package io.github.kusumotok.spotworkflow.core.alg;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import inra.ijpb.binary.BinaryImages;
import io.github.kusumotok.spotworkflow.core.model.Connectivity;
import io.github.kusumotok.spotworkflow.core.model.MarkerResult3D;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Two-pass seeded watershed segmentation for Seeded Spot Quantifier 3D.
 *
 * Algorithm:
 *  1. [Optional] Gaussian blur once on a working copy
 *  2. Seed detection: CC at seedThreshold + size filter (same logic as SpotQuantifier3D)
 *  3. If areaEnabled: build domain mask at areaThreshold, run seeded watershed
 *     If areaEnabled=false: return seed CC directly (watershed bypass)
 *
 * When multiple seeds fall in the same domain region, watershed splits them.
 * Domain regions that contain no seed are excluded from the result.
 */
public class SeededQuantifier3D {

    /**
     * Compound result holding seed segmentation (raw + filtered) and the final segmentation.
     * When areaEnabled=false, seedSeg and finalSeg point to the same object.
     */
    public static class SeededResult {
        /** All seed CC labels before size filter (for Seed ROI export). */
        public final SegmentationResult3D rawSeedSeg;
        /** Size-filtered seed labels (from seed threshold CC). */
        public final SegmentationResult3D seedSeg;
        /** Final segmentation (watershed if area enabled, otherwise same as seedSeg). */
        public final SegmentationResult3D finalSeg;
        /** Raw seed label id -> voxel count. */
        public final Map<Integer, Long> seedVoxelCounts;

        public SeededResult(SegmentationResult3D rawSeedSeg,
                            SegmentationResult3D seedSeg,
                            SegmentationResult3D finalSeg) {
            this(rawSeedSeg, seedSeg, finalSeg, null);
        }

        public SeededResult(SegmentationResult3D rawSeedSeg,
                            SegmentationResult3D seedSeg,
                            SegmentationResult3D finalSeg,
                            Map<Integer, Long> seedVoxelCounts) {
            this.rawSeedSeg      = rawSeedSeg;
            this.seedSeg         = seedSeg;
            this.finalSeg        = finalSeg;
            this.seedVoxelCounts = seedVoxelCounts;
        }
    }

    /**
     * Run two-pass seeded segmentation.
     *
     * @param imp            Input 3D image
     * @param areaThreshold  Low threshold defining the extent of each spot (domain)
     * @param seedThreshold  High threshold for seed detection
     * @param params         Quantifier params (size filter, gauss, connectivity, fillHoles).
     *                       params.threshold is ignored; areaThreshold / seedThreshold are used.
     * @param voxelVol       µm³ per voxel (for seed size filter)
     * @param areaEnabled    If false, watershed is skipped and seed CC is used as final result
     * @return SeededResult with seedSeg and finalSeg, or null if no seeds found
     */
    public static SeededResult compute(ImagePlus imp,
                                       int areaThreshold,
                                       int seedThreshold,
                                       QuantifierParams params,
                                       double voxelVol,
                                       boolean areaEnabled) {
        return compute(imp, areaThreshold, seedThreshold, params, voxelVol, areaEnabled, null, null);
    }

    public static SeededResult compute(ImagePlus imp,
                                       int areaThreshold,
                                       int seedThreshold,
                                       QuantifierParams params,
                                       double voxelVol,
                                       boolean areaEnabled,
                                       Consumer<String> progress) {
        return compute(imp, areaThreshold, seedThreshold, params, voxelVol, areaEnabled, progress, null);
    }

