package org.qainsights.jmeter.ai.gui;

import javax.swing.JSplitPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;

/** Utilities for docking side panels beside a container's centre component. */
public final class SidePanelDock {
    private SidePanelDock() {
    }

    public static JSplitPane dock(Container contentPane, Component panel, double resizeWeight, int frameWidth) {
        if (!(contentPane.getLayout() instanceof BorderLayout)) {
            return null;
        }

        BorderLayout layout = (BorderLayout) contentPane.getLayout();
        Component center = layout.getLayoutComponent(BorderLayout.CENTER);
        if (center == null) {
            return null;
        }

        contentPane.remove(center);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, panel);
        split.setResizeWeight(resizeWeight);
        split.setOneTouchExpandable(true);
        split.setContinuousLayout(true);
        split.setDividerLocation(frameWidth - panel.getPreferredSize().width - 10);
        contentPane.add(split, BorderLayout.CENTER);
        return split;
    }

    public static void undock(JSplitPane split) {
        Container parent = split.getParent();
        Component main = split.getLeftComponent();
        if (parent == null || main == null) {
            return;
        }

        if (parent instanceof JSplitPane) {
            JSplitPane outer = (JSplitPane) parent;
            int dividerLocation = outer.getDividerLocation();
            if (outer.getLeftComponent() == split) {
                outer.setLeftComponent(main);
            } else if (outer.getRightComponent() == split) {
                outer.setRightComponent(main);
            }
            outer.setDividerLocation(dividerLocation);
        } else {
            Object constraint = null;
            if (parent.getLayout() instanceof BorderLayout) {
                constraint = ((BorderLayout) parent.getLayout()).getConstraints(split);
            }
            parent.remove(split);
            parent.add(main, constraint != null ? constraint : BorderLayout.CENTER);
        }

        parent.revalidate();
        parent.repaint();
    }
}
