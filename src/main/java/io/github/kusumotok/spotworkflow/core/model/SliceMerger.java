package io.github.kusumotok.spotworkflow.core.model;

import ij.ImagePlus;
import ij.process.ImageProcessor;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Merges 2D segmentation results from individual slices into a coherent 3D segmentation.
 * 
 * <p>This class implements a threshold-free overlap-based matching algorithm that:</p>
 * <ul>
 *   <li>Performs bidirectional matching to ensure symmetry</li>
 *   <li>Uses maximum overlap area to select the best match when multiple candidates exist</li>
 *   <li>Handles U-shaped structures correctly</li>
 * </ul>
 *
 * <p>The algorithm processes adjacent slice pairs, calculates overlaps between regions,
 * and uses Union-Find to group connected components across slices. The result is a
 * 3D label image where each connected 3D region has a unique label.</p>
 *
 * <p><strong>Algorithm Overview:</strong></p>
 * <ol>
 *   <li>For each adjacent slice pair (z, z+1), perform bidirectional matching</li>
 *   <li>Calculate overlap areas between all region pairs</li>
 *   <li>For each region, select the candidate with maximum overlap area</li>
 *   <li>Use Union-Find to group connected components</li>
 *   <li>Reassign labels to create final 3D label image</li>
 * </ol>
 * 
 * @see UnionFind
 * @see LabelKey
 * @see SliceSegmentationResult
 * @see MergeStatistics
 */
public class SliceMerger {
    
    /**
     * Merges a list of 2D slice label images into a single 3D label image.
     * 
     * <p>This is the main entry point for the slice merging process. It takes
     * the results from individual slice segmentations and combines them into
     * a coherent 3D segmentation using the improved overlap-based matching algorithm.</p>
     * 
     * @param sliceLabels list of 2D label images, one per slice (must not be null or empty)
     * @return a 3D label image where connected regions across slices have the same label
     * @throws IllegalArgumentException if sliceLabels is null, empty, or contains null images
     */
    public ImagePlus mergeSlices(List<ImagePlus> sliceLabels) {
        // 1. Validate input
        if (sliceLabels == null || sliceLabels.isEmpty()) {
            throw new IllegalArgumentException("sliceLabels cannot be null or empty");
        }
        
        // Check if all slices are null (no regions detected)
        boolean hasAnyRegions = false;
        for (ImagePlus slice : sliceLabels) {
            if (slice != null && hasNonZeroPixels(slice)) {
                hasAnyRegions = true;
                break;
            }
        }
        
        if (!hasAnyRegions) {
            // Return empty 3D label image
            return createEmpty3DLabelImage(sliceLabels);
        }
        
        // 2. Build connected components using Union-Find
        UnionFind uf = buildConnectedComponents(sliceLabels);
        
        // 3. Create 3D label image with reassigned labels
        return create3DLabelImage(sliceLabels, uf);
    }

    
    /**
     * Checks if an image has any non-zero pixels.
     * 
     * @param image the image to check
     * @return true if the image has non-zero pixels
     */
    private boolean hasNonZeroPixels(ImagePlus image) {
        if (image == null) return false;
        
        ImageProcessor proc = image.getProcessor();
        int width = proc.getWidth();
        int height = proc.getHeight();
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (proc.getPixel(x, y) > 0) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * Creates an empty 3D label image with all pixels set to 0.
     * 
     * @param sliceLabels the original slice labels to get dimensions
     * @return empty 3D label image
     */
    private ImagePlus createEmpty3DLabelImage(List<ImagePlus> sliceLabels) {
        // Find first non-null slice to get dimensions
        ImagePlus firstSlice = null;
        for (ImagePlus slice : sliceLabels) {
            if (slice != null) {
                firstSlice = slice;
                break;
            }
        }
        
        if (firstSlice == null) {
            throw new IllegalArgumentException("All slices are null");
        }
        
        int width = firstSlice.getWidth();
        int height = firstSlice.getHeight();
        int depth = sliceLabels.size();
        
        // Create empty stack
        ij.ImageStack stack = new ij.ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            ij.process.ShortProcessor emptySlice = new ij.process.ShortProcessor(width, height);
            stack.addSlice(emptySlice);
        }
        
        return new ImagePlus("Merged 3D Labels", stack);
    }
    
