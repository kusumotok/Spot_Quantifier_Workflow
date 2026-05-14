package io.github.kusumotok.spotworkflow;

import ij.gui.Roi;
import ij.io.RoiDecoder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class TimeSeriesTrackController {

    public Path buildTracks(Path projectFolder, Consumer<String> progress) throws Exception {
        Path untrackedRoot = projectFolder.resolve("seed_rois_untracked");
        if (!Files.isDirectory(untrackedRoot)) {
            throw new IOException("Missing seed_rois_untracked. Run Seed first.");
        }
        List<Path> timeRoots = listTimeRoots(untrackedRoot);
        if (timeRoots.isEmpty()) throw new IOException("No timepoint folders in seed_rois_untracked.");

        Path tracksRoot = projectFolder.resolve("seed_tracks");
        deleteContents(tracksRoot);
        Files.createDirectories(tracksRoot);

        Map<Integer, Integer> previousTrackByObject = new HashMap<>();
        List<ObjectInfo> previousObjects = new ArrayList<>();
        int nextTrackId = 1;
        for (Path timeRoot : timeRoots) {
            int t = parseTimeFolder(timeRoot);
            report(progress, "Linking seed ROIs for T " + t + "...");
            List<ObjectInfo> currentObjects = readObjects(timeRoot, t);
            Map<Integer, Integer> currentTrackByObject = new HashMap<>();
            Set<Integer> usedPrevious = new HashSet<>();
            for (ObjectInfo current : currentObjects) {
                ObjectInfo best = nearestUnused(current, previousObjects, usedPrevious);
                int trackId;
                if (best != null) {
                    trackId = previousTrackByObject.get(best.index);
                    usedPrevious.add(best.index);
                } else {
                    trackId = nextTrackId++;
                }
                currentTrackByObject.put(current.index, trackId);
                copyObjectToTrack(current.path, tracksRoot, trackId, t);
            }
            previousObjects = currentObjects;
            previousTrackByObject = currentTrackByObject;
        }
        report(progress, "Seed tracks saved: " + tracksRoot);
        return tracksRoot;
    }

    private static List<Path> listTimeRoots(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                .filter(p -> parseTimeFolder(p) > 0)
                .sorted(Comparator.comparingInt(TimeSeriesTrackController::parseTimeFolder))
                .forEach(out::add);
        }
        return out;
    }

    private static List<ObjectInfo> readObjects(Path timeRoot, int t) throws IOException {
        List<ObjectInfo> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(timeRoot)) {
            List<Path> objects = new ArrayList<>();
            stream.filter(Files::isDirectory)
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .forEach(objects::add);
            int index = 1;
            for (Path object : objects) {
                out.add(new ObjectInfo(index++, t, object, centroid(object)));
            }
        }
        return out;
    }

    private static Point3 centroid(Path objectDir) throws IOException {
        double sx = 0, sy = 0, sz = 0;
        int n = 0;
        try (java.util.stream.Stream<Path> stream = Files.walk(objectDir)) {
            List<Path> files = new ArrayList<>();
            stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".roi"))
                .forEach(files::add);
            for (Path file : files) {
                Roi roi = new RoiDecoder(file.toString()).getRoi();
                if (roi == null) continue;
                java.awt.Rectangle b = roi.getBounds();
                sx += b.getCenterX();
                sy += b.getCenterY();
                int z = roi.getZPosition() > 0 ? roi.getZPosition() : roi.getPosition();
                sz += z > 0 ? z : 1;
                n++;
            }
        }
        if (n == 0) return new Point3(0, 0, 0);
        return new Point3(sx / n, sy / n, sz / n);
    }

    private static ObjectInfo nearestUnused(ObjectInfo current, List<ObjectInfo> previous, Set<Integer> usedPrevious) {
        ObjectInfo best = null;
        double bestD2 = Double.POSITIVE_INFINITY;
        for (ObjectInfo candidate : previous) {
            if (usedPrevious.contains(candidate.index)) continue;
            double d2 = current.centroid.distance2(candidate.centroid);
            if (d2 < bestD2) {
                bestD2 = d2;
                best = candidate;
            }
        }
        return best;
    }

    private static void copyObjectToTrack(Path object, Path tracksRoot, int trackId, int t) throws IOException {
        Path target = tracksRoot.resolve(String.format("obj-%03d", trackId))
            .resolve(TimeSeriesSegmentationController.timeFolder(t));
        copyDirectory(object, target);
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
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

    private static void deleteContents(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            List<Path> paths = new ArrayList<>();
            stream.forEach(paths::add);
            paths.sort(Comparator.reverseOrder());
            for (Path path : paths) {
                if (!path.equals(dir)) Files.deleteIfExists(path);
            }
        }
    }

    private static int parseTimeFolder(Path path) {
        String name = path.getFileName() != null ? path.getFileName().toString() : "";
        if (!name.matches("(?i)t\\d+")) return 0;
        return Integer.parseInt(name.substring(1));
    }

    private static void report(Consumer<String> progress, String msg) {
        if (progress != null) progress.accept(msg);
    }

    private static final class ObjectInfo {
        final int index;
        final int t;
        final Path path;
        final Point3 centroid;

        ObjectInfo(int index, int t, Path path, Point3 centroid) {
            this.index = index;
            this.t = t;
            this.path = path;
            this.centroid = centroid;
        }
    }

    private static final class Point3 {
        final double x, y, z;

        Point3(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        double distance2(Point3 other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
