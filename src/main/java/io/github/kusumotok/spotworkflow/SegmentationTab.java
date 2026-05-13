package io.github.kusumotok.spotworkflow;

import ij.IJ;
import ij.ImageListener;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.ImageCanvas;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.measure.Calibration;
import ij.plugin.Duplicator;
import io.github.kusumotok.spotworkflow.save.SaveMode;
import io.github.kusumotok.spotworkflow.save.SegmentationParams;
import io.github.kusumotok.spotworkflow.core.alg.QuantifierParams;
import io.github.kusumotok.spotworkflow.core.alg.SeededQuantifier3D;
import io.github.kusumotok.spotworkflow.core.model.ThresholdModel;
import io.github.kusumotok.spotworkflow.core.roi.RoiExporter3D;
import io.github.kusumotok.spotworkflow.core.ui.HistogramPanel;
import io.github.kusumotok.spotworkflow.core.util.SeededSpotQuantifier3DImageSupport;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Segmentation parameter UI.
 * Row order mirrors the processing pipeline:
 *   Channel / Z-proj → Histogram → Seed → Min/Max vol → Area → 3D options → Preview → Save
 */
public final class SegmentationTab extends JPanel {
    public enum Mode { SEED, AREA_RESULT }

    private Mode mode = Mode.AREA_RESULT; // constructor で上書きされる

    // ── Image context ──────────────────────────────────────────────────
    private static final String ZPROJ_NONE = "None";
    private final JSpinner          channelSpinner = intSpinner(1, 1, 32, 1);
    private final JComboBox<String> zprojCombo     = new JComboBox<>();
    private final JButton           zprojRefresh   = new JButton("⟳");
    private final JButton           zprojBtn       = new JButton("Max Proj");
    private boolean zprojSyncing = false;

    // ── Histogram ──────────────────────────────────────────────────────
    private final JPanel histHolder  = new JPanel(new BorderLayout());
    private final JLabel noImageHint = new JLabel("Bind an image to see histogram", SwingConstants.CENTER);
    private HistogramPanel histogramPanel;
    private ThresholdModel model;

    // ── Seed threshold ─────────────────────────────────────────────────
    private final JSlider    seedSlider = new JSlider(0, 65535, 200);
    private final JTextField seedField  = numField("200");

    // ── Volume filter ──────────────────────────────────────────────────
    private final JCheckBox  minVolCheck = new JCheckBox(volLabel("µm"), true);
    private final JTextField minVolField = numField("0.10");
    private final JSlider    minVolSlider = new JSlider(0, 1000, 1);
    private final JCheckBox  maxVolCheck = new JCheckBox(maxVolLabel("µm"), false);
    private final JTextField maxVolField = numField("50.0");
    private final JSlider    maxVolSlider = new JSlider(0, 1000, 500);
    private double sizeSliderMinVolume = 0.0;
    private double sizeSliderMaxVolume = 100.0;

    // ── Area threshold ─────────────────────────────────────────────────
    private final JCheckBox  areaEnabledCheck = new JCheckBox("Area enabled", true);
    private final JSlider    areaSlider       = new JSlider(0, 65535, 100);
    private final JTextField areaField        = numField("100");

    // ── 3D options ─────────────────────────────────────────────────────
    // MorphoLibJ componentsLabeling は 6 / 26 のみサポート (18 は IllegalArgumentException)
    private final JComboBox<Integer> connectivityBox  = new JComboBox<>(new Integer[]{6, 26});
    private final JCheckBox          fillHolesCheck   = new JCheckBox("Fill holes");
    private final JRadioButton       conflictMaxBtn   = new JRadioButton("Max overlap", true);
    private final JRadioButton       conflictSplitBtn = new JRadioButton("Split");

    // ── Preview controls ───────────────────────────────────────────────
    private final JRadioButton modeOff      = new JRadioButton("Off", true);
    private final JRadioButton modeRoi      = new JRadioButton("ROI");
    private final JRadioButton modeRoiLight = new JRadioButton("ROI light");
    private Color seedColor   = Color.CYAN;
    private Color resultColor = Color.decode("#FFFF00");   // yellow
    private final JButton btnSeedColor   = colorSwatch(seedColor,   "Seed color");
    private final JButton btnResultColor = colorSwatch(resultColor, "Result color");
    final JButton btnApply        = new JButton("Apply Preview");
    final JButton btnClearPreview = new JButton("Clear");
    final JButton btnCancel       = new JButton("Cancel");
    final JLabel  previewCountLabel = new JLabel("");
    private final JCheckBox  previewNoiseCheck  = new JCheckBox("Hide preview noise <", false);
    private final JTextField previewNoiseField  = numField("0.0");
    private final JSlider    previewNoiseSlider = new JSlider(0, 1000, 0);
    private final JCheckBox  showRejectedSeedCheck = new JCheckBox("Show filtered-out / excluded ROIs", true);
    final JButton btnMakeSeedRoi    = new JButton("Make / Update Seed ROI");
    final JButton btnMakeResultRoi  = new JButton("Make Result ROI");
    private final JButton btnManualInclude = new JButton("Manual Include");
    private final JButton btnManualExclude = new JButton("Manual Exclude");
    private final JButton btnManualClear   = new JButton("Clear Manual Picks");

    // ── Save ───────────────────────────────────────────────────────────
    private final JComboBox<SaveMode> saveModeBox =
        new JComboBox<>(new SaveMode[]{SaveMode.FOLDER, SaveMode.ZIP_FAST, SaveMode.ZIP_COMPRESSED});
    private final JTextField resultPatternField = new JTextField("{name} result", 16);
    // Save-to: null = auto (follow image file directory); non-null = user-set fixed path
    private java.nio.file.Path saveBaseDir = null;
    private final JTextField saveToDirDisplay = new JTextField(16);
    private final JButton    btnSaveTo        = new JButton("Browse…");
    private final JButton    btnSaveToClear   = new JButton("✕");
    private final JLabel     saveToHintLabel  = new JLabel("");

    // ── Internal state ─────────────────────────────────────────────────
    private ImagePlus currentImage;
    private int imgMin = 0, imgMax = 65535;
    private boolean syncing = false;

    // Preview cache
    private String cachedKey;
    private Map<Integer, List<Roi>> cachedFinalRoisByZ;
    private Map<Integer, List<Roi>> cachedSeedRoisByZ;
    private List<Roi> cachedZProjResultRois;
    private List<Roi> cachedZProjSeedRois;
    private String cachedSeedBaseKey;
    private Map<Integer, List<Roi>> cachedSeedRawRoisByLabel;
    private Map<Integer, List<Roi>> cachedSeedAcceptedRoisByLabel;
    private Map<Integer, Long> cachedSeedVoxelCounts;
    private double cachedSeedVoxelVolume = 1.0;
    private final Set<Integer> manualIncludeLabels = new HashSet<Integer>();
    private final Set<Integer> manualExcludeLabels = new HashSet<Integer>();
    private ManualPickMode manualPickMode;
    private MouseAdapter manualPickListener;
    private final List<ImageCanvas> manualPickCanvases = new ArrayList<ImageCanvas>();
    private Integer manualHoverLabel;
    private boolean manualHoverOnZProj;
    private static final Color MANUAL_HOVER_COLOR = Color.MAGENTA;
    private final javax.swing.Timer sizeFilterDebounceTimer;
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicInteger previewGen      = new AtomicInteger();
    private ImageListener zWatcher;
    private boolean originalPreviewActive = true;
    private boolean zprojPreviewActive = true;
    private JPanel channelRow;
    private JPanel seedThresholdSection;
    private JPanel minVolRow;
    private JPanel maxVolRow;
    private JPanel sizeFilterGuardPanel;
    private JPanel areaSection;
    private JPanel threeDSection;
    private JPanel previewSection;
    private JPanel saveSection;
    private JPanel manualSection;
    private JPanel makeRoiSection;

    private enum ManualPickMode { INCLUDE, EXCLUDE }

    private static final class PreviewResult {
        final Map<Integer, List<Roi>> finalSliceRois;
        final Map<Integer, List<Roi>> seedSliceRois;
        final Map<Integer, List<Roi>> rawSeedRois;
        final Map<Integer, Long> seedVoxelCounts;
        final double voxelVolume;

        PreviewResult(Map<Integer, List<Roi>> finalSliceRois,
                      Map<Integer, List<Roi>> seedSliceRois) {
            this(finalSliceRois, seedSliceRois, null, null, 1.0);
        }

        PreviewResult(Map<Integer, List<Roi>> finalSliceRois,
                      Map<Integer, List<Roi>> seedSliceRois,
                      Map<Integer, List<Roi>> rawSeedRois,
                      Map<Integer, Long> seedVoxelCounts,
                      double voxelVolume) {
            this.finalSliceRois = finalSliceRois;
            this.seedSliceRois = seedSliceRois;
            this.rawSeedRois = rawSeedRois;
            this.seedVoxelCounts = seedVoxelCounts;
            this.voxelVolume = voxelVolume;
        }
    }

    // ── Histogram listener ─────────────────────────────────────────────
    private final HistogramPanel.ThresholdListener histListener = (tBg, tFg) -> {
        if (model == null) return;
        int oldBg = model.getTBg(), oldFg = model.getTFg();
        syncing = true;
        // AREA_RESULT: area threshold (tBg) のみ更新。seedSlider への書き込みを防ぐ
        // SEED:        seed threshold (tFg) のみ更新。areaSlider への書き込みを防ぐ
        if (mode == Mode.AREA_RESULT) {
            model.setTBg(tBg);
            areaSlider.setValue(tBg); areaField.setText(String.valueOf(tBg));
        } else {
            model.setTFg(tFg);
            seedSlider.setValue(tFg); seedField.setText(String.valueOf(tFg));
        }
        syncing = false;
        if (histogramPanel != null) histogramPanel.repaintThresholdMarkers(oldBg, oldFg);
        onThresholdParamsChanged();
    };

    public SegmentationTab() {
        this(Mode.AREA_RESULT);
    }

