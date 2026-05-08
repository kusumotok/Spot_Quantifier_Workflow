package io.github.kusumotok.spotworkflow.core.model;

import ij.ImagePlus;

/**
 * Immutable data class representing the segmentation result for a single slice.
 * Contains the slice index, the resulting label image, and the number of regions detected.
 * 
 * <p>This class is used by the SliceSegmenter to store the results of 2D segmentation
 * on each slice before they are merged into a 3D segmentation result.</p>
 * 
 * @see io.github.kusumotok.spotworkflow.core.alg.SliceSegmenter
 */
public final class SliceSegmentationResult {
    /**
     * The slice index (0-based).
     */
    public final int sliceIndex;
    
    /**
     * The label image resulting from 2D segmentation.
     * Each pixel value represents a region label (0 for background, 1+ for regions).
     */
    public final ImagePlus labelImage;
    
    /**
     * The number of regions detected in this slice (excluding background).
     */
    public final int regionCount;
    
    /**
     * Creates a new SliceSegmentationResult with the specified parameters.
     * 
     * @param sliceIndex the slice index (0-based)
     * @param labelImage the label image from 2D segmentation (must not be null)
     * @param regionCount the number of regions detected (must be non-negative)
     * @throws IllegalArgumentException if labelImage is null or regionCount is negative
     */
    public SliceSegmentationResult(int sliceIndex, ImagePlus labelImage, int regionCount) {
        if (labelImage == null) {
            throw new IllegalArgumentException("labelImage must not be null");
        }
        if (regionCount < 0) {
            throw new IllegalArgumentException("regionCount must be non-negative");
        }
        this.sliceIndex = sliceIndex;
        this.labelImage = labelImage;
        this.regionCount = regionCount;
    }
    
    /**
     * Returns a string representation of this SliceSegmentationResult.
     * 
     * @return a string in the format "SliceSegmentationResult(slice=X, regions=Y)"
     */
    @Override
    public String toString() {
        return "SliceSegmentationResult(slice=" + sliceIndex + ", regions=" + regionCount + ")";
    }
}
