package io.github.kusumotok.spotworkflow.core.alg;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import inra.ijpb.binary.BinaryImages;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Core algorithm for 3D spot quantification:
 *   1. [Optional] Gaussian Blur 3D
 *   2. Fixed threshold → binary mask
 *   3. FloodFillComponentsLabeling3D (MorphoLibJ, 26-connectivity)
 *   4. Collect per-label voxel counts
 *
 * Size filtering is NOT applied here; use CcResult3D.classifyLabels() and
 * CcResult3D.buildFilteredResult() to apply size constraints after caching.
 */
public class SpotQuantifier3D {

    /**
     * Run CC labeling on an already-blurred (or raw) image at the given threshold.
     * Gaussian blur is NOT applied; the caller manages image lifecycle.
     * Used by SeededQuantifier3D to reuse a single blurred copy across two threshold passes.
     *
     * @param blurred   Pre-blurred image (or raw if blur is disabled)
     * @param threshold Intensity threshold for binarization
     * @param params    Quantifier params (connectivity, fillHoles used; threshold ignored)
     */
    public static CcResult3D computeCCFromBlurred(ImagePlus blurred, int threshold,
                                                   QuantifierParams params) {
        return computeCCFromBlurred(blurred, threshold, params, null);
    }

    public static CcResult3D computeCCFromBlurred(ImagePlus blurred, int threshold,
                                                   QuantifierParams params, BooleanSupplier shouldCancel) {
        int w = blurred.getWidth();
        int h = blurred.getHeight();
        int d = blurred.getNSlices();

        // --- 1. Build binary stack: pixels >= threshold → 255, else 0 ---
        ImageStack srcStack    = blurred.getStack();
        ImageStack binaryStack = new ImageStack(w, h);
        for (int z = 1; z <= d; z++) {
            checkCancelled(shouldCancel);
            ImageProcessor ip = srcStack.getProcessor(z);
            ByteProcessor bp  = new ByteProcessor(w, h);
            byte[] bpix = (byte[]) bp.getPixels();
            for (int y = 0; y < h; y++) {
                checkCancelled(shouldCancel);
                for (int x = 0; x < w; x++) {
                    if (ip.get(x, y) >= threshold) {
                        bpix[y * w + x] = (byte) 255;
                    }
                }
            }
            binaryStack.addSlice(bp);
        }

        // --- 2. Optional fill holes (per-slice 2D) ---
        ImagePlus binaryImp = new ImagePlus("binary", binaryStack);
        if (params.fillHoles) {
            IJ.run(binaryImp, "Fill Holes", "stack");
        }

        // --- 3. 3D connected-components labeling (32-bit labels) ---
        // 32-bit avoids the 65535 label cap when many noise CCs are present above threshold.
        checkCancelled(shouldCancel);
        ImagePlus labelImp  = BinaryImages.componentsLabeling(binaryImp, params.connectivity, 32);
        ImageStack labelStack = labelImp.getStack();
        checkCancelled(shouldCancel);

        // --- 4. Count voxels per label ---
        Map<Integer, Long> voxelCounts = new HashMap<>();
        for (int z = 1; z <= d; z++) {
            checkCancelled(shouldCancel);
            ImageProcessor ip = labelStack.getProcessor(z);
            for (int y = 0; y < h; y++) {
                checkCancelled(shouldCancel);
                for (int x = 0; x < w; x++) {
                    int label = (int) Math.round(ip.getPixelValue(x, y));
                    if (label > 0) {
                        voxelCounts.merge(label, 1L, Long::sum);
                    }
                }
            }
        }

        ImagePlus labelImage = new ImagePlus(blurred.getShortTitle() + "-cc", labelStack);
        return new CcResult3D(labelImage, voxelCounts);
    }

    private static void checkCancelled(BooleanSupplier shouldCancel) {
        if (shouldCancel != null && shouldCancel.getAsBoolean()) {
            throw new CancellationException();
        }
    }
}