    public SegmentationTab(Mode mode) {
        this.mode = mode;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        channelRow = buildChannelRow();
        seedThresholdSection = buildThreshRow("Seed threshold:", seedSlider, seedField);
        minVolRow = buildVolRow(minVolCheck, minVolField);
        maxVolRow = buildVolRow(maxVolCheck, maxVolField);
        sizeFilterGuardPanel = buildManualResetGuardPanel();
        areaSection = buildAreaSection();
        threeDSection = buildThreeDSection();
        previewSection = buildPreviewSection();
        manualSection = buildManualSection();
        makeRoiSection = buildMakeRoiSection();
        saveSection = buildSaveSection();

        add(channelRow);
        add(vgap(4));
        add(buildHistogramSection());
        add(vgap(4));
        add(seedThresholdSection);
        add(vgap(2));
        add(minVolRow);
        add(vgap(1));
        add(maxVolRow);
        add(sizeFilterGuardPanel);
        add(vgap(4));
        add(areaSection);
        add(vgap(6));
        add(threeDSection);
        add(vgap(6));
        add(previewSection);
        add(vgap(6));
        add(manualSection);
        add(vgap(6));
        add(saveSection);
        add(vgap(6));
        add(makeRoiSection);
        add(Box.createVerticalGlue());

        sizeFilterDebounceTimer = new javax.swing.Timer(140, e -> applySizeFilterPreviewNow());
        sizeFilterDebounceTimer.setRepeats(false);
        wireListeners();
        applyModeVisibility();
        updateEnabled();
        updateSaveToDisplay();
    }

    // ── Panel builders ─────────────────────────────────────────────────

