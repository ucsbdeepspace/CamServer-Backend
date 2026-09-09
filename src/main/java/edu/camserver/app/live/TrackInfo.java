package edu.camserver.app.live;

/**
 * What a viewer needs to know about the video track carried by a fragmented MP4 stream, taken
 * from its {@code moov} box.
 *
 * @param codecs                RFC 6381 codec string for the MSE {@code addSourceBuffer} call,
 *                              e.g. {@code avc1.64001F}
 * @param width                 coded width in pixels (0 when unknown)
 * @param height                coded height in pixels (0 when unknown)
 * @param timescale             media timescale of the video track (ticks per second)
 * @param defaultSampleDuration {@code trex} default duration in timescale ticks (0 when absent)
 * @param defaultSampleFlags    {@code trex} default sample flags, or -1 when absent
 */
public record TrackInfo(String codecs, int width, int height, long timescale,
                        long defaultSampleDuration, int defaultSampleFlags) {

    public String mimeType() {
        return "video/mp4; codecs=\"" + codecs + "\"";
    }
}
