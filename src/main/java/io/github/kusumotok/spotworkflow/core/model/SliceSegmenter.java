package io.github.kusumotok.spotworkflow.core.model;

import ij.ImagePlus;
import ij.ImageStack;
import ij.IJ;
import ij.process.ImageProcessor;
import ij.process.IntProcessor;
import io.github.kusumotok.spotworkflow.core.alg.SegmentationResult;
import io.github.kusumotok.spotworkflow.core.alg.WatershedRunner;
import java.util.List;
import java.util.ArrayList;

/**
 * SliceSegmenter performs 2D segmentation on each slice of a 3D stack
 * using the Simple plugin approach (Watershed, INVERT_ORIGINAL, C4).
 *
 * @see ThresholdModel
 * @see MarkerBuilder
 */
public class SliceSegmenter {

    /**
     * Segments all slices in a 3D stack using 2D segmentation.
     *
     * @param stack3D the input 3D stack (must have at least 2 slices)
     * @param bgThreshold background threshold value
     * @param tolerance FindMaxima tolerance value
     * @return a list of label images, one per slice
     */
    public List<ImagePlus> segmentAllSlices(ImagePlus stack3D, int bgThreshold, double tolerance) {
        if (stack3D == null) {
            throw new IllegalArgumentException("Input stack cannot be null");
        }

        int nSlices = stack3D.getNSlices();
        if (nSlices < 2) {
            throw new IllegalArgumentException("Stack must have at least 2 slices");
        }

        List<ImagePlus> results = new ArrayList<>();
        ImageStack stack = stack3D.getStack();

        IJ.log(String.format("Processing %d slices individually...", nSlices));

        for (int z = 1; z <= nSlices; z++) {
            IJ.log(String.format("Processing slice %d/%d...", z, nSlices));

            ImageProcessor sliceProcessor = stack.getProcessor(z);
            ImagePlus slice = new ImagePlus(stack3D.getShortTitle() + "_slice_" + z, sliceProcessor);

            ImagePlus labelImage = segmentSingleSlice(slice, bgThreshold, tolerance, z);
            results.add(labelImage);
        }

        IJ.log("Individual slice processing completed.");
        return results;
    }

    private ImagePlus segmentSingleSlice(ImagePlus slice, int bgThreshold, double tolerance, int sliceIndex) {
        ThresholdModel sliceModel = ThresholdModel.createForSimplePlugin(slice);
        sliceModel.setTBg(bgThreshold);
        sliceModel.setFindMaximaTolerance(tolerance);

        MarkerBuilder markerBuilder = new MarkerBuilder();
        MarkerResult markers = markerBuilder.build(slice, sliceModel);

        if (markers.fgCount == 0) {
            IJ.log(String.format("Slice %d: No seeds found, treating as background.", sliceIndex));
            return createEmptyLabelImage(slice.getWidth(), slice.getHeight(), slice.getShortTitle() + "_labels");
        }

        IJ.log(String.format("Slice %d: %d seeds detected", sliceIndex, markers.fgCount));

        WatershedRunner watershedRunner = new WatershedRunner();
        SegmentationResult segResult = watershedRunner.run(
            slice, markers, sliceModel.getSurface(), sliceModel.getConnectivity(),
            false, 0.0
        );

        return segResult.labelImage;
    }

    private ImagePlus createEmptyLabelImage(int width, int height, String title) {
        int[] pixels = new int[width * height];
        IntProcessor processor = new IntProcessor(width, height, pixels);
        return new ImagePlus(title, processor);
    }
}
