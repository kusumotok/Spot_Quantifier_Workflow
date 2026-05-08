package io.github.kusumotok.spotworkflow.core.alg;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Intermediate result of 3D connected-components labeling before size filtering.
 * Holds the full label image and per-label voxel counts so that:
 *  - The CC computation is cached separately from the size filter.
 *  - The preview can recolor labels (valid / too small / too large) without re-running CC.
 */
public class CcResult3D {

    /** Status codes for each label (used by preview color coding). */
    public static final int STATUS_VALID     = 0;
    public static final int STATUS_TOO_SMALL = 1;
    public static final int STATUS_TOO_LARGE = 2;

    /** Full label image (all CCs, NOT yet size-filtered). */
    public final ImagePlus labelImage;

    /** label id → voxel count. */
    public final Map<Integer, Long> voxelCounts;

    public CcResult3D(ImagePlus labelImage, Map<Integer, Long> voxelCounts) {
        this.labelImage  = labelImage;
        this.voxelCounts = voxelCounts;
    }

    /**
     * Classify each label as VALID / TOO_SMALL / TOO_LARGE given the current size params.
     * @param params   quantifier params (min/maxVolUm3 may be null = no limit)
     * @param voxelVol µm³ per voxel
     * @return map label → status constant
     */
    public Map<Integer, Integer> classifyLabels(QuantifierParams params, double voxelVol) {
        Map<Integer, Integer> status = new HashMap<>();
        for (Map.Entry<Integer, Long> e : voxelCounts.entrySet()) {
            int    label  = e.getKey();
            double volUm3 = e.getValue() * voxelVol;
            if (params.minVolUm3 != null && volUm3 < params.minVolUm3) {
                status.put(label, STATUS_TOO_SMALL);
            } else if (params.maxVolUm3 != null && volUm3 > params.maxVolUm3) {
                status.put(label, STATUS_TOO_LARGE);
            } else {
                status.put(label, STATUS_VALID);
            }
        }
        return status;
    }

    /**
     * Build a filtered SegmentationResult3D containing only the valid labels.
     * The label image is deep-copied; filtered-out labels are set to 0.
     */
    public SegmentationResult3D buildFilteredResult(Map<Integer, Integer> statusMap) {
        Set<Integer> validLabels = new HashSet<>();
        for (Map.Entry<Integer, Integer> e : statusMap.entrySet()) {
            if (e.getValue() == STATUS_VALID) validLabels.add(e.getKey());
        }

        ImageStack src = labelImage.getStack();
        int w = src.getWidth();
        int h = src.getHeight();
        int d = src.getSize();
        ImageStack dst = new ImageStack(w, h);
        for (int z = 1; z <= d; z++) {
            ImageProcessor srcIp = src.getProcessor(z);
            ImageProcessor dstIp = srcIp.duplicate();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int label = (int) Math.round(srcIp.getPixelValue(x, y));
                    if (label > 0 && !validLabels.contains(label)) {
                        dstIp.putPixelValue(x, y, 0);
                    }
                }
            }
            dst.addSlice(dstIp);
        }
        ImagePlus filtered = new ImagePlus(labelImage.getShortTitle() + "-filtered", dst);
        return new SegmentationResult3D(filtered);
    }

    /**
     * Min volume among all detected spots (µm³).
     */
    public double minVolUm3(double voxelVol) {
        long min = Long.MAX_VALUE;
        for (long v : voxelCounts.values()) if (v < min) min = v;
        return min == Long.MAX_VALUE ? 0 : min * voxelVol;
    }

    /**
     * Max volume among all detected spots (µm³).
     */
    public double maxVolUm3(double voxelVol) {
        long max = 0;
        for (long v : voxelCounts.values()) if (v > max) max = v;
        return max * voxelVol;
    }
}
