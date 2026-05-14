package io.github.kusumotok.spotworkflow;

import ij.Prefs;
import io.github.kusumotok.roiexplorer.service.measure.MeasurementColumn;
import io.github.kusumotok.spotworkflow.save.SaveMode;
import io.github.kusumotok.spotworkflow.save.SegmentationParams;

import java.awt.Color;
import java.util.EnumSet;
import java.util.Set;

final class WorkflowPreferences {
    private static final String PREFIX = "spotquant.workflow.";

    int preferredChannel = 1;
    final SegmentationParams params = new SegmentationParams();
    final PreviewSettings seedPreview = new PreviewSettings();
    final PreviewSettings areaPreview = new PreviewSettings();
    boolean measurementSaveCsv = true;
    boolean measurementShowTable = false;
    Set<MeasurementColumn> measurementColumns = MeasurementColumn.allEnabled();

    static WorkflowPreferences load() {
        WorkflowPreferences prefs = new WorkflowPreferences();
        prefs.preferredChannel = getInt("channel", 1);
        prefs.params.seedThreshold = getInt("seedThreshold", prefs.params.seedThreshold);
        prefs.params.areaThreshold = getInt("areaThreshold", prefs.params.areaThreshold);
        prefs.params.areaEnabled = Prefs.get(key("areaEnabled"), prefs.params.areaEnabled);
        prefs.params.minVolUm3 = getOptionalDouble("minVolUm3");
        prefs.params.maxVolUm3 = getOptionalDouble("maxVolUm3");
        prefs.params.connectivity = getInt("connectivity", prefs.params.connectivity);
        prefs.params.fillHoles = Prefs.get(key("fillHoles"), prefs.params.fillHoles);
        prefs.params.saveMode = parseSaveMode(Prefs.get(key("saveMode"), prefs.params.saveMode.name()));
        prefs.params.resultFolderPattern = Prefs.get(key("resultFolderPattern"), prefs.params.resultFolderPattern);
        prefs.seedPreview.load("seedPreview.");
        prefs.areaPreview.load("areaPreview.");
        prefs.measurementSaveCsv = Prefs.get(key("measurement.saveCsv"), prefs.measurementSaveCsv);
        prefs.measurementShowTable = Prefs.get(key("measurement.showTable"), prefs.measurementShowTable);
        prefs.measurementColumns = parseColumns(Prefs.get(key("measurement.columns"), ""));
        return prefs;
    }

    void save() {
        setInt("channel", preferredChannel);
        setInt("seedThreshold", params.seedThreshold);
        setInt("areaThreshold", params.areaThreshold);
        Prefs.set(key("areaEnabled"), params.areaEnabled);
        setOptionalDouble("minVolUm3", params.minVolUm3);
        setOptionalDouble("maxVolUm3", params.maxVolUm3);
        setInt("connectivity", params.connectivity);
        Prefs.set(key("fillHoles"), params.fillHoles);
        Prefs.set(key("saveMode"), params.saveMode != null ? params.saveMode.name() : SaveMode.FOLDER.name());
        Prefs.set(key("resultFolderPattern"), params.resultFolderPattern != null ? params.resultFolderPattern : "{name} result");
        seedPreview.save("seedPreview.");
        areaPreview.save("areaPreview.");
        Prefs.set(key("measurement.saveCsv"), measurementSaveCsv);
        Prefs.set(key("measurement.showTable"), measurementShowTable);
        Prefs.set(key("measurement.columns"), formatColumns(measurementColumns));
        Prefs.savePreferences();
    }

    private static String key(String name) {
        return PREFIX + name;
    }

    private static int getInt(String name, int defaultValue) {
        return (int) Math.round(Prefs.get(key(name), defaultValue));
    }

    private static void setInt(String name, int value) {
        Prefs.set(key(name), value);
    }

    private static Double getOptionalDouble(String name) {
        String raw = Prefs.get(key(name), "");
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return Double.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void setOptionalDouble(String name, Double value) {
        Prefs.set(key(name), value != null ? String.valueOf(value) : "");
    }

    private static SaveMode parseSaveMode(String value) {
        if (value != null) {
            try {
                return SaveMode.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return SaveMode.FOLDER;
    }

    private static Set<MeasurementColumn> parseColumns(String raw) {
        if (raw == null || raw.trim().isEmpty()) return MeasurementColumn.allEnabled();
        Set<MeasurementColumn> out = EnumSet.noneOf(MeasurementColumn.class);
        for (String token : raw.split(",")) {
            try {
                out.add(MeasurementColumn.valueOf(token.trim()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out.isEmpty() ? MeasurementColumn.allEnabled() : out;
    }

    private static String formatColumns(Set<MeasurementColumn> columns) {
        StringBuilder sb = new StringBuilder();
        Set<MeasurementColumn> safe = columns == null || columns.isEmpty()
            ? MeasurementColumn.allEnabled()
            : columns;
        for (MeasurementColumn col : safe) {
            if (sb.length() > 0) sb.append(',');
            sb.append(col.name());
        }
        return sb.toString();
    }

    static final class PreviewSettings {
        boolean showTinyFilteredOutRois = true;
        double noiseVolume = 0.0;
        boolean showRejected = true;
        Color seedColor = Color.CYAN;
        Color resultColor = Color.YELLOW;

        void load(String prefix) {
            showTinyFilteredOutRois = Prefs.get(key(prefix + "showTinyFilteredOutRois"), showTinyFilteredOutRois);
            noiseVolume = Prefs.get(key(prefix + "noiseVolume"), noiseVolume);
            showRejected = Prefs.get(key(prefix + "showRejected"), showRejected);
            seedColor = new Color(getInt(prefix + "seedColor", seedColor.getRGB()), true);
            resultColor = new Color(getInt(prefix + "resultColor", resultColor.getRGB()), true);
        }

        void save(String prefix) {
            Prefs.set(key(prefix + "showTinyFilteredOutRois"), showTinyFilteredOutRois);
            Prefs.set(key(prefix + "noiseVolume"), noiseVolume);
            Prefs.set(key(prefix + "showRejected"), showRejected);
            setInt(prefix + "seedColor", seedColor.getRGB());
            setInt(prefix + "resultColor", resultColor.getRGB());
        }

        void copyFrom(PreviewSettings other) {
            if (other == null) return;
            showTinyFilteredOutRois = other.showTinyFilteredOutRois;
            noiseVolume = other.noiseVolume;
            showRejected = other.showRejected;
            seedColor = other.seedColor;
            resultColor = other.resultColor;
        }
    }
}
