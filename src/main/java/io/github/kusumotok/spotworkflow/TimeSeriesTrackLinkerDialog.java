package io.github.kusumotok.spotworkflow;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ImageProcessor;
import io.github.kusumotok.spotworkflow.tracking.TrackEditor;
import io.github.kusumotok.spotworkflow.tracking.TrackTree;
import io.github.kusumotok.spotworkflow.tracking.TrackTreeIo;
import io.github.kusumotok.spotworkflow.tracking.TrackValidator;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

final class TimeSeriesTrackLinkerDialog extends JDialog {
    private final ImagePlus image;
    private final Path tracksRoot;
    private final Runnable onSaved;
    private final TrackTreeIo io = new TrackTreeIo();
    private final TrackEditor editor = new TrackEditor();
    private final TrackValidator validator = new TrackValidator();

    private final int maxT;
    private final int maxZ;
    private final int maxC;
    private final AxisControl tAxis;
    private final AxisControl leftCAxis;
    private final AxisControl rightCAxis;
    private final AxisControl leftZAxis;
    private final AxisControl rightZAxis;

    private final DefaultListModel<TrackEditor.ObjRef> leftModel = new DefaultListModel<TrackEditor.ObjRef>();
    private final DefaultListModel<TrackEditor.ObjRef> rightModel = new DefaultListModel<TrackEditor.ObjRef>();
    private final JList<TrackEditor.ObjRef> leftList = new JList<TrackEditor.ObjRef>(leftModel);
    private final JList<TrackEditor.ObjRef> rightList = new JList<TrackEditor.ObjRef>(rightModel);
    private final LinkerImagePane leftPane = new LinkerImagePane(true);
    private final LinkerImagePane rightPane = new LinkerImagePane(false);
    private final LinkerImageArea imageArea = new LinkerImageArea();

    private final JCheckBox zProjMode = new JCheckBox("Z-proj", true);
    private final JCheckBox zSync = new JCheckBox("Z sync", true);
    private final JCheckBox cSync = new JCheckBox("C sync", true);
    private final JCheckBox showRois = new JCheckBox("ROI outlines", true);
    private final JCheckBox showCentroids = new JCheckBox("Centroids", true);
    private final JCheckBox showTrajectories = new JCheckBox("Trajectories", true);
    private final JCheckBox showLabels = new JCheckBox("Labels", true);
    private final JCheckBox showAllLinks = new JCheckBox("All T/T+1 links", false);
    private final JButton linkButton = new JButton("Link");
    private final JButton replaceButton = new JButton("Replace");
    private final JButton unlinkPrevButton = new JButton("Unlink previous");
    private final JButton unlinkNextButton = new JButton("Unlink next");
    private final JButton clearSelectionButton = new JButton("Clear selection");
    private final JButton saveButton = new JButton("Save Tracking");
    private final JLabel selectionStatus = new JLabel(" ");
    private final JLabel status = new JLabel(" ");

