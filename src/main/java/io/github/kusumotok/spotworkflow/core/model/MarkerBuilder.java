package io.github.kusumotok.spotworkflow.core.model;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.filter.MaximumFinder;
import ij.plugin.filter.GaussianBlur;
import ij.plugin.frame.RoiManager;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import io.github.kusumotok.spotworkflow.core.util.IJLog;

import java.awt.Polygon;
import java.awt.Rectangle;
import java.util.List;

public class MarkerBuilder {
    public MarkerResult build(ImagePlus imp, ThresholdModel model) {
        int w = imp.getWidth();
        int h = imp.getHeight();
        int size = w * h;
        int tFg = model.getTFg();
        int tBg = model.getTBg();
        Connectivity conn = model.getConnectivity();
        int minArea = model.getSeedMinArea();
        int maxArea = model.getSeedMaxArea();

        boolean[] fgMask = new boolean[size];
        boolean[] bgMask = new boolean[size];
        boolean[] unknownMask = new boolean[size];

        ImageProcessor ip = imp.getProcessor();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                double v = ip.getPixelValue(x, y);
                fgMask[idx] = v >= tFg;
                bgMask[idx] = v < tBg;  // Changed from v <= tBg to v < tBg for correct domain definition
                unknownMask[idx] = (v >= tBg) && (v < tFg);  // Adjusted to match bgMask change
            }
        }

        boolean[] bgSeedMask = new boolean[size];
        boolean[] unknownWork = new boolean[size];
        System.arraycopy(bgMask, 0, bgSeedMask, 0, size);
        System.arraycopy(unknownMask, 0, unknownWork, 0, size);
        if (model.isAbsorbUnknown()) {
            absorbUnknownIslands(fgMask, bgSeedMask, unknownWork, w, h, conn);
        }

        boolean[] domain = new boolean[size];
        for (int i = 0; i < size; i++) {
            domain[i] = !bgMask[i];  // domain = pixels where v >= tBg (since bgMask is v < tBg)
        }

        SeedResult seeds = buildSeedsFromSource(imp, model, fgMask, domain, conn, minArea, maxArea);
        boolean[] seedMask = new boolean[size];
        for (int i = 0; i < size; i++) seedMask[i] = seeds.labels[i] > 0;

        return new MarkerResult(w, h, seeds.labels, fgMask, bgMask, unknownMask, unknownWork, bgSeedMask, seedMask, domain, seeds.count);
    }

    private SeedResult buildSeedsFromSource(ImagePlus imp, ThresholdModel model, boolean[] fgMask,
                                           boolean[] domain, Connectivity conn, int minArea, int maxArea) {
        int w = imp.getWidth();
        int h = imp.getHeight();
        int size = w * h;
        int[] labels = new int[size];
        int count = 0;

        MarkerSource source = model.getMarkerSource();
        if (source == MarkerSource.THRESHOLD_COMPONENTS) {
            boolean[] mask = new boolean[size];
            for (int i = 0; i < size; i++) mask[i] = fgMask[i] && domain[i];
            count = labelFromMask(mask, labels, w, h, conn, minArea, maxArea);
        } else if (source == MarkerSource.ROI_MANAGER) {
            SeedResult r = seedsFromRoiManager(model, domain, w, h, minArea, maxArea);
            labels = r.labels;
            count = r.count;
        } else if (source == MarkerSource.BINARY_IMAGE) {
            SeedResult r = seedsFromBinaryImage(imp, model, domain, conn, minArea, maxArea);
            labels = r.labels;
            count = r.count;
        } else if (source == MarkerSource.FIND_MAXIMA) {
            SeedResult r = seedsFromFindMaxima(imp, model, domain);
            labels = r.labels;
            count = r.count;
        } else if (source == MarkerSource.MANUAL_SELECTION) {
            SeedResult r = seedsFromManual(model, domain, w, h);
            labels = r.labels;
            count = r.count;
        }

        if (source != MarkerSource.MANUAL_SELECTION) {
            SeedResult manual = seedsFromManual(model, domain, w, h);
            if (manual.count > 0) {
                int offset = count;
                for (int i = 0; i < size; i++) {
                    if (manual.labels[i] > 0) labels[i] = manual.labels[i] + offset;
                }
                count += manual.count;
            }
        }

        return new SeedResult(labels, count);
    }

    private SeedResult seedsFromRoiManager(ThresholdModel model, boolean[] domain, int w, int h,
                                           int minArea, int maxArea) {
        RoiManager rm = RoiManager.getInstance();
        if (rm == null || rm.getCount() == 0) {
            IJ.error("ROI seeds", "RoiManager is empty.");
            return new SeedResult(new int[w * h], 0);
        }
        int[] labels = new int[w * h];
        Roi[] rois = rm.getRoisAsArray();
        int label = 0;
        int ignoredOutside = 0;
        for (int i = 0; i < rois.length; i++) {
            Roi roi = rois[i];
            if (roi == null) continue;
            ByteProcessor mask = (ByteProcessor) roi.getMask();
            int area = 0;
            if (mask == null) {
                area = fillRoi(roi, labels, domain, w, h, label + 1, model.getOverlapRule());
            } else {
                Rectangle bounds = roi.getBounds();
                for (int y = 0; y < bounds.height; y++) {
                    for (int x = 0; x < bounds.width; x++) {
                        int ix = bounds.x + x;
                        int iy = bounds.y + y;
                        if (ix < 0 || ix >= w || iy < 0 || iy >= h) continue;
                        int idx = iy * w + ix;
                        int mv = mask.getPixel(x, y);
                        if (mv == 0) continue;
                        if (!domain[idx]) { ignoredOutside++; continue; }
                        area++;
                        if (model.getOverlapRule() == OverlapRule.FIRST_WINS && labels[idx] != 0) continue;
                        labels[idx] = label + 1;
                    }
                }
            }
            if (!areaInRange(area, minArea, maxArea)) {
                clearLabel(labels, label + 1);
                continue;
            }
            label++;
        }
        if (ignoredOutside > 0) {
            IJLog.warn("DOMAIN外のseedを無視しました: " + ignoredOutside + "個");
        }
        return new SeedResult(labels, label);
    }

    private int fillRoi(Roi roi, int[] labels, boolean[] domain, int w, int h, int label, OverlapRule rule) {
        Rectangle r = roi.getBounds();
        int area = 0;
        for (int y = r.y; y < r.y + r.height; y++) {
            if (y < 0 || y >= h) continue;
            for (int x = r.x; x < r.x + r.width; x++) {
                if (x < 0 || x >= w) continue;
                int idx = y * w + x;
                if (!domain[idx]) continue;
                if (!roi.contains(x, y)) continue;
                if (rule == OverlapRule.FIRST_WINS && labels[idx] != 0) continue;
                labels[idx] = label;
                area++;
            }
        }
        return area;
    }

    private SeedResult seedsFromBinaryImage(ImagePlus imp, ThresholdModel model, boolean[] domain, Connectivity conn,
                                            int minArea, int maxArea) {
        ImagePlus bin = resolveBinaryImage(imp, model.getBinarySourceId());
        if (bin == null) {
            IJ.error("Binary seeds", "Binary image not found.");
            return new SeedResult(new int[imp.getWidth() * imp.getHeight()], 0);
        }
        if (bin.getWidth() != imp.getWidth() || bin.getHeight() != imp.getHeight()) {
            IJ.error("Binary seeds", "Binary image size must match the target image.");
            return new SeedResult(new int[imp.getWidth() * imp.getHeight()], 0);
        }
        int w = imp.getWidth();
        int h = imp.getHeight();
        int size = w * h;
        boolean[] mask = new boolean[size];
        ImageProcessor ip = bin.getProcessor();
        int ignoredOutside = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                boolean on = ip.getPixelValue(x, y) != 0;
                if (!domain[idx]) {
                    if (on) ignoredOutside++;
                    continue;
                }
                mask[idx] = on;
            }
        }
        int[] labels = new int[size];
        int count = labelFromMask(mask, labels, w, h, conn, minArea, maxArea);
        if (ignoredOutside > 0) {
            IJLog.warn("DOMAIN外のseedを無視しました: " + ignoredOutside + "個");
        }
        return new SeedResult(labels, count);
    }

    private ImagePlus resolveBinaryImage(ImagePlus imp, int id) {
        if (id <= 0) return null;
        if (imp.getID() == id) return imp;
        return ij.WindowManager.getImage(id);
    }

    private SeedResult seedsFromFindMaxima(ImagePlus imp, ThresholdModel model, boolean[] domain) {
        int w = imp.getWidth();
        int h = imp.getHeight();
        FloatProcessor fp = imp.getProcessor().convertToFloatProcessor();
        if (model.isPreprocessingEnabled() && model.getSigmaSeed() > 0.0) {
            GaussianBlur gb = new GaussianBlur();
            gb.blurGaussian(fp, model.getSigmaSeed(), model.getSigmaSeed(), 0.01);
        }
        MaximumFinder mf = new MaximumFinder();
        Polygon poly = mf.getMaxima(fp, model.getFindMaximaTolerance(), false);
        int[] labels = new int[w * h];
        if (poly == null) return new SeedResult(labels, 0);
        int label = 0;
        int ignoredOutside = 0;
        for (int i = 0; i < poly.npoints; i++) {
            int x = poly.xpoints[i];
            int y = poly.ypoints[i];
            if (x < 0 || x >= w || y < 0 || y >= h) continue;
            int idx = y * w + x;
            if (!domain[idx]) { ignoredOutside++; continue; }
            label++;
            labels[idx] = label;
        }
        if (ignoredOutside > 0) {
            IJLog.warn("DOMAIN外のseedを無視しました: " + ignoredOutside + "個");
        }
        return new SeedResult(labels, label);
    }

    private SeedResult seedsFromManual(ThresholdModel model, boolean[] domain, int w, int h) {
        List<Roi> rois = model.getManualSeeds();
        int[] labels = new int[w * h];
        int label = 0;
        int ignoredOutside = 0;
        for (Roi roi : rois) {
            if (roi == null) continue;
            label++;
            Rectangle r = roi.getBounds();
            for (int y = r.y; y < r.y + r.height; y++) {
                if (y < 0 || y >= h) continue;
                for (int x = r.x; x < r.x + r.width; x++) {
                    if (x < 0 || x >= w) continue;
                    int idx = y * w + x;
                    if (!roi.contains(x, y)) continue;
                    if (!domain[idx]) { ignoredOutside++; continue; }
                    labels[idx] = label;
                }
            }
        }
        if (ignoredOutside > 0) {
            IJLog.warn("DOMAIN外のseedを無視しました: " + ignoredOutside + "個");
        }
        return new SeedResult(labels, label);
    }

    private int labelFromMask(boolean[] fgMask, int[] labels, int w, int h, Connectivity conn,
                              int minArea, int maxArea) {
        int size = w * h;
        int[] queue = new int[size];
        int label = 0;
        int[] dx = (conn == Connectivity.C8)
            ? new int[] {-1, 0, 1, -1, 1, -1, 0, 1}
            : new int[] {-1, 0, 1, 0};
        int[] dy = (conn == Connectivity.C8)
            ? new int[] {-1, -1, -1, 0, 0, 1, 1, 1}
            : new int[] {0, -1, 0, 1};

        for (int i = 0; i < size; i++) {
            if (!fgMask[i] || labels[i] != 0) continue;
            int head = 0;
            int tail = 0;
            queue[tail++] = i;
            int compSize = 0;
            labels[i] = -1;
            while (head < tail) {
                int p = queue[head++];
                queue[compSize++] = p;
                int x = p % w;
                int y = p / w;
                for (int k = 0; k < dx.length; k++) {
                    int nx = x + dx[k];
                    int ny = y + dy[k];
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                    int n = ny * w + nx;
                    if (fgMask[n] && labels[n] == 0) {
                        labels[n] = -1;
                        queue[tail++] = n;
                    }
                }
            }
            if (!areaInRange(compSize, minArea, maxArea)) {
                for (int k = 0; k < compSize; k++) labels[queue[k]] = 0;
                continue;
            }
            label++;
            for (int k = 0; k < compSize; k++) labels[queue[k]] = label;
        }
        return label;
    }

    private boolean areaInRange(int area, int minArea, int maxArea) {
        if (minArea > 0 && area < minArea) return false;
        if (maxArea > 0 && area > maxArea) return false;
        return true;
    }

    private void clearLabel(int[] labels, int target) {
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == target) labels[i] = 0;
        }
    }

    private static class SeedResult {
        final int[] labels;
        final int count;
        SeedResult(int[] labels, int count) {
            this.labels = labels;
            this.count = count;
        }
    }

    private void absorbUnknownIslands(boolean[] fgMask, boolean[] bgMask, boolean[] unknownMask,
                                      int w, int h, Connectivity conn) {
        boolean absorbUnknown = true;
        if (!absorbUnknown) return;

        int size = w * h;
        boolean[] visited = new boolean[size];
        int[] queue = new int[size];
        int[] dx = (conn == Connectivity.C8)
            ? new int[] {-1, 0, 1, -1, 1, -1, 0, 1}
            : new int[] {-1, 0, 1, 0};
        int[] dy = (conn == Connectivity.C8)
            ? new int[] {-1, -1, -1, 0, 0, 1, 1, 1}
            : new int[] {0, -1, 0, 1};

        for (int i = 0; i < size; i++) {
            if (!unknownMask[i] || visited[i]) continue;
            int head = 0;
            int tail = 0;
            queue[tail++] = i;
            visited[i] = true;
            boolean touchesFg = false;
            while (head < tail) {
                int p = queue[head++];
                int x = p % w;
                int y = p / w;
                for (int k = 0; k < dx.length; k++) {
                    int nx = x + dx[k];
                    int ny = y + dy[k];
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                    int n = ny * w + nx;
                    if (fgMask[n]) touchesFg = true;
                    if (unknownMask[n] && !visited[n]) {
                        visited[n] = true;
                        queue[tail++] = n;
                    }
                }
            }

            if (!touchesFg) {
                for (int k = 0; k < tail; k++) {
                    int p = queue[k];
                    bgMask[p] = true;
                    unknownMask[p] = false;
                }
            }
        }
    }
}
