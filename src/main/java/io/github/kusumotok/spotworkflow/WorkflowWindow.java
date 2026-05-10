package io.github.kusumotok.spotworkflow;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.io.FileInfo;
import io.github.kusumotok.roiexplorer.service.RoiExplorerFacade.MeasurementRequest;
import io.github.kusumotok.roiexplorer.service.RoiExplorerFacade.MeasurementResult;
import io.github.kusumotok.roiexplorer.ui.RoiExplorerPanel;
import io.github.kusumotok.spotworkflow.save.ParameterFileReader;
import io.github.kusumotok.spotworkflow.save.SegmentationParams;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CancellationException;

public final class WorkflowWindow extends JFrame {

    private static WorkflowWindow instance;

    // ── Controllers ───────────────────────────────────────────────────────────
    private final WorkflowController     controller       = new WorkflowController();
    private final SegmentationController segmentationCtrl = new SegmentationController();
    private final RoiExplorerPanel       roiPanel         = new RoiExplorerPanel();
    private final MeasurementController  measureCtrl      = new MeasurementController(roiPanel);
    private final SegmentationTab        segmentationTab  = new SegmentationTab();
    private final MeasurementTab         measurementTab   = new MeasurementTab();
    private final RoiEditTabController   roiEditCtrl;

    // ── Image selector ────────────────────────────────────────────────────────
    private final JComboBox<String> imageCombo   = new JComboBox<>();
    private final JButton           btnRefresh   = new JButton("⟳");
    private boolean                 comboSyncing = false;

    // ── Tabs ──────────────────────────────────────────────────────────────────
    private final JTabbedPane tabs             = new JTabbedPane();
    private       int         previousTabIndex = 0;

    // ── ROI Edit tab info ─────────────────────────────────────────────────────
    private final JLabel  roiEditFolderLabel = new JLabel("No result loaded");
    private final JButton btnReloadRoi       = new JButton("Reload from Disk");

    // ── Action buttons ────────────────────────────────────────────────────────
    private final JButton btnMakeRoi      = new JButton("Make ROI");
    private final JButton btnMeasure      = new JButton("Measure");
    private final JButton btnRunAll       = new JButton("Make ROI & Measure");
    private final JButton btnLoadResult   = new JButton("Load Result…");
    private final JButton btnShowInFinder = new JButton("Show in Explorer");

    // ── Status ────────────────────────────────────────────────────────────────
    private final JLabel statusLabel = new JLabel("Ready");

    public static synchronized WorkflowWindow getInstance() {
        if (instance == null || !instance.isDisplayable()) {
            instance = new WorkflowWindow();
        }
        return instance;
    }

    private WorkflowWindow() {
        super("Spot Quantifier Workflow");
        roiEditCtrl = new RoiEditTabController(controller, this, roiPanel);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        buildUI();
        wireController();
        updateButtonStates();
        pack();
        setMinimumSize(new Dimension(560, 600));
        setLocationRelativeTo(null);

        SwingUtilities.invokeLater(() -> {
            refreshImageCombo();
            ImagePlus active = IJ.getImage();
            if (active != null && active.getNSlices() > 1) {
                comboSyncing = true;
                imageCombo.setSelectedItem(active.getTitle());
                comboSyncing = false;
                bindImage(active);
            }
        });
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        setLayout(new BorderLayout(4, 4));
        add(buildTopPanel(),    BorderLayout.NORTH);
        add(tabs,               BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);

        tabs.addTab("Segmentation", new JScrollPane(segmentationTab));
        tabs.addTab("ROI Edit",     buildRoiEditTab());
        tabs.addTab("Measurement",  new JScrollPane(measurementTab));
    }

    private JPanel buildTopPanel() {
        JPanel p = new JPanel(new BorderLayout(4, 2));
        p.setBorder(BorderFactory.createEmptyBorder(6, 8, 2, 8));

        imageCombo.setPrototypeDisplayValue("A reasonably long image title     ");
        imageCombo.addActionListener(e -> {
            if (comboSyncing) return;
            String title = (String) imageCombo.getSelectedItem();
            if (title == null || title.isEmpty()) return;
            ImagePlus imp = WindowManager.getImage(title);
            if (imp != null) bindImage(imp);
        });
        btnRefresh.setToolTipText("Refresh image list");
        btnRefresh.setMargin(new Insets(2, 4, 2, 4));
        btnRefresh.addActionListener(e -> refreshImageCombo());

        p.add(new JLabel("Image:"), BorderLayout.WEST);
        p.add(imageCombo, BorderLayout.CENTER);
        p.add(btnRefresh, BorderLayout.EAST);
        return p;
    }

