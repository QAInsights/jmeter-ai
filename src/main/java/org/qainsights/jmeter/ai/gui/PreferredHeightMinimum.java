package org.qainsights.jmeter.ai.gui;

import java.awt.Dimension;

final class PreferredHeightMinimum {
    private PreferredHeightMinimum() {
    }

    static Dimension preserve(Dimension minimum, Dimension preferred) {
        return new Dimension(minimum.width, Math.max(minimum.height, preferred.height));
    }
}