    private final java.awt.event.AWTEventListener dragReleaseListener = this::handleGlobalMouseRelease;
    private final java.util.Map<String, BufferedImage> frameCache = new java.util.LinkedHashMap<String, BufferedImage>() {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, BufferedImage> eldest) {
            return size() > 6;
        }
    };
    private final java.util.List<Trajectory> trajectories = new java.util.ArrayList<Trajectory>();

    private TrackTree tree;
    private boolean updatingControls;
    private TrackEditor.ObjRef draggingLeftRef;
    private Side lastSelectedSide;
    private int lastPairT = 1;
    private double viewScale = 0.0;
    private double viewOffsetX = 0.0;
    private double viewOffsetY = 0.0;

    TimeSeriesTrackLinkerDialog(Window owner, ImagePlus image, Path tracksRoot, Runnable onSaved) throws IOException {
        super(owner, "Track Linker", ModalityType.MODELESS);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        this.image = image;
        this.tracksRoot = tracksRoot;
        this.onSaved = onSaved;
        this.maxT = image != null ? Math.max(1, image.getNFrames()) : 1;
        this.maxZ = image != null ? Math.max(1, image.getNSlices()) : 1;
        this.maxC = image != null ? Math.max(1, image.getNChannels()) : 1;
        this.tAxis = new AxisControl("T", 1, Math.max(1, maxT - 1), 1);
        int initialC = image != null ? Math.max(1, image.getC()) : 1;
        this.leftCAxis = new AxisControl("C left", 1, maxC, initialC);
        this.rightCAxis = new AxisControl("C right", 1, maxC, initialC);
        this.leftZAxis = new AxisControl("Z left", 1, maxZ, 1);
        this.rightZAxis = new AxisControl("Z right", 1, maxZ, 1);
        this.tree = io.read(tracksRoot);
        rebuildTrajectoryCache();
        buildUi();
        installListeners();
        Toolkit.getDefaultToolkit().addAWTEventListener(dragReleaseListener, AWTEvent.MOUSE_EVENT_MASK);
        reloadPair();
        pack();
        setSize(Math.max(1120, getWidth()), Math.max(780, getHeight()));
        setLocationRelativeTo(owner);
    }

    void focusLinker() {
        setVisible(true);
        toFront();
        requestFocus();
    }

    private void buildUi() {
        setLayout(new BorderLayout(6, 6));
        add(buildTop(), BorderLayout.NORTH);
        add(imageArea, BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);
    }

    private JComponent buildTop() {
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.setBorder(BorderFactory.createEmptyBorder(6, 8, 2, 8));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        toolbar.add(new JLabel("Mode:"));
        toolbar.add(zProjMode);
        toolbar.add(zSync);
        toolbar.add(cSync);
        JButton preview = new JButton("Preview Settings");
        preview.addActionListener(e -> showPreviewSettings(preview));
        JButton resetView = new JButton("Fit");
        resetView.addActionListener(e -> resetView());
        toolbar.add(preview);
        toolbar.add(resetView);
        top.add(toolbar, BorderLayout.NORTH);

        selectionStatus.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        selectionStatus.setForeground(new Color(60, 60, 60));
        top.add(selectionStatus, BorderLayout.SOUTH);
        return top;
    }

    private JComponent buildBottom() {
        JPanel bottom = new JPanel(new BorderLayout(0, 2));
        JPanel axes = new JPanel(new GridBagLayout());
        axes.setBorder(BorderFactory.createTitledBorder("Axes"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(1, 4, 1, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        gc.gridx = 0;
        gc.gridy = 0;
        axes.add(tAxis.panel, gc);
        gc.gridy++;
        axes.add(twoAxisRow(leftCAxis, rightCAxis), gc);
        gc.gridy++;
        axes.add(twoAxisRow(leftZAxis, rightZAxis), gc);
        bottom.add(axes, BorderLayout.NORTH);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        JButton openSeedEdit = new JButton("Open Seed Edit");
        openSeedEdit.addActionListener(e -> status.setText("Open Seed Edit is not wired yet."));
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        actions.add(linkButton);
        actions.add(replaceButton);
        actions.add(unlinkPrevButton);
        actions.add(unlinkNextButton);
        actions.add(clearSelectionButton);
        actions.add(openSeedEdit);
        actions.add(saveButton);
        actions.add(close);
        bottom.add(actions, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));
        bottom.add(status, BorderLayout.SOUTH);
        return bottom;
    }

    private static JComponent twoAxisRow(AxisControl left, AxisControl right) {
        JPanel p = new JPanel(new GridLayout(1, 2, 8, 0));
        p.add(left.panel);
        p.add(right.panel);
        return p;
    }

    private static JPanel panelWithTitle(String title, JList<TrackEditor.ObjRef> list) {
        JPanel p = new JPanel(new BorderLayout(0, 2));
        p.setBorder(BorderFactory.createTitledBorder(title));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(4);
        list.setCellRenderer(new ObjRefRenderer());
        p.add(new JScrollPane(list), BorderLayout.CENTER);
        return p;
    }

    private static JPanel panelWithTitle(String title, JComponent component) {
        JPanel p = new JPanel(new BorderLayout(0, 2));
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.add(component, BorderLayout.CENTER);
        return p;
    }

    private void installListeners() {
        tAxis.slider.addChangeListener(e -> reloadPair());
        leftCAxis.slider.addChangeListener(e -> {
            if (cSync.isSelected()) rightCAxis.setValue(leftCAxis.value());
            updateImages();
        });
        rightCAxis.slider.addChangeListener(e -> updateImages());
        leftZAxis.slider.addChangeListener(e -> {
            if (zSync.isSelected()) rightZAxis.setValue(leftZAxis.value());
            updateImages();
        });
        rightZAxis.slider.addChangeListener(e -> updateImages());
        zProjMode.addActionListener(e -> updateModeControls());
        zSync.addActionListener(e -> updateModeControls());
        cSync.addActionListener(e -> updateModeControls());
        leftList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) selectionChanged(); });
        rightList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) selectionChanged(); });
        linkButton.addActionListener(e -> linkSelected(false));
        replaceButton.addActionListener(e -> linkSelected(true));
        unlinkPrevButton.addActionListener(e -> unlinkPrevious());
        unlinkNextButton.addActionListener(e -> unlinkNext());
        clearSelectionButton.addActionListener(e -> clearSelection());
        saveButton.addActionListener(e -> save());
        installShortcuts();
        updateModeControls();
    }

    private void reloadPair() {
        if (updatingControls) return;
        boolean timeChanged = currentT() != lastPairT;
        lastPairT = currentT();
        fill(leftModel, editor.objectsAt(tree, currentT()));
        fill(rightModel, editor.objectsAt(tree, currentT() + 1));
        if (timeChanged) clearSelectionOnly();
        updateImages();
        updateSelectionState();
    }

    private void updateImages() {
        if (updatingControls) return;
        boolean project = zProjMode.isSelected();
        leftPane.setFrame(currentT(), frameFor(leftCAxis.value(), leftZAxis.value(), currentT(), project),
            refsFromModel(leftModel), project ? 0 : leftZAxis.value());
        rightPane.setFrame(currentT() + 1, frameFor(rightCAxis.value(), rightZAxis.value(), currentT() + 1, project),
            refsFromModel(rightModel), project ? 0 : rightZAxis.value());
        repaintPanes();
    }

    private void updateModeControls() {
        updatingControls = true;
        if (cSync.isSelected()) rightCAxis.setValue(leftCAxis.value());
        if (zSync.isSelected()) rightZAxis.setValue(leftZAxis.value());
        boolean project = zProjMode.isSelected();
        zSync.setEnabled(!project);
        leftZAxis.setEnabled(!project);
        rightZAxis.setEnabled(!project && !zSync.isSelected());
        leftZAxis.setSuffix(project ? "projection mode" : "");
        rightZAxis.setSuffix(project ? "projection mode" : (zSync.isSelected() ? "mirrored" : ""));
        rightCAxis.setEnabled(!cSync.isSelected());
        rightCAxis.setSuffix(cSync.isSelected() ? "mirrored" : "");
        updatingControls = false;
        updateImages();
    }

    private void selectionChanged() {
        updateSelectionState();
        repaintPanes();
    }

    private void updateSelectionState() {
        TrackEditor.ObjRef left = leftList.getSelectedValue();
        TrackEditor.ObjRef right = rightList.getSelectedValue();
        boolean both = left != null && right != null;
        linkButton.setEnabled(both);
        replaceButton.setEnabled(both);
        unlinkPrevButton.setEnabled(right != null || left != null);
        unlinkNextButton.setEnabled(left != null || right != null);
        selectionStatus.setText("Selected: T" + currentT() + " "
            + (left != null ? objLabel(left) : "none")
            + "  ->  T" + (currentT() + 1) + " "
            + (right != null ? objLabel(right) : "none")
            + "    Drag left obj to right obj, or click both and Link.  Ctrl+wheel=zoom, drag empty image=pan.");
    }

    private void linkSelected(boolean forceReplace) {
        TrackEditor.ObjRef left = leftList.getSelectedValue();
        TrackEditor.ObjRef right = rightList.getSelectedValue();
        if (left == null || right == null) {
            status.setText("Select one object on both sides.");
            return;
        }
        if (!forceReplace && needsReplaceConfirmation(left, right)) {
            int choice = JOptionPane.showConfirmDialog(this,
                "This edit replaces an existing adjacent link. Replace it?",
                "Replace link", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) return;
        }
        editor.linkAfter(left, right);
        editor.pruneEmptyTracks(tree);
        rebuildTrajectoryCache();
        reloadPair();
        status.setText("Linked " + objLabel(left) + " -> " + objLabel(right) + ". Save Tracking to persist.");
    }

    private boolean needsReplaceConfirmation(TrackEditor.ObjRef left, TrackEditor.ObjRef right) {
        TrackEditor.ObjRef existingNext = editor.nextObject(tree, left);
        TrackEditor.ObjRef existingPrev = editor.previousObject(tree, right);
        return existingNext != null && existingNext.obj != right.obj
            || existingPrev != null && existingPrev.obj != left.obj;
    }

    private void unlinkPrevious() {
        TrackEditor.ObjRef ref = rightList.getSelectedValue();
        if (ref == null) ref = leftList.getSelectedValue();
        if (ref == null) {
            status.setText("Select an object to unlink from its previous object.");
            return;
        }
        editor.unlinkToNewTrack(tree, ref);
        editor.pruneEmptyTracks(tree);
        rebuildTrajectoryCache();
        reloadPair();
        status.setText("Unlinked previous connection for " + objLabel(ref) + ".");
    }

    private void unlinkNext() {
        TrackEditor.ObjRef ref = leftList.getSelectedValue();
        if (ref == null) ref = rightList.getSelectedValue();
        TrackEditor.ObjRef next = editor.nextObject(tree, ref);
        if (next == null) {
            status.setText("No next object is linked to the selected object.");
            return;
        }
        editor.unlinkToNewTrack(tree, next);
        editor.pruneEmptyTracks(tree);
        rebuildTrajectoryCache();
        reloadPair();
        status.setText("Unlinked next connection for " + objLabel(ref) + ".");
    }

    private void clearSelection() {
        clearSelectionOnly();
        updateSelectionState();
        repaintPanes();
    }

    private void clearSelectionOnly() {
        leftList.clearSelection();
        rightList.clearSelection();
        leftPane.hoverRef = null;
        rightPane.hoverRef = null;
        lastSelectedSide = null;
    }

    private void save() {
        TrackValidator.Result result = validator.validate(tree);
        if (!result.isValid()) {
            status.setText("Cannot save: " + result.getErrors().get(0));
            JOptionPane.showMessageDialog(this, String.join("\n", result.getErrors()),
                "Tracking validation", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            io.write(tracksRoot, tree);
            tree = io.read(tracksRoot);
            rebuildTrajectoryCache();
            frameCache.clear();
            reloadPair();
            if (onSaved != null) onSaved.run();
            status.setText("Saved Tracking: " + tracksRoot);
        } catch (IOException e) {
            status.setText("Save failed: " + e.getMessage());
        }
    }

    private void showPreviewSettings(Component parent) {
        JPopupMenu menu = new JPopupMenu();
        for (JCheckBox cb : new JCheckBox[]{showRois, showCentroids, showTrajectories, showLabels, showAllLinks}) {
            cb.addActionListener(e -> repaintPanes());
            menu.add(cb);
        }
        menu.show(parent, 0, parent.getHeight());
    }

    private void resetView() {
        viewScale = 0.0;
        viewOffsetX = 0.0;
        viewOffsetY = 0.0;
        repaintPanes();
    }

    private void repaintPanes() {
        leftPane.repaint();
        rightPane.repaint();
        imageArea.repaint();
    }

    private int currentT() {
        return tAxis.value();
    }

    private void bumpTime(int delta) {
        int value = currentT() + delta;
        if (value >= 1 && value <= Math.max(1, maxT - 1)) tAxis.setValue(value);
    }

    private void installShortcuts() {
        JRootPane root = getRootPane();
        bind(root, "link", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), e -> linkSelected(false));
        bind(root, "unlinkNext", KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), e -> unlinkNext());
        bind(root, "save", KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), e -> save());
        bind(root, "prevT", KeyStroke.getKeyStroke(KeyEvent.VK_A, 0), e -> bumpTime(-1));
        bind(root, "nextT", KeyStroke.getKeyStroke(KeyEvent.VK_D, 0), e -> bumpTime(1));
        bind(root, "clear", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), e -> clearSelection());
        bind(root, "zoomIn", KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, 0), e -> leftPane.zoomAtCenter(1.18));
        bind(root, "zoomInPlus", KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, 0), e -> leftPane.zoomAtCenter(1.18));
        bind(root, "zoomOut", KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, 0), e -> leftPane.zoomAtCenter(1.0 / 1.18));
    }

    private static void bind(JComponent c, String name, KeyStroke key, java.util.function.Consumer<ActionEvent> action) {
        c.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                action.accept(e);
            }
        });
    }

    private BufferedImage frameFor(int c, int z, int t, boolean project) {
        String key = (project ? "p" : "z") + ":c" + c + ":z" + z + ":t" + t;
        BufferedImage cached = frameCache.get(key);
        if (cached != null) return cached;
        BufferedImage created = project ? createMaxProjection(image, c, t) : createSliceImage(image, c, z, t);
        frameCache.put(key, created);
        return created;
    }

    private void rebuildTrajectoryCache() {
        trajectories.clear();
        if (tree == null) return;
        for (TrackTree.TrackNode track : tree.getTracks()) collectTrajectory(track);
    }

    private void collectTrajectory(TrackTree.TrackNode track) {
        java.util.List<TrajectoryPoint> points = new java.util.ArrayList<TrajectoryPoint>();
        collectTrajectoryPoints(track, points);
        java.util.Collections.sort(points, new java.util.Comparator<TrajectoryPoint>() {
            @Override public int compare(TrajectoryPoint a, TrajectoryPoint b) {
                return Integer.compare(a.t, b.t);
            }
        });
        if (points.size() >= 2) trajectories.add(new Trajectory(points));
        for (TrackTree.Entry child : track.getChildren()) {
            if (child instanceof TrackTree.TrackNode) collectTrajectory((TrackTree.TrackNode) child);
        }
    }

    private void collectTrajectoryPoints(TrackTree.TrackNode track, java.util.List<TrajectoryPoint> points) {
        for (TrackTree.Entry child : track.getChildren()) {
            if (child instanceof TrackTree.ObjNode) {
                TrackTree.ObjNode obj = (TrackTree.ObjNode) child;
                points.add(new TrajectoryPoint(obj.firstT(), obj.centerX(), obj.centerY()));
            } else if (child instanceof TrackTree.TrackNode) {
                collectTrajectoryPoints((TrackTree.TrackNode) child, points);
            }
        }
    }

    @Override public void dispose() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(dragReleaseListener);
        super.dispose();
    }

    private void handleGlobalMouseRelease(AWTEvent event) {
        if (!(event instanceof MouseEvent) || ((MouseEvent) event).getID() != MouseEvent.MOUSE_RELEASED) return;
        if (draggingLeftRef == null) return;
        MouseEvent mouse = (MouseEvent) event;
        Point p = SwingUtilities.convertPoint((Component) mouse.getSource(), mouse.getPoint(), rightPane);
        TrackEditor.ObjRef right = rightPane.findAt(p.x, p.y);
        if (right != null) {
            leftList.setSelectedValue(draggingLeftRef, true);
            rightList.setSelectedValue(right, true);
            linkSelected(false);
        }
        draggingLeftRef = null;
        repaintPanes();
    }

    private void objectClicked(Side side, TrackEditor.ObjRef selected) {
        if (selected == null) return;
        if (side == Side.LEFT) {
            if (lastSelectedSide == Side.RIGHT && rightList.getSelectedValue() != null) {
                leftList.setSelectedValue(selected, true);
                linkSelected(false);
            } else {
                leftList.setSelectedValue(selected, true);
                rightList.clearSelection();
                lastSelectedSide = Side.LEFT;
            }
        } else {
            if (lastSelectedSide == Side.LEFT && leftList.getSelectedValue() != null) {
                rightList.setSelectedValue(selected, true);
                linkSelected(false);
            } else {
                rightList.setSelectedValue(selected, true);
                leftList.clearSelection();
                lastSelectedSide = Side.RIGHT;
            }
        }
        updateSelectionState();
        repaintPanes();
    }

    private static void fill(DefaultListModel<TrackEditor.ObjRef> model, List<TrackEditor.ObjRef> refs) {
        model.clear();
        for (TrackEditor.ObjRef ref : refs) model.addElement(ref);
    }

    private static java.util.List<TrackEditor.ObjRef> refsFromModel(DefaultListModel<TrackEditor.ObjRef> model) {
        java.util.List<TrackEditor.ObjRef> refs = new java.util.ArrayList<TrackEditor.ObjRef>();
        for (int i = 0; i < model.getSize(); i++) refs.add(model.getElementAt(i));
        return refs;
    }

    private static TrackTree.ObjNode selectedObj(JList<TrackEditor.ObjRef> list) {
        TrackEditor.ObjRef ref = list.getSelectedValue();
        return ref != null ? ref.obj : null;
    }

    private static void selectObject(JList<TrackEditor.ObjRef> list, DefaultListModel<TrackEditor.ObjRef> model,
                                     TrackTree.ObjNode obj) {
        if (obj == null) return;
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).obj == obj) {
                list.setSelectedIndex(i);
                list.ensureIndexIsVisible(i);
                return;
            }
        }
    }

    private static String objLabel(TrackEditor.ObjRef ref) {
        return ref.obj.getGlobalId() + " / " + ref.obj.getSourceObjId()
            + " [track " + ref.parent.getGlobalId() + "]";
    }

    private static BufferedImage createMaxProjection(ImagePlus image, int c, int t) {
        int w = image != null ? image.getWidth() : 1;
        int h = image != null ? image.getHeight() : 1;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        if (image == null) return out;
        int safeC = Math.max(1, Math.min(c, Math.max(1, image.getNChannels())));
        int safeT = Math.max(1, Math.min(t, Math.max(1, image.getNFrames())));
        int slices = Math.max(1, image.getNSlices());
        float[] max = new float[w * h];
        java.util.Arrays.fill(max, -Float.MAX_VALUE);
        float min = Float.MAX_VALUE;
        float maxVal = -Float.MAX_VALUE;
        for (int z = 1; z <= slices; z++) {
            ImageProcessor ip = image.getStack().getProcessor(image.getStackIndex(safeC, z, safeT));
            for (int y = 0; y < h; y++) {
                int offset = y * w;
                for (int x = 0; x < w; x++) {
                    int idx = offset + x;
                    float v = ip.getf(x, y);
                    if (v > max[idx]) max[idx] = v;
                }
            }
        }
        for (float v : max) {
            if (v < min) min = v;
            if (v > maxVal) maxVal = v;
        }
        double scale = maxVal > min ? 255.0 / (maxVal - min) : 1.0;
        java.awt.image.WritableRaster raster = out.getRaster();
        for (int y = 0; y < h; y++) {
            int offset = y * w;
            for (int x = 0; x < w; x++) {
                int gray = (int) Math.round((max[offset + x] - min) * scale);
                raster.setSample(x, y, 0, clamp(gray));
            }
        }
        return out;
    }

    private static BufferedImage createSliceImage(ImagePlus image, int c, int z, int t) {
        int w = image != null ? image.getWidth() : 1;
        int h = image != null ? image.getHeight() : 1;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        if (image == null) return out;
        int safeC = Math.max(1, Math.min(c, Math.max(1, image.getNChannels())));
        int safeZ = Math.max(1, Math.min(z, Math.max(1, image.getNSlices())));
        int safeT = Math.max(1, Math.min(t, Math.max(1, image.getNFrames())));
        ImageProcessor ip = image.getStack().getProcessor(image.getStackIndex(safeC, safeZ, safeT));
        double min = ip.getMin();
        double max = ip.getMax();
        double scale = max > min ? 255.0 / (max - min) : 1.0;
        java.awt.image.WritableRaster raster = out.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int gray = (int) Math.round((ip.getf(x, y) - min) * scale);
                raster.setSample(x, y, 0, clamp(gray));
            }
        }
        return out;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private final class LinkerImageArea extends JPanel {
        LinkerImageArea() {
            super(new GridLayout(1, 2, 8, 0));
            add(panelWithTitle("Left: T", leftPane));
            add(panelWithTitle("Right: T+1", rightPane));
        }

        @Override protected void paintChildren(Graphics g) {
            super.paintChildren(g);
        }

        private void paintSelectedInterPaneLink(Graphics2D g2) {
            TrackEditor.ObjRef left = leftList.getSelectedValue();
            TrackEditor.ObjRef right = rightList.getSelectedValue();
            if (left == null || right == null) return;
            Point a = convertPoint(leftPane, leftPane.centroidPoint(left));
            Point b = convertPoint(rightPane, rightPane.centroidPoint(right));
            paintLink(g2, a, b, new Color(255, 128, 0, 235), 2.6f);
        }

        private void paintAllCurrentLinks(Graphics2D g2) {
            java.util.Map<TrackTree.ObjNode, TrackEditor.ObjRef> rights = new java.util.IdentityHashMap<TrackTree.ObjNode, TrackEditor.ObjRef>();
            for (int i = 0; i < rightModel.size(); i++) rights.put(rightModel.get(i).obj, rightModel.get(i));
            for (int i = 0; i < leftModel.size(); i++) {
                TrackEditor.ObjRef left = leftModel.get(i);
                TrackEditor.ObjRef next = editor.nextObject(tree, left);
                if (next == null) continue;
                TrackEditor.ObjRef right = rights.get(next.obj);
                if (right == null) continue;
                Point a = convertPoint(leftPane, leftPane.centroidPoint(left));
                Point b = convertPoint(rightPane, rightPane.centroidPoint(right));
                paintLink(g2, a, b, new Color(0, 210, 255, 145), 1.4f);
            }
        }

        private Point convertPoint(Component source, Point p) {
            return SwingUtilities.convertPoint(source, p, this);
        }

        private void paintLink(Graphics2D g2, Point a, Point b, Color color, float width) {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(width));
            g2.drawLine(a.x, a.y, b.x, b.y);
            double angle = Math.atan2(b.y - a.y, b.x - a.x);
            int len = 9;
            int x1 = b.x - (int) Math.round(Math.cos(angle - Math.PI / 7.0) * len);
            int y1 = b.y - (int) Math.round(Math.sin(angle - Math.PI / 7.0) * len);
            int x2 = b.x - (int) Math.round(Math.cos(angle + Math.PI / 7.0) * len);
            int y2 = b.y - (int) Math.round(Math.sin(angle + Math.PI / 7.0) * len);
            g2.drawLine(b.x, b.y, x1, y1);
            g2.drawLine(b.x, b.y, x2, y2);
        }
    }

    private final class LinkerImagePane extends JPanel {
        private final boolean left;
        private int time;
        private int zFilter;
        private BufferedImage frame;
        private java.util.List<TrackEditor.ObjRef> refs = java.util.Collections.emptyList();
        private TrackEditor.ObjRef hoverRef;
        private Point panStart;
        private double panStartX;
        private double panStartY;

        LinkerImagePane(boolean left) {
            this.left = left;
            setPreferredSize(new Dimension(520, 420));
            setBackground(Color.BLACK);
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    TrackEditor.ObjRef selected = findAt(e.getX(), e.getY());
                    if (left && selected != null) draggingLeftRef = selected;
                    if (selected == null || SwingUtilities.isRightMouseButton(e) || e.isAltDown()) {
                        panStart = e.getPoint();
                        panStartX = viewOffsetX;
                        panStartY = viewOffsetY;
                    }
                }

                @Override public void mouseReleased(MouseEvent e) {
                    panStart = null;
                }

                @Override public void mouseClicked(MouseEvent e) {
                    objectClicked(left ? Side.LEFT : Side.RIGHT, findAt(e.getX(), e.getY()));
                }

                @Override public void mouseExited(MouseEvent e) {
                    hoverRef = null;
                    repaint();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    hoverRef = findAt(e.getX(), e.getY());
                    if (hoverRef != null) status.setText("Hover: " + objLabel(hoverRef));
                    repaint();
                }

                @Override public void mouseDragged(MouseEvent e) {
                    if (panStart != null) {
                        viewOffsetX = panStartX + e.getX() - panStart.x;
                        viewOffsetY = panStartY + e.getY() - panStart.y;
                        repaintPanes();
                    }
                }
            });
            addMouseWheelListener(this::wheelMoved);
        }

        void setFrame(int time, BufferedImage frame, java.util.List<TrackEditor.ObjRef> refs, int zFilter) {
            this.time = time;
            this.frame = frame;
            this.refs = refs != null ? refs : java.util.Collections.<TrackEditor.ObjRef>emptyList();
            this.zFilter = zFilter;
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Rectangle draw = imageRect();
                if (frame != null) g2.drawImage(frame, draw.x, draw.y, draw.width, draw.height, null);
                paintTrajectories(g2, draw);
                paintObjects(g2, draw);
                paintPaneLocalLinks(g2);
                paintOverlayText(g2);
            } finally {
                g2.dispose();
            }
        }

        private void paintOverlayText(Graphics2D g2) {
            g2.setColor(new Color(0, 0, 0, 150));
            g2.fillRoundRect(6, 6, 120, 24, 8, 8);
            g2.setColor(Color.WHITE);
            g2.drawString((left ? "Left T" : "Right T") + time, 14, 23);
        }

        private void paintObjects(Graphics2D g2, Rectangle draw) {
            TrackEditor.ObjRef selected = left ? leftList.getSelectedValue() : rightList.getSelectedValue();
            TrackEditor.ObjRef peer = left ? rightList.getSelectedValue() : leftList.getSelectedValue();
            for (TrackEditor.ObjRef ref : refs) {
                boolean hovered = ref == hoverRef;
                boolean sameTrack = peer != null && peer.parent == ref.parent;
                boolean selectedObj = ref == selected;
                Color color = selectedObj ? Color.YELLOW
                    : hovered ? Color.ORANGE
                    : sameTrack ? new Color(0, 230, 120, 220)
                    : new Color(220, 220, 220, 150);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(selectedObj || hovered ? 2.6f : 1.2f));
                if (showRois.isSelected()) {
                    for (Roi roi : ref.obj.getRois()) {
                        if (zFilter > 0 && !roiMatchesZ(roi, zFilter)) continue;
                        drawRoi(g2, draw, roi);
                    }
                }
                Point p = toView(draw, ref.obj.centerX(), ref.obj.centerY());
                if (showCentroids.isSelected()) {
                    g2.fillOval(p.x - 4, p.y - 4, 8, 8);
                }
                if (showLabels.isSelected()) {
                    g2.drawString(ref.obj.getGlobalId(), p.x + 6, p.y - 6);
                }
            }
        }

        private void paintTrajectories(Graphics2D g2, Rectangle draw) {
            if (!showTrajectories.isSelected()) return;
            g2.setColor(new Color(0, 190, 255, 135));
            g2.setStroke(new BasicStroke(1.5f));
            for (Trajectory trajectory : trajectories) {
                for (int i = 1; i < trajectory.points.size(); i++) {
                    TrajectoryPoint a = trajectory.points.get(i - 1);
                    TrajectoryPoint b = trajectory.points.get(i);
                    Point p1 = toView(draw, a.x, a.y);
                    Point p2 = toView(draw, b.x, b.y);
                    g2.drawLine(p1.x, p1.y, p2.x, p2.y);
                }
            }
        }

        private void paintPaneLocalLinks(Graphics2D g2) {
            if (showAllLinks.isSelected()) paintAllCurrentLinksInPane(g2);
            paintSelectedLinkInPane(g2);
        }

        private void paintSelectedLinkInPane(Graphics2D g2) {
            TrackEditor.ObjRef l = leftList.getSelectedValue();
            TrackEditor.ObjRef r = rightList.getSelectedValue();
            if (l == null || r == null) return;
            Point a = left ? centroidPoint(l) : SwingUtilities.convertPoint(leftPane, leftPane.centroidPoint(l), this);
            Point b = left ? SwingUtilities.convertPoint(rightPane, rightPane.centroidPoint(r), this) : centroidPoint(r);
            paintLinkSegment(g2, a, b, new Color(255, 128, 0, 235), 2.6f);
        }

        private void paintAllCurrentLinksInPane(Graphics2D g2) {
            java.util.Map<TrackTree.ObjNode, TrackEditor.ObjRef> rights = new java.util.IdentityHashMap<TrackTree.ObjNode, TrackEditor.ObjRef>();
            for (int i = 0; i < rightModel.size(); i++) rights.put(rightModel.get(i).obj, rightModel.get(i));
            for (int i = 0; i < leftModel.size(); i++) {
                TrackEditor.ObjRef l = leftModel.get(i);
                TrackEditor.ObjRef next = editor.nextObject(tree, l);
                if (next == null) continue;
                TrackEditor.ObjRef r = rights.get(next.obj);
                if (r == null) continue;
                Point a = left ? centroidPoint(l) : SwingUtilities.convertPoint(leftPane, leftPane.centroidPoint(l), this);
                Point b = left ? SwingUtilities.convertPoint(rightPane, rightPane.centroidPoint(r), this) : centroidPoint(r);
                paintLinkSegment(g2, a, b, new Color(0, 210, 255, 145), 1.4f);
            }
        }

        private void paintLinkSegment(Graphics2D g2, Point a, Point b, Color color, float width) {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(width));
            g2.drawLine(a.x, a.y, b.x, b.y);
            if (left) return;
            double angle = Math.atan2(b.y - a.y, b.x - a.x);
            int len = 9;
            int x1 = b.x - (int) Math.round(Math.cos(angle - Math.PI / 7.0) * len);
            int y1 = b.y - (int) Math.round(Math.sin(angle - Math.PI / 7.0) * len);
            int x2 = b.x - (int) Math.round(Math.cos(angle + Math.PI / 7.0) * len);
            int y2 = b.y - (int) Math.round(Math.sin(angle + Math.PI / 7.0) * len);
            g2.drawLine(b.x, b.y, x1, y1);
            g2.drawLine(b.x, b.y, x2, y2);
        }

        private void drawRoi(Graphics2D g2, Rectangle draw, Roi roi) {
            Polygon poly = roi.getPolygon();
            if (poly == null || poly.npoints == 0) {
                Rectangle b = roi.getBounds();
                Point p1 = toView(draw, b.x, b.y);
                Point p2 = toView(draw, b.x + b.width, b.y + b.height);
                g2.drawRect(p1.x, p1.y, p2.x - p1.x, p2.y - p1.y);
                return;
            }
            int[] xs = new int[poly.npoints];
            int[] ys = new int[poly.npoints];
            for (int i = 0; i < poly.npoints; i++) {
                Point p = toView(draw, poly.xpoints[i], poly.ypoints[i]);
                xs[i] = p.x;
                ys[i] = p.y;
            }
            g2.drawPolygon(xs, ys, poly.npoints);
        }

        private TrackEditor.ObjRef findAt(int x, int y) {
            Rectangle draw = imageRect();
            if (frame == null || !draw.contains(x, y)) return null;
            double ix = (x - draw.x) / scale();
            double iy = (y - draw.y) / scale();
            TrackEditor.ObjRef centroidHit = nearestCentroid(draw, x, y);
            if (centroidHit != null) return centroidHit;
            TrackEditor.ObjRef best = null;
            double bestArea = Double.POSITIVE_INFINITY;
            for (TrackEditor.ObjRef ref : refs) {
                for (Roi roi : ref.obj.getRois()) {
                    if (zFilter > 0 && !roiMatchesZ(roi, zFilter)) continue;
                    if (!roi.contains((int) Math.round(ix), (int) Math.round(iy))) continue;
                    Rectangle b = roi.getBounds();
                    double area = b.getWidth() * b.getHeight();
                    if (area < bestArea) {
                        bestArea = area;
                        best = ref;
                    }
                }
            }
            return best;
        }

        private TrackEditor.ObjRef nearestCentroid(Rectangle draw, int x, int y) {
            TrackEditor.ObjRef best = null;
            double bestDist = 144.0;
            for (TrackEditor.ObjRef ref : refs) {
                Point p = toView(draw, ref.obj.centerX(), ref.obj.centerY());
                double dx = p.x - x;
                double dy = p.y - y;
                double d = dx * dx + dy * dy;
                if (d < bestDist) {
                    bestDist = d;
                    best = ref;
                }
            }
            return best;
        }

        private void wheelMoved(MouseWheelEvent e) {
            if (e.isControlDown()) {
                zoomAt(e.getPoint(), e.getWheelRotation() < 0 ? 1.18 : 1.0 / 1.18);
            } else if (e.isShiftDown() && !zProjMode.isSelected()) {
                (left ? leftZAxis : rightZAxis).bump(-e.getWheelRotation());
            } else {
                bumpTime(e.getWheelRotation() > 0 ? 1 : -1);
            }
        }

        private void zoomAt(Point p, double factor) {
            Rectangle before = imageRect();
            double oldScale = scale();
            double imageX = (p.x - before.x) / oldScale;
            double imageY = (p.y - before.y) / oldScale;
            viewScale = Math.max(fitScale() * 0.5, Math.min(oldScale * factor, fitScale() * 20.0));
            viewOffsetX = p.x - imageX * viewScale;
            viewOffsetY = p.y - imageY * viewScale;
            repaintPanes();
        }

        private void zoomAtCenter(double factor) {
            zoomAt(new Point(getWidth() / 2, getHeight() / 2), factor);
        }

        private Point centroidPoint(TrackEditor.ObjRef ref) {
            return toView(imageRect(), ref.obj.centerX(), ref.obj.centerY());
        }

        private Rectangle imageRect() {
            if (frame == null) return new Rectangle(0, 0, getWidth(), getHeight());
            double s = scale();
            int w = Math.max(1, (int) Math.round(frame.getWidth() * s));
            int h = Math.max(1, (int) Math.round(frame.getHeight() * s));
            if (viewScale <= 0.0) {
                viewOffsetX = (getWidth() - w) / 2.0;
                viewOffsetY = (getHeight() - h) / 2.0;
            }
            return new Rectangle((int) Math.round(viewOffsetX), (int) Math.round(viewOffsetY), w, h);
        }

        private double scale() {
            return viewScale > 0.0 ? viewScale : fitScale();
        }

        private double fitScale() {
            if (frame == null) return 1.0;
            return Math.min(getWidth() / (double) frame.getWidth(), getHeight() / (double) frame.getHeight());
        }

        private Point toView(Rectangle draw, double x, double y) {
            double s = scale();
            return new Point(draw.x + (int) Math.round(x * s), draw.y + (int) Math.round(y * s));
        }

        private boolean roiMatchesZ(Roi roi, int z) {
            int rz = roi.getZPosition();
            if (rz <= 0) rz = roi.getPosition();
            return rz <= 0 || rz == z;
        }
    }

    private static final class AxisControl {
        final JPanel panel = new JPanel(new BorderLayout(6, 0));
        final JLabel nameLabel = new JLabel();
        final JSlider slider;
        final JLabel valueLabel = new JLabel();
        private String suffix = "";

        AxisControl(String name, int min, int max, int value) {
            int safeMax = Math.max(min, max);
            slider = new JSlider(min, safeMax, Math.max(min, Math.min(value, safeMax)));
            slider.setPaintTicks(false);
            nameLabel.setText(name + ":");
            nameLabel.setPreferredSize(new Dimension(54, nameLabel.getPreferredSize().height));
            valueLabel.setPreferredSize(new Dimension(118, valueLabel.getPreferredSize().height));
            panel.add(nameLabel, BorderLayout.WEST);
            panel.add(slider, BorderLayout.CENTER);
            panel.add(valueLabel, BorderLayout.EAST);
            slider.addChangeListener(e -> refreshLabel());
            refreshLabel();
        }

        int value() {
            return slider.getValue();
        }

        void setValue(int value) {
            slider.setValue(Math.max(slider.getMinimum(), Math.min(value, slider.getMaximum())));
        }

        void bump(int delta) {
            setValue(value() + delta);
        }

        void setEnabled(boolean enabled) {
            slider.setEnabled(enabled);
            nameLabel.setEnabled(enabled);
            valueLabel.setEnabled(enabled);
        }

        void setSuffix(String suffix) {
            this.suffix = suffix != null ? suffix : "";
            refreshLabel();
        }

        private void refreshLabel() {
            String range = value() + " / " + slider.getMaximum();
            valueLabel.setText(suffix.isEmpty() ? range : range + "  " + suffix);
        }
    }

    private static final class ObjRefRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                               boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof TrackEditor.ObjRef) {
                TrackEditor.ObjRef ref = (TrackEditor.ObjRef) value;
                label.setText(ref.obj.getGlobalId() + "  " + ref.obj.getSourceObjId()
                    + "    track " + ref.parent.getGlobalId());
            }
            return label;
        }
    }

    private static final class Trajectory {
        final java.util.List<TrajectoryPoint> points;
        Trajectory(java.util.List<TrajectoryPoint> points) {
            this.points = points;
        }
    }

    private static final class TrajectoryPoint {
        final int t;
        final double x;
        final double y;
        TrajectoryPoint(int t, double x, double y) {
            this.t = t;
            this.x = x;
            this.y = y;
        }
    }

    private enum Side {
        LEFT, RIGHT
    }
}
