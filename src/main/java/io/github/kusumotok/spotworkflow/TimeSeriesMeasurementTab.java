package io.github.kusumotok.spotworkflow;

import io.github.kusumotok.roiexplorer.service.RoiExplorerFacade.MeasurementRequest;
import io.github.kusumotok.roiexplorer.service.measure.MeasurementColumn;
import io.github.kusumotok.roiexplorer.service.measure.XyztTrackComparisonProfile;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

public final class TimeSeriesMeasurementTab extends JPanel {

    final JButton btnMeasure = new JButton("Measure XYZT comparison");
    private final JComboBox<ResultFolderItem> resultFolderCombo = new JComboBox<>();
    private final JCheckBox saveCsvCheck = new JCheckBox("Save CSV", true);
    private final JCheckBox showTableCheck = new JCheckBox("Show ImageJ ResultsTable", false);

    public TimeSeriesMeasurementTab() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel resultRow = new JPanel(new BorderLayout(6, 0));
        resultRow.setBorder(BorderFactory.createTitledBorder("Result ROI"));
        resultRow.add(new JLabel("Area threshold:"), BorderLayout.WEST);
        resultRow.add(resultFolderCombo, BorderLayout.CENTER);
        resultRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, resultRow.getPreferredSize().height));
        add(resultRow);
        add(Box.createVerticalStrut(8));

        JPanel output = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        output.setBorder(BorderFactory.createTitledBorder("Output"));
        output.add(saveCsvCheck);
        output.add(showTableCheck);
        output.add(btnMeasure);
        output.setAlignmentX(LEFT_ALIGNMENT);
        output.setMaximumSize(new Dimension(Integer.MAX_VALUE, output.getPreferredSize().height));
        add(output);
    }

    public void setResultRoiFolders(java.util.List<Path> roots, Path selectedRoot) {
        resultFolderCombo.removeAllItems();
        resultFolderCombo.addItem(new ResultFolderItem(null));
        if (roots != null) for (Path root : roots) resultFolderCombo.addItem(new ResultFolderItem(root));
        if (selectedRoot != null) resultFolderCombo.setSelectedItem(new ResultFolderItem(selectedRoot));
    }

    public Path getSelectedResultRoiFolder() {
        Object item = resultFolderCombo.getSelectedItem();
        return item instanceof ResultFolderItem ? ((ResultFolderItem) item).path : null;
    }

    public MeasurementRequest buildRequest(Path csvPath) {
        Set<MeasurementColumn> enabled = EnumSet.allOf(MeasurementColumn.class);
        MeasurementRequest request = MeasurementRequest
            .useProfile(new XyztTrackComparisonProfile(enabled))
            .withEnabledColumns(enabled)
            .withShowResultsTable(showTableCheck.isSelected())
            .withMeasureAll(true);
        if (saveCsvCheck.isSelected()) request = request.withCsvOutput(csvPath);
        return request;
    }

    private static final class ResultFolderItem {
        final Path path;

        ResultFolderItem(Path path) { this.path = path; }

        @Override public String toString() {
            if (path == null || path.getFileName() == null) return "None";
            String name = path.getFileName().toString();
            if ("result_rois_area-disabled".equals(name)) return "disabled";
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
