package io.github.kusumotok.spotworkflow;

import ij.ImagePlus;
import io.github.kusumotok.roiexplorer.ui.RoiExplorerPanel;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RoiEditTabController {

    private final WorkflowController controller;
    private final WorkflowWindow window;
    private final RoiExplorerPanel panel;

    public RoiEditTabController(WorkflowController controller, WorkflowWindow window,
                                 RoiExplorerPanel panel) {
        this.controller = controller;
        this.window = window;
        this.panel = panel;
    }

    public void openResult(Path resultFolder, ImagePlus boundImage) {
        Path roiRoot = resultFolder.resolve("rois");
        if (!Files.isDirectory(roiRoot)) {
            JOptionPane.showMessageDialog(window,
                "The selected folder does not contain a 'rois' subdirectory.\n" + resultFolder,
                "Invalid Result Folder", JOptionPane.ERROR_MESSAGE);
            return;
        }

        controller.getSession().setResultFolder(resultFolder);
        controller.getSession().setRoiRoot(roiRoot);
        if (boundImage != null) controller.getSession().setBoundImage(boundImage);

        window.setStatus("Loading ROI...");
        controller.setState(WorkflowController.State.LOADING_ROI);

        panel.cleanupPreview();
        ImagePlus img = controller.getSession().getBoundImage();
        if (img != null) panel.setBindImage(img);
        panel.openFolder(roiRoot);

        controller.setState(WorkflowController.State.READY);
        window.setStatus("ROI loaded (" + resultFolder.getFileName() + ").");
        window.refreshRoiEditTab();
    }

    public boolean confirmReplaceIfNeeded() {
        if (!controller.getSession().hasResultFolder()) return true;
        int choice = JOptionPane.showConfirmDialog(window,
            "A result is already loaded in ROI Edit. Replace it?",
            "Replace ROI?", JOptionPane.YES_NO_OPTION);
        return choice == JOptionPane.YES_OPTION;
    }
}
