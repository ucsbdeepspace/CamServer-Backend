package edu.camserver.app.live;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Minimal ISO BMFF box walker for the two things the live relay needs: the codec/size of the video
 * track in a {@code moov} box and the keyframe/timing facts of each {@code moof}.
 */
public final class Mp4Parser {

    private static final Set<String> VIDEO_SAMPLE_ENTRIES = Set.of("avc1", "avc3", "hvc1", "hev1", "av01", "vp09");

    /** A child box located inside a parent buffer. */
    record Range(String type, int offset, int size, int headerLength) {
        int payload() {
            return offset + headerLength;
        }

        int end() {
            return offset + size;
        }
    }

    private Mp4Parser() {
    }

    /**
     * Extracts the first video track from a {@code moov} box.
     *
     * @throws IllegalArgumentException when the box has no recognisable video track
     */
    public static TrackInfo parseTrack(byte[] moov) {
        long trexDuration = 0;
        int trexFlags = -1;
        for (Range mvex : children(moov, 8, moov.length)) {
            if (!mvex.type().equals("mvex")) {
                continue;
            }
            for (Range trex : children(moov, mvex.payload(), mvex.end())) {
                if (trex.type().equals("trex") && trex.size() >= 32) {
                    trexDuration = u32(moov, trex.payload() + 12);
                    trexFlags = (int) u32(moov, trex.payload() + 20);
                    break;
                }
            }
        }

        for (Range trak : children(moov, 8, moov.length)) {
            if (!trak.type().equals("trak")) {
                continue;
            }
            Range mdia = child(moov, trak, "mdia");
            if (mdia == null) {
                continue;
            }
            long timescale = 0;
            Range mdhd = child(moov, mdia, "mdhd");
            if (mdhd != null) {
                int version = moov[mdhd.payload()] & 0xff;
                timescale = u32(moov, mdhd.payload() + (version == 1 ? 20 : 12));
            }
            Range minf = child(moov, mdia, "minf");
            Range stbl = minf == null ? null : child(moov, minf, "stbl");
            Range stsd = stbl == null ? null : child(moov, stbl, "stsd");
            if (stsd == null) {
                continue;
            }
            for (Range entry : children(moov, stsd.payload() + 8, stsd.end())) {
                if (!VIDEO_SAMPLE_ENTRIES.contains(entry.type())) {
                    continue;
                }
                int width = entry.size() >= 36 ? u16(moov, entry.offset() + 32) : 0;
                int height = entry.size() >= 36 ? u16(moov, entry.offset() + 34) : 0;
                String codecs = codecString(moov, entry);
                if (timescale <= 0) {
                    timescale = 1;
                }
                return new TrackInfo(codecs, width, height, timescale, trexDuration, trexFlags);
            }
        }
        throw new IllegalArgumentException("moov box carries no supported video track (avc1/hvc1/av01/vp09)");
    }

    /**
     * Reads the sync-sample flag and timing of a fragment from its {@code moof} box.
     */
    public static FragmentInfo parseFragment(byte[] moof, TrackInfo track) {
        boolean keyframe = true;
        boolean keyframeDecided = false;
        long decodeTime = 0;
        long duration = 0;
        int sampleCount = 0;

        for (Range traf : children(moof, 8, moof.length)) {
            if (!traf.type().equals("traf")) {
                continue;
            }
            long defaultDuration = track.defaultSampleDuration();
            int defaultFlags = track.defaultSampleFlags();

            Range tfhd = child(moof, traf, "tfhd");
            if (tfhd != null) {
                int flags = u24(moof, tfhd.payload() + 1);
                int pos = tfhd.payload() + 8;
                if ((flags & 0x1) != 0) {
                    pos += 8;
                }
                if ((flags & 0x2) != 0) {
                    pos += 4;
                }
                if ((flags & 0x8) != 0) {
                    defaultDuration = u32(moof, pos);
                    pos += 4;
                }
                if ((flags & 0x10) != 0) {
                    pos += 4;
                }
                if ((flags & 0x20) != 0) {
                    defaultFlags = (int) u32(moof, pos);
                }
            }

            Range tfdt = child(moof, traf, "tfdt");
            if (tfdt != null) {
                int version = moof[tfdt.payload()] & 0xff;
                decodeTime = version == 1 ? u64(moof, tfdt.payload() + 4) : u32(moof, tfdt.payload() + 4);
            }

            for (Range trun : children(moof, traf.payload(), traf.end())) {
                if (!trun.type().equals("trun")) {
                    continue;
                }
                int flags = u24(moof, trun.payload() + 1);
                int count = (int) u32(moof, trun.payload() + 4);
                int pos = trun.payload() + 8;
                if ((flags & 0x1) != 0) {
                    pos += 4;
                }
                int firstSampleFlags = -1;
                if ((flags & 0x4) != 0) {
                    firstSampleFlags = (int) u32(moof, pos);
                    pos += 4;
                }
                // With tfhd defaults in place a trun may carry no per-sample fields at all, so
                // the sample loop must run on the count alone and only bounds-check real fields.
                int perSampleBytes = ((flags & 0x100) != 0 ? 4 : 0) + ((flags & 0x200) != 0 ? 4 : 0)
                        + ((flags & 0x400) != 0 ? 4 : 0) + ((flags & 0x800) != 0 ? 4 : 0);
                for (int i = 0; i < count; i++) {
                    if (perSampleBytes > 0 && pos + perSampleBytes > trun.end()) {
                        break;
                    }
                    long sampleDuration = defaultDuration;
                    if ((flags & 0x100) != 0) {
                        sampleDuration = u32(moof, pos);
                        pos += 4;
                    }
                    if ((flags & 0x200) != 0) {
                        pos += 4;
                    }
                    int sampleFlags = -1;
                    if ((flags & 0x400) != 0) {
                        sampleFlags = (int) u32(moof, pos);
                        pos += 4;
                    }
                    if ((flags & 0x800) != 0) {
                        pos += 4;
                    }
                    if (!keyframeDecided) {
                        int effective = firstSampleFlags != -1 ? firstSampleFlags
                                : sampleFlags != -1 ? sampleFlags
                                : defaultFlags;
                        // sample_is_non_sync_sample is bit 16 of the sample flags.
                        keyframe = effective == -1 || ((effective >>> 16) & 0x1) == 0;
                        keyframeDecided = true;
                    }
                    duration += sampleDuration;
                    sampleCount++;
                }
            }
        }
        return new FragmentInfo(keyframe, decodeTime, duration, sampleCount);
    }

