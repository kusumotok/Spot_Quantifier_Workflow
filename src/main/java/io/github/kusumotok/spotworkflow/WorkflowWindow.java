package io.github.kusumotok.spotworkflow;

import ij.IJ;
import ij.ImageListener;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Roi;
import io.github.kusumotok.roiexplorer.OpenViewRegistry;
import io.github.kusumotok.roiexplorer.service.RoiExplorerFacade.MeasurementRequest;
import io.github.kusumotok.roiexplorer.service.RoiExplorerFacade.MeasurementResult;
import io.github.kusumotok.roiexplorer.ui.RoiExplorerPanel;
import io.github.kusumotok.spotworkflow.core.roi.SeedRoiReader;
import io.github.kusumotok.spotworkflow.save.ParameterFileReader;
import io.github.kusumotok.spotworkflow.save.ResultFolderService;
import io.github.kusumotok.spotworkflow.save.SegmentationParams;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

public final class WorkflowWindow extends JFrame {

    private static WorkflowWindow instance;

    // ── Controllers ───────────────────────────────────────────────────────────
    private final WorkflowController     controller       = new WorkflowController();
    private final SegmentationController segmentationCtrl = new SegmentationController();
    private final ResultFolderService    folderService    = new ResultFolderService();
    private final SeedRoiReader          seedRoiReader    = new SeedRoiReader();
    private final RoiExplorerPanel       seedRoiPanel     = new RoiExplorerPanel();
    private final RoiExplorerPanel       resultMeasurePanel = new RoiExplorerPanel();
    private final MeasurementController  measureCtrl      = new MeasurementController(resultMeasurePanel);
    private final SegmentationTab        seedTab          = new SegmentationTab(SegmentationTab.Mode.SEED);
    private final SegmentationTab        segmentationTab  = new SegmentationTab(SegmentationTab.Mode.AREA_RESULT);
    private final MeasurementTab         measurementTab   = new MeasurementTab();

