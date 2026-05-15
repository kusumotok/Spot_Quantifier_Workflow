package io.github.kusumotok.spotworkflow;

import ij.ImagePlus;
import io.github.kusumotok.roiexplorer.ui.RoiExplorerPanel;

final class RoiExplorerPreviewSupport {
    private RoiExplorerPreviewSupport() {}

    static void configureSeedEditPanel(RoiExplorerPanel panel, ImagePlus mainImage, ImagePlus subImage) {
        if (panel == null) return;
        if (mainImage != null) {
            panel.setBindImage(mainImage);
            panel.setContainerOrMode(true);
            panel.setProjectionMode(true, false, false);
        }
        syncSubImage(panel, mainImage, subImage);
    }

    static void activateSeedEditPreview(RoiExplorerPanel panel, SegmentationTab seedTab,
                                        SegmentationTab areaTab, ImagePlus mainImage,
                                        ImagePlus subImage) {
        if (panel == null) return;
        panel.setOverlayEnabled(true);
        if (seedTab != null) {
            seedTab.setPreviewActive(false, false);
            seedTab.clearOverlayOnly();
        }
        if (areaTab != null) {
            areaTab.setPreviewActive(false, false);
            areaTab.clearOverlayOnly();
        }
        configureSeedEditPanel(panel, mainImage, subImage);
        if (panel.hasLoadedRoot()) panel.refreshOverlay();
    }

    static void syncSubImage(RoiExplorerPanel panel, ImagePlus mainImage, ImagePlus subImage) {
        if (panel == null) return;
        if (mainImage == null || subImage == null || subImage == mainImage) {
            panel.clearSubBindImage();
        } else {
            panel.setSubBindImage(subImage);
        }
    }
}
