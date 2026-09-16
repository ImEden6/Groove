package groove.engine.samples;

/** Source-frame interval [startFrame,endFrame); endFrame=0 means the asset end. */
public record SampleRegion(int startFrame, int endFrame, int slices, int index, boolean reverse) {
    public static final SampleRegion ALL = new SampleRegion(0, 0, 1, 0, false);
    public SampleRegion {
        if (startFrame < 0 || startFrame >= SampleData.MAX_FLOATS || endFrame < 0 || endFrame > SampleData.MAX_FLOATS
                || (endFrame != 0 && endFrame <= startFrame) || slices < 1 || slices > 64 || index < 0 || index >= slices)
            throw new IllegalArgumentException("Invalid sample region/slice");
    }
    /** Control-thread validation against the actual decoded asset. */
    public int start(SampleData pcm) {
        int end = endFrame == 0 ? pcm.frames() : endFrame;
        if (end > pcm.frames() || startFrame >= end || end - startFrame < slices)
            throw new IllegalArgumentException("Sample region exceeds asset or has empty slices");
        return startFrame + (int)((long)(end - startFrame) * index / slices);
    }
    public int end(SampleData pcm) {
        start(pcm);
        int end = endFrame == 0 ? pcm.frames() : endFrame;
        return startFrame + (int)((long)(end - startFrame) * (index + 1) / slices);
    }
}
