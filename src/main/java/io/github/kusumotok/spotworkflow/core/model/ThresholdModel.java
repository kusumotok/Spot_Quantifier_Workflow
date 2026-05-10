package io.github.kusumotok.spotworkflow.core.model;

import ij.ImagePlus;

/**
 * Holds the two threshold values (area = tBg, seed = tFg) for the segmentation UI.
 * Also owns the image reference so HistogramPanel can query the data range.
 */
public class ThresholdModel {

    private ImagePlus imp;
    private int tFg;
    private int tBg;

    public ThresholdModel(ImagePlus imp) {
        this.imp = imp;
        int max = getMaxValue();
        this.tFg = (int) Math.round(max * 0.8);
        this.tBg = (int) Math.round(max * 0.2);
    }

    public int getTFg() { return tFg; }
    public int getTBg() { return tBg; }

    public void setTFg(int v) { this.tFg = v; }
    public void setTBg(int v) { this.tBg = v; }

    public void setImage(ImagePlus newImp) { this.imp = newImp; }

    public int getMaxValue() {
        return (int) Math.ceil(imp.getStatistics().max);
    }

    public int getMinValue() {
        return (int) Math.floor(imp.getStatistics().min);
    }

    public void resetDefaults() {
        int min = getMinValue();
        int max = getMaxValue();
        double range = Math.max(0, max - min);
        this.tFg = (int) Math.round(min + range * 0.8);
        this.tBg = (int) Math.round(min + range * 0.2);
    }

    /** Factory: fixed settings for the 3D seeded workflow. */
    public static ThresholdModel createFor3DPlugin(ImagePlus imp) {
        return new ThresholdModel(imp);
    }
}
