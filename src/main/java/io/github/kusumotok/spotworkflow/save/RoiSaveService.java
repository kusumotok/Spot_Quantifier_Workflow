package io.github.kusumotok.spotworkflow.save;

import ij.gui.Roi;
import ij.io.RoiEncoder;
import io.github.kusumotok.roiexplorer.service.RoiZipService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

public final class RoiSaveService {

    private final RoiZipService zipService = new RoiZipService();

    /**
     * Saves per-object ROI lists to result_folder/rois/.
     *
     * objectRois: one List<Roi> per object (index 0 = obj-001).
     * Each Roi in the list is a 2D cross-section at a specific Z.
     *
     * Folder mode: rois/obj-001/roi-z001.roi, ...
     * ZIP modes:   rois/obj-001.zip
     */
    public void saveRois(Path resultFolder, List<List<Roi>> objectRois, SaveMode mode)
            throws IOException {
        saveRoisToRoot(resultFolder.resolve("rois"), objectRois, mode, false);
    }

    public void saveRoisToRoot(Path roisDir, List<List<Roi>> objectRois, SaveMode mode,
                               boolean clearExisting) throws IOException {
        if (clearExisting) deleteContents(roisDir);
        Files.createDirectories(roisDir);

        int objIdx = 1;
        for (List<Roi> rois : objectRois) {
            String objName = String.format("obj-%03d", objIdx++);
            if (mode == SaveMode.FOLDER) {
                saveFolderObject(roisDir, objName, rois);
            } else {
                saveZipObject(roisDir, objName, rois, mode);
            }
        }
    }

    /**
     * seed の nameToLabel マッピング（folderName → label）を使って result ROI を保存する。
     * label から folderName を逆引きし、seed_rois/obj-003_split1 →
     * result_rois/obj-003_split1 のようにフォルダ名を保持する。
     * roisByLabel のキーが nameToLabel に存在しないラベルは obj-NNN で fallback。
     */
    public void saveRoisByNameMapping(Path roisDir, Map<Integer, List<Roi>> roisByLabel,
                                      Map<String, Integer> nameToLabel,
                                      SaveMode mode, boolean clearExisting) throws IOException {
        if (clearExisting) deleteContents(roisDir);
        Files.createDirectories(roisDir);

        // 逆引きマップ: label → folderName
        Map<Integer, String> labelToName = new java.util.HashMap<>();
        for (Map.Entry<String, Integer> e : nameToLabel.entrySet()) {
            labelToName.put(e.getValue(), e.getKey());
        }

        // 未対応ラベルの連番カウンター
        int fallbackIdx = nameToLabel.size() + 1;

        for (Map.Entry<Integer, List<Roi>> entry : new TreeMap<>(roisByLabel).entrySet()) {
            int label = entry.getKey();
            String objName = labelToName.getOrDefault(label,
                String.format("obj-%03d", fallbackIdx++));
            List<Roi> rois = entry.getValue();
            if (mode == SaveMode.FOLDER) {
                saveFolderObject(roisDir, objName, rois);
            } else {
                saveZipObject(roisDir, objName, rois, mode);
            }
        }
    }

    private static void deleteContents(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(p -> !p.equals(dir))
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
            throw e;
        }
    }

    private void saveFolderObject(Path roisDir, String objName, List<Roi> rois)
            throws IOException {
        Path objDir = roisDir.resolve(objName);
        Files.createDirectories(objDir);
        int sliceIdx = 1;
        for (Roi roi : rois) {
            String filename = resolveRoiFilename(roi, objName, sliceIdx);
            new RoiEncoder(objDir.resolve(filename).toString()).write(roi);
            sliceIdx++;
        }
    }

    private void saveZipObject(Path roisDir, String objName, List<Roi> rois, SaveMode mode)
            throws IOException {
        RoiZipService.ZipMode zipMode = mode == SaveMode.ZIP_FAST
            ? RoiZipService.ZipMode.FAST
            : RoiZipService.ZipMode.COMPRESSED;

        List<RoiZipService.RoiEntry> entries = new ArrayList<>();
        int sliceIdx = 1;
        for (Roi roi : rois) {
            String filename = resolveRoiFilename(roi, objName, sliceIdx++);
            entries.add(new RoiZipService.RoiEntry(filename, roi));
        }
        zipService.writeZip(entries, roisDir.resolve(objName + ".zip").toFile(), zipMode);
    }

    private static String resolveRoiFilename(Roi roi, String objName, int sliceIdx) {
        String name = roi.getName();
        if (name != null && !name.trim().isEmpty()) {
            if (!name.toLowerCase().endsWith(".roi")) name += ".roi";
            return name;
        }
        return String.format("%s-z%03d.roi", objName, sliceIdx);
    }
}
