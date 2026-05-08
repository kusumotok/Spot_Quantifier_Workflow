package io.github.kusumotok.spotworkflow.save;

import ij.gui.Roi;
import ij.io.RoiEncoder;
import io.github.kusumotok.roiexplorer.service.RoiZipService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        Path roisDir = resultFolder.resolve("rois");
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
