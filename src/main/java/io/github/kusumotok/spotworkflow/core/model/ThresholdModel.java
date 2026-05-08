package io.github.kusumotok.spotworkflow.core.model;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;

import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;

public class ThresholdModel {
    public enum Action { APPLY, ADD_ROI }

    private ImagePlus imp;
    private int tFg;
    private int tBg;
    private boolean invert;

    private PreviewMode previewMode = PreviewMode.MARKER_BOUNDARIES;
    private Method method = Method.WATERSHED;
    private Surface surface = Surface.INVERT_ORIGINAL;
    private Connectivity connectivity = Connectivity.C4;
    private boolean absorbUnknown = true;
    private OverlapRule overlapRule = OverlapRule.LAST_WINS;
    private int previewDebounceMs = 200;
    private final AppearanceSettings appearance = new AppearanceSettings();
    private MarkerSource markerSource = MarkerSource.FIND_MAXIMA;
    private int binarySourceId = -1;
    private double findMaximaTolerance = 2000.0;
    private double randomWalkerBeta = 90.0 / (255.0 * 255.0);
    private boolean preprocessingEnabled = false;
    private double sigmaSurface = 0.0;
    private double sigmaSeed = 0.0;
    private int seedMinArea = 0;
    private int seedMaxArea = 0;
    private final List<Roi> manualSeeds = new ArrayList<>();
    private int manualSeedVersion = 0;

    private Consumer<Action> uiCallback = a -> {};

    public ThresholdModel(ImagePlus imp) {
        this.imp = imp;
        int max = getMaxValue();
        // defaults per decisions: high side foreground; conservative init
        this.tFg = (int)Math.round(max * 0.8);
        this.tBg = (int)Math.round(max * 0.2);
        snapFg();
    }

    public void setUiCallback(Consumer<Action> cb) { this.uiCallback = (cb == null) ? (a -> {}) : cb; }

    public int getMaxValue() {
        return (int) Math.ceil(imp.getStatistics().max);
    }

    public int getMinValue() {
        return (int) Math.floor(imp.getStatistics().min);
    }

    public int getTFg() { return tFg; }
    public int getTBg() { return tBg; }
    public boolean isInvert() { return invert; }
    public PreviewMode getPreviewMode() { return previewMode; }
    public Method getMethod() { return method; }
    public Surface getSurface() { return surface; }
    public Connectivity getConnectivity() { return connectivity; }
    public boolean isAbsorbUnknown() { return absorbUnknown; }
    public OverlapRule getOverlapRule() { return overlapRule; }
    public int getPreviewDebounceMs() { return previewDebounceMs; }
    public AppearanceSettings getAppearance() { return appearance; }
    public MarkerSource getMarkerSource() { return markerSource; }
    public int getBinarySourceId() { return binarySourceId; }
    public double getFindMaximaTolerance() { return findMaximaTolerance; }
    public double getRandomWalkerBeta() { return randomWalkerBeta; }
    public boolean isPreprocessingEnabled() { return preprocessingEnabled; }
    public double getSigmaSurface() { return sigmaSurface; }
    public double getSigmaSeed() { return sigmaSeed; }
    public int getSeedMinArea() { return seedMinArea; }
    public int getSeedMaxArea() { return seedMaxArea; }
    public List<Roi> getManualSeeds() { return new ArrayList<>(manualSeeds); }
    public int getManualSeedVersion() { return manualSeedVersion; }

    public void setTFg(int v) { this.tFg = v; snapFg(); }
    public void setTBg(int v) { this.tBg = v; snapBg(); }
    public void setInvert(boolean v) { this.invert = v; }
    public void setPreviewMode(PreviewMode m) { this.previewMode = m; }
    public void setMethod(Method m) { this.method = m; }
    public void setSurface(Surface s) { this.surface = s; }
    public void setConnectivity(Connectivity c) { this.connectivity = c; }
    public void setAbsorbUnknown(boolean v) { this.absorbUnknown = v; }
    public void setOverlapRule(OverlapRule r) { this.overlapRule = r; }
    public void setPreviewDebounceMs(int ms) { this.previewDebounceMs = ms; }
    public void setMarkerSource(MarkerSource source) {
        this.markerSource = source;
        if (shouldEnforceThresholdOrder()) snapFg();
    }
    public void setBinarySourceId(int id) { this.binarySourceId = id; }
    public void setFindMaximaTolerance(double v) { this.findMaximaTolerance = v; }
    public void setRandomWalkerBeta(double v) { this.randomWalkerBeta = v; }
    public void setPreprocessingEnabled(boolean enabled) { this.preprocessingEnabled = enabled; }
    public void setSigmaSurface(double v) { this.sigmaSurface = v; }
    public void setSigmaSeed(double v) { this.sigmaSeed = v; }
    public void setSeedMinArea(int v) { this.seedMinArea = Math.max(0, v); }
    public void setSeedMaxArea(int v) { this.seedMaxArea = Math.max(0, v); }
    public void addManualSeed(Roi roi) {
        if (roi == null) return;
        manualSeeds.add((Roi) roi.clone());
        manualSeedVersion++;
    }
    public void clearManualSeeds() {
        if (manualSeeds.isEmpty()) return;
        manualSeeds.clear();
        manualSeedVersion++;
    }
    public void resetManualSeeds() { manualSeeds.clear(); manualSeedVersion++; }

