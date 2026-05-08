package io.github.kusumotok.spotworkflow;

import io.github.kusumotok.roiexplorer.service.RoiExplorerFacade.MeasurementRequest;
import io.github.kusumotok.roiexplorer.service.RoiExplorerFacade.MeasurementResult;
import io.github.kusumotok.roiexplorer.ui.RoiExplorerPanel;

public final class MeasurementController {

    private final RoiExplorerPanel panel;

    public MeasurementController(RoiExplorerPanel panel) {
        this.panel = panel;
    }

    public MeasurementResult measure(MeasurementRequest request) {
        if (!panel.hasLoadedRoot()) {
            return MeasurementResult.notPerformed("No ROI root loaded. Run Make ROI first.");
        }
        return panel.measureCurrentRoot(request);
    }
}
