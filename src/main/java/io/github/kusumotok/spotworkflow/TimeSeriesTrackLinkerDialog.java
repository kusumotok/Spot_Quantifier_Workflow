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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
    private final JLabel status = new JLabel(" ");
    private TrackTree tree;
    private boolean renderingOverlay;
    private final java.util.Map<TrackTree.ObjNode, java.util.List<Roi>> roiOverlayCache =
        new java.util.IdentityHashMap<TrackTree.ObjNode, java.util.List<Roi>>();
    private final java.util.Map<TrackTree.ObjNode, OvalRoi> centroidOverlayCache =
        new java.util.IdentityHashMap<TrackTree.ObjNode, OvalRoi>();
    private final java.util.Map<Integer, BufferedImage> projectionCache =
        new java.util.HashMap<Integer, BufferedImage>();

    TimeSeriesTrackLinkerDialog(Window owner, ImagePlus image, Path tracksRoot, Runnable onSaved) throws IOException {
        super(owner, "Track Linker", ModalityType.MODELESS);
        this.image = image;
        this.tracksRoot = tracksRoot;
        this.onSaved = onSaved;
        this.tree = io.read(tracksRoot);
        rebuildOverlayCache();
        int maxT = image != null ? Math.max(1, image.getNFrames() - 1) : 1;
        timeSpinner = new JSpinner(new SpinnerNumberModel(1, 1, Math.max(1, maxT), 1));
        buildUi();
        installListeners();
        reloadLists();
        pack();
        setSize(Math.max(620, getWidth()), Math.max(420, getHeight()));
        setLocationRelativeTo(owner);
        renderOverlay();
    }

    private void buildUi() {
        setLayout(new BorderLayout(6, 6));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        top.add(new JLabel("T:"));
        top.add(timeSpinner);
        top.add(new JLabel("left=T, right=T+1"));
        add(top, BorderLayout.NORTH);

        JPanel imageViews = new JPanel(new GridLayout(1, 2, 6, 0));
        imageViews.add(panelWithTitle("T image", leftPane));
        imageViews.add(panelWithTitle("T+1 image", rightPane));

        JPanel lists = new JPanel(new GridLayout(1, 2, 6, 0));
        lists.add(panelWithTitle("T", leftList));
        lists.add(panelWithTitle("T+1", rightList));
        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, imageViews, lists);
        center.setResizeWeight(0.72);
        center.setDividerLocation(0.72);
        add(center, BorderLayout.CENTER);

        JButton link = new JButton("Link selected");
        JButton unlink = new JButton("Unlink right to new track");
        JButton save = new JButton("Save Tracking");
        JButton close = new JButton("Close");
        link.addActionListener(e -> linkSelected());
        unlink.addActionListener(e -> unlinkSelected());
        save.addActionListener(e -> save());
        close.addActionListener(e -> dispose());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        actions.add(link);
        actions.add(unlink);
        actions.add(save);
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
        leftList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) renderOverlay(); });
        rightList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) renderOverlay(); });
    }

    private void reloadLists() {
        int t = currentT();
        renderingOverlay = true;
        fill(leftModel, editor.objectsAt(tree, t));
        fill(rightModel, editor.objectsAt(tree, t + 1));
        renderingOverlay = false;
        status.setText("Editing links for T" + t + " -> T" + (t + 1));
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

    private void updateImagePanes() {
        int t = currentT();
        leftPane.setFrame(t, projectionForTime(t), refsFromModel(leftModel));
        rightPane.setFrame(t + 1, projectionForTime(t + 1), refsFromModel(rightModel));
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

    private BufferedImage projectionForTime(int t) {
        BufferedImage cached = projectionCache.get(t);
        if (cached != null) return cached;
        BufferedImage created = createMaxProjection(image, t);
        projectionCache.put(t, created);
        return created;
    }

    @Override public void dispose() {
        super.dispose();
    }

    private static BufferedImage createMaxProjection(ImagePlus image, int t) {
        int w = image != null ? image.getWidth() : 1;
        int h = image != null ? image.getHeight() : 1;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        if (image == null) return out;
        int c = Math.max(1, Math.min(image.getC(), Math.max(1, image.getNChannels())));
        int safeT = Math.max(1, Math.min(t, Math.max(1, image.getNFrames())));
        int slices = Math.max(1, image.getNSlices());
        float[] max = new float[w * h];
        java.util.Arrays.fill(max, -Float.MAX_VALUE);
        float min = Float.MAX_VALUE;
        float maxVal = -Float.MAX_VALUE;
        for (int z = 1; z <= slices; z++) {
            ImageProcessor ip = image.getStack().getProcessor(image.getStackIndex(c, z, safeT));
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

    private final class LinkerImagePane extends JPanel {
        private final boolean left;
        private int time;
        private BufferedImage frame;
        private java.util.List<TrackEditor.ObjRef> refs = java.util.Collections.emptyList();

        LinkerImagePane(boolean left) {
            this.left = left;
            setPreferredSize(new Dimension(280, 240));
            setBackground(Color.BLACK);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    selectAt(e.getX(), e.getY());
                }
            });
        }

        void setFrame(int time, BufferedImage frame, java.util.List<TrackEditor.ObjRef> refs) {
            this.time = time;
            this.frame = frame;
            this.refs = refs != null ? refs : java.util.Collections.<TrackEditor.ObjRef>emptyList();
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
            TrackEditor.ObjRef selected = left ? leftList.getSelectedValue() : rightList.getSelectedValue();
            for (TrackEditor.ObjRef ref : refs) {
                Color color = ref == selected ? (left ? Color.YELLOW : Color.CYAN) : new Color(210, 210, 210, 150);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(ref == selected ? 2f : 1f));
                java.util.List<Roi> rois = roiOverlayCache.get(ref.obj);
                if (rois != null) {
                    for (Roi roi : rois) drawRoi(g2, draw, roi);
                }
                Point p = toView(draw, ref.obj.centerX(), ref.obj.centerY());
                g2.fillOval(p.x - 3, p.y - 3, 6, 6);
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

        private void selectAt(int x, int y) {
            Rectangle draw = imageRect();
            if (frame == null || !draw.contains(x, y)) return;
            double ix = (x - draw.x) / scale(draw);
            double iy = (y - draw.y) / scale(draw);
            TrackEditor.ObjRef best = null;
            double bestArea = Double.POSITIVE_INFINITY;
            for (TrackEditor.ObjRef ref : refs) {
                for (Roi roi : ref.obj.getRois()) {
                    if (!roi.contains((int) Math.round(ix), (int) Math.round(iy))) continue;
                    Rectangle b = roi.getBounds();
                    double area = b.getWidth() * b.getHeight();
                    if (area < bestArea) {
                        bestArea = area;
                        best = ref;
                    }
                }
            }
            if (best != null) {
                if (left) leftList.setSelectedValue(best, true);
                else rightList.setSelectedValue(best, true);
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
    }
}
