package io.github.kusumotok.spotworkflow;

import io.github.kusumotok.roiexplorer.service.RoiExplorerFacade.MeasurementRequest;
import io.github.kusumotok.roiexplorer.service.measure.MeasurementColumn;
import io.github.kusumotok.roiexplorer.service.measure.MeasurementTargetMode;
import io.github.kusumotok.roiexplorer.service.measure.RoiCollectionMode;
import io.github.kusumotok.roiexplorer.service.measure.XyzObjectProfile;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class TimeSeriesMeasurementTab extends JPanel {

    final JButton btnMeasure = new JButton("Measure");

    private final JComboBox<ResultFolderItem> resultFolderCombo = new JComboBox<ResultFolderItem>();
    private final JCheckBox saveCsvCheck = new JCheckBox("Save CSV to result folder", true);
    private final JCheckBox showTableCheck = new JCheckBox("Show ResultsTable", false);
    private final Map<MeasurementColumn, JCheckBox> columnChecks = new LinkedHashMap<MeasurementColumn, JCheckBox>();
    private final TitledBorder columnsBorder =
        BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "Output columns (XYZ Object, leaf folders)");

    public TimeSeriesMeasurementTab() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(buildResultFolderPanel());
        add(Box.createVerticalStrut(8));
        add(buildOutputPanel());
        add(Box.createVerticalStrut(8));
        add(buildColumnsPanel());
        add(Box.createVerticalStrut(8));
    }

    private JPanel buildResultFolderPanel() {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "Result ROI"));
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.add(new JLabel("Area threshold:"), BorderLayout.WEST);
        resultFolderCombo.setPrototypeDisplayValue(new ResultFolderItem(Paths.get("result_rois_area-th0000")));
        p.add(resultFolderCombo, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private JPanel buildOutputPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "Output"));
        p.setAlignmentX(LEFT_ALIGNMENT);

        saveCsvCheck.setAlignmentX(LEFT_ALIGNMENT);
        showTableCheck.setAlignmentX(LEFT_ALIGNMENT);
        p.add(saveCsvCheck);
        p.add(showTableCheck);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        actionRow.setAlignmentX(LEFT_ALIGNMENT);
        actionRow.add(btnMeasure);
        p.add(actionRow);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private JPanel buildColumnsPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(columnsBorder);
        p.setAlignmentX(LEFT_ALIGNMENT);

        JLabel always = new JLabel("Always: spot_id, unit_name, c, t");
        always.setFont(always.getFont().deriveFont(Font.ITALIC, 11f));
        always.setForeground(Color.GRAY);
        always.setAlignmentX(LEFT_ALIGNMENT);
        p.add(always);
        p.add(Box.createVerticalStrut(4));

        Object[][] groups = {
            {"Volume", new MeasurementColumn[]{MeasurementColumn.VOLUME_CAL3, MeasurementColumn.VOLUME_VOX}},
            {"Surface", new MeasurementColumn[]{MeasurementColumn.SURFACE_AREA, MeasurementColumn.SPHERICITY}},
            {"Intensity", new MeasurementColumn[]{MeasurementColumn.INTEGRATED_INTENSITY, MeasurementColumn.MEAN_INTENSITY, MeasurementColumn.MAX_INTENSITY}},
            {"Position", new MeasurementColumn[]{MeasurementColumn.CENTROID, MeasurementColumn.MAX_FERET3D, MeasurementColumn.FERET_ENDPOINTS}},
        };

        for (Object[] group : groups) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            row.setAlignmentX(LEFT_ALIGNMENT);
            JLabel grpLabel = new JLabel((String) group[0] + ":");
            grpLabel.setPreferredSize(new Dimension(68, grpLabel.getPreferredSize().height));
            grpLabel.setFont(grpLabel.getFont().deriveFont(Font.BOLD, 11f));
            row.add(grpLabel);
            for (MeasurementColumn col : (MeasurementColumn[]) group[1]) {
                JCheckBox cb = new JCheckBox(col.displayName, true);
                cb.setFont(cb.getFont().deriveFont(Font.PLAIN, 11f));
                columnChecks.put(col, cb);
                row.add(cb);
            }
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            p.add(row);
        }

        JPanel ctrlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        ctrlRow.setAlignmentX(LEFT_ALIGNMENT);
        JButton btnAll = new JButton("All");
        JButton btnNone = new JButton("None");
        btnAll.addActionListener(e -> columnChecks.values().forEach(cb -> cb.setSelected(true)));
        btnNone.addActionListener(e -> columnChecks.values().forEach(cb -> cb.setSelected(false)));
        ctrlRow.add(new JLabel("Select:"));
        ctrlRow.add(btnAll);
        ctrlRow.add(btnNone);
        p.add(Box.createVerticalStrut(2));
        p.add(ctrlRow);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    public void setResultRoiFolders(java.util.List<Path> roots, Path selectedRoot) {
        resultFolderCombo.removeAllItems();
        resultFolderCombo.addItem(new ResultFolderItem(null));
        if (roots != null) {
            for (Path root : roots) resultFolderCombo.addItem(new ResultFolderItem(root));
        }
        if (selectedRoot != null) resultFolderCombo.setSelectedItem(new ResultFolderItem(selectedRoot));
        Path selected = getSelectedResultRoiFolder();
        resultFolderCombo.setToolTipText(selected != null ? selected.toString() : "None");
    }

    public Path getSelectedResultRoiFolder() {
        Object item = resultFolderCombo.getSelectedItem();
        return item instanceof ResultFolderItem ? ((ResultFolderItem) item).path : null;
    }

    public MeasurementRequest buildRequest(Path csvPath) {
        Set<MeasurementColumn> enabled = getSelectedColumns();
        MeasurementRequest request = MeasurementRequest
            .useProfile(new XyzObjectProfile(enabled))
            .withEnabledColumns(enabled)
            .withShowResultsTable(showTableCheck.isSelected())
            .withMeasureAll(true)
            .withTargetMode(MeasurementTargetMode.LEAF_FOLDERS)
            .withCollectionMode(RoiCollectionMode.DIRECT);
        if (saveCsvCheck.isSelected() && csvPath != null) request = request.withCsvOutput(csvPath);
        return request;
    }

    public Set<MeasurementColumn> getSelectedColumns() {
        Set<MeasurementColumn> enabled = EnumSet.noneOf(MeasurementColumn.class);
        for (Map.Entry<MeasurementColumn, JCheckBox> entry : columnChecks.entrySet()) {
            if (entry.getValue().isSelected()) enabled.add(entry.getKey());
        }
        return enabled.isEmpty() ? MeasurementColumn.allEnabled() : enabled;
    }

    public String selectedPresetLabel() {
        return "XYZ Object";
    }

    private static final class ResultFolderItem {
        final Path path;

        ResultFolderItem(Path path) { this.path = path; }

        @Override public String toString() {
            if (path == null || path.getFileName() == null) return "None";
            String name = path.getFileName().toString();
            if ("result_rois_area-disabled".equals(name)) return "disabled";
            if ("result_rois".equals(name)) return "result_rois";
            String prefix = "result_rois_area-th";
            return name.startsWith(prefix) ? name.substring(prefix.length()) : name;
        }

        @Override public boolean equals(Object obj) {
            return obj instanceof ResultFolderItem
                && java.util.Objects.equals(path, ((ResultFolderItem) obj).path);
        }

        @Override public int hashCode() {
            return java.util.Objects.hashCode(path);
        }
    }
}
