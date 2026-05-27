package org.qainsights.jmeter.ai.correlation;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class CorrelationMenuItem extends JMenuItem implements ActionListener {

    public CorrelationMenuItem() {
        super("Correlation Studio");
        addActionListener(this);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        Frame frame = (Frame) SwingUtilities.getWindowAncestor(this);
        CorrelationReviewDialog dialog = new CorrelationReviewDialog(frame);
        dialog.setVisible(true);
    }
}
