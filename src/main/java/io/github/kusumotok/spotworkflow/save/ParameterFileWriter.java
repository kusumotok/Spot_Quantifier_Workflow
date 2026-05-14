package io.github.kusumotok.spotworkflow.save;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ParameterFileWriter {

    /**
     * Writes parameters to a plain-text key=value file.
     * params should be a LinkedHashMap to preserve insertion order.
     *
     * Format:
     *   # Spot Quantifier Workflow -- parameters
     *   # TIMESTAMP=2026-05-07T12:00:00
     *   KEY=value
     *   ...
     */
    public void write(Path outputPath, Map<String, String> params) throws IOException {
        String timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(
                    new FileOutputStream(outputPath.toFile()), StandardCharsets.UTF_8)))) {
            pw.println("# Spot Quantifier Workflow -- parameters");
            pw.println("# TIMESTAMP=" + timestamp);
            for (Map.Entry<String, String> entry : params.entrySet()) {
                String value = entry.getValue() != null ? entry.getValue() : "";
                pw.println(entry.getKey() + "=" + value);
            }
        }
    }

    public void update(Path outputPath, Map<String, String> updates) throws IOException {
        Map<String, String> merged = new LinkedHashMap<>();
        SegmentationParams.putAllKnownKeys(merged);
        Map<String, String> existing = readExisting(outputPath);
        for (String key : SegmentationParams.PARAMETER_FILE_KEYS) {
            if (existing.containsKey(key)) merged.put(key, existing.get(key));
        }
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            merged.put(entry.getKey(), entry.getValue());
        }
        write(outputPath, merged);
    }

    private Map<String, String> readExisting(Path path) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        if (path == null || !Files.exists(path)) return map;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.startsWith("#") || line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return map;
    }
}
