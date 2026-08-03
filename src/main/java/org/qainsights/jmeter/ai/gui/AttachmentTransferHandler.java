package org.qainsights.jmeter.ai.gui;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.TransferHandler;

/**
 * {@link TransferHandler} for the message field that turns file drops and
 * clipboard file pastes into attachments (via the {@link AttachmentBar}),
 * while delegating every other flavor (notably plain text) to the field's
 * original handler so normal paste keeps working.
 */
class AttachmentTransferHandler extends TransferHandler {

    private final TransferHandler delegate;
    private final Consumer<File> fileConsumer;

    AttachmentTransferHandler(TransferHandler delegate, Consumer<File> fileConsumer) {
        this.delegate = delegate;
        this.fileConsumer = fileConsumer;
    }

    @Override
    public boolean canImport(TransferSupport support) {
        return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                || (delegate != null && delegate.canImport(support));
    }

    @Override
    public boolean importData(TransferSupport support) {
        Transferable transferable = support.getTransferable();
        if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            return importFiles(transferable);
        }
        return delegate != null && delegate.importData(support);
    }

    /** Extracts the file list from a drop/paste and feeds each file to the consumer. */
    boolean importFiles(Transferable transferable) {
        try {
            Object data = transferable.getTransferData(DataFlavor.javaFileListFlavor);
            if (data instanceof List<?>) {
                int accepted = 0;
                for (Object item : (List<?>) data) {
                    if (item instanceof File) {
                        fileConsumer.accept((File) item);
                        accepted++;
                    }
                }
                return accepted > 0;
            }
        } catch (Exception e) {
            // fall through: not a file list
        }
        return false;
    }
}
