package io.github.kusumotok.spotworkflow.core.model;

public class MarkerResult {
    public final int width;
    public final int height;
    public final int[] fgLabels;
    public final boolean[] fgMask;
    public final boolean[] bgMask;
    public final boolean[] unknownMask;
    public final boolean[] unknownAfterAbsorb;
    public final boolean[] bgSeedMask;
    public final boolean[] seedMask;
    public final boolean[] domainMask;
    public final int fgCount;

    public MarkerResult(int w, int h, int[] fgLabels, boolean[] fgMask, boolean[] bgMask, boolean[] unknownMask,
                        boolean[] unknownAfterAbsorb, boolean[] bgSeedMask, boolean[] seedMask,
                        boolean[] domainMask, int fgCount) {
        this.width = w;
        this.height = h;
        this.fgLabels = fgLabels;
        this.fgMask = fgMask;
        this.bgMask = bgMask;
        this.unknownMask = unknownMask;
        this.unknownAfterAbsorb = unknownAfterAbsorb;
        this.bgSeedMask = bgSeedMask;
        this.seedMask = seedMask;
        this.domainMask = domainMask;
        this.fgCount = fgCount;
    }

    public int[] buildSeedLabels() {
        int size = width * height;
        int[] seeds = new int[size];
        for (int i = 0; i < size; i++) {
            if (fgLabels[i] > 0) seeds[i] = fgLabels[i];
        }
        return seeds;
    }
}
