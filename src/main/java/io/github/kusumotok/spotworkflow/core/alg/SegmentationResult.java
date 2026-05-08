package io.github.kusumotok.spotworkflow.core.alg;

import ij.ImagePlus;

public class SegmentationResult {
    public final ImagePlus labelImage;
    public SegmentationResult(ImagePlus labelImage) { this.labelImage = labelImage; }
}
