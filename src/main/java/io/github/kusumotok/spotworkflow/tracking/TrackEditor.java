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
        linkAfter(null, left, right);
    }

    public void linkAfter(TrackTree tree, ObjRef left, ObjRef right) {
        if (left == null || right == null || left.obj == right.obj) return;
        int leftIndex = left.parent.mutableChildren().indexOf(left.obj);
        int rightIndex = right.parent.mutableChildren().indexOf(right.obj);
        if (leftIndex < 0 || rightIndex < 0) return;
        if (left.parent == right.parent) {
            // Already part of the same track. Adjacent links are a no-op; non-adjacent
            // same-track edits are intentionally ignored as a fail-safe.
            return;
        }

        TrackTree.TrackNode merged = new TrackTree.TrackNode("", "track");
        moveRange(left.parent.mutableChildren(), 0, leftIndex + 1, merged);
        moveRange(right.parent.mutableChildren(), rightIndex, right.parent.mutableChildren().size(), merged);
        if (!merged.getChildren().isEmpty()) addRootTrack(tree, merged);

        splitNonEmptyRemainderToRoot(tree, left.parent);
        splitNonEmptyRemainderToRoot(tree, right.parent);
    }

    public ObjRef nextObject(TrackTree tree, ObjRef ref) {
        if (tree == null || ref == null) return null;
        List<ObjRef> refs = objectsAt(tree, ref.obj.firstT() + 1);
        for (ObjRef candidate : refs) {
            if (candidate.parent == ref.parent) return candidate;
        }
        return null;
    }

    public ObjRef previousObject(TrackTree tree, ObjRef ref) {
        if (tree == null || ref == null) return null;
        List<ObjRef> refs = objectsAt(tree, ref.obj.firstT() - 1);
        for (ObjRef candidate : refs) {
            if (candidate.parent == ref.parent) return candidate;
        }
        return null;
    }

    public void unlinkToNewTrack(TrackTree tree, ObjRef ref) {
        if (tree == null || ref == null) return;
        int index = ref.parent.mutableChildren().indexOf(ref.obj);
        if (index < 0) return;
        TrackTree.TrackNode track = new TrackTree.TrackNode("", "track");
        moveRange(ref.parent.mutableChildren(), index, ref.parent.mutableChildren().size(), track);
        if (!track.getChildren().isEmpty()) tree.addTrack(track);
    }

    public boolean removeObjectAndSplit(TrackTree tree, TrackTree.TrackNode parent, TrackTree.ObjNode obj) {
        if (tree == null || parent == null || obj == null) return false;
        int index = parent.mutableChildren().indexOf(obj);
        if (index < 0) return false;
        parent.mutableChildren().remove(index);
        TrackTree.TrackNode suffix = new TrackTree.TrackNode("", "track");
        moveRange(parent.mutableChildren(), index, parent.mutableChildren().size(), suffix);
        if (!suffix.getChildren().isEmpty()) tree.addTrack(suffix);
        return true;
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

    private static void moveRange(List<TrackTree.Entry> source, int fromInclusive, int toExclusive,
                                  TrackTree.TrackNode target) {
        int from = Math.max(0, fromInclusive);
        int to = Math.max(from, Math.min(toExclusive, source.size()));
        List<TrackTree.Entry> moving = new ArrayList<TrackTree.Entry>(source.subList(from, to));
        source.subList(from, to).clear();
        for (TrackTree.Entry entry : moving) target.addChild(entry);
    }

    private static void addRootTrack(TrackTree tree, TrackTree.TrackNode track) {
        if (tree != null) tree.addTrack(track);
    }

    private static void splitNonEmptyRemainderToRoot(TrackTree tree, TrackTree.TrackNode parent) {
        if (tree == null || parent == null || parent.getChildren().isEmpty()) return;
        if (tree.getTracks().contains(parent)) return;
        TrackTree.TrackNode remainder = new TrackTree.TrackNode("", "track");
        moveRange(parent.mutableChildren(), 0, parent.mutableChildren().size(), remainder);
        if (!remainder.getChildren().isEmpty()) tree.addTrack(remainder);
    }

    public static final class ObjRef {
        public final TrackTree.TrackNode parent;
        public final TrackTree.ObjNode obj;

        ObjRef(TrackTree.TrackNode parent, TrackTree.ObjNode obj) {
            this.parent = parent;
            this.obj = obj;
        }

        @Override public String toString() {
            return obj.getSourceObjId();
        }
    }
}
