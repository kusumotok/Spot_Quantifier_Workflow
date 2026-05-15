package io.github.kusumotok.spotworkflow;

import ij.ImagePlus;
import ij.gui.OvalRoi;
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

    private final JSpinner timeSpinner;
    private final DefaultListModel<TrackEditor.ObjRef> leftModel = new DefaultListModel<TrackEditor.ObjRef>();
    private final DefaultListModel<TrackEditor.ObjRef> rightModel = new DefaultListModel<TrackEditor.ObjRef>();
    private final JList<TrackEditor.ObjRef> leftList = new JList<TrackEditor.ObjRef>(leftModel);
    private final JList<TrackEditor.ObjRef> rightList = new JList<TrackEditor.ObjRef>(rightModel);
    private final LinkerImagePane leftPane = new LinkerImagePane(true);
    private final LinkerImagePane rightPane = new LinkerImagePane(false);
    private final JCheckBox zProjMode = new JCheckBox("Z-proj", true);
    private final JCheckBox zSync = new JCheckBox("Z sync", true);
    private final JCheckBox cSync = new JCheckBox("C sync", true);
    private final JSpinner leftZSpinner;
    private final JSpinner rightZSpinner;
    private final JSpinner leftCSpinner;
    private final JSpinner rightCSpinner;
    private final JLabel status = new JLabel(" ");
    private final JLabel selectionStatus = new JLabel("Select objects on T and T+1, then press Link. Double-click a right object to link.");
    private final JButton linkButton = new JButton("Link / Replace");
    private final JButton unlinkButton = new JButton("Unlink right");
    private final JButton saveButton = new JButton("Save Tracking");
    private final java.awt.event.AWTEventListener dragReleaseListener = this::handleGlobalMouseRelease;
    private TrackTree tree;
    private boolean renderingOverlay;
    private TrackEditor.ObjRef draggingLeftRef;
    private final java.util.Map<TrackTree.ObjNode, java.util.List<Roi>> roiOverlayCache =
        new java.util.IdentityHashMap<TrackTree.ObjNode, java.util.List<Roi>>();
    private final java.util.Map<TrackTree.ObjNode, OvalRoi> centroidOverlayCache =
        new java.util.IdentityHashMap<TrackTree.ObjNode, OvalRoi>();
    private final java.util.Map<String, BufferedImage> frameCache =
        new java.util.HashMap<String, BufferedImage>();
    private final java.util.List<Trajectory> trajectories = new java.util.ArrayList<Trajectory>();

    TimeSeriesTrackLinkerDialog(Window owner, ImagePlus image, Path tracksRoot, Runnable onSaved) throws IOException {
        super(owner, "Track Linker", ModalityType.MODELESS);
        this.image = image;
        this.tracksRoot = tracksRoot;
        this.onSaved = onSaved;
        this.tree = io.read(tracksRoot);
        rebuildOverlayCache();
        rebuildTrajectoryCache();
        int maxT = image != null ? Math.max(1, image.getNFrames() - 1) : 1;
        int maxZ = image != null ? Math.max(1, image.getNSlices()) : 1;
        int maxC = image != null ? Math.max(1, image.getNChannels()) : 1;
        timeSpinner = new JSpinner(new SpinnerNumberModel(1, 1, Math.max(1, maxT), 1));
        leftZSpinner = new JSpinner(new SpinnerNumberModel(1, 1, maxZ, 1));
        rightZSpinner = new JSpinner(new SpinnerNumberModel(1, 1, maxZ, 1));
        leftCSpinner = new JSpinner(new SpinnerNumberModel(image != null ? Math.max(1, image.getC()) : 1, 1, maxC, 1));
        rightCSpinner = new JSpinner(new SpinnerNumberModel(image != null ? Math.max(1, image.getC()) : 1, 1, maxC, 1));
        buildUi();
        installListeners();
        Toolkit.getDefaultToolkit().addAWTEventListener(dragReleaseListener, AWTEvent.MOUSE_EVENT_MASK);
        reloadLists();
        pack();
        setSize(Math.max(980, getWidth()), Math.max(680, getHeight()));
        setLocationRelativeTo(owner);
        renderOverlay();
    }

    private void buildUi() {
        setLayout(new BorderLayout(6, 6));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JPanel mainControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        mainControls.add(new JLabel("T:"));
        mainControls.add(timeSpinner);
        mainControls.add(new JLabel("left=T, right=T+1"));
        mainControls.add(zProjMode);
        mainControls.add(zSync);
        mainControls.add(cSync);
        mainControls.add(new JLabel("C L/R:"));
        mainControls.add(leftCSpinner);
        mainControls.add(rightCSpinner);
        mainControls.add(new JLabel("Z L/R:"));
        mainControls.add(leftZSpinner);
        mainControls.add(rightZSpinner);
        JPanel helpRow = new JPanel(new BorderLayout(4, 0));
        selectionStatus.setForeground(new Color(70, 70, 70));
        helpRow.setBorder(BorderFactory.createEmptyBorder(0, 8, 2, 8));
        helpRow.add(selectionStatus, BorderLayout.CENTER);
        top.add(mainControls);
        top.add(helpRow);
        add(top, BorderLayout.NORTH);

        JPanel imageViews = new JPanel(new GridLayout(1, 2, 6, 0));
        imageViews.add(panelWithTitle("T image", leftPane));
        imageViews.add(panelWithTitle("T+1 image", rightPane));

        JPanel lists = new JPanel(new GridLayout(1, 2, 6, 0));
        lists.add(panelWithTitle("T", leftList));
        lists.add(panelWithTitle("T+1", rightList));
        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, imageViews, lists);
        center.setResizeWeight(0.86);
        center.setDividerLocation(520);
        add(center, BorderLayout.CENTER);

        JButton close = new JButton("Close");
        linkButton.addActionListener(e -> linkSelected());
        unlinkButton.addActionListener(e -> unlinkSelected());
        saveButton.addActionListener(e -> save());
        close.addActionListener(e -> dispose());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        actions.add(linkButton);
        actions.add(unlinkButton);
        actions.add(saveButton);
        actions.add(close);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(actions, BorderLayout.NORTH);
        bottom.add(status, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
    }

    private static JPanel panelWithTitle(String title, JList<TrackEditor.ObjRef> list) {
        JPanel p = new JPanel(new BorderLayout(0, 2));
        p.setBorder(BorderFactory.createTitledBorder(title));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(5);
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
        timeSpinner.addChangeListener(e -> reloadLists());
        zProjMode.addActionListener(e -> updateModeControlsAndImages());
        zSync.addActionListener(e -> syncRightZFromLeft());
        cSync.addActionListener(e -> syncRightCFromLeft());
        leftZSpinner.addChangeListener(e -> {
            if (zSync.isSelected()) syncRightZFromLeft();
            updateImagePanes();
        });
        rightZSpinner.addChangeListener(e -> updateImagePanes());
        leftCSpinner.addChangeListener(e -> {
            if (cSync.isSelected()) syncRightCFromLeft();
            updateImagePanes();
        });
        rightCSpinner.addChangeListener(e -> updateImagePanes());
        leftList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) selectionChanged(); });
        rightList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) selectionChanged(); });
        installShortcuts();
        updateModeControlsAndImages();
    }

    private void reloadLists() {
        int t = currentT();
        TrackTree.ObjNode leftSelected = leftList.getSelectedValue() != null ? leftList.getSelectedValue().obj : null;
        TrackTree.ObjNode rightSelected = rightList.getSelectedValue() != null ? rightList.getSelectedValue().obj : null;
        renderingOverlay = true;
        fill(leftModel, editor.objectsAt(tree, t));
        fill(rightModel, editor.objectsAt(tree, t + 1));
        selectObject(leftList, leftModel, leftSelected);
        selectObject(rightList, rightModel, rightSelected);
        renderingOverlay = false;
        status.setText("Editing links for T" + t + " -> T" + (t + 1));
        updateSelectionStatus();
        updateImagePanes();
        renderOverlay();
    }

    private static void fill(DefaultListModel<TrackEditor.ObjRef> model, List<TrackEditor.ObjRef> refs) {
        model.clear();
        for (TrackEditor.ObjRef ref : refs) model.addElement(ref);
    }

    private int currentT() {
        return ((Integer) timeSpinner.getValue()).intValue();
    }

    private void linkSelected() {
        TrackEditor.ObjRef left = leftList.getSelectedValue();
        TrackEditor.ObjRef right = rightList.getSelectedValue();
        if (left == null || right == null) {
            status.setText("Select one object on both sides.");
            return;
        }
        editor.linkAfter(left, right);
        editor.pruneEmptyTracks(tree);
        rebuildTrajectoryCache();
        reloadLists();
        status.setText("Linked " + right.obj.getSourceObjId() + " after " + left.obj.getSourceObjId() + ". Save Tracking to persist.");
    }

    private void unlinkSelected() {
        TrackEditor.ObjRef right = rightList.getSelectedValue();
        if (right == null) {
            status.setText("Select a right-side object to unlink.");
            return;
        }
        editor.unlinkToNewTrack(tree, right);
        editor.pruneEmptyTracks(tree);
        rebuildTrajectoryCache();
        reloadLists();
        status.setText("Unlinked " + right.obj.getSourceObjId() + " to a new track. Save Tracking to persist.");
    }

    private void save() {
        TrackValidator.Result result = validator.validate(tree);
        if (!result.isValid()) {
            status.setText("Cannot save: " + result.getErrors().get(0));
            return;
        }
        try {
            io.write(tracksRoot, tree);
            tree = io.read(tracksRoot);
            rebuildOverlayCache();
            rebuildTrajectoryCache();
            reloadLists();
            if (onSaved != null) onSaved.run();
            status.setText("Saved Tracking: " + tracksRoot);
        } catch (IOException e) {
            status.setText("Save failed: " + e.getMessage());
        }
    }

    private void renderOverlay() {
        if (renderingOverlay) return;
        leftPane.repaint();
        rightPane.repaint();
    }

    private void selectionChanged() {
        updateSelectionStatus();
        renderOverlay();
    }

    private void updateSelectionStatus() {
        TrackEditor.ObjRef left = leftList.getSelectedValue();
        TrackEditor.ObjRef right = rightList.getSelectedValue();
        linkButton.setEnabled(left != null && right != null);
        unlinkButton.setEnabled(right != null);
        String l = left != null ? objLabel(left) : "none";
        String r = right != null ? objLabel(right) : "none";
        selectionStatus.setText("Selected: T" + currentT() + " " + l + "  ->  T" + (currentT() + 1) + " " + r
            + "    Shortcuts: Enter=link, Delete=unlink, A/D=T-1/T+1, Ctrl+S=save");
    }

    private static String objLabel(TrackEditor.ObjRef ref) {
        return ref.obj.getGlobalId() + " / " + ref.obj.getSourceObjId()
            + " [" + ref.parent.getGlobalId() + "]";
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

    private void installShortcuts() {
        JRootPane root = getRootPane();
        bind(root, "link", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), e -> linkSelected());
        bind(root, "unlink", KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), e -> unlinkSelected());
        bind(root, "save", KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), e -> save());
        bind(root, "prevT", KeyStroke.getKeyStroke(KeyEvent.VK_A, 0), e -> bumpTime(-1));
        bind(root, "nextT", KeyStroke.getKeyStroke(KeyEvent.VK_D, 0), e -> bumpTime(1));
    }

    private static void bind(JComponent c, String name, KeyStroke key, java.util.function.Consumer<ActionEvent> action) {
        c.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                action.accept(e);
            }
        });
    }

    private void bumpTime(int delta) {
        SpinnerNumberModel model = (SpinnerNumberModel) timeSpinner.getModel();
        int value = intValue(timeSpinner) + delta;
        int min = ((Number) model.getMinimum()).intValue();
        int max = ((Number) model.getMaximum()).intValue();
        if (value >= min && value <= max) timeSpinner.setValue(value);
    }

    private void updateImagePanes() {
        int t = currentT();
        int leftC = intValue(leftCSpinner);
        int rightC = intValue(rightCSpinner);
        int leftZ = intValue(leftZSpinner);
        int rightZ = intValue(rightZSpinner);
        boolean project = zProjMode.isSelected();
        leftPane.setFrame(t, frameFor(leftC, leftZ, t, project), refsFromModel(leftModel), project ? 0 : leftZ);
        rightPane.setFrame(t + 1, frameFor(rightC, rightZ, t + 1, project), refsFromModel(rightModel), project ? 0 : rightZ);
        renderOverlay();
    }

    private static java.util.List<TrackEditor.ObjRef> refsFromModel(DefaultListModel<TrackEditor.ObjRef> model) {
        java.util.List<TrackEditor.ObjRef> refs = new java.util.ArrayList<TrackEditor.ObjRef>();
        for (int i = 0; i < model.getSize(); i++) refs.add(model.getElementAt(i));
        return refs;
    }

    private void rebuildOverlayCache() {
        roiOverlayCache.clear();
        centroidOverlayCache.clear();
        if (tree == null) return;
        int maxT = image != null ? Math.max(1, image.getNFrames()) : 1;
        for (int t = 1; t <= maxT; t++) {
            for (TrackEditor.ObjRef ref : editor.objectsAt(tree, t)) {
                java.util.List<Roi> rois = new java.util.ArrayList<Roi>();
                for (Roi roi : ref.obj.getRois()) {
                    Roi copy = (Roi) roi.clone();
                    copy.setFillColor(null);
                    rois.add(copy);
                }
                roiOverlayCache.put(ref.obj, rois);
                OvalRoi dot = new OvalRoi(ref.obj.centerX() - 3.0, ref.obj.centerY() - 3.0, 6.0, 6.0);
                centroidOverlayCache.put(ref.obj, dot);
            }
        }
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

    private void updateModeControlsAndImages() {
        boolean project = zProjMode.isSelected();
        leftZSpinner.setEnabled(!project);
        rightZSpinner.setEnabled(!project && !zSync.isSelected());
        zSync.setEnabled(!project);
        rightCSpinner.setEnabled(!cSync.isSelected());
        if (!project && zSync.isSelected()) syncRightZFromLeft();
        if (cSync.isSelected()) syncRightCFromLeft();
        updateImagePanes();
    }

    private void syncRightZFromLeft() {
        if (!zSync.isSelected()) return;
        rightZSpinner.setValue(leftZSpinner.getValue());
        rightZSpinner.setEnabled(!zProjMode.isSelected() && !zSync.isSelected());
    }

    private void syncRightCFromLeft() {
        if (!cSync.isSelected()) return;
        rightCSpinner.setValue(leftCSpinner.getValue());
        rightCSpinner.setEnabled(false);
    }

    private BufferedImage frameFor(int c, int z, int t, boolean project) {
        String key = (project ? "p" : "z") + ":c" + c + ":z" + z + ":t" + t;
        BufferedImage cached = frameCache.get(key);
        if (cached != null) return cached;
        BufferedImage created = project ? createMaxProjection(image, c, t) : createSliceImage(image, c, z, t);
        frameCache.put(key, created);
        return created;
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
            linkSelected();
        }
        draggingLeftRef = null;
        leftPane.repaint();
        rightPane.repaint();
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
                if (gray < 0) gray = 0;
                if (gray > 255) gray = 255;
                raster.setSample(x, y, 0, gray);
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
                if (gray < 0) gray = 0;
                if (gray > 255) gray = 255;
                raster.setSample(x, y, 0, gray);
            }
        }
        return out;
    }

    private static int intValue(JSpinner spinner) {
        return ((Number) spinner.getValue()).intValue();
    }

    private final class LinkerImagePane extends JPanel {
        private final boolean left;
        private int time;
        private int zFilter;
        private BufferedImage frame;
        private java.util.List<TrackEditor.ObjRef> refs = java.util.Collections.emptyList();

        LinkerImagePane(boolean left) {
            this.left = left;
            setPreferredSize(new Dimension(460, 380));
            setBackground(Color.BLACK);
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    TrackEditor.ObjRef selected = selectAt(e.getX(), e.getY());
                    if (left) draggingLeftRef = selected;
                }
                @Override public void mouseClicked(MouseEvent e) {
                    TrackEditor.ObjRef selected = selectAt(e.getX(), e.getY());
                    if (!left && selected != null && e.getClickCount() >= 2 && leftList.getSelectedValue() != null) {
                        linkSelected();
                    }
                }
                @Override public void mouseExited(MouseEvent e) {
                    hoverRef = null;
                    repaint();
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    hoverRef = findAt(e.getX(), e.getY());
                    if (hoverRef != null) {
                        status.setText("Hover " + (left ? "T" + currentT() : "T" + (currentT() + 1)) + ": "
                            + objLabel(hoverRef));
                    }
                    repaint();
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
                paintObjects(g2, draw);
                paintLink(g2, draw);
                g2.setColor(Color.WHITE);
                g2.drawString("T" + time, 8, 16);
            } finally {
                g2.dispose();
            }
        }

        private void paintObjects(Graphics2D g2, Rectangle draw) {
            paintTrajectories(g2, draw);
            TrackEditor.ObjRef selected = left ? leftList.getSelectedValue() : rightList.getSelectedValue();
            TrackEditor.ObjRef peer = left ? rightList.getSelectedValue() : leftList.getSelectedValue();
            for (TrackEditor.ObjRef ref : refs) {
                boolean sameTrack = peer != null && peer.parent == ref.parent;
                boolean hovered = ref == hoverRef;
                Color color = ref == selected ? (left ? Color.YELLOW : Color.CYAN)
                    : hovered ? Color.ORANGE
                    : sameTrack ? new Color(0, 230, 120, 210)
                    : new Color(210, 210, 210, 150);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(ref == selected || hovered ? 2.4f : 1.1f));
                java.util.List<Roi> rois = roiOverlayCache.get(ref.obj);
                if (rois != null) {
                    for (Roi roi : rois) {
                        if (zFilter > 0 && !roiMatchesZ(roi, zFilter)) continue;
                        drawRoi(g2, draw, roi);
                    }
                }
                Point p = toView(draw, ref.obj.centerX(), ref.obj.centerY());
                g2.fillOval(p.x - 3, p.y - 3, 6, 6);
                g2.drawString(ref.obj.getGlobalId(), p.x + 5, p.y - 5);
            }
        }

        private void paintTrajectories(Graphics2D g2, Rectangle draw) {
            g2.setColor(new Color(0, 180, 255, 130));
            g2.setStroke(new BasicStroke(1.4f));
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

        private void paintLink(Graphics2D g2, Rectangle draw) {
            TrackEditor.ObjRef l = leftList.getSelectedValue();
            TrackEditor.ObjRef r = rightList.getSelectedValue();
            if (l == null || r == null) return;
            TrackEditor.ObjRef ref = left ? l : r;
            Point p = toView(draw, ref.obj.centerX(), ref.obj.centerY());
            g2.setColor(new Color(255, 128, 0, 210));
            g2.setStroke(new BasicStroke(2f));
            if (left) g2.drawLine(p.x, p.y, getWidth(), p.y);
            else g2.drawLine(0, p.y, p.x, p.y);
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

        private TrackEditor.ObjRef selectAt(int x, int y) {
            TrackEditor.ObjRef best = findAt(x, y);
            if (best != null) {
                if (left) leftList.setSelectedValue(best, true);
                else rightList.setSelectedValue(best, true);
            }
            return best;
        }

        private TrackEditor.ObjRef findAt(int x, int y) {
            Rectangle draw = imageRect();
            if (frame == null || !draw.contains(x, y)) return null;
            double ix = (x - draw.x) / scale(draw);
            double iy = (y - draw.y) / scale(draw);
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

        private void wheelMoved(MouseWheelEvent e) {
            if (e.isShiftDown() && !zProjMode.isSelected()) {
                JSpinner spinner = left ? leftZSpinner : rightZSpinner;
                bumpSpinner(spinner, -e.getWheelRotation());
            } else {
                bumpTime(e.getWheelRotation() > 0 ? 1 : -1);
            }
        }

        private Rectangle imageRect() {
            if (frame == null) return new Rectangle(0, 0, getWidth(), getHeight());
            double s = Math.min(getWidth() / (double) frame.getWidth(), getHeight() / (double) frame.getHeight());
            int w = Math.max(1, (int) Math.round(frame.getWidth() * s));
            int h = Math.max(1, (int) Math.round(frame.getHeight() * s));
            return new Rectangle((getWidth() - w) / 2, (getHeight() - h) / 2, w, h);
        }

        private double scale(Rectangle draw) {
            return frame == null ? 1.0 : draw.width / (double) frame.getWidth();
        }

        private Point toView(Rectangle draw, double x, double y) {
            double s = scale(draw);
            return new Point(draw.x + (int) Math.round(x * s), draw.y + (int) Math.round(y * s));
        }

        private boolean roiMatchesZ(Roi roi, int z) {
            int rz = roi.getZPosition();
            if (rz <= 0) rz = roi.getPosition();
            return rz <= 0 || rz == z;
        }

        private TrackEditor.ObjRef hoverRef;
    }

    private static void bumpSpinner(JSpinner spinner, int delta) {
        SpinnerNumberModel model = (SpinnerNumberModel) spinner.getModel();
        int value = intValue(spinner) + delta;
        int min = ((Number) model.getMinimum()).intValue();
        int max = ((Number) model.getMaximum()).intValue();
        if (value >= min && value <= max) spinner.setValue(value);
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
}
