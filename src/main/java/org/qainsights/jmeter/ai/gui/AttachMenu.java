package org.qainsights.jmeter.ai.gui;

import java.awt.Component;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.JFileChooser;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.apache.jmeter.util.JMeterUtils;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The paperclip popup menu: quick-attach shortcuts for the files performance
 * engineers attach most - any file via a chooser, the current jmeter.log, or a
 * recent results (.jtl/.csv) file, pre-pointed at the JMeter bin directory.
 */
class AttachMenu extends JPopupMenu {

    private static final Logger log = LoggerFactory.getLogger(AttachMenu.class);

    private final Component parent;
    private final AttachmentBar attachmentBar;

    AttachMenu(Component parent, AttachmentBar attachmentBar) {
        this.parent = parent;
        this.attachmentBar = attachmentBar;
        setBackground(ThemeColors.elevatedSurface());
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeColors.separator()),
                BorderFactory.createEmptyBorder(
                        UiTokens.SPACE_1, UiTokens.SPACE_1,
                        UiTokens.SPACE_1, UiTokens.SPACE_1)));

        JMenuItem browse = new JMenuItem("Attach file…");
        browse.addActionListener(e -> browseForFile(null));
        add(browse);

        JMenuItem jmeterLog = new JMenuItem("Attach jmeter.log");
        jmeterLog.addActionListener(e -> attachJMeterLog());
        add(jmeterLog);

        JMenuItem results = new JMenuItem("Attach recent results…");
        results.addActionListener(e -> browseForFile(binDirectory()));
        add(results);
    }

    /** Opens a chooser (optionally pre-pointed at a directory) and attaches the pick. */
    private void browseForFile(File directory) {
        JFileChooser chooser = directory == null
                ? new JFileChooser()
                : new JFileChooser(directory);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Logs and results (*.log, *.jtl, *.csv, *.txt)", "log", "jtl", "csv", "txt"));
        if (directory == null) {
            chooser.removeChoosableFileFilter(chooser.getFileFilter());
        }
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            attachmentBar.addFileAsync(chooser.getSelectedFile());
        }
    }

    /** Attaches the running JMeter's log file, resolved from the install/bin directory. */
    private void attachJMeterLog() {
        File logFile = resolveJMeterLog();
        if (logFile == null) {
            log.warn("jmeter.log not found in the usual locations");
            attachmentBar.addFileAsync(new File("jmeter.log")); // lets the bar show the proper error
            return;
        }
        attachmentBar.addFileAsync(logFile);
    }

    /** Finds jmeter.log: <home>/bin, <home>, then the working directory. */
    static File resolveJMeterLog() {
        String home = JMeterUtils.getJMeterHome();
        if (home != null) {
            File inBin = new File(new File(home, "bin"), "jmeter.log");
            if (inBin.isFile()) {
                return inBin;
            }
            File inHome = new File(home, "jmeter.log");
            if (inHome.isFile()) {
                return inHome;
            }
        }
        File inCwd = new File("jmeter.log");
        return inCwd.isFile() ? inCwd : null;
    }

    /** The JMeter bin directory (results files typically land here), or null. */
    static File binDirectory() {
        String home = JMeterUtils.getJMeterHome();
        if (home == null) {
            return null;
        }
        File bin = new File(home, "bin");
        return bin.isDirectory() ? bin : null;
    }
}
