package edu.camserver.app.service;

import edu.camserver.app.live.FragmentInfo;
import edu.camserver.app.live.TrackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Relays one live fragmented-MP4 video stream from the camera host to any number of viewers.
 *
 * <p>The producer pushes {@code ftyp}+{@code moov} (the initialization segment) followed by
 * {@code moof}+{@code mdat} fragments. This service keeps the initialization segment and the
 * fragments of the current group of pictures, so a viewer that joins mid-stream immediately gets
 * everything it needs to start decoding at the most recent keyframe, then receives new fragments
 * as they arrive. Viewers that cannot keep up are disconnected rather than allowed to buffer
 * without bound; the browser player simply reconnects.
 */
@Service
public class LiveStreamService {

    private static final Logger log = LoggerFactory.getLogger(LiveStreamService.class);

    /** Queue marker telling a viewer that its stream is over. */
    public static final byte[] END_OF_STREAM = new byte[0];

    /** One producer connection. */
    public static final class Session {
        private final long id;
        private final String producer;
        private final String remoteAddress;
        private final Instant startedAt = Instant.now();
        private volatile byte[] init;
        private volatile TrackInfo track;
        private volatile Instant lastFragmentAt;
        private volatile FragmentInfo lastFragment;
        private final AtomicLong fragments = new AtomicLong();
        private final AtomicLong bytes = new AtomicLong();

        private Session(long id, String producer, String remoteAddress) {
            this.id = id;
            this.producer = producer;
            this.remoteAddress = remoteAddress;
        }

        public long id() {
            return id;
        }

        public TrackInfo track() {
            return track;
        }

        public long fragments() {
            return fragments.get();
        }

        public long bytes() {
            return bytes.get();
        }
    }

    /** One viewer connection. */
    public static final class Viewer {
        private final Session session;
        private final byte[] init;
        private final List<byte[]> backlog;
        private final BlockingQueue<byte[]> queue;
        private final Instant startedAt = Instant.now();
        private volatile boolean primed;
        private volatile boolean active = true;

        private Viewer(Session session, byte[] init, List<byte[]> backlog, int queueLimit) {
            this.session = session;
            this.init = init;
            this.backlog = backlog;
            this.queue = new LinkedBlockingQueue<>(queueLimit);
            this.primed = !backlog.isEmpty();
        }

        public Session session() {
            return session;
        }

        public byte[] init() {
            return init;
        }

        public List<byte[]> backlog() {
            return backlog;
        }

        public BlockingQueue<byte[]> queue() {
            return queue;
        }

        public boolean isActive() {
            return active;
        }
    }

    private record Fragment(byte[] bytes, FragmentInfo info) {
    }

    private final Object lock = new Object();
    private final AtomicLong sessionCounter = new AtomicLong();
    private final Set<Viewer> viewers = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<Fragment> currentGop = new ArrayDeque<>();
    private final int viewerQueueLimit;

    private Session current;
    private volatile Instant lastSessionEndedAt;
    private volatile String lastSessionEndReason;

    public LiveStreamService(@Value("${app.live.viewer-queue-limit:600}") int viewerQueueLimit) {
        this.viewerQueueLimit = Math.max(8, viewerQueueLimit);
    }

    /**
     * Starts a new producer session. Any previous session is ended and its viewers disconnected,
     * so a producer that reconnects after a network hiccup takes over immediately.
     */
    public Session openSession(String producer, String remoteAddress) {
        synchronized (lock) {
            if (current != null) {
                log.info("Live stream: producer session {} replaced by a new connection from {}",
                        current.id, remoteAddress);
                endViewers();
                lastSessionEndedAt = Instant.now();
                lastSessionEndReason = "replaced by a new producer connection";
            }
            current = new Session(sessionCounter.incrementAndGet(), producer, remoteAddress);
            currentGop.clear();
            log.info("Live stream: producer session {} started from {} ({})",
                    current.id, remoteAddress, producer == null ? "no producer info" : producer);
            return current;
        }
    }

    public boolean isCurrent(Session session) {
        return current == session;
    }

    public void closeSession(Session session, String reason) {
        synchronized (lock) {
            if (current != session) {
                return;
            }
            current = null;
            currentGop.clear();
            endViewers();
            lastSessionEndedAt = Instant.now();
            lastSessionEndReason = reason;
            log.info("Live stream: producer session {} ended after {} fragments, {} bytes: {}",
                    session.id, session.fragments.get(), session.bytes.get(), reason);
        }
    }

