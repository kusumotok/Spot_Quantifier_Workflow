package io.github.kusumotok.spotworkflow.core.roi;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.io.RoiEncoder;
import ij.plugin.filter.ThresholdToSelection;
import ij.plugin.frame.RoiManager;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class RoiExporter {
    public void exportToRoiManager(ImagePlus labelImage) {
        if (labelImage == null) {
            IJ.error("Add ROI failed", "Label image is missing.");
            return;
        }
        ImageProcessor ip = labelImage.getProcessor();
        int w = labelImage.getWidth();
        int h = labelImage.getHeight();
        int size = w * h;
        int maxLabel = 0;
        for (int i = 0; i < size; i++) {
            int v = ip.get(i);
            if (v > maxLabel) maxLabel = v;
        }
        if (maxLabel <= 0) {
            IJ.error("Add ROI failed", "No objects found in label image.");
            return;
        }

        RoiManager rm = RoiManager.getRoiManager();
        for (int label = 1; label <= maxLabel; label++) {
            ByteProcessor bp = new ByteProcessor(w, h);
            byte[] pixels = (byte[]) bp.getPixels();
            for (int i = 0; i < size; i++) {
                if (ip.get(i) == label) pixels[i] = (byte) 255;
            }
            bp.setThreshold(255, 255, ImageProcessor.NO_LUT_UPDATE);
            ImagePlus mask = new ImagePlus("mask", bp);
            Roi roi = ThresholdToSelection.run(mask);
            if (roi == null) continue;
            roi.setName(String.format("obj-%03d", label));
            rm.addRoi(roi);
        }
    }

    /**
     * Save all ROIs currently in the ROI Manager to a ZIP file.
     * @param path File path (will have .zip appended if missing)
     * @return true if saved successfully
     */
    public static boolean saveRoiManagerToZip(String path) {
        if (path == null || path.isEmpty()) return false;
        if (!path.toLowerCase().endsWith(".zip")) path = path + ".zip";

        RoiManager rm = RoiManager.getInstance();
        if (rm == null || rm.getCount() == 0) {
            IJ.error("Save ROI", "ROI Manager is empty. Run Add ROI first.");
            return false;
        }

        Roi[] rois = rm.getRoisAsArray();
        return saveRoisToZip(rois, path);
    }

    public static boolean saveRoisToZip(Roi[] rois, String path) {
        if (path == null || path.isEmpty()) return false;
        if (!path.toLowerCase().endsWith(".zip")) path = path + ".zip";
        if (rois == null || rois.length == 0) {
            IJ.error("Save ROI", "No ROIs to save.");
            return false;
        }
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(path))) {
            DataOutputStream dos = new DataOutputStream(zos);
            for (int i = 0; i < rois.length; i++) {
                Roi roi = rois[i];
                String name = roi.getName();
                if (name == null) name = String.format("roi-%04d", i + 1);
                if (!name.endsWith(".roi")) name = name + ".roi";
                zos.putNextEntry(new ZipEntry(name));
                new RoiEncoder(dos).write(roi);
                dos.flush();
            }
            return true;
        } catch (IOException ex) {
            IJ.error("Save ROI", "Failed to save: " + ex.getMessage());
            return false;
        }
    }
}