    private JPanel buildBottomPanel() {
        JPanel outer = new JPanel(new BorderLayout(4, 4));
        outer.setBorder(BorderFactory.createEmptyBorder(2, 8, 6, 8));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(btnMakeRoi);
        buttons.add(btnMeasure);
        buttons.add(btnRunAll);
        buttons.add(Box.createHorizontalStrut(12));
        buttons.add(btnLoadResult);
        buttons.add(btnShowInFinder);

        btnMakeRoi.addActionListener(e      -> cmdMakeRoi());
        btnMeasure.addActionListener(e      -> cmdMeasure());
        btnRunAll.addActionListener(e       -> cmdRunAll());
        btnLoadResult.addActionListener(e   -> cmdLoadResultFolder());
        btnShowInFinder.addActionListener(e -> cmdShowInFinder());

        segmentationTab.btnApply.addActionListener(e -> cmdPreview());
        segmentationTab.btnClearPreview.addActionListener(e -> cmdClearPreview());

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
            BorderFactory.createEmptyBorder(3, 2, 1, 2)));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
        statusPanel.add(statusLabel, BorderLayout.WEST);

        outer.add(buttons,     BorderLayout.NORTH);
        outer.add(statusPanel, BorderLayout.SOUTH);
        return outer;
    }

    private JPanel buildRoiEditTab() {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // Info strip at top
        JPanel infoStrip = new JPanel(new BorderLayout(6, 0));
        infoStrip.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
        roiEditFolderLabel.setFont(roiEditFolderLabel.getFont().deriveFont(Font.PLAIN, 11f));
        infoStrip.add(roiEditFolderLabel, BorderLayout.CENTER);
        btnReloadRoi.setEnabled(false);
        btnReloadRoi.addActionListener(e -> cmdReloadRoi());
        infoStrip.add(btnReloadRoi, BorderLayout.EAST);

        p.add(infoStrip, BorderLayout.NORTH);
        p.add(roiPanel,  BorderLayout.CENTER);   // ← 埋め込み
        return p;
    }

    // ── Controller wiring ─────────────────────────────────────────────────────

    private void wireController() {
        controller.addStateListener(this::updateButtonStates);

        tabs.addChangeListener(e -> {
            int current = tabs.getSelectedIndex();
            // Leave Segmentation → clear overlay, suspend Z-watcher (cache kept)
            if (previousTabIndex == 0 && current != 0) {
                segmentationTab.setTabActive(false);
                segmentationTab.clearOverlayOnly();
            }
            // Return to Segmentation → re-enable Z-watcher
            if (current == 0 && previousTabIndex != 0) {
                segmentationTab.setTabActive(true);
            }
            // Leave ROI Edit → clean up ROI Explorer preview overlays
            if (previousTabIndex == 1 && current != 1) {
                roiPanel.cleanupPreview();
            }
            previousTabIndex = current;
        });

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                segmentationTab.onWindowClosing();  // free cache + remove ImageListener
                roiPanel.cleanupPreview();
                roiPanel.onWindowClosing();
            }
        });
    }

    private void updateButtonStates() {
        boolean busy      = controller.isBusy();
        boolean hasBound  = controller.getSession().hasBoundImage();
        boolean hasResult = controller.getSession().hasResultFolder();
        btnMakeRoi.setEnabled(!busy && hasBound);
        btnMeasure.setEnabled(!busy && hasResult);
        btnRunAll.setEnabled(!busy  && hasBound);
        btnLoadResult.setEnabled(!busy);
        btnShowInFinder.setEnabled(!busy && hasResult);
        btnReloadRoi.setEnabled(!busy && hasResult);
        segmentationTab.btnApply.setEnabled(!busy && hasBound);
        segmentationTab.btnClearPreview.setEnabled(hasBound);
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    private void bindImage(ImagePlus imp) {
        if (imp == null) return;
        controller.getSession().setBoundImage(imp);
        roiPanel.setBindImage(imp);
        segmentationTab.updateImage(imp);
        setStatus("Bound: " + imp.getTitle());
        updateButtonStates();
    }

    private void refreshImageCombo() {
        String[] titles = WindowManager.getImageTitles();
        comboSyncing = true;
        String current = (String) imageCombo.getSelectedItem();
        imageCombo.removeAllItems();
        imageCombo.addItem("");
        for (String t : titles) imageCombo.addItem(t);
        if (current != null && !current.isEmpty()) imageCombo.setSelectedItem(current);
        comboSyncing = false;
    }

    private void cmdPreview() {
        if (controller.getSession().getBoundImage() == null) { setStatus("Bind an image first."); return; }
        segmentationTab.applyPreview();
    }

    private void cmdClearPreview() {
        segmentationTab.clearPreview();
        setStatus("Preview cleared.");
    }

    private void cmdMakeRoi() {
        ImagePlus image = controller.getSession().getBoundImage();
        if (image == null) { setStatus("Bind an image first."); return; }
        if (!roiEditCtrl.confirmReplaceIfNeeded()) return;

        Path baseDir = resolveBaseDir(image);
        if (baseDir == null) return;

        runSegmentation(image, segmentationTab.getParams(), baseDir, false);
    }

    private void cmdMeasure() {
        WorkflowSession session = controller.getSession();
        if (!session.hasResultFolder()) {
            setStatus("No result loaded. Run Make ROI first.");
            return;
        }
        runMeasurement(session.getResultFolder());
    }

    private void cmdRunAll() {
        ImagePlus image = controller.getSession().getBoundImage();
        if (image == null) { setStatus("Bind an image first."); return; }
        if (!roiEditCtrl.confirmReplaceIfNeeded()) return;

        Path baseDir = resolveBaseDir(image);
        if (baseDir == null) return;

        runSegmentation(image, segmentationTab.getParams(), baseDir, true);
    }

    private void cmdLoadResultFolder() {
        if (!roiEditCtrl.confirmReplaceIfNeeded()) return;

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Load Result Folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        Path prevFolder = controller.getSession().getResultFolder();
        if (prevFolder != null && prevFolder.getParent() != null) {
            chooser.setCurrentDirectory(prevFolder.getParent().toFile());
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        Path selected = chooser.getSelectedFile().toPath();
        roiEditCtrl.openResult(selected, controller.getSession().getBoundImage());

        Path paramFile = selected.resolve("parameters.txt");
        if (Files.isRegularFile(paramFile)) {
            try {
                SegmentationParams loaded = new ParameterFileReader().read(paramFile);
                segmentationTab.setParams(loaded);
                setStatus(getStatus() + "  |  Parameters loaded");
            } catch (Exception ex) {
                setStatus(getStatus() + "  |  Could not read parameters.txt");
            }
        }
    }

    private void cmdShowInFinder() {
        Path folder = controller.getSession().getResultFolder();
        if (folder == null) return;
        try {
            java.awt.Desktop.getDesktop().open(folder.toFile());
        } catch (Exception e) {
            setStatus("Could not open folder: " + e.getMessage());
        }
    }

    private void cmdReloadRoi() {
        roiPanel.reloadFromDisk();
        setStatus("ROI reloaded from disk.");
    }

    // ── Background workers ────────────────────────────────────────────────────

    private void runSegmentation(ImagePlus image, SegmentationParams params,
                                  Path baseDir, boolean measureAfter) {
        controller.setState(WorkflowController.State.SEGMENTING);
        setStatus("Segmenting...");

        new SwingWorker<Path, String>() {
            @Override protected Path doInBackground() throws Exception {
                return segmentationCtrl.makeRoi(image, params, baseDir, this::publish);
            }
            @Override protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) setStatus(chunks.get(chunks.size() - 1));
            }
            @Override protected void done() {
                try {
                    Path resultFolder = get();
                    if (!roiEditCtrl.openResult(resultFolder, image)) {
                        controller.setState(WorkflowController.State.IDLE);
                        setStatus("Error: result folder is missing 'rois' subdirectory.");
                        return;
                    }
                    if (measureAfter) {
                        tabs.setSelectedIndex(2);
                        runMeasurement(resultFolder);
                    } else {
                        tabs.setSelectedIndex(1);
                    }
                } catch (CancellationException e) {
                    controller.setState(WorkflowController.State.IDLE);
                    setStatus("Cancelled.");
                } catch (Exception e) {
                    controller.setState(WorkflowController.State.IDLE);
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    setStatus("Error: " + cause.getMessage());
                    JOptionPane.showMessageDialog(WorkflowWindow.this,
                        cause.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void runMeasurement(Path resultFolder) {
        roiPanel.cleanupPreview();
        Path csvPath = resultFolder.resolve("measurement.csv");
        MeasurementRequest request = measurementTab.buildRequest(csvPath);

        controller.setState(WorkflowController.State.MEASURING);
        setStatus("Measuring...");

        new SwingWorker<MeasurementResult, Void>() {
            @Override protected MeasurementResult doInBackground() {
                return measureCtrl.measure(request);
            }
            @Override protected void done() {
                try {
                    MeasurementResult result = get();
                    controller.setState(WorkflowController.State.READY);
                    setStatus(result.isPerformed()
                        ? "Saved " + csvPath.getFileName() + ". " + result.getMessage()
                        : "Measurement skipped: " + result.getMessage());
                } catch (Exception e) {
                    controller.setState(WorkflowController.State.READY);
                    setStatus("Measurement error: " + e.getMessage());
                }
            }
        }.execute();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setStatus(String text) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(text));
    }

    public void refreshRoiEditTab() {
        SwingUtilities.invokeLater(() -> {
            Path folder = controller.getSession().getResultFolder();
            roiEditFolderLabel.setText(folder != null ? folder.toString() : "No result loaded");
            measurementTab.setOutputFolder(folder);
            updateButtonStates();
        });
    }

    public WorkflowController getController() { return controller; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String getStatus() { return statusLabel.getText(); }

    private Path resolveBaseDir(ImagePlus image) {
        Path dir = segmentationTab.getEffectiveSaveBaseDir();
        if (dir != null) return dir;
        // No image file directory and no custom path set — prompt
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose save location for result folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return null;
        return chooser.getSelectedFile().toPath();
    }
}
