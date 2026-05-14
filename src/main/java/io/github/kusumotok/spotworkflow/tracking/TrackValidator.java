package io.github.kusumotok.spotworkflow.tracking;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TrackValidator {
    public Result validate(TrackTree tree) {
        Result result = new Result();
        if (tree == null || tree.getTracks().isEmpty()) {
            result.warnings.add("No tracks.");
            return result;
        }
        Set<String> objectKeys = new HashSet<String>();
        for (TrackTree.TrackNode track : tree.getTracks()) validateTrack(track, result, objectKeys);
        return result;
    }

    private void validateTrack(TrackTree.TrackNode track, Result result, Set<String> objectKeys) {
        int directObjects = 0;
        Set<Integer> directObjTimes = new HashSet<Integer>();
        Set<Integer> directTrackTimes = new HashSet<Integer>();
        for (TrackTree.Entry child : track.getChildren()) {
            if (child instanceof TrackTree.ObjNode) {
                TrackTree.ObjNode obj = (TrackTree.ObjNode) child;
                directObjects++;
                int t = obj.firstT();
                if (!directObjTimes.add(t)) {
                    result.errors.add("Multiple direct objects at T" + t + " in track " + track.getGlobalId());
                }
                String key = obj.firstT() + ":" + obj.getSourceObjId();
                if (!objectKeys.add(key)) {
                    result.errors.add("Duplicate object assignment: " + obj.getSourceObjId());
                }
                if (obj.getRois().isEmpty()) result.errors.add("Object has no ROI: " + obj.getSourceObjId());
            } else if (child instanceof TrackTree.TrackNode) {
                TrackTree.TrackNode childTrack = (TrackTree.TrackNode) child;
                Integer firstT = firstTime(childTrack);
                if (firstT != null) directTrackTimes.add(firstT);
                validateTrack(childTrack, result, objectKeys);
            }
        }
        if (directObjects == 0) {
            result.errors.add("Track has no direct object anchor: " + track.getGlobalId());
        }
        for (Integer t : directObjTimes) {
            if (directTrackTimes.contains(t)) {
                result.errors.add("Direct object and child track share T" + t + " in track " + track.getGlobalId());
            }
        }
    }

    private Integer firstTime(TrackTree.TrackNode track) {
        Integer first = null;
        for (TrackTree.Entry child : track.getChildren()) {
            Integer t = null;
            if (child instanceof TrackTree.ObjNode) t = ((TrackTree.ObjNode) child).firstT();
            else if (child instanceof TrackTree.TrackNode) t = firstTime((TrackTree.TrackNode) child);
            if (t != null && (first == null || t < first)) first = t;
        }
        return first;
    }

    public static final class Result {
        private final List<String> errors = new ArrayList<String>();
        private final List<String> warnings = new ArrayList<String>();

        public boolean isValid() { return errors.isEmpty(); }
        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }
    }
}
