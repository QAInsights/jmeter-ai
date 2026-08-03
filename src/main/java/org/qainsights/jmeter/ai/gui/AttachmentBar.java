package org.qainsights.jmeter.ai.gui;

import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.service.attach.Attachment;
import org.qainsights.jmeter.ai.service.attach.AttachmentRegistry;
import org.qainsights.jmeter.ai.service.attach.FileContentPreparer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Horizontal strip of pending-attachment chips shown above the message field.
 * Each chip shows the file name, size and processing mode; clicking a chip
 * cycles smart/raw (re-preparing the content), the × removes it. On send,
 * {@link #consumeMarkers()} hands the {@code [file:<id>]} markers to the
 * message and clears the strip. Hidden while empty.
 */
class AttachmentBar extends JPanel {

    /** Files larger than this are rejected (they're for the smart digests anyway). */
    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(AttachmentBar.class);

    private final AttachmentRegistry registry;
    private final Consumer<String> systemMessage;
    private final List<JPanel> chips = new ArrayList<>();

    AttachmentBar(AttachmentRegistry registry, Consumer<String> systemMessage) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 2));
        this.registry = registry;
        this.systemMessage = systemMessage;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        setVisible(false);
    }

    /**
     * Attaches a file from disk: reads it, registers it in the given mode
     * (property default), and adds a chip. Failures are reported via the
     * system-message consumer instead of throwing. Synchronous - for tests;
     * UI paths use {@link #addFileAsync} so the EDT never blocks on I/O.
     *
     * @return the created attachment, or null when rejected
     */
    Attachment addFile(File file) {
        if (!validate(file)) {
            return null;
        }
        try {
            String content = readCapped(file);
            Attachment attachment = registry.register(file.getName(), content, null);
            addChip(attachment);
            return attachment;
        } catch (Exception e) {
            log.warn("Failed to attach {}", file, e);
            systemMessage.accept("Couldn't read " + file.getName() + " as text (" + e.getMessage() + ").");
            return null;
        }
    }

    /**
     * EDT-safe variant of {@link #addFile}: cheap rejections (missing file,
     * oversize, cap) are reported synchronously; the actual read and register
     * happen on a background thread, with the chip added back on the EDT.
     */
    void addFileAsync(File file) {
        if (!validate(file)) {
            return;
        }
        new javax.swing.SwingWorker<Attachment, Void>() {
            @Override
            protected Attachment doInBackground() throws Exception {
                String content = readCapped(file);
                return registry.register(file.getName(), content, null);
            }

            @Override
            protected void done() {
                try {
                    addChip(get());
                } catch (java.util.concurrent.ExecutionException e) {
                    log.warn("Failed to attach {}", file, e.getCause());
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    systemMessage.accept("Couldn't read " + file.getName() + " as text ("
                            + cause.getMessage() + ").");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    /**
     * Reads at most {@link #MAX_FILE_BYTES} + 1 bytes and decodes them as
     * strict UTF-8. Reading is capped so a file that grows between the cheap
     * pre-check and the read (TOCTOU) can never balloon memory, and the size
     * is re-verified on the bytes actually read; strict decoding keeps
     * binary files rejected with a friendly message.
     */
    static String readCapped(File file) throws java.io.IOException {
        try (java.io.InputStream in = Files.newInputStream(file.toPath())) {
            byte[] bytes = in.readNBytes((int) MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new java.io.IOException("file exceeds 10 MB (re-verified at read time)");
            }
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes))
                        .toString();
            } catch (java.nio.charset.CharacterCodingException e) {
                throw new java.io.IOException("not valid UTF-8 text", e);
            }
        }
    }

    /** Cheap pre-read checks (no I/O beyond file.length()); reports and returns false on rejection. */
    private boolean validate(File file) {
        if (file == null || !file.isFile()) {
            systemMessage.accept("Couldn't read that file.");
            return false;
        }
        if (file.length() > MAX_FILE_BYTES) {
            systemMessage.accept(file.getName() + " is larger than 10 MB - attach a smaller excerpt instead.");
            return false;
        }
        if (!registry.canAddMore()) {
            systemMessage.accept("Attachment limit reached (" + AttachmentRegistry.maxCount() + " per message).");
            return false;
        }
        return true;
    }

    /** True when at least one attachment is pending. */
    boolean hasAttachments() {
        return !chips.isEmpty();
    }

    /**
     * Returns the marker string for this message's pending attachments (e.g.
     * {@code " [file:f1] [file:f2]"}), then clears the strip. The registry keeps
     * the prepared content so request-build time can resolve the markers, but
     * previously sent messages' attachments are NOT re-emitted.
     */
    String consumeMarkers() {
        StringBuilder sb = new StringBuilder();
        for (Attachment attachment : registry.consumePending()) {
            sb.append(' ').append(attachment.marker());
        }
        clear();
        return sb.toString();
    }

    /** Removes all chips (attachments stay in the registry unless cleared there). */
    void clear() {
        chips.clear();
        removeAll();
        setVisible(false);
        revalidate();
        repaint();
    }

    /** Number of visible chips (for tests). */
    int getChipCount() {
        return chips.size();
    }

    void addChip(Attachment attachment) {
        JPanel chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        chip.setOpaque(true);
        chip.setBackground(ThemeColors.codeBackground());
        chip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeColors.border(), 1, true),
                BorderFactory.createEmptyBorder(1, 6, 1, 4)));

        // File names come from disk - never render them as HTML.
        JLabel label = LabelUtils.plain(attachment.chipLabel(), AttachIcons.document(12), JLabel.LEFT);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.setToolTipText("Click to switch smart/raw processing");
        label.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                attachment.setMode(attachment.getMode() == FileContentPreparer.Mode.SMART
                        ? FileContentPreparer.Mode.RAW : FileContentPreparer.Mode.SMART);
                label.setText(attachment.chipLabel());
            }
        });
        chip.add(label);

        JLabel remove = new JLabel("×");
        remove.setFont(remove.getFont().deriveFont(Font.BOLD, 12f));
        remove.setForeground(ThemeColors.secondaryText());
        remove.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        remove.setToolTipText("Remove attachment");
        remove.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                registry.remove(attachment.getId());
                chips.remove(chip);
                remove(chip);
                if (chips.isEmpty()) {
                    setVisible(false);
                }
                revalidate();
                repaint();
            }
        });
        chip.add(remove);

        chips.add(chip);
        add(chip);
        setVisible(true);
        revalidate();
        repaint();
    }
}