    /**
     * Creates the final 3D label image with reassigned labels based on connected components.
     * 
     * @param sliceLabels the original slice labels
     * @param uf the Union-Find structure with connected components
     * @return 3D label image with reassigned labels
     */
    private ImagePlus create3DLabelImage(List<ImagePlus> sliceLabels, UnionFind uf) {
        // Get connected component groups
        Map<Integer, List<Integer>> groups = uf.getGroups();
        
        // Create mapping from Union-Find ID to new label
        Map<Integer, Integer> idToNewLabel = new HashMap<>();
        int newLabel = 1;
        for (Integer representative : groups.keySet()) {
            idToNewLabel.put(representative, newLabel++);
        }
        
        // Create reverse mapping from LabelKey to Union-Find ID
        Map<LabelKey, Integer> labelKeyToId = new HashMap<>();
        int nextId = 0;
        
        for (int sliceIndex = 0; sliceIndex < sliceLabels.size(); sliceIndex++) {
            ImagePlus slice = sliceLabels.get(sliceIndex);
            if (slice == null) continue;
            
            Set<Integer> uniqueLabels = getUniqueLabels(slice);
            for (Integer label : uniqueLabels) {
                LabelKey key = new LabelKey(sliceIndex, label);
                labelKeyToId.put(key, nextId++);
            }
        }
        
        // Get dimensions from first non-null slice
        ImagePlus firstSlice = null;
        for (ImagePlus slice : sliceLabels) {
            if (slice != null) {
                firstSlice = slice;
                break;
            }
        }
        
        if (firstSlice == null) {
            throw new IllegalArgumentException("All slices are null");
        }
        
        int width = firstSlice.getWidth();
        int height = firstSlice.getHeight();
        int depth = sliceLabels.size();
        
        ij.ImageStack stack = new ij.ImageStack(width, height);
        
        for (int z = 0; z < depth; z++) {
            ImagePlus slice = sliceLabels.get(z);
            ij.process.ShortProcessor newSlice = new ij.process.ShortProcessor(width, height);
            
            if (slice != null) {
                ImageProcessor proc = slice.getProcessor();
                
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int oldLabel = proc.getPixel(x, y);
                        if (oldLabel > 0) {
                            LabelKey key = new LabelKey(z, oldLabel);
                            Integer ufId = labelKeyToId.get(key);
                            if (ufId != null) {
                                int representative = uf.find(ufId);
                                Integer finalLabel = idToNewLabel.get(representative);
                                if (finalLabel != null) {
                                    newSlice.putPixel(x, y, finalLabel);
                                }
                            }
                        }
                        // Background pixels remain 0
                    }
                }
            }
            
