package io.github.kusumotok.spotworkflow.core.util;

import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Roi;
import ij.plugin.Duplicator;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import io.github.kusumotok.spotworkflow.core.alg.SegmentationResult3D;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;

public final class SeededSpotQuantifier3DImageSupport {
    public static final String NONE_ITEM = "None";

    private SeededSpotQuantifier3DImageSupport() {}

    public static ImagePlus extractProcessingImage(ImagePlus image, int channel) {
        int nCh = Math.max(1, image.getNChannels());
        if (nCh <= 1) return image;
        int ch = Math.max(1, Math.min(nCh, channel));
        return new Duplicator().run(image, ch, ch, 1, image.getNSlices(), 1, image.getNFrames());
    }

    public static void disposeProcessingImage(ImagePlus image, boolean owned) {
        if (image == null || !owned) return;
        image.flush();
    }

    public static int[] computeStackMinMax(ImagePlus image) {
        if (image == null || image.getStackSize() <= 0) return new int[]{0, 1};
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 1; i <= image.getStackSize(); i++) {
            ImageProcessor ip = image.getStack().getProcessor(i);
            int w = ip.getWidth();
            int h = ip.getHeight();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int v = (int) Math.round(ip.getPixelValue(x, y));
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
            }
        }
        if (min == Integer.MAX_VALUE || max == Integer.MIN_VALUE) return new int[]{0, 1};
        return new int[]{min, max};
    }

    public static List<ImagePlus> listOpen3DImages() {
        List<ImagePlus> out = new ArrayList<>();
        int[] ids = WindowManager.getIDList();
        if (ids == null) return out;
        for (int id : ids) {
            ImagePlus img = WindowManager.getImage(id);
            if (img != null && img.getNSlices() >= 2) out.add(img);
        }
        return out;
    }

    public static List<ImagePlus> listOpen2DImages() {
        List<ImagePlus> out = new ArrayList<>();
        int[] ids = WindowManager.getIDList();
        if (ids == null) return out;
        for (int id : ids) {
            ImagePlus img = WindowManager.getImage(id);
            if (img != null && img.getNSlices() < 2) out.add(img);
        }
        return out;
    }

    public static ImagePlus findImageByTitle(String title) {
        if (title == null || NONE_ITEM.equals(title)) return null;
        return WindowManager.getImage(title);
    }

    public static String autoMatchZProjTitle(ImagePlus rawImp, List<ImagePlus> zProjCandidates) {
        if (rawImp == null) return NONE_ITEM;
        String rawTitle = rawImp.getTitle();
        String match = null;
        for (ImagePlus candidate : zProjCandidates) {
            if (candidate == null) continue;
            if (!candidate.getTitle().contains(rawTitle)) continue;
            if (match != null) return NONE_ITEM;
            match = candidate.getTitle();
        }
        return match != null ? match : NONE_ITEM;
    }

    public static int countLabels(SegmentationResult3D seg) {
        if (seg == null || seg.labelImage == null) return 0;
        TreeSet<Integer> labels = new TreeSet<>();
        int d = seg.labelImage.getNSlices();
        for (int z = 1; z <= d; z++) {
            ImageProcessor ip = seg.labelImage.getStack().getProcessor(z);
            int w = ip.getWidth();
            int h = ip.getHeight();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int label = (int) Math.round(ip.getPixelValue(x, y));
                    if (label > 0) labels.add(label);
                }
            }
        }
        return labels.size();
    }

    public static List<Roi> buildLabelUnionRois(ImagePlus labelImp) {
        return buildLabelUnionRois(labelImp, null, null);
    }

    public static int[] buildProjectedPreviewTypeMap(ImagePlus finalLabelImp, ImagePlus seedLabelImp,
                                                     boolean areaEnabled, Consumer<String> progress) {
        return buildProjectedPreviewTypeMap(finalLabelImp, seedLabelImp, areaEnabled, true, progress);
    }

    public static int[] buildProjectedPreviewTypeMap(ImagePlus finalLabelImp, ImagePlus seedLabelImp,
                                                     boolean areaEnabled, boolean seedPriority,
                                                     Consumer<String> progress) {
        if (finalLabelImp == null) return null;
        int w = finalLabelImp.getWidth();
        int h = finalLabelImp.getHeight();
        int d = finalLabelImp.getNSlices();
        int[] typeMap = new int[w * h];
        boolean hasSeed = areaEnabled && seedLabelImp != null;

        reportProgress(progress, "projecting 3D labels to 2D");
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int type = 0;
                for (int z = 1; z <= d; z++) {
                    boolean isSeed = hasSeed && z <= seedLabelImp.getNSlices()
                        && (int) Math.round(seedLabelImp.getStack().getProcessor(z).getPixelValue(x, y)) > 0;
                    boolean isFinal = (int) Math.round(finalLabelImp.getStack().getProcessor(z).getPixelValue(x, y)) > 0;
                    if (seedPriority) {
                        if (isSeed) {
                            type = 1;
                            break;
                        }
                        if (isFinal) type = 2;
                    } else {
                        if (isFinal) {
                            type = 2;
                            break;
                        }
                        if (isSeed) type = 1;
                    }
                }
                typeMap[y * w + x] = type;
            }
        }
        return typeMap;
    }

    public static Roi buildProjectedTypeRoi(int[] typeMap, int w, int h, int type,
                                            String labelKind, Consumer<String> progress) {
        if (typeMap == null || typeMap.length != w * h) return null;
        reportProgress(progress, phasePrefix(labelKind) + "building 2D ROI outline");
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if (typeMap[row + x] != type) continue;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < minX || maxY < minY) return null;

        int x0 = Math.max(0, minX - 1);
        int y0 = Math.max(0, minY - 1);
        int x1 = Math.min(w - 1, maxX + 1);
        int y1 = Math.min(h - 1, maxY + 1);
        int bw = x1 - x0 + 1;
        int bh = y1 - y0 + 1;
        ByteProcessor bp = new ByteProcessor(bw, bh);
        byte[] pixels = (byte[]) bp.getPixels();
        for (int y = y0; y <= y1; y++) {
            int srcRow = y * w;
            int dstRow = (y - y0) * bw;
            for (int x = x0; x <= x1; x++) {
                if (typeMap[srcRow + x] == type) {
                    pixels[dstRow + (x - x0)] = (byte) 255;
                }
            }
        }
        bp.setThreshold(255, 255, ImageProcessor.NO_LUT_UPDATE);
        Roi roi = ThresholdToSelection.run(new ImagePlus("", bp));
        if (roi == null) return null;
        roi.setLocation(roi.getXBase() + x0, roi.getYBase() + y0);
        return roi;
    }

    public static List<Roi> buildLabelUnionRois(ImagePlus labelImp, String labelKind, Consumer<String> progress) {
        int w = labelImp.getWidth();
        int h = labelImp.getHeight();
        int d = labelImp.getNSlices();

        reportProgress(progress, phasePrefix(labelKind) + "projecting 3D labels to 2D");
        Map<Integer, byte[]> projectionByLabel = new HashMap<Integer, byte[]>();
        Map<Integer, int[]> bboxByLabel = new HashMap<Integer, int[]>();
        for (int z = 1; z <= d; z++) {
            ImageProcessor ip = labelImp.getStack().getProcessor(z);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int v = (int) Math.round(ip.getPixelValue(x, y));
                    if (v <= 0) continue;
                    byte[] proj = projectionByLabel.get(v);
                    if (proj == null) {
                        proj = new byte[w * h];
                        projectionByLabel.put(v, proj);
                        bboxByLabel.put(v, new int[]{x, y, x, y});
                    } else {
                        int[] bb = bboxByLabel.get(v);
                        if (x < bb[0]) bb[0] = x;
                        if (y < bb[1]) bb[1] = y;
                        if (x > bb[2]) bb[2] = x;
                        if (y > bb[3]) bb[3] = y;
                    }
                    proj[y * w + x] = (byte) 255;
                }
            }
        }

        List<Roi> rois = new ArrayList<>();
        TreeSet<Integer> labels = new TreeSet<Integer>(projectionByLabel.keySet());
        reportProgress(progress, phasePrefix(labelKind) + "building 2D ROI outlines");
        for (int label : labels) {
            byte[] proj = projectionByLabel.get(label);
            int[] bb = bboxByLabel.get(label);
            int x0 = bb[0];
            int y0 = bb[1];
            int bw = bb[2] - bb[0] + 1;
            int bh = bb[3] - bb[1] + 1;
            ByteProcessor bp = new ByteProcessor(bw, bh);
            byte[] bpix = (byte[]) bp.getPixels();
            for (int y = 0; y < bh; y++) {
                int srcRow = (y0 + y) * w;
                int dstRow = y * bw;
                for (int x = 0; x < bw; x++) {
                    bpix[dstRow + x] = proj[srcRow + x0 + x];
                }
            }
            bp.setThreshold(255, 255, ImageProcessor.NO_LUT_UPDATE);
            Roi roi = ThresholdToSelection.run(new ImagePlus("", bp));
            if (roi != null) {
                roi.setLocation(roi.getXBase() + x0, roi.getYBase() + y0);
                rois.add(roi);
            }
        }
        return rois;
    }

    private static void reportProgress(Consumer<String> progress, String message) {
        if (progress != null) progress.accept(message);
    }

    private static String phasePrefix(String labelKind) {
        return (labelKind == null || labelKind.isEmpty()) ? "" : labelKind + ": ";
    }
}
