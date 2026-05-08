package io.github.kusumotok.spotworkflow.core.alg;

import ij.IJ;
import ij.ImagePlus;
import ij.plugin.filter.GaussianBlur;
import ij.process.IntProcessor;
import ij.process.ImageProcessor;
import ij.process.FloatProcessor;
import io.github.kusumotok.spotworkflow.core.model.MarkerResult;
import io.github.kusumotok.spotworkflow.core.model.Connectivity;

public class RandomWalkerRunner {
    public SegmentationResult run(ImagePlus imp, MarkerResult markers, Connectivity connectivity,
                                  boolean preprocessingEnabled, double sigmaSurface, double betaParam) {
        if (markers == null) {
            IJ.error("Random Walker failed", "Markers are missing.");
            return new SegmentationResult(null);
        }
        int w = markers.width;
        int h = markers.height;
        int size = w * h;
        int[] seeds = markers.buildSeedLabels();
        int maxSeed = 0;
        for (int v : seeds) if (v > maxSeed) maxSeed = v;
        int classCount = maxSeed;
        boolean[] domain = markers.domainMask;
        int domainCount = 0;
        for (int i = 0; i < size; i++) {
            if (domain[i]) domainCount++;
        }
        if (classCount <= 0 || domainCount == 0) {
            int[] labels = new int[size];
            ImagePlus out = new ImagePlus(imp.getShortTitle() + "-labels", new IntProcessor(w, h, labels));
            return new SegmentationResult(out);
        }

        float[] intensity = new float[size];
        ImageProcessor ip = imp.getProcessor();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                intensity[y * w + x] = (float) ip.getPixelValue(x, y);
            }
        }
        if (preprocessingEnabled && sigmaSurface > 0.0) {
            FloatProcessor fp = new FloatProcessor(w, h, intensity);
            GaussianBlur gb = new GaussianBlur();
            gb.blurGaussian(fp, sigmaSurface, sigmaSurface, 0.01);
            intensity = (float[]) fp.getPixels();
        }

        boolean[] isSeed = new boolean[size];
        int[] seedLabel = new int[size];
        for (int i = 0; i < size; i++) seedLabel[i] = -1;
        for (int i = 0; i < size; i++) {
            if (domain[i] && seeds[i] > 0) {
                isSeed[i] = true;
                seedLabel[i] = seeds[i] - 1;
            }
        }

        float[][] probs = new float[classCount][size];
        for (int i = 0; i < size; i++) {
            if (!isSeed[i]) continue;
            for (int l = 0; l < classCount; l++) probs[l][i] = 0f;
            probs[seedLabel[i]][i] = 1f;
        }

        double beta = (betaParam > 0.0) ? betaParam : defaultBeta(imp);
        int[] dx = new int[] {-1, 0, 1, 0, -1, 1, -1, 1};
        int[] dy = new int[] {0, -1, 0, 1, -1, -1, 1, 1};
        int neighborCount = connectivity == Connectivity.C8 ? 8 : 4;
        int maxIter = 200;
        double eps = 1e-4;
        double[] weights = new double[neighborCount];
        int[] nIdx = new int[neighborCount];

        for (int iter = 0; iter < maxIter; iter++) {
            double maxDelta = 0.0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int idx = y * w + x;
                    if (!domain[idx]) continue;
                    if (isSeed[idx]) continue;
                    double denom = 0.0;
                    int nCount = 0;
                    for (int k = 0; k < neighborCount; k++) {
                        int nx = x + dx[k];
                        int ny = y + dy[k];
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                        int n = ny * w + nx;
                        if (!domain[n]) continue;
                        double diff = intensity[idx] - intensity[n];
                        double wgt = Math.exp(-beta * diff * diff);
                        weights[nCount] = wgt;
                        nIdx[nCount] = n;
                        denom += wgt;
                        nCount++;
                    }
                    if (denom == 0.0) continue;
                    double sumAll = 0.0;
                    for (int l = 0; l < classCount; l++) {
                        double acc = 0.0;
                        for (int k = 0; k < nCount; k++) {
                            acc += weights[k] * probs[l][nIdx[k]];
                        }
                        double newVal = acc / denom;
                        double delta = Math.abs(newVal - probs[l][idx]);
                        if (delta > maxDelta) maxDelta = delta;
                        probs[l][idx] = (float) newVal;
                        sumAll += newVal;
                    }
                    if (sumAll > 0.0) {
                        float inv = (float) (1.0 / sumAll);
                        for (int l = 0; l < classCount; l++) probs[l][idx] *= inv;
                    }
                }
            }
            if (maxDelta < eps) break;
        }

        int[] labels = new int[size];
        for (int i = 0; i < size; i++) {
            if (!domain[i]) {
                labels[i] = 0;
                continue;
            }
            if (isSeed[i]) {
                labels[i] = seedLabel[i] + 1;
                continue;
            }
            int best = 0;
            float bestVal = probs[0][i];
            for (int l = 1; l < classCount; l++) {
                float v = probs[l][i];
                if (v > bestVal) {
                    bestVal = v;
                    best = l;
                }
            }
            labels[i] = best + 1;
        }

        if (classCount > 0) {
            removeIslands(labels, w, h, classCount, connectivity);
        }

        ImagePlus out = new ImagePlus(imp.getShortTitle() + "-labels", new IntProcessor(w, h, labels));
        return new SegmentationResult(out);
    }

    private void removeIslands(int[] labels, int w, int h, int maxLabel, Connectivity connectivity) {
        int size = w * h;
        int[] seen = new int[size];
        int[] stack = new int[size];
        int[] comp = new int[size];
        int[] dx = new int[] {-1, 0, 1, 0, -1, 1, -1, 1};
        int[] dy = new int[] {0, -1, 0, 1, -1, -1, 1, 1};
        int neighborCount = connectivity == Connectivity.C8 ? 8 : 4;
        for (int label = 1; label <= maxLabel; label++) {
            int bestSize = 0;
            int[] bestPixels = null;
            int bestLen = 0;
            for (int i = 0; i < size; i++) {
                if (labels[i] != label || seen[i] == label) continue;
                int sp = 0;
                stack[sp++] = i;
                seen[i] = label;
                int compSize = 0;
                while (sp > 0) {
                    int idx = stack[--sp];
                    comp[compSize++] = idx;
                    int x = idx % w;
                    int y = idx / w;
                    for (int k = 0; k < neighborCount; k++) {
                        int nx = x + dx[k];
                        int ny = y + dy[k];
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                        int ni = ny * w + nx;
                        if (labels[ni] != label || seen[ni] == label) continue;
                        seen[ni] = label;
                        stack[sp++] = ni;
                    }
                }
                if (compSize > bestSize) {
                    if (bestPixels != null) {
                        for (int j = 0; j < bestLen; j++) labels[bestPixels[j]] = 0;
                    }
                    bestSize = compSize;
                    bestLen = compSize;
                    bestPixels = new int[compSize];
                    System.arraycopy(comp, 0, bestPixels, 0, compSize);
                } else {
                    for (int j = 0; j < compSize; j++) labels[comp[j]] = 0;
                }
            }
        }
    }

    private double defaultBeta(ImagePlus imp) {
        int bitDepth = imp.getBitDepth();
        double max = (bitDepth == 16) ? 65535.0 : (bitDepth == 32) ? 65535.0 : 255.0;
        return 90.0 / (max * max);
    }
}
