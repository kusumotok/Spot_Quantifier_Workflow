package io.github.kusumotok.spotworkflow;

import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.gui.Overlay;
import ij.gui.Roi;
import io.github.kusumotok.spotworkflow.tracking.TrackEditor;
import io.github.kusumotok.spotworkflow.tracking.TrackTree;
import io.github.kusumotok.spotworkflow.tracking.TrackTreeIo;
import io.github.kusumotok.spotworkflow.tracking.TrackValidator;

import javax.swing.*;
import java.awt.*;
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
    private final JLabel status = new JLabel(" ");
    private TrackTree tree;
    private boolean renderingOverlay;
    private final java.util.Map<TrackTree.ObjNode, java.util.List<Roi>> roiOverlayCache =
        new java.util.IdentityHashMap<TrackTree.ObjNode, java.util.List<Roi>>();
    private final java.util.Map<TrackTree.ObjNode, OvalRoi> centroidOverlayCache =
        new java.util.IdentityHashMap<TrackTree.ObjNode, OvalRoi>();

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

        JPanel lists = new JPanel(new GridLayout(1, 2, 6, 0));
        lists.add(panelWithTitle("T", leftList));
        lists.add(panelWithTitle("T+1", rightList));
        add(lists, BorderLayout.CENTER);

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
        if (image == null || renderingOverlay) return;
        renderingOverlay = true;
        Overlay overlay = new Overlay();
        try {
            TrackEditor.ObjRef left = leftList.getSelectedValue();
            TrackEditor.ObjRef right = rightList.getSelectedValue();
            addObject(overlay, left, Color.YELLOW);
            addObject(overlay, right, Color.CYAN);
            image.setOverlay(overlay.size() > 0 ? overlay : null);
            image.updateAndDraw();
        } finally {
            renderingOverlay = false;
        }
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

    private void addObject(Overlay overlay, TrackEditor.ObjRef ref, Color color) {
        if (ref == null) return;
        java.util.List<Roi> rois = roiOverlayCache.get(ref.obj);
        if (rois == null) rois = java.util.Collections.emptyList();
        for (Roi cached : rois) {
            Roi copy = (Roi) cached.clone();
            copy.setStrokeColor(color);
            copy.setFillColor(null);
            overlay.add(copy);
        }
        OvalRoi cachedDot = centroidOverlayCache.get(ref.obj);
        OvalRoi dot = cachedDot != null
            ? (OvalRoi) cachedDot.clone()
            : new OvalRoi(ref.obj.centerX() - 3.0, ref.obj.centerY() - 3.0, 6.0, 6.0);
        dot.setStrokeColor(color);
        dot.setFillColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 90));
        overlay.add(dot);
    }

    @Override public void dispose() {
        if (image != null) {
            image.setOverlay(null);
            image.updateAndDraw();
        }
        super.dispose();
    }
}
