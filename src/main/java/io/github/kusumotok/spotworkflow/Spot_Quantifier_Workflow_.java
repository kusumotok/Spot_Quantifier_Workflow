package io.github.kusumotok.spotworkflow;

import ij.plugin.PlugIn;

import javax.swing.*;

public class Spot_Quantifier_Workflow_ implements PlugIn {

    @Override
    public void run(String arg) {
        SwingUtilities.invokeLater(() -> {
            WorkflowWindow window = WorkflowWindow.getInstance();
            window.setVisible(true);
            window.toFront();
        });
    }
}