    private JPanel buildChannelRow() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.add(new JLabel("Ch:"));
        p.add(channelSpinner);
        p.add(Box.createHorizontalStrut(12));
        p.add(new JLabel("Z-proj:"));
        zprojCombo.setPrototypeDisplayValue("A reasonably long title      ");
        p.add(zprojCombo);
        zprojRefresh.setToolTipText("Refresh projection list");
        zprojRefresh.setMargin(new Insets(2, 4, 2, 4));
        p.add(zprojRefresh);
        p.add(zprojBtn);
        return p;
    }

    private JPanel buildHistogramSection() {
        histHolder.setAlignmentX(LEFT_ALIGNMENT);
        Dimension preferred = new Dimension(420, 140);
        histHolder.setPreferredSize(preferred);
        histHolder.setMinimumSize(new Dimension(200, preferred.height));
        histHolder.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
        histHolder.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        noImageHint.setForeground(Color.GRAY);
        histHolder.add(noImageHint, BorderLayout.CENTER);
        return histHolder;
    }

    private JPanel buildThreshRow(String labelText, JSlider slider, JTextField field) {
        JPanel outer = new JPanel();
        outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));
        outer.setAlignmentX(LEFT_ALIGNMENT);
        JPanel labelRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        labelRow.setAlignmentX(LEFT_ALIGNMENT);
        labelRow.add(new JLabel(labelText));
        labelRow.add(field);
        JPanel sliderRow = new JPanel(new BorderLayout(0, 0));
        sliderRow.setAlignmentX(LEFT_ALIGNMENT);
        sliderRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, slider.getPreferredSize().height));
        sliderRow.add(slider, BorderLayout.CENTER);
        outer.add(labelRow);
        outer.add(sliderRow);
        outer.setMaximumSize(new Dimension(Integer.MAX_VALUE, outer.getPreferredSize().height));
        return outer;
    }

    private JPanel buildVolRow(JCheckBox check, JTextField field) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.add(check, BorderLayout.WEST);
        p.add(field, BorderLayout.EAST);
        if (check == minVolCheck) p.add(minVolSlider, BorderLayout.CENTER);
        else if (check == maxVolCheck) p.add(maxVolSlider, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private JPanel buildManualResetGuardPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.setAlignmentX(LEFT_ALIGNMENT);
        JLabel label = new JLabel("Manual edits are active. Clear manual edits to change threshold or size filter.");
        label.setFont(label.getFont().deriveFont(Font.ITALIC, 11f));
        label.setForeground(Color.GRAY);
        p.add(label);
        p.setVisible(false);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private JPanel buildAreaSection() {
        JPanel outer = new JPanel();
        outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));
        outer.setAlignmentX(LEFT_ALIGNMENT);
        JPanel labelRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        labelRow.setAlignmentX(LEFT_ALIGNMENT);
        labelRow.add(areaEnabledCheck);
        labelRow.add(new JLabel("Area threshold:"));
        labelRow.add(areaField);
        JPanel sliderRow = new JPanel(new BorderLayout(4, 0));
        sliderRow.setAlignmentX(LEFT_ALIGNMENT);
        sliderRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, areaSlider.getPreferredSize().height));
        sliderRow.add(areaSlider, BorderLayout.CENTER);
        outer.add(labelRow);
        outer.add(sliderRow);
        outer.setMaximumSize(new Dimension(Integer.MAX_VALUE, outer.getPreferredSize().height));
        return outer;
    }

    private JPanel buildThreeDSection() {
        JPanel p = titledPanel("3D options");
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JPanel connRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        connRow.setAlignmentX(LEFT_ALIGNMENT);
        connRow.add(new JLabel("Connectivity:")); connRow.add(connectivityBox); connRow.add(fillHolesCheck);
        p.add(connRow);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private JPanel buildPreviewSection() {
        JPanel p = titledPanel("Preview");
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));

        // ROI preview is fixed; only colors remain configurable.
        ButtonGroup mg = new ButtonGroup(); mg.add(modeOff); mg.add(modeRoi); mg.add(modeRoiLight);
        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        modeRow.setAlignmentX(LEFT_ALIGNMENT);
        if (mode == Mode.SEED) {
            modeRow.add(new JLabel("Outside size filter:")); modeRow.add(btnSeedColor);
            modeRow.add(new JLabel("Output ROI:")); modeRow.add(btnResultColor);
        } else {
            modeRow.add(new JLabel("Seed:")); modeRow.add(btnSeedColor);
            modeRow.add(new JLabel("Area / result:")); modeRow.add(btnResultColor);
        }

        // Apply / Cancel / Clear
        btnCancel.setEnabled(false);
        btnCancel.setFocusable(false);
        btnClearPreview.setFocusable(false);
        previewCountLabel.setFont(previewCountLabel.getFont().deriveFont(Font.ITALIC, 11f));
        previewCountLabel.setForeground(Color.DARK_GRAY);
        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actionRow.setAlignmentX(LEFT_ALIGNMENT);
        actionRow.add(btnApply); actionRow.add(btnCancel); actionRow.add(btnClearPreview);
        Dimension statusSize = new Dimension(360, Math.max(24, previewCountLabel.getPreferredSize().height + 10));
        previewCountLabel.setPreferredSize(statusSize);
        previewCountLabel.setMinimumSize(statusSize);
        previewCountLabel.setMaximumSize(statusSize);
        previewCountLabel.setAlignmentX(LEFT_ALIGNMENT);

        p.add(modeRow);
        if (mode == Mode.SEED) {
            JPanel rejectedRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            rejectedRow.setAlignmentX(LEFT_ALIGNMENT);
            rejectedRow.add(showRejectedSeedCheck);
            p.add(rejectedRow);
            JPanel noiseRow = new JPanel(new BorderLayout(6, 0));
            noiseRow.setAlignmentX(LEFT_ALIGNMENT);
            noiseRow.add(previewNoiseCheck, BorderLayout.WEST);
            noiseRow.add(previewNoiseSlider, BorderLayout.CENTER);
            noiseRow.add(previewNoiseField, BorderLayout.EAST);
            noiseRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, noiseRow.getPreferredSize().height));
            p.add(noiseRow);
        }
        p.add(actionRow);
        p.add(previewCountLabel);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private JPanel buildMakeRoiSection() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        p.setAlignmentX(LEFT_ALIGNMENT);
        if (mode == Mode.AREA_RESULT) p.add(btnMakeResultRoi);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private JPanel buildManualSection() {
        JPanel p = titledPanel("Manual seed accept/reject");
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel note = new JLabel("Manual picks are reset when threshold or size filter changes.");
        note.setFont(note.getFont().deriveFont(Font.ITALIC, 11f));
        note.setForeground(Color.GRAY);
        note.setAlignmentX(LEFT_ALIGNMENT);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.add(btnManualInclude);
        row.add(btnManualExclude);
        row.add(btnManualClear);
        p.add(note);
        p.add(row);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private JPanel buildSaveSection() {
        JPanel p = titledPanel("Save");
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));

        JPanel folderRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        folderRow.setAlignmentX(LEFT_ALIGNMENT);
        folderRow.add(new JLabel("Project folder name:"));
        folderRow.add(resultPatternField);
        JButton btnResetPattern = new JButton("↺");
        btnResetPattern.setMargin(new Insets(2, 4, 2, 4));
        btnResetPattern.setFocusable(false);
        btnResetPattern.setToolTipText("Reset to default: {name} result");
        btnResetPattern.addActionListener(e -> resultPatternField.setText("{name} result"));
        folderRow.add(btnResetPattern);

        // Save-to row
        saveToDirDisplay.setEditable(false);
        saveToDirDisplay.setFont(saveToDirDisplay.getFont().deriveFont(Font.PLAIN, 11f));
        btnSaveToClear.setMargin(new Insets(2, 4, 2, 4));
        btnSaveToClear.setToolTipText("Reset to auto (follow image)");
        JPanel saveToRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        saveToRow.setAlignmentX(LEFT_ALIGNMENT);
        saveToRow.add(new JLabel("Save to:"));
        saveToRow.add(saveToDirDisplay);
        saveToRow.add(btnSaveTo);
        saveToRow.add(btnSaveToClear);

        // Hint showing effective path
        saveToHintLabel.setFont(saveToHintLabel.getFont().deriveFont(Font.ITALIC, 10f));
        saveToHintLabel.setForeground(Color.GRAY);
        saveToHintLabel.setAlignmentX(LEFT_ALIGNMENT);

        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        modeRow.setAlignmentX(LEFT_ALIGNMENT);
        modeRow.add(new JLabel("ROI save mode:")); modeRow.add(saveModeBox);

        p.add(folderRow);
        p.add(saveToRow);
        p.add(saveToHintLabel);
        p.add(modeRow);
        if (mode == Mode.SEED) {
            JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            actionRow.setAlignmentX(LEFT_ALIGNMENT);
            actionRow.add(btnMakeSeedRoi);
            p.add(actionRow);
        }

        // Wire buttons
        btnSaveTo.addActionListener(e -> {
            javax.swing.JFileChooser fc = new javax.swing.JFileChooser(saveBaseDir != null ? saveBaseDir.toFile() : null);
            fc.setDialogTitle("Choose save location for result folders");
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                saveBaseDir = fc.getSelectedFile().toPath();
                updateSaveToDisplay();
            }
        });
        btnSaveToClear.addActionListener(e -> {
            saveBaseDir = null;
            updateSaveToDisplay();
        });

        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    // ── Listeners ──────────────────────────────────────────────────────

    private void wireListeners() {
        channelSpinner.addChangeListener(e -> { if (currentImage != null) updateHistogramForChannel(); });

        zprojBtn.addActionListener(e -> cmdCreateMaxProj());
        zprojRefresh.addActionListener(e -> refreshZProjCombo());
        zprojCombo.addActionListener(e -> { if (!zprojSyncing) cmdSelectZProj(); });
        zprojCombo.addItem(ZPROJ_NONE);

        // Seed slider ↔ field ↔ model
        seedSlider.addChangeListener(e -> {
            if (syncing || model == null) return;
            int val = seedSlider.getValue();
            int oldBg = model.getTBg(), oldFg = model.getTFg();
            syncing = true; seedField.setText(String.valueOf(val)); model.setTFg(val); syncing = false;
            if (histogramPanel != null) histogramPanel.repaintThresholdMarkers(oldBg, oldFg);
            onThresholdParamsChanged();
        });
        seedField.addActionListener(e -> commitSeedField());
        seedField.addFocusListener(new FocusAdapter() { @Override public void focusLost(FocusEvent e) { commitSeedField(); } });

        // Area slider ↔ field ↔ model
        areaSlider.addChangeListener(e -> {
            if (syncing || model == null) return;
            int val = areaSlider.getValue();
            int oldBg = model.getTBg(), oldFg = model.getTFg();
            syncing = true; areaField.setText(String.valueOf(val)); model.setTBg(val); syncing = false;
            if (histogramPanel != null) histogramPanel.repaintThresholdMarkers(oldBg, oldFg);
            onThresholdParamsChanged();
        });
        areaField.addActionListener(e -> commitAreaField());
        areaField.addFocusListener(new FocusAdapter() { @Override public void focusLost(FocusEvent e) { commitAreaField(); } });

        // Volume / area enabled
        minVolField.setEnabled(minVolCheck.isSelected());
        maxVolField.setEnabled(maxVolCheck.isSelected());
        minVolSlider.setEnabled(minVolCheck.isSelected());
        maxVolSlider.setEnabled(maxVolCheck.isSelected());
        areaSlider.setEnabled(areaEnabledCheck.isSelected());
        areaField.setEnabled(areaEnabledCheck.isSelected());
        minVolCheck.addActionListener(e -> {
            minVolField.setEnabled(minVolCheck.isSelected());
            minVolSlider.setEnabled(minVolCheck.isSelected());
            onSizeFilterChanged();
        });
        maxVolCheck.addActionListener(e -> {
            maxVolField.setEnabled(maxVolCheck.isSelected());
            maxVolSlider.setEnabled(maxVolCheck.isSelected());
            onSizeFilterChanged();
        });
        areaEnabledCheck.addActionListener(e -> {
            boolean en = areaEnabledCheck.isSelected();
            areaSlider.setEnabled(en); areaField.setEnabled(en);
            onParamsChanged();
        });
        minVolField.addActionListener(e -> commitSizeFilterFields());
        maxVolField.addActionListener(e -> commitSizeFilterFields());
        minVolField.addFocusListener(new FocusAdapter() { @Override public void focusLost(FocusEvent e) { commitSizeFilterFields(); } });
        maxVolField.addFocusListener(new FocusAdapter() { @Override public void focusLost(FocusEvent e) { commitSizeFilterFields(); } });
        minVolSlider.addChangeListener(e -> {
            if (syncing) return;
            syncing = true;
            minVolField.setText(formatVolume(sliderToVolume(minVolSlider)));
            syncing = false;
            onSizeFilterChanged();
        });
        maxVolSlider.addChangeListener(e -> {
            if (syncing) return;
            syncing = true;
            maxVolField.setText(formatVolume(sliderToVolume(maxVolSlider)));
            syncing = false;
            onSizeFilterChanged();
        });
        previewNoiseCheck.addActionListener(e -> {
            previewNoiseField.setEnabled(previewNoiseCheck.isSelected());
            previewNoiseSlider.setEnabled(previewNoiseCheck.isSelected());
            onPreviewDisplayChanged();
        });
        previewNoiseField.addActionListener(e -> commitPreviewNoiseField());
        previewNoiseField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { commitPreviewNoiseField(); }
        });
        previewNoiseSlider.addChangeListener(e -> {
            if (syncing) return;
            syncing = true;
            previewNoiseField.setText(formatVolume(sliderToVolume(previewNoiseSlider)));
            syncing = false;
            onPreviewDisplayChanged();
        });
        showRejectedSeedCheck.addActionListener(e -> onPreviewDisplayChanged());
        connectivityBox.addActionListener(e -> onParamsChanged());
        fillHolesCheck.addActionListener(e -> onParamsChanged());
        conflictMaxBtn.addActionListener(e -> onParamsChanged());
        conflictSplitBtn.addActionListener(e -> onParamsChanged());
        btnManualInclude.addActionListener(e -> toggleManualPickMode(ManualPickMode.INCLUDE));
        btnManualExclude.addActionListener(e -> toggleManualPickMode(ManualPickMode.EXCLUDE));
        btnManualClear.addActionListener(e -> clearManualPicks());

        // Preview mode change:
        //   - switching Off clears overlay and label
        //   - switching to active mode with cache: instant re-render (no Apply needed)
        //   - switching to active mode without cache: show "Press Apply to update" consistently
        modeOff.addActionListener(e -> {
            clearPreviewOverlay();
            previewCountLabel.setText("");
            updateEnabled();
        });
        modeRoi.addActionListener(e -> {
            updateEnabled();
            if (cachedFinalRoisByZ != null) {
                renderPreview(currentZPlane());
                renderSelectedZProjOverlay();
            }
            else if (currentImage != null) setPreviewStatus("Press Apply to update");
        });
        modeRoiLight.addActionListener(e -> {
            updateEnabled();
            if (cachedFinalRoisByZ != null) {
                renderPreview(currentZPlane());
                renderSelectedZProjOverlay();
            }
            else if (currentImage != null) setPreviewStatus("Press Apply to update");
        });

        // Color pickers
        btnSeedColor.addActionListener(e -> {
            Color c = JColorChooser.showDialog(this, "Seed color", seedColor);
            if (c != null) {
                seedColor = c;
                btnSeedColor.setBackground(c);
                if (cachedFinalRoisByZ != null) {
                    renderPreview(currentZPlane());
                    renderSelectedZProjOverlay();
                }
            }
        });
        btnResultColor.addActionListener(e -> {
            Color c = JColorChooser.showDialog(this, "Result color", resultColor);
            if (c != null) {
                resultColor = c;
                btnResultColor.setBackground(c);
                if (cachedFinalRoisByZ != null) {
                    renderPreview(currentZPlane());
                    renderSelectedZProjOverlay();
                }
            }
        });

        // Preview buttons
        // SEED: SegmentationTab 内部で完結するため直接登録
        // AREA_RESULT: seed ROI ファイルを使うため WorkflowWindow.cmdPreview() が担当
        //              ここで applyPreview() を登録すると内部 seed 計算の二重発火が起きる
        if (mode == Mode.SEED) {
            btnApply.addActionListener(e -> applyPreview());
        }
        btnCancel.addActionListener(e -> cancelPreview());
        btnClearPreview.addActionListener(e -> clearPreview());
    }

    private void applyModeVisibility() {
        channelRow.setVisible(false);
        saveSection.setVisible(mode == Mode.SEED);
        conflictMaxBtn.setSelected(true);
        conflictSplitBtn.setSelected(false);
        if (mode == Mode.SEED) {
            areaSection.setVisible(false);
            previewNoiseField.setEnabled(previewNoiseCheck.isSelected());
            previewNoiseSlider.setEnabled(previewNoiseCheck.isSelected());
            manualSection.setVisible(true);
            updateManualButtonState();
            btnResultColor.setToolTipText("Seed ROI output color");
            btnSeedColor.setToolTipText("Filtered-out seed color");
            modeOff.setVisible(false);
            modeRoiLight.setVisible(false);
            modeRoi.setSelected(true);
            areaEnabledCheck.setSelected(false);
        } else {
            seedThresholdSection.setVisible(false);
            minVolRow.setVisible(false);
            maxVolRow.setVisible(false);
            sizeFilterGuardPanel.setVisible(false);
            manualSection.setVisible(false);
            modeOff.setVisible(false);
            modeRoiLight.setVisible(false);
            modeRoi.setSelected(true);
            areaEnabledCheck.setVisible(true);
        }
    }

    // ── Param change ───────────────────────────────────────────────────

    private void onParamsChanged() {
        onThresholdParamsChanged();
    }

    private void onThresholdParamsChanged() {
        if (sizeFilterDebounceTimer.isRunning()) sizeFilterDebounceTimer.stop();
        uninstallManualPickMode();
        manualIncludeLabels.clear();
        manualExcludeLabels.clear();
        updateManualButtonState();
        updateManualEditGuardState();
        clearPreviewCache();
        if (!modeOff.isSelected()) {
            setPreviewStatus("Press Apply to update");
        }
    }

    private void onSizeFilterChanged() {
        if (mode == Mode.SEED && cachedSeedRawRoisByLabel != null && cachedSeedVoxelCounts != null) {
            manualIncludeLabels.clear();
            manualExcludeLabels.clear();
            updateManualButtonState();
            updateManualEditGuardState();
            setPreviewStatus("Updating size filter...");
            sizeFilterDebounceTimer.restart();
            return;
        }
        onThresholdParamsChanged();
    }

    private void applySizeFilterPreviewNow() {
        if (rebuildSeedPreviewFromSizeFilter()) return;
        onThresholdParamsChanged();
    }

    private void commitSizeFilterFields() {
        if (syncing) return;
        syncSizeSlidersFromFields();
        onSizeFilterChanged();
    }

    private void commitPreviewNoiseField() {
        if (syncing) return;
        boolean wasSyncing = syncing;
        syncing = true;
        previewNoiseSlider.setValue(volumeToSlider(parseDoubleOrDefault(previewNoiseField.getText(), 0.0)));
        syncing = wasSyncing;
        onPreviewDisplayChanged();
    }

    private void onPreviewDisplayChanged() {
        if (mode == Mode.SEED && cachedSeedRawRoisByLabel != null && cachedSeedVoxelCounts != null) {
            rebuildSeedPreviewFromSizeFilter();
        }
    }

    // ── Preview: apply ─────────────────────────────────────────────────

    public void applyPreview() {
        applyPreview(null);
    }

    public void applyPreviewFromSeedLabels(ImagePlus seedLabelImage) {
        applyPreview(seedLabelImage);
    }

    private void applyPreview(ImagePlus seedLabelImage) {
        if (currentImage == null || modeOff.isSelected()) return;

        SegmentationParams params = getParams();
        String key = makeKey(params) + (seedLabelImage != null ? ":editedSeeds:" + System.nanoTime() : "");
        String seedBaseKey = makeSeedBaseKey(params);

        // Cache hit: just re-render current Z
        if (cachedFinalRoisByZ != null && key.equals(cachedKey)) {
            renderPreview(currentZPlane());
            renderSelectedZProjOverlay();
            return;
        }

        // Cache miss: run segmentation
        cancelRequested.set(false);
        int gen = previewGen.incrementAndGet();
        setPreviewBusy(true);
        setPreviewStatus("Computing...");

        Calibration cal = currentImage.getCalibration();
        double vw = cal.pixelWidth  > 0 ? cal.pixelWidth  : 1.0;
        double vh = cal.pixelHeight > 0 ? cal.pixelHeight : 1.0;
        double vd = cal.pixelDepth  > 0 ? cal.pixelDepth  : 1.0;
        final ImagePlus channelImg  = extractChannel(currentImage, params.channel);
        final boolean   ownsChannel = channelImg != currentImage;

        new SwingWorker<PreviewResult, String>() {
            @Override
            protected PreviewResult doInBackground() {
                try {
                    publish(mode == Mode.SEED ? "Computing 3D seed components..." : "Computing 3D segmentation...");
                    SeededQuantifier3D.SeededResult seeded = seedLabelImage != null
                        ? SeededQuantifier3D.computeFromSeedLabels(
                            channelImg, seedLabelImage, params.areaThreshold,
                            params.toQuantifierParams(), params.areaEnabled, this::publish, cancelRequested::get)
                        : SeededQuantifier3D.compute(
                            channelImg, params.areaThreshold, params.seedThreshold,
                            params.toQuantifierParams(), vw * vh * vd, params.areaEnabled,
                            this::publish, cancelRequested::get);
                    if (seeded == null || seeded.finalSeg == null || seeded.finalSeg.labelImage == null) {
                        return null;
                    }
                    publish("Converting 3D labels to ROI outlines...");
                    RoiExporter3D exporter = new RoiExporter3D();
                    Map<Integer, List<Roi>> finalRois = exporter.exportToRoiListsByLabel(
                        seeded.finalSeg.labelImage, null, currentImage, params.channel);
                    Map<Integer, List<Roi>> seedRois = null;
                    if (mode == Mode.SEED && seeded.rawSeedSeg != null && seeded.rawSeedSeg.labelImage != null) {
                        Map<Integer, List<Roi>> rawRois = exporter.exportToRoiListsByLabel(
                            seeded.rawSeedSeg.labelImage, null, currentImage, params.channel);
                        seedRois = rejectedSeedRois(rawRois, finalRois);
                        return new PreviewResult(finalRois, seedRois, rawRois,
                            seeded.seedVoxelCounts, vw * vh * vd);
                    } else if (params.areaEnabled && seeded.seedSeg != null && seeded.seedSeg.labelImage != null) {
                        seedRois = exporter.exportToRoiListsByLabel(
                            seeded.seedSeg.labelImage, null, currentImage, params.channel);
                    }
                    return new PreviewResult(finalRois, seedRois);
                } finally {
                    if (ownsChannel) channelImg.flush();
                }
            }
            @Override
            protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) setPreviewStatus(chunks.get(chunks.size() - 1));
            }
            @Override
            protected void done() {
                if (previewGen.get() != gen) { setPreviewBusy(false); return; }
                setPreviewBusy(false);
                try {
                    PreviewResult result = get();
                    boolean noAccepted = result == null || result.finalSliceRois == null || result.finalSliceRois.isEmpty();
                    boolean noRejected = result == null || result.seedSliceRois == null || result.seedSliceRois.isEmpty();
                    if (result == null || (mode == Mode.SEED ? (noAccepted && noRejected) : noAccepted)) {
                        setPreviewStatus("No spots found.");
                        return;
                    }
                    cachedFinalRoisByZ = organizeByZ(result.finalSliceRois);
                    cachedSeedRoisByZ = result.seedSliceRois != null ? organizeByZ(result.seedSliceRois) : null;
                    cachedZProjResultRois = computeZProjRois(result.finalSliceRois);
                    cachedZProjSeedRois = result.seedSliceRois != null ? computeZProjRois(result.seedSliceRois) : null;
                    cachedKey = key;
                    if (mode == Mode.SEED) {
                        cachedSeedBaseKey = seedBaseKey;
                        cachedSeedRawRoisByLabel = result.rawSeedRois;
                        cachedSeedVoxelCounts = result.seedVoxelCounts;
                        cachedSeedVoxelVolume = result.voxelVolume;
                        manualIncludeLabels.clear();
                        manualExcludeLabels.clear();
                        updateManualButtonState();
                        updateManualEditGuardState();
                        updateSizeSliderRangeFromCache();
                        if (rebuildSeedPreviewFromSizeFilter()) return;
                    }
                    renderPreview(currentZPlane());
                    renderSelectedZProjOverlay();
                    int n = result.finalSliceRois.size();
                    if (mode == Mode.SEED && result.seedSliceRois != null && !result.seedSliceRois.isEmpty()) {
                        int rejected = result.seedSliceRois.size();
                        setPreviewStatus(n + " kept, " + rejected + " filtered out");
                    } else {
                        setPreviewStatus(n + " spot" + (n != 1 ? "s" : ""));
                    }
                } catch (CancellationException e) {
                    setPreviewStatus("Cancelled.");
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    setPreviewStatus("Error: " + cause.getMessage());
                }
            }
        }.execute();
    }

    // ── Preview: render current Z ──────────────────────────────────────

    private void renderPreview(int zPlane) {
        if (!originalPreviewActive || cachedFinalRoisByZ == null || currentImage == null || modeOff.isSelected()) return;

        Overlay overlay = new Overlay();
        if (mode == Mode.SEED) {
            if (showRejectedSeedCheck.isSelected()) {
                addRoisToOverlay(overlay, cachedSeedRoisByZ != null ? cachedSeedRoisByZ.get(zPlane) : null, seedColor);
            }
            addRoisToOverlay(overlay, cachedFinalRoisByZ.get(zPlane), resultColor);
            if (manualHoverLabel != null && !manualHoverOnZProj) {
                addRoisToOverlay(overlay, filterRoisForZ(cachedSeedRawRoisByLabel.get(manualHoverLabel), zPlane), MANUAL_HOVER_COLOR);
            }
        } else {
            if (cachedSeedRoisByZ != null) {
                addRoisToOverlay(overlay, cachedSeedRoisByZ.get(zPlane), seedColor);
            }
            addRoisToOverlay(overlay, cachedFinalRoisByZ.get(zPlane), resultColor);
        }

        currentImage.setOverlay(overlay);
        currentImage.updateAndDraw();
    }

    private void renderZProjOverlay(ImagePlus zp) {
        if (!zprojPreviewActive || zp == null || cachedZProjResultRois == null) return;
        Overlay overlay = new Overlay();

        if (modeRoiLight.isSelected()) {
            ShapeRoi merged = mergeAll(cachedZProjResultRois);
            if (merged != null) {
                merged.setStrokeColor(resultColor);
                overlay.add(merged);
            }
            if (cachedZProjSeedRois != null) {
                ShapeRoi mergedSeed = mergeAll(cachedZProjSeedRois);
                if (mergedSeed != null) {
                    mergedSeed.setStrokeColor(seedColor);
                    overlay.add(mergedSeed);
                }
            }
        } else {
            if (mode == Mode.SEED) {
                if (showRejectedSeedCheck.isSelected()) addRoisToOverlay(overlay, cachedZProjSeedRois, seedColor);
                addRoisToOverlay(overlay, cachedZProjResultRois, resultColor);
                if (manualHoverLabel != null && manualHoverOnZProj) {
                    addRoisToOverlay(overlay, computeZProjRoisForLabel(manualHoverLabel), MANUAL_HOVER_COLOR);
                }
            } else {
                if (cachedZProjSeedRois != null) {
                    addRoisToOverlay(overlay, cachedZProjSeedRois, seedColor);
                }
                addRoisToOverlay(overlay, cachedZProjResultRois, resultColor);
            }
        }

        zp.setOverlay(overlay);
        zp.updateAndDraw();
    }

    private void renderSelectedZProjOverlay() {
        ImagePlus zp = getZProjImp();
        if (zp != null) renderZProjOverlay(zp);
    }

    // ── Preview: cancel / clear ────────────────────────────────────────

    public void cancelPreview() {
        cancelRequested.set(true);
        previewGen.incrementAndGet();
    }

    /** Segmentation タブの表示状態を通知する。非表示中は Z-watcher によるオーバーレイ再描画を抑制する。 */
    public void setTabActive(boolean active) {
        setPreviewActive(active, active);
    }

    public void setPreviewActive(boolean originalActive, boolean zprojActive) {
        originalPreviewActive = originalActive;
        zprojPreviewActive = zprojActive;
        if (originalActive) renderPreview(currentZPlane());
        if (zprojActive) renderSelectedZProjOverlay();
    }

    /** Clears overlay on the image and Z-proj, but keeps the cache. */
    public void clearOverlayOnly() {
        clearPreviewOverlay();
    }

    public void clearOriginalOverlayOnly() {
        if (currentImage != null) {
            currentImage.setOverlay(null);
            // updateAndDraw は呼ばない: ROI Explorer が overlay を管理するため
            // 不要な imageUpdated → refreshOverlay 連鎖を避ける
        }
    }

    public void clearZProjOverlayOnly() {
        ImagePlus zp = getZProjImp();
        if (zp != null) { zp.setOverlay(null); zp.updateAndDraw(); }
    }

    /** Clears overlay, cancels computation, and frees the cache. */
    public void clearPreview() {
        cancelPreview();
        uninstallManualPickMode();
        clearPreviewOverlay();
        clearPreviewCache();
        manualIncludeLabels.clear();
        manualExcludeLabels.clear();
        updateManualButtonState();
        updateManualEditGuardState();
        previewCountLabel.setText("");
    }

    private void clearPreviewOverlay() {
        if (currentImage != null) {
            currentImage.setOverlay(null);
            currentImage.updateAndDraw();
        }
        ImagePlus zp = getZProjImp();
        if (zp != null) { zp.setOverlay(null); zp.updateAndDraw(); }
    }

    // ── Z-watcher ──────────────────────────────────────────────────────

    private void installZWatcher() {
        if (zWatcher != null) ImagePlus.removeImageListener(zWatcher);
        if (currentImage == null) { zWatcher = null; return; }
        final int[] lastZ = {-1};
        zWatcher = new ImageListener() {
            @Override public void imageOpened(ImagePlus imp) {}
            @Override public void imageClosed(ImagePlus imp) {}
            @Override public void imageUpdated(ImagePlus imp) {
                if (imp != currentImage) return;
                int z = currentZPlane();
                if (z != lastZ[0]) { lastZ[0] = z; updatePreviewForZChange(); }
            }
        };
        ImagePlus.addImageListener(zWatcher);
    }

    private void updatePreviewForZChange() {
        if (!originalPreviewActive || modeOff.isSelected() || cachedFinalRoisByZ == null) return;
        SwingUtilities.invokeLater(() -> renderPreview(currentZPlane()));
    }

    // ── Z-proj commands ────────────────────────────────────────────────

    private void cmdCreateMaxProj() {
        if (currentImage == null) return;
        Set<Integer> beforeIds = currentImageIdSet();
        IJ.run(currentImage, "Z Project...", "projection=[Max Intensity]");
        ImagePlus result = findNewImage(beforeIds);
        refreshZProjCombo();
        if (result != null && result != currentImage) {
            zprojSyncing = true;
            zprojCombo.setSelectedItem(result.getTitle());
            zprojSyncing = false;
            renderSelectedZProjOverlay();
        }
    }

    private void cmdSelectZProj() {
        String selected = (String) zprojCombo.getSelectedItem();
        if (selected == null || ZPROJ_NONE.equals(selected)) return;
        ImagePlus zp = WindowManager.getImage(selected);
        if (zp != null && zp.getWindow() != null) zp.getWindow().toFront();
        if (zp != null) renderZProjOverlay(zp);
    }

    void refreshZProjCombo() {
        if (currentImage == null) return;
        zprojSyncing = true;
        String current = (String) zprojCombo.getSelectedItem();
        zprojCombo.removeAllItems();
        zprojCombo.addItem(ZPROJ_NONE);
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus zp = WindowManager.getImage(id);
                if (zp == null || zp == currentImage || zp.getNSlices() != 1) continue;
                if (zp.getWidth() != currentImage.getWidth() || zp.getHeight() != currentImage.getHeight()) continue;
                zprojCombo.addItem(zp.getTitle());
            }
        }
        if (current != null) zprojCombo.setSelectedItem(current);
        if (zprojCombo.getSelectedIndex() < 0) zprojCombo.setSelectedIndex(0);
        zprojSyncing = false;
    }

    // ── Threshold field commits ────────────────────────────────────────

    private void commitSeedField() {
        if (syncing || model == null) return;
        try {
            int val = clamp(Integer.parseInt(seedField.getText().trim()), imgMin, imgMax);
            int oldBg = model.getTBg(), oldFg = model.getTFg();
            syncing = true; seedSlider.setValue(val); seedField.setText(String.valueOf(val)); model.setTFg(val); syncing = false;
            if (histogramPanel != null) histogramPanel.repaintThresholdMarkers(oldBg, oldFg);
            onParamsChanged();
        } catch (NumberFormatException ignored) {}
    }

    private void commitAreaField() {
        if (syncing || model == null) return;
        try {
            int val = clamp(Integer.parseInt(areaField.getText().trim()), imgMin, imgMax);
            int oldBg = model.getTBg(), oldFg = model.getTFg();
            syncing = true; areaSlider.setValue(val); areaField.setText(String.valueOf(val)); model.setTBg(val); syncing = false;
            if (histogramPanel != null) histogramPanel.repaintThresholdMarkers(oldBg, oldFg);
            onParamsChanged();
        } catch (NumberFormatException ignored) {}
    }

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Called when the enclosing window is closed.
     * Removes the ImageListener, cancels any running computation, frees the cache,
     * and clears overlays — preventing memory leaks via ImageJ's static listener list.
     */
    public void onWindowClosing() {
        cancelRequested.set(true);
        previewGen.incrementAndGet();
        uninstallManualPickMode();
        clearPreviewOverlay();
        clearPreviewCache();
        if (zWatcher != null) {
            ImagePlus.removeImageListener(zWatcher);
            zWatcher = null;
        }
        currentImage = null;
    }

    /**
     * Returns the effective base directory for saving result folders.
     * Returns saveBaseDir if explicitly set; otherwise the image's file directory; null if unknown.
     */
    public java.nio.file.Path getEffectiveSaveBaseDir() {
        if (saveBaseDir != null) return saveBaseDir;
        if (currentImage == null) return null;
        ij.io.FileInfo fi = currentImage.getOriginalFileInfo();
        if (fi != null && fi.directory != null && !fi.directory.trim().isEmpty())
            return java.nio.file.Paths.get(fi.directory);
        return null;
    }

    private void updateSaveToDisplay() {
        if (saveBaseDir != null) {
            saveToDirDisplay.setText(saveBaseDir.toString());
            saveToDirDisplay.setToolTipText(saveBaseDir.toString());
            saveToDirDisplay.setForeground(UIManager.getColor("TextField.foreground"));
            saveToHintLabel.setText("");
        } else {
            saveToDirDisplay.setText("(auto — follows image file location)");
            saveToDirDisplay.setToolTipText("(auto — follows image file location)");
            saveToDirDisplay.setForeground(Color.GRAY);
            java.nio.file.Path auto = getEffectiveSaveBaseDir();
            saveToHintLabel.setText(auto != null ? "→ " + auto : "→ will prompt when saving");
        }
    }

    public void updateImage(ImagePlus image) {
        boolean imageChanged = currentImage != null && currentImage != image;
        if (imageChanged) {
            clearPreview();
        }
        currentImage = image;
        if (image == null) {
            refreshZProjCombo();
            if (saveBaseDir == null) updateSaveToDisplay();
            updateEnabled();
            installZWatcher();
            return;
        }
        updateUnit(image);
        refreshZProjCombo();
        if (saveBaseDir == null) updateSaveToDisplay(); // refresh hint with new image path
        int nCh = Math.max(1, image.getNChannels());
        ((SpinnerNumberModel) channelSpinner.getModel()).setMaximum(nCh);
        if ((Integer) channelSpinner.getValue() > nCh) channelSpinner.setValue(nCh);
        updateHistogramForChannel();
        updateEnabled();
        installZWatcher();
        if (imageChanged) onThresholdParamsChanged();
    }

    public void updateUnit(ImagePlus image) {
        String unit = resolveUnit(image);
        minVolCheck.setText(volLabel(unit));
        maxVolCheck.setText(maxVolLabel(unit));
    }

    public void setParams(SegmentationParams p) {
        syncing = true;
        seedSlider.setValue(clamp(p.seedThreshold, imgMin, imgMax));
        seedField.setText(String.valueOf(p.seedThreshold));
        areaSlider.setValue(clamp(p.areaThreshold, imgMin, imgMax));
        areaField.setText(String.valueOf(p.areaThreshold));
        areaEnabledCheck.setSelected(mode == Mode.SEED ? false : p.areaEnabled);
        minVolCheck.setSelected(p.minVolUm3 != null);
        minVolField.setText(p.minVolUm3 != null ? String.valueOf(p.minVolUm3) : "");
        minVolField.setEnabled(p.minVolUm3 != null);
        maxVolCheck.setSelected(p.maxVolUm3 != null);
        maxVolField.setText(p.maxVolUm3 != null ? String.valueOf(p.maxVolUm3) : "");
        maxVolField.setEnabled(p.maxVolUm3 != null);
        minVolSlider.setEnabled(p.minVolUm3 != null);
        maxVolSlider.setEnabled(p.maxVolUm3 != null);
        syncSizeSlidersFromFields();
        connectivityBox.setSelectedItem(p.connectivity);
        fillHolesCheck.setSelected(p.fillHoles);
        boolean split = p.areaConflictMode == QuantifierParams.AreaConflictMode.SPLIT;
        conflictSplitBtn.setSelected(split); conflictMaxBtn.setSelected(!split);
        saveModeBox.setSelectedItem(p.saveMode);
        resultPatternField.setText(p.resultFolderPattern);
        if (model != null) {
            model.setTFg(p.seedThreshold);
            model.setTBg(p.areaThreshold);
            if (histogramPanel != null) histogramPanel.repaint();
        }
        syncing = false;
        onParamsChanged();
        updateEnabled();
    }

    public SegmentationParams getParams() {
        SegmentationParams p = new SegmentationParams();
        p.seedThreshold       = seedSlider.getValue();
        p.areaThreshold       = areaSlider.getValue();
        p.areaEnabled         = mode == Mode.SEED ? false : areaEnabledCheck.isSelected();
        p.minVolUm3           = minVolCheck.isSelected() ? effectiveMinFilter(parseDoubleOrNull(minVolField.getText())) : null;
        p.maxVolUm3           = maxVolCheck.isSelected() ? effectiveMaxFilter(parseDoubleOrNull(maxVolField.getText())) : null;
        p.connectivity        = (Integer) connectivityBox.getSelectedItem();
        p.fillHoles           = fillHolesCheck.isSelected();
        p.areaConflictMode    = QuantifierParams.AreaConflictMode.MAX_OVERLAP;
        p.channel             = (Integer) channelSpinner.getValue();
        p.saveMode            = (SaveMode) saveModeBox.getSelectedItem();
        p.resultFolderPattern = resultPatternField.getText().trim();
        if (p.resultFolderPattern.isEmpty()) p.resultFolderPattern = "{name} result";
        return p;
    }

    public WorkflowPreferences.PreviewSettings getPreviewSettings() {
        WorkflowPreferences.PreviewSettings settings = new WorkflowPreferences.PreviewSettings();
        settings.hideNoise = previewNoiseCheck.isSelected();
        settings.noiseVolume = parseDoubleOrDefault(previewNoiseField.getText(), 0.0);
        settings.showRejected = showRejectedSeedCheck.isSelected();
        settings.seedColor = seedColor;
        settings.resultColor = resultColor;
        return settings;
    }

    public void setPreviewSettings(WorkflowPreferences.PreviewSettings settings) {
        if (settings == null) return;
        syncing = true;
        previewNoiseCheck.setSelected(settings.hideNoise);
        previewNoiseField.setText(formatVolume(settings.noiseVolume));
        previewNoiseField.setEnabled(settings.hideNoise);
        previewNoiseSlider.setEnabled(settings.hideNoise);
        previewNoiseSlider.setValue(volumeToSlider(settings.noiseVolume));
        showRejectedSeedCheck.setSelected(settings.showRejected);
        seedColor = settings.seedColor != null ? settings.seedColor : Color.CYAN;
        resultColor = settings.resultColor != null ? settings.resultColor : Color.YELLOW;
        btnSeedColor.setBackground(seedColor);
        btnResultColor.setBackground(resultColor);
        syncing = false;
        if (cachedFinalRoisByZ != null) {
            renderPreview(currentZPlane());
            renderSelectedZProjOverlay();
        }
    }

    public boolean hasCurrentSeedRoiCache() {
        if (mode != Mode.SEED || cachedSeedAcceptedRoisByLabel == null || cachedSeedAcceptedRoisByLabel.isEmpty()) {
            return false;
        }
        return makeKey(getParams()).equals(cachedKey);
    }

    public List<List<Roi>> getCurrentSeedRoiObjects() {
        if (!hasCurrentSeedRoiCache()) return Collections.emptyList();
        return new ArrayList<List<Roi>>(cachedSeedAcceptedRoisByLabel.values());
    }

    public void setExternalChannel(int channel) {
        int nCh = ((Number) ((SpinnerNumberModel) channelSpinner.getModel()).getMaximum()).intValue();
        int safe = Math.max(1, Math.min(channel, nCh));
        boolean changed = ((Integer) channelSpinner.getValue()) != safe;
        channelSpinner.setValue(safe);
        if (changed) onThresholdParamsChanged();
    }

    public void setExternalSaveMode(SaveMode mode) {
        saveModeBox.setSelectedItem(mode);
    }

    public void setExternalResultFolderPattern(String pattern) {
        resultPatternField.setText(pattern != null && !pattern.trim().isEmpty() ? pattern : "{name} result");
    }

    public void setExternalZProjTitle(String title) {
        refreshZProjCombo();
        String safe = title != null && !title.trim().isEmpty() ? title : ZPROJ_NONE;
        zprojSyncing = true;
        zprojCombo.setSelectedItem(safe);
        if (zprojCombo.getSelectedIndex() < 0) zprojCombo.setSelectedItem(ZPROJ_NONE);
        zprojSyncing = false;
        renderSelectedZProjOverlay();
    }

    // ── Private helpers ────────────────────────────────────────────────

    private void updateSizeSliderRangeFromCache() {
        if (cachedSeedVoxelCounts == null || cachedSeedVoxelCounts.isEmpty()) return;
        double min = Double.POSITIVE_INFINITY;
        double max = 0.0;
        for (Long count : cachedSeedVoxelCounts.values()) {
            if (count == null) continue;
            double volume = count * cachedSeedVoxelVolume;
            min = Math.min(min, volume);
            max = Math.max(max, volume);
        }
        if (!Double.isFinite(min)) min = 0.0;
        if (max <= min) max = min + 1.0;
        sizeSliderMinVolume = Math.max(0.0, min);
        sizeSliderMaxVolume = max;
        syncSizeSlidersFromFields();
    }

    private void syncSizeSlidersFromFields() {
        boolean wasSyncing = syncing;
        syncing = true;
        minVolSlider.setValue(volumeToSlider(parseDoubleOrDefault(minVolField.getText(), sizeSliderMinVolume)));
        maxVolSlider.setValue(volumeToSlider(parseDoubleOrDefault(maxVolField.getText(), sizeSliderMaxVolume)));
        previewNoiseSlider.setValue(volumeToSlider(parseDoubleOrDefault(previewNoiseField.getText(), sizeSliderMinVolume)));
        syncing = wasSyncing;
    }

    private int volumeToSlider(double volume) {
        double min = positiveSizeSliderMin();
        double max = Math.max(min * 1.000001, sizeSliderMaxVolume);
        double safeVolume = Math.max(min, Math.min(max, volume));
        double range = Math.log(max) - Math.log(min);
        if (range <= 0) return 0;
        double t = (Math.log(safeVolume) - Math.log(min)) / range;
        return (int) Math.round(t * 1000.0);
    }

    private double sliderToVolume(JSlider slider) {
        double min = positiveSizeSliderMin();
        double max = Math.max(min * 1.000001, sizeSliderMaxVolume);
        double t = Math.max(0.0, Math.min(1.0, slider.getValue() / 1000.0));
        if (t <= 0.0) return min;
        if (t >= 1.0) return max;
        return Math.exp(Math.log(min) + t * (Math.log(max) - Math.log(min)));
    }

    private double positiveSizeSliderMin() {
        return Math.max(sizeSliderMinVolume, 1.0e-12);
    }

    private Double effectiveMinFilter(Double value) {
        if (value == null) return null;
        return value <= sizeSliderMinVolume * 1.0000001 ? null : value;
    }

    private Double effectiveMaxFilter(Double value) {
        if (value == null) return null;
        return value >= sizeSliderMaxVolume / 1.0000001 ? null : value;
    }

    private static String formatVolume(double value) {
        if (value >= 100.0) return String.format(Locale.US, "%.0f", value);
        if (value >= 10.0) return String.format(Locale.US, "%.1f", value);
        return String.format(Locale.US, "%.3f", value);
    }

    private void updateHistogramForChannel() {
        int ch = (Integer) channelSpinner.getValue();
        ImagePlus channelImg = extractChannel(currentImage, ch);
        int[] mm = SeededSpotQuantifier3DImageSupport.computeStackMinMax(channelImg);
        imgMin = mm[0]; imgMax = Math.max(imgMin + 1, mm[1]);
        if (model == null) model = ThresholdModel.createFor3DPlugin(channelImg);
        else model.setImage(channelImg);
        syncing = true;
        seedSlider.setMinimum(imgMin); seedSlider.setMaximum(imgMax);
        areaSlider.setMinimum(imgMin); areaSlider.setMaximum(imgMax);
        int sv = clamp(seedSlider.getValue(), imgMin, imgMax);
        int av = clamp(areaSlider.getValue(), imgMin, imgMax);
        seedSlider.setValue(sv); seedField.setText(String.valueOf(sv));
        areaSlider.setValue(av); areaField.setText(String.valueOf(av));
        model.setTFg(sv); model.setTBg(av);
        syncing = false;
        if (histogramPanel == null) {
            histogramPanel = new HistogramPanel(channelImg, model, histListener);
            histogramPanel.setBgEnabled(mode != Mode.SEED);
            histogramPanel.setFgEnabled(mode != Mode.AREA_RESULT);
            histHolder.remove(noImageHint);
            histHolder.add(histogramPanel, BorderLayout.CENTER);
            histHolder.revalidate();
        } else {
            histogramPanel.setImage(channelImg);
            histogramPanel.setBgEnabled(mode != Mode.SEED);
            histogramPanel.setFgEnabled(mode != Mode.AREA_RESULT);
        }
        histHolder.repaint();
    }

    private void updateEnabled() {
        boolean hasImage = currentImage != null;
        seedSlider.setEnabled(hasImage);
        seedField.setEnabled(hasImage);
        minVolSlider.setEnabled(hasImage && minVolCheck.isSelected());
        maxVolSlider.setEnabled(hasImage && maxVolCheck.isSelected());
        areaSlider.setEnabled(hasImage && areaEnabledCheck.isSelected());
        areaField.setEnabled(hasImage && areaEnabledCheck.isSelected());
        boolean activeMode = !modeOff.isSelected();
        btnApply.setEnabled(hasImage && activeMode);
        btnClearPreview.setEnabled(hasImage && activeMode);
        updateManualEditGuardState();
    }

    private void setPreviewBusy(boolean busy) {
        btnApply.setEnabled(!busy);
        btnCancel.setEnabled(busy);
        if (busy) {
            // setEnabled(false) に伴う Swing の focus traversal は invokeLater で処理される。
            // その後にフォーカスを解放しないと負けるため、二段 invokeLater で確実に後処理する。
            SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() ->
                java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .clearGlobalFocusOwner()));
        }
    }

    private ImagePlus getZProjImp() {
        if (currentImage == null) return null;
        String title = (String) zprojCombo.getSelectedItem();
        if (title == null || ZPROJ_NONE.equals(title)) return null;
        ImagePlus zp = WindowManager.getImage(title);
        if (zp == null || zp.getProcessor() == null) return null;
        if (zp.getWidth() != currentImage.getWidth() || zp.getHeight() != currentImage.getHeight()) return null;
        return zp;
    }

    private int currentZPlane() {
        if (currentImage == null) return 1;
        return currentImage.isHyperStack() ? currentImage.getZ() : currentImage.getCurrentSlice();
    }

    private String makeKey(SegmentationParams p) {
        return p.seedThreshold + ":" + p.areaThreshold + ":" + p.areaEnabled + ":"
            + p.connectivity + ":" + p.fillHoles + ":" + p.minVolUm3 + ":" + p.maxVolUm3
            + ":" + p.areaConflictMode + ":" + p.channel;
    }

    private String makeSeedBaseKey(SegmentationParams p) {
        return p.seedThreshold + ":" + p.channel;
    }

    private void clearPreviewCache() {
        cachedKey = null;
        cachedFinalRoisByZ = null;
        cachedSeedRoisByZ = null;
        cachedZProjResultRois = null;
        cachedZProjSeedRois = null;
        cachedSeedBaseKey = null;
        cachedSeedRawRoisByLabel = null;
        cachedSeedAcceptedRoisByLabel = null;
        cachedSeedVoxelCounts = null;
        sizeSliderMinVolume = 0.0;
        cachedSeedVoxelVolume = 1.0;
    }

    private static Map<Integer, List<Roi>> organizeByZ(Map<Integer, List<Roi>> roisByLabel) {
        Map<Integer, List<Roi>> roisByZ = new HashMap<Integer, List<Roi>>();
        for (List<Roi> rois : roisByLabel.values()) {
            for (Roi roi : rois) {
                int z = roi.getCPosition() > 0 ? roi.getZPosition() : roi.getPosition();
                if (z <= 0) z = 1;
                List<Roi> zRois = roisByZ.get(z);
                if (zRois == null) {
                    zRois = new ArrayList<Roi>();
                    roisByZ.put(z, zRois);
                }
                zRois.add(roi);
            }
        }
        return roisByZ;
    }

    private static Map<Integer, List<Roi>> rejectedSeedRois(Map<Integer, List<Roi>> rawRois,
                                                            Map<Integer, List<Roi>> acceptedRois) {
        Map<Integer, List<Roi>> rejected = new HashMap<Integer, List<Roi>>();
        if (rawRois == null || rawRois.isEmpty()) return rejected;
        Set<Integer> acceptedLabels = acceptedRois != null ? acceptedRois.keySet() : Collections.emptySet();
        for (Map.Entry<Integer, List<Roi>> entry : rawRois.entrySet()) {
            if (!acceptedLabels.contains(entry.getKey())) {
                rejected.put(entry.getKey(), entry.getValue());
            }
        }
        return rejected;
    }

    private Map<Integer, List<Roi>> visibleRejectedSeedRois(Map<Integer, List<Roi>> rawRois,
                                                            Map<Integer, List<Roi>> acceptedRois) {
        Map<Integer, List<Roi>> rejected = new HashMap<Integer, List<Roi>>();
        if (rawRois == null || rawRois.isEmpty()) return rejected;
        Set<Integer> acceptedLabels = acceptedRois != null ? acceptedRois.keySet() : Collections.emptySet();
        Double previewMin = previewNoiseCheck.isSelected() && showRejectedSeedCheck.isSelected()
            ? effectiveMinFilter(parseDoubleOrDefault(previewNoiseField.getText(), sizeSliderMinVolume))
            : null;
        for (Map.Entry<Integer, List<Roi>> entry : rawRois.entrySet()) {
            if (acceptedLabels.contains(entry.getKey())) continue;
            Long voxels = cachedSeedVoxelCounts != null ? cachedSeedVoxelCounts.get(entry.getKey()) : null;
            double vol = (voxels != null ? voxels : 0L) * cachedSeedVoxelVolume;
            if (previewMin == null || vol >= previewMin) rejected.put(entry.getKey(), entry.getValue());
        }
        return rejected;
    }

    private boolean rebuildSeedPreviewFromSizeFilter() {
        if (mode != Mode.SEED || cachedSeedRawRoisByLabel == null || cachedSeedVoxelCounts == null) return false;
        SegmentationParams params = getParams();
        String seedBaseKey = makeSeedBaseKey(params);
        if (!seedBaseKey.equals(cachedSeedBaseKey)) return false;

        Map<Integer, List<Roi>> accepted = new HashMap<Integer, List<Roi>>();
        Map<Integer, List<Roi>> rejected = new HashMap<Integer, List<Roi>>();
        int hiddenPreviewNoise = 0;
        Double previewMin = previewNoiseCheck.isSelected()
            ? effectiveMinFilter(parseDoubleOrDefault(previewNoiseField.getText(), sizeSliderMinVolume))
            : null;
        Double minFilter = effectiveMinFilter(params.minVolUm3);
        Double maxFilter = effectiveMaxFilter(params.maxVolUm3);
        for (Map.Entry<Integer, List<Roi>> entry : cachedSeedRawRoisByLabel.entrySet()) {
            Long voxels = cachedSeedVoxelCounts.get(entry.getKey());
            double vol = (voxels != null ? voxels : 0L) * cachedSeedVoxelVolume;
            boolean keep = (minFilter == null || vol >= minFilter)
                && (maxFilter == null || vol <= maxFilter);
            if (manualIncludeLabels.contains(entry.getKey())) keep = true;
            if (manualExcludeLabels.contains(entry.getKey())) keep = false;
            if (keep) {
                accepted.put(entry.getKey(), entry.getValue());
            } else if (showRejectedSeedCheck.isSelected() && (previewMin == null || vol >= previewMin)) {
                rejected.put(entry.getKey(), entry.getValue());
            } else {
                hiddenPreviewNoise++;
            }
        }

        cachedSeedAcceptedRoisByLabel = accepted;
        cachedFinalRoisByZ = organizeByZ(accepted);
        cachedSeedRoisByZ = organizeByZ(rejected);
        cachedZProjResultRois = computeZProjRois(accepted);
        cachedZProjSeedRois = computeZProjRois(rejected);
        cachedKey = makeKey(params);
        renderPreview(currentZPlane());
        renderSelectedZProjOverlay();
        updateManualButtonState();
        setPreviewStatus(accepted.size() + " kept, " + rejected.size()
            + " filtered out" + (hiddenPreviewNoise > 0 ? ", " + hiddenPreviewNoise + " hidden" : ""));
        return true;
    }

    private void toggleManualPickMode(ManualPickMode mode) {
        if (cachedSeedRawRoisByLabel == null || cachedSeedRawRoisByLabel.isEmpty()) {
            setPreviewStatus("Press Apply before manual picking.");
            return;
        }
        if (manualPickMode == mode) {
            uninstallManualPickMode();
            return;
        }
        installManualPickMode(mode);
    }

    private void installManualPickMode(ManualPickMode mode) {
        uninstallManualPickMode();
        manualPickMode = mode;
        manualPickListener = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { handleManualPickMove(e); }
            @Override public void mouseExited(MouseEvent e) { clearManualHover(); }
            @Override public void mouseClicked(MouseEvent e) { handleManualPickClick(e); }
        };
        addManualPickCanvas(currentImage);
        addManualPickCanvas(getZProjImp());
        updateManualButtonState();
        IJ.showStatus("Seed manual " + (mode == ManualPickMode.INCLUDE ? "include" : "exclude") + ": click ROI on image or Z-proj");
    }

    private void addManualPickCanvas(ImagePlus imp) {
        if (imp == null || imp.getCanvas() == null || manualPickListener == null) return;
        ImageCanvas canvas = imp.getCanvas();
        if (manualPickCanvases.contains(canvas)) return;
        canvas.addMouseMotionListener(manualPickListener);
        canvas.addMouseListener(manualPickListener);
        manualPickCanvases.add(canvas);
    }

    private void uninstallManualPickMode() {
        if (manualPickListener != null) {
            for (ImageCanvas canvas : new ArrayList<ImageCanvas>(manualPickCanvases)) {
                canvas.removeMouseMotionListener(manualPickListener);
                canvas.removeMouseListener(manualPickListener);
            }
        }
        manualPickCanvases.clear();
        manualPickListener = null;
        manualPickMode = null;
        clearManualHover();
        updateManualButtonState();
        IJ.showStatus("");
    }

    private void handleManualPickMove(MouseEvent e) {
        if (manualPickMode == null || cachedSeedRawRoisByLabel == null) return;
        Object source = e.getSource();
        if (!(source instanceof ImageCanvas)) return;
        ImageCanvas canvas = (ImageCanvas) source;
        Point point = new Point(canvas.offScreenX(e.getX()), canvas.offScreenY(e.getY()));
        boolean zproj = getZProjImp() != null && canvas == getZProjImp().getCanvas();
        Integer label = findSeedLabelAt(point, zproj);
        if (Objects.equals(label, manualHoverLabel) && zproj == manualHoverOnZProj) return;
        manualHoverLabel = label;
        manualHoverOnZProj = zproj;
        renderPreview(currentZPlane());
        renderSelectedZProjOverlay();
    }

    private void handleManualPickClick(MouseEvent e) {
        if (manualPickMode == null || cachedSeedRawRoisByLabel == null) return;
        Object source = e.getSource();
        if (!(source instanceof ImageCanvas)) return;
        ImageCanvas canvas = (ImageCanvas) source;
        Point point = new Point(canvas.offScreenX(e.getX()), canvas.offScreenY(e.getY()));
        boolean zproj = getZProjImp() != null && canvas == getZProjImp().getCanvas();
        Integer label = findSeedLabelAt(point, zproj);
        if (label == null) {
            previewCountLabel.setText("No seed ROI at click.");
            return;
        }
        if (manualPickMode == ManualPickMode.INCLUDE) {
            manualExcludeLabels.remove(label);
            manualIncludeLabels.add(label);
        } else {
            manualIncludeLabels.remove(label);
            manualExcludeLabels.add(label);
        }
        rebuildSeedPreviewFromSizeFilter();
        updateManualEditGuardState();
        e.consume();
    }

    private void clearManualHover() {
        if (manualHoverLabel == null) return;
        manualHoverLabel = null;
        renderPreview(currentZPlane());
        renderSelectedZProjOverlay();
    }

    private Integer findSeedLabelAt(Point point, boolean zproj) {
        List<Integer> hits = new ArrayList<Integer>();
        int z = currentZPlane();
        for (Map.Entry<Integer, List<Roi>> entry : cachedSeedRawRoisByLabel.entrySet()) {
            for (Roi roi : entry.getValue()) {
                if (!zproj && !roiMatchesZ(roi, z)) continue;
                if (roiContains(roi, point.x, point.y)) {
                    hits.add(entry.getKey());
                    break;
                }
            }
        }
        if (hits.isEmpty()) return null;
        Collections.sort(hits, (a, b) -> Double.compare(labelArea(a), labelArea(b)));
        return hits.get(0);
    }

    private boolean roiMatchesZ(Roi roi, int z) {
        int rz = roi.getCPosition() > 0 ? roi.getZPosition() : roi.getPosition();
        return rz <= 0 || rz == z;
    }

    private boolean roiContains(Roi roi, int x, int y) {
        try {
            return roi.contains(x, y);
        } catch (RuntimeException ex) {
            Rectangle bounds = roi.getBounds();
            return bounds != null && bounds.contains(x, y);
        }
    }

    private double labelArea(Integer label) {
        List<Roi> rois = cachedSeedRawRoisByLabel.get(label);
        if (rois == null || rois.isEmpty()) return Double.POSITIVE_INFINITY;
        double area = 0.0;
        for (Roi roi : rois) {
            try {
                area += roi.getStatistics().area;
            } catch (RuntimeException ex) {
                Rectangle bounds = roi.getBounds();
                if (bounds != null) area += bounds.getWidth() * bounds.getHeight();
            }
        }
        return area > 0 ? area : Double.POSITIVE_INFINITY;
    }

    private List<Roi> filterRoisForZ(List<Roi> rois, int zPlane) {
        List<Roi> out = new ArrayList<Roi>();
        if (rois == null) return out;
        for (Roi roi : rois) {
            if (roiMatchesZ(roi, zPlane)) out.add(roi);
        }
        return out;
    }

    private List<Roi> computeZProjRoisForLabel(Integer label) {
        List<Roi> rois = cachedSeedRawRoisByLabel != null ? cachedSeedRawRoisByLabel.get(label) : null;
        if (rois == null || rois.isEmpty()) return Collections.emptyList();
        ShapeRoi projected = null;
        for (Roi roi : rois) {
            ShapeRoi sr = new ShapeRoi(cloneForPreviewOverlay(roi));
            projected = projected == null ? sr : projected.or(sr);
        }
        if (projected == null) return Collections.emptyList();
        projected.setPosition(0);
        return Collections.<Roi>singletonList(projected);
    }

    private void clearManualPicks() {
        if (!hasManualEdits()) return;
        int ok = JOptionPane.showConfirmDialog(this,
            "Clear manual include/exclude edits?",
            "Clear Manual Edits", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        manualIncludeLabels.clear();
        manualExcludeLabels.clear();
        uninstallManualPickMode();
        rebuildSeedPreviewFromSizeFilter();
        updateManualEditGuardState();
    }

    private void updateManualButtonState() {
        boolean ready = mode == Mode.SEED && cachedSeedRawRoisByLabel != null && !cachedSeedRawRoisByLabel.isEmpty();
        btnManualInclude.setEnabled(ready);
        btnManualExclude.setEnabled(ready);
        btnManualClear.setEnabled(ready && hasManualEdits());
        btnManualInclude.setText(manualPickMode == ManualPickMode.INCLUDE ? "Picking Include..." : "Manual Include");
        btnManualExclude.setText(manualPickMode == ManualPickMode.EXCLUDE ? "Picking Exclude..." : "Manual Exclude");
    }

    private boolean hasManualEdits() {
        return !manualIncludeLabels.isEmpty() || !manualExcludeLabels.isEmpty();
    }

    private void updateManualEditGuardState() {
        boolean locked = mode == Mode.SEED && hasManualEdits();
        seedSlider.setEnabled(!locked && currentImage != null);
        seedField.setEnabled(!locked && currentImage != null);
        minVolCheck.setEnabled(!locked);
        minVolField.setEnabled(!locked && currentImage != null && minVolCheck.isSelected());
        minVolSlider.setEnabled(!locked && currentImage != null && minVolCheck.isSelected());
        maxVolCheck.setEnabled(!locked);
        maxVolField.setEnabled(!locked && currentImage != null && maxVolCheck.isSelected());
        maxVolSlider.setEnabled(!locked && currentImage != null && maxVolCheck.isSelected());
        showRejectedSeedCheck.setEnabled(true);
        previewNoiseCheck.setEnabled(true);
        previewNoiseField.setEnabled(previewNoiseCheck.isSelected());
        previewNoiseSlider.setEnabled(previewNoiseCheck.isSelected());
        sizeFilterGuardPanel.setVisible(locked);
        seedThresholdSection.setToolTipText(locked ? "Clear manual edits before changing threshold." : null);
        minVolRow.setToolTipText(locked ? "Clear manual edits before changing size filter." : null);
        maxVolRow.setToolTipText(locked ? "Clear manual edits before changing size filter." : null);
    }

    private static List<Roi> computeZProjRois(Map<Integer, List<Roi>> roisByLabel) {
        List<Roi> out = new ArrayList<Roi>();
        for (List<Roi> slices : roisByLabel.values()) {
            ShapeRoi projected = null;
            for (Roi roi : slices) {
                ShapeRoi sr = new ShapeRoi(cloneForPreviewOverlay(roi));
                projected = projected == null ? sr : projected.or(sr);
            }
            if (projected != null) {
                projected.setPosition(0);
                out.add(projected);
            }
        }
        return out;
    }

    private static void addRoisToOverlay(Overlay overlay, List<Roi> rois, Color color) {
        if (rois == null) return;
        for (Roi roi : rois) {
            Roi viewRoi = cloneForPreviewOverlay(roi);
            viewRoi.setStrokeColor(color);
            overlay.add(viewRoi);
        }
    }

    private static ShapeRoi mergeAll(List<Roi> rois) {
        ShapeRoi merged = null;
        for (Roi roi : rois) {
            ShapeRoi sr = new ShapeRoi(cloneForPreviewOverlay(roi));
            merged = merged == null ? sr : merged.or(sr);
        }
        if (merged != null) merged.setPosition(0);
        return merged;
    }

    private static Roi cloneForPreviewOverlay(Roi roi) {
        Roi clone = (Roi) roi.clone();
        clone.setPosition(0);
        clone.setPosition(0, 0, 0);
        return clone;
    }

    private void setPreviewStatus(String text) {
        previewCountLabel.setText(shortStatus(text));
        previewCountLabel.setToolTipText(text == null || text.isEmpty() ? null : text);
    }

    private static Set<Integer> currentImageIdSet() {
        Set<Integer> ids = new HashSet<>();
        int[] list = WindowManager.getIDList();
        if (list != null) for (int id : list) ids.add(id);
        return ids;
    }

    private static ImagePlus findNewImage(Set<Integer> beforeIds) {
        int[] list = WindowManager.getIDList();
        if (list == null) return null;
        for (int id : list) if (!beforeIds.contains(id)) return WindowManager.getImage(id);
        return null;
    }

    private static ImagePlus extractChannel(ImagePlus image, int channel) {
        if (image.getNChannels() <= 1) return image;
        int safeC = Math.max(1, Math.min(channel, image.getNChannels()));
        return new Duplicator().run(image, safeC, safeC, 1, image.getNSlices(), 1, image.getNFrames());
    }

    private static String resolveUnit(ImagePlus image) {
        if (image == null) return "µm";
        Calibration cal = image.getCalibration();
        if (cal == null) return "µm";
        String unit = cal.getUnit();
        return (unit == null || unit.trim().isEmpty()) ? "px" : unit.trim();
    }

    private static String volLabel(String unit) { return "Min size " + unit + "³"; }
    private static String maxVolLabel(String unit) { return "Max size " + unit + "³"; }

    private static JSpinner intSpinner(int val, int min, int max, int step) {
        return new JSpinner(new SpinnerNumberModel(val, min, max, step));
    }

    private static JTextField numField(String text) {
        JTextField f = new JTextField(text, 7);
        f.setHorizontalAlignment(JTextField.RIGHT);
        Dimension size = new Dimension(80, f.getPreferredSize().height);
        f.setPreferredSize(size);
        f.setMinimumSize(size);
        f.setMaximumSize(size);
        return f;
    }

    private static JButton colorSwatch(Color c, String tooltip) {
        JButton btn = new JButton();
        btn.setBackground(c);
        btn.setPreferredSize(new Dimension(28, 20));
        btn.setOpaque(true);
        btn.setBorderPainted(true);
        btn.setToolTipText(tooltip);
        return btn;
    }

    private static JPanel titledPanel(String title) {
        JPanel p = new JPanel();
        p.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(),
            title, TitledBorder.LEFT, TitledBorder.TOP));
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private static Component vgap(int h) { return Box.createRigidArea(new Dimension(0, h)); }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static Double parseDoubleOrNull(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static double parseDoubleOrDefault(String s, double fallback) {
        if (s == null || s.trim().isEmpty()) return fallback;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static String shortStatus(String text) {
        if (text == null) return "";
        String s = text.trim();
        int max = 80;
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
