package org.qainsights.jmeter.ai.pet;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.util.function.Supplier;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.apache.jmeter.gui.GuiPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time wiring for the JMeter pet. Reads {@link PetConfig} from JMeter properties,
 * loads the configured pet's spritesheet, registers the test monitor, and installs the
 * overlay on the JMeter main frame once it exists. Any problem (feature disabled,
 * headless, missing spritesheet) quietly skips the pet - it must never break startup.
 */
public final class PetBootstrap {
    private static final Logger log = LoggerFactory.getLogger(PetBootstrap.class);

    static final int INSTALL_RETRY_DELAY_MS = 1000;
    static final int MAX_INSTALL_ATTEMPTS = 120;

    private static boolean initialized;

    private PetBootstrap() {
    }

    /** Idempotent entry point, called once from the plugin's menu creator. */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            start(PetConfig.fromJMeterProperties(), PetSpriteSheet::load);
        } catch (RuntimeException e) {
            log.warn("Pet initialization failed: {}", e.toString());
        }
    }

    /** Resets the idempotency guard; test use only. */
    static synchronized void resetForTest() {
        initialized = false;
    }

    /** Loads a pet spritesheet by name; indirection point for tests. */
    interface SheetLoader {
        PetSpriteSheet load(String petName) throws IOException;
    }

    /**
     * Wires up the pet for the given configuration. Returns {@code true} when the pet
     * was actually started.
     */
    static boolean start(PetConfig config, SheetLoader sheetLoader) {
        if (!config.isEnabled()) {
            return false;
        }
        if (GraphicsEnvironment.isHeadless()) {
            log.info("Pet is enabled but the environment is headless; skipping.");
            return false;
        }
        PetSpriteSheet sheet;
        try {
            sheet = sheetLoader.load(config.getPetName());
        } catch (IOException e) {
            log.warn("Pet '{}' could not be loaded: {}", config.getPetName(), e.toString());
            return false;
        }
        PetAnimator animator = new PetAnimator(sheet::frameCount);
        PetSampleTap sampleTap = new PetSampleTap(animator::onSampleFailure);
        PetTestMonitor monitor = new PetTestMonitor(animator, sampleTap, PetSampleTap::findListenersInGuiTree);
        monitor.register();
        PetView view = new PetView(sheet, animator, config.getScale());
        scheduleInstall(new PetOverlay(view), PetBootstrap::findMainFrameLayeredPane, 0);
        log.info("Pet '{}' is on its way to the bottom-right corner.", config.getPetName());
        return true;
    }

    /** The JMeter main frame's layered pane, or null while the GUI is still starting. */
    static JLayeredPane findMainFrameLayeredPane() {
        GuiPackage gui = GuiPackage.getInstance();
        JFrame mainFrame = gui == null ? null : gui.getMainFrame();
        return mainFrame == null ? null : mainFrame.getLayeredPane();
    }

    /**
     * Installs the overlay on the EDT as soon as the pane source yields a pane,
     * retrying every {@link #INSTALL_RETRY_DELAY_MS} while the GUI finishes starting.
     */
    static void scheduleInstall(PetOverlay overlay, Supplier<JLayeredPane> paneSource, int attempt) {
        SwingUtilities.invokeLater(() -> {
            JLayeredPane pane = paneSource.get();
            if (pane != null) {
                overlay.install(pane);
            } else if (attempt < MAX_INSTALL_ATTEMPTS) {
                Timer retry = new Timer(INSTALL_RETRY_DELAY_MS,
                        e -> scheduleInstall(overlay, paneSource, attempt + 1));
                retry.setRepeats(false);
                retry.start();
            } else {
                log.warn("Pet gave up waiting for the JMeter main frame.");
            }
        });
    }
}
