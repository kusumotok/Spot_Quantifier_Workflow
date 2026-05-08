package io.github.kusumotok.spotworkflow.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable data class holding statistics about the slice merging process.
 * Contains information about the number of slices processed, 2D regions detected,
 * final 3D regions created, processing time, and per-region voxel counts.
 *
 * <p>This class is used to provide summary information about the 3D segmentation
 * process, particularly useful for logging and performance analysis.</p>
 *
 * @see io.github.kusumotok.spotworkflow.core.model.SliceMerger
 */
public final class MergeStatistics {
    /**
     * The total number of slices processed.
     */
    public final int totalSlices;

    /**
     * The total number of 2D regions detected across all slices.
     */
    public final int total2DRegions;

    /**
     * The total number of 3D regions after merging.
     */
    public final int total3DRegions;

    /**
     * The processing time in milliseconds.
     */
    public final long processingTimeMs;

    /**
     * Voxel counts per 3D region (label -> voxel count). Unmodifiable.
     */
    public final Map<Integer, Integer> voxelCounts;

    /**
     * Creates a new MergeStatistics with the specified parameters.
     *
     * @param totalSlices the total number of slices processed (must be non-negative)
     * @param total2DRegions the total number of 2D regions detected (must be non-negative)
     * @param total3DRegions the total number of 3D regions after merging (must be non-negative)
     * @param processingTimeMs the processing time in milliseconds (must be non-negative)
     * @throws IllegalArgumentException if any parameter is negative
     */
    public MergeStatistics(int totalSlices, int total2DRegions, int total3DRegions, long processingTimeMs) {
        this(totalSlices, total2DRegions, total3DRegions, processingTimeMs, Collections.emptyMap());
    }

    /**
     * Creates a new MergeStatistics with the specified parameters including voxel counts.
     *
     * @param totalSlices the total number of slices processed (must be non-negative)
     * @param total2DRegions the total number of 2D regions detected (must be non-negative)
     * @param total3DRegions the total number of 3D regions after merging (must be non-negative)
     * @param processingTimeMs the processing time in milliseconds (must be non-negative)
     * @param voxelCounts map of region label to voxel count (must not be null)
     * @throws IllegalArgumentException if any parameter is negative or voxelCounts is null
     */
    public MergeStatistics(int totalSlices, int total2DRegions, int total3DRegions,
                           long processingTimeMs, Map<Integer, Integer> voxelCounts) {
        if (totalSlices < 0) {
            throw new IllegalArgumentException("totalSlices must be non-negative");
        }
        if (total2DRegions < 0) {
            throw new IllegalArgumentException("total2DRegions must be non-negative");
        }
        if (total3DRegions < 0) {
            throw new IllegalArgumentException("total3DRegions must be non-negative");
        }
        if (processingTimeMs < 0) {
            throw new IllegalArgumentException("processingTimeMs must be non-negative");
        }
        if (voxelCounts == null) {
            throw new IllegalArgumentException("voxelCounts must not be null");
        }
        this.totalSlices = totalSlices;
        this.total2DRegions = total2DRegions;
        this.total3DRegions = total3DRegions;
        this.processingTimeMs = processingTimeMs;
        this.voxelCounts = Collections.unmodifiableMap(new LinkedHashMap<>(voxelCounts));
    }

    /**
     * Returns a human-readable summary string of the merge statistics.
     * The summary includes information about slices processed, regions detected,
     * processing time, and per-region voxel counts.
     *
     * @return a formatted summary string
     */
    public String toSummaryString() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Slice-Based 3D Segmentation Summary ===\n");
        sb.append(String.format("Slices processed: %d\n", totalSlices));
        sb.append(String.format("2D regions detected: %d\n", total2DRegions));
        sb.append(String.format("3D regions after merging: %d\n", total3DRegions));
        sb.append(String.format("Processing time: %.2f seconds\n", processingTimeMs / 1000.0));

        if (total2DRegions > 0) {
            double mergeRatio = (double) total3DRegions / total2DRegions;
            sb.append(String.format("Merge ratio: %.2f (3D/2D)\n", mergeRatio));
        }

        if (!voxelCounts.isEmpty()) {
            sb.append("--- Voxel Counts per 3D Region ---\n");
            List<Integer> sortedLabels = new ArrayList<>(voxelCounts.keySet());
            Collections.sort(sortedLabels);

            int totalVoxels = 0;
            for (Integer label : sortedLabels) {
                int count = voxelCounts.get(label);
                totalVoxels += count;
                sb.append(String.format("  Region %d: %d voxels\n", label, count));
            }

            sb.append(String.format("Total foreground voxels: %d\n", totalVoxels));

            if (sortedLabels.size() > 0) {
                double averageSize = (double) totalVoxels / sortedLabels.size();
                int minSize = Collections.min(voxelCounts.values());
                int maxSize = Collections.max(voxelCounts.values());
                sb.append(String.format("Average region size: %.1f voxels\n", averageSize));
                sb.append(String.format("Size range: %d - %d voxels\n", minSize, maxSize));
            }
        }

        sb.append("==========================================");
        return sb.toString();
    }

    /**
     * Returns a string representation of this MergeStatistics.
     *
     * @return a string in the format "MergeStatistics(slices=X, 2D=Y, 3D=Z, time=Tms)"
     */
    @Override
    public String toString() {
        return String.format("MergeStatistics(slices=%d, 2D=%d, 3D=%d, time=%dms)",
                totalSlices, total2DRegions, total3DRegions, processingTimeMs);
    }
}
