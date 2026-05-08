package io.github.kusumotok.spotworkflow.core.alg;

/**
 * Parameters for Spot_Quantifier_3D_.
 * minVolUm3 / maxVolUm3 == null means the filter is disabled.
 */
public class QuantifierParams {
    public enum AreaConflictMode {
        MAX_OVERLAP,
        SPLIT
    }

    public final int     threshold;
    public final Double  minVolUm3;    // null = no lower limit
    public final Double  maxVolUm3;    // null = no upper limit
    public final boolean gaussianBlur;
    public final double  gaussXY;      // sigma XY (pixels); used only when gaussianBlur=true
    public final double  gaussZ;       // sigma Z  (pixels); used only when gaussianBlur=true
    public final int     connectivity; // 3D CC connectivity: 6, 18, or 26
    public final boolean fillHoles;    // fill holes in binary mask before CC labeling
    public final AreaConflictMode areaConflictMode;

    public QuantifierParams(int threshold, Double minVolUm3, Double maxVolUm3,
                             boolean gaussianBlur, double gaussXY, double gaussZ,
                             int connectivity, boolean fillHoles) {
        this(threshold, minVolUm3, maxVolUm3, gaussianBlur, gaussXY, gaussZ,
            connectivity, fillHoles, AreaConflictMode.MAX_OVERLAP);
    }

    public QuantifierParams(int threshold, Double minVolUm3, Double maxVolUm3,
                             boolean gaussianBlur, double gaussXY, double gaussZ,
                             int connectivity, boolean fillHoles,
                             AreaConflictMode areaConflictMode) {
        this.threshold    = threshold;
        this.minVolUm3    = minVolUm3;
        this.maxVolUm3    = maxVolUm3;
        this.gaussianBlur = gaussianBlur;
        this.gaussXY      = gaussXY;
        this.gaussZ       = gaussZ;
        this.connectivity = connectivity;
        this.fillHoles    = fillHoles;
        this.areaConflictMode = areaConflictMode != null ? areaConflictMode : AreaConflictMode.MAX_OVERLAP;
    }
}
