package io.github.kusumotok.spotworkflow;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.Duplicator;
import io.github.kusumotok.spotworkflow.core.alg.SeededQuantifier3D;
import io.github.kusumotok.spotworkflow.core.roi.RoiExporter3D;
import io.github.kusumotok.spotworkflow.save.ParameterFileWriter;
import io.github.kusumotok.spotworkflow.save.RoiSaveService;
import io.github.kusumotok.spotworkflow.save.SegmentationParams;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class TimeSeriesSegmentationController {

    private final RoiSaveService roiSaveService = new RoiSaveService();
    private final ParameterFileWriter paramWriter = new ParameterFileWriter();

    public Path makeUntrackedSeedRois(ImagePlus image, SegmentationParams params, Path projectFolder,
                                      Consumer<String> progress) throws Exception {
        if (image == null) throw new IllegalArgumentException("Image is required.");
        if (image.getNFrames() <= 1) throw new IllegalArgumentException("Time Series workflow requires T > 1.");

        Path seedRoot = projectFolder.resolve("seed_rois_untracked");
        backupTimeSeriesOutputs(projectFolder, progress);
        Files.createDirectories(seedRoot);

        Calibration cal = image.getCalibration();
        double voxelVolume = calibrationVoxelVolume(cal);
        RoiExporter3D exporter = new RoiExporter3D();
        for (int t = 1; t <= image.getNFrames(); t++) {
            report(progress, "Finding seeds for T " + t + " / " + image.getNFrames() + "...");
            ImagePlus channelTime = extractChannelTime(image, params.channel, t);
            SeededQuantifier3D.SeededResult result = SeededQuantifier3D.compute(
                channelTime, params.areaThreshold, params.seedThreshold, params.toQuantifierParams(),
                voxelVolume, false, progress);
            if (result == null || result.seedSeg == null || result.seedSeg.labelImage == null) continue;
            Map<Integer, List<Roi>> roisByLabel = exporter.exportToRoiListsByLabel(
                result.seedSeg.labelImage, null, image, params.channel, t);
            if (roisByLabel.isEmpty()) continue;
            Path timeRoot = seedRoot.resolve(timeFolder(t));
            roiSaveService.saveRoisToRoot(timeRoot, new ArrayList<>(roisByLabel.values()), params.saveMode, true);
        }
        paramWriter.update(projectFolder.resolve("parameters.txt"), params.toSeedParameterMap());
        return seedRoot;
    }

    public static ImagePlus extractChannelTime(ImagePlus image, int channel, int time) {
        int safeC = Math.max(1, Math.min(channel, Math.max(1, image.getNChannels())));
        int safeT = Math.max(1, Math.min(time, Math.max(1, image.getNFrames())));
        return new Duplicator().run(image, safeC, safeC, 1, image.getNSlices(), safeT, safeT);
    }

    public static String timeFolder(int t) {
        return String.format("t%03d", t);
    }

    private static double calibrationVoxelVolume(Calibration cal) {
        double vw = cal != null && cal.pixelWidth  > 0 ? cal.pixelWidth  : 1.0;
        double vh = cal != null && cal.pixelHeight > 0 ? cal.pixelHeight : 1.0;
        double vd = cal != null && cal.pixelDepth  > 0 ? cal.pixelDepth  : 1.0;
        return vw * vh * vd;
    }

    private static void report(Consumer<String> progress, String msg) {
        if (progress != null) progress.accept(msg);
    }

    private void backupTimeSeriesOutputs(Path projectFolder, Consumer<String> progress) throws Exception {
        if (projectFolder == null || !Files.isDirectory(projectFolder)) return;
        Path seedRoot = projectFolder.resolve("seed_rois_untracked");
        Path tracksRoot = projectFolder.resolve("seed_tracks");
        Path paramsFile = projectFolder.resolve("parameters.txt");
        if (!Files.exists(seedRoot) && !Files.exists(tracksRoot) && !Files.exists(paramsFile)
            && !hasGlob(projectFolder, "result_rois_area-*") && !hasGlob(projectFolder, "measurement_area-*.csv")) {
            return;
        }
        Path backupDir = nextBackupDir(projectFolder);
        Files.createDirectories(backupDir);
        moveIfExists(seedRoot, backupDir.resolve("seed_rois_untracked"));
        moveIfExists(tracksRoot, backupDir.resolve("seed_tracks"));
        moveIfExists(paramsFile, backupDir.resolve("parameters.txt"));
        moveGlob(projectFolder, "result_rois_area-*", backupDir);
        moveGlob(projectFolder, "measurement_area-*.csv", backupDir);
        report(progress, "Moved previous time-series outputs to " + backupDir.getFileName());
    }

    private static boolean hasGlob(Path folder, String glob) throws Exception {
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(folder, glob)) {
            return stream.iterator().hasNext();
        }
    }

    private static void moveGlob(Path folder, String glob, Path backupDir) throws Exception {
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(folder, glob)) {
            for (Path path : stream) moveIfExists(path, backupDir.resolve(path.getFileName()));
        }
    }

    private static void moveIfExists(Path source, Path target) throws Exception {
        if (Files.exists(source)) Files.move(source, target);
    }

    private static Path nextBackupDir(Path projectFolder) {
        Path backupRoot = projectFolder.resolve("backup");
        String projectName = projectFolder.getFileName() != null ? projectFolder.getFileName().toString() : "project";
        String base = projectName + "_backup";
        Path candidate = backupRoot.resolve(base);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = backupRoot.resolve(base + "_" + suffix);
            suffix++;
        }
        return candidate;
    }
}
