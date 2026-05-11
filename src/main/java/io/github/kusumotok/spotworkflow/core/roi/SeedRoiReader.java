package io.github.kusumotok.spotworkflow.core.roi;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.io.RoiDecoder;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class SeedRoiReader {

    /**
     * 読み込み結果。ラベル画像と "フォルダ名 → ラベル番号" の対応表を持つ。
     * result ROI 保存時に対応表を逆引きしてフォルダ名を決めることで
     * seed_rois/obj-003_split1 ↔ result_rois/obj-003_split1 の追跡を保証する。
     */
    public static final class SeedReadResult {
        public final ImagePlus labelImage;
        /** seed フォルダ名 → 整数ラベル ID。挿入順序を保持する。 */
        public final Map<String, Integer> nameToLabel;

        SeedReadResult(ImagePlus labelImage, Map<String, Integer> nameToLabel) {
            this.labelImage = labelImage;
            this.nameToLabel = nameToLabel;
        }
    }

    public SeedReadResult read(Path seedRoot, ImagePlus image) throws IOException {
        if (!Files.isDirectory(seedRoot))
            throw new IOException("Seed ROI folder does not exist: " + seedRoot);
        ImageStack stack = new ImageStack(image.getWidth(), image.getHeight());
        for (int z = 1; z <= image.getNSlices(); z++)
            stack.addSlice(new FloatProcessor(image.getWidth(), image.getHeight()));

        List<Path> objects = listSeedObjects(seedRoot);
        if (objects.isEmpty()) throw new IOException("No seed objects found in: " + seedRoot);

        Map<String, Integer> nameToLabel = new LinkedHashMap<>();
        int label = 1;
        for (Path object : objects) {
            List<Roi> rois = Files.isDirectory(object) ? readFolderObject(object) : readZipObject(object);
            if (rois.isEmpty()) continue;
            String folderName = stemName(object);
            nameToLabel.put(folderName, label);
            for (Roi roi : rois) paintRoi(stack, roi, image, label);
            label++;
        }
        if (nameToLabel.isEmpty()) throw new IOException("No readable seed ROIs found in: " + seedRoot);
        return new SeedReadResult(new ImagePlus("edited-seed-labels", stack), nameToLabel);
    }

    /** 後方互換: ラベル画像のみ返す。 */
    public ImagePlus readAsLabelImage(Path seedRoot, ImagePlus image) throws IOException {
        return read(seedRoot, image).labelImage;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static List<Path> listSeedObjects(Path seedRoot) throws IOException {
        List<Path> objects = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(seedRoot)) {
            stream.filter(p -> Files.isDirectory(p) ||
                               p.getFileName().toString().toLowerCase().endsWith(".zip"))
                  .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                  .forEach(objects::add);
        }
        return objects;
    }

    /** パスのステム名（拡張子なし）を返す。"obj-003.zip" → "obj-003" */
    private static String stemName(Path path) {
        String name = path.getFileName().toString();
        if (name.toLowerCase().endsWith(".zip")) name = name.substring(0, name.length() - 4);
        return name;
    }

    private static List<Roi> readFolderObject(Path objectDir) throws IOException {
        List<Roi> rois = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(objectDir)) {
            List<Path> files = new ArrayList<>();
            stream.filter(p -> Files.isRegularFile(p) &&
                               p.getFileName().toString().toLowerCase().endsWith(".roi"))
                  .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                  .forEach(files::add);
            for (Path file : files) {
                Roi roi = new RoiDecoder(file.toString()).getRoi();
                if (roi != null) rois.add(roi);
            }
        }
        return rois;
    }

    private static List<Roi> readZipObject(Path zipFile) throws IOException {
        List<Roi> rois = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".roi")) continue;
                Roi roi = new RoiDecoder(readAllBytes(zin), entry.getName()).getRoi();
                if (roi != null) rois.add(roi);
            }
        }
        return rois;
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void paintRoi(ImageStack stack, Roi roi, ImagePlus image, int label)
            throws IOException {
        int z = resolveZPosition(roi, image);
        if (z < 1 || z > image.getNSlices())
            throw new IOException("Seed ROI z-position out of range: " + z);
        ImageProcessor target = stack.getProcessor(z);
        Rectangle b = roi.getBounds();
        ImageProcessor mask = roi.getMask();
        int minX = Math.max(0, b.x), minY = Math.max(0, b.y);
        int maxX = Math.min(image.getWidth(),  b.x + b.width);
        int maxY = Math.min(image.getHeight(), b.y + b.height);
        for (int y = minY; y < maxY; y++) {
            for (int x = minX; x < maxX; x++) {
                boolean inside = mask != null
                    ? mask.getPixel(x - b.x, y - b.y) != 0
                    : roi.contains(x, y);
                if (!inside) continue;
                int existing = (int) Math.round(target.getPixelValue(x, y));
                if (existing > 0 && existing != label)
                    throw new IOException(
                        "Seed ROI overlap between different objects at x=" + x + ", y=" + y + ", z=" + z);
                target.putPixelValue(x, y, label);
            }
        }
    }

    private static int resolveZPosition(Roi roi, ImagePlus image) {
        int z = roi.getZPosition();
        if (z > 0) return z;
        int p = roi.getPosition();
        if (p > 0 && image.getNChannels() <= 1 && image.getNFrames() <= 1) return p;
        return image.getNSlices() == 1 ? 1 : 0;
    }
}
