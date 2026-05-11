package io.github.kusumotok.spotworkflow;

import ij.IJ;
import ij.ImageListener;
import ij.ImagePlus;
import ij.WindowManager;
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
    private final JCheckBox  maxVolCheck = new JCheckBox(volLabel("µm"), false);
    private final JTextField maxVolField = numField("50.0");

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
    private Color seedColor   = Color.decode("#AA00FF");   // purple
    private Color resultColor = Color.decode("#FFFF00");   // yellow
    private final JButton btnSeedColor   = colorSwatch(seedColor,   "Seed color");
    private final JButton btnResultColor = colorSwatch(resultColor, "Result color");
    final JButton btnApply        = new JButton("Apply Preview");
    final JButton btnClearPreview = new JButton("Clear");
    final JButton btnCancel       = new JButton("Cancel");
    final JLabel  previewCountLabel = new JLabel("");

    // ── Save ───────────────────────────────────────────────────────────
    private final JComboBox<SaveMode> saveModeBox =
        new JComboBox<>(new SaveMode[]{SaveMode.FOLDER, SaveMode.ZIP_FAST, SaveMode.ZIP_COMPRESSED});
    private final JTextField resultPatternField = new JTextField("{name} result", 22);
    // Save-to: null = auto (follow image file directory); non-null = user-set fixed path
    private java.nio.file.Path saveBaseDir = null;
    private final JTextField saveToDirDisplay = new JTextField(24);
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
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicInteger previewGen      = new AtomicInteger();
    private ImageListener zWatcher;
    private boolean tabActive = true; // false = 他タブ表示中。Z-watcher を抑制する

    private static final class PreviewResult {
        final Map<Integer, List<Roi>> finalSliceRois;
        final Map<Integer, List<Roi>> seedSliceRois;

        PreviewResult(Map<Integer, List<Roi>> finalSliceRois,
                      Map<Integer, List<Roi>> seedSliceRois) {
            this.finalSliceRois = finalSliceRois;
            this.seedSliceRois = seedSliceRois;
        }
    }

    // ── Histogram listener ─────────────────────────────────────────────
    private final HistogramPanel.ThresholdListener histListener = (tBg, tFg) -> {
        if (model == null) return;
        int oldBg = model.getTBg(), oldFg = model.getTFg();
        syncing = true;
        model.setTBg(tBg); model.setTFg(tFg);
        areaSlider.setValue(tBg); areaField.setText(String.valueOf(tBg));
        seedSlider.setValue(tFg); seedField.setText(String.valueOf(tFg));
        syncing = false;
        if (histogramPanel != null) histogramPanel.repaintThresholdMarkers(oldBg, oldFg);
        onParamsChanged();
    };

    public SegmentationTab() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        add(buildChannelRow());
        add(vgap(4));
        add(buildHistogramSection());
        add(vgap(4));
        add(buildThreshRow("Seed threshold:", seedSlider, seedField));
        add(vgap(2));
        add(buildVolRow(minVolCheck, minVolField));
        add(vgap(1));
        add(buildVolRow(maxVolCheck, maxVolField));
        add(vgap(4));
        add(buildAreaSection());
        add(vgap(6));
        add(buildThreeDSection());
        add(vgap(6));
        add(buildPreviewSection());
        add(vgap(6));
        add(buildSaveSection());
        add(Box.createVerticalGlue());

        wireListeners();
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
        histHolder.setPreferredSize(new Dimension(420, 140));
        histHolder.setMinimumSize(new Dimension(200, 100));
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
        JPanel sliderRow = new JPanel(new BorderLayout(4, 0));
        sliderRow.setAlignmentX(LEFT_ALIGNMENT);
        sliderRow.add(slider, BorderLayout.CENTER);
        sliderRow.add(field,  BorderLayout.EAST);
        outer.add(labelRow);
        outer.add(sliderRow);
        return outer;
    }

    private JPanel buildVolRow(JCheckBox check, JTextField field) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.add(check, BorderLayout.WEST);
        p.add(field, BorderLayout.EAST);
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
        JPanel sliderRow = new JPanel(new BorderLayout(4, 0));
        sliderRow.setAlignmentX(LEFT_ALIGNMENT);
        sliderRow.add(areaSlider, BorderLayout.CENTER);
        sliderRow.add(areaField,  BorderLayout.EAST);
        outer.add(labelRow);
        outer.add(sliderRow);
        return outer;
    }

    private JPanel buildThreeDSection() {
        JPanel p = titledPanel("3D options");
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JPanel connRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        connRow.setAlignmentX(LEFT_ALIGNMENT);
        connRow.add(new JLabel("Connectivity:")); connRow.add(connectivityBox); connRow.add(fillHolesCheck);
        ButtonGroup cg = new ButtonGroup(); cg.add(conflictMaxBtn); cg.add(conflictSplitBtn);
        JPanel conflictRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        conflictRow.setAlignmentX(LEFT_ALIGNMENT);
        conflictRow.add(new JLabel("Area conflict:")); conflictRow.add(conflictMaxBtn); conflictRow.add(conflictSplitBtn);
        p.add(connRow); p.add(conflictRow);
        return p;
    }

    private JPanel buildPreviewSection() {
        JPanel p = titledPanel("Preview");
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));

        // Mode + colors
        ButtonGroup mg = new ButtonGroup(); mg.add(modeOff); mg.add(modeRoi); mg.add(modeRoiLight);
        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        modeRow.setAlignmentX(LEFT_ALIGNMENT);
        modeRow.add(new JLabel("Mode:"));
        modeRow.add(modeOff); modeRow.add(modeRoi); modeRow.add(modeRoiLight);
        modeRow.add(Box.createHorizontalStrut(10));
        modeRow.add(new JLabel("Seed:")); modeRow.add(btnSeedColor);
        modeRow.add(new JLabel("Result:")); modeRow.add(btnResultColor);

        // Apply / Cancel / Clear
        btnCancel.setEnabled(false);
        previewCountLabel.setFont(previewCountLabel.getFont().deriveFont(Font.ITALIC, 11f));
        previewCountLabel.setForeground(Color.DARK_GRAY);
        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actionRow.setAlignmentX(LEFT_ALIGNMENT);
        actionRow.add(btnApply); actionRow.add(btnCancel); actionRow.add(btnClearPreview);
        actionRow.add(previewCountLabel);

        p.add(modeRow); p.add(actionRow);
        return p;
    }

    private JPanel buildSaveSection() {
        JPanel p = titledPanel("Save");
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));

        JPanel folderRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        folderRow.setAlignmentX(LEFT_ALIGNMENT);
        folderRow.add(new JLabel("Result folder:")); folderRow.add(resultPatternField);

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
        modeRow.add(new JLabel("Save mode:")); modeRow.add(saveModeBox);

        p.add(folderRow);
        p.add(saveToRow);
        p.add(saveToHintLabel);
        p.add(modeRow);

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
            onParamsChanged();
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
            onParamsChanged();
        });
        areaField.addActionListener(e -> commitAreaField());
        areaField.addFocusListener(new FocusAdapter() { @Override public void focusLost(FocusEvent e) { commitAreaField(); } });

        // Volume / area enabled
        minVolField.setEnabled(minVolCheck.isSelected());
        maxVolField.setEnabled(maxVolCheck.isSelected());
        areaSlider.setEnabled(areaEnabledCheck.isSelected());
        areaField.setEnabled(areaEnabledCheck.isSelected());
        minVolCheck.addActionListener(e -> { minVolField.setEnabled(minVolCheck.isSelected()); onParamsChanged(); });
        maxVolCheck.addActionListener(e -> { maxVolField.setEnabled(maxVolCheck.isSelected()); onParamsChanged(); });
        areaEnabledCheck.addActionListener(e -> {
            boolean en = areaEnabledCheck.isSelected();
            areaSlider.setEnabled(en); areaField.setEnabled(en);
            onParamsChanged();
        });
        minVolField.addActionListener(e -> onParamsChanged());
        maxVolField.addActionListener(e -> onParamsChanged());
        connectivityBox.addActionListener(e -> onParamsChanged());
        fillHolesCheck.addActionListener(e -> onParamsChanged());
        conflictMaxBtn.addActionListener(e -> onParamsChanged());
        conflictSplitBtn.addActionListener(e -> onParamsChanged());

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
            else if (currentImage != null) previewCountLabel.setText("Press Apply to update");
        });
        modeRoiLight.addActionListener(e -> {
            updateEnabled();
            if (cachedFinalRoisByZ != null) {
                renderPreview(currentZPlane());
                renderSelectedZProjOverlay();
            }
            else if (currentImage != null) previewCountLabel.setText("Press Apply to update");
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
        btnApply.addActionListener(e -> applyPreview());
        btnCancel.addActionListener(e -> cancelPreview());
        btnClearPreview.addActionListener(e -> clearPreview());
    }

    // ── Param change ───────────────────────────────────────────────────

    private void onParamsChanged() {
        clearPreviewCache();
        if (!modeOff.isSelected()) {
            previewCountLabel.setText("Press Apply to update");
        }
    }

    // ── Preview: apply ─────────────────────────────────────────────────

    public void applyPreview() {
        if (currentImage == null || modeOff.isSelected()) return;

        SegmentationParams params = getParams();
        String key = makeKey(params);

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
        previewCountLabel.setText("Computing...");

        Calibration cal = currentImage.getCalibration();
        double vw = cal.pixelWidth  > 0 ? cal.pixelWidth  : 1.0;
        double vh = cal.pixelHeight > 0 ? cal.pixelHeight : 1.0;
        double vd = cal.pixelDepth  > 0 ? cal.pixelDepth  : 1.0;
        final ImagePlus channelImg  = extractChannel(currentImage, params.channel);
        final boolean   ownsChannel = channelImg != currentImage;

        new SwingWorker<PreviewResult, Void>() {
            @Override
            protected PreviewResult doInBackground() {
                try {
                    SeededQuantifier3D.SeededResult seeded = SeededQuantifier3D.compute(
                        channelImg, params.areaThreshold, params.seedThreshold,
                        params.toQuantifierParams(), vw * vh * vd, params.areaEnabled,
                        null, cancelRequested::get);
                    if (seeded == null || seeded.finalSeg == null || seeded.finalSeg.labelImage == null) {
                        return null;
                    }
                    RoiExporter3D exporter = new RoiExporter3D();
                    Map<Integer, List<Roi>> finalRois = exporter.exportToRoiListsByLabel(
                        seeded.finalSeg.labelImage, null, currentImage, params.channel);
                    Map<Integer, List<Roi>> seedRois = null;
                    if (params.areaEnabled && seeded.seedSeg != null && seeded.seedSeg.labelImage != null) {
                        seedRois = exporter.exportToRoiListsByLabel(
                            seeded.seedSeg.labelImage, null, currentImage, params.channel);
                    }
                    return new PreviewResult(finalRois, seedRois);
                } finally {
                    if (ownsChannel) channelImg.flush();
                }
            }
            @Override
            protected void done() {
                if (previewGen.get() != gen) { setPreviewBusy(false); return; }
                setPreviewBusy(false);
                try {
                    PreviewResult result = get();
                    if (result == null || result.finalSliceRois == null || result.finalSliceRois.isEmpty()) {
                        previewCountLabel.setText("No spots found.");
                        return;
                    }
                    cachedFinalRoisByZ = organizeByZ(result.finalSliceRois);
                    cachedSeedRoisByZ = result.seedSliceRois != null ? organizeByZ(result.seedSliceRois) : null;
                    cachedZProjResultRois = computeZProjRois(result.finalSliceRois);
                    cachedZProjSeedRois = result.seedSliceRois != null ? computeZProjRois(result.seedSliceRois) : null;
                    cachedKey = key;
                    renderPreview(currentZPlane());
                    renderSelectedZProjOverlay();
                    int n = result.finalSliceRois.size();
                    previewCountLabel.setText(n + " spot" + (n != 1 ? "s" : ""));
                } catch (CancellationException e) {
                    previewCountLabel.setText("Cancelled.");
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    previewCountLabel.setText("Error: " + cause.getMessage());
                }
            }
        }.execute();
    }

    // ── Preview: render current Z ──────────────────────────────────────

    private void renderPreview(int zPlane) {
        if (!tabActive || cachedFinalRoisByZ == null || currentImage == null || modeOff.isSelected()) return;

        Overlay overlay = new Overlay();
        addRoisToOverlay(overlay, cachedFinalRoisByZ.get(zPlane), resultColor);
        if (areaEnabledCheck.isSelected() && cachedSeedRoisByZ != null) {
            addRoisToOverlay(overlay, cachedSeedRoisByZ.get(zPlane), seedColor);
        }

        currentImage.setOverlay(overlay);
        currentImage.updateAndDraw();
    }

    private void renderZProjOverlay(ImagePlus zp) {
        if (zp == null || cachedZProjResultRois == null) return;
        Overlay overlay = new Overlay();

        if (modeRoiLight.isSelected()) {
            ShapeRoi merged = mergeAll(cachedZProjResultRois);
            if (merged != null) {
                merged.setStrokeColor(resultColor);
                overlay.add(merged);
            }
            if (areaEnabledCheck.isSelected() && cachedZProjSeedRois != null) {
                ShapeRoi mergedSeed = mergeAll(cachedZProjSeedRois);
                if (mergedSeed != null) {
                    mergedSeed.setStrokeColor(seedColor);
                    overlay.add(mergedSeed);
                }
            }
        } else {
            addRoisToOverlay(overlay, cachedZProjResultRois, resultColor);
            if (areaEnabledCheck.isSelected() && cachedZProjSeedRois != null) {
                addRoisToOverlay(overlay, cachedZProjSeedRois, seedColor);
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
        tabActive = active;
    }

    /** Clears overlay on the image and Z-proj, but keeps the cache. */
    public void clearOverlayOnly() {
        clearPreviewOverlay();
    }

    /** Clears overlay, cancels computation, and frees the cache. */
    public void clearPreview() {
        cancelPreview();
        clearPreviewOverlay();
        clearPreviewCache();
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
        if (!tabActive || modeOff.isSelected() || cachedFinalRoisByZ == null) return;
        SwingUtilities.invokeLater(() -> renderPreview(currentZPlane()));
    }

    // ── Z-proj commands ────────────────────────────────────────────────

    private void cmdCreateMaxProj() {
        if (currentImage == null) return;
        Set<Integer> beforeIds = currentImageIdSet();
        IJ.run(currentImage, "Z Project...", "projection=[Max Intensity]");
        ImagePlus result = findNewImage(beforeIds);
        if (result == null && WindowManager.getCurrentImage() != currentImage)
            result = WindowManager.getCurrentImage();
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
            saveToDirDisplay.setForeground(UIManager.getColor("TextField.foreground"));
            saveToHintLabel.setText("");
        } else {
            saveToDirDisplay.setText("(auto — follows image file location)");
            saveToDirDisplay.setForeground(Color.GRAY);
            java.nio.file.Path auto = getEffectiveSaveBaseDir();
            saveToHintLabel.setText(auto != null ? "→ " + auto : "→ will prompt when saving");
        }
    }

    public void updateImage(ImagePlus image) {
        currentImage = image;
        updateUnit(image);
        refreshZProjCombo();
        if (saveBaseDir == null) updateSaveToDisplay(); // refresh hint with new image path
        int nCh = Math.max(1, image.getNChannels());
        ((SpinnerNumberModel) channelSpinner.getModel()).setMaximum(nCh);
        if ((Integer) channelSpinner.getValue() > nCh) channelSpinner.setValue(nCh);
        updateHistogramForChannel();
        updateEnabled();
        installZWatcher();
    }

    public void updateUnit(ImagePlus image) {
        String unit = resolveUnit(image);
        minVolCheck.setText(volLabel(unit));
        maxVolCheck.setText(volLabel(unit));
    }

    public void setParams(SegmentationParams p) {
        syncing = true;
        seedSlider.setValue(clamp(p.seedThreshold, imgMin, imgMax));
        seedField.setText(String.valueOf(p.seedThreshold));
        areaSlider.setValue(clamp(p.areaThreshold, imgMin, imgMax));
        areaField.setText(String.valueOf(p.areaThreshold));
        areaEnabledCheck.setSelected(p.areaEnabled);
        minVolCheck.setSelected(p.minVolUm3 != null);
        minVolField.setText(p.minVolUm3 != null ? String.valueOf(p.minVolUm3) : "");
        minVolField.setEnabled(p.minVolUm3 != null);
        maxVolCheck.setSelected(p.maxVolUm3 != null);
        maxVolField.setText(p.maxVolUm3 != null ? String.valueOf(p.maxVolUm3) : "");
        maxVolField.setEnabled(p.maxVolUm3 != null);
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
        p.areaEnabled         = areaEnabledCheck.isSelected();
        p.minVolUm3           = minVolCheck.isSelected() ? parseDoubleOrNull(minVolField.getText()) : null;
        p.maxVolUm3           = maxVolCheck.isSelected() ? parseDoubleOrNull(maxVolField.getText()) : null;
        p.connectivity        = (Integer) connectivityBox.getSelectedItem();
        p.fillHoles           = fillHolesCheck.isSelected();
        p.areaConflictMode    = conflictSplitBtn.isSelected()
            ? QuantifierParams.AreaConflictMode.SPLIT : QuantifierParams.AreaConflictMode.MAX_OVERLAP;
        p.channel             = (Integer) channelSpinner.getValue();
        p.saveMode            = (SaveMode) saveModeBox.getSelectedItem();
        p.resultFolderPattern = resultPatternField.getText().trim();
        if (p.resultFolderPattern.isEmpty()) p.resultFolderPattern = "{name} result";
        return p;
    }

    // ── Private helpers ────────────────────────────────────────────────

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
            histHolder.remove(noImageHint);
            histHolder.add(histogramPanel, BorderLayout.CENTER);
            histHolder.revalidate();
        } else {
            histogramPanel.setImage(channelImg);
        }
        histHolder.repaint();
    }

    private void updateEnabled() {
        boolean hasImage = currentImage != null;
        seedSlider.setEnabled(hasImage);
        seedField.setEnabled(hasImage);
        areaSlider.setEnabled(hasImage && areaEnabledCheck.isSelected());
        areaField.setEnabled(hasImage && areaEnabledCheck.isSelected());
        boolean activeMode = !modeOff.isSelected();
        btnApply.setEnabled(hasImage && activeMode);
        btnClearPreview.setEnabled(hasImage && activeMode);
    }

    private void setPreviewBusy(boolean busy) {
        btnApply.setEnabled(!busy);
        btnCancel.setEnabled(busy);
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

    private void clearPreviewCache() {
        cachedKey = null;
        cachedFinalRoisByZ = null;
        cachedSeedRoisByZ = null;
        cachedZProjResultRois = null;
        cachedZProjSeedRois = null;
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
        return clone;
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

    private static String volLabel(String unit) { return "Vol " + unit + "³ (seed)"; }

    private static JSpinner intSpinner(int val, int min, int max, int step) {
        return new JSpinner(new SpinnerNumberModel(val, min, max, step));
    }

    private static JTextField numField(String text) {
        JTextField f = new JTextField(text, 7);
        f.setHorizontalAlignment(JTextField.RIGHT);
        f.setMaximumSize(new Dimension(80, f.getPreferredSize().height));
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
}
