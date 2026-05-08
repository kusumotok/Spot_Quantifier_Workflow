package io.github.kusumotok.spotworkflow.save;

import io.github.kusumotok.spotworkflow.core.alg.QuantifierParams;

import java.util.LinkedHashMap;
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

    public QuantifierParams toQuantifierParams() {
        return new QuantifierParams(
            0, minVolUm3, maxVolUm3,
            gaussianBlur, gaussXY, gaussZ,
            connectivity, fillHoles, areaConflictMode);
    }

    public Map<String, String> toParameterMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("SEED_THRESHOLD",  String.valueOf(seedThreshold));
        map.put("AREA_THRESHOLD",  String.valueOf(areaThreshold));
        map.put("AREA_ENABLED",    String.valueOf(areaEnabled));
        map.put("MIN_VOL_UM3",     minVolUm3 != null ? String.valueOf(minVolUm3) : "");
        map.put("MAX_VOL_UM3",     maxVolUm3 != null ? String.valueOf(maxVolUm3) : "");
        map.put("CONNECTIVITY",    String.valueOf(connectivity));
        map.put("FILL_HOLES",      String.valueOf(fillHoles));
        map.put("GAUSSIAN_BLUR",   String.valueOf(gaussianBlur));
        if (gaussianBlur) {
            map.put("GAUSS_XY", String.valueOf(gaussXY));
            map.put("GAUSS_Z",  String.valueOf(gaussZ));
        }
        map.put("AREA_CONFLICT_MODE", areaConflictMode.name().toLowerCase());
        map.put("SAVE_MODE",       saveMode.name().toLowerCase());
        return map;
    }
}
