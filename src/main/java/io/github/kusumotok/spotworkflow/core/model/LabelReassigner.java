package io.github.kusumotok.spotworkflow.core.model;

import ij.ImagePlus;
import ij.process.ImageProcessor;
import java.util.*;

/**
 * Reassigns labels in a 3D label image to consecutive integers starting from 1.
 * 
 * <p>This class provides an alternative approach to label reassignment that can be used
 * independently of the SliceMerger's built-in reassignment. It ensures the final output
 * has clean, consecutive labeling (1, 2, 3, ...) with background pixels remaining 0.</p>
 * 
 * <p>The reassignment process:</p>
 * <ol>
 *   <li>Scans all pixels to collect unique label values (excluding 0)</li>
 *   <li>Sorts the labels and creates a mapping to consecutive integers starting from 1</li>
 *   <li>Applies the mapping to all pixels in the image</li>
 * </ol>
 * 
 * <p>This approach is useful when you have a 3D label image with non-consecutive
 * or sparse label values and need to normalize them to a clean consecutive sequence.</p>
 * 
 * @author Fiji Maxima Based Segmenter Team
 * @since 1.0
 */
public class LabelReassigner {
    
    /**
     * Reassigns all labels in the given 3D label image to consecutive integers starting from 1.
     * 
     * <p>Background pixels (value 0) are preserved as 0. All other labels are mapped to
     * consecutive integers 1, 2, 3, ... in ascending order of their original values.</p>
     * 
     * <p>Example:</p>
     * <pre>
     * Original labels: [0, 5, 0, 12, 5, 0, 7]
     * Reassigned:      [0, 1, 0, 3,  1, 0, 2]
     * </pre>
     * 
     * @param labelImage the 3D label image to reassign (must not be null)
     * @return a new ImagePlus with reassigned labels, or null if input is invalid
     * @throws IllegalArgumentException if labelImage is null
     */
    public ImagePlus reassignLabels(ImagePlus labelImage) {
        if (labelImage == null) {
            throw new IllegalArgumentException("Label image cannot be null");
        }
        
        // Build mapping from old labels to new consecutive labels
        Map<Integer, Integer> labelMapping = buildLabelMapping(labelImage);
        
        if (labelMapping.isEmpty()) {
            // No non-zero labels found, return a copy of the original
            return labelImage.duplicate();
        }
        
        // Create new image with same dimensions
        int width = labelImage.getWidth();
        int height = labelImage.getHeight();
        int depth = labelImage.getNSlices();
        
        ij.ImageStack newStack = new ij.ImageStack(width, height);
        
        // Process each slice
        for (int z = 1; z <= depth; z++) {
            labelImage.setSlice(z);
            ImageProcessor oldProc = labelImage.getProcessor();
            ij.process.ByteProcessor newProc = new ij.process.ByteProcessor(width, height);
            
            // Reassign labels for each pixel
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int oldLabel = oldProc.getPixel(x, y);
                    if (oldLabel == 0) {
                        // Background pixels remain 0
                        newProc.putPixel(x, y, 0);
                    } else {
                        // Map to new consecutive label
                        Integer newLabel = labelMapping.get(oldLabel);
                        if (newLabel != null) {
                            newProc.putPixel(x, y, newLabel);
                        } else {
                            // This shouldn't happen if buildLabelMapping is correct
                            newProc.putPixel(x, y, 0);
                        }
                    }
                }
            }
            
            newStack.addSlice(newProc);
        }
        
        return new ImagePlus("Reassigned Labels", newStack);
    }
    
    /**
     * Builds a mapping from existing labels to new consecutive labels starting from 1.
     * 
     * <p>This method scans all pixels in the 3D image to collect unique label values
     * (excluding 0), sorts them, and creates a mapping where the smallest non-zero
     * label maps to 1, the next smallest to 2, and so on.</p>
     * 
     * <p>The mapping preserves the relative ordering of original labels while ensuring
     * consecutive numbering.</p>
     * 
     * @param labelImage the 3D label image to analyze (must not be null)
     * @return mapping from old labels to new consecutive labels (empty if no non-zero labels)
     * @throws IllegalArgumentException if labelImage is null
     */
    private Map<Integer, Integer> buildLabelMapping(ImagePlus labelImage) {
        if (labelImage == null) {
            throw new IllegalArgumentException("Label image cannot be null");
        }
        
        // Collect all unique non-zero labels
        Set<Integer> uniqueLabels = new HashSet<>();
        
        int depth = labelImage.getNSlices();
        int width = labelImage.getWidth();
        int height = labelImage.getHeight();
        
        for (int z = 1; z <= depth; z++) {
            labelImage.setSlice(z);
            ImageProcessor proc = labelImage.getProcessor();
            
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int label = proc.getPixel(x, y);
                    if (label > 0) {
                        uniqueLabels.add(label);
                    }
                }
            }
        }
        
        // Sort labels and create consecutive mapping
        List<Integer> sortedLabels = new ArrayList<>(uniqueLabels);
        Collections.sort(sortedLabels);
        
        Map<Integer, Integer> mapping = new HashMap<>();
        int newLabel = 1;
        for (Integer oldLabel : sortedLabels) {
            mapping.put(oldLabel, newLabel++);
        }
        
        return mapping;
    }
}