    // ── Image selector ────────────────────────────────────────────────────────
    private final JComboBox<String> imageCombo   = new JComboBox<>();
    private final JButton           btnRefresh   = new JButton("⟳");
    private final JSpinner          channelSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 1, 1));
    private final JComboBox<String> zprojCombo = new JComboBox<>();
    private final JButton           zprojBtn = new JButton("Max Proj");
    private final JTextField        projectField = new JTextField(18);
    private boolean                 comboSyncing = false;
    private int                     preferredChannel = 1;
    private boolean                 channelSyncing = false;
    private ImageListener           targetImageListener;

    // ── Tabs ──────────────────────────────────────────────────────────────────
    private final JTabbedPane tabs             = new JTabbedPane();
    private       int         previousTabIndex = 0;

    // ── ROI Edit tab info ─────────────────────────────────────────────────────

    // ── Action buttons ────────────────────────────────────────────────────────
    private final JButton btnLoadResult   = new JButton("Load Project...");
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
        // resultMeasurePanel は Measurement タブ専用。seedRoiPanel と同じ画像に
        // 登録されると refreshOverlaysFor() で overlay が競合するため外しておく。
        // openResultMeasureRoot() 時に bind するだけで測定には十分。
        OpenViewRegistry.getInstance().unregister(resultMeasurePanel);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        buildUI();
        loadPersistentSettings();
        wireController();
        updateButtonStates();
        pack();
        setMinimumSize(new Dimension(480, 600));
        setLocationRelativeTo(null);

        SwingUtilities.invokeLater(() -> {
            refreshImageCombo();
            ImagePlus active = WindowManager.getCurrentImage();
            if (active != null && active.getNSlices() > 1) {
                comboSyncing = true;
                imageCombo.setSelectedItem(active.getTitle());
                comboSyncing = false;
                bindImage(active);
            } else if (active == null) {
                setStatus("No image open. Open a 3D image to start.");
            } else {
                setStatus("Selected image has only 1 slice. Open a 3D (Z-stack) image.");
            }
        });
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        setLayout(new BorderLayout(4, 4));
        add(buildTopPanel(),    BorderLayout.NORTH);
        add(tabs,               BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);

        tabs.addTab("Seed",          new JScrollPane(seedTab));
        tabs.addTab("Seed Edit",     buildRoiEditTab(seedRoiPanel));
        tabs.addTab("Area / Result", new JScrollPane(segmentationTab));
        tabs.addTab("Measurement",   new JScrollPane(measurementTab));
        applyPreviewPolicy(0, -1);
    }

    private JPanel buildTopPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(6, 8, 2, 8));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 3, 2, 3);
        gc.fill = GridBagConstraints.HORIZONTAL;

        imageCombo.setPrototypeDisplayValue("None");
        imageCombo.addActionListener(e -> {
            if (comboSyncing) return;
            String title = (String) imageCombo.getSelectedItem();
            if (title == null || title.isEmpty() || "None".equals(title)) {
                clearTargetImageAndProject("Target image cleared.");
                return;
            }
            ImagePlus imp = WindowManager.getImage(title);
            if (imp != null) bindImage(imp);
        });
        btnRefresh.setToolTipText("Refresh image list");
        btnRefresh.setMargin(new Insets(2, 4, 2, 4));
        btnRefresh.addActionListener(e -> {
            refreshImageCombo();
            refreshZProjCombo();
        });
        zprojCombo.addItem("None");
        zprojCombo.addActionListener(e -> syncSharedParamsToTabs());
        zprojBtn.addActionListener(e -> cmdCreateMaxProj());
        channelSpinner.addChangeListener(e -> {
            if (channelSyncing) return;
            preferredChannel = (Integer) channelSpinner.getValue();
            syncSharedParamsToTabs();
        });
        projectField.setEditable(false);

        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0;
        p.add(new JLabel("Image:"), gc);
        gc.gridx = 1; gc.weightx = 1;
        p.add(imageCombo, gc);
        gc.gridx = 2; gc.weightx = 0;
        p.add(btnRefresh, gc);
        gc.gridx = 3;
        p.add(new JLabel("Ch:"), gc);
        gc.gridx = 4;
        p.add(channelSpinner, gc);

        gc.gridx = 0; gc.gridy = 1;
        p.add(new JLabel("Z-proj:"), gc);
        gc.gridx = 1; gc.weightx = 1;
        p.add(zprojCombo, gc);
        gc.gridx = 2; gc.weightx = 0; gc.gridwidth = 3;
        p.add(zprojBtn, gc);
        gc.gridwidth = 1;

        gc.gridx = 0; gc.gridy = 2;
        p.add(new JLabel("Project:"), gc);
        gc.gridx = 1; gc.weightx = 1;
        p.add(projectField, gc);
        gc.gridx = 2; gc.weightx = 0;
        p.add(btnLoadResult, gc);
        gc.gridx = 3; gc.gridwidth = 2;
        p.add(btnShowInFinder, gc);
        gc.gridwidth = 1;
        return p;
    }

    private JPanel buildBottomPanel() {
        JPanel outer = new JPanel(new BorderLayout(4, 4));
        outer.setBorder(BorderFactory.createEmptyBorder(2, 8, 6, 8));

        btnLoadResult.addActionListener(e   -> cmdLoadResultFolder());
        btnShowInFinder.addActionListener(e -> cmdShowInFinder());

        seedTab.btnApply.addActionListener(e -> cmdSeedPreview());
        seedTab.btnClearPreview.addActionListener(e -> cmdSeedClearPreview());
        seedTab.btnMakeSeedRoi.addActionListener(e -> cmdMakeSeedRoi());
        segmentationTab.btnApply.addActionListener(e -> cmdPreview());
        segmentationTab.btnClearPreview.addActionListener(e -> cmdClearPreview());
        segmentationTab.btnMakeResultRoi.addActionListener(e -> cmdMakeResultRoi());
        measurementTab.btnMeasure.addActionListener(e -> cmdMeasure());
        measurementTab.addResultRoiSelectionListener(e -> updateButtonStates());

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
            BorderFactory.createEmptyBorder(3, 2, 1, 2)));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
        statusPanel.add(statusLabel, BorderLayout.WEST);

        outer.add(statusPanel, BorderLayout.SOUTH);
        return outer;
    }

    private JPanel buildRoiEditTab(RoiExplorerPanel panel) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        p.add(panel,  BorderLayout.CENTER);
        return p;
    }

    // ── Controller wiring ─────────────────────────────────────────────────────

    private void wireController() {
        controller.addStateListener(this::updateButtonStates);
        installTargetImageListener();

        tabs.addChangeListener(e -> {
            int current = tabs.getSelectedIndex();
            // Leave ROI Edit tabs → clean up ROI Explorer preview overlays
            if (previousTabIndex == 1 && current != 1) {
                seedRoiPanel.cleanupPreview();
            }
            applyPreviewPolicy(current, previousTabIndex);
            previousTabIndex = current;
        });

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                savePersistentSettings();
                seedTab.onWindowClosing();          // free cache + remove ImageListener
                segmentationTab.onWindowClosing();  // free cache + remove ImageListener
                seedRoiPanel.cleanupPreview();
                seedRoiPanel.onWindowClosing();
                resultMeasurePanel.cleanupPreview();
                resultMeasurePanel.onWindowClosing();
                if (targetImageListener != null) ImagePlus.removeImageListener(targetImageListener);
            }
        });
    }

    private void updateButtonStates() {
        boolean busy      = controller.isBusy();
        boolean hasBound  = controller.getSession().hasBoundImage();
        boolean hasProject = controller.getSession().hasProjectFolder();
        boolean canMeasure = measurementTab.getSelectedResultRoiFolder() != null;
        btnLoadResult.setEnabled(!busy);
        btnShowInFinder.setEnabled(!busy && hasProject);
        seedTab.btnApply.setEnabled(!busy && hasBound);
        seedTab.btnClearPreview.setEnabled(hasBound);
        seedTab.btnMakeSeedRoi.setEnabled(!busy && hasBound);
        segmentationTab.btnApply.setEnabled(!busy && hasBound);
        segmentationTab.btnClearPreview.setEnabled(hasBound);
        segmentationTab.btnMakeResultRoi.setEnabled(!busy && hasBound && hasProject);
        measurementTab.btnMeasure.setEnabled(!busy && canMeasure);
    }

    private void applyPreviewPolicy(int current, int previous) {
        switch (current) {
            case 0: // Seed: 3D + Z-proj seed preview
                seedRoiPanel.setOverlayEnabled(false);
                resultMeasurePanel.setOverlayEnabled(false);
                seedTab.setPreviewActive(true, true);
                segmentationTab.setPreviewActive(false, false);
                break;
            case 1: // Seed Edit: ROI Explorer owns both main and sub overlays
                seedRoiPanel.setOverlayEnabled(true);
                resultMeasurePanel.setOverlayEnabled(false);
                seedTab.setPreviewActive(false, false);
                segmentationTab.setPreviewActive(false, false);
                seedTab.clearOverlayOnly();
                segmentationTab.clearOverlayOnly();
                syncSeedEditSubImage();
                if (seedRoiPanel.hasLoadedRoot()) seedRoiPanel.refreshOverlay();
                break;
            case 2: // Area / Result: 3D + Z-proj area preview
                seedRoiPanel.setOverlayEnabled(false);
                resultMeasurePanel.setOverlayEnabled(false);
                seedTab.setPreviewActive(false, false);
                seedTab.clearOverlayOnly();
                segmentationTab.setPreviewActive(true, true);
                break;
            case 3: // Measurement: ROI Explorer owns result overlays
                seedRoiPanel.setOverlayEnabled(false);
                resultMeasurePanel.setOverlayEnabled(true);
                seedTab.setPreviewActive(false, false);
                segmentationTab.setPreviewActive(false, false);
                seedTab.clearOverlayOnly();
                segmentationTab.clearOverlayOnly();
                if (resultMeasurePanel.hasLoadedRoot()) {
                    syncResultMeasureSubImage();
                    resultMeasurePanel.refreshOverlay();
                }
                break;
            default:
                seedRoiPanel.setOverlayEnabled(false);
                resultMeasurePanel.setOverlayEnabled(false);
                seedTab.setPreviewActive(false, false);
                segmentationTab.setPreviewActive(false, false);
                break;
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    private void bindImage(ImagePlus imp) {
        if (imp == null) return;
        if (imp.getNFrames() > 1) {
            setStatus("Time series image detected. Use Plugins > Spot Quantifier Time Series.");
            JOptionPane.showMessageDialog(this,
                "This workflow is for T=1 images. Use Spot Quantifier Time Series for T>1 images.",
                "Spot Quantifier Workflow", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ImagePlus previous = controller.getSession().getBoundImage();
        boolean targetChanged = previous != null && previous != imp;
        if (targetChanged) {
            clearProjectForTargetChange();
        }
        controller.getSession().setBoundImage(imp);
        seedRoiPanel.setBindImage(imp);
        seedRoiPanel.setContainerOrMode(true);
        seedRoiPanel.setProjectionMode(true, false, false);
        // resultMeasurePanel は openResultMeasureRoot() で bind する
        // 同じ画像に両パネルを bind すると refreshOverlaysFor() で overlay が競合するため
        SpinnerNumberModel model = (SpinnerNumberModel) channelSpinner.getModel();
        int nCh = Math.max(1, imp.getNChannels());
        int requestedChannel = Math.max(1, preferredChannel);
        int safeChannel = requestedChannel <= nCh ? requestedChannel : 1;
        channelSyncing = true;
        model.setMaximum(nCh);
        if ((Integer) model.getValue() != safeChannel) model.setValue(safeChannel);
        channelSyncing = false;
        preferredChannel = safeChannel;
        seedTab.updateImage(imp);
        segmentationTab.updateImage(imp);
        refreshZProjCombo();
        syncSharedParamsToTabs();
        setStatus(targetChanged
            ? "Bound: " + imp.getTitle() + "  |  Project cleared for target image change."
            : "Bound: " + imp.getTitle());
        updateButtonStates();
    }

    private void refreshImageCombo() {
        comboSyncing = true;
        String current = (String) imageCombo.getSelectedItem();
        imageCombo.removeAllItems();
        imageCombo.addItem("None");
        // Z-proj など 1 スライス画像を除外。誤選択でセグメンテーションが
        // 2D 画像に対して走るのを防ぐ。
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null && imp.getNSlices() > 1) imageCombo.addItem(imp.getTitle());
            }
        }
        if (current != null && !current.isEmpty()) imageCombo.setSelectedItem(current);
        if (imageCombo.getSelectedIndex() < 0) imageCombo.setSelectedIndex(0);
        comboSyncing = false;
    }

    private void syncSharedParamsToTabs() {
        int ch = (Integer) channelSpinner.getValue();
        String zproj = (String) zprojCombo.getSelectedItem();
        seedTab.setExternalChannel(ch);
        segmentationTab.setExternalChannel(ch);
        seedTab.setExternalZProjTitle(zproj);
        segmentationTab.setExternalZProjTitle(zproj);
        syncSeedEditSubImage();
        if (resultMeasurePanel.hasLoadedRoot()) syncResultMeasureSubImage();
    }

    private void syncSeedEditSubImage() {
        syncSubImage(seedRoiPanel);
    }

    private void syncResultMeasureSubImage() {
        syncSubImage(resultMeasurePanel);
    }

    private void syncSubImage(RoiExplorerPanel panel) {
        ImagePlus main = controller.getSession().getBoundImage();
        if (main == null) {
            panel.clearSubBindImage();
            return;
        }
        Object selected = zprojCombo.getSelectedItem();
        String title = selected != null ? selected.toString() : null;
        if (title == null || title.equals("None")) {
            panel.clearSubBindImage();
            return;
        }
        ImagePlus sub = WindowManager.getImage(title);
        if (sub == null || sub == main) {
            panel.clearSubBindImage();
            return;
        }
        panel.setSubBindImage(sub);
    }

    private void refreshZProjCombo() {
        Object current = zprojCombo.getSelectedItem();
        zprojCombo.removeAllItems();
        zprojCombo.addItem("None");
        ImagePlus image = controller.getSession().getBoundImage();
        int[] ids = WindowManager.getIDList();
        if (ids != null && image != null) {
            for (int id : ids) {
                ImagePlus candidate = WindowManager.getImage(id);
                if (candidate == null || candidate == image || candidate.getNSlices() != 1) continue;
                if (candidate.getWidth() == image.getWidth() && candidate.getHeight() == image.getHeight()) {
                    zprojCombo.addItem(candidate.getTitle());
                }
            }
        }
        if (current != null) zprojCombo.setSelectedItem(current);
        if (zprojCombo.getSelectedIndex() < 0) zprojCombo.setSelectedIndex(0);
    }

    private void cmdCreateMaxProj() {
        ImagePlus image = controller.getSession().getBoundImage();
        if (image == null) return;
        java.util.Set<Integer> beforeIds = currentImageIdSet();
        IJ.run(image, "Z Project...", "projection=[Max Intensity]");
        refreshZProjCombo();
        ImagePlus result = findNewImage(beforeIds);
        if (result != null) zprojCombo.setSelectedItem(result.getTitle());
        syncSharedParamsToTabs();
    }

    private void cmdPreview() {
        if (controller.getSession().getBoundImage() == null) { setStatus("Bind an image first."); return; }
        syncSharedParamsToTabs();
        Path seedRoot = seedRoiPanel.hasLoadedRoot()
            ? seedRoiPanel.getCurrentRoot()
            : controller.getSession().getSeedRoiRoot();
        if (seedRoot == null) {
            setStatus("No edited seed ROI loaded.");
            return;
        }
        try {
            ImagePlus seedLabels = seedRoiReader.readAsLabelImage(seedRoot, controller.getSession().getBoundImage());
            segmentationTab.applyPreviewFromSeedLabels(seedLabels);
            setStatus("Area preview uses seed ROI: " + seedRoot);
        } catch (Exception e) {
            setStatus("Could not read seed ROI: " + e.getMessage());
            JOptionPane.showMessageDialog(this, e.getMessage(), "Seed ROI", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void cmdClearPreview() {
        segmentationTab.clearPreview();
        setStatus("Preview cleared.");
    }

    private void cmdSeedPreview() {
        if (controller.getSession().getBoundImage() == null) { setStatus("Bind an image first."); return; }
        syncSharedParamsToTabs();
        seedTab.applyPreview();
    }

    private void cmdSeedClearPreview() {
        seedTab.clearPreview();
        setStatus("Seed preview cleared.");
    }

    private void cmdMakeSeedRoi() {
        ImagePlus image = controller.getSession().getBoundImage();
        if (image == null) { setStatus("Bind an image first."); return; }
        syncSharedParamsToTabs();
        Path project = ensureProjectFolder(image);
        if (project == null) return;
        SegmentationParams params = currentWorkflowParams();
        if (seedTab.hasCurrentSeedRoiCache()) {
            runSeedRoiFromCache(params, seedTab.getCurrentSeedRoiObjects(), project);
            return;
        }
        runSeedRoi(image, params, project);
    }

    private void cmdMakeResultRoi() {
        ImagePlus image = controller.getSession().getBoundImage();
        if (image == null) { setStatus("Bind an image first."); return; }
        syncSharedParamsToTabs();
        Path project = ensureProjectFolder(image);
        if (project == null) return;
        SegmentationParams params = currentWorkflowParams();
        Path seedRoot = seedRoiPanel.hasLoadedRoot()
            ? seedRoiPanel.getCurrentRoot()
            : controller.getSession().getSeedRoiRoot();
        if (seedRoot == null) {
            setStatus("No edited seed ROI loaded.");
            return;
        }
        runResultRoi(image, params, project, seedRoot, false);
    }

    private void cmdMeasure() {
        WorkflowSession session = controller.getSession();
        Path project = session.getProjectFolder();
        Path resultRoot = session.getResultRoiRoot();
        if (project != null && resultRoot != null && Files.isDirectory(resultRoot)) {
            Path selectedRoot = measurementTab.getSelectedResultRoiFolder();
            runMeasurement(project, selectedRoot != null ? selectedRoot : resultRoot, currentWorkflowParams());
            return;
        }

        ImagePlus image = session.getBoundImage();
        if (image == null) {
            setStatus("No result ROI loaded. Bind an image and make result ROI first.");
            JOptionPane.showMessageDialog(this,
                "No result ROI is loaded. Bind an image and make result ROI first.",
                "Measure", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int ok = JOptionPane.showConfirmDialog(this,
            "No result ROI is available. Make result ROI now and then measure?",
            "Measure", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            setStatus("Measurement requires result ROI.");
            return;
        }

        syncSharedParamsToTabs();
        project = ensureProjectFolder(image);
        if (project == null) return;
        SegmentationParams params = currentWorkflowParams();
        Path seedRoot = seedRoiPanel.hasLoadedRoot()
            ? seedRoiPanel.getCurrentRoot()
            : session.getSeedRoiRoot();
        if (seedRoot == null || !Files.isDirectory(seedRoot)) {
            setStatus("No seed ROI loaded. Make seed ROI before measurement.");
            JOptionPane.showMessageDialog(this,
                "No seed ROI is loaded. Make or load seed ROI before creating result ROI.",
                "Measure", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        runResultRoi(image, params, project, seedRoot, true);
    }

    private void cmdLoadResultFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Load Project / Result Folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        Path prevFolder = controller.getSession().getProjectFolder();
        if (prevFolder != null && prevFolder.getParent() != null) {
            chooser.setCurrentDirectory(prevFolder.getParent().toFile());
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        Path selected = chooser.getSelectedFile().toPath();
        seedTab.clearPreview();
        segmentationTab.clearPreview();
        seedRoiPanel.closeFolder();
        resultMeasurePanel.closeFolder();
        setProjectFolder(selected);
        openSeedRoiRoot();
        openResultMeasureRoot();

        Path paramFile = selected.resolve("parameters.txt");
        if (Files.isRegularFile(paramFile)) {
            try {
                SegmentationParams loaded = new ParameterFileReader().read(paramFile);
                seedTab.setParams(loaded);
                segmentationTab.setParams(loaded);
                setStatus(getStatus() + "  |  Parameters loaded");
            } catch (Exception ex) {
                setStatus(getStatus() + "  |  Could not read parameters.txt");
            }
        }
    }

    private void cmdShowInFinder() {
        Path folder = controller.getSession().getProjectFolder();
        if (folder == null) return;
        try {
            java.awt.Desktop.getDesktop().open(folder.toFile());
        } catch (Exception e) {
            setStatus("Could not open folder: " + e.getMessage());
        }
    }

    private Path ensureProjectFolder(ImagePlus image) {
        Path project = controller.getSession().getProjectFolder();
        if (project != null) return project;
        Path baseDir = resolveBaseDir(image);
        if (baseDir == null) return null;
        try {
            String pattern = currentWorkflowParams().resultFolderPattern;
            if (pattern.isEmpty()) pattern = "{name} result";
            String name = pattern.replace("{name}", image.getTitle().replaceAll("\\.[^.]+$", ""));
            project = folderService.createResultFolder(baseDir, name);
            setProjectFolder(project);
            setStatus("New project: " + project);
            return project;
        } catch (Exception e) {
            setStatus("Could not create project folder: " + e.getMessage());
            return null;
        }
    }

    private void clearProjectForTargetChange() {
        seedTab.clearPreview();
        segmentationTab.clearPreview();
        seedRoiPanel.closeFolder();
        resultMeasurePanel.closeFolder();
        setProjectFolder(null);
    }

    private void clearTargetImageAndProject(String status) {
        controller.getSession().setBoundImage(null);
        clearProjectForTargetChange();
        seedTab.updateImage(null);
        segmentationTab.updateImage(null);
        if (status != null && !status.isEmpty()) setStatus(status);
        updateButtonStates();
    }

    private void installTargetImageListener() {
        targetImageListener = new ImageListener() {
            @Override public void imageOpened(ImagePlus imp) {}
            @Override public void imageUpdated(ImagePlus imp) {}
            @Override public void imageClosed(ImagePlus imp) {
                if (imp != controller.getSession().getBoundImage()) return;
                SwingUtilities.invokeLater(() -> {
                    comboSyncing = true;
                    imageCombo.setSelectedItem("None");
                    comboSyncing = false;
                    clearTargetImageAndProject("Target image closed. Project cleared.");
                });
            }
        };
        ImagePlus.addImageListener(targetImageListener);
    }

    private void setProjectFolder(Path project) {
        controller.getSession().setProjectFolder(project);
        projectField.setText(project != null ? project.toString() : "");
        projectField.setToolTipText(project != null ? project.toString() : "");
        refreshMeasurementResultFolders();
        updateButtonStates();
    }

    private void openSeedRoiRoot() {
        Path seedRoot = controller.getSession().getSeedRoiRoot();
        ImagePlus image = controller.getSession().getBoundImage();
        if (seedRoot != null && Files.isDirectory(seedRoot)) {
            if (image != null) {
                seedRoiPanel.setBindImage(image);
                seedRoiPanel.setContainerOrMode(true);
                seedRoiPanel.setProjectionMode(true, false, false);
                syncSeedEditSubImage();
            }
            seedRoiPanel.openFolder(seedRoot);
        }
    }

    private void openResultMeasureRoot() {
        Path resultRoot = controller.getSession().getResultRoiRoot();
        ImagePlus image = controller.getSession().getBoundImage();
        if (resultRoot != null && Files.isDirectory(resultRoot)) {
            if (image != null) {
                resultMeasurePanel.setBindImage(image);
                resultMeasurePanel.setContainerOrMode(true);
                resultMeasurePanel.setProjectionMode(true, false, false);
                syncResultMeasureSubImage();
            }
            resultMeasurePanel.openFolder(resultRoot);
        }
    }

    // ── Background workers ────────────────────────────────────────────────────

    private void runSeedRoi(ImagePlus image, SegmentationParams params, Path project) {
        controller.setState(WorkflowController.State.SEGMENTING);
        setStatus("Making seed ROI...");

        new SwingWorker<Path, String>() {
            @Override protected Path doInBackground() throws Exception {
                return segmentationCtrl.makeSeedRois(image, params, project, this::publish);
            }
            @Override protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) setStatus(chunks.get(chunks.size() - 1));
            }
            @Override protected void done() {
                try {
                    Path seedRoot = get();
                    controller.getSession().setSeedRoiRoot(seedRoot);
                    controller.getSession().setResultRoiRoot(null);
                    resultMeasurePanel.closeFolder();
                    openSeedRoiRoot();
                    tabs.setSelectedIndex(1);
                    controller.setState(WorkflowController.State.READY);
                    setStatus("Seed ROI saved: " + seedRoot);
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

    private void runSeedRoiFromCache(SegmentationParams params, List<List<Roi>> objectRois, Path project) {
        controller.setState(WorkflowController.State.SEGMENTING);
        setStatus("Saving seed ROI...");

        new SwingWorker<Path, String>() {
            @Override protected Path doInBackground() throws Exception {
                return segmentationCtrl.saveSeedRois(project, params, objectRois, this::publish);
            }
            @Override protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) setStatus(chunks.get(chunks.size() - 1));
            }
            @Override protected void done() {
                try {
                    Path seedRoot = get();
                    controller.getSession().setSeedRoiRoot(seedRoot);
                    controller.getSession().setResultRoiRoot(null);
                    resultMeasurePanel.closeFolder();
                    openSeedRoiRoot();
                    tabs.setSelectedIndex(1);
                    controller.setState(WorkflowController.State.READY);
                    setStatus("Seed ROI saved: " + seedRoot);
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

    private void runResultRoi(ImagePlus image, SegmentationParams params, Path project, Path seedRoot,
                              boolean measureAfter) {
        controller.setState(WorkflowController.State.SEGMENTING);
        setStatus(measureAfter ? "Making result ROI before measurement..." : "Making result ROI...");

        new SwingWorker<Path, String>() {
            @Override protected Path doInBackground() throws Exception {
                return segmentationCtrl.makeResultFromSeedRois(image, params, project, seedRoot, this::publish);
            }
            @Override protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) setStatus(chunks.get(chunks.size() - 1));
            }
            @Override protected void done() {
                try {
                    Path resultRoot = get();
                    controller.getSession().setResultRoiRoot(resultRoot);
                    resultMeasurePanel.setBindImage(image);
                    resultMeasurePanel.setContainerOrMode(true);
                    resultMeasurePanel.setProjectionMode(true, false, false);
                    syncResultMeasureSubImage();
                    resultMeasurePanel.openFolder(resultRoot);
                    controller.setState(WorkflowController.State.READY);
                    setStatus("Result ROI saved: " + resultRoot);
                    refreshMeasurementResultFolders(resultRoot);
                    if (measureAfter) {
                        tabs.setSelectedIndex(3);
                        runMeasurement(project, resultRoot, params);
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

    private void runMeasurement(Path resultFolder, Path resultRoot, SegmentationParams params) {
        openResultMeasureRoot();
        Path csvPath = SegmentationController.measurementCsvFor(resultFolder, resultRoot, params);
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
            openSeedRoiRoot();
            openResultMeasureRoot();
            refreshMeasurementResultFolders();
            updateButtonStates();
        });
    }

    private void refreshMeasurementResultFolders() {
        refreshMeasurementResultFolders(controller.getSession().getResultRoiRoot());
    }

    private void refreshMeasurementResultFolders(Path selectedRoot) {
        Path project = controller.getSession().getProjectFolder();
        List<Path> roots = new ArrayList<>();
        if (project != null && Files.isDirectory(project)) {
            addResultRootIfExists(roots, project.resolve("result_rois"));
            try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(project, "result_rois_area-*")) {
                for (Path root : stream) {
                    if (Files.isDirectory(root)) roots.add(root);
                }
            } catch (Exception ignored) {
                // Folder display is informational; measurement will still validate selected root.
            }
        }
        roots.sort(WorkflowWindow::compareResultRootForDisplay);
        measurementTab.setResultRoiFolders(roots, selectedRoot);
    }

    private static void addResultRootIfExists(List<Path> roots, Path root) {
        if (root != null && Files.isDirectory(root)) roots.add(root);
    }

    private static int compareResultRootForDisplay(Path a, Path b) {
        return Integer.compare(resultRootSortKey(a), resultRootSortKey(b));
    }

    private static int resultRootSortKey(Path root) {
        if (root == null || root.getFileName() == null) return Integer.MAX_VALUE;
        String name = root.getFileName().toString();
        if ("result_rois_area-disabled".equals(name)) return -2;
        if ("result_rois".equals(name)) return -1;
        String prefix = "result_rois_area-th";
        if (name.startsWith(prefix)) {
            try {
                return Integer.parseInt(name.substring(prefix.length()));
            } catch (NumberFormatException ignored) {
                return Integer.MAX_VALUE - 1;
            }
        }
        return Integer.MAX_VALUE;
    }

    public WorkflowController getController() { return controller; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String getStatus() { return statusLabel.getText(); }

    private void loadPersistentSettings() {
        WorkflowPreferences prefs = WorkflowPreferences.load();
        preferredChannel = Math.max(1, prefs.preferredChannel);
        SpinnerNumberModel channelModel = (SpinnerNumberModel) channelSpinner.getModel();
        channelSyncing = true;
        channelModel.setMaximum(Math.max(1, preferredChannel));
        channelSpinner.setValue(preferredChannel);
        channelSyncing = false;
        seedTab.setParams(prefs.params);
        segmentationTab.setParams(prefs.params);
        seedTab.setPreviewSettings(prefs.seedPreview);
        segmentationTab.setPreviewSettings(prefs.areaPreview);
        measurementTab.setSaveCsvSelected(prefs.measurementSaveCsv);
        measurementTab.setShowTableSelected(prefs.measurementShowTable);
        measurementTab.setSelectedColumns(prefs.measurementColumns);
    }

    private void savePersistentSettings() {
        WorkflowPreferences prefs = new WorkflowPreferences();
        SegmentationParams params = currentWorkflowParams();
        prefs.preferredChannel = Math.max(1, preferredChannel);
        prefs.params.seedThreshold = params.seedThreshold;
        prefs.params.areaThreshold = params.areaThreshold;
        prefs.params.areaEnabled = params.areaEnabled;
        prefs.params.minVolUm3 = params.minVolUm3;
        prefs.params.maxVolUm3 = params.maxVolUm3;
        prefs.params.connectivity = params.connectivity;
        prefs.params.fillHoles = params.fillHoles;
        prefs.params.saveMode = params.saveMode;
        prefs.params.resultFolderPattern = params.resultFolderPattern;
        prefs.seedPreview.copyFrom(seedTab.getPreviewSettings());
        prefs.areaPreview.copyFrom(segmentationTab.getPreviewSettings());
        prefs.measurementSaveCsv = measurementTab.isSaveCsvSelected();
        prefs.measurementShowTable = measurementTab.isShowTableSelected();
        prefs.measurementColumns = measurementTab.getSelectedColumns();
        prefs.save();
    }

    private SegmentationParams currentWorkflowParams() {
        SegmentationParams seedParams = seedTab.getParams();
        SegmentationParams areaParams = segmentationTab.getParams();
        SegmentationParams params = new SegmentationParams();
        params.seedThreshold = seedParams.seedThreshold;
        params.areaThreshold = areaParams.areaThreshold;
        params.areaEnabled = areaParams.areaEnabled;
        params.minVolUm3 = seedParams.minVolUm3;
        params.maxVolUm3 = seedParams.maxVolUm3;
        params.connectivity = areaParams.connectivity;
        params.fillHoles = areaParams.fillHoles;
        params.areaConflictMode = areaParams.areaConflictMode;
        params.channel = (Integer) channelSpinner.getValue();
        params.saveMode = seedParams.saveMode;
        params.resultFolderPattern = seedParams.resultFolderPattern;
        return params;
    }

    private Path resolveBaseDir(ImagePlus image) {
        Path dir = seedTab.getEffectiveSaveBaseDir();
        if (dir != null) return dir;
        // No image file directory and no custom path set — prompt
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose save location for result folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return null;
        return chooser.getSelectedFile().toPath();
    }

    private static java.util.Set<Integer> currentImageIdSet() {
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        int[] list = WindowManager.getIDList();
        if (list != null) for (int id : list) ids.add(id);
        return ids;
    }

    private static ImagePlus findNewImage(java.util.Set<Integer> beforeIds) {
        int[] list = WindowManager.getIDList();
        if (list == null) return null;
        for (int id : list) {
            if (!beforeIds.contains(id)) return WindowManager.getImage(id);
        }
        return null;
    }
}
