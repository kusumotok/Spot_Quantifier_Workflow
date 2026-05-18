package io.github.kusumotok.spotworkflow;

import ij.ImagePlus;
import ij.ImageStack;
import ij.ImageListener;
import ij.WindowManager;
import ij.plugin.Duplicator;
import ij.plugin.ZProjector;
import ij.gui.ImageCanvas;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.io.RoiEncoder;
import ij.process.ImageProcessor;
import io.github.kusumotok.roiexplorer.service.RoiExplorerFacade.MeasurementRequest;
import io.github.kusumotok.roiexplorer.service.RoiExplorerFacade.MeasurementResult;
import io.github.kusumotok.roiexplorer.ui.RoiExplorerPanel;
import io.github.kusumotok.spotworkflow.core.alg.CcResult3D;
import io.github.kusumotok.spotworkflow.core.alg.SpotQuantifier3D;
import io.github.kusumotok.spotworkflow.core.roi.RoiExporter3D;
import io.github.kusumotok.spotworkflow.core.roi.SeedRoiReader;
import io.github.kusumotok.spotworkflow.save.ResultFolderService;
import io.github.kusumotok.spotworkflow.save.SegmentationParams;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

public final class TimeSeriesWorkflowWindow extends JFrame {

    private static TimeSeriesWorkflowWindow instance;

