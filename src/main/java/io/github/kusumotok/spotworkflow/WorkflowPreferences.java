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
    static final String TIME_SERIES_PREFIX = "spotquant.timeWorkflow.";

    int preferredChannel = 1;
    final SegmentationParams params = new SegmentationParams();
    final PreviewSettings seedPreview = new PreviewSettings();
    final PreviewSettings areaPreview = new PreviewSettings();
    boolean measurementSaveCsv = true;
    boolean measurementShowTable = false;
    boolean measurementTrackTableCsv = false;
    String measurementTrackTableValue = "volume_cal3";
    Set<MeasurementColumn> measurementColumns = MeasurementColumn.allEnabled();

    static WorkflowPreferences load() {
        return loadWithPrefix(PREFIX);
    }

    static WorkflowPreferences loadWithPrefix(String rootPrefix) {
        WorkflowPreferences prefs = new WorkflowPreferences();
        prefs.preferredChannel = getInt(rootPrefix, "channel", 1);
        prefs.params.seedThreshold = getInt(rootPrefix, "seedThreshold", prefs.params.seedThreshold);
        prefs.params.areaThreshold = getInt(rootPrefix, "areaThreshold", prefs.params.areaThreshold);
        prefs.params.areaEnabled = Prefs.get(key(rootPrefix, "areaEnabled"), prefs.params.areaEnabled);
        prefs.params.minVolUm3 = getOptionalDouble(rootPrefix, "minVolUm3");
        prefs.params.maxVolUm3 = getOptionalDouble(rootPrefix, "maxVolUm3");
        prefs.params.connectivity = getInt(rootPrefix, "connectivity", prefs.params.connectivity);
        prefs.params.fillHoles = Prefs.get(key(rootPrefix, "fillHoles"), prefs.params.fillHoles);
        prefs.params.saveMode = parseSaveMode(Prefs.get(key(rootPrefix, "saveMode"), prefs.params.saveMode.name()));
        prefs.params.resultFolderPattern = Prefs.get(key(rootPrefix, "resultFolderPattern"), prefs.params.resultFolderPattern);
        prefs.seedPreview.load(rootPrefix, "seedPreview.");
        prefs.areaPreview.load(rootPrefix, "areaPreview.");
        prefs.measurementSaveCsv = Prefs.get(key(rootPrefix, "measurement.saveCsv"), prefs.measurementSaveCsv);
        prefs.measurementShowTable = Prefs.get(key(rootPrefix, "measurement.showTable"), prefs.measurementShowTable);
        prefs.measurementTrackTableCsv = Prefs.get(key(rootPrefix, "measurement.trackTableCsv"), prefs.measurementTrackTableCsv);
        prefs.measurementTrackTableValue = Prefs.get(key(rootPrefix, "measurement.trackTableValue"), prefs.measurementTrackTableValue);
        prefs.measurementColumns = parseColumns(Prefs.get(key(rootPrefix, "measurement.columns"), ""));
        return prefs;
    }

    void save() {
        saveWithPrefix(PREFIX);
    }

    void saveWithPrefix(String rootPrefix) {
        setInt(rootPrefix, "channel", preferredChannel);
        setInt(rootPrefix, "seedThreshold", params.seedThreshold);
        setInt(rootPrefix, "areaThreshold", params.areaThreshold);
        Prefs.set(key(rootPrefix, "areaEnabled"), params.areaEnabled);
        setOptionalDouble(rootPrefix, "minVolUm3", params.minVolUm3);
        setOptionalDouble(rootPrefix, "maxVolUm3", params.maxVolUm3);
        setInt(rootPrefix, "connectivity", params.connectivity);
        Prefs.set(key(rootPrefix, "fillHoles"), params.fillHoles);
        Prefs.set(key(rootPrefix, "saveMode"), params.saveMode != null ? params.saveMode.name() : SaveMode.FOLDER.name());
        Prefs.set(key(rootPrefix, "resultFolderPattern"), params.resultFolderPattern != null ? params.resultFolderPattern : "{name} result");
        seedPreview.save(rootPrefix, "seedPreview.");
        areaPreview.save(rootPrefix, "areaPreview.");
        Prefs.set(key(rootPrefix, "measurement.saveCsv"), measurementSaveCsv);
        Prefs.set(key(rootPrefix, "measurement.showTable"), measurementShowTable);
        Prefs.set(key(rootPrefix, "measurement.trackTableCsv"), measurementTrackTableCsv);
        Prefs.set(key(rootPrefix, "measurement.trackTableValue"), measurementTrackTableValue != null ? measurementTrackTableValue : "volume_cal3");
        Prefs.set(key(rootPrefix, "measurement.columns"), formatColumns(measurementColumns));
        Prefs.savePreferences();
    }

    private static String key(String name) {
        return key(PREFIX, name);
    }

    private static String key(String rootPrefix, String name) {
        return rootPrefix + name;
    }

    private static int getInt(String name, int defaultValue) {
        return getInt(PREFIX, name, defaultValue);
    }

    private static int getInt(String rootPrefix, String name, int defaultValue) {
        return (int) Math.round(Prefs.get(key(rootPrefix, name), defaultValue));
    }

    private static void setInt(String name, int value) {
        setInt(PREFIX, name, value);
    }

    private static void setInt(String rootPrefix, String name, int value) {
        Prefs.set(key(rootPrefix, name), value);
    }

    private static Double getOptionalDouble(String name) {
        return getOptionalDouble(PREFIX, name);
    }

    private static Double getOptionalDouble(String rootPrefix, String name) {
        String raw = Prefs.get(key(rootPrefix, name), "");
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return Double.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void setOptionalDouble(String name, Double value) {
        setOptionalDouble(PREFIX, name, value);
    }

    private static void setOptionalDouble(String rootPrefix, String name, Double value) {
        Prefs.set(key(rootPrefix, name), value != null ? String.valueOf(value) : "");
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
            load(PREFIX, prefix);
        }

        void load(String rootPrefix, String prefix) {
            showTinyFilteredOutRois = Prefs.get(key(rootPrefix, prefix + "showTinyFilteredOutRois"), showTinyFilteredOutRois);
            noiseVolume = Prefs.get(key(rootPrefix, prefix + "noiseVolume"), noiseVolume);
            showRejected = Prefs.get(key(rootPrefix, prefix + "showRejected"), showRejected);
            seedColor = new Color(getInt(rootPrefix, prefix + "seedColor", seedColor.getRGB()), true);
            resultColor = new Color(getInt(rootPrefix, prefix + "resultColor", resultColor.getRGB()), true);
        }

        void save(String prefix) {
            save(PREFIX, prefix);
        }

        void save(String rootPrefix, String prefix) {
            Prefs.set(key(rootPrefix, prefix + "showTinyFilteredOutRois"), showTinyFilteredOutRois);
            Prefs.set(key(rootPrefix, prefix + "noiseVolume"), noiseVolume);
            Prefs.set(key(rootPrefix, prefix + "showRejected"), showRejected);
            setInt(rootPrefix, prefix + "seedColor", seedColor.getRGB());
            setInt(rootPrefix, prefix + "resultColor", resultColor.getRGB());
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