    public static SeededResult compute(ImagePlus imp,
                                       int areaThreshold,
                                       int seedThreshold,
                                       QuantifierParams params,
                                       double voxelVol,
                                       boolean areaEnabled,
                                       Consumer<String> progress,
                                       BooleanSupplier shouldCancel) {
        int w = imp.getWidth();
        int h = imp.getHeight();
        int d = imp.getNSlices();

        // 1. Apply Gaussian blur once (if enabled) on a working copy
        ImagePlus blurred = imp;
        if (params.gaussianBlur) {
            reportProgress(progress, "blurring");
            checkCancelled(shouldCancel);
            blurred = imp.duplicate();
            blurred.setTitle(imp.getShortTitle() + "-blurred");
            IJ.run(blurred, "Gaussian Blur 3D...",
                "x=" + params.gaussXY + " y=" + params.gaussXY + " z=" + params.gaussZ);
            checkCancelled(shouldCancel);
        }

        try {
            // 2. Seed detection: CC at seedThreshold + size filter
            reportProgress(progress, "finding seed components");
            CcResult3D seedCC = SpotQuantifier3D.computeCCFromBlurred(blurred, seedThreshold, params, shouldCancel);
            if (seedCC.voxelCounts.isEmpty()) {
                return null;
            }
            // Raw seeds: all CC labels marked valid (no size filter)
            Map<Integer, Integer> allValid = new HashMap<>();
            seedCC.voxelCounts.keySet().forEach(k -> allValid.put(k, CcResult3D.STATUS_VALID));
            SegmentationResult3D rawSeedSeg = seedCC.buildFilteredResult(allValid);

            reportProgress(progress, "filtering seed components");
            checkCancelled(shouldCancel);
            Map<Integer, Integer> seedStatus = seedCC.classifyLabels(params, voxelVol);
            SegmentationResult3D filteredSeeds = seedCC.buildFilteredResult(seedStatus);

            // Count valid seeds
            int seedCount = 0;
            for (int st : seedStatus.values()) {
                if (st == CcResult3D.STATUS_VALID) seedCount++;
            }
            if (seedCount == 0 && areaEnabled) {
                return null;
            }

            return finishFromSeedLabels(imp, blurred, rawSeedSeg, filteredSeeds,
                areaThreshold, params, areaEnabled, progress, shouldCancel, seedCC.voxelCounts);

        } finally {
            if (params.gaussianBlur && blurred != imp) {
                blurred.close();
            }
        }
    }

    public static SeededResult computeFromSeedLabels(ImagePlus imp,
                                                     ImagePlus seedLabelImage,
                                                     int areaThreshold,
                                                     QuantifierParams params,
                                                     boolean areaEnabled,
                                                     Consumer<String> progress,
                                                     BooleanSupplier shouldCancel) {
        ImagePlus blurred = imp;
        if (params.gaussianBlur) {
            reportProgress(progress, "blurring");
            checkCancelled(shouldCancel);
            blurred = imp.duplicate();
            blurred.setTitle(imp.getShortTitle() + "-blurred");
            IJ.run(blurred, "Gaussian Blur 3D...",
                "x=" + params.gaussXY + " y=" + params.gaussXY + " z=" + params.gaussZ);
            checkCancelled(shouldCancel);
        }
        try {
            SegmentationResult3D seeds = new SegmentationResult3D(seedLabelImage);
            if (countDistinctLabels(seedLabelImage.getStack(), shouldCancel) == 0) return null;
            return finishFromSeedLabels(imp, blurred, seeds, seeds,
                areaThreshold, params, areaEnabled, progress, shouldCancel, null);
        } finally {
            if (params.gaussianBlur && blurred != imp) blurred.close();
        }
    }

