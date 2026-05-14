package io.github.kusumotok.spotworkflow;

import ij.ImagePlus;
import ij.WindowManager;
import io.github.kusumotok.roiexplorer.service.RoiExplorerFacade.MeasurementRequest;
import io.github.kusumotok.roiexplorer.service.RoiExplorerFacade.MeasurementResult;
import io.github.kusumotok.roiexplorer.ui.RoiExplorerPanel;
import io.github.kusumotok.spotworkflow.save.ResultFolderService;
import io.github.kusumotok.spotworkflow.save.SegmentationParams;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;

public final class TimeSeriesWorkflowWindow extends JFrame {

    private static TimeSeriesWorkflowWindow instance;

    private final JComboBox<String> imageCombo = new JComboBox<>();
    private final JTextField projectField = new JTextField();
    private final JLabel statusLabel = new JLabel("Ready.");
    private final JTabbedPane tabs = new JTabbedPane();
    private final SegmentationTab seedTab = new SegmentationTab(SegmentationTab.Mode.SEED);
    private final SegmentationTab areaTab = new SegmentationTab(SegmentationTab.Mode.AREA_RESULT);
    private final TimeSeriesMeasurementTab measurementTab = new TimeSeriesMeasurementTab();
    private final RoiExplorerPanel resultMeasurePanel = new RoiExplorerPanel();
    private final MeasurementController measureCtrl = new MeasurementController(resultMeasurePanel);
    private final TimeSeriesSegmentationController segmentationCtrl = new TimeSeriesSegmentationController();
    private final TimeSeriesTrackController trackCtrl = new TimeSeriesTrackController();
    private final ResultFolderService folderService = new ResultFolderService();

    private ImagePlus boundImage;
    private Path projectFolder;
    private boolean comboSyncing;

    public static synchronized TimeSeriesWorkflowWindow getInstance() {
        if (instance == null) instance = new TimeSeriesWorkflowWindow();
        return instance;
    }

    private TimeSeriesWorkflowWindow() {
        super("Spot Quantifier Time Series");
        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        setSize(760, 760);
        buildUI();
        refreshImageCombo();
        ImagePlus active = WindowManager.getCurrentImage();
        if (active != null) bindImage(active);
    }

    private void buildUI() {
        setLayout(new BorderLayout(4, 4));
        add(buildHeader(), BorderLayout.NORTH);
        tabs.addTab("Seed", new JScrollPane(seedTab));
        tabs.addTab("Seed Edit", placeholder("Seed Edit will edit seed_rois_untracked/t### per timepoint."));
        tabs.addTab("Seed Track", buildTrackTab());
        tabs.addTab("Area / Result", new JScrollPane(areaTab));
        tabs.addTab("Measurement", new JScrollPane(measurementTab));
        add(tabs, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        seedTab.btnMakeSeedRoi.addActionListener(e -> cmdMakeSeedRois());
        areaTab.btnMakeResultRoi.addActionListener(e -> cmdMakeResultRois());
        measurementTab.btnMeasure.addActionListener(e -> cmdMeasure());
    }

    private JComponent buildTrackTab() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        JButton btnAutoTrack = new JButton("Auto Link Seed Tracks");
        btnAutoTrack.addActionListener(e -> cmdAutoTrackSeeds());
        p.add(btnAutoTrack);
        p.add(new JLabel("MVP: nearest-centroid adjacent-T linking; manual correction UI is not implemented yet."));
        return p;
    }

