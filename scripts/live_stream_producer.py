#!/usr/bin/env python3
"""
Seeing-monitor live video producer.

Captures frames from the QHY camera module (or a synthetic scene with --mock), encodes them to
H.264 with ffmpeg, and pushes the resulting fragmented MP4 to the CamServer backend over a single
long-lived HTTP POST (/api/live/ingest). A side channel (/api/live/telemetry) reports the capture
timestamp, the measured star position and the camera settings once per second and picks up the
exposure/gain requested on the website.

The video is a constant-frame-rate stream: the encoder is fed at --fps regardless of how fast the
camera delivers frames, repeating the last frame when a long exposure is still running, so the
viewer never stalls and the fragment cadence stays predictable.

Requirements: Python 3.8+, numpy, ffmpeg with libx264 (Debian/Ubuntu: apt install ffmpeg).

Examples:
  python3 live_stream_producer.py --mock --backend http://localhost:8080
  python3 live_stream_producer.py --camera --camera-path /home/pi/allSkyCamera/camera \
      --backend https://armageddon.deepspace.ucsb.edu --insecure-tls --token "$CAMSERVER_LIVE_INGEST_TOKEN"
"""

from __future__ import annotations

import argparse
import datetime as dt
import http.client
import json
import math
import os
import shutil
import signal
import socket
import ssl
import subprocess
import sys
import threading
import time
import traceback
import urllib.parse
from collections import deque
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Deque, Dict, List, Optional, Tuple

try:
    import numpy as np
except ImportError:  # pragma: no cover - numpy is a hard requirement
    print("numpy is required: pip install numpy", file=sys.stderr)
    raise SystemExit(2)


DEFAULT_BACKEND = os.environ.get("CAMSERVER_BACKEND", "https://armageddon.deepspace.ucsb.edu")
DEFAULT_CAMERA_PATH = "../allSkyCamera_device/camera"
INGEST_PATH = "/api/live/ingest"
TELEMETRY_PATH = "/api/live/telemetry"
MIN_EXPOSURE_US = 100
MAX_EXPOSURE_US = 100_000_000
READ_CHUNK = 64 * 1024


def log(message: str) -> None:
    stamp = dt.datetime.now().strftime("%H:%M:%S")
    print(f"[{stamp}] {message}", flush=True)


# --------------------------------------------------------------------------- settings


def clamp_exposure(value: int) -> int:
    return max(MIN_EXPOSURE_US, min(MAX_EXPOSURE_US, int(value)))


class Settings:
    """Exposure/gain shared between the telemetry thread (writer) and capture thread (reader)."""

    def __init__(self, exposure: int, gain: int) -> None:
        self._lock = threading.Lock()
        self.exposure = clamp_exposure(exposure)
        self.gain = max(1, int(gain))

    def update(self, exposure: Any = None, gain: Any = None) -> bool:
        changed = False
        with self._lock:
            try:
                if exposure is not None:
                    value = clamp_exposure(int(float(exposure)))
                    changed |= value != self.exposure
                    self.exposure = value
                if gain is not None:
                    value = max(1, int(float(gain)))
                    changed |= value != self.gain
                    self.gain = value
            except (TypeError, ValueError):
                return False
        return changed

    def snapshot(self) -> Tuple[int, int]:
        with self._lock:
            return self.exposure, self.gain


# --------------------------------------------------------------------------- frames


@dataclass
class Frame:
    data: bytes
    width: int
    height: int
    channels: int
    index: int
    captured_at: float
    actual_exposure_s: float
    star: Optional[Tuple[float, float, float]]