    private static SeededResult finishFromSeedLabels(ImagePlus imp,
                                                     ImagePlus blurred,
                                                     SegmentationResult3D rawSeedSeg,
                                                     SegmentationResult3D filteredSeeds,
                                                     int areaThreshold,
                                                     QuantifierParams params,
                                                     boolean areaEnabled,
                                                     Consumer<String> progress,
                                                     BooleanSupplier shouldCancel,
                                                     Map<Integer, Long> seedVoxelCounts) {
        int w = imp.getWidth();
        int h = imp.getHeight();
        int d = imp.getNSlices();

        if (!areaEnabled) return new SeededResult(rawSeedSeg, filteredSeeds, filteredSeeds, seedVoxelCounts);

        reportProgress(progress, "building area mask");
        ImageStack blurredStack = blurred.getStack();
        ImageStack domainStack  = new ImageStack(w, h);
        for (int z = 1; z <= d; z++) {
            checkCancelled(shouldCancel);
            ImageProcessor ip = blurredStack.getProcessor(z);
            ByteProcessor bp  = new ByteProcessor(w, h);
            byte[] bpix = (byte[]) bp.getPixels();
            for (int y = 0; y < h; y++) {
                checkCancelled(shouldCancel);
                for (int x = 0; x < w; x++) {
                    if (ip.get(x, y) >= areaThreshold) bpix[y * w + x] = (byte) 255;
                }
            }
            domainStack.addSlice(bp);
        }
        ImagePlus domainImp = new ImagePlus("domain", domainStack);
        if (params.fillHoles) {
            reportProgress(progress, "filling mask holes");
            checkCancelled(shouldCancel);
            IJ.run(domainImp, "Fill Holes", "stack");
            domainStack = domainImp.getStack();
            checkCancelled(shouldCancel);
        }

        reportProgress(progress, "building area components");
        checkCancelled(shouldCancel);
        ImagePlus areaLabelImp = BinaryImages.componentsLabeling(domainImp, params.connectivity, 32);
        ImageStack areaLabelStack = areaLabelImp.getStack();

        AreaPartition partition = partitionAreas(
            filteredSeeds.labelImage.getStack(), areaLabelStack, params.areaConflictMode, shouldCancel);

        reportProgress(progress, "assigning single-seed areas");
        ImageStack finalLabelStack = createEmptyLabelStack(filteredSeeds.labelImage.getStack());
        int ambiguousAreaCount = populateShortcutAndAmbiguousMasks(
            areaLabelStack, filteredSeeds.labelImage.getStack(), partition, finalLabelStack, shouldCancel);

        if (ambiguousAreaCount == 0) {
            reportProgress(progress, "watershed skipped");
            return new SeededResult(rawSeedSeg, filteredSeeds,
                new SegmentationResult3D(new ImagePlus(imp.getShortTitle() + "-labels-3D", finalLabelStack)),
                seedVoxelCounts);
        }

        reportProgress(progress, "running watershed on ambiguous areas");
        Connectivity conn = Connectivity.fromInt(params.connectivity);
        MarkerResult3D markers = new MarkerResult3D(
            partition.ambiguousSeedStack, partition.ambiguousDomainStack, partition.ambiguousSeedCount);
        SegmentationResult3D watershedResult = new Watershed3DRunner().run(blurred, markers, conn, shouldCancel);
        mergeLabels(finalLabelStack, watershedResult.labelImage.getStack(), shouldCancel);
        return new SeededResult(rawSeedSeg, filteredSeeds,
            new SegmentationResult3D(new ImagePlus(imp.getShortTitle() + "-labels-3D", finalLabelStack)),
            seedVoxelCounts);
    }

    private static void reportProgress(Consumer<String> progress, String message) {
        if (progress != null) progress.accept(message);
    }

    private static void checkCancelled(BooleanSupplier shouldCancel) {
        if (shouldCancel != null && shouldCancel.getAsBoolean()) {
            throw new CancellationException();
        }
    }

