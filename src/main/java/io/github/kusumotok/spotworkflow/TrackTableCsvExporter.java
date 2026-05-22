package io.github.kusumotok.spotworkflow;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TrackTableCsvExporter {

    private TrackTableCsvExporter() {}

    static Path write(Path sourceCsv, Path outputCsv, TimeSeriesMeasurementTab.TrackTableValue value, int frameCount)
        throws IOException {
        List<List<String>> rows = readCsv(sourceCsv);
        if (rows.isEmpty()) throw new IOException("Measurement CSV is empty: " + sourceCsv);

        List<String> header = rows.get(0);
        int unitCol = indexOf(header, "unit_name");
        int tCol = indexOf(header, "t");
        int valueCol = resolveValueColumn(header, value);
        if (unitCol < 0) throw new IOException("Measurement CSV has no unit_name column.");
        if (tCol < 0) throw new IOException("Measurement CSV has no t column.");

        LinkedHashMap<String, LinkedHashMap<Integer, Cell>> table =
            new LinkedHashMap<String, LinkedHashMap<Integer, Cell>>();
        int maxObservedT = 0;
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String unitName = cell(row, unitCol);
            if (unitName.trim().isEmpty()) continue;
            int t = parseTime(cell(row, tCol), i + 1);
            maxObservedT = Math.max(maxObservedT, t);
            String trackPath = parentPath(unitName);
            LinkedHashMap<Integer, Cell> byTime = table.get(trackPath);
            if (byTime == null) {
                byTime = new LinkedHashMap<Integer, Cell>();
                table.put(trackPath, byTime);
            }
            Cell previous = byTime.get(t);
            if (previous != null) {
                throw new IOException("Duplicate track-table cell at t=" + t + ", track=" + trackPath
                    + ": " + previous.unitName + " and " + unitName);
            }
            byTime.put(t, new Cell(unitName, cell(row, valueCol)));
        }

        Files.createDirectories(outputCsv.getParent());
        writeTable(outputCsv, table, Math.max(Math.max(1, frameCount), maxObservedT));
        return outputCsv;
    }

    private static List<List<String>> readCsv(Path csv) throws IOException {
        List<List<String>> rows = new ArrayList<List<String>>();
        try (BufferedReader br = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                rows.add(parseLine(line));
            }
        }
        return rows;
    }

    private static List<String> parseLine(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                out.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        out.add(cell.toString());
        return out;
    }

    private static void writeTable(Path outputCsv,
                                   LinkedHashMap<String, LinkedHashMap<Integer, Cell>> table,
                                   int frameCount) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(outputCsv, StandardCharsets.UTF_8)) {
            bw.write("t");
            for (String track : table.keySet()) {
                bw.write(',');
                bw.write(csvCell(track));
            }
            bw.newLine();
            for (int t = 1; t <= frameCount; t++) {
                bw.write(Integer.toString(t));
                for (Map<Integer, Cell> byTime : table.values()) {
                    bw.write(',');
                    Cell cell = byTime.get(t);
                    if (cell != null) bw.write(csvCell(cell.value));
                }
                bw.newLine();
            }
        }
    }

    private static int resolveValueColumn(List<String> header, TimeSeriesMeasurementTab.TrackTableValue value)
        throws IOException {
        String key = value.key;
        if ("volume_cal3".equals(key)) {
            return requireFirst(header, key, name -> name.startsWith("volume_")
                && !"volume_vox".equals(name)
                && !name.startsWith("volume_from_")
                && !name.startsWith("volume_to_"));
        }
        if ("volume_vox".equals(key)) return requireExact(header, key);
        if ("surface_area".equals(key)) return requireFirst(header, key, name -> name.startsWith("surface_area_"));
        if ("sphericity".equals(key)) return requireExact(header, key);
        if ("integrated_intensity".equals(key)) return requireExact(header, key);
        if ("mean_intensity".equals(key)) return requireExact(header, key);
        if ("max_intensity".equals(key)) return requireExact(header, key);
        if ("centroid_x".equals(key)) return requireFirst(header, key, name -> name.startsWith("centroid_x_"));
        if ("centroid_y".equals(key)) return requireFirst(header, key, name -> name.startsWith("centroid_y_"));
        if ("centroid_z".equals(key)) return requireFirst(header, key, name -> name.startsWith("centroid_z_"));
        if ("max_feret3d".equals(key)) return requireFirst(header, key, name -> name.startsWith("max_feret3d_"));
        throw new IOException("Unsupported track-table value: " + key);
    }

    private static int requireExact(List<String> header, String name) throws IOException {
        int idx = indexOf(header, name);
        if (idx >= 0) return idx;
        throw new IOException("Measurement CSV has no " + name + " column.");
    }

    private static int requireFirst(List<String> header, String label, ColumnMatcher matcher) throws IOException {
        for (int i = 0; i < header.size(); i++) {
            if (matcher.matches(header.get(i))) return i;
        }
        throw new IOException("Measurement CSV has no " + label + " column.");
    }

    private static int indexOf(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (name.equals(header.get(i))) return i;
        }
        return -1;
    }

    private static int parseTime(String text, int rowNumber) throws IOException {
        try {
            int t = Integer.parseInt(text.trim());
            if (t > 0) return t;
        } catch (NumberFormatException ignored) {
        }
        throw new IOException("Invalid t value at CSV row " + rowNumber + ": " + text);
    }

    private static String cell(List<String> row, int index) {
        return index >= 0 && index < row.size() ? row.get(index) : "";
    }

    private static String parentPath(String unitName) {
        String normalized = unitName.replace('\\', '/');
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int slash = normalized.lastIndexOf('/');
        return slash > 0 ? normalized.substring(0, slash) : "(root)";
    }

    private static String csvCell(String value) {
        String safe = value != null ? value : "";
        if (safe.indexOf(',') < 0 && safe.indexOf('"') < 0 && safe.indexOf('\n') < 0 && safe.indexOf('\r') < 0) {
            return safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private interface ColumnMatcher {
        boolean matches(String name);
    }

    private static final class Cell {
        final String unitName;
        final String value;

        Cell(String unitName, String value) {
            this.unitName = unitName;
            this.value = value;
        }
    }
}
