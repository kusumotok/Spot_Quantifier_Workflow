package io.github.kusumotok.spotworkflow.core.model;

import ij.ImageStack;
import ij.process.ImageProcessor;

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

    /**
     * Extract a 2D MarkerResult for a specific Z-plane (1-based) for preview rendering.
     * Remaps label IDs to consecutive 1..N to avoid memory issues with sparse labels.
     */
    public MarkerResult getSlice(int zPlane) {
        int w = seedLabels.getWidth();
        int h = seedLabels.getHeight();
        int size = w * h;

        ImageProcessor labelIp = seedLabels.getProcessor(zPlane);
        ImageProcessor domainIp = domainMask.getProcessor(zPlane);

        // First pass: collect unique labels and create mapping
        java.util.Set<Integer> uniqueLabels = new java.util.HashSet<>();
        for (int i = 0; i < size; i++) {
            int label = labelIp.get(i);
            if (label > 0) {
                uniqueLabels.add(label);
            }
        }

        // Create label remapping (sparse labels -> consecutive 1..N)
        java.util.Map<Integer, Integer> labelMap = new java.util.HashMap<>();
        int newLabel = 1;
        for (int oldLabel : uniqueLabels) {
            labelMap.put(oldLabel, newLabel++);
        }

        // Second pass: apply remapping
        int[] fgLabels = new int[size];
        boolean[] fgMask = new boolean[size];
        boolean[] bgMask = new boolean[size];
        boolean[] unknownMask = new boolean[size];
        boolean[] seedMask = new boolean[size];
        boolean[] domain = new boolean[size];

        for (int i = 0; i < size; i++) {
            int oldLabel = labelIp.get(i);
            boolean inDomain = domainIp.get(i) != 0;
            
            // Remap label to consecutive range
            int remappedLabel = (oldLabel > 0) ? labelMap.get(oldLabel) : 0;
            
            fgLabels[i] = remappedLabel;
            seedMask[i] = remappedLabel > 0;
            fgMask[i] = remappedLabel > 0;
            domain[i] = inDomain;
            bgMask[i] = !inDomain;
        }

        // Return actual seed count in this slice
        int actualSeedCount = uniqueLabels.size();

        return new MarkerResult(w, h, fgLabels, fgMask, bgMask, unknownMask,
            unknownMask, bgMask, seedMask, domain, actualSeedCount);
    }
}