    private static AreaPartition partitionAreas(ImageStack seedLabelStack,
                                                ImageStack areaLabelStack,
                                                QuantifierParams.AreaConflictMode mode,
                                                BooleanSupplier shouldCancel) {
        int w = seedLabelStack.getWidth();
        int h = seedLabelStack.getHeight();
        int d = seedLabelStack.getSize();

        Map<SeedAreaKey, Integer> overlapByPair = new HashMap<>();
        for (int z = 1; z <= d; z++) {
            checkCancelled(shouldCancel);
            ImageProcessor seedIp = seedLabelStack.getProcessor(z);
            ImageProcessor areaIp = areaLabelStack.getProcessor(z);
            for (int y = 0; y < h; y++) {
                checkCancelled(shouldCancel);
                for (int x = 0; x < w; x++) {
                    int seedLabel = (int) Math.round(seedIp.getPixelValue(x, y));
                    if (seedLabel <= 0) continue;
                    int areaLabel = (int) Math.round(areaIp.getPixelValue(x, y));
                    if (areaLabel <= 0) continue;
                    SeedAreaKey key = new SeedAreaKey(seedLabel, areaLabel);
                    overlapByPair.put(key, overlapByPair.getOrDefault(key, 0) + 1);
                }
            }
        }

        Map<Integer, Map<Integer, Integer>> areaOverlapBySeed = new HashMap<>();
        int maxSeedLabel = 0;
        for (Map.Entry<SeedAreaKey, Integer> entry : overlapByPair.entrySet()) {
            SeedAreaKey key = entry.getKey();
            if (key.seedLabel > maxSeedLabel) maxSeedLabel = key.seedLabel;
            Map<Integer, Integer> byArea = areaOverlapBySeed.get(key.seedLabel);
            if (byArea == null) {
                byArea = new HashMap<>();
                areaOverlapBySeed.put(key.seedLabel, byArea);
            }
            byArea.put(key.areaLabel, entry.getValue());
        }

        Map<SeedAreaKey, Integer> outputLabelByPair = new HashMap<>();
        int nextSplitLabel = maxSeedLabel + 1;
        for (Map.Entry<Integer, Map<Integer, Integer>> entry : areaOverlapBySeed.entrySet()) {
            int seedLabel = entry.getKey();
            Map<Integer, Integer> byArea = entry.getValue();
            if (mode == QuantifierParams.AreaConflictMode.SPLIT && byArea.size() > 1) {
                boolean first = true;
                for (int areaLabel : byArea.keySet()) {
                    int outLabel = first ? seedLabel : nextSplitLabel++;
                    first = false;
                    outputLabelByPair.put(new SeedAreaKey(seedLabel, areaLabel), outLabel);
                }
            } else {
                int bestArea = -1;
                int bestOverlap = -1;
                for (Map.Entry<Integer, Integer> areaEntry : byArea.entrySet()) {
                    int areaLabel = areaEntry.getKey();
                    int overlap = areaEntry.getValue();
                    if (overlap > bestOverlap || (overlap == bestOverlap && areaLabel < bestArea)) {
                        bestArea = areaLabel;
                        bestOverlap = overlap;
                    }
                }
                if (bestArea > 0) {
                    outputLabelByPair.put(new SeedAreaKey(seedLabel, bestArea), seedLabel);
                }
            }
        }

        Map<Integer, Integer> areaSeedCount = new HashMap<>();
        Map<Integer, Integer> singleSeedLabelByArea = new HashMap<>();
        for (Map.Entry<SeedAreaKey, Integer> entry : outputLabelByPair.entrySet()) {
            int areaLabel = entry.getKey().areaLabel;
            int outputLabel = entry.getValue();
            int count = areaSeedCount.getOrDefault(areaLabel, 0) + 1;
            areaSeedCount.put(areaLabel, count);
            if (count == 1) {
                singleSeedLabelByArea.put(areaLabel, outputLabel);
            } else {
                singleSeedLabelByArea.remove(areaLabel);
            }
        }

        ImageStack ambiguousDomainStack = new ImageStack(w, h);
        ImageStack ambiguousSeedStack = createEmptyLabelStack(seedLabelStack);
        return new AreaPartition(areaSeedCount, singleSeedLabelByArea, outputLabelByPair,
            ambiguousDomainStack, ambiguousSeedStack);
    }

    private static int populateShortcutAndAmbiguousMasks(ImageStack areaLabelStack,
                                                         ImageStack seedLabelStack,
                                                         AreaPartition partition,
                                                         ImageStack finalLabelStack,
                                                         BooleanSupplier shouldCancel) {
        int w = areaLabelStack.getWidth();
        int h = areaLabelStack.getHeight();
        int d = areaLabelStack.getSize();
        int ambiguousAreaCount = 0;
        Map<Integer, Boolean> ambiguousSeen = new HashMap<>();

        for (int z = 1; z <= d; z++) {
            checkCancelled(shouldCancel);
            ImageProcessor areaIp = areaLabelStack.getProcessor(z);
            ImageProcessor outIp = finalLabelStack.getProcessor(z);
            ByteProcessor ambiguousDomainIp = new ByteProcessor(w, h);
            ImageProcessor ambiguousSeedIp = partition.ambiguousSeedStack.getProcessor(z);
            byte[] ambiguousDomainPixels = (byte[]) ambiguousDomainIp.getPixels();

            for (int y = 0; y < h; y++) {
                checkCancelled(shouldCancel);
                for (int x = 0; x < w; x++) {
                    int areaLabel = (int) Math.round(areaIp.getPixelValue(x, y));
                    if (areaLabel <= 0) continue;

                    int seedCount = partition.areaSeedCount.getOrDefault(areaLabel, 0);
                    if (seedCount == 1) {
                        int seedLabel = partition.singleSeedLabelByArea.get(areaLabel);
                        outIp.putPixelValue(x, y, seedLabel);
                    } else if (seedCount > 1) {
                        ambiguousDomainPixels[y * w + x] = (byte) 255;
                        if (!ambiguousSeen.containsKey(areaLabel)) {
                            ambiguousSeen.put(areaLabel, Boolean.TRUE);
                            ambiguousAreaCount++;
                        }
                    }
                }
            }

            ambiguousSeedIp = partition.ambiguousSeedStack.getProcessor(z);
            ImageProcessor sourceSeedIp = seedLabelStack.getProcessor(z);
            for (int y = 0; y < h; y++) {
                checkCancelled(shouldCancel);
                for (int x = 0; x < w; x++) {
                    if (ambiguousDomainPixels[y * w + x] == 0) continue;
                    int seedLabel = (int) Math.round(sourceSeedIp.getPixelValue(x, y));
                    if (seedLabel > 0) {
                        int areaLabel = (int) Math.round(areaIp.getPixelValue(x, y));
                        Integer outputLabel = partition.outputLabelByPair.get(new SeedAreaKey(seedLabel, areaLabel));
                        if (outputLabel != null) ambiguousSeedIp.putPixelValue(x, y, outputLabel);
                    }
                }
            }

            partition.ambiguousDomainStack.addSlice(ambiguousDomainIp);
        }

        partition.ambiguousSeedCount = countDistinctLabels(partition.ambiguousSeedStack, shouldCancel);
        return ambiguousAreaCount;
    }

