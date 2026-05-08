package io.github.kusumotok.spotworkflow.core.model;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import inra.ijpb.binary.conncomp.ConnectedComponentsLabeling3D;
import inra.ijpb.binary.conncomp.FloodFillComponentsLabeling3D;
import inra.ijpb.morphology.MinimaAndMaxima3D;

public class MarkerBuilder3D {

    /**
     * Build 3D seed markers using Extended Maxima.
     * @param imp 3D image stack
     * @param bgThreshold background threshold (domain = intensity >= bgThreshold)
     * @param tolerance Extended Maxima tolerance
     * @param connectivity C6 connectivity
     * @return MarkerResult3D with 3D labels, domain mask, and seed count
     */
    public MarkerResult3D build(ImagePlus imp, int bgThreshold,
                                double tolerance, Connectivity connectivity) {
        ImageStack stack = imp.getStack();
        int w = imp.getWidth();
        int h = imp.getHeight();
        int d = imp.getNSlices();
        int conn3d = connectivity.to3D();

        // Create domain mask (intensity >= bgThreshold)
        ImageStack domainStack = new ImageStack(w, h);
        for (int z = 1; z <= d; z++) {
            ImageProcessor ip = stack.getProcessor(z);
            ByteProcessor bp = new ByteProcessor(w, h);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (ip.getPixelValue(x, y) >= bgThreshold) {
                        bp.set(x, y, 255);
                    }
                }
            }
            domainStack.addSlice(bp);
        }

        // Detect 3D extended maxima using MorphoLibJ
        ImageStack maximaStack = MinimaAndMaxima3D.extendedMaxima(stack, tolerance, conn3d);

        // Mask maxima by domain (only keep maxima within domain)
        for (int z = 1; z <= d; z++) {
            ImageProcessor maxIp = maximaStack.getProcessor(z);
            ImageProcessor domIp = domainStack.getProcessor(z);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (domIp.get(x, y) == 0) {
                        maxIp.set(x, y, 0);
                    }
                }
            }
        }

        // Label connected components in maxima
        ConnectedComponentsLabeling3D labeling = new FloodFillComponentsLabeling3D(conn3d, 32);
        ImageStack seedLabels = labeling.computeLabels(maximaStack);

        // Count seed labels
        int seedCount = countLabels(seedLabels);

        return new MarkerResult3D(seedLabels, domainStack, seedCount);
    }

    private int countLabels(ImageStack labelStack) {
        int max = 0;
        int d = labelStack.getSize();
        for (int z = 1; z <= d; z++) {
            ImageProcessor ip = labelStack.getProcessor(z);
            int w = ip.getWidth();
            int h = ip.getHeight();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int v = ip.get(x, y);
                    if (v > max) max = v;
                }
            }
        }
        return max;
    }
}
