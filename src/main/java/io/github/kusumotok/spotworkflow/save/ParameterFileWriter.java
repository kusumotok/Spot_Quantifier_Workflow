package io.github.kusumotok.spotworkflow.save;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
}