    private JPanel buildHeader() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(6, 8, 2, 8));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 3, 2, 3);
        gc.fill = GridBagConstraints.HORIZONTAL;

        imageCombo.addActionListener(e -> {
            if (comboSyncing) return;
            String title = (String) imageCombo.getSelectedItem();
            bindImage(WindowManager.getImage(title));
        });
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refreshImageCombo());
        projectField.setEditable(false);

        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0; p.add(new JLabel("Image:"), gc);
        gc.gridx = 1; gc.weightx = 1; p.add(imageCombo, gc);
        gc.gridx = 2; gc.weightx = 0; p.add(refresh, gc);
        gc.gridx = 0; gc.gridy = 1; p.add(new JLabel("Project:"), gc);
        gc.gridx = 1; gc.gridwidth = 2; gc.weightx = 1; p.add(projectField, gc);
        return p;
    }

    private JPanel buildFooter() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(3, 8, 6, 8));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
        p.add(statusLabel, BorderLayout.WEST);
        return p;
    }

    private static JComponent placeholder(String text) {
        JPanel p = new JPanel(new BorderLayout());
        JLabel label = new JLabel(text);
        label.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        p.add(label, BorderLayout.NORTH);
        return p;
    }

    private void refreshImageCombo() {
        Object selected = imageCombo.getSelectedItem();
        comboSyncing = true;
        imageCombo.removeAllItems();
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus image = WindowManager.getImage(id);
                if (image != null) imageCombo.addItem(image.getTitle());
            }
        }
        if (selected != null) imageCombo.setSelectedItem(selected);
        comboSyncing = false;
    }

    private void bindImage(ImagePlus image) {
        boundImage = image;
        seedTab.updateImage(image);
        areaTab.updateImage(image);
        if (image == null) {
            setStatus("No image selected.");
        } else if (image.getNFrames() <= 1) {
            setStatus("Time Series workflow requires T > 1.");
        } else {
            setStatus("Bound " + image.getTitle() + " (" + image.getNFrames() + " frames).");
        }
    }

    private void cmdMakeSeedRois() {
        if (boundImage == null) {
            setStatus("Select an image first.");
            return;
        }
        if (boundImage.getNFrames() <= 1) {
            setStatus("Time Series workflow requires T > 1.");
            return;
        }
        Path project = ensureProjectFolder();
        if (project == null) return;
        SegmentationParams params = seedTab.getParams();
        new SwingWorker<Path, String>() {
            @Override protected Path doInBackground() throws Exception {
                return segmentationCtrl.makeUntrackedSeedRois(boundImage, params, project, this::publish);
            }
            @Override protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) setStatus(chunks.get(chunks.size() - 1));
            }
            @Override protected void done() {
                try {
                    Path root = get();
                    setStatus("Untracked seed ROI saved: " + root);
                    tabs.setSelectedIndex(1);
                } catch (CancellationException e) {
                    setStatus("Cancelled.");
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    setStatus("Error: " + cause.getMessage());
                    JOptionPane.showMessageDialog(TimeSeriesWorkflowWindow.this,
                        cause.getMessage(), "Time Series Seed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void cmdAutoTrackSeeds() {
        if (projectFolder == null) {
            setStatus("Run Seed first.");
            return;
        }
        new SwingWorker<Path, String>() {
            @Override protected Path doInBackground() throws Exception {
                return trackCtrl.buildTracks(projectFolder, this::publish);
            }
            @Override protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) setStatus(chunks.get(chunks.size() - 1));
            }
            @Override protected void done() {
                try {
                    Path root = get();
                    setStatus("Seed tracks saved: " + root);
                    tabs.setSelectedIndex(3);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    setStatus("Track error: " + cause.getMessage());
                    JOptionPane.showMessageDialog(TimeSeriesWorkflowWindow.this,
                        cause.getMessage(), "Seed Track", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void cmdMakeResultRois() {
        if (boundImage == null || projectFolder == null) {
            setStatus("Run Seed and Seed Track first.");
            return;
        }
        SegmentationParams params = areaTab.getParams();
        new SwingWorker<Path, String>() {
            @Override protected Path doInBackground() throws Exception {
                return segmentationCtrl.makeResultFromSeedTracks(boundImage, params, projectFolder, this::publish);
            }
            @Override protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) setStatus(chunks.get(chunks.size() - 1));
            }
            @Override protected void done() {
                try {
                    Path root = get();
                    setStatus("Time-series result ROI saved: " + root);
                    refreshMeasurementResultFolders(root);
                    tabs.setSelectedIndex(4);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    setStatus("Area result error: " + cause.getMessage());
                    JOptionPane.showMessageDialog(TimeSeriesWorkflowWindow.this,
                        cause.getMessage(), "Area / Result", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void cmdMeasure() {
        Path resultRoot = measurementTab.getSelectedResultRoiFolder();
        if (projectFolder == null || resultRoot == null) {
            setStatus("Select a result ROI first.");
            return;
        }
        resultMeasurePanel.setBindImage(boundImage);
        resultMeasurePanel.setContainerOrMode(true);
        resultMeasurePanel.openFolder(resultRoot);
        Path csvPath = timeSeriesMeasurementCsv(projectFolder, resultRoot);
        MeasurementRequest request = measurementTab.buildRequest(csvPath);
        new SwingWorker<MeasurementResult, Void>() {
            @Override protected MeasurementResult doInBackground() {
                return measureCtrl.measure(request);
            }
            @Override protected void done() {
                try {
                    MeasurementResult result = get();
                    setStatus(result.isPerformed()
                        ? "Saved " + csvPath.getFileName() + ". " + result.getMessage()
                        : "Measurement skipped: " + result.getMessage());
                } catch (Exception e) {
                    setStatus("Measurement error: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void refreshMeasurementResultFolders(Path selectedRoot) {
        java.util.List<Path> roots = new java.util.ArrayList<>();
        if (projectFolder != null && java.nio.file.Files.isDirectory(projectFolder)) {
            try (java.nio.file.DirectoryStream<Path> stream =
                     java.nio.file.Files.newDirectoryStream(projectFolder, "result_rois_area-*")) {
                for (Path root : stream) if (java.nio.file.Files.isDirectory(root)) roots.add(root);
            } catch (Exception ignored) {}
        }
        roots.sort((a, b) -> Integer.compare(areaSortKey(a), areaSortKey(b)));
        measurementTab.setResultRoiFolders(roots, selectedRoot);
    }

    private static Path timeSeriesMeasurementCsv(Path project, Path resultRoot) {
        String name = resultRoot.getFileName() != null ? resultRoot.getFileName().toString() : "result";
        String prefix = "result_rois_";
        String key = name.startsWith(prefix) ? name.substring(prefix.length()) : name;
        return project.resolve("measurement_" + key + "_xyzt.csv");
    }

    private static int areaSortKey(Path root) {
        String name = root.getFileName() != null ? root.getFileName().toString() : "";
        if ("result_rois_area-disabled".equals(name)) return -1;
        String prefix = "result_rois_area-th";
        if (name.startsWith(prefix)) {
            try { return Integer.parseInt(name.substring(prefix.length())); }
            catch (NumberFormatException ignored) { return Integer.MAX_VALUE; }
        }
        return Integer.MAX_VALUE;
    }

    private Path ensureProjectFolder() {
        if (projectFolder != null) return projectFolder;
        try {
            Path dir = boundImage.getOriginalFileInfo() != null && boundImage.getOriginalFileInfo().directory != null
                ? java.nio.file.Paths.get(boundImage.getOriginalFileInfo().directory)
                : java.nio.file.Paths.get(System.getProperty("user.home"));
            String name = boundImage.getTitle().replaceAll("\\.[^.]+$", "") + " time series result";
            projectFolder = folderService.createResultFolder(dir, name);
            projectField.setText(projectFolder.toString());
            projectField.setToolTipText(projectFolder.toString());
            return projectFolder;
        } catch (Exception e) {
            setStatus("Could not create project folder: " + e.getMessage());
            return null;
        }
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(text));
    }
}