    private static String codecString(byte[] buf, Range entry) {
        int childrenStart = entry.offset() + 86;
        if (childrenStart >= entry.end()) {
            return entry.type();
        }
        for (Range config : children(buf, childrenStart, entry.end())) {
            if (config.type().equals("avcC") && config.size() >= 12) {
                int p = config.payload();
                return String.format("%s.%02X%02X%02X", entry.type(),
                        buf[p + 1] & 0xff, buf[p + 2] & 0xff, buf[p + 3] & 0xff);
            }
            if (config.type().equals("hvcC") && config.size() >= 31) {
                return hevcCodecString(buf, config, entry.type());
            }
        }
        return entry.type();
    }

    private static String hevcCodecString(byte[] buf, Range hvcC, String entryType) {
        int p = hvcC.payload();
        int profileByte = buf[p + 1] & 0xff;
        int profileSpace = profileByte >>> 6;
        int tier = (profileByte >>> 5) & 0x1;
        int profileIdc = profileByte & 0x1f;
        long compatibility = u32(buf, p + 2);
        long reversed = 0;
        for (int i = 0; i < 32; i++) {
            reversed |= ((compatibility >>> i) & 1L) << (31 - i);
        }
        int levelIdc = buf[p + 12] & 0xff;
        StringBuilder sb = new StringBuilder(entryType).append('.');
        if (profileSpace > 0) {
            sb.append((char) ('A' + profileSpace - 1));
        }
        sb.append(profileIdc).append('.').append(Long.toHexString(reversed).toUpperCase())
                .append('.').append(tier == 0 ? 'L' : 'H').append(levelIdc);
        int lastNonZero = -1;
        for (int i = 0; i < 6; i++) {
            if (buf[p + 6 + i] != 0) {
                lastNonZero = i;
            }
        }
        for (int i = 0; i <= lastNonZero; i++) {
            sb.append('.').append(String.format("%02X", buf[p + 6 + i] & 0xff));
        }
        return sb.toString();
    }

    static List<Range> children(byte[] buf, int start, int end) {
        List<Range> out = new ArrayList<>();
        int pos = start;
        while (pos + 8 <= end) {
            long size = u32(buf, pos);
            String type = new String(buf, pos + 4, 4, StandardCharsets.ISO_8859_1);
            int headerLength = 8;
            if (size == 1) {
                if (pos + 16 > end) {
                    break;
                }
                size = u64(buf, pos + 8);
                headerLength = 16;
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < headerLength || pos + size > end) {
                break;
            }
            out.add(new Range(type, pos, (int) size, headerLength));
            pos += (int) size;
        }
        return out;
    }

    private static Range child(byte[] buf, Range parent, String type) {
        for (Range range : children(buf, parent.payload(), parent.end())) {
            if (range.type().equals(type)) {
                return range;
            }
        }
        return null;
    }

    static int u16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    static int u24(byte[] b, int off) {
        return ((b[off] & 0xff) << 16) | ((b[off + 1] & 0xff) << 8) | (b[off + 2] & 0xff);
    }

    static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    static long u64(byte[] b, int off) {
        return (u32(b, off) << 32) | u32(b, off + 4);
    }
}