    public void setImage(ImagePlus newImp) {
        this.imp = newImp;
        clamp();
    }

    private void snapFg() {
        // invariant: T_bg <= T_fg (snap the moved one)
        if (shouldEnforceThresholdOrder() && tFg < tBg) tFg = tBg;
        clamp();
    }

    private void snapBg() {
        // invariant: T_bg <= T_fg (snap the moved one)
        if (shouldEnforceThresholdOrder() && tBg > tFg) tBg = tFg;
        clamp();
    }

    private boolean shouldEnforceThresholdOrder() {
        return markerSource == MarkerSource.THRESHOLD_COMPONENTS;
    }

    private void clamp() {
        // Allow thresholds to be outside image range - no clamping needed
        // This allows users to set thresholds independently of image content
    }

    public void resetDefaults() {
        int min = getMinValue();
        int max = getMaxValue();
        double range = Math.max(0, max - min);
        this.tFg = (int)Math.round(min + range * 0.8);
        this.tBg = (int)Math.round(min + range * 0.2);
        this.invert = false;
        this.previewMode = PreviewMode.MARKER_BOUNDARIES;
        this.method = Method.WATERSHED;
        this.surface = Surface.INVERT_ORIGINAL;
        this.connectivity = Connectivity.C4;
        this.absorbUnknown = true;
        this.overlapRule = OverlapRule.LAST_WINS;
        this.previewDebounceMs = 200;
        this.markerSource = MarkerSource.FIND_MAXIMA;
        this.binarySourceId = -1;
        this.findMaximaTolerance = 2000.0;
        this.randomWalkerBeta = 90.0 / (255.0 * 255.0);
        this.preprocessingEnabled = false;
        this.sigmaSurface = 0.0;
        this.sigmaSeed = 0.0;
        this.seedMinArea = 0;
        this.seedMaxArea = 0;
        resetManualSeeds();
        snapFg();
    }

    public void requestApply() { uiCallback.accept(Action.APPLY); }
    public void requestAddRoi() { uiCallback.accept(Action.ADD_ROI); }

    public void showNotImplemented(String msg) {
        IJ.error("Not implemented", msg);
    }

    /**
     * Create a ThresholdModel with fixed parameters for the Simple plugin.
     * Fixed: C4, WATERSHED, INVERT_ORIGINAL, FIND_MAXIMA, no preprocessing.
     */
    public static ThresholdModel createForSimplePlugin(ImagePlus imp) {
        ThresholdModel m = new ThresholdModel(imp);
        m.connectivity = Connectivity.C4;
        m.method = Method.WATERSHED;
        m.surface = Surface.INVERT_ORIGINAL;
        m.markerSource = MarkerSource.FIND_MAXIMA;
        m.preprocessingEnabled = false;
        m.absorbUnknown = true;
        return m;
    }

    /**
     * Create a ThresholdModel with fixed parameters for the 3D plugin.
     * Fixed: C6, WATERSHED, INVERT_ORIGINAL, FIND_MAXIMA, no preprocessing.
     */
    public static ThresholdModel createFor3DPlugin(ImagePlus imp) {
        ThresholdModel m = new ThresholdModel(imp);
        m.connectivity = Connectivity.C6;
        m.method = Method.WATERSHED;
        m.surface = Surface.INVERT_ORIGINAL;
        m.markerSource = MarkerSource.FIND_MAXIMA;
        m.preprocessingEnabled = false;
        m.absorbUnknown = true;
        return m;
    }
}
