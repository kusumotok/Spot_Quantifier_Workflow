package io.github.kusumotok.spotworkflow;

import ij.gui.Roi;
import io.github.kusumotok.spotworkflow.tracking.TrackTree;
import io.github.kusumotok.spotworkflow.tracking.TrackTreeIo;
import io.github.kusumotok.spotworkflow.tracking.TrackValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class TimeSeriesTrackController {
    private static final double MIN_AUTO_LINK_DISTANCE = 25.0;
    private static final double AUTO_LINK_RADIUS_FACTOR = 6.0;

    private final TrackTreeIo treeIo = new TrackTreeIo();
    private final TrackValidator validator = new TrackValidator();

    public Path buildTracks(Path projectFolder, Consumer<String> progress) throws Exception {
        return buildTracks(projectFolder, 0.0, progress);
    }

    public Path buildTracks(Path projectFolder, double maxLinkDistancePx, Consumer<String> progress) throws Exception {
        Path untrackedRoot = projectFolder.resolve("seed_rois_untracked");
        if (!Files.isDirectory(untrackedRoot)) {
            throw new IOException("Missing seed_rois_untracked. Run Seed first.");
        }
        double maxDistance = Math.max(0.0, maxLinkDistancePx);
        List<Path> timeRoots = listTimeRoots(untrackedRoot);
        if (timeRoots.isEmpty()) throw new IOException("No timepoint folders in seed_rois_untracked.");

        Path tracksRoot = projectFolder.resolve("seed_tracks");
        backupExistingTracks(projectFolder, tracksRoot, progress);

        List<ObjectInfo> previousObjects = new ArrayList<>();
        TrackTree tree = new TrackTree();
        int nextTrackId = 1;
        for (Path timeRoot : timeRoots) {
            int t = parseTimeFolder(timeRoot);
            report(progress, "Linking seed ROIs for T " + t + "...");
            List<ObjectInfo> currentObjects = readObjects(timeRoot, t);
            Set<Integer> usedPrevious = new HashSet<>();
            for (ObjectInfo current : currentObjects) {
                ObjectInfo best = mutualNearestWithinLimit(current, currentObjects, previousObjects, usedPrevious, maxDistance);
                TrackTree.TrackNode track;
                if (best != null) {
                    track = best.track;
                    usedPrevious.add(best.index);
                } else {
                    track = new TrackTree.TrackNode(String.format("%03d", nextTrackId++), "track");
                    tree.addTrack(track);
                }
                current.track = track;
                track.addChild(new TrackTree.ObjNode("", TrackTreeIo.sourceObjId(t, current.path)));
                TrackTree.ObjNode obj = (TrackTree.ObjNode) track.getChildren().get(track.getChildren().size() - 1);
                for (Roi roi : current.rois) obj.addRoi(roi);
            }
            previousObjects = currentObjects;
        }
        TrackValidator.Result validation = validator.validate(tree);
        if (!validation.isValid()) {
            throw new IOException("Auto tracking produced invalid track tree: " + validation.getErrors().get(0));
        }
        treeIo.write(tracksRoot, tree);
        report(progress, "Seed tracks saved: " + tracksRoot);
        return tracksRoot;
    }

    public Path syncTracksWithUntrackedSeedRois(Path projectFolder, Consumer<String> progress) throws Exception {
        Path untrackedRoot = projectFolder.resolve("seed_rois_untracked");
        Path tracksRoot = projectFolder.resolve("seed_tracks");
        if (!Files.isDirectory(untrackedRoot) || !Files.isDirectory(tracksRoot)) return tracksRoot;

        TrackTree tree = treeIo.read(tracksRoot);
        Map<String, ObjectInfo> current = readAllObjectsBySourceId(untrackedRoot);
        Map<String, ObjLocation> existing = new LinkedHashMap<String, ObjLocation>();
        collectObjLocations(tree, existing);

        int removed = removeMissingObjects(tree, current.keySet());
        int updated = 0;
        int added = 0;
        for (Map.Entry<String, ObjectInfo> entry : current.entrySet()) {
            ObjLocation location = existing.get(entry.getKey());
            if (location != null) {
                location.obj.setSourceObjId(entry.getKey());
                location.obj.setRois(entry.getValue().rois);
                updated++;
                continue;
            }
            TrackTree.TrackNode track = new TrackTree.TrackNode("", "track");
            TrackTree.ObjNode obj = new TrackTree.ObjNode("", entry.getKey());
            obj.setRois(entry.getValue().rois);
            track.addChild(obj);
            tree.addTrack(track);
            added++;
        }
        new io.github.kusumotok.spotworkflow.tracking.TrackEditor().pruneEmptyTracks(tree);
        treeIo.write(tracksRoot, tree);
        report(progress, "Synced seed_tracks with seed_rois_untracked: +" + added
            + ", -" + removed + ", updated " + updated + ".");
        return tracksRoot;
    }

    private static Map<String, ObjectInfo> readAllObjectsBySourceId(Path untrackedRoot) throws IOException {
        Map<String, ObjectInfo> out = new LinkedHashMap<String, ObjectInfo>();
        for (Path timeRoot : listTimeRoots(untrackedRoot)) {
            int t = parseTimeFolder(timeRoot);
            for (ObjectInfo info : readObjects(timeRoot, t)) {
                out.put(TrackTreeIo.sourceObjId(t, info.path), info);
            }
        }
        return out;
    }

    private static void collectObjLocations(TrackTree tree, Map<String, ObjLocation> out) {
        if (tree == null) return;
        for (TrackTree.TrackNode track : tree.getTracks()) collectObjLocations(track, out);
    }

    private static void collectObjLocations(TrackTree.TrackNode parent, Map<String, ObjLocation> out) {
        for (TrackTree.Entry child : parent.getChildren()) {
            if (child instanceof TrackTree.ObjNode) {
                TrackTree.ObjNode obj = (TrackTree.ObjNode) child;
                out.put(TrackTreeIo.canonicalSourceObjId(obj.getSourceObjId()), new ObjLocation(parent, obj));
            } else if (child instanceof TrackTree.TrackNode) {
                collectObjLocations((TrackTree.TrackNode) child, out);
            }
        }
    }

    private static int removeMissingObjects(TrackTree tree, Set<String> currentSourceIds) {
        int removed = 0;
        for (TrackTree.TrackNode track : tree.getTracks()) removed += removeMissingObjects(track, currentSourceIds);
        return removed;
    }

    private static int removeMissingObjects(TrackTree.TrackNode parent, Set<String> currentSourceIds) {
        int removed = 0;
        for (Iterator<TrackTree.Entry> it = parent.mutableChildren().iterator(); it.hasNext();) {
            TrackTree.Entry child = it.next();
            if (child instanceof TrackTree.ObjNode) {
                TrackTree.ObjNode obj = (TrackTree.ObjNode) child;
                if (!currentSourceIds.contains(TrackTreeIo.canonicalSourceObjId(obj.getSourceObjId()))) {
                    it.remove();
                    removed++;
                }
            } else if (child instanceof TrackTree.TrackNode) {
                removed += removeMissingObjects((TrackTree.TrackNode) child, currentSourceIds);
            }
        }
        return removed;
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
                List<Roi> rois = TrackTreeIo.readRois(object);
                if (rois.isEmpty()) continue;
                out.add(new ObjectInfo(index++, t, object, rois, centroid(rois), objectRadius(rois)));
            }
        }
        return out;
    }

    private static Point3 centroid(List<Roi> rois) {
        double sx = 0, sy = 0, sz = 0;
        int n = 0;
        for (Roi roi : rois) {
            if (roi == null) continue;
            java.awt.Rectangle b = roi.getBounds();
            sx += b.getCenterX();
            sy += b.getCenterY();
            int z = roi.getZPosition() > 0 ? roi.getZPosition() : roi.getPosition();
            sz += z > 0 ? z : 1;
            n++;
        }
        if (n == 0) return new Point3(0, 0, 0);
        return new Point3(sx / n, sy / n, sz / n);
    }

    private static double objectRadius(List<Roi> rois) {
        double sum = 0.0;
        int n = 0;
        for (Roi roi : rois) {
            if (roi == null) continue;
            java.awt.Rectangle b = roi.getBounds();
            sum += 0.5 * Math.hypot(b.getWidth(), b.getHeight());
            n++;
        }
        return n == 0 ? 1.0 : Math.max(1.0, sum / n);
    }

    private static ObjectInfo mutualNearestWithinLimit(ObjectInfo current, List<ObjectInfo> currentObjects,
                                                       List<ObjectInfo> previous, Set<Integer> usedPrevious,
                                                       double maxLinkDistancePx) {
        ObjectInfo best = nearestPreviousWithinLimit(current, previous, usedPrevious, maxLinkDistancePx);
        if (best == null) return null;
        ObjectInfo reverse = nearestCurrentWithinLimit(best, currentObjects, maxLinkDistancePx);
        return reverse == current ? best : null;
    }

    private static ObjectInfo nearestPreviousWithinLimit(ObjectInfo current, List<ObjectInfo> previous,
                                                        Set<Integer> usedPrevious, double maxLinkDistancePx) {
        ObjectInfo best = null;
        double bestD2 = Double.POSITIVE_INFINITY;
        for (ObjectInfo candidate : previous) {
            if (usedPrevious.contains(candidate.index)) continue;
            if (!withinAutoLinkLimit(current, candidate, maxLinkDistancePx)) continue;
            double d2 = current.centroid.distance2(candidate.centroid);
            if (d2 < bestD2) {
                bestD2 = d2;
                best = candidate;
            }
        }
        return best;
    }

    private static ObjectInfo nearestCurrentWithinLimit(ObjectInfo previous, List<ObjectInfo> currentObjects,
                                                       double maxLinkDistancePx) {
        ObjectInfo best = null;
        double bestD2 = Double.POSITIVE_INFINITY;
        for (ObjectInfo candidate : currentObjects) {
            if (!withinAutoLinkLimit(candidate, previous, maxLinkDistancePx)) continue;
            double d2 = candidate.centroid.distance2(previous.centroid);
            if (d2 < bestD2) {
                bestD2 = d2;
                best = candidate;
            }
        }
        return best;
    }

    private static boolean withinAutoLinkLimit(ObjectInfo current, ObjectInfo previous, double maxLinkDistancePx) {
        double limit = maxLinkDistancePx > 0.0
            ? maxLinkDistancePx
            : Math.max(MIN_AUTO_LINK_DISTANCE, AUTO_LINK_RADIUS_FACTOR * Math.max(current.radius, previous.radius));
        return current.centroid.distance2(previous.centroid) <= limit * limit;
    }

    private static int parseTimeFolder(Path path) {
        String name = path.getFileName() != null ? path.getFileName().toString() : "";
        if (!name.matches("(?i)t\\d+")) return 0;
        return Integer.parseInt(name.substring(1));
    }

    private static void report(Consumer<String> progress, String msg) {
        if (progress != null) progress.accept(msg);
    }

    private static void backupExistingTracks(Path projectFolder, Path tracksRoot, Consumer<String> progress) throws IOException {
        if (!Files.exists(tracksRoot)) return;
        Path backupRoot = projectFolder.resolve("backup");
        Files.createDirectories(backupRoot);
        Path backup = backupRoot.resolve("tracking_1");
        int suffix = 2;
        while (Files.exists(backup)) backup = backupRoot.resolve("tracking_" + suffix++);
        Files.move(tracksRoot, backup);
        report(progress, "Moved previous seed_tracks to " + projectFolder.relativize(backup));
    }

    private static final class ObjectInfo {
        final int index;
        final int t;
        final Path path;
        final List<Roi> rois;
        final Point3 centroid;
        final double radius;
        TrackTree.TrackNode track;

        ObjectInfo(int index, int t, Path path, List<Roi> rois, Point3 centroid, double radius) {
            this.index = index;
            this.t = t;
            this.path = path;
            this.rois = rois;
            this.centroid = centroid;
            this.radius = radius;
        }
    }

    private static final class ObjLocation {
        final TrackTree.TrackNode parent;
        final TrackTree.ObjNode obj;

        ObjLocation(TrackTree.TrackNode parent, TrackTree.ObjNode obj) {
            this.parent = parent;
            this.obj = obj;
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
