package io.github.kusumotok.spotworkflow;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.Duplicator;
import io.github.kusumotok.spotworkflow.core.alg.SeededQuantifier3D;
import io.github.kusumotok.spotworkflow.core.roi.RoiExporter3D;
import io.github.kusumotok.spotworkflow.core.roi.SeedRoiReader;
import io.github.kusumotok.spotworkflow.save.*;

import java.nio.file.Files;
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
    private final SeedRoiReader        seedRoiReader  = new SeedRoiReader();

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

    public Path makeSeedRois(ImagePlus image, SegmentationParams params, Path projectFolder,
                             Consumer<String> progress) throws Exception {
        Calibration cal = image.getCalibration();
        ImagePlus channelImage = extractChannel(image, params.channel);

        report(progress, "Finding seeds...");
        SeededQuantifier3D.SeededResult result = SeededQuantifier3D.compute(
            channelImage, params.areaThreshold, params.seedThreshold,
            params.toQuantifierParams(), calibrationVoxelVolume(cal), false, progress);
        if (result == null || result.seedSeg == null) throw new RuntimeException("No seed objects detected.");

        RoiExporter3D exporter = new RoiExporter3D();
        Map<Integer, List<Roi>> roisByLabel = exporter.exportToRoiListsByLabel(
            result.seedSeg.labelImage, null, image, params.channel);
        if (roisByLabel.isEmpty()) throw new RuntimeException("No seed objects found after filtering.");

        Path seedRoot = projectFolder.resolve("seed_rois");
        backupSeedUpdateOutputs(projectFolder, progress);
        roiSaveService.saveRoisToRoot(seedRoot, new ArrayList<>(roisByLabel.values()), params.saveMode, true);
        paramWriter.update(projectFolder.resolve("parameters.txt"), params.toSeedParameterMap());
        return seedRoot;
    }

    public Path saveSeedRois(Path projectFolder, SegmentationParams params, List<List<Roi>> objectRois,
                             Consumer<String> progress) throws Exception {
        if (objectRois == null || objectRois.isEmpty()) {
            throw new RuntimeException("No seed objects to save.");
        }
        report(progress, "Saving edited seed ROI...");
        Path seedRoot = projectFolder.resolve("seed_rois");
        backupSeedUpdateOutputs(projectFolder, progress);
        roiSaveService.saveRoisToRoot(seedRoot, objectRois, params.saveMode, true);
        paramWriter.update(projectFolder.resolve("parameters.txt"), params.toSeedParameterMap());
        return seedRoot;
    }

    public Path makeResultFromSeedRois(ImagePlus image, SegmentationParams params, Path projectFolder,
                                       Consumer<String> progress) throws Exception {
        return makeResultFromSeedRois(image, params, projectFolder, projectFolder.resolve("seed_rois"), progress);
    }

    public Path makeResultFromSeedRois(ImagePlus image, SegmentationParams params, Path projectFolder,
                                       Path seedRoot, Consumer<String> progress) throws Exception {
        ImagePlus channelImage = extractChannel(image, params.channel);
        // seed フォルダ名 → label ID の対応表を取得（3D watershed 後の _split 名も追跡できる）
        SeedRoiReader.SeedReadResult seedRead = seedRoiReader.read(seedRoot, channelImage);

        report(progress, "Building result from edited seeds...");
        SeededQuantifier3D.SeededResult result = SeededQuantifier3D.computeFromSeedLabels(
            channelImage, seedRead.labelImage, params.areaThreshold, params.toQuantifierParams(),
            params.areaEnabled, progress, null);
        if (result == null || result.finalSeg == null) throw new RuntimeException("No result objects detected.");

        RoiExporter3D exporter = new RoiExporter3D();
        Map<Integer, List<Roi>> roisByLabel = exporter.exportToRoiListsByLabel(
            result.finalSeg.labelImage, null, image, params.channel);
        if (roisByLabel.isEmpty()) throw new RuntimeException("No result objects found.");

        // nameToLabel マッピングを使って seed と同じフォルダ名で result を保存
        // obj-003_split1 → result_rois/obj-003_split1 のように追跡できる
        Path resultRoot = resultRoiRootFor(projectFolder, params);
        roiSaveService.saveRoisByNameMapping(resultRoot, roisByLabel, seedRead.nameToLabel, params.saveMode, true);
        return resultRoot;
    }

    public static String areaResultKey(SegmentationParams params) {
        if (params == null || !params.areaEnabled) return "area-disabled";
        return "area-th" + params.areaThreshold;
    }

    public static Path resultRoiRootFor(Path projectFolder, SegmentationParams params) {
        return projectFolder.resolve("result_rois_" + areaResultKey(params));
    }

    public static Path measurementCsvFor(Path projectFolder, SegmentationParams params) {
        return projectFolder.resolve("measurement_" + areaResultKey(params) + ".csv");
    }

    public static Path measurementCsvFor(Path projectFolder, Path resultRoot, SegmentationParams fallbackParams) {
        if (resultRoot != null && resultRoot.getFileName() != null) {
            String name = resultRoot.getFileName().toString();
            String prefix = "result_rois_";
            if (name.startsWith(prefix)) {
                return projectFolder.resolve("measurement_" + name.substring(prefix.length()) + ".csv");
            }
        }
        return measurementCsvFor(projectFolder, fallbackParams);
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

    private static double calibrationVoxelVolume(Calibration cal) {
        double vw = cal.pixelWidth  > 0 ? cal.pixelWidth  : 1.0;
        double vh = cal.pixelHeight > 0 ? cal.pixelHeight : 1.0;
        double vd = cal.pixelDepth  > 0 ? cal.pixelDepth  : 1.0;
        return vw * vh * vd;
    }

    private String expandTokens(String pattern, ImagePlus image) {
        String title = image.getTitle();
        String name = title.replaceAll("\\.[^.]+$", ""); // strip extension
        return pattern.replace("{name}", name);
    }

    private static void report(Consumer<String> progress, String msg) {
        if (progress != null) progress.accept(msg);
    }

    private void backupSeedUpdateOutputs(Path projectFolder, Consumer<String> progress) throws Exception {
        if (projectFolder == null) return;
        Path seedRoot = projectFolder.resolve("seed_rois");
        Path paramsFile = projectFolder.resolve("parameters.txt");
        boolean hasOutputs = Files.exists(seedRoot) || Files.exists(paramsFile)
            || hasAreaOutputs(projectFolder) || hasMeasurementCsvs(projectFolder);
        if (!hasOutputs) return;

        Path backupDir = nextProjectBackupDir(projectFolder);
        Files.createDirectories(backupDir);
        moveIfExists(seedRoot, backupDir.resolve("seed_rois"));
        moveAreaOutputs(projectFolder, backupDir);
        moveIfExists(paramsFile, backupDir.resolve("parameters.txt"));
        moveMeasurementCsvs(projectFolder, backupDir);
        report(progress, "Moved previous project outputs to " + backupDir.getFileName());
    }

    private Path nextProjectBackupDir(Path projectFolder) {
        Path backupRoot = projectFolder.resolve("backup");
        String base = projectName(projectFolder) + "_backup";
        Path candidate = backupRoot.resolve(base);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = backupRoot.resolve(base + "_" + suffix);
            suffix++;
        }
        return candidate;
    }

    private static String projectName(Path projectFolder) {
        return projectFolder.getFileName() != null ? projectFolder.getFileName().toString() : "project";
    }

    private static void moveIfExists(Path source, Path target) throws Exception {
        if (Files.exists(source)) Files.move(source, target);
    }

    private static void moveMeasurementCsvs(Path projectFolder, Path backupDir) throws Exception {
        if (projectFolder == null || !Files.isDirectory(projectFolder)) return;
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(projectFolder, "measurement*.csv")) {
            for (Path csv : stream) {
                moveIfExists(csv, backupDir.resolve(csv.getFileName()));
            }
        }
    }

    private static void moveAreaOutputs(Path projectFolder, Path backupDir) throws Exception {
        if (projectFolder == null || !Files.isDirectory(projectFolder)) return;
        moveIfExists(projectFolder.resolve("result_rois"), backupDir.resolve("result_rois"));
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(projectFolder, "result_rois_area-*")) {
            for (Path resultRoot : stream) {
                moveIfExists(resultRoot, backupDir.resolve(resultRoot.getFileName()));
            }
        }
    }

    private static boolean hasAreaOutputs(Path projectFolder) throws Exception {
        if (projectFolder == null || !Files.isDirectory(projectFolder)) return false;
        if (Files.exists(projectFolder.resolve("result_rois"))) return true;
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(projectFolder, "result_rois_area-*")) {
            return stream.iterator().hasNext();
        }
    }

    private static boolean hasMeasurementCsvs(Path projectFolder) throws Exception {
        if (projectFolder == null || !Files.isDirectory(projectFolder)) return false;
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(projectFolder, "measurement*.csv")) {
            return stream.iterator().hasNext();
        }
    }
}