    private final JComboBox<String> imageCombo = new JComboBox<>();
    private final JButton btnRefresh = new JButton("⟳");
    private final JSpinner channelSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 1, 1));
    private final JComboBox<String> zprojCombo = new JComboBox<>();
    private final JButton zprojBtn = new JButton("Max Proj");
    private final JTextField projectField = new JTextField(18);
    private final JButton btnLoadProject = new JButton("Load Project...");
    private final JButton btnShowInFinder = new JButton("Show in Explorer");
    private final JLabel statusLabel = new JLabel("Ready.");
    private final JTabbedPane tabs = new JTabbedPane();
    private final SegmentationTab seedTab = new SegmentationTab(SegmentationTab.Mode.SEED);
    private final SegmentationTab areaTab = new SegmentationTab(SegmentationTab.Mode.AREA_RESULT);
    private final TimeSeriesMeasurementTab measurementTab = new TimeSeriesMeasurementTab();
    private final RoiExplorerPanel seedRoiPanel = new RoiExplorerPanel();
    private final TimeSeriesTrackTab trackTab = new TimeSeriesTrackTab();
    private final RoiExplorerPanel resultMeasurePanel = new RoiExplorerPanel();
    private final JSpinner autoTrackMaxDistanceSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 10000.0, 5.0));
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
    private ImageListener targetImageListener;
    private TimeSeriesTrackLinkerDialog linkerDialog;
    private Path linkerTracksRoot;
    private ImagePlus linkerImage;
    private boolean seedTrackSyncRunning;
    private final JSlider seedEditThresholdSlider = new JSlider(0, 65535, 1000);
    private final JTextField seedEditThresholdField = new JTextField("1000", 6);
    private final JCheckBox seedEditShowTinyCandidatesCheck = new JCheckBox("Show tiny candidates <", true);
    private final JSlider seedEditPreviewNoiseSlider = new JSlider(0, 1000, 0);
    private final JTextField seedEditPreviewNoiseField = new JTextField("0.0", 6);
    private final JButton btnSeedEditManualInclude = new JButton("Manual Include");
    private final JButton btnSeedEditCandidateColor = colorButton(new Color(0, 255, 255, 180), "Threshold candidate color");
    private final JButton btnSeedEditExistingColor = colorButton(new Color(255, 255, 0, 220), "Existing seed color");
    private final JButton btnSeedEditHoverColor = colorButton(new Color(255, 128, 0, 230), "Candidate hover color");
    private Color seedEditCandidateColor = new Color(0, 255, 255, 180);
    private Color seedEditExistingColor = new Color(255, 255, 0, 220);
    private Color seedEditHoverColor = new Color(255, 128, 0, 230);
    private Map<Integer, List<Roi>> seedEditAllCandidateRoisByLabel = Collections.emptyMap();
    private Map<Integer, List<Roi>> seedEditCandidateRoisByLabel = Collections.emptyMap();
    private Map<Integer, Long> seedEditCandidateVoxelCounts = Collections.emptyMap();
    private double seedEditCandidateVoxelVolume = 1.0;
    private double seedEditPreviewMinVolume = 0.0;
    private double seedEditPreviewMaxVolume = 1.0;
    private Integer seedEditHoverLabel;
    private MouseAdapter seedEditCandidatePickListener;
    private final List<ImageCanvas> seedEditCandidatePickCanvases = new ArrayList<ImageCanvas>();

    public static synchronized TimeSeriesWorkflowWindow getInstance() {
        if (instance == null) instance = new TimeSeriesWorkflowWindow();
        return instance;
    }

    private TimeSeriesWorkflowWindow() {
        super("Spot Quantifier Time Series");
        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        buildUI();
        seedRoiPanel.setDiskChangeListener(this::syncSeedTracksAfterSeedEdit);
        seedRoiPanel.setOverlayDecorator(this::decorateSeedEditCandidateOverlay);
        seedRoiPanel.setRegularOverlayColorOverride(seedEditExistingColor);
        loadPersistentSettings();
        pack();
        setMinimumSize(new Dimension(480, 600));
        setLocationRelativeTo(null);
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
        installTargetImageListener();
        seedTab.btnMakeSeedRoi.addActionListener(e -> cmdMakeSeedRois());
        areaTab.btnApply.addActionListener(e -> cmdAreaPreview());
        areaTab.btnMakeResultRoi.addActionListener(e -> cmdMakeResultRois());
        measurementTab.btnMeasure.addActionListener(e -> cmdMeasure());
        areaTab.setOverlayDecorator((target, overlay, projection) ->
            trackTab.addTrajectoryOverlay(target, overlay, projection));
        btnLoadProject.addActionListener(e -> cmdLoadProject());
        btnShowInFinder.addActionListener(e -> cmdShowInFinder());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                savePersistentSettings();
            }
            @Override public void windowClosed(WindowEvent e) {
                if (targetImageListener != null) ImagePlus.removeImageListener(targetImageListener);
                seedTab.onWindowClosing();
                areaTab.onWindowClosing();
                uninstallSeedEditCandidatePickMode();
                seedRoiPanel.onWindowClosing();
                resultMeasurePanel.onWindowClosing();
            }
        });
        tabs.addChangeListener(e -> {
            int current = tabs.getSelectedIndex();
            applyPreviewPolicy(current);
            previousTabIndex = current;
        });
        applyPreviewPolicy(0);
    }

    private JComponent buildTrackTab() {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        JButton btnAutoTrack = new JButton("Auto Track + Save");
        JButton btnLinker = new JButton("Open Linker...");
        btnAutoTrack.addActionListener(e -> cmdAutoTrackSeeds());
        btnLinker.addActionListener(e -> cmdOpenTrackLinker());
        autoTrackMaxDistanceSpinner.setToolTipText("0 = auto: max(25 px, 6 x object radius). Positive values are used as the maximum allowed centroid movement.");
        autoTrackMaxDistanceSpinner.setEditor(new JSpinner.NumberEditor(autoTrackMaxDistanceSpinner, "0.0"));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        actions.add(btnAutoTrack);
        actions.add(new JLabel("Max move px:"));
        actions.add(autoTrackMaxDistanceSpinner);
        actions.add(btnLinker);
        p.add(actions, BorderLayout.NORTH);
        p.add(trackTab, BorderLayout.CENTER);
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
            if (title == null || title.isEmpty() || "None".equals(title)) {
                clearTargetImageAndProject("Target image cleared.");
                return;
            }
            ImagePlus image = WindowManager.getImage(title);
            if (image != null) bindImage(image);
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
        // Track tab clears ImageJ overlays when deactivated; do that before any tab-specific overlay is drawn.
        trackTab.setActive(current == 2);
        if (current == 1) {
            resultMeasurePanel.setOverlayEnabled(false);
            RoiExplorerPreviewSupport.activateSeedEditPreview(seedRoiPanel, seedTab, areaTab,
                boundImage, zprojImage);
        } else if (previousTabIndex == 1) {
            seedRoiPanel.setOverlayEnabled(false);
        } else {
            seedRoiPanel.setOverlayEnabled(false);
        }
        if (current == 0) {
            resultMeasurePanel.setOverlayEnabled(false);
            areaTab.setPreviewActive(false, false);
            seedTab.setPreviewActive(true, true);
        } else if (current == 3) {
            resultMeasurePanel.setOverlayEnabled(false);
            areaTab.setPreviewActive(false, false);
            seedTab.clearOverlayOnly();
            areaTab.setPreviewActive(true, true);
        } else if (current != 1) {
            resultMeasurePanel.setOverlayEnabled(current == 4);
            seedTab.setPreviewActive(false, false);
            areaTab.setPreviewActive(false, false);
        }
    }

    private void syncSharedParamsToTabs() {
        int ch = (Integer) channelSpinner.getValue();
        String zproj = (String) zprojCombo.getSelectedItem();
        seedTab.setExternalChannel(ch);
        areaTab.setExternalChannel(ch);
        updateSeedEditThresholdRangeFromTabs();
        seedTab.setExternalZProjTitle(zproj);
        areaTab.setExternalZProjTitle(zproj);
        syncSeedEditSubImage();
        syncTrackSubImage();
    }

    private void loadPersistentSettings() {
        WorkflowPreferences prefs = WorkflowPreferences.loadWithPrefix(WorkflowPreferences.TIME_SERIES_PREFIX);
        preferredChannel = Math.max(1, prefs.preferredChannel);
        SpinnerNumberModel channelModel = (SpinnerNumberModel) channelSpinner.getModel();
        channelSyncing = true;
        channelModel.setMaximum(Math.max(1, preferredChannel));
        channelSpinner.setValue(preferredChannel);
        channelSyncing = false;
        seedTab.setParams(prefs.params);
        areaTab.setParams(prefs.params);
        seedTab.setPreviewSettings(prefs.seedPreview);
        areaTab.setPreviewSettings(prefs.areaPreview);
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
        prefs.areaPreview.copyFrom(areaTab.getPreviewSettings());
        prefs.measurementSaveCsv = measurementTab.isSaveCsvSelected();
        prefs.measurementShowTable = measurementTab.isShowTableSelected();
        prefs.measurementColumns = measurementTab.getSelectedColumns();
        prefs.saveWithPrefix(WorkflowPreferences.TIME_SERIES_PREFIX);
    }

    private SegmentationParams currentWorkflowParams() {
        SegmentationParams seedParams = seedTab.getParams();
        SegmentationParams areaParams = areaTab.getParams();
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

    private void syncSeedEditSubImage() {
        RoiExplorerPreviewSupport.syncSubImage(seedRoiPanel, boundImage, zprojImage);
    }

    private void syncTrackSubImage() {
        if (boundImage == null || zprojImage == null || zprojImage == boundImage) {
            trackTab.setSubImage(null);
        } else {
            trackTab.setSubImage(zprojImage);
        }
    }

    private JPanel buildRoiEditTab(RoiExplorerPanel panel) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        p.add(buildSeedEditManualPanel(), BorderLayout.NORTH);
        p.add(panel, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildSeedEditManualPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder("Seed Edit Manual Include / Exclude"));
        JButton applyCandidates = new JButton("Apply Threshold Candidates");
        JButton clearCandidates = new JButton("Clear Threshold Candidates");
        JButton exclude = new JButton("Exclude Selected Object");
        seedEditThresholdSlider.setPaintTicks(false);
        seedEditThresholdField.setMaximumSize(seedEditThresholdField.getPreferredSize());
        seedEditPreviewNoiseField.setMaximumSize(seedEditPreviewNoiseField.getPreferredSize());
        applyCandidates.setToolTipText("Recompute threshold candidates for the current T.");
        btnSeedEditManualInclude.setToolTipText("Click a threshold candidate on the main image or Z-proj to add it as manual_###.");
        exclude.setToolTipText("Delete selected seed object folder(s) from seed_rois_untracked.");
        seedEditThresholdSlider.addChangeListener(e -> {
            seedEditThresholdField.setText(String.valueOf(seedEditThresholdSlider.getValue()));
        });
        seedEditThresholdField.addActionListener(e -> commitSeedEditThresholdField());
        seedEditShowTinyCandidatesCheck.addActionListener(e -> rebuildSeedEditCandidatePreview());
        seedEditPreviewNoiseSlider.addChangeListener(e -> {
            seedEditPreviewNoiseField.setText(formatVolume(seedEditNoiseSliderToVolume()));
            if (!seedEditPreviewNoiseSlider.getValueIsAdjusting()) rebuildSeedEditCandidatePreview();
        });
        seedEditPreviewNoiseField.addActionListener(e -> commitSeedEditPreviewNoiseField());
        applyCandidates.addActionListener(e -> cmdSeedEditApplyThresholdCandidates());
        clearCandidates.addActionListener(e -> clearSeedEditThresholdCandidates());
        btnSeedEditManualInclude.addActionListener(e -> installSeedEditCandidatePickMode());
        exclude.addActionListener(e -> cmdSeedEditManualExclude());
        btnSeedEditCandidateColor.addActionListener(e -> chooseSeedEditColor("Threshold candidate", true, false, false));
        btnSeedEditExistingColor.addActionListener(e -> chooseSeedEditColor("Existing seed", false, true, false));
        btnSeedEditHoverColor.addActionListener(e -> chooseSeedEditColor("Candidate hover", false, false, true));

        JPanel thresholdRow = new JPanel(new BorderLayout(6, 0));
        thresholdRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel labelRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        labelRow.add(new JLabel("Seed threshold:"));
        labelRow.add(seedEditThresholdField);
        thresholdRow.add(labelRow, BorderLayout.WEST);
        thresholdRow.add(seedEditThresholdSlider, BorderLayout.CENTER);

        JPanel noiseRow = new JPanel(new BorderLayout(6, 0));
        noiseRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        noiseRow.add(seedEditShowTinyCandidatesCheck, BorderLayout.WEST);
        noiseRow.add(seedEditPreviewNoiseSlider, BorderLayout.CENTER);
        noiseRow.add(seedEditPreviewNoiseField, BorderLayout.EAST);

        JPanel colorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        colorRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        colorRow.add(new JLabel("Candidate:"));
        colorRow.add(btnSeedEditCandidateColor);
        colorRow.add(new JLabel("Existing seed:"));
        colorRow.add(btnSeedEditExistingColor);
        colorRow.add(new JLabel("Hover:"));
        colorRow.add(btnSeedEditHoverColor);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actionRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionRow.add(applyCandidates);
        actionRow.add(clearCandidates);
        JPanel editRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        editRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        editRow.add(btnSeedEditManualInclude);
        editRow.add(exclude);
        p.add(thresholdRow);
        p.add(noiseRow);
        p.add(colorRow);
        p.add(actionRow);
        p.add(editRow);
        return p;
    }

    private void cmdSeedEditApplyThresholdCandidates() {
        if (projectFolder == null || boundImage == null) {
            setStatus("Load a project and target image before Seed Edit threshold preview.");
            return;
        }
        int threshold = seedEditThresholdSlider.getValue();
        int channel = (Integer) channelSpinner.getValue();
        int time = currentTargetT();
        setStatus("Computing Seed Edit threshold candidates...");
        new SwingWorker<SeedEditCandidateResult, Void>() {
            @Override protected SeedEditCandidateResult doInBackground() throws Exception {
                ImagePlus channelTime = TimeSeriesSegmentationController.extractChannelTime(boundImage, channel, time);
                try {
                    SegmentationParams params = seedTab.getParams();
                    params.seedThreshold = threshold;
                    CcResult3D cc = SpotQuantifier3D.computeCCFromBlurred(channelTime, threshold, params.toQuantifierParams());
                    Map<Integer, List<Roi>> rois = new RoiExporter3D().exportToRoiListsByLabel(cc.labelImage,
                        seedEditCandidateColor, boundImage, channel, time);
                    return new SeedEditCandidateResult(rois, cc.voxelCounts, calibrationVoxelVolume(boundImage));
                } finally {
                    channelTime.flush();
                }
            }
            @Override protected void done() {
                try {
                    SeedEditCandidateResult result = get();
                    seedEditAllCandidateRoisByLabel = result.roisByLabel;
                    seedEditCandidateVoxelCounts = result.voxelCounts;
                    seedEditCandidateVoxelVolume = result.voxelVolume;
                    updateSeedEditPreviewNoiseRange();
                    rebuildSeedEditCandidatePreview();
                    seedEditHoverLabel = null;
                    setStatus("Seed Edit candidates: " + seedEditCandidateRoisByLabel.size()
                        + ". Press Manual Include to pick a candidate.");
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    setStatus("Seed Edit threshold error: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private void cmdSeedEditManualInclude(Integer label) {
        if (projectFolder == null || boundImage == null) {
            setStatus("Load a project and target image before manual include.");
            return;
        }
        List<Roi> rois = seedEditCandidateRoisByLabel.get(label);
        if (rois == null || rois.isEmpty()) {
            setStatus("No threshold candidate selected.");
            return;
        }
        try {
            Path objectFolder = nextManualSeedObjectFolder(currentSeedTimeRoot());
            Files.createDirectories(objectFolder);
            int index = 1;
            for (Roi roi : rois) {
                Roi copy = (Roi) roi.clone();
                copy.setStrokeColor(seedEditExistingColor);
                new RoiEncoder(objectFolder.resolve(String.format("roi-%03d.roi", index++)).toString()).write(copy);
            }
            reloadSeedEditAfterManualChange();
            setStatus("Manual seed included: " + objectFolder.getFileName());
        } catch (Exception e) {
            setStatus("Manual include error: " + e.getMessage());
            JOptionPane.showMessageDialog(this, e.getMessage(), "Seed Edit Manual Include", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void installSeedEditCandidatePickMode() {
        if (seedEditCandidateRoisByLabel == null || seedEditCandidateRoisByLabel.isEmpty()) {
            setStatus("Apply threshold candidates before Manual Include.");
            return;
        }
        uninstallSeedEditCandidatePickMode();
        seedEditCandidatePickListener = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { handleSeedEditCandidateMove(e); }
            @Override public void mouseExited(MouseEvent e) { clearSeedEditCandidateHover(); }
            @Override public void mouseClicked(MouseEvent e) { handleSeedEditCandidateClick(e); }
        };
        addSeedEditCandidateCanvas(boundImage);
        addSeedEditCandidateCanvas(zprojImage);
        updateSeedEditManualIncludeButtonState();
        setStatus("Manual include: click threshold candidate on image or Z-proj.");
    }

    private void addSeedEditCandidateCanvas(ImagePlus image) {
        if (image == null || image.getCanvas() == null || seedEditCandidatePickListener == null) return;
        ImageCanvas canvas = image.getCanvas();
        if (seedEditCandidatePickCanvases.contains(canvas)) return;
        canvas.addMouseMotionListener(seedEditCandidatePickListener);
        canvas.addMouseListener(seedEditCandidatePickListener);
        seedEditCandidatePickCanvases.add(canvas);
    }

    private void uninstallSeedEditCandidatePickMode() {
        if (seedEditCandidatePickListener != null) {
            for (ImageCanvas canvas : new ArrayList<ImageCanvas>(seedEditCandidatePickCanvases)) {
                canvas.removeMouseMotionListener(seedEditCandidatePickListener);
                canvas.removeMouseListener(seedEditCandidatePickListener);
            }
        }
        seedEditCandidatePickCanvases.clear();
        seedEditCandidatePickListener = null;
        clearSeedEditCandidateHover();
        updateSeedEditManualIncludeButtonState();
    }

    private void updateSeedEditManualIncludeButtonState() {
        boolean picking = seedEditCandidatePickListener != null;
        btnSeedEditManualInclude.setText(picking ? "Picking Include..." : "Manual Include");
        btnSeedEditManualInclude.setBackground(picking ? new Color(255, 236, 179) : UIManager.getColor("Button.background"));
        btnSeedEditManualInclude.setForeground(picking ? new Color(102, 60, 0) : UIManager.getColor("Button.foreground"));
    }

    private void handleSeedEditCandidateMove(MouseEvent e) {
        Integer label = seedEditCandidateAt(e);
        if (java.util.Objects.equals(seedEditHoverLabel, label)) return;
        seedEditHoverLabel = label;
        seedRoiPanel.refreshOverlay();
    }

    private void handleSeedEditCandidateClick(MouseEvent e) {
        Integer label = seedEditCandidateAt(e);
        if (label == null) {
            setStatus("No threshold candidate at click.");
            return;
        }
        cmdSeedEditManualInclude(label);
        e.consume();
    }

    private Integer seedEditCandidateAt(MouseEvent e) {
        if (!(e.getSource() instanceof ImageCanvas)) return null;
        ImageCanvas canvas = (ImageCanvas) e.getSource();
        Point point = new Point(canvas.offScreenX(e.getX()), canvas.offScreenY(e.getY()));
        boolean projection = zprojImage != null && canvas == zprojImage.getCanvas();
        int z = Math.max(1, boundImage != null ? boundImage.getZ() : 1);
        int t = projection && zprojImage != null && zprojImage.getNFrames() > 1
            ? Math.max(1, zprojImage.getT())
            : currentTargetT();
        List<Integer> hits = new ArrayList<Integer>();
        for (Map.Entry<Integer, List<Roi>> entry : seedEditCandidateRoisByLabel.entrySet()) {
            for (Roi roi : entry.getValue()) {
                int rt = roi.getTPosition();
                if (rt > 0 && rt != t) continue;
                int rz = roi.getZPosition() > 0 ? roi.getZPosition() : roi.getPosition();
                if (!projection && rz > 0 && rz != z) continue;
                if (roi.contains(point.x, point.y)) {
                    hits.add(entry.getKey());
                    break;
                }
            }
        }
        if (hits.isEmpty()) return null;
        Collections.sort(hits);
        return hits.get(0);
    }

    private void clearSeedEditCandidateHover() {
        if (seedEditHoverLabel == null) return;
        seedEditHoverLabel = null;
        seedRoiPanel.refreshOverlay();
    }

    private void clearSeedEditThresholdCandidates() {
        seedEditAllCandidateRoisByLabel = Collections.emptyMap();
        seedEditCandidateRoisByLabel = Collections.emptyMap();
        seedEditCandidateVoxelCounts = Collections.emptyMap();
        seedEditHoverLabel = null;
        uninstallSeedEditCandidatePickMode();
        seedRoiPanel.refreshOverlay();
        setStatus("Cleared Seed Edit threshold candidates.");
    }

    private void decorateSeedEditCandidateOverlay(Overlay overlay, ImagePlus image, boolean subImage) {
        if (overlay == null || image == null || seedEditCandidateRoisByLabel == null
            || seedEditCandidateRoisByLabel.isEmpty()) return;
        boolean projection = zprojImage != null && image == zprojImage;
        int z = Math.max(1, projection ? 1 : image.getZ());
        int t = projection && image.getNFrames() > 1 ? Math.max(1, image.getT()) : currentTargetT();
        for (Map.Entry<Integer, List<Roi>> entry : seedEditCandidateRoisByLabel.entrySet()) {
            Color color = java.util.Objects.equals(seedEditHoverLabel, entry.getKey())
                ? seedEditHoverColor : seedEditCandidateColor;
            if (projection) {
                Roi projected = mergeSeedEditCandidateForProjection(entry.getValue(), t);
                if (projected != null) {
                    projected.setStrokeColor(color);
                    if (image.isHyperStack()) projected.setPosition(0, 0, Math.max(1, image.getT()));
                    else projected.setPosition(Math.max(1, image.getCurrentSlice()));
                    overlay.add(projected);
                }
                continue;
            }
            for (Roi roi : entry.getValue()) {
                if (roi == null) continue;
                int rt = roi.getTPosition();
                if (rt > 0 && rt != t) continue;
                int rz = roi.getZPosition() > 0 ? roi.getZPosition() : roi.getPosition();
                if (!projection && rz > 0 && rz != z) continue;
                Roi copy = (Roi) roi.clone();
                copy.setFillColor(null);
                copy.setStrokeColor(color);
                overlay.add(copy);
            }
        }
    }

    private Roi mergeSeedEditCandidateForProjection(List<Roi> rois, int t) {
        ShapeRoi merged = null;
        if (rois == null) return null;
        for (Roi roi : rois) {
            if (roi == null) continue;
            int rt = roi.getTPosition();
            if (rt > 0 && rt != t) continue;
            Roi clone = (Roi) roi.clone();
            clone.setPosition(0);
            clone.setPosition(0, 0, 0);
            clone.setFillColor(null);
            ShapeRoi sr = new ShapeRoi(clone);
            merged = merged == null ? sr : merged.or(sr);
        }
        if (merged == null) return null;
        merged.setFillColor(null);
        merged.setPosition(0);
        return merged;
    }

    private void commitSeedEditThresholdField() {
        try {
            int value = Integer.parseInt(seedEditThresholdField.getText().trim());
            value = Math.max(seedEditThresholdSlider.getMinimum(), Math.min(value, seedEditThresholdSlider.getMaximum()));
            seedEditThresholdSlider.setValue(value);
            seedEditThresholdField.setText(String.valueOf(value));
        } catch (NumberFormatException e) {
            seedEditThresholdField.setText(String.valueOf(seedEditThresholdSlider.getValue()));
        }
    }

    private void updateSeedEditThresholdRange(ImagePlus image) {
        if (image == null) {
            seedEditThresholdSlider.setMinimum(0);
            seedEditThresholdSlider.setMaximum(1);
            seedEditThresholdSlider.setValue(0);
            seedEditThresholdField.setText("0");
            return;
        }
        updateSeedEditThresholdRangeFromTabs();
    }

    private void updateSeedEditThresholdRangeFromTabs() {
        int min = seedTab.getThresholdRangeMin();
        int max = Math.max(min + 1, seedTab.getThresholdRangeMax());
        seedEditThresholdSlider.setMinimum(min);
        seedEditThresholdSlider.setMaximum(max);
        int value = Math.max(min, Math.min(seedTab.getParams().seedThreshold, max));
        seedEditThresholdSlider.setValue(value);
        seedEditThresholdField.setText(String.valueOf(value));
    }

    private void chooseSeedEditColor(String title, boolean candidate, boolean existing, boolean hover) {
        Color current = candidate ? seedEditCandidateColor : existing ? seedEditExistingColor : seedEditHoverColor;
        Color chosen = JColorChooser.showDialog(this, title + " color", current);
        if (chosen == null) return;
        Color withAlpha = new Color(chosen.getRed(), chosen.getGreen(), chosen.getBlue(), current.getAlpha());
        if (candidate) {
            seedEditCandidateColor = withAlpha;
            btnSeedEditCandidateColor.setBackground(withAlpha);
        } else if (existing) {
            seedEditExistingColor = withAlpha;
            btnSeedEditExistingColor.setBackground(withAlpha);
            seedRoiPanel.setRegularOverlayColorOverride(withAlpha);
        } else if (hover) {
            seedEditHoverColor = withAlpha;
            btnSeedEditHoverColor.setBackground(withAlpha);
        }
        seedRoiPanel.refreshOverlay();
    }

    private void rebuildSeedEditCandidatePreview() {
        if (seedEditAllCandidateRoisByLabel == null || seedEditAllCandidateRoisByLabel.isEmpty()) {
            seedEditCandidateRoisByLabel = Collections.emptyMap();
            seedRoiPanel.refreshOverlay();
            return;
        }
        Double min = seedEditShowTinyCandidatesCheck.isSelected() ? null : effectiveSeedEditNoiseMin();
        Map<Integer, List<Roi>> visible = new LinkedHashMap<Integer, List<Roi>>();
        int hidden = 0;
        for (Map.Entry<Integer, List<Roi>> entry : seedEditAllCandidateRoisByLabel.entrySet()) {
            Long voxels = seedEditCandidateVoxelCounts.get(entry.getKey());
            double volume = (voxels != null ? voxels : 0L) * seedEditCandidateVoxelVolume;
            if (min == null || volume >= min) {
                visible.put(entry.getKey(), entry.getValue());
            } else {
                hidden++;
            }
        }
        seedEditCandidateRoisByLabel = visible;
        if (seedEditHoverLabel != null && !visible.containsKey(seedEditHoverLabel)) seedEditHoverLabel = null;
        seedRoiPanel.refreshOverlay();
        setStatus(visible.size() + " candidates visible" + (hidden > 0 ? ", " + hidden + " hidden" : ""));
    }

    private void updateSeedEditPreviewNoiseRange() {
        double min = Double.POSITIVE_INFINITY;
        double max = 0.0;
        for (Long voxels : seedEditCandidateVoxelCounts.values()) {
            double volume = (voxels != null ? voxels : 0L) * seedEditCandidateVoxelVolume;
            if (volume > 0 && volume < min) min = volume;
            if (volume > max) max = volume;
        }
        if (!Double.isFinite(min)) min = 0.0;
        if (max <= min) max = min + 1.0;
        seedEditPreviewMinVolume = Math.max(0.0, min);
        seedEditPreviewMaxVolume = max;
        seedEditPreviewNoiseSlider.setValue(seedEditVolumeToNoiseSlider(parseDoubleOrDefault(seedEditPreviewNoiseField.getText(), seedEditPreviewMinVolume)));
        seedEditPreviewNoiseField.setText(formatVolume(seedEditNoiseSliderToVolume()));
    }

    private void commitSeedEditPreviewNoiseField() {
        seedEditPreviewNoiseSlider.setValue(seedEditVolumeToNoiseSlider(parseDoubleOrDefault(seedEditPreviewNoiseField.getText(), seedEditPreviewMinVolume)));
        seedEditPreviewNoiseField.setText(formatVolume(seedEditNoiseSliderToVolume()));
        rebuildSeedEditCandidatePreview();
    }

    private Double effectiveSeedEditNoiseMin() {
        double value = parseDoubleOrDefault(seedEditPreviewNoiseField.getText(), seedEditPreviewMinVolume);
        if (value <= seedEditPreviewMinVolume * 1.0000001) return null;
        return value;
    }

    private int seedEditVolumeToNoiseSlider(double volume) {
        double min = Math.max(seedEditPreviewMinVolume, 1.0e-12);
        double max = Math.max(min * 1.000001, seedEditPreviewMaxVolume);
        double safe = Math.max(min, Math.min(max, volume));
        double range = Math.log(max) - Math.log(min);
        if (range <= 0) return 0;
        return (int) Math.round(((Math.log(safe) - Math.log(min)) / range) * 1000.0);
    }

    private double seedEditNoiseSliderToVolume() {
        double min = Math.max(seedEditPreviewMinVolume, 1.0e-12);
        double max = Math.max(min * 1.000001, seedEditPreviewMaxVolume);
        double t = Math.max(0.0, Math.min(1.0, seedEditPreviewNoiseSlider.getValue() / 1000.0));
        if (t <= 0.0) return min;
        if (t >= 1.0) return max;
        return Math.exp(Math.log(min) + t * (Math.log(max) - Math.log(min)));
    }

    private static double parseDoubleOrDefault(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String formatVolume(double value) {
        if (value >= 100.0) return String.format(java.util.Locale.US, "%.0f", value);
        if (value >= 10.0) return String.format(java.util.Locale.US, "%.1f", value);
        return String.format(java.util.Locale.US, "%.3f", value);
    }

    private static double calibrationVoxelVolume(ImagePlus image) {
        if (image == null || image.getCalibration() == null) return 1.0;
        double x = image.getCalibration().pixelWidth > 0 ? image.getCalibration().pixelWidth : 1.0;
        double y = image.getCalibration().pixelHeight > 0 ? image.getCalibration().pixelHeight : 1.0;
        double z = image.getCalibration().pixelDepth > 0 ? image.getCalibration().pixelDepth : 1.0;
        return x * y * z;
    }

    private static final class SeedEditCandidateResult {
        final Map<Integer, List<Roi>> roisByLabel;
        final Map<Integer, Long> voxelCounts;
        final double voxelVolume;

        SeedEditCandidateResult(Map<Integer, List<Roi>> roisByLabel,
                                Map<Integer, Long> voxelCounts,
                                double voxelVolume) {
            this.roisByLabel = roisByLabel != null ? roisByLabel : Collections.emptyMap();
            this.voxelCounts = voxelCounts != null ? voxelCounts : Collections.emptyMap();
            this.voxelVolume = voxelVolume > 0 ? voxelVolume : 1.0;
        }
    }

    private static JButton colorButton(Color color, String tooltip) {
        JButton button = new JButton(" ");
        button.setPreferredSize(new Dimension(28, 18));
        button.setMinimumSize(new Dimension(28, 18));
        button.setBackground(color);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        return button;
    }

    private void cmdSeedEditManualExclude() {
        if (projectFolder == null) {
            setStatus("Load a project before manual exclude.");
            return;
        }
        java.util.List<Path> selected = seedRoiPanel.getSelectedPaths();
        if (selected.isEmpty()) {
            setStatus("Select seed object folder(s) in Seed Edit first.");
            return;
        }
        java.util.LinkedHashSet<Path> objectFolders = new java.util.LinkedHashSet<Path>();
        for (Path path : selected) {
            Path objectFolder = seedObjectFolderForPath(path);
            if (objectFolder != null && Files.exists(objectFolder)) objectFolders.add(objectFolder);
        }
        if (objectFolders.isEmpty()) {
            setStatus("Manual exclude needs seed object folder selection.");
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
            "Exclude " + objectFolders.size() + " selected seed object(s)?",
            "Seed Edit Manual Exclude", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        try {
            for (Path objectFolder : objectFolders) deleteTree(objectFolder);
            reloadSeedEditAfterManualChange();
            setStatus("Manual seed excluded: " + objectFolders.size() + " object(s).");
        } catch (Exception e) {
            setStatus("Manual exclude error: " + e.getMessage());
            JOptionPane.showMessageDialog(this, e.getMessage(), "Seed Edit Manual Exclude", JOptionPane.ERROR_MESSAGE);
        }
    }

    private Path currentSeedTimeRoot() {
        return projectFolder.resolve("seed_rois_untracked")
            .resolve(String.format("t%03d", currentTargetT()));
    }

    private int currentTargetT() {
        if (boundImage == null) return 1;
        if (boundImage.isHyperStack() || boundImage.getNFrames() > 1) return Math.max(1, boundImage.getT());
        return 1;
    }

    private static Path nextManualSeedObjectFolder(Path timeRoot) throws Exception {
        Files.createDirectories(timeRoot);
        for (int i = 1; i < 100000; i++) {
            Path candidate = timeRoot.resolve(String.format("manual_%03d", i));
            if (!Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("No available manual seed object name.");
    }

    private Path seedObjectFolderForPath(Path path) {
        if (projectFolder == null || path == null) return null;
        Path root = projectFolder.resolve("seed_rois_untracked").toAbsolutePath().normalize();
        Path p = path.toAbsolutePath().normalize();
        if (!p.startsWith(root)) return null;
        Path rel = root.relativize(p);
        if (rel.getNameCount() < 2) return null;
        return root.resolve(rel.getName(0)).resolve(rel.getName(1));
    }

    private void reloadSeedEditAfterManualChange() {
        Path root = projectFolder.resolve("seed_rois_untracked");
        openSeedRoiRoot(root);
        syncSeedTracksAfterSeedEdit();
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
        imageCombo.addItem("None");
        if (ids != null) {
            for (int id : ids) {
                ImagePlus image = WindowManager.getImage(id);
                if (image != null && (image.getNSlices() > 1 || image.getNFrames() > 1)) {
                    imageCombo.addItem(image.getTitle());
                }
            }
        }
        if (selected != null) imageCombo.setSelectedItem(selected);
        if (imageCombo.getSelectedIndex() < 0) imageCombo.setSelectedIndex(0);
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
        if (image == null) return;
        ImagePlus previous = boundImage;
        boolean targetChanged = previous != null && previous != image;
        if (targetChanged) clearProjectForTargetChange();
        boundImage = image;
        updateSeedEditThresholdRange(image);
        setImageComboSelection(image.getTitle());
        int nCh = image != null ? Math.max(1, image.getNChannels()) : 1;
        int safeChannel = Math.max(1, Math.min(preferredChannel, nCh));
        channelSyncing = true;
        ((SpinnerNumberModel) channelSpinner.getModel()).setMaximum(nCh);
        channelSpinner.setValue(safeChannel);
        channelSyncing = false;
        preferredChannel = safeChannel;
        seedTab.updateImage(image);
        areaTab.updateImage(image);
        trackTab.setImage(image);
        RoiExplorerPreviewSupport.configureSeedEditPanel(seedRoiPanel, image, zprojImage);
        refreshZProjCombo();
        syncSharedParamsToTabs();
        if (image == null) {
            setStatus("No image selected.");
        } else if (image.getNFrames() <= 1) {
            setStatus("Time Series workflow requires T > 1.");
        } else {
            setStatus(targetChanged
                ? "Bound " + image.getTitle() + " (" + image.getNFrames() + " frames). Project cleared for target image change."
                : "Bound " + image.getTitle() + " (" + image.getNFrames() + " frames).");
        }
    }

    private void setImageComboSelection(String title) {
        if (title == null || title.isEmpty()) return;
        comboSyncing = true;
        boolean found = false;
        for (int i = 0; i < imageCombo.getItemCount(); i++) {
            if (title.equals(imageCombo.getItemAt(i))) {
                found = true;
                break;
            }
        }
        if (!found) imageCombo.addItem(title);
        imageCombo.setSelectedItem(title);
        comboSyncing = false;
    }

    private void clearProjectForTargetChange() {
        seedTab.clearPreview();
        areaTab.clearPreview();
        seedEditAllCandidateRoisByLabel = Collections.emptyMap();
        seedEditCandidateRoisByLabel = Collections.emptyMap();
        seedEditCandidateVoxelCounts = Collections.emptyMap();
        uninstallSeedEditCandidatePickMode();
        seedRoiPanel.closeFolder();
        resultMeasurePanel.closeFolder();
        projectFolder = null;
        projectField.setText("");
        projectField.setToolTipText(null);
        trackTab.setTracksRoot(null);
        refreshMeasurementResultFolders(null);
    }

    private void clearTargetImageAndProject(String status) {
        boundImage = null;
        clearProjectForTargetChange();
        seedTab.updateImage(null);
        areaTab.updateImage(null);
        trackTab.setImage(null);
        zprojImage = null;
        refreshZProjCombo();
        syncSharedParamsToTabs();
        if (status != null && !status.isEmpty()) setStatus(status);
    }

    private void installTargetImageListener() {
        targetImageListener = new ImageListener() {
            @Override public void imageOpened(ImagePlus imp) {}
            @Override public void imageUpdated(ImagePlus imp) {}
            @Override public void imageClosed(ImagePlus imp) {
                if (imp != boundImage) return;
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
        if (!seedTab.hasCurrentSeedRoiCache()) {
            setStatus("Press Apply before Make / Update Seed ROI.");
            JOptionPane.showMessageDialog(this,
                "Make / Update Seed ROI saves the current preview cache, including manual include/exclude edits.\n"
                    + "Press Apply first, then run Make / Update Seed ROI.",
                "Time Series Seed", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        final java.util.List<java.util.List<ij.gui.Roi>> objectRois = seedTab.getCurrentSeedRoiObjects();
        new SwingWorker<Path, String>() {
            @Override protected Path doInBackground() throws Exception {
                return segmentationCtrl.saveUntrackedSeedRois(project, params, objectRois, this::publish);
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
        final double maxMovePx = ((Number) autoTrackMaxDistanceSpinner.getValue()).doubleValue();
        new SwingWorker<Path, String>() {
            @Override protected Path doInBackground() throws Exception {
                return trackCtrl.buildTracks(projectFolder, maxMovePx, this::publish);
            }
            @Override protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) setStatus(chunks.get(chunks.size() - 1));
            }
            @Override protected void done() {
                try {
                    Path root = get();
                    trackTab.setTracksRoot(root);
                    setStatus("Seed tracks saved: " + root);
                    tabs.setSelectedIndex(2);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    setStatus("Track error: " + cause.getMessage());
                    JOptionPane.showMessageDialog(TimeSeriesWorkflowWindow.this,
                        cause.getMessage(), "Seed Track", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void cmdOpenTrackLinker() {
        if (projectFolder == null || boundImage == null) {
            setStatus("Run Seed Track first.");
            return;
        }
        Path tracksRoot = projectFolder.resolve("seed_tracks");
        if (!Files.isDirectory(tracksRoot)) {
            setStatus("Missing seed_tracks. Run Auto Track + Save first.");
            return;
        }
        Path workingRoot = projectFolder.resolve("seed_tracks_working");
        if (linkerDialog != null && linkerDialog.isDisplayable()) {
            if (workingRoot.equals(linkerTracksRoot) && boundImage == linkerImage) {
                linkerDialog.focusLinker();
                return;
            }
            if (!linkerDialog.requestClose()) return;
            linkerDialog = null;
        }
        try {
            prepareWorkingTrackRoot(tracksRoot, workingRoot);
            linkerTracksRoot = workingRoot;
            linkerImage = boundImage;
            linkerDialog = new TimeSeriesTrackLinkerDialog(this, boundImage, workingRoot, () -> {
                try {
                    commitWorkingTrackRoot(workingRoot, tracksRoot);
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
                trackTab.setTracksRoot(tracksRoot);
                setStatus("Tracking committed: " + tracksRoot);
            }, this::openSeedEditAtObject);
            linkerDialog.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent e) {
                    if (e.getWindow() == linkerDialog) {
                        linkerDialog = null;
                        linkerTracksRoot = null;
                        linkerImage = null;
                    }
                }
            });
            linkerDialog.setVisible(true);
        } catch (Exception e) {
            setStatus("Linker error: " + e.getMessage());
            JOptionPane.showMessageDialog(this, e.getMessage(), "Track Linker", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void prepareWorkingTrackRoot(Path source, Path working) throws Exception {
        deleteTree(working);
        copyDirectory(source, working);
    }

    private void commitWorkingTrackRoot(Path working, Path target) throws Exception {
        if (!Files.isDirectory(working)) {
            throw new IllegalArgumentException("Missing seed_tracks_working.");
        }
        deleteTree(target);
        copyDirectory(working, target);
    }

    private static void copyDirectory(Path source, Path target) throws Exception {
        Files.createDirectories(target);
        try (java.util.stream.Stream<Path> stream = Files.walk(source)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path rel = source.relativize(path);
                Path dst = target.resolve(rel);
                if (Files.isDirectory(path)) Files.createDirectories(dst);
                else Files.copy(path, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteTree(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            java.util.List<Path> paths = new java.util.ArrayList<Path>();
            stream.forEach(paths::add);
            paths.sort(java.util.Comparator.reverseOrder());
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }

    private void syncSeedTracksAfterSeedEdit() {
        if (projectFolder == null || seedTrackSyncRunning) return;
        Path tracksRoot = linkerDialog != null && linkerDialog.isDisplayable() && linkerTracksRoot != null
            ? linkerTracksRoot
            : projectFolder.resolve("seed_tracks");
        if (!Files.isDirectory(tracksRoot)) return;
        final boolean syncingWorking = tracksRoot.getFileName() != null
            && "seed_tracks_working".equals(tracksRoot.getFileName().toString());
        seedTrackSyncRunning = true;
        new SwingWorker<Path, String>() {
            @Override protected Path doInBackground() throws Exception {
                return trackCtrl.syncTracksWithUntrackedSeedRois(projectFolder, tracksRoot, this::publish);
            }
            @Override protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) setStatus(chunks.get(chunks.size() - 1));
            }
            @Override protected void done() {
                seedTrackSyncRunning = false;
                try {
                    Path root = get();
                    trackTab.setTracksRoot(syncingWorking ? projectFolder.resolve("seed_tracks") : root);
                    if (linkerDialog != null && linkerDialog.isDisplayable()) linkerDialog.reloadFromDisk(syncingWorking);
                    setStatus("Seed Track synced from Seed Edit.");
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    setStatus("Seed Track sync error: " + cause.getMessage());
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
        setStatus("Measuring " + measurementTab.selectedPresetLabel() + ": " + resultRoot.getFileName());
        final MeasurementRequest request = measurementTab.buildRequest(csvPath);
        new SwingWorker<MeasurementResult, String>() {
            @Override protected MeasurementResult doInBackground() {
                return measureCtrl.measure(request.withProgress(msg -> publish(msg)));
            }
            @Override protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) setStatus(chunks.get(chunks.size() - 1));
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
        RoiExplorerPreviewSupport.configureSeedEditPanel(seedRoiPanel, boundImage, zprojImage);
        seedRoiPanel.openFolder(root);
    }

    private void openSeedEditAtObject(Path objectPath) {
        if (objectPath == null || !Files.exists(objectPath) || boundImage == null) {
            setStatus("Selected seed object is not available on disk.");
            return;
        }
        Path root = projectFolder != null ? projectFolder.resolve("seed_rois_untracked") : null;
        if (root == null || !Files.isDirectory(root)) {
            setStatus("Could not open seed_rois_untracked.");
            return;
        }
        Path seedObjectPath = resolveUntrackedSeedObjectPath(root, objectPath);
        RoiExplorerPreviewSupport.configureSeedEditPanel(seedRoiPanel, boundImage, zprojImage);
        seedRoiPanel.openFolder(root);
        restoreSeedRoiSelection(seedObjectPath);
        tabs.setSelectedIndex(1);
        bringWorkflowToFront();
        setStatus("Opened Seed Edit: " + seedObjectPath.getFileName());
    }

    private void bringWorkflowToFront() {
        SwingUtilities.invokeLater(() -> {
            if ((getExtendedState() & Frame.ICONIFIED) != 0) {
                setExtendedState(getExtendedState() & ~Frame.ICONIFIED);
            }
            toFront();
            requestFocus();
        });
    }

    private Path resolveUntrackedSeedObjectPath(Path seedRoot, Path objectPath) {
        if (objectPath != null && objectPath.startsWith(seedRoot) && Files.exists(objectPath)) return objectPath;
        String name = objectPath != null && objectPath.getFileName() != null
            ? objectPath.getFileName().toString() : "";
        int marker = name.indexOf("__");
        if (marker < 0) return objectPath;
        String sourceId = name.substring(marker + "__".length());
        if (sourceId.startsWith("obj__")) sourceId = sourceId.substring("obj__".length());
        int sep = sourceId.indexOf('_');
        if (sep <= 0 || sep >= sourceId.length() - 1) return objectPath;
        String time = sourceId.substring(0, sep);
        String object = sourceId.substring(sep + 1);
        Path candidate = seedRoot.resolve(time).resolve(object);
        return Files.exists(candidate) ? candidate : objectPath;
    }

    private void restoreSeedRoiSelection(Path objectPath) {
        try {
            Method restore = RoiExplorerPanel.class.getDeclaredMethod("restoreSelection", java.util.List.class);
            restore.setAccessible(true);
            restore.invoke(seedRoiPanel, Collections.singletonList(objectPath));
        } catch (Exception e) {
            setStatus("Seed Edit opened, but selection restore failed: " + e.getMessage());
        }
    }

    private void cmdLoadProject() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        projectFolder = chooser.getSelectedFile().toPath();
        projectField.setText(projectFolder.toString());
        projectField.setToolTipText(projectFolder.toString());
        openSeedRoiRoot(projectFolder.resolve("seed_rois_untracked"));
        Path tracksRoot = projectFolder.resolve("seed_tracks");
        if (Files.isDirectory(tracksRoot)) trackTab.setTracksRoot(tracksRoot);
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
        ImagePlus created = null;
        try {
            created = createMaxZProjectionPreserveT(boundImage);
        } catch (Exception e) {
            setStatus("Could not create Z projection: " + e.getMessage());
            return;
        }
        if (created == null) {
            setStatus("Could not create Z projection.");
            return;
        }
        zprojImage = created;
        String title = boundImage.getShortTitle() + "-MAX";
        zprojImage.setTitle(title);
        zprojImage.show();
        String shownTitle = zprojImage.getTitle();
        refreshZProjCombo();
        if (!comboContains(zprojCombo, shownTitle)) zprojCombo.addItem(shownTitle);
        zprojCombo.setSelectedItem(shownTitle);
        syncSharedParamsToTabs();
        setStatus("Created Z projection: " + shownTitle);
    }

    private static boolean comboContains(JComboBox<String> combo, String value) {
        if (value == null) return false;
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (value.equals(combo.getItemAt(i))) return true;
        }
        return false;
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
            Path dir = seedTab.getEffectiveSaveBaseDir();
            if (dir == null) {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Choose save location for result folder");
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return null;
                dir = chooser.getSelectedFile().toPath();
            }
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
