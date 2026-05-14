package io.github.kusumotok.spotworkflow;

import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.plugin.Duplicator;
import ij.plugin.ZProjector;
import ij.process.ImageProcessor;
import io.github.kusumotok.roiexplorer.service.RoiExplorerFacade.MeasurementRequest;
import io.github.kusumotok.roiexplorer.service.RoiExplorerFacade.MeasurementResult;
import io.github.kusumotok.roiexplorer.ui.RoiExplorerPanel;
import io.github.kusumotok.spotworkflow.core.roi.SeedRoiReader;
import io.github.kusumotok.spotworkflow.save.ResultFolderService;
import io.github.kusumotok.spotworkflow.save.SegmentationParams;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;

public final class TimeSeriesWorkflowWindow extends JFrame {

    private static TimeSeriesWorkflowWindow instance;

    private final JComboBox<String> imageCombo = new JComboBox<>();
    private final JButton btnRefresh = new JButton("⟳");
    private final JSpinner channelSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 1, 1));
    private final JComboBox<String> zprojCombo = new JComboBox<>();
    private final JButton zprojBtn = new JButton("Max Proj");
    private final JTextField projectField = new JTextField();
    private final JButton btnLoadProject = new JButton("Load Project...");
    private final JButton btnShowInFinder = new JButton("Show in Explorer");
    private final JLabel statusLabel = new JLabel("Ready.");
    private final JTabbedPane tabs = new JTabbedPane();
    private final SegmentationTab seedTab = new SegmentationTab(SegmentationTab.Mode.SEED);
    private final SegmentationTab areaTab = new SegmentationTab(SegmentationTab.Mode.AREA_RESULT);
    private final MeasurementTab measurementTab = new MeasurementTab();
    private final RoiExplorerPanel seedRoiPanel = new RoiExplorerPanel();
    private final RoiExplorerPanel resultMeasurePanel = new RoiExplorerPanel();
    private final MeasurementController measureCtrl = new MeasurementController(resultMeasurePanel);
    private final TimeSeriesSegmentationController segmentationCtrl = new TimeSeriesSegmentationController();
    private final TimeSeriesTrackController trackCtrl = new TimeSeriesTrackController();
    private final ResultFolderService folderService = new ResultFolderService();

    private ImagePlus boundImage;
    private ImagePlus zprojImage;
    private Path projectFolder;
    private boolean comboSyncing;
    private boolean channelSyncing;
    private int preferredChannel = 1;
    private int previousTabIndex = 0;

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
        refreshZProjCombo();
        ImagePlus active = WindowManager.getCurrentImage();
        if (active != null) bindImage(active);
    }

    private void buildUI() {
        setLayout(new BorderLayout(4, 4));
        add(buildHeader(), BorderLayout.NORTH);
        tabs.addTab("Seed", new JScrollPane(seedTab));
        tabs.addTab("Seed Edit", buildRoiEditTab(seedRoiPanel));
        tabs.addTab("Seed Track", buildTrackTab());
        tabs.addTab("Area / Result", new JScrollPane(areaTab));
        tabs.addTab("Measurement", new JScrollPane(measurementTab));
        add(tabs, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        seedTab.btnMakeSeedRoi.addActionListener(e -> cmdMakeSeedRois());
        areaTab.btnApply.addActionListener(e -> cmdAreaPreview());
        areaTab.btnMakeResultRoi.addActionListener(e -> cmdMakeResultRois());
        measurementTab.btnMeasure.addActionListener(e -> cmdMeasure());
        btnLoadProject.addActionListener(e -> cmdLoadProject());
        btnShowInFinder.addActionListener(e -> cmdShowInFinder());
        tabs.addChangeListener(e -> {
            int current = tabs.getSelectedIndex();
            applyPreviewPolicy(current);
            previousTabIndex = current;
        });
        applyPreviewPolicy(0);
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
        btnRefresh.setToolTipText("Refresh image list");
        btnRefresh.setMargin(new Insets(2, 4, 2, 4));
        btnRefresh.addActionListener(e -> {
            refreshImageCombo();
            refreshZProjCombo();
        });
        zprojCombo.addItem("None");
        zprojCombo.addActionListener(e -> {
            Object selected = zprojCombo.getSelectedItem();
            zprojImage = selected instanceof String && !"None".equals(selected)
                ? WindowManager.getImage((String) selected)
                : null;
            syncSharedParamsToTabs();
        });
        zprojBtn.addActionListener(e -> cmdCreateMaxProj());
        channelSpinner.addChangeListener(e -> {
            if (channelSyncing) return;
            preferredChannel = (Integer) channelSpinner.getValue();
            syncSharedParamsToTabs();
        });
        projectField.setEditable(false);

        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0; p.add(new JLabel("Image:"), gc);
        gc.gridx = 1; gc.weightx = 1; p.add(imageCombo, gc);
        gc.gridx = 2; gc.weightx = 0; p.add(btnRefresh, gc);
        gc.gridx = 3; p.add(new JLabel("Ch:"), gc);
        gc.gridx = 4; p.add(channelSpinner, gc);

        gc.gridx = 0; gc.gridy = 1; gc.gridwidth = 1; p.add(new JLabel("Z-proj:"), gc);
        gc.gridx = 1; gc.weightx = 1; p.add(zprojCombo, gc);
        gc.gridx = 2; gc.weightx = 0; gc.gridwidth = 3; p.add(zprojBtn, gc);
        gc.gridwidth = 1;

        gc.gridx = 0; gc.gridy = 2; p.add(new JLabel("Project:"), gc);
        gc.gridx = 1; gc.weightx = 1; p.add(projectField, gc);
        gc.gridx = 2; gc.weightx = 0; p.add(btnLoadProject, gc);
        gc.gridx = 3; gc.gridwidth = 2; p.add(btnShowInFinder, gc);
        return p;
    }

    private void applyPreviewPolicy(int current) {
        seedRoiPanel.setOverlayEnabled(current == 1);
        seedTab.setPreviewActive(current == 0, current == 0);
        areaTab.setPreviewActive(current == 3, current == 3);
        if (current == 1) {
            seedTab.clearOverlayOnly();
            areaTab.clearOverlayOnly();
            syncSeedEditSubImage();
            if (seedRoiPanel.hasLoadedRoot()) seedRoiPanel.refreshOverlay();
        } else if (previousTabIndex == 1) {
            seedRoiPanel.cleanupPreview();
        }
        if (current == 3) {
            seedTab.clearOverlayOnly();
        }
    }

    private void syncSharedParamsToTabs() {
        int ch = (Integer) channelSpinner.getValue();
        String zproj = (String) zprojCombo.getSelectedItem();
        seedTab.setExternalChannel(ch);
        areaTab.setExternalChannel(ch);
        seedTab.setExternalZProjTitle(zproj);
        areaTab.setExternalZProjTitle(zproj);
        syncSeedEditSubImage();
    }

    private void syncSeedEditSubImage() {
        if (boundImage == null || zprojImage == null || zprojImage == boundImage) {
            seedRoiPanel.clearSubBindImage();
        } else {
            seedRoiPanel.setSubBindImage(zprojImage);
        }
    }

    private JPanel buildRoiEditTab(RoiExplorerPanel panel) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        p.add(panel, BorderLayout.CENTER);
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

    private void refreshZProjCombo() {
        Object selected = zprojCombo.getSelectedItem();
        zprojCombo.removeAllItems();
        zprojCombo.addItem("None");
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus image = WindowManager.getImage(id);
                if (image != null && image != boundImage) zprojCombo.addItem(image.getTitle());
            }
        }
        if (selected != null) zprojCombo.setSelectedItem(selected);
    }

    private void bindImage(ImagePlus image) {
        boundImage = image;
        int nCh = image != null ? Math.max(1, image.getNChannels()) : 1;
        int safeChannel = Math.max(1, Math.min(preferredChannel, nCh));
        channelSyncing = true;
        ((SpinnerNumberModel) channelSpinner.getModel()).setMaximum(nCh);
        channelSpinner.setValue(safeChannel);
        channelSyncing = false;
        preferredChannel = safeChannel;
        seedTab.updateImage(image);
        areaTab.updateImage(image);
        if (image != null) {
            seedRoiPanel.setBindImage(image);
            seedRoiPanel.setContainerOrMode(true);
            seedRoiPanel.setProjectionMode(true, false, false);
        }
        refreshZProjCombo();
        syncSharedParamsToTabs();
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
                    openSeedRoiRoot(root);
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

    private void cmdAreaPreview() {
        if (boundImage == null || projectFolder == null) {
            setStatus("Run Seed Track first.");
            return;
        }
        SegmentationParams params = areaTab.getParams();
        try {
            areaTab.applyPreviewFromSeedLabelProvider(t -> {
                try {
                    return segmentationCtrl.readSeedTrackLabelsForTime(boundImage, projectFolder, params.channel, t);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "seedTracks:" + projectFolder.toAbsolutePath());
            setStatus("Area preview uses seed tracks across all T.");
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            setStatus("Area preview error: " + cause.getMessage());
        }
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
        setStatus("Measuring XYZT track comparison: " + resultRoot.getFileName());
        MeasurementRequest baseRequest = MeasurementRequest
            .useProfile(new io.github.kusumotok.roiexplorer.service.measure.XyztTrackComparisonProfile(measurementTab.getSelectedColumns()))
            .withEnabledColumns(measurementTab.getSelectedColumns())
            .withShowResultsTable(measurementTab.isShowTableSelected())
            .withMeasureAll(true);
        final MeasurementRequest request = measurementTab.isSaveCsvSelected()
            ? baseRequest.withCsvOutput(csvPath)
            : baseRequest;
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

    private void openSeedRoiRoot(Path root) {
        if (root == null || !Files.isDirectory(root) || boundImage == null) return;
        seedRoiPanel.setBindImage(boundImage);
        seedRoiPanel.setContainerOrMode(true);
        seedRoiPanel.setProjectionMode(true, false, false);
        seedRoiPanel.openFolder(root);
    }

    private void cmdLoadProject() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        projectFolder = chooser.getSelectedFile().toPath();
        projectField.setText(projectFolder.toString());
        projectField.setToolTipText(projectFolder.toString());
        openSeedRoiRoot(projectFolder.resolve("seed_rois_untracked"));
        refreshMeasurementResultFolders(null);
        setStatus("Loaded project: " + projectFolder);
    }

    private void cmdShowInFinder() {
        if (projectFolder == null) return;
        try {
            Desktop.getDesktop().open(projectFolder.toFile());
        } catch (Exception e) {
            setStatus("Could not open folder: " + e.getMessage());
        }
    }

    private void cmdCreateMaxProj() {
        if (boundImage == null) return;
        try {
            zprojImage = createMaxZProjectionPreserveT(boundImage);
            if (zprojImage != null) {
                zprojImage.setTitle(boundImage.getShortTitle() + "-MAX");
                zprojImage.show();
                refreshZProjCombo();
                zprojCombo.setSelectedItem(zprojImage.getTitle());
            }
        } catch (Exception e) {
            setStatus("Could not create Z projection: " + e.getMessage());
        }
    }

    private static ImagePlus createMaxZProjectionPreserveT(ImagePlus image) {
        int nC = Math.max(1, image.getNChannels());
        int nT = Math.max(1, image.getNFrames());
        ImageStack out = new ImageStack(image.getWidth(), image.getHeight());
        for (int t = 1; t <= nT; t++) {
            for (int c = 1; c <= nC; c++) {
                ImagePlus sub = new Duplicator().run(image, c, c, 1, image.getNSlices(), t, t);
                ImagePlus projected = ZProjector.run(sub, "max");
                ImageProcessor ip = projected.getProcessor().duplicate();
                out.addSlice("c" + c + "-t" + t, ip);
                sub.flush();
                projected.flush();
            }
        }
        ImagePlus result = new ImagePlus(image.getShortTitle() + "-MAX", out);
        result.setCalibration(image.getCalibration());
        result.setDimensions(nC, 1, nT);
        if (nC > 1 || nT > 1) result.setOpenAsHyperStack(true);
        return result;
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
            String pattern = seedTab.getParams().resultFolderPattern;
            if (pattern == null || pattern.trim().isEmpty()) pattern = "{name} result";
            String imageName = boundImage.getTitle().replaceAll("\\.[^.]+$", "");
            String name = pattern.replace("{name}", imageName);
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
