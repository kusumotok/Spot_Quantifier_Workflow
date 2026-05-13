package io.github.kusumotok.spotworkflow.save;

import io.github.kusumotok.spotworkflow.core.alg.QuantifierParams;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SegmentationParams {

    public int    seedThreshold       = 200;
    public int    areaThreshold       = 100;
    public boolean areaEnabled        = true;
    public Double  minVolUm3          = null;
    public Double  maxVolUm3          = null;
    public boolean gaussianBlur       = false;
    public double  gaussXY            = 1.0;
    public double  gaussZ             = 1.0;
    public int     connectivity       = 6;
    public boolean fillHoles          = false;
    public QuantifierParams.AreaConflictMode areaConflictMode =
        QuantifierParams.AreaConflictMode.MAX_OVERLAP;
    public int     channel            = 1;
    public SaveMode saveMode          = SaveMode.FOLDER;
    public String  resultFolderPattern = "{name} result";

    public static final List<String> PARAMETER_FILE_KEYS = java.util.Arrays.asList(
        "SEED_THRESHOLD",
        "AREA_THRESHOLD",
        "AREA_ENABLED",
        "MIN_VOL_UM3",
        "MAX_VOL_UM3",
        "CONNECTIVITY",
        "FILL_HOLES",
        "AREA_CONFLICT_MODE",
        "SAVE_MODE");

    public QuantifierParams toQuantifierParams() {
        return new QuantifierParams(
            minVolUm3, maxVolUm3,
            gaussianBlur, gaussXY, gaussZ,
            connectivity, fillHoles, areaConflictMode);
    }

    public Map<String, String> toParameterMap() {
        Map<String, String> map = new LinkedHashMap<>();
        putAllKnownKeys(map);
        putSeedParameterValues(map);
        putAreaParameterValues(map);
        return map;
    }

    public Map<String, String> toSeedParameterMap() {
        Map<String, String> map = new LinkedHashMap<>();
        putSeedParameterValues(map);
        return map;
    }

    public Map<String, String> toAreaParameterMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("AREA_THRESHOLD", String.valueOf(areaThreshold));
        map.put("AREA_ENABLED", String.valueOf(areaEnabled));
        return map;
    }

    public static void putAllKnownKeys(Map<String, String> map) {
        for (String key : PARAMETER_FILE_KEYS) map.put(key, "");
    }

    private void putSeedParameterValues(Map<String, String> map) {
        map.put("SEED_THRESHOLD",  String.valueOf(seedThreshold));
        map.put("MIN_VOL_UM3",     minVolUm3 != null ? String.valueOf(minVolUm3) : "");
        map.put("MAX_VOL_UM3",     maxVolUm3 != null ? String.valueOf(maxVolUm3) : "");
        map.put("CONNECTIVITY",    String.valueOf(connectivity));
        map.put("FILL_HOLES",      String.valueOf(fillHoles));
        map.put("AREA_CONFLICT_MODE", areaConflictMode.name().toLowerCase());
        map.put("SAVE_MODE",       saveMode.name().toLowerCase());
    }

    private void putAreaParameterValues(Map<String, String> map) {
        map.put("AREA_THRESHOLD",  String.valueOf(areaThreshold));
        map.put("AREA_ENABLED",    String.valueOf(areaEnabled));
    }
}