    private static void mergeLabels(ImageStack dst, ImageStack src, BooleanSupplier shouldCancel) {
        int w = dst.getWidth();
        int h = dst.getHeight();
        int d = dst.getSize();
        for (int z = 1; z <= d; z++) {
            checkCancelled(shouldCancel);
            ImageProcessor dstIp = dst.getProcessor(z);
            ImageProcessor srcIp = src.getProcessor(z);
            for (int y = 0; y < h; y++) {
                checkCancelled(shouldCancel);
                for (int x = 0; x < w; x++) {
                    int label = (int) Math.round(srcIp.getPixelValue(x, y));
                    if (label > 0) {
                        dstIp.putPixelValue(x, y, label);
                    }
                }
            }
        }
    }

    private static ImageStack createEmptyLabelStack(ImageStack like) {
        int w = like.getWidth();
        int h = like.getHeight();
        int d = like.getSize();
        ImageStack out = new ImageStack(w, h);
        for (int z = 1; z <= d; z++) {
            out.addSlice(like.getProcessor(z).createProcessor(w, h));
        }
        return out;
    }

    private static int countDistinctLabels(ImageStack stack, BooleanSupplier shouldCancel) {
        Map<Integer, Boolean> labels = new HashMap<>();
        int w = stack.getWidth();
        int h = stack.getHeight();
        int d = stack.getSize();
        for (int z = 1; z <= d; z++) {
            checkCancelled(shouldCancel);
            ImageProcessor ip = stack.getProcessor(z);
            for (int y = 0; y < h; y++) {
                checkCancelled(shouldCancel);
                for (int x = 0; x < w; x++) {
                    int label = (int) Math.round(ip.getPixelValue(x, y));
                    if (label > 0) labels.put(label, Boolean.TRUE);
                }
            }
        }
        return labels.size();
    }

    private static class AreaPartition {
        final Map<Integer, Integer> areaSeedCount;
        final Map<Integer, Integer> singleSeedLabelByArea;
        final Map<SeedAreaKey, Integer> outputLabelByPair;
        final ImageStack ambiguousDomainStack;
        final ImageStack ambiguousSeedStack;
        int ambiguousSeedCount;

        AreaPartition(Map<Integer, Integer> areaSeedCount,
                      Map<Integer, Integer> singleSeedLabelByArea,
                      Map<SeedAreaKey, Integer> outputLabelByPair,
                      ImageStack ambiguousDomainStack,
                      ImageStack ambiguousSeedStack) {
            this.areaSeedCount = areaSeedCount;
            this.singleSeedLabelByArea = singleSeedLabelByArea;
            this.outputLabelByPair = outputLabelByPair;
            this.ambiguousDomainStack = ambiguousDomainStack;
            this.ambiguousSeedStack = ambiguousSeedStack;
        }
    }

    private static class SeedAreaKey {
        final int seedLabel;
        final int areaLabel;

        SeedAreaKey(int seedLabel, int areaLabel) {
            this.seedLabel = seedLabel;
            this.areaLabel = areaLabel;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof SeedAreaKey)) return false;
            SeedAreaKey other = (SeedAreaKey) obj;
            return seedLabel == other.seedLabel && areaLabel == other.areaLabel;
        }

        @Override
        public int hashCode() {
            return 31 * seedLabel + areaLabel;
        }
    }
}
