package io.github.kusumotok.spotworkflow.core.alg;

import ij.IJ;
import ij.ImagePlus;
import ij.plugin.filter.GaussianBlur;
import ij.process.FloatProcessor;
import ij.process.IntProcessor;
import ij.process.ImageProcessor;
import io.github.kusumotok.spotworkflow.core.model.MarkerResult;
import io.github.kusumotok.spotworkflow.core.model.Surface;
import io.github.kusumotok.spotworkflow.core.model.Connectivity;

import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

public class WatershedRunner {
    public SegmentationResult run(ImagePlus imp, MarkerResult markers, Surface surface, Connectivity connectivity,
                                  boolean preprocessingEnabled, double sigmaSurface) {
        if (markers == null) {
            IJ.error("Watershed failed", "Markers are missing.");
            return new SegmentationResult(null);
        }
        int w = markers.width;
        int h = markers.height;
        int size = w * h;
        float[] topo;
        if (surface == Surface.GRADIENT_SOBEL) {
            topo = sobelSurface(imp);
        } else if (surface == Surface.INVERT_ORIGINAL) {
            topo = invertedIntensitySurface(imp);
        } else {
            topo = intensitySurface(imp);
        }
        topo = maybeGaussian(topo, w, h, preprocessingEnabled, sigmaSurface);

        boolean[] domain = markers.domainMask;

        int[] labels = new int[size];
        Arrays.fill(labels, -1);
        boolean[] seed = new boolean[size];
        int[] seeds = markers.buildSeedLabels();
        for (int i = 0; i < size; i++) {
            if (!domain[i]) {
                labels[i] = 0;
                continue;
            }
            if (seeds[i] > 0) {
                labels[i] = seeds[i];
                seed[i] = true;
            }
        }

        PriorityQueue<Node> pq = new PriorityQueue<>(
            Comparator.<Node>comparingDouble(n -> n.priority).thenComparingLong(n -> n.seq));
        long seq = 0;
        for (int i = 0; i < size; i++) {
            if (seed[i]) pq.add(new Node(i, labels[i], topo[i], seq++));
        }

        int[] dx = new int[] {-1, 0, 1, 0, -1, 1, -1, 1};
        int[] dy = new int[] {0, -1, 0, 1, -1, -1, 1, 1};
        int neighborCount = connectivity == Connectivity.C8 ? 8 : 4;

        while (!pq.isEmpty()) {
            Node n = pq.poll();
            int p = n.idx;
            int x = p % w;
            int y = p / w;
            for (int k = 0; k < neighborCount; k++) {
                int nx = x + dx[k];
                int ny = y + dy[k];
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                int ni = ny * w + nx;
                if (!domain[ni]) continue;
                if (labels[ni] != -1) continue;
                labels[ni] = n.label;
                float nextPriority = Math.max(n.priority, topo[ni]);
                pq.add(new Node(ni, n.label, nextPriority, seq++));
            }
        }

        for (int i = 0; i < size; i++) {
            if (labels[i] < 0) labels[i] = 0;
        }

        ImagePlus out = new ImagePlus(imp.getShortTitle() + "-labels", new IntProcessor(w, h, labels));
        return new SegmentationResult(out);
    }

    private float[] maybeGaussian(float[] surface, int w, int h, boolean enabled, double sigma) {
        if (!enabled || sigma <= 0.0) return surface;
        FloatProcessor fp = new FloatProcessor(w, h, surface);
        GaussianBlur gb = new GaussianBlur();
        gb.blurGaussian(fp, sigma, sigma, 0.01);
        return (float[]) fp.getPixels();
    }

    private float[] intensitySurface(ImagePlus imp) {
        int w = imp.getWidth();
        int h = imp.getHeight();
        int size = w * h;
        float[] surface = new float[size];
        ImageProcessor ip = imp.getProcessor();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                surface[y * w + x] = (float) ip.getPixelValue(x, y);
            }
        }
        return surface;
    }

    private float[] sobelSurface(ImagePlus imp) {
        int w = imp.getWidth();
        int h = imp.getHeight();
        FloatProcessor fp = imp.getProcessor().convertToFloatProcessor();
        float[] surface = new float[w * h];
        float[] pixels = (float[]) fp.getPixels();
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(y - 1, 0);
            int y2 = Math.min(y + 1, h - 1);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(x - 1, 0);
                int x2 = Math.min(x + 1, w - 1);
                float a = pixels[y0 * w + x0];
                float b = pixels[y0 * w + x];
                float c = pixels[y0 * w + x2];
                float d = pixels[y * w + x0];
                float f = pixels[y * w + x2];
                float g = pixels[y2 * w + x0];
                float h2 = pixels[y2 * w + x];
                float i = pixels[y2 * w + x2];
                float gx = (-a + c) + (-2 * d + 2 * f) + (-g + i);
                float gy = (-a - 2 * b - c) + (g + 2 * h2 + i);
                surface[y * w + x] = (float) Math.hypot(gx, gy);
            }
        }
        return surface;
    }

    private float[] invertedIntensitySurface(ImagePlus imp) {
        int w = imp.getWidth();
        int h = imp.getHeight();
        int size = w * h;
        float[] surface = new float[size];
        ImageProcessor ip = imp.getProcessor();
        float maxVal = (float) ip.getMax();
        float minVal = (float) ip.getMin();
        float sum = maxVal + minVal;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                surface[y * w + x] = sum - (float) ip.getPixelValue(x, y);
            }
        }
        return surface;
    }

    private static class Node {
        final int idx;
        final int label;
        final float priority;
        final long seq;
        Node(int idx, int label, float priority, long seq) {
            this.idx = idx;
            this.label = label;
            this.priority = priority;
            this.seq = seq;
        }
    }
}
