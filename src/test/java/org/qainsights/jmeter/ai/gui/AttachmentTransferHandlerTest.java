package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.TransferHandler;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AttachmentTransferHandler}: file-list extraction from
 * drops/pastes and graceful handling of non-file transfers.
 */
class AttachmentTransferHandlerTest {

    private static Transferable fileListTransferable(List<?> files) {
        return new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[]{DataFlavor.javaFileListFlavor};
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor flavor) {
                return DataFlavor.javaFileListFlavor.equals(flavor);
            }

            @Override
            public Object getTransferData(DataFlavor flavor) {
                return files;
            }
        };
    }

    private static Transferable stringTransferable() {
        return new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[]{DataFlavor.stringFlavor};
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor flavor) {
                return DataFlavor.stringFlavor.equals(flavor);
            }

            @Override
            public Object getTransferData(DataFlavor flavor) {
                return "plain text";
            }
        };
    }

    @Test
    void importFilesFeedsEachFileToConsumer() {
        List<File> consumed = new ArrayList<>();
        AttachmentTransferHandler handler = new AttachmentTransferHandler(null, consumed::add);
        File a = new File("a.txt");
        File b = new File("b.txt");

        assertTrue(handler.importFiles(fileListTransferable(List.of(a, b))));
        assertEquals(List.of(a, b), consumed);
    }

    @Test
    void importFilesReturnsFalseForNonFiles() {
        List<File> consumed = new ArrayList<>();
        AttachmentTransferHandler handler = new AttachmentTransferHandler(null, consumed::add);
        assertFalse(handler.importFiles(stringTransferable()));
        assertTrue(consumed.isEmpty());
    }

    @Test
    void importFilesSkipsNonFileItems() {
        List<File> consumed = new ArrayList<>();
        AttachmentTransferHandler handler = new AttachmentTransferHandler(null, consumed::add);
        File real = new File("real.txt");
        List<Object> mixed = new ArrayList<>();
        mixed.add(real);
        mixed.add("not-a-file");
        assertTrue(handler.importFiles(fileListTransferable(mixed)));
        assertEquals(List.of(real), consumed);
    }

    @Test
    void canImportAcceptsFileListsWithoutDelegate() {
        AttachmentTransferHandler handler = new AttachmentTransferHandler(null, f -> {});
        TransferHandler.TransferSupport support = new TransferHandler.TransferSupport(
                new javax.swing.JTextArea(), fileListTransferable(List.of()));
        assertTrue(handler.canImport(support));
    }

    @Test
    void canImportFallsBackToDelegateForNonFileFlavors() {
        TransferHandler accepting = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return true;
            }
        };
        AttachmentTransferHandler handler = new AttachmentTransferHandler(accepting, f -> {});
        assertTrue(handler.canImport(new TransferHandler.TransferSupport(
                new javax.swing.JTextArea(), stringTransferable())));

        AttachmentTransferHandler noDelegate = new AttachmentTransferHandler(null, f -> {});
        assertFalse(noDelegate.canImport(new TransferHandler.TransferSupport(
                new javax.swing.JTextArea(), stringTransferable())));
    }

    @Test
    void importDataRoutesFileListsToConsumer() {
        List<File> consumed = new ArrayList<>();
        AttachmentTransferHandler handler = new AttachmentTransferHandler(null, consumed::add);
        File dropped = new File("dropped.txt");
        assertTrue(handler.importData(new TransferHandler.TransferSupport(
                new javax.swing.JTextArea(), fileListTransferable(List.of(dropped)))));
        assertEquals(List.of(dropped), consumed);
    }

    @Test
    void importDataRoutesTextToDelegate() {
        boolean[] delegateCalled = {false};
        TransferHandler delegate = new TransferHandler() {
            @Override
            public boolean importData(TransferSupport support) {
                delegateCalled[0] = true;
                return true;
            }
        };
        AttachmentTransferHandler handler = new AttachmentTransferHandler(delegate, f -> {});
        assertTrue(handler.importData(new TransferHandler.TransferSupport(
                new javax.swing.JTextArea(), stringTransferable())));
        assertTrue(delegateCalled[0], "text paste must reach the original handler");
    }

    @Test
    void importDataReturnsFalseForTextWithoutDelegate() {
        AttachmentTransferHandler handler = new AttachmentTransferHandler(null, f -> {});
        assertFalse(handler.importData(new TransferHandler.TransferSupport(
                new javax.swing.JTextArea(), stringTransferable())));
    }
}
