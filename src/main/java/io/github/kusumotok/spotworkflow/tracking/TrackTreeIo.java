package io.github.kusumotok.spotworkflow.tracking;

import ij.gui.Roi;
import ij.io.RoiDecoder;
import ij.io.RoiEncoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class TrackTreeIo {
    private static final Pattern TRACK_NAME = Pattern.compile("^([0-9.]+)__track(?:__(.+))?$");
    private static final Pattern OBJ_NAME = Pattern.compile("^([0-9.]+)__(?:obj__)?(.+)$");

    public TrackTree read(Path root) throws IOException {
        TrackTree tree = new TrackTree();
        if (root == null || !Files.isDirectory(root)) return tree;
        for (Path child : listChildren(root)) {
            TrackTree.TrackNode track = readTrack(child);
            if (track != null) tree.addTrack(track);
        }
        return tree;
    }

    public void write(Path root, TrackTree tree) throws IOException {
        deleteContents(root);
        Files.createDirectories(root);
        int index = 1;
        for (TrackTree.TrackNode track : tree.getTracks()) {
            assignIds(track, String.format("%03d", index++));
            writeTrack(root, track);
        }
    }

    public int copyObjectsAtTimeToFlatRoot(Path tracksRoot, Path flatRoot, int time) throws IOException {
        TrackTree tree = read(tracksRoot);
        Files.createDirectories(flatRoot);
        int copied = 0;
        for (TrackTree.ObjNode obj : tree.allObjects()) {
            if (obj.firstT() != time) continue;
            Path target = flatRoot.resolve(obj.getGlobalId());
            writeObjRois(target, obj.getRois());
            copied++;
        }
        return copied;
    }

    public void copyFlatResultsToTrackTree(Path seedTracksRoot, Path flatResultRoot,
                                           Path resultRoot, int time) throws IOException {
        TrackTree seedTree = read(seedTracksRoot);
        Files.createDirectories(resultRoot);
        for (TrackTree.TrackNode track : seedTree.getTracks()) {
            copyResultTrack(track, resultRoot, flatResultRoot, time);
        }
    }

    private static void copyResultTrack(TrackTree.TrackNode track, Path parent,
                                        Path flatResultRoot, int time) throws IOException {
        Path outTrack = parent.resolve(trackName(track));
        Files.createDirectories(outTrack);
        for (TrackTree.Entry child : track.getChildren()) {
            if (child instanceof TrackTree.TrackNode) {
                copyResultTrack((TrackTree.TrackNode) child, outTrack, flatResultRoot, time);
            } else if (child instanceof TrackTree.ObjNode) {
                TrackTree.ObjNode obj = (TrackTree.ObjNode) child;
                if (obj.firstT() != time) continue;
                Path flat = findFlatObject(flatResultRoot, obj.getGlobalId());
                if (flat != null) copyDirectoryOrFile(flat, outTrack.resolve(resultObjName(obj)));
            }
        }
    }

    private static Path findFlatObject(Path flatRoot, String globalId) throws IOException {
        if (flatRoot == null || !Files.isDirectory(flatRoot)) return null;
        try (java.util.stream.Stream<Path> stream = Files.list(flatRoot)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String stem = stemName(p);
                if (globalId.equals(stem)) return p;
            }
        }
        return null;
    }

    private static TrackTree.TrackNode readTrack(Path path) throws IOException {
        if (!Files.isDirectory(path)) return null;
        Matcher m = TRACK_NAME.matcher(path.getFileName().toString());
        if (!m.matches()) return null;
        String displayName = m.group(2) != null ? m.group(2) : "";
        TrackTree.TrackNode track = new TrackTree.TrackNode(m.group(1), displayName);
        track.setSourcePath(path);
        for (Path child : listChildren(path)) {
            TrackTree.TrackNode childTrack = readTrack(child);
            if (childTrack != null) {
                track.addChild(childTrack);
                continue;
            }
            TrackTree.ObjNode obj = readObj(child);
            if (obj != null) track.addChild(obj);
        }
        return track;
    }

    private static TrackTree.ObjNode readObj(Path path) throws IOException {
        if (!Files.isDirectory(path)) return null;
        Matcher m = OBJ_NAME.matcher(path.getFileName().toString());
        if (!m.matches()) return null;
        TrackTree.ObjNode obj = new TrackTree.ObjNode(m.group(1), m.group(2));
        obj.setSourcePath(path);
        for (Roi roi : readRois(path)) obj.addRoi(roi);
        return obj;
    }

    private static void assignIds(TrackTree.TrackNode track, String id) {
        track.setGlobalId(id);
        int index = 1;
        for (TrackTree.Entry child : track.getChildren()) {
            String childId = id + "." + String.format("%03d", index++);
            if (child instanceof TrackTree.TrackNode) assignIds((TrackTree.TrackNode) child, childId);
            else if (child instanceof TrackTree.ObjNode) ((TrackTree.ObjNode) child).setGlobalId(childId);
        }
    }

    private static void writeTrack(Path parent, TrackTree.TrackNode track) throws IOException {
        Path dir = parent.resolve(trackName(track));
        Files.createDirectories(dir);
        for (TrackTree.Entry child : track.getChildren()) {
            if (child instanceof TrackTree.TrackNode) writeTrack(dir, (TrackTree.TrackNode) child);
            else if (child instanceof TrackTree.ObjNode) {
                TrackTree.ObjNode obj = (TrackTree.ObjNode) child;
                writeObjRois(dir.resolve(objName(obj)), obj.getRois());
            }
        }
    }

    private static String trackName(TrackTree.TrackNode track) {
        String displayName = track.getDisplayName();
        if (displayName == null || displayName.trim().isEmpty() || "track".equalsIgnoreCase(displayName.trim())) {
            return track.getGlobalId() + "__track";
        }
        return track.getGlobalId() + "__track__" + safeName(displayName);
    }

    private static String objName(TrackTree.ObjNode obj) {
        return obj.getGlobalId() + "__" + safeName(obj.getSourceObjId());
    }

    private static String resultObjName(TrackTree.ObjNode obj) {
        return obj.getGlobalId() + "__result";
    }

    public static String sourceObjId(int t, Path source) {
        return "t" + String.format("%03d", t) + "_" + stemName(source);
    }

    public static List<Roi> readRois(Path object) throws IOException {
        List<Roi> rois = new ArrayList<Roi>();
        if (Files.isDirectory(object)) {
            try (java.util.stream.Stream<Path> stream = Files.walk(object)) {
                List<Path> files = new ArrayList<Path>();
                stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".roi"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(files::add);
                for (Path file : files) {
                    Roi roi = new RoiDecoder(file.toString()).getRoi();
                    if (roi != null) rois.add(roi);
                }
            }
        } else if (object.getFileName().toString().toLowerCase().endsWith(".zip")) {
            try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(object))) {
                ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                    if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".roi")) continue;
                    Roi roi = new RoiDecoder(readAllBytes(zin), entry.getName()).getRoi();
                    if (roi != null) rois.add(roi);
                }
            }
        }
        return rois;
    }

    private static void writeObjRois(Path objectDir, List<Roi> rois) throws IOException {
        Files.createDirectories(objectDir);
        int index = 1;
        for (Roi roi : rois) {
            String name = roi.getName();
            if (name == null || name.trim().isEmpty()) name = "roi-" + String.format("%03d", index);
            if (!name.toLowerCase().endsWith(".roi")) name += ".roi";
            new RoiEncoder(objectDir.resolve(name).toString()).write(roi);
            index++;
        }
    }

    private static List<Path> listChildren(Path root) throws IOException {
        List<Path> out = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = Files.list(root)) {
            stream.sorted(Comparator.comparing(p -> p.getFileName().toString())).forEach(out::add);
        }
        return out;
    }

    private static void copyDirectoryOrFile(Path source, Path target) throws IOException {
        if (Files.isDirectory(source)) copyDirectory(source, target);
        else {
            Files.createDirectories(target);
            Files.copy(source, target.resolve(source.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
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
            List<Path> paths = new ArrayList<Path>();
            stream.forEach(paths::add);
            paths.sort(Comparator.reverseOrder());
            for (Path path : paths) if (!path.equals(dir)) Files.deleteIfExists(path);
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String stemName(Path path) {
        String name = path.getFileName().toString();
        if (name.toLowerCase().endsWith(".zip")) name = name.substring(0, name.length() - 4);
        return name;
    }

    private static String safeName(String name) {
        if (name == null || name.trim().isEmpty()) return "track";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

}
