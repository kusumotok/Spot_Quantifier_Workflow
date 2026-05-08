package io.github.kusumotok.spotworkflow.core.alg;

import ij.ImagePlus;

public class SegmentationResult3D {
    public final ImagePlus labelImage;

    public SegmentationResult3D(ImagePlus labelImage) {
        this.labelImage = labelImage;
    }
}
