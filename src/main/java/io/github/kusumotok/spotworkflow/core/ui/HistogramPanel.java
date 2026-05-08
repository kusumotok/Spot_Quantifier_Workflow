package io.github.kusumotok.spotworkflow.core.ui;

import ij.ImagePlus;
import ij.process.ImageProcessor;
import io.github.kusumotok.spotworkflow.core.model.ThresholdModel;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class HistogramPanel extends Panel {
    private ImagePlus imp;
    private final ThresholdModel model;
    private final ThresholdListener listener;
    private int[] histogram;
    private int histMax = 1;
    private int histSlice = -2; // -2 = "not computed yet"
    // Actual data range, computed from raw pixels (B&C-independent)
    private int histRangeMin = 0;
    private int histRangeMax = 65535;
    private int dragMode = 0; // 0 none, 1 bg, 2 fg
    private boolean fgEnabled = true;
    private boolean logScale = true;
    private static final int PAD = 8;
    private static final int HANDLE_TOL = 4;
    private Image histogramImage;
    private int cachedImageWidth = -1;
    private int cachedImageHeight = -1;

    public interface ThresholdListener {
        void onThresholdsChanged(int tBg, int tFg);
    }

    public HistogramPanel(ImagePlus imp, ThresholdModel model, ThresholdListener listener) {
        this.imp = imp;
        this.model = model;
        this.listener = listener;
        setPreferredSize(new Dimension(420, 140));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int x = e.getX();
                int bgX = valueToX(model.getTBg());
                int fgX = valueToX(model.getTFg());
                if (Math.abs(x - bgX) <= HANDLE_TOL) dragMode = 1;
                else if (fgEnabled && Math.abs(x - fgX) <= HANDLE_TOL) dragMode = 2;
                else {
                    dragMode = 0;
                    int value = xToValue(x);
                    int curBg = model.getTBg();
                    int curFg = model.getTFg();
                    if (Math.abs(value - curBg) <= Math.abs(value - curFg)) {
                        listener.onThresholdsChanged(value, curFg);
                    } else if (fgEnabled) {
                        listener.onThresholdsChanged(curBg, value);
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragMode = 0;
            }
        });
        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragMode == 0) return;
                int value = xToValue(e.getX());
                if (dragMode == 1) listener.onThresholdsChanged(value, model.getTFg());
                else listener.onThresholdsChanged(model.getTBg(), value);
            }
        });
    }

    public void setImage(ImagePlus imp) {
        this.imp = imp;
        this.histogram = null; // force recompute for new image
        this.histSlice = -2;
        invalidateHistogramImage();
        repaint();
    }

    public void setFgEnabled(boolean enabled) {
        this.fgEnabled = enabled;
        invalidateHistogramImage();
        repaint();
    }

    public void setLogScale(boolean log) {
        this.logScale = log;
        invalidateHistogramImage();
        repaint();
    }

    public void repaintThresholdMarkers(int oldBg, int oldFg) {
        ensureHistogram();
        int plotH = getHeight() - 2 * PAD;
        if (plotH <= 0) {
            repaint();
            return;
        }
        int left = Math.min(valueToX(oldBg), valueToX(model.getTBg()));
        int right = Math.max(valueToX(oldBg), valueToX(model.getTBg()));
        left = Math.min(left, Math.min(valueToX(oldFg), valueToX(model.getTFg())));
        right = Math.max(right, Math.max(valueToX(oldFg), valueToX(model.getTFg())));
        int dirtyX = Math.max(0, left - HANDLE_TOL - 2);
        int dirtyW = Math.max(1, right - left + (HANDLE_TOL + 2) * 2);
        repaint(dirtyX, Math.max(0, PAD - 1), dirtyW, plotH + 3);
    }

    @Override
    public void paint(Graphics g) {
        if (imp == null) return;
        ensureHistogram();
        int w = getWidth();
        int h = getHeight();
        ensureHistogramImage(w, h);
        g.drawImage(histogramImage, 0, 0, this);

        int plotH = h - 2 * PAD;
        int bgX = valueToX(model.getTBg());
        int fgX = valueToX(model.getTFg());
        g.setColor(Color.BLACK);
        g.drawLine(bgX, PAD, bgX, PAD + plotH);
        if (fgEnabled) {
            g.drawLine(fgX, PAD, fgX, PAD + plotH);
        } else {
            g.setColor(Color.LIGHT_GRAY);
            g.drawLine(fgX, PAD, fgX, PAD + plotH);
            g.drawString("T_fg unused", fgX + 4, PAD + 12);
        }
    }

    @Override
    public void update(Graphics g) {
        paint(g);
    }

    private void ensureHistogram() {
        if (imp == null) return;
        boolean is3D = imp.getNSlices() > 1;
        // For 3D: use sentinel -1 (recompute only when histogram is null or image changed).
        // For 2D: use current slice number (recompute on slice change).
        int slice = is3D ? -1 : imp.getCurrentSlice();
        if (histogram != null && histSlice == slice) return;
        buildHistogram(imp, is3D);
        histSlice = slice;
        invalidateHistogramImage();
    }

    /**
     * Build histogram from raw pixel values in a single pass.
     * The data range (histRangeMin/Max) is derived from actual pixels,
     * completely independent of Brightness & Contrast display settings.
     */
    private void buildHistogram(ImagePlus imp, boolean allSlices) {
        int iw = imp.getWidth();
        int ih = imp.getHeight();
        int bitDepth = imp.getBitDepth();
        // Use full-resolution bins matching the bit depth for a single-pass approach.
        int fullBins = (bitDepth >= 16) ? 65536 : 256;
        int[] fullHist = new int[fullBins];

        if (allSlices) {
            int nSlices = imp.getNSlices();
            for (int z = 1; z <= nSlices; z++) {
                imp.setSliceWithoutUpdate(z);
                ImageProcessor ip = imp.getProcessor();
                for (int y = 0; y < ih; y++) {
                    for (int x = 0; x < iw; x++) {
                        int v = (int) ip.getPixelValue(x, y);
                        if (v >= 0 && v < fullBins) fullHist[v]++;
                        else if (v >= fullBins) fullHist[fullBins - 1]++;
                    }
                }
            }
        } else {
            ImageProcessor ip = imp.getProcessor();
            for (int y = 0; y < ih; y++) {
                for (int x = 0; x < iw; x++) {
                    int v = (int) ip.getPixelValue(x, y);
                    if (v >= 0 && v < fullBins) fullHist[v]++;
                    else if (v >= fullBins) fullHist[fullBins - 1]++;
                }
            }
        }

        // Find actual data range from the histogram (avoids empty leading/trailing bins)
        int dataMin = 0, dataMax = fullBins - 1;
        for (int i = 0; i < fullBins; i++) { if (fullHist[i] > 0) { dataMin = i; break; } }
        for (int i = fullBins - 1; i >= 0; i--) { if (fullHist[i] > 0) { dataMax = i; break; } }
        histRangeMin = dataMin;
        histRangeMax = (dataMax > dataMin) ? dataMax : dataMin + 1;

        // Downsample to 256 display bins over the actual data range
        int bins = 256;
        int[] hist = new int[bins];
        int range = histRangeMax - histRangeMin;
        for (int i = histRangeMin; i <= histRangeMax && i < fullBins; i++) {
            if (fullHist[i] == 0) continue;
            int bin = (range > 0) ? (int) Math.round((double)(i - histRangeMin) / range * (bins - 1)) : 0;
            if (bin < 0) bin = 0;
            if (bin >= bins) bin = bins - 1;
            hist[bin] += fullHist[i];
        }

        histogram = hist;
        histMax = 1;
        for (int v : histogram) if (v > histMax) histMax = v;
    }

    private void ensureHistogramImage(int w, int h) {
        if (histogramImage != null && cachedImageWidth == w && cachedImageHeight == h) return;

        histogramImage = createImage(w, h);
        cachedImageWidth = w;
        cachedImageHeight = h;
        Graphics ig = histogramImage.getGraphics();
        try {
            ig.setColor(Color.WHITE);
            ig.fillRect(0, 0, w, h);
            ig.setColor(Color.BLACK);
            ig.drawRect(PAD, PAD, w - 2 * PAD, h - 2 * PAD);

            int plotW = w - 2 * PAD;
            int plotH = h - 2 * PAD;
            int bins = histogram.length;
            ig.setColor(Color.GRAY);
            double scaleMax = logScale ? Math.log1p(histMax) : histMax;
            for (int i = 0; i < bins; i++) {
                int x = PAD + (int) Math.round(i * (plotW - 1) / (double) (bins - 1));
                double val = logScale ? Math.log1p(histogram[i]) : histogram[i];
                int barH = scaleMax > 0 ? (int) Math.round(val * (plotH - 2) / scaleMax) : 0;
                ig.drawLine(x, PAD + plotH - 1, x, PAD + plotH - 1 - barH);
            }
        } finally {
            ig.dispose();
        }
    }

    private void invalidateHistogramImage() {
        histogramImage = null;
        cachedImageWidth = -1;
        cachedImageHeight = -1;
    }

    private int valueToX(int value) {
        int w = getWidth() - 2 * PAD;
        int min = histRangeMin;
        int max = histRangeMax;
        if (max <= min) return PAD;
        double t = (value - min) / (double) (max - min);
        t = Math.max(0.0, Math.min(1.0, t));
        return PAD + (int) Math.round(t * (w - 1));
    }

    private int xToValue(int x) {
        int w = getWidth() - 2 * PAD;
        int min = histRangeMin;
        int max = histRangeMax;
        int clamped = Math.max(PAD, Math.min(PAD + w - 1, x));
        double t = (clamped - PAD) / (double) (w - 1);
        if (max <= min) return min;
        return (int) Math.round(min + t * (max - min));
    }
}