    /** Records the initialization segment; viewers already attached must re-initialize. */
    public void publishInit(Session session, byte[] init, TrackInfo track) {
        synchronized (lock) {
            if (current != session) {
                return;
            }
            boolean reinit = session.init != null;
            session.init = init;
            session.track = track;
            currentGop.clear();
            if (reinit) {
                endViewers();
            }
            log.info("Live stream: session {} video track {} {}x{} timescale {}",
                    session.id, track.codecs(), track.width(), track.height(), track.timescale());
        }
    }

    public void publishFragment(Session session, byte[] bytes, FragmentInfo info) {
        synchronized (lock) {
            if (current != session || session.init == null) {
                return;
            }
            if (info.keyframe()) {
                currentGop.clear();
            }
            currentGop.add(new Fragment(bytes, info));
            session.fragments.incrementAndGet();
            session.bytes.addAndGet(bytes.length);
            session.lastFragmentAt = Instant.now();
            session.lastFragment = info;

            for (Viewer viewer : viewers) {
                if (viewer.session != session) {
                    continue;
                }
                if (!viewer.primed) {
                    if (!info.keyframe()) {
                        continue;
                    }
                    viewer.primed = true;
                }
                if (!viewer.queue.offer(bytes)) {
                    log.warn("Live stream: dropping viewer that fell {} fragments behind", viewerQueueLimit);
                    dropViewer(viewer);
                }
            }
        }
    }

    /**
     * Attaches a viewer to the current session.
     *
     * @return empty when no producer is streaming, or when the stream has not delivered its
     *         initialization segment yet
     */
    public Optional<Viewer> subscribe() {
        synchronized (lock) {
            if (current == null || current.init == null) {
                return Optional.empty();
            }
            List<byte[]> backlog = new ArrayList<>();
            if (!currentGop.isEmpty() && currentGop.peekFirst().info().keyframe()) {
                for (Fragment fragment : currentGop) {
                    backlog.add(fragment.bytes());
                }
            }
            Viewer viewer = new Viewer(current, current.init, Collections.unmodifiableList(backlog), viewerQueueLimit);
            viewers.add(viewer);
            return Optional.of(viewer);
        }
    }

    public void unsubscribe(Viewer viewer) {
        viewer.active = false;
        viewers.remove(viewer);
    }

    public Map<String, Object> status() {
        synchronized (lock) {
            Map<String, Object> status = new LinkedHashMap<>();
            Session session = current;
            boolean live = session != null && session.init != null && session.lastFragmentAt != null;
            status.put("live", live);
            status.put("viewers", viewers.size());
            if (session == null) {
                status.put("state", "offline");
                status.put("message", "No camera is streaming right now.");
            } else {
                status.put("state", live ? "live" : "starting");
                status.put("sessionId", session.id);
                status.put("producer", session.producer);
                status.put("remoteAddress", session.remoteAddress);
                status.put("startedAt", session.startedAt.toString());
                status.put("fragmentsReceived", session.fragments.get());
                status.put("bytesReceived", session.bytes.get());
                status.put("bufferedFragments", currentGop.size());
                double seconds = Math.max(0.001, Duration.between(session.startedAt, Instant.now()).toMillis() / 1000.0);
                status.put("averageKbps", Math.round(session.bytes.get() * 8 / seconds / 1000.0));
                TrackInfo track = session.track;
                if (track != null) {
                    status.put("codecs", track.codecs());
                    status.put("mimeType", track.mimeType());
                    status.put("width", track.width());
                    status.put("height", track.height());
                    status.put("timescale", track.timescale());
                }
                FragmentInfo last = session.lastFragment;
                if (last != null && track != null) {
                    status.put("lastFragmentAt", session.lastFragmentAt.toString());
                    status.put("lastFragmentAgeMs", Duration.between(session.lastFragmentAt, Instant.now()).toMillis());
                    if (last.duration() > 0) {
                        double fragmentSeconds = last.duration() / (double) track.timescale();
                        status.put("fps", Math.round(last.sampleCount() / fragmentSeconds * 100.0) / 100.0);
                        status.put("fragmentDurationMs", Math.round(fragmentSeconds * 1000.0));
                    }
                    status.put("streamPositionSeconds", Math.round(last.decodeTime() / (double) track.timescale() * 100.0) / 100.0);
                }
            }
            if (lastSessionEndedAt != null) {
                status.put("lastSessionEndedAt", lastSessionEndedAt.toString());
                status.put("lastSessionEndReason", lastSessionEndReason);
            }
            return status;
        }
    }

    private void endViewers() {
        for (Viewer viewer : viewers) {
            dropViewer(viewer);
        }
    }

    private void dropViewer(Viewer viewer) {
        viewer.active = false;
        viewers.remove(viewer);
        viewer.queue.clear();
        viewer.queue.offer(END_OF_STREAM);
    }
}
