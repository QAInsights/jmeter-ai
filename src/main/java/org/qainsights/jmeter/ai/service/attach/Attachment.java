package org.qainsights.jmeter.ai.service.attach;

/**
 * One attached file: its identity (id, name, size), the user's chosen
 * processing mode (smart/raw), and the prepared content that gets inlined
 * into prompts. The {@link #marker()} string is what travels inside the user
 * message through conversation history; the prepared content is substituted
 * back in at request-build time via the {@link AttachmentRegistry}.
 */
public final class Attachment {

    private final String id;
    private final String fileName;
    private final String rawContent;
    private final long sizeBytes;
    private FileContentPreparer.Mode mode;
    private String preparedContent;

    public Attachment(String id, String fileName, String rawContent, FileContentPreparer.Mode mode) {
        this.id = id;
        this.fileName = fileName == null ? "file" : fileName;
        this.rawContent = rawContent == null ? "" : rawContent;
        this.sizeBytes = this.rawContent.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        setMode(mode);
    }

    public String getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public FileContentPreparer.Mode getMode() {
        return mode;
    }

    /** Switches the processing mode and re-prepares the content. */
    public void setMode(FileContentPreparer.Mode mode) {
        this.mode = mode == null ? FileContentPreparer.defaultMode() : mode;
        this.preparedContent = FileContentPreparer.prepare(fileName, rawContent, this.mode);
    }

    /** The processed text to inline into a request (already wrapped in {@code <attached>}). */
    public String getPreparedContent() {
        return preparedContent;
    }

    /** The original file content (preparation re-runs from this on mode change or restore). */
    public String getRawContent() {
        return rawContent;
    }

    /** The marker referencing this attachment inside a message string. */
    public String marker() {
        return "[file:" + id + "]";
    }

    /** Human-readable chip label: name + size + mode. */
    public String chipLabel() {
        return fileName + " · " + formatSize(sizeBytes) + " · " + modeLabel();
    }

    private String modeLabel() {
        return mode == FileContentPreparer.Mode.RAW ? "raw" : "smart";
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
