# Camera-host scripts

## `live_stream_producer.py` — seeing-monitor video

Streams the seeing camera to the backend as real H.264 video. Frames from the QHY camera module
are fed to `ffmpeg` at a fixed frame rate, encoded with `libx264` (one MP4 fragment per frame,
a keyframe every second), and pushed to `POST /api/live/ingest` in one long-lived chunked HTTP
request. The backend relays the stream to browsers (`GET /api/live/stream.mp4`, played through
Media Source Extensions on the site's Seeing Monitor page).

Once a second the script also posts telemetry (`POST /api/live/telemetry`): capture timestamp
(for the latency figure), the star centroid and its RMS wander, exposure, gain and frame rate.
The reply carries the exposure/gain chosen on the website, which the camera applies immediately.

### Requirements

- Python 3.8+ with `numpy`
- `ffmpeg` with `libx264` (`sudo apt install ffmpeg`)
- the camera driver directory (`allSkyCamera_device/camera`) for `--camera`

### Usage

```bash
# synthetic scene, useful to check the whole path without hardware
python3 live_stream_producer.py --mock --backend https://armageddon.deepspace.ucsb.edu --insecure-tls --token "$TOKEN"

# real camera
python3 live_stream_producer.py --camera --camera-path /home/pi/allSkyCamera/camera \
    --backend https://armageddon.deepspace.ucsb.edu --insecure-tls --token "$TOKEN"
```

Environment variables `CAMSERVER_BACKEND`, `CAMSERVER_LIVE_INGEST_TOKEN` and
`CAMSERVER_TLS_INSECURE=1` replace `--backend`, `--token` and `--insecure-tls`.
`seeing-monitor-stream.service` is a systemd unit template for running it permanently.

Useful options: `--fps` (default 5), `--max-width` (downscale, default 1920; `0` keeps the native
3856 px), `--roi x,y,w,h`, `--preset`/`--crf`/`--max-bitrate` for quality vs CPU, `--encoder` to
use a hardware encoder ffmpeg knows about, `--stretch` to normalise dim frames.

The token is whatever `CAMSERVER_LIVE_INGEST_TOKEN` is set to in the backend's service unit;
when the backend has no token configured the header is simply ignored.
