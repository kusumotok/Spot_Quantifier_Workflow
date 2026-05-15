package io.github.kusumotok.spotworkflow.tracking;

import ij.gui.Roi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TrackTree {
    private final List<TrackNode> tracks = new ArrayList<TrackNode>();

    public List<TrackNode> getTracks() {
        return Collections.unmodifiableList(tracks);
    }

    public List<TrackNode> mutableTracks() {
        return tracks;
    }

    public void addTrack(TrackNode track) {
        if (track != null) tracks.add(track);
    }

    public List<ObjNode> allObjects() {
        List<ObjNode> out = new ArrayList<ObjNode>();
        for (TrackNode track : tracks) track.collectObjects(out);
        return out;
    }

    public static final class TrackNode implements Entry {
        private String globalId;
        private String displayName;
        private Path sourcePath;
        private final List<Entry> children = new ArrayList<Entry>();

        public TrackNode(String globalId, String displayName) {
            this.globalId = globalId;
            this.displayName = displayName;
        }

        public String getGlobalId() { return globalId; }
        public void setGlobalId(String globalId) { this.globalId = globalId; }
        public String getDisplayName() { return displayName; }
        public Path getSourcePath() { return sourcePath; }
        public void setSourcePath(Path sourcePath) { this.sourcePath = sourcePath; }
        public List<Entry> getChildren() { return Collections.unmodifiableList(children); }
        public List<Entry> mutableChildren() { return children; }

        public void addChild(Entry child) {
            if (child != null) children.add(child);
        }

        public void addChild(int index, Entry child) {
            if (child == null) return;
            int safeIndex = Math.max(0, Math.min(index, children.size()));
            children.add(safeIndex, child);
        }

        public boolean removeChild(Entry child) {
            return children.remove(child);
        }

        private void collectObjects(List<ObjNode> out) {
            for (Entry child : children) {
                if (child instanceof ObjNode) out.add((ObjNode) child);
                else if (child instanceof TrackNode) ((TrackNode) child).collectObjects(out);
            }
        }
    }

    public static final class ObjNode implements Entry {
        private String globalId;
        private String sourceObjId;
        private Path sourcePath;
        private final List<Roi> rois = new ArrayList<Roi>();

        public ObjNode(String globalId, String sourceObjId) {
            this.globalId = globalId;
            this.sourceObjId = sourceObjId;
        }

        public String getGlobalId() { return globalId; }
        public void setGlobalId(String globalId) { this.globalId = globalId; }
        public String getSourceObjId() { return sourceObjId; }
        public void setSourceObjId(String sourceObjId) { this.sourceObjId = sourceObjId; }
        public Path getSourcePath() { return sourcePath; }
        public void setSourcePath(Path sourcePath) { this.sourcePath = sourcePath; }
        public List<Roi> getRois() { return Collections.unmodifiableList(rois); }
        public void addRoi(Roi roi) { if (roi != null) rois.add(roi); }
        public void setRois(List<Roi> nextRois) {
            rois.clear();
            if (nextRois == null) return;
            for (Roi roi : nextRois) addRoi(roi);
        }

        public int firstT() {
            int min = Integer.MAX_VALUE;
            for (Roi roi : rois) {
                int t = resolveT(roi);
                if (t > 0 && t < min) min = t;
            }
            return min == Integer.MAX_VALUE ? 1 : min;
        }

        public int firstZ() {
            int min = Integer.MAX_VALUE;
            for (Roi roi : rois) {
                int z = roi.getZPosition();
                if (z <= 0) z = roi.getPosition();
                if (z > 0 && z < min) min = z;
            }
            return min == Integer.MAX_VALUE ? 1 : min;
        }

        public double centerX() {
            if (rois.isEmpty()) return 0.0;
            double sum = 0.0;
            for (Roi roi : rois) sum += roi.getBounds().getCenterX();
            return sum / rois.size();
        }

        public double centerY() {
            if (rois.isEmpty()) return 0.0;
            double sum = 0.0;
            for (Roi roi : rois) sum += roi.getBounds().getCenterY();
            return sum / rois.size();
        }
    }

    public interface Entry {}

    public static int resolveT(Roi roi) {
        int t = roi.getTPosition();
        return t > 0 ? t : 1;
    }
}
