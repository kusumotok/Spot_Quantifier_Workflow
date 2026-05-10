package io.github.kusumotok.spotworkflow.core.model;

import ij.ImageStack;

public class MarkerResult3D {
    private final ImageStack seedLabels;
    private final ImageStack domainMask;
    private final int seedCount;

    public MarkerResult3D(ImageStack seedLabels, ImageStack domainMask, int seedCount) {
        this.seedLabels = seedLabels;
        this.domainMask = domainMask;
        this.seedCount = seedCount;
    }

    public ImageStack getSeedLabels() { return seedLabels; }
    public ImageStack getDomainMask() { return domainMask; }
    public int getSeedCount() { return seedCount; }
    public int getDepth() { return seedLabels.getSize(); }
}
