package io.github.kusumotok.spotworkflow;

import ij.ImageListener;
import ij.ImagePlus;
import ij.gui.Line;
import ij.gui.OvalRoi;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.io.RoiDecoder;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class TimeSeriesTrackTab extends JPanel {
    private final JButton btnRefresh = new JButton("Refresh Tracks");
    private final JButton btnClearOverlay = new JButton("Clear Overlay");
    private final JLabel statusLabel = new JLabel("No seed tracks loaded.");
    private final TrackTableModel tableModel = new TrackTableModel();
    private final JTable table = new JTable(tableModel);

    private ImagePlus image;
    private Path tracksRoot;
    private List<Track> tracks = Collections.emptyList();
    private boolean active;
    private ImageListener listener;

    TimeSeriesTrackTab() {
        super(new BorderLayout(0, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        actions.add(btnRefresh);
        actions.add(btnClearOverlay);
        actions.add(new JLabel("Overlay: current-T seed ROI + centroid trajectories. Link editing UI is next."));
        add(actions, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        btnRefresh.addActionListener(e -> reload());
        btnClearOverlay.addActionListener(e -> clearOverlay());
    }

    void setImage(ImagePlus image) {
        if (this.image == image) return;
        uninstallListener();
        clearOverlay();
        this.image = image;
        installListener();
        renderIfActive();
    }

    void setTracksRoot(Path root) {
        tracksRoot = root;
        reload();
    }

    void setActive(boolean active) {
        this.active = active;
        if (active) renderOverlay();
        else clearOverlay();
    }

    private void reload() {
        if (tracksRoot == null || !Files.isDirectory(tracksRoot)) {
            tracks = Collections.emptyList();
            tableModel.setTracks(tracks);
            statusLabel.setText("No seed_tracks folder loaded.");
            clearOverlay();
            return;
        }
        try {
            tracks = readTracks(tracksRoot);
            tableModel.setTracks(tracks);
            statusLabel.setText(tracks.size() + " tracks loaded: " + tracksRoot);
            renderIfActive();
        } catch (IOException e) {
            statusLabel.setText("Could not read seed_tracks: " + e.getMessage());
            tracks = Collections.emptyList();
            tableModel.setTracks(tracks);
            clearOverlay();
        }
    }

    private void renderIfActive() {
        if (active) renderOverlay();
    }

    private void renderOverlay() {
        if (!active || image == null) return;
        Overlay overlay = new Overlay();
        int currentT = image.isHyperStack() ? Math.max(1, image.getT()) : 1;
        for (Track track : tracks) {
            addTrajectory(overlay, track);
            for (TrackSpot spot : track.spots) {
                if (spot.t != currentT) continue;
                for (Roi roi : spot.rois) {
                    Roi clone = (Roi) roi.clone();
                    clone.setStrokeColor(Color.YELLOW);
                    overlay.add(clone);
                }
                OvalRoi dot = new OvalRoi(spot.x - 3.0, spot.y - 3.0, 6.0, 6.0);
                dot.setStrokeColor(Color.CYAN);
                dot.setFillColor(new Color(0, 255, 255, 90));
                overlay.add(dot);
            }
        }
        image.setOverlay(overlay);
        image.updateAndDraw();
    }

    private void addTrajectory(Overlay overlay, Track track) {
        List<TrackSpot> spots = track.spots;
        for (int i = 1; i < spots.size(); i++) {
            TrackSpot a = spots.get(i - 1);
            TrackSpot b = spots.get(i);
            Line line = new Line(a.x, a.y, b.x, b.y);
            line.setStrokeColor(new Color(0, 180, 255, 160));
            line.setStrokeWidth(1.5);
            overlay.add(line);
        }
    }

    private void clearOverlay() {
        if (image != null) {
            image.setOverlay(null);
            image.updateAndDraw();
        }
    }

    private void installListener() {
        if (image == null) return;
        listener = new ImageListener() {
            @Override public void imageOpened(ImagePlus imp) {}
            @Override public void imageClosed(ImagePlus imp) {}
            @Override public void imageUpdated(ImagePlus imp) {
                if (imp == image && active) SwingUtilities.invokeLater(() -> renderOverlay());
            }
        };
        ImagePlus.addImageListener(listener);
    }

    private void uninstallListener() {
        if (listener != null) ImagePlus.removeImageListener(listener);
        listener = null;
    }

    private static List<Track> readTracks(Path root) throws IOException {
        List<Track> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(root)) {
            List<Path> dirs = new ArrayList<>();
            stream.filter(Files::isDirectory)
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .forEach(dirs::add);
            for (Path dir : dirs) {
                Track track = readTrack(dir);
                if (!track.spots.isEmpty()) out.add(track);
            }
        }
        return out;
    }

    private static Track readTrack(Path trackDir) throws IOException {
        List<TrackSpot> spots = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(trackDir)) {
            List<Path> timeDirs = new ArrayList<>();
            stream.filter(Files::isDirectory)
                .filter(p -> parseTimeFolder(p) > 0)
                .sorted(Comparator.comparingInt(TimeSeriesTrackTab::parseTimeFolder))
                .forEach(timeDirs::add);
            for (Path timeDir : timeDirs) {
                TrackSpot spot = readSpot(timeDir, parseTimeFolder(timeDir));
                if (spot != null) spots.add(spot);
            }
        }
        return new Track(trackDir.getFileName().toString(), spots);
    }

    private static TrackSpot readSpot(Path timeDir, int t) throws IOException {
        List<Roi> rois = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(timeDir)) {
            List<Path> files = new ArrayList<>();
            stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".roi"))
                .forEach(files::add);
            for (Path file : files) {
                Roi roi = new RoiDecoder(file.toString()).getRoi();
                if (roi != null) rois.add(roi);
            }
        }
        if (rois.isEmpty()) return null;
        double sx = 0.0;
        double sy = 0.0;
        int n = 0;
        for (Roi roi : rois) {
            Rectangle b = roi.getBounds();
            sx += b.getCenterX();
            sy += b.getCenterY();
            n++;
        }
        return new TrackSpot(t, sx / n, sy / n, rois);
    }

    private static int parseTimeFolder(Path path) {
        String name = path.getFileName() != null ? path.getFileName().toString() : "";
        if (!name.matches("(?i)t\\d+")) return 0;
        return Integer.parseInt(name.substring(1));
    }

    private static final class Track {
        final String name;
        final List<TrackSpot> spots;

        Track(String name, List<TrackSpot> spots) {
            this.name = name;
            this.spots = spots;
        }
    }

    private static final class TrackSpot {
        final int t;
        final double x;
        final double y;
        final List<Roi> rois;

        TrackSpot(int t, double x, double y, List<Roi> rois) {
            this.t = t;
            this.x = x;
            this.y = y;
            this.rois = rois;
        }
    }

    private static final class TrackTableModel extends AbstractTableModel {
        private final String[] columns = {"Track", "First T", "Last T", "Spots"};
        private List<Track> tracks = Collections.emptyList();

        void setTracks(List<Track> tracks) {
            this.tracks = tracks != null ? tracks : Collections.emptyList();
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return tracks.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Track track = tracks.get(rowIndex);
            switch (columnIndex) {
                case 0: return track.name;
                case 1: return track.spots.isEmpty() ? "" : track.spots.get(0).t;
                case 2: return track.spots.isEmpty() ? "" : track.spots.get(track.spots.size() - 1).t;
                case 3: return track.spots.size();
                default: return "";
            }
        }
    }
}
