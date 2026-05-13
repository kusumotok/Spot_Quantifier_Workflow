package io.github.kusumotok.spotworkflow.save;

import ij.IJ;
import io.github.kusumotok.spotworkflow.core.alg.QuantifierParams;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ParameterFileReader {

    /**
     * Reads a parameters.txt file written by ParameterFileWriter and
     * returns the corresponding SegmentationParams.
     * Missing keys keep the default values from SegmentationParams.
     */
    public SegmentationParams read(Path path) throws IOException {
        Map<String, String> map = parseKeyValue(path);
        SegmentationParams p = new SegmentationParams();

        if (map.containsKey("SEED_THRESHOLD"))
            p.seedThreshold = parseInt(map.get("SEED_THRESHOLD"), p.seedThreshold);
        if (map.containsKey("AREA_THRESHOLD"))
            p.areaThreshold = parseInt(map.get("AREA_THRESHOLD"), p.areaThreshold);
        if (map.containsKey("AREA_ENABLED"))
            p.areaEnabled = Boolean.parseBoolean(map.get("AREA_ENABLED"));
        if (map.containsKey("MIN_VOL_UM3"))
            p.minVolUm3 = parseDoubleOrNull(map.get("MIN_VOL_UM3"));
        if (map.containsKey("MAX_VOL_UM3"))
            p.maxVolUm3 = parseDoubleOrNull(map.get("MAX_VOL_UM3"));
        if (map.containsKey("CONNECTIVITY"))
            p.connectivity = parseInt(map.get("CONNECTIVITY"), p.connectivity);
        if (map.containsKey("FILL_HOLES"))
            p.fillHoles = Boolean.parseBoolean(map.get("FILL_HOLES"));
        if (map.containsKey("AREA_CONFLICT_MODE")) {
            String raw = map.get("AREA_CONFLICT_MODE");
            if ("split".equalsIgnoreCase(raw)) {
                p.areaConflictMode = QuantifierParams.AreaConflictMode.SPLIT;
            } else if (!"max_overlap".equalsIgnoreCase(raw)) {
                IJ.log("[SpotQuantifier] WARN: unknown AREA_CONFLICT_MODE='" + raw + "', using MAX_OVERLAP");
            }
        }
        if (map.containsKey("SAVE_MODE")) {
            try { p.saveMode = SaveMode.valueOf(map.get("SAVE_MODE").toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }
        return p;
    }

    private static Map<String, String> parseKeyValue(Path path) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.startsWith("#") || line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return map;
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static Double parseDoubleOrNull(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
