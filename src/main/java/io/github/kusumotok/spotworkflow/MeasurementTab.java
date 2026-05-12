package io.github.kusumotok.spotworkflow;

import io.github.kusumotok.roiexplorer.service.RoiExplorerFacade.MeasurementRequest;
import io.github.kusumotok.roiexplorer.service.measure.MeasurementColumn;
import io.github.kusumotok.roiexplorer.service.measure.XyzObjectProfile;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class MeasurementTab extends JPanel {

    final JButton btnMeasure = new JButton("Measure");

    // ── Output ────────────────────────────────────────────────────────
    private final JCheckBox saveCsvCheck    = new JCheckBox("Save CSV to result folder", true);
    private final JCheckBox showTableCheck  = new JCheckBox("Show ResultsTable",          false);

    // ── Columns ───────────────────────────────────────────────────────
    // LinkedHashMap preserves display order
    private final Map<MeasurementColumn, JCheckBox> columnChecks = new LinkedHashMap<>();

    public MeasurementTab() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(buildOutputPanel());
        add(Box.createVerticalStrut(8));
        add(buildColumnsPanel());
        add(Box.createVerticalStrut(8));
    }

    // ── Panel builders ────────────────────────────────────────────────

    private JPanel buildOutputPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(), "Output"));
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
        p.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(), "Output columns (XYZ Object)"));
        p.setAlignmentX(LEFT_ALIGNMENT);

        JLabel always = new JLabel("Always: spot_id, unit_name, c, t");
        always.setFont(always.getFont().deriveFont(Font.ITALIC, 11f));
        always.setForeground(Color.GRAY);
        always.setAlignmentX(LEFT_ALIGNMENT);
        p.add(always);
        p.add(Box.createVerticalStrut(4));

        // Build checkboxes grouped logically
        Object[][] groups = {
            {"Volume",     new MeasurementColumn[]{MeasurementColumn.VOLUME_CAL3, MeasurementColumn.VOLUME_VOX}},
            {"Surface",    new MeasurementColumn[]{MeasurementColumn.SURFACE_AREA, MeasurementColumn.SPHERICITY}},
            {"Intensity",  new MeasurementColumn[]{MeasurementColumn.INTEGRATED_INTENSITY, MeasurementColumn.MEAN_INTENSITY, MeasurementColumn.MAX_INTENSITY}},
            {"Position",   new MeasurementColumn[]{MeasurementColumn.CENTROID, MeasurementColumn.MAX_FERET3D, MeasurementColumn.FERET_ENDPOINTS}},
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

        // Select all / none
        JPanel ctrlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        ctrlRow.setAlignmentX(LEFT_ALIGNMENT);
        JButton btnAll  = new JButton("All");
        JButton btnNone = new JButton("None");
        btnAll.addActionListener(e  -> columnChecks.values().forEach(cb -> cb.setSelected(true)));
        btnNone.addActionListener(e -> columnChecks.values().forEach(cb -> cb.setSelected(false)));
        ctrlRow.add(new JLabel("Select:"));
        ctrlRow.add(btnAll);
        ctrlRow.add(btnNone);
        p.add(Box.createVerticalStrut(2));
        p.add(ctrlRow);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));

        return p;
    }

    // ── Public API ────────────────────────────────────────────────────

    /** Updates the CSV output path display. Call when the result folder changes. */
    public void setOutputFolder(Path resultFolder) {
        // Kept as a stable API for WorkflowWindow; the default CSV path is project/measurement.csv.
    }

    public MeasurementRequest buildRequest(Path csvOutputPath) {
        Set<MeasurementColumn> enabled = EnumSet.noneOf(MeasurementColumn.class);
        for (Map.Entry<MeasurementColumn, JCheckBox> entry : columnChecks.entrySet()) {
            if (entry.getValue().isSelected()) enabled.add(entry.getKey());
        }
        if (enabled.isEmpty()) enabled = MeasurementColumn.allEnabled(); // safety fallback

        // Pass enabled columns to profile so computation is also skipped for disabled columns
        MeasurementRequest req = MeasurementRequest
            .useProfile(new XyzObjectProfile(enabled))
            .withEnabledColumns(enabled)
            .withShowResultsTable(showTableCheck.isSelected())
            .withMeasureAll(true);
        if (saveCsvCheck.isSelected() && csvOutputPath != null) {
            req = req.withCsvOutput(csvOutputPath);
        }
        return req;
    }
}
