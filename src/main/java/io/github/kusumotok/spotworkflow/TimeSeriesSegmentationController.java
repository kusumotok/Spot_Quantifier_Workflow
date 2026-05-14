package io.github.kusumotok.spotworkflow;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.Duplicator;
import io.github.kusumotok.spotworkflow.core.alg.SeededQuantifier3D;
import io.github.kusumotok.spotworkflow.core.roi.RoiExporter3D;
import io.github.kusumotok.spotworkflow.core.roi.SeedRoiReader;
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
    private final SeedRoiReader seedRoiReader = new SeedRoiReader();

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

    public Path makeResultFromSeedTracks(ImagePlus image, SegmentationParams params, Path projectFolder,
                                         Consumer<String> progress) throws Exception {
        Path tracksRoot = projectFolder.resolve("seed_tracks");
        if (!Files.isDirectory(tracksRoot)) {
            throw new IllegalArgumentException("Missing seed_tracks. Run Seed Track first.");
        }
        Path resultRoot = SegmentationController.resultRoiRootFor(projectFolder, params);
        deleteContents(resultRoot);
        Files.createDirectories(resultRoot);

        RoiExporter3D exporter = new RoiExporter3D();
        for (int t = 1; t <= image.getNFrames(); t++) {
            Path tempSeedRoot = Files.createTempDirectory("spot-quantifier-tseed-");
            Path tempResultRoot = Files.createTempDirectory("spot-quantifier-tresult-");
            try {
                int copied = copyTrackTimeToFlatSeedRoot(tracksRoot, tempSeedRoot, t);
                if (copied == 0) continue;
                report(progress, "Making area result for T " + t + " / " + image.getNFrames() + "...");
                ImagePlus channelTime = extractChannelTime(image, params.channel, t);
                SeedRoiReader.SeedReadResult seedRead = seedRoiReader.read(tempSeedRoot, channelTime);
                SeededQuantifier3D.SeededResult result = SeededQuantifier3D.computeFromSeedLabels(
                    channelTime, seedRead.labelImage, params.areaThreshold, params.toQuantifierParams(),
                    params.areaEnabled, progress, null);
                if (result == null || result.finalSeg == null || result.finalSeg.labelImage == null) continue;
                Map<Integer, List<Roi>> roisByLabel = exporter.exportToRoiListsByLabel(
                    result.finalSeg.labelImage, null, image, params.channel, t);
                roiSaveService.saveRoisByNameMapping(tempResultRoot, roisByLabel, seedRead.nameToLabel,
                    params.saveMode, true);
                copyFlatResultToTrackTime(tempResultRoot, resultRoot, t);
            } finally {
                deleteTree(tempSeedRoot);
                deleteTree(tempResultRoot);
            }
        }
        return resultRoot;
    }

    public ImagePlus readSeedTrackLabelsForTime(ImagePlus image, Path projectFolder, int channel, int time)
            throws Exception {
        Path tracksRoot = projectFolder.resolve("seed_tracks");
        if (!Files.isDirectory(tracksRoot)) {
            throw new IllegalArgumentException("Missing seed_tracks. Run Seed Track first.");
        }
        Path tempSeedRoot = Files.createTempDirectory("spot-quantifier-tseed-preview-");
        ImagePlus channelTime = null;
        try {
            int copied = copyTrackTimeToFlatSeedRoot(tracksRoot, tempSeedRoot, time);
            if (copied == 0) {
                throw new IllegalArgumentException("No seed tracks for T " + time + ".");
            }
            channelTime = extractChannelTime(image, channel, time);
            SeedRoiReader.SeedReadResult seedRead = seedRoiReader.read(tempSeedRoot, channelTime);
            return seedRead.labelImage;
        } finally {
            if (channelTime != null) channelTime.flush();
            deleteTree(tempSeedRoot);
        }
    }

    public static ImagePlus extractChannelTime(ImagePlus image, int channel, int time) {
        int safeC = Math.max(1, Math.min(channel, Math.max(1, image.getNChannels())));
        int safeT = Math.max(1, Math.min(time, Math.max(1, image.getNFrames())));
        return new Duplicator().run(image, safeC, safeC, 1, image.getNSlices(), safeT, safeT);
    }

    public static String timeFolder(int t) {
        return String.format("t%03d", t);
    }

    private static int copyTrackTimeToFlatSeedRoot(Path tracksRoot, Path flatRoot, int t) throws Exception {
        int copied = 0;
        try (java.util.stream.Stream<Path> stream = Files.list(tracksRoot)) {
            for (Path track : (Iterable<Path>) stream.filter(Files::isDirectory)::iterator) {
                Path timeDir = track.resolve(timeFolder(t));
                if (!Files.isDirectory(timeDir)) continue;
                copyDirectory(timeDir, flatRoot.resolve(track.getFileName()));
                copied++;
            }
        }
        return copied;
    }

    private static void copyFlatResultToTrackTime(Path flatResultRoot, Path resultRoot, int t) throws Exception {
        try (java.util.stream.Stream<Path> stream = Files.list(flatResultRoot)) {
            for (Path object : (Iterable<Path>) stream::iterator) {
                if (!Files.isDirectory(object) && !object.getFileName().toString().toLowerCase().endsWith(".zip")) {
                    continue;
                }
                Path target = resultRoot.resolve(object.getFileName()).resolve(timeFolder(t));
                if (Files.isDirectory(object)) copyDirectory(object, target);
                else {
                    Files.createDirectories(target);
                    Files.copy(object, target.resolve(object.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
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

    private static void copyDirectory(Path source, Path target) throws Exception {
        Files.createDirectories(target);
        try (java.util.stream.Stream<Path> stream = Files.walk(source)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path rel = source.relativize(path);
                Path dst = target.resolve(rel);
                if (Files.isDirectory(path)) Files.createDirectories(dst);
                else Files.copy(path, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteContents(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            java.util.List<Path> paths = new java.util.ArrayList<>();
            stream.forEach(paths::add);
            paths.sort(java.util.Comparator.reverseOrder());
            for (Path path : paths) if (!path.equals(dir)) Files.deleteIfExists(path);
        }
    }

    private static void deleteTree(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        deleteContents(dir);
        Files.deleteIfExists(dir);
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
