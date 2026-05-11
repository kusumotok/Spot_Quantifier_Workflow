package io.github.kusumotok.spotworkflow.core.roi;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.plugin.filter.ThresholdToSelection;
import ij.plugin.frame.RoiManager;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class RoiExporter3D {

    /**
     * Named ROI colors available in the UI.
     * Each entry is { display name, hex color code }.
     */
    public static final String[][] ROI_COLOR_OPTIONS = {
        { "Yellow",  "#FFFF00" },
        { "Cyan",    "#00FFFF" },
        { "Magenta", "#FF00FF" },
        { "Red",     "#FF0000" },
        { "Green",   "#00FF00" },
        { "White",   "#FFFFFF" },
    };

    /** Default ROI color (yellow). */
    public static final Color DEFAULT_ROI_COLOR = Color.decode(ROI_COLOR_OPTIONS[0][1]);

    /**
     * Decode a hex color string (e.g. "#FFFF00") to a Color.
     */
    public static Color decodeColor(String hex) {
        return Color.decode(hex);
    }

    /**
     * Export 3D segmentation as 2D ROI slices with Position and Group attributes.
     * Uses the default ROI color (yellow).
     */
    public void exportToRoiManager(ImagePlus labelImage) {
        exportToRoiManager(labelImage, DEFAULT_ROI_COLOR);
    }

    /**
     * Export 3D segmentation as 2D ROI slices with Position and Group attributes.
     *
     * @param roiColor stroke color applied to every exported ROI
     */
    public void exportToRoiManager(ImagePlus labelImage, Color roiColor) {
        exportToRoiManager(labelImage, roiColor, null, 1);
    }

    public void exportToRoiManager(ImagePlus labelImage, Color roiColor,
                                    ImagePlus sourceImage, int sourceChannel) {
        exportToRoiManager(RoiManager.getRoiManager(), labelImage, roiColor, sourceImage, sourceChannel);
    }

    public void exportToRoiManager(RoiManager rm, ImagePlus labelImage, Color roiColor,
                                    ImagePlus sourceImage, int sourceChannel) {
        if (labelImage == null) {
            IJ.error("Add ROI failed", "Label image is missing.");
            return;
        }
        List<Roi> rois = exportToRoiList(labelImage, roiColor, sourceImage, sourceChannel);
        if (rois.isEmpty()) {
            IJ.log("RoiExporter3D: no objects found in label image (skipping ROI export).");
            return;
        }
        for (Roi roi : rois) {
            rm.addRoi(roi);
        }
    }

    public List<Roi> exportToRoiList(ImagePlus labelImage, Color roiColor,
                                     ImagePlus sourceImage, int sourceChannel) {
        Map<Integer, List<Roi>> grouped = exportToRoiListsByLabel(labelImage, roiColor, sourceImage, sourceChannel);
        List<Roi> rois = new ArrayList<Roi>();
        for (List<Roi> each : grouped.values()) {
            rois.addAll(each);
        }
        return rois;
    }

    public Map<Integer, List<Roi>> exportToRoiListsByLabel(ImagePlus labelImage, Color roiColor,
                                                            ImagePlus sourceImage, int sourceChannel) {
        if (labelImage == null) {
            IJ.error("Add ROI failed", "Label image is missing.");
            return java.util.Collections.emptyMap();
        }
        ImageStack stack = labelImage.getStack();
        int w = labelImage.getWidth();
        int h = labelImage.getHeight();
        int d = labelImage.getNSlices();
        int nChannels = Math.max(1, sourceImage != null ? sourceImage.getNChannels() : labelImage.getNChannels());
        int channel   = Math.max(1, sourceImage != null
            ? Math.min(nChannels, sourceChannel)
            : labelImage.getC());

        Map<Integer, Map<Integer, Rectangle>> bboxByLabelByZ =
            new TreeMap<Integer, Map<Integer, Rectangle>>();
        for (int z = 1; z <= d; z++) {
            ImageProcessor ip = stack.getProcessor(z);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int v = (int) Math.round(ip.getPixelValue(x, y));
                    if (v <= 0) continue;
                    Map<Integer, Rectangle> byZ = bboxByLabelByZ.get(v);
                    if (byZ == null) {
                        byZ = new HashMap<Integer, Rectangle>();
                        bboxByLabelByZ.put(v, byZ);
                    }
                    Rectangle r = byZ.get(z);
                    if (r == null) {
                        byZ.put(z, new Rectangle(x, y, 1, 1));
                    } else {
                        r.add(x, y);
                    }
                }
            }
        }

        if (bboxByLabelByZ.isEmpty()) {
            return java.util.Collections.emptyMap();
        }

        Map<Integer, List<Roi>> roisByLabel = new LinkedHashMap<Integer, List<Roi>>();
        for (Map.Entry<Integer, Map<Integer, Rectangle>> labelEntry : bboxByLabelByZ.entrySet()) {
            int label = labelEntry.getKey();
            List<Roi> rois = new ArrayList<Roi>();
            for (Map.Entry<Integer, Rectangle> zEntry : new TreeMap<Integer, Rectangle>(labelEntry.getValue()).entrySet()) {
                int z = zEntry.getKey();
                Rectangle bbox = zEntry.getValue();
                int x0 = Math.max(0, bbox.x - 1);
                int y0 = Math.max(0, bbox.y - 1);
                int x1 = Math.min(w - 1, bbox.x + bbox.width);
                int y1 = Math.min(h - 1, bbox.y + bbox.height);
                int bw = x1 - x0 + 1;
                int bh = y1 - y0 + 1;

                ImageProcessor ip = stack.getProcessor(z);
                ByteProcessor bp = new ByteProcessor(bw, bh);
                byte[] pixels = (byte[]) bp.getPixels();
                boolean hasPixel = false;
                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) {
                        int v = (int) Math.round(ip.getPixelValue(x, y));
                        if (v == label) {
                            pixels[(y - y0) * bw + (x - x0)] = (byte) 255;
                            hasPixel = true;
                        }
                    }
                }
                if (!hasPixel) continue;

                bp.setThreshold(255, 255, ImageProcessor.NO_LUT_UPDATE);
                ImagePlus mask = new ImagePlus("mask", bp);
                Roi roi = ThresholdToSelection.run(mask);
                if (roi == null) continue;
                roi.setLocation(roi.getXBase() + x0, roi.getYBase() + y0);

                if (nChannels > 1) roi.setPosition(channel, z, 1);
                else               roi.setPosition(z);
                roi.setStrokeColor(roiColor);
                roi.setName(String.format("obj-%03d-z%03d", label, z));
                rois.add(roi);
            }
            if (!rois.isEmpty()) roisByLabel.put(label, rois);
        }
        return roisByLabel;
    }
}