            stack.addSlice(newSlice);
        }
        
        return new ImagePlus("Merged 3D Labels", stack);
    }
    
    /**
     * Builds connected components across slices using Union-Find data structure.
     *
     * <p>This method processes all adjacent slice pairs and identifies which regions
     * should be merged based on maximum overlap area. It returns a Union-Find
     * structure that groups all connected regions.</p>
     *
     * @param sliceLabels list of 2D label images to analyze
     * @return Union-Find structure containing connected components
     * @throws IllegalArgumentException if sliceLabels is invalid
     */
    private UnionFind buildConnectedComponents(List<ImagePlus> sliceLabels) {
        if (sliceLabels == null || sliceLabels.isEmpty()) {
            throw new IllegalArgumentException("sliceLabels cannot be null or empty");
        }

        // First pass: collect all unique (slice, label) pairs and assign them unique IDs
        Map<LabelKey, Integer> labelKeyToId = new HashMap<>();
        int nextId = 0;

        for (int sliceIndex = 0; sliceIndex < sliceLabels.size(); sliceIndex++) {
            ImagePlus slice = sliceLabels.get(sliceIndex);
            if (slice == null) {
                throw new IllegalArgumentException("Slice at index " + sliceIndex + " is null");
            }

            ImageProcessor proc = slice.getProcessor();
            int width = proc.getWidth();
            int height = proc.getHeight();

            // Collect unique labels in this slice (excluding background 0)
            Set<Integer> uniqueLabels = new HashSet<>();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int label = proc.getPixel(x, y);
                    if (label > 0) {
                        uniqueLabels.add(label);
                    }
                }
            }

            // Assign unique IDs to each (slice, label) pair
            for (Integer label : uniqueLabels) {
                LabelKey key = new LabelKey(sliceIndex, label);
                labelKeyToId.put(key, nextId++);
            }
        }

        // Create Union-Find with the total number of unique (slice, label) pairs
        UnionFind uf = new UnionFind(nextId);

        // Second pass: process adjacent slice pairs for bidirectional matching
        for (int z = 0; z < sliceLabels.size() - 1; z++) {
            ImagePlus slice1 = sliceLabels.get(z);
            ImagePlus slice2 = sliceLabels.get(z + 1);

            performBidirectionalMatching(slice1, slice2, z, z + 1, uf, labelKeyToId);
        }

        return uf;
    }


    
    /**
     * Performs bidirectional matching between two adjacent slices.
     * 
     * <p>This method ensures symmetry by processing matches in both directions:
     * slice1 → slice2 and slice2 → slice1. This prevents order-dependent results
     * and handles U-shaped structures correctly.</p>
     * 
     * @param slice1 the first slice label image
     * @param slice2 the second slice label image
     * @param z1 the slice index of the first slice
     * @param z2 the slice index of the second slice
     * @param uf the Union-Find structure to update with matches
     * @param labelKeyToId mapping from LabelKey to Union-Find ID
     */
    private void performBidirectionalMatching(
            ImagePlus slice1, ImagePlus slice2, int z1, int z2, UnionFind uf, Map<LabelKey, Integer> labelKeyToId) {
        
        // Calculate all overlaps between slice1 and slice2
        Map<Integer, Map<Integer, Integer>> overlapMatrix = calculateAllOverlaps(slice1, slice2);
        
        // Process matching from slice1 to slice2
        processMatching(slice1, slice2, z1, z2, overlapMatrix, uf, labelKeyToId);
        
        // Process matching from slice2 to slice1 (transpose for symmetry)
        Map<Integer, Map<Integer, Integer>> transposedMatrix = transposeOverlapMatrix(overlapMatrix);
        processMatching(slice2, slice1, z2, z1, transposedMatrix, uf, labelKeyToId);
    }
    
    /**
     * Processes matching from source slice to target slice.
     *
     * <p>For each source region, finds the target region with the maximum overlap area
     * and merges them. No threshold is applied—any overlap (>= 1 pixel) is sufficient.</p>
     *
     * @param sourceSlice the source slice
     * @param targetSlice the target slice
     * @param sourceZ the source slice index
     * @param targetZ the target slice index
     * @param overlapMatrix the overlap matrix
     * @param uf the Union-Find structure
     * @param labelKeyToId mapping from LabelKey to Union-Find ID
     */
    private void processMatching(ImagePlus sourceSlice, ImagePlus targetSlice, int sourceZ, int targetZ,
                                Map<Integer, Map<Integer, Integer>> overlapMatrix, UnionFind uf,
                                Map<LabelKey, Integer> labelKeyToId) {

        Set<Integer> sourceLabels = getUniqueLabels(sourceSlice);

        for (Integer sourceLabel : sourceLabels) {
            Map<Integer, Integer> sourceOverlaps = overlapMatrix.get(sourceLabel);
            if (sourceOverlaps == null || sourceOverlaps.isEmpty()) {
                continue;
            }

            // Find the target label with maximum overlap area
            Integer bestMatch = null;
            int maxOverlap = 0;
            for (Map.Entry<Integer, Integer> entry : sourceOverlaps.entrySet()) {
                if (entry.getValue() > maxOverlap) {
                    maxOverlap = entry.getValue();
                    bestMatch = entry.getKey();
                }
            }

            if (bestMatch != null) {
                unionLabels(sourceZ, sourceLabel, targetZ, bestMatch, uf, labelKeyToId);
            }
        }
    }
    
    /**
     * Transposes the overlap matrix for bidirectional processing.
     * 
     * @param overlapMatrix the original overlap matrix
     * @return the transposed overlap matrix
     */
    private Map<Integer, Map<Integer, Integer>> transposeOverlapMatrix(
            Map<Integer, Map<Integer, Integer>> overlapMatrix) {
        Map<Integer, Map<Integer, Integer>> transposed = new HashMap<>();
        
        for (Map.Entry<Integer, Map<Integer, Integer>> entry : overlapMatrix.entrySet()) {
            Integer sourceLabel = entry.getKey();
            Map<Integer, Integer> targetOverlaps = entry.getValue();
            
            for (Map.Entry<Integer, Integer> targetEntry : targetOverlaps.entrySet()) {
                Integer targetLabel = targetEntry.getKey();
                Integer overlapArea = targetEntry.getValue();
                
                transposed.computeIfAbsent(targetLabel, k -> new HashMap<>()).put(sourceLabel, overlapArea);
            }
        }
        
        return transposed;
    }
    
    /**
     * Gets all unique labels in a slice (excluding background 0).
     * 
     * @param slice the slice to analyze
     * @return set of unique labels
     */
    private Set<Integer> getUniqueLabels(ImagePlus slice) {
        Set<Integer> uniqueLabels = new TreeSet<>();  // Use TreeSet for consistent ordering
        ImageProcessor proc = slice.getProcessor();
        int width = proc.getWidth();
        int height = proc.getHeight();
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int label = proc.getPixel(x, y);
                if (label > 0) {
                    uniqueLabels.add(label);
                }
            }
        }
        
        return uniqueLabels;
    }
    
    /**
     * Unions two labels from different slices in the Union-Find structure.
     * 
     * @param z1 first slice index
     * @param label1 first label
     * @param z2 second slice index
     * @param label2 second label
     * @param uf the Union-Find structure
     * @param labelKeyToId mapping from LabelKey to Union-Find ID
     */
    private void unionLabels(int z1, int label1, int z2, int label2, UnionFind uf, 
                            Map<LabelKey, Integer> labelKeyToId) {
        LabelKey key1 = new LabelKey(z1, label1);
        LabelKey key2 = new LabelKey(z2, label2);
        
        Integer id1 = labelKeyToId.get(key1);
        Integer id2 = labelKeyToId.get(key2);
        
        if (id1 != null && id2 != null) {
            uf.union(id1, id2);
        }
    }
    
    /**
     * Calculates overlap areas between all region pairs in two slices.
     * 
     * <p>Returns a nested map where the outer key is a label from slice1,
     * the inner key is a label from slice2, and the value is the overlap area
     * in pixels. Only non-zero overlaps are included in the result.</p>
     * 
     * @param slice1 the first slice label image
     * @param slice2 the second slice label image
     * @return nested map of overlap areas: slice1Label → slice2Label → overlapArea
     */
    private Map<Integer, Map<Integer, Integer>> calculateAllOverlaps(
            ImagePlus slice1, ImagePlus slice2) {
        Map<Integer, Map<Integer, Integer>> overlapMatrix = new HashMap<>();
        
        ImageProcessor proc1 = slice1.getProcessor();
        ImageProcessor proc2 = slice2.getProcessor();
        
        int width = proc1.getWidth();
        int height = proc1.getHeight();
        
        // Early return if dimensions don't match
        if (width != proc2.getWidth() || height != proc2.getHeight()) {
            return overlapMatrix;
        }
        
        // Scan all pixels to find overlapping regions
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int label1 = proc1.getPixel(x, y);
                int label2 = proc2.getPixel(x, y);
                
                // Skip background pixels (label 0)
                if (label1 > 0 && label2 > 0) {
                    // Initialize nested maps if needed
                    overlapMatrix.computeIfAbsent(label1, k -> new HashMap<>());
                    
                    // Increment overlap count
                    overlapMatrix.get(label1).merge(label2, 1, Integer::sum);
                }
            }
        }
        
        return overlapMatrix;
    }
    

}