package io.github.kusumotok.spotworkflow;

import ij.plugin.PlugIn;

import javax.swing.SwingUtilities;

public class Spot_Quantifier_Time_Series_ implements PlugIn {
    @Override public void run(String arg) {
        SwingUtilities.invokeLater(() -> {
            TimeSeriesWorkflowWindow window = TimeSeriesWorkflowWindow.getInstance();
            window.setVisible(true);
            window.toFront();
        });
    }
}
