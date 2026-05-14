package io.github.kusumotok.spotworkflow.tracking;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class TrackEditor {
    public List<ObjRef> objectsAt(TrackTree tree, int t) {
        List<ObjRef> out = new ArrayList<ObjRef>();
        if (tree == null) return out;
        for (TrackTree.TrackNode track : tree.getTracks()) collect(track, t, out);
        return out;
    }

    public void linkAfter(ObjRef left, ObjRef right) {
        if (left == null || right == null || left.obj == right.obj) return;
        right.parent.removeChild(right.obj);
        int leftIndex = left.parent.mutableChildren().indexOf(left.obj);
        left.parent.addChild(leftIndex + 1, right.obj);
    }

    public void unlinkToNewTrack(TrackTree tree, ObjRef ref) {
        if (tree == null || ref == null) return;
        ref.parent.removeChild(ref.obj);
        TrackTree.TrackNode track = new TrackTree.TrackNode("", "track");
        track.addChild(ref.obj);
        tree.addTrack(track);
    }

    public void pruneEmptyTracks(TrackTree tree) {
        if (tree == null) return;
        pruneRootTracks(tree.mutableTracks());
    }

    private void pruneRootTracks(List<TrackTree.TrackNode> tracks) {
        for (Iterator<TrackTree.TrackNode> it = tracks.iterator(); it.hasNext();) {
            TrackTree.TrackNode track = it.next();
            pruneChildTracks(track);
            if (track.getChildren().isEmpty()) it.remove();
        }
    }

    private void pruneChildTracks(TrackTree.TrackNode track) {
        for (Iterator<TrackTree.Entry> it = track.mutableChildren().iterator(); it.hasNext();) {
            TrackTree.Entry child = it.next();
            if (child instanceof TrackTree.TrackNode) {
                TrackTree.TrackNode childTrack = (TrackTree.TrackNode) child;
                pruneChildTracks(childTrack);
                if (childTrack.getChildren().isEmpty()) it.remove();
            }
        }
    }

    private void collect(TrackTree.TrackNode parent, int t, List<ObjRef> out) {
        for (TrackTree.Entry child : parent.getChildren()) {
            if (child instanceof TrackTree.ObjNode) {
                TrackTree.ObjNode obj = (TrackTree.ObjNode) child;
                if (obj.firstT() == t) out.add(new ObjRef(parent, obj));
            } else if (child instanceof TrackTree.TrackNode) {
                collect((TrackTree.TrackNode) child, t, out);
            }
        }
    }

    public static final class ObjRef {
        public final TrackTree.TrackNode parent;
        public final TrackTree.ObjNode obj;

        ObjRef(TrackTree.TrackNode parent, TrackTree.ObjNode obj) {
            this.parent = parent;
            this.obj = obj;
        }

        @Override public String toString() {
            return obj.getGlobalId() + "  " + obj.getSourceObjId();
        }
    }
}
