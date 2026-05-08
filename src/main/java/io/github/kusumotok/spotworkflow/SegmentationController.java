package io.github.kusumotok.spotworkflow;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.Duplicator;
import io.github.kusumotok.spotworkflow.core.alg.SeededQuantifier3D;
import io.github.kusumotok.spotworkflow.core.roi.RoiExporter3D;
import io.github.kusumotok.spotworkflow.save.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class SegmentationController {

    private final ResultFolderService  folderService  = new ResultFolderService();
    private final RoiSaveService       roiSaveService = new RoiSaveService();
    private final ParameterFileWriter  paramWriter    = new ParameterFileWriter();

    /**
     * Runs segmentation, saves ROIs and parameters, returns the created result folder.
     * Intended to run on a background thread (e.g. SwingWorker).
     * progress receives status strings; may be null.
     */
    public Path makeRoi(ImagePlus image, SegmentationParams params,
                        Path baseDir, Consumer<String> progress) throws Exception {
        Calibration cal = image.getCalibration();
        double vw = cal.pixelWidth  > 0 ? cal.pixelWidth  : 1.0;
        double vh = cal.pixelHeight > 0 ? cal.pixelHeight : 1.0;
        double vd = cal.pixelDepth  > 0 ? cal.pixelDepth  : 1.0;
        double voxelVol = vw * vh * vd;

        ImagePlus channelImage = extractChannel(image, params.channel);

        report(progress, "Segmenting...");
        SeededQuantifier3D.SeededResult result = SeededQuantifier3D.compute(
            channelImage,
            params.areaThreshold,
            params.seedThreshold,
            params.toQuantifierParams(),
            voxelVol,
            params.areaEnabled,
            progress);

        if (result == null || result.finalSeg == null) {
            throw new RuntimeException("No spots detected.");
        }

        RoiExporter3D exporter = new RoiExporter3D();
        Map<Integer, List<Roi>> roisByLabel = exporter.exportToRoiListsByLabel(
            result.finalSeg.labelImage, null, image, params.channel);

        if (roisByLabel.isEmpty()) {
            throw new RuntimeException("No objects found after filtering.");
        }

        report(progress, "Saving ROI...");
        String folderName = expandTokens(params.resultFolderPattern, image);
        Path resultFolder = folderService.createResultFolder(baseDir, folderName);

        List<List<Roi>> objectRois = new ArrayList<>(roisByLabel.values());
        roiSaveService.saveRois(resultFolder, objectRois, params.saveMode);
        paramWriter.write(resultFolder.resolve("parameters.txt"), params.toParameterMap());

        return resultFolder;
    }

    /**
     * Runs segmentation and returns the flat ROI list for preview overlay.
     * Does NOT save anything to disk.
     */
    public List<Roi> computePreview(ImagePlus image, SegmentationParams params) throws Exception {
        Calibration cal = image.getCalibration();
        double vw = cal.pixelWidth  > 0 ? cal.pixelWidth  : 1.0;
        double vh = cal.pixelHeight > 0 ? cal.pixelHeight : 1.0;
        double vd = cal.pixelDepth  > 0 ? cal.pixelDepth  : 1.0;

        ImagePlus channelImg = extractChannel(image, params.channel);
        SeededQuantifier3D.SeededResult result = SeededQuantifier3D.compute(
            channelImg, params.areaThreshold, params.seedThreshold,
            params.toQuantifierParams(), vw * vh * vd, params.areaEnabled);

        if (result == null || result.finalSeg == null) return Collections.emptyList();

        RoiExporter3D exporter = new RoiExporter3D();
        return exporter.exportToRoiList(result.finalSeg.labelImage,
            RoiExporter3D.DEFAULT_ROI_COLOR, image, params.channel);
    }

    private ImagePlus extractChannel(ImagePlus image, int channel) {
        if (image.getNChannels() <= 1) return image;
        int safeC = Math.max(1, Math.min(channel, image.getNChannels()));
        return new Duplicator().run(image, safeC, safeC, 1, image.getNSlices(), 1, image.getNFrames());
    }

    private String expandTokens(String pattern, ImagePlus image) {
        String title = image.getTitle();
        String name = title.replaceAll("\\.[^.]+$", ""); // strip extension
        return pattern.replace("{name}", name);
    }

    private static void report(Consumer<String> progress, String msg) {
        if (progress != null) progress.accept(msg);
    }
}