def measure_star(gray: np.ndarray) -> Optional[Tuple[float, float, float]]:
    """
    Locates the brightest star and returns (x, y, peak) with a sub-pixel weighted centroid,
    or None when nothing stands out from the background.
    """
    if gray.ndim != 2 or gray.size == 0:
        return None
    step = max(1, min(gray.shape) // 512)
    coarse = gray[::step, ::step]
    flat_index = int(np.argmax(coarse))
    cy, cx = divmod(flat_index, coarse.shape[1])
    cy *= step
    cx *= step

    half = 32
    y0, y1 = max(0, cy - half), min(gray.shape[0], cy + half + 1)
    x0, x1 = max(0, cx - half), min(gray.shape[1], cx + half + 1)
    window = gray[y0:y1, x0:x1].astype(np.float32)
    if window.size == 0:
        return None
    background = float(np.median(window))
    peak = float(window.max())
    if peak - background < 20.0:
        return None

    weights = window - background
    threshold = 0.25 * (peak - background)
    weights[weights < threshold] = 0.0
    total = float(weights.sum())
    if total <= 0.0:
        return None
    ys, xs = np.indices(weights.shape)
    x = x0 + float((xs * weights).sum() / total)
    y = y0 + float((ys * weights).sum() / total)
    return x, y, peak


class StarTracker:
    """Keeps recent centroids and formats the position string shown on the site."""

    def __init__(self, window: int = 30) -> None:
        self._points: Deque[Tuple[float, float]] = deque(maxlen=window)
        self._lock = threading.Lock()

    def add(self, star: Optional[Tuple[float, float, float]]) -> None:
        with self._lock:
            if star is None:
                self._points.clear()
            else:
                self._points.append((star[0], star[1]))

    def describe(self) -> str:
        with self._lock:
            points = list(self._points)
        if not points:
            return "no star detected"
        xs = np.array([p[0] for p in points])
        ys = np.array([p[1] for p in points])
        rms = math.sqrt(float(((xs - xs.mean()) ** 2 + (ys - ys.mean()) ** 2).mean()))
        return f"x: {xs[-1]:.2f}, y: {ys[-1]:.2f}, rms error: {rms:.2f}"


def to_8bit(frame: np.ndarray, stretch: bool) -> np.ndarray:
    array = np.asarray(frame)
    if array.ndim == 3 and array.shape[0] in (1, 3) and array.shape[-1] not in (1, 3):
        array = np.transpose(array, (1, 2, 0))
    if array.ndim == 3 and array.shape[-1] == 1:
        array = array[..., 0]

    if stretch:
        values = array.astype(np.float32)
        low = float(values.min())
        high = float(values.max())
        if high > low:
            values = (values - low) * (255.0 / (high - low))
        return np.ascontiguousarray(values.clip(0, 255).astype(np.uint8))

    if array.dtype == np.uint8:
        return np.ascontiguousarray(array)
    if array.dtype == np.uint16:
        return np.ascontiguousarray((array >> 8).astype(np.uint8))
    return np.ascontiguousarray(np.clip(array, 0, 255).astype(np.uint8))


# --------------------------------------------------------------------------- sources


class MockSource:
    """A drifting, twinkling star over a noisy sky, for testing without hardware."""

    def __init__(self, width: int, height: int) -> None:
        self.width = width
        self.height = height
        y, x = np.mgrid[0:height, 0:width]
        gradient = 18 + 20 * (y / max(1, height - 1)) + 8 * np.sin(x * 0.01)
        self.background = gradient.astype(np.float32)
        rng = np.random.default_rng(7)
        for _ in range(60):
            sx, sy = int(rng.integers(0, width)), int(rng.integers(0, height))
            self.background[max(0, sy - 1):sy + 2, max(0, sx - 1):sx + 2] += float(rng.integers(40, 140))
        self.rng = np.random.default_rng()
        self.index = 0

    def read(self, settings: Settings) -> Tuple[np.ndarray, float]:
        exposure_us, gain = settings.snapshot()
        t = self.index * 0.2
        frame = self.background + self.rng.normal(0, 4, size=self.background.shape).astype(np.float32)
        cx = self.width * 0.5 + 60 * math.sin(t * 0.13) + self.rng.normal(0, 1.2)
        cy = self.height * 0.5 + 40 * math.cos(t * 0.09) + self.rng.normal(0, 1.2)
        radius = 24
        x0, y0 = int(cx) - radius, int(cy) - radius
        yy, xx = np.mgrid[y0:y0 + 2 * radius, x0:x0 + 2 * radius]
        brightness = min(230.0, 60.0 + gain * 1.5 + exposure_us / 200.0)
        sigma = 2.2 + 0.6 * abs(math.sin(t * 0.5))
        star = brightness * np.exp(-(((xx - cx) ** 2) + ((yy - cy) ** 2)) / (2 * sigma ** 2))
        ys = slice(max(0, y0), min(self.height, y0 + 2 * radius))
        xs = slice(max(0, x0), min(self.width, x0 + 2 * radius))
        frame[ys, xs] += star[ys.start - y0:ys.stop - y0, xs.start - x0:xs.stop - x0]
        self.index += 1
        time.sleep(0.05)
        return frame.clip(0, 255).astype(np.uint8), exposure_us / 1e6


class CameraSource:
    """Frames from the QHY camera driver in allSkyCamera_device/camera/camera.py."""

    def __init__(self, camera_path: str, roi: Optional[Tuple[Tuple[int, int], Tuple[int, int]]], bit_depth: int) -> None:
        resolved = Path(camera_path).expanduser().resolve()
        sys.path.insert(0, str(resolved))
        from camera import Camera  # type: ignore

        self.camera = Camera()
        self.camera.config_continuous_mode()
        self.roi = roi
        self.bit_depth = bit_depth
        log(f"camera {self.camera.camera_id} ready: {self.camera.resolution[0]}x{self.camera.resolution[1]}, "
            f"{'color' if self.camera.color else 'mono'}")

    def read(self, settings: Settings) -> Tuple[np.ndarray, float]:
        exposure_us, gain = settings.snapshot()
        frame, actual = self.camera.expose(int(exposure_us), gain=int(gain), bbp=self.bit_depth, roi=self.roi)
        # The driver reuses its buffer for the next exposure, so take a copy now.
        return np.array(frame, copy=True), float(actual)

    def close(self) -> None:
        try:
            self.camera.close()
        except Exception:
            pass


# --------------------------------------------------------------------------- capture thread


class Capture(threading.Thread):
    def __init__(self, source: Any, settings: Settings, stretch: bool, stop_event: threading.Event) -> None:
        super().__init__(name="capture", daemon=True)
        self.source = source
        self.settings = settings
        self.stretch = stretch
        self.stop_event = stop_event
        self.tracker = StarTracker()
        self._lock = threading.Lock()
        self._latest: Optional[Frame] = None
        self.frames = 0
        self.errors = 0
        self._recent: Deque[float] = deque(maxlen=50)

    def latest(self) -> Optional[Frame]:
        with self._lock:
            return self._latest

    def capture_fps(self) -> float:
        with self._lock:
            stamps = list(self._recent)
        if len(stamps) < 2 or stamps[-1] <= stamps[0]:
            return 0.0
        return (len(stamps) - 1) / (stamps[-1] - stamps[0])

    def run(self) -> None:
        while not self.stop_event.is_set():
            try:
                raw, actual = self.source.read(self.settings)
                image = to_8bit(raw, self.stretch)
                gray = image if image.ndim == 2 else image.mean(axis=2).astype(np.uint8)
                star = measure_star(gray)
                self.tracker.add(star)
                channels = 1 if image.ndim == 2 else image.shape[2]
                frame = Frame(
                    data=image.tobytes(),
                    width=image.shape[1],
                    height=image.shape[0],
                    channels=channels,
                    index=self.frames,
                    captured_at=time.time(),
                    actual_exposure_s=actual,
                    star=star,
                )
                with self._lock:
                    self._latest = frame
                    self.frames += 1
                    self._recent.append(frame.captured_at)
            except Exception as exc:
                self.errors += 1
                log(f"capture failed: {exc}")
                if self.errors <= 3:
                    traceback.print_exc()
                time.sleep(1.0)


# --------------------------------------------------------------------------- ffmpeg encoder


class Encoder:
    def __init__(self, args: argparse.Namespace, width: int, height: int, channels: int) -> None:
        self.args = args
        self.width = width
        self.height = height
        self.channels = channels
        self.process: Optional[subprocess.Popen] = None
        self.frames_written = 0
        self._stderr_thread: Optional[threading.Thread] = None
        self.last_error = ""

    def command(self) -> List[str]:
        a = self.args
        fps = max(1, int(round(a.fps)))
        gop = max(1, int(round(a.fps * a.keyframe_interval)))
        cmd = [
            a.ffmpeg, "-hide_banner", "-loglevel", "warning", "-nostdin",
            "-f", "rawvideo", "-pix_fmt", "gray" if self.channels == 1 else "rgb24",
            "-video_size", f"{self.width}x{self.height}", "-framerate", str(fps),
            "-i", "pipe:0",
        ]
        filters = []
        if a.max_width and self.width > a.max_width:
            filters.append(f"scale={a.max_width}:-2")
        if filters:
            cmd += ["-vf", ",".join(filters)]
        cmd += [
            "-c:v", a.encoder, "-pix_fmt", "yuv420p",
            "-g", str(gop), "-keyint_min", str(gop), "-sc_threshold", "0", "-bf", "0",
        ]
        if a.encoder == "libx264":
            cmd += ["-preset", a.preset, "-tune", "zerolatency", "-crf", str(a.crf)]
            if a.max_bitrate:
                cmd += ["-maxrate", a.max_bitrate, "-bufsize", a.max_bitrate]
        else:
            cmd += ["-b:v", a.max_bitrate or "4M"]
        movflags = "empty_moov+default_base_moof+" + ("frag_every_frame" if a.frag_every_frame else "frag_keyframe")
        cmd += ["-f", "mp4", "-movflags", movflags, "-flush_packets", "1", "pipe:1"]
        return cmd

    def start(self) -> None:
        cmd = self.command()
        log("starting encoder: " + " ".join(cmd))
        self.process = subprocess.Popen(
            cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0
        )
        self._stderr_thread = threading.Thread(target=self._drain_stderr, name="ffmpeg-stderr", daemon=True)
        self._stderr_thread.start()

    def _drain_stderr(self) -> None:
        process = self.process
        if process is None or process.stderr is None:
            return
        for raw in iter(process.stderr.readline, b""):
            line = raw.decode("utf-8", "replace").rstrip()
            if line:
                self.last_error = line
                log(f"ffmpeg: {line}")

    def write(self, frame: bytes) -> bool:
        process = self.process   # stop() may clear it from another thread at any time
        if process is None or process.stdin is None:
            return False
        try:
            view = memoryview(frame)
            while len(view):
                written = process.stdin.write(view)
                if not written:
                    return False
                view = view[written:]
            self.frames_written += 1
            return True
        except (BrokenPipeError, OSError, ValueError, AttributeError):
            return False

    def read(self) -> bytes:
        process = self.process
        if process is None or process.stdout is None:
            return b""
        try:
            return os.read(process.stdout.fileno(), READ_CHUNK)
        except (OSError, ValueError):
            return b""

    def alive(self) -> bool:
        return self.process is not None and self.process.poll() is None

    def stop(self) -> None:
        process = self.process
        self.process = None
        if process is None:
            return
        for stream in (process.stdin, process.stdout):
            try:
                if stream:
                    stream.close()
            except OSError:
                pass
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


class Pacer(threading.Thread):
    """Feeds the newest frame to ffmpeg at a fixed rate, repeating it while the camera is busy."""

    def __init__(self, capture: Capture, encoder: Encoder, fps: float, stop_event: threading.Event) -> None:
        super().__init__(name="pacer", daemon=True)
        self.capture = capture
        self.encoder = encoder
        self.interval = 1.0 / max(0.1, fps)
        self.stop_event = stop_event
        self.failed = threading.Event()
        self.repeated = 0
        self._last_index = -1

    def run(self) -> None:
        next_tick = time.monotonic()
        while not self.stop_event.is_set() and not self.failed.is_set():
            frame = self.capture.latest()
            if frame is None:
                time.sleep(0.02)
                next_tick = time.monotonic()
                continue
            if frame.width != self.encoder.width or frame.height != self.encoder.height or frame.channels != self.encoder.channels:
                self.failed.set()   # geometry changed: the session restarts with a new encoder
                break
            if frame.index == self._last_index:
                self.repeated += 1
            self._last_index = frame.index
            if not self.encoder.write(frame.data):
                self.failed.set()
                break
            next_tick += self.interval
            delay = next_tick - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            else:
                next_tick = time.monotonic()


# --------------------------------------------------------------------------- backend client


class Backend:
    def __init__(self, base_url: str, token: str, insecure_tls: bool, timeout: float) -> None:
        parsed = urllib.parse.urlparse(base_url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            raise ValueError(f"invalid backend URL: {base_url}")
        self.scheme = parsed.scheme
        self.host = parsed.hostname
        self.port = parsed.port or (443 if parsed.scheme == "https" else 80)
        self.base_path = parsed.path.rstrip("/")
        self.token = token
        self.timeout = timeout
        self.context: Optional[ssl.SSLContext] = None
        if self.scheme == "https":
            self.context = ssl._create_unverified_context() if insecure_tls else ssl.create_default_context()

    def describe(self) -> str:
        return f"{self.scheme}://{self.host}:{self.port}{self.base_path}"

    def connection(self, timeout: Optional[float] = None) -> http.client.HTTPConnection:
        timeout = self.timeout if timeout is None else timeout
        if self.scheme == "https":
            return http.client.HTTPSConnection(self.host, self.port, timeout=timeout, context=self.context)
        return http.client.HTTPConnection(self.host, self.port, timeout=timeout)

    def headers(self) -> Dict[str, str]:
        headers = {"User-Agent": "camserver-live-producer/2"}
        if self.token:
            headers["X-Live-Token"] = self.token
        return headers

    def post_json(self, path: str, payload: Dict[str, Any]) -> Tuple[int, Dict[str, Any]]:
        body = json.dumps(payload).encode("utf-8")
        headers = self.headers()
        headers["Content-Type"] = "application/json"
        conn = self.connection()
        try:
            conn.request("POST", self.base_path + path, body=body, headers=headers)
            response = conn.getresponse()
            raw = response.read()
            try:
                data = json.loads(raw.decode("utf-8")) if raw else {}
            except ValueError:
                data = {"raw": raw[:200].decode("utf-8", "replace")}
            return response.status, data
        finally:
            conn.close()

    def open_ingest(self, producer_info: Dict[str, Any]) -> http.client.HTTPConnection:
        conn = self.connection()
        conn.putrequest("POST", self.base_path + INGEST_PATH, skip_accept_encoding=True)
        for key, value in self.headers().items():
            conn.putheader(key, value)
        conn.putheader("Content-Type", "video/mp4")
        conn.putheader("Transfer-Encoding", "chunked")
        conn.putheader("X-Live-Producer", json.dumps(producer_info, separators=(",", ":")))
        conn.endheaders()
        return conn

    @staticmethod
    def send_chunk(conn: http.client.HTTPConnection, data: bytes) -> None:
        conn.send(b"%X\r\n" % len(data) + data + b"\r\n")

    @staticmethod
    def finish(conn: http.client.HTTPConnection) -> Optional[str]:
        try:
            conn.send(b"0\r\n\r\n")
            conn.sock.settimeout(3.0)  # type: ignore[union-attr]
            response = conn.getresponse()
            return f"HTTP {response.status}: {response.read(300).decode('utf-8', 'replace')}"
        except Exception:
            return None
        finally:
            conn.close()


# --------------------------------------------------------------------------- telemetry thread


class Telemetry(threading.Thread):
    def __init__(self, backend: Backend, capture: Capture, settings: Settings, stop_event: threading.Event,
                 state: "SessionState", poll_settings: bool) -> None:
        super().__init__(name="telemetry", daemon=True)
        self.backend = backend
        self.capture = capture
        self.settings = settings
        self.stop_event = stop_event
        self.state = state
        self.poll_settings = poll_settings
        self.failures = 0
        self.last_latency_ms: Optional[int] = None

    def payload(self) -> Dict[str, Any]:
        frame = self.capture.latest()
        exposure_us, gain = self.settings.snapshot()
        payload: Dict[str, Any] = {
            "pos": self.capture.tracker.describe(),
            "exposureUs": exposure_us,
            "gain": gain,
            "captureFps": round(self.capture.capture_fps(), 2),
            "framesCaptured": self.capture.frames,
            "encoder": self.state.encoder_name,
            "streaming": self.state.streaming,
            "host": socket.gethostname(),
        }
        if frame is not None:
            payload["ts"] = dt.datetime.fromtimestamp(frame.captured_at).strftime("%S.%f")
            payload["actualExposureMs"] = round(frame.actual_exposure_s * 1000.0, 1)
            payload["frame"] = f"{frame.width}x{frame.height}"
            if frame.star is not None:
                payload["starPeak"] = round(frame.star[2], 1)
        return payload

    def run(self) -> None:
        while not self.stop_event.is_set():
            started = time.monotonic()
            try:
                status, data = self.backend.post_json(TELEMETRY_PATH, self.payload())
                if status == 401:
                    log("telemetry rejected: wrong or missing --token (HTTP 401)")
                    self.failures += 1
                elif status != 200:
                    log(f"telemetry failed: HTTP {status} {data}")
                    self.failures += 1
                else:
                    self.failures = 0
                    self.last_latency_ms = data.get("latencyMs")
                    settings = data.get("settings") if self.poll_settings else None
                    if isinstance(settings, dict) and self.settings.update(settings.get("exposure"), settings.get("gain")):
                        exposure_us, gain = self.settings.snapshot()
                        log(f"settings from site: exposure={exposure_us}us gain={gain}")
            except Exception as exc:
                self.failures += 1
                if self.failures <= 3 or self.failures % 30 == 0:
                    log(f"telemetry error: {exc}")
            self.stop_event.wait(max(0.2, 1.0 - (time.monotonic() - started)))


@dataclass
class SessionState:
    encoder_name: str = ""
    streaming: bool = False
    bytes_sent: int = 0
    sessions: int = 0


# --------------------------------------------------------------------------- session


def run_session(args: argparse.Namespace, backend: Backend, capture: Capture, state: SessionState,
                stop_event: threading.Event) -> str:
    """Runs one encoder + one ingest connection until something breaks. Returns the reason."""
    frame = capture.latest()
    while frame is None and not stop_event.is_set():
        time.sleep(0.1)
        frame = capture.latest()
    if frame is None:
        return "stopped"

    encoder = Encoder(args, frame.width, frame.height, frame.channels)
    encoder.start()
    state.encoder_name = args.encoder
    pacer = Pacer(capture, encoder, args.fps, stop_event)
    pacer.start()

    producer_info = {
        "host": socket.gethostname(),
        "source": "mock" if args.mock else "camera",
        "fps": args.fps,
        "encoder": args.encoder,
        "input": f"{frame.width}x{frame.height}x{frame.channels}",
    }
    conn: Optional[http.client.HTTPConnection] = None
    reason = "unknown"
    sent = 0
    last_status = time.monotonic()
    try:
        conn = backend.open_ingest(producer_info)
        state.streaming = True
        state.sessions += 1
        log(f"ingest connection open to {backend.describe()}{INGEST_PATH}")
        while not stop_event.is_set():
            if pacer.failed.is_set():
                reason = "encoder input closed (frame geometry changed or ffmpeg exited)"
                break
            data = encoder.read()
            if not data:
                reason = f"ffmpeg exited ({encoder.last_error or 'no error output'})"
                break
            Backend.send_chunk(conn, data)
            sent += len(data)
            state.bytes_sent += len(data)
            now = time.monotonic()
            if now - last_status >= args.status_every:
                exposure_us, gain = capture.settings.snapshot()
                log(f"streaming: sent={sent / 1e6:.1f}MB captured={capture.frames} "
                    f"({capture.capture_fps():.1f} fps) encoded={encoder.frames_written} repeated={pacer.repeated} "
                    f"exposure={exposure_us}us gain={gain} star={capture.tracker.describe()}")
                last_status = now
        else:
            reason = "stopped"
    except (BrokenPipeError, ConnectionError, socket.timeout, OSError, http.client.HTTPException) as exc:
        reason = f"connection failed: {exc}"
    finally:
        state.streaming = False
        pacer.failed.set()
        encoder.stop()
        pacer.join(timeout=2)
        if conn is not None:
            summary = Backend.finish(conn) if reason == "stopped" else None
            if summary:
                log(f"server closed the session: {summary}")
            else:
                try:
                    conn.close()
                except Exception:
                    pass
    return reason


def preflight(backend: Backend, settings: Settings) -> None:
    """Fails fast on a wrong token or unreachable backend before the camera starts streaming."""
    delay = 2.0
    while True:
        try:
            status, data = backend.post_json(TELEMETRY_PATH, {"pos": "starting", "host": socket.gethostname()})
        except Exception as exc:
            log(f"backend {backend.describe()} not reachable ({exc}); retrying in {delay:.0f}s")
            time.sleep(delay)
            delay = min(30.0, delay * 2)
            continue
        if status == 401:
            raise SystemExit("the backend rejected the ingest token (HTTP 401); pass --token or set CAMSERVER_LIVE_INGEST_TOKEN")
        if status == 404:
            raise SystemExit(f"{backend.describe()}{TELEMETRY_PATH} is missing: the backend is too old for this producer")
        if status != 200:
            log(f"unexpected reply from backend: HTTP {status} {data}; retrying in {delay:.0f}s")
            time.sleep(delay)
            delay = min(30.0, delay * 2)
            continue
        served = data.get("settings")
        if isinstance(served, dict) and settings.update(served.get("exposure"), served.get("gain")):
            exposure_us, gain = settings.snapshot()
            log(f"initial settings from site: exposure={exposure_us}us gain={gain}")
        log(f"backend {backend.describe()} accepted the token")
        return


# --------------------------------------------------------------------------- cli


def parse_roi(value: str) -> Optional[Tuple[Tuple[int, int], Tuple[int, int]]]:
    if value.strip().lower() in ("", "full", "none"):
        return None
    parts = [int(part.strip()) for part in value.split(",")]
    if len(parts) != 4:
        raise argparse.ArgumentTypeError('--roi must be x,y,width,height or "full"')
    x, y, width, height = parts
    if width <= 0 or height <= 0:
        raise argparse.ArgumentTypeError("--roi width and height must be positive")
    return (x, y), (width, height)


def parse_size(value: str) -> Tuple[int, int]:
    try:
        width, height = value.lower().split("x")
        return max(16, int(width)), max(16, int(height))
    except ValueError as exc:
        raise argparse.ArgumentTypeError("size must look like 1920x1080") from exc


def ffmpeg_supports_frag_every_frame(ffmpeg: str) -> bool:
    try:
        output = subprocess.run([ffmpeg, "-hide_banner", "-h", "muxer=mp4"], capture_output=True, text=True, timeout=10).stdout
    except (OSError, subprocess.SubprocessError):
        return False
    return "frag_every_frame" in output


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Stream the seeing-monitor camera to the CamServer backend as H.264 video.")
    parser.add_argument("--backend", default=DEFAULT_BACKEND, help=f"Backend base URL (default: {DEFAULT_BACKEND}, env CAMSERVER_BACKEND)")
    parser.add_argument("--token", default=os.environ.get("CAMSERVER_LIVE_INGEST_TOKEN", ""), help="Ingest token (env CAMSERVER_LIVE_INGEST_TOKEN)")
    parser.add_argument("--insecure-tls", action="store_true", default=os.environ.get("CAMSERVER_TLS_INSECURE") == "1",
                        help="Do not verify the backend certificate (needed for the self-signed one)")
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--camera", action="store_true", help="Use the QHY camera module (default)")
    source.add_argument("--mock", action="store_true", help="Generate a synthetic scene instead of using the camera")
    parser.add_argument("--camera-path", default=DEFAULT_CAMERA_PATH, help=f"Directory containing camera.py (default: {DEFAULT_CAMERA_PATH})")
    parser.add_argument("--roi", type=parse_roi, default=None, help='Camera ROI as x,y,width,height (default: full frame)')
    parser.add_argument("--bit-depth", type=int, choices=(8, 16), default=8, help="Camera readout depth (default: 8)")
    parser.add_argument("--exposure", type=int, default=1000, help="Initial exposure in microseconds until the site sets one (default: 1000)")
    parser.add_argument("--gain", type=int, default=1, help="Initial gain until the site sets one (default: 1)")
    parser.add_argument("--stretch", action="store_true", help="Normalise each frame to its min/max before encoding")
    parser.add_argument("--mock-size", type=parse_size, default=(1920, 1080), help="Synthetic frame size (default: 1920x1080)")
    parser.add_argument("--fps", type=float, default=5.0, help="Video frame rate fed to the encoder (default: 5)")
    parser.add_argument("--keyframe-interval", type=float, default=1.0, help="Seconds between keyframes; also the maximum join delay (default: 1)")
    parser.add_argument("--max-width", type=int, default=1920, help="Downscale wider frames to this width; 0 keeps the native size (default: 1920)")
    parser.add_argument("--encoder", default="libx264", help="ffmpeg video encoder (default: libx264; e.g. h264_v4l2m2m, h264_nvenc)")
    parser.add_argument("--preset", default="ultrafast", help="libx264 preset (default: ultrafast)")
    parser.add_argument("--crf", type=int, default=23, help="libx264 quality, lower is better (default: 23)")
    parser.add_argument("--max-bitrate", default="4M", help="Bitrate cap such as 4M; empty for none (default: 4M)")
    parser.add_argument("--ffmpeg", default=os.environ.get("FFMPEG", "ffmpeg"), help="ffmpeg executable (default: ffmpeg on PATH)")
    parser.add_argument("--poll-settings", action=argparse.BooleanOptionalAction, default=True, help="Apply exposure/gain chosen on the site (default: on)")
    parser.add_argument("--timeout", type=float, default=20.0, help="Socket timeout in seconds (default: 20)")
    parser.add_argument("--status-every", type=float, default=10.0, help="Seconds between status lines (default: 10)")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    args.backend = args.backend.rstrip("/")
    if not args.mock:
        args.camera = True

    if shutil.which(args.ffmpeg) is None and not os.path.exists(args.ffmpeg):
        print(f"ffmpeg not found ({args.ffmpeg}); install it with: sudo apt install ffmpeg", file=sys.stderr)
        return 2
    args.frag_every_frame = ffmpeg_supports_frag_every_frame(args.ffmpeg)
    if not args.frag_every_frame:
        log("this ffmpeg lacks frag_every_frame; fragments will follow keyframes (higher latency)")

    backend = Backend(args.backend, args.token, args.insecure_tls, args.timeout)
    settings = Settings(args.exposure, args.gain)
    stop_event = threading.Event()

    def request_stop(signum: int, _frame: Any) -> None:
        log(f"signal {signum}: stopping")
        stop_event.set()

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)

    if args.mock:
        source: Any = MockSource(*args.mock_size)
        log(f"mock source {args.mock_size[0]}x{args.mock_size[1]}")
    else:
        source = CameraSource(args.camera_path, args.roi, args.bit_depth)

    capture = Capture(source, settings, args.stretch, stop_event)
    capture.start()
    preflight(backend, settings)

    state = SessionState()
    telemetry = Telemetry(backend, capture, settings, stop_event, state, args.poll_settings)
    telemetry.start()

    delay = 1.0
    while not stop_event.is_set():
        started = time.monotonic()
        reason = run_session(args, backend, capture, state, stop_event)
        if stop_event.is_set():
            break
        if time.monotonic() - started > 30:
            delay = 1.0
        log(f"session ended ({reason}); reconnecting in {delay:.0f}s")
        stop_event.wait(delay)
        delay = min(30.0, delay * 2)

    if hasattr(source, "close"):
        source.close()
    log(f"stopped after {state.sessions} session(s), {state.bytes_sent / 1e6:.1f} MB sent")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
