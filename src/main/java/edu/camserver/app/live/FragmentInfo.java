package edu.camserver.app.live;

/**
 * Facts about one {@code moof}+{@code mdat} movie fragment.
 *
 * @param keyframe    whether the fragment starts with a sync sample, i.e. a viewer may begin
 *                    decoding here
 * @param decodeTime  {@code tfdt} base media decode time of the first sample, in timescale ticks
 * @param duration    total duration of the fragment's samples, in timescale ticks
 * @param sampleCount number of samples (frames) in the fragment
 */
public record FragmentInfo(boolean keyframe, long decodeTime, long duration, int sampleCount) {
}
