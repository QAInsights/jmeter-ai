package org.qainsights.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.service.prompts.PromptLibrary;

/**
 * Modal dialog for naming and editing a prompt before saving it to the
 * {@link PromptLibrary}. Opened from the MessageCard "Save prompt" action
 * (new prompts) and the {@code @prompts} picker's manage actions
 * (edit/rename, and "save as copy" for built-ins). Validation and
 * name-derivation rules live in static package-private methods so they are
 * unit-testable without showing a modal dialog.
 */
public final class PromptEditDialog extends JDialog {

    private final PromptLibrary library;
    private final String editingOriginal;
    private final JTextField nameField;
    private final JTextArea bodyArea;
    private final JLabel errorLabel;
    private boolean saved;

    private PromptEditDialog(Window owner, PromptLibrary library, String title,
            String initialName, String initialBody, String editingOriginal) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.library = library;
        this.editingOriginal = editingOriginal;

        JPanel form = new JPanel(new BorderLayout(6, 6));
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        nameField = new JTextField(initialName, 24);
        JPanel nameRow = new JPanel(new BorderLayout(6, 0));
        nameRow.add(new JLabel("Name:"), BorderLayout.WEST);
        nameRow.add(nameField, BorderLayout.CENTER);
        form.add(nameRow, BorderLayout.NORTH);

        bodyArea = new JTextArea(initialBody, 8, 40);
        bodyArea.setLineWrap(true);
        bodyArea.setWrapStyleWord(true);
        form.add(new JScrollPane(bodyArea), BorderLayout.CENTER);

        errorLabel = new JLabel(" ");
        errorLabel.setForeground(ThemeColors.error());
        JPanel south = new JPanel(new BorderLayout());
        south.add(errorLabel, BorderLayout.CENTER);
        JButton save = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        save.addActionListener(e -> onSave());
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(cancel);
        buttons.add(save);
        south.add(buttons, BorderLayout.EAST);
        form.add(south, BorderLayout.SOUTH);

        setContentPane(form);
        pack();
        setLocationRelativeTo(owner);
        getRootPane().setDefaultButton(save);
    }

    /** Opens the "save new prompt" flow, prefilled with a message body. */
    public static PromptEditDialog forNew(Window owner, PromptLibrary library, String initialBody) {
        return new PromptEditDialog(owner, library, "Save prompt", "", initialBody, null);
    }

    /** Opens edit/rename for an existing user prompt. */
    public static PromptEditDialog forEdit(Window owner, PromptLibrary library, PromptLibrary.Prompt prompt) {
        return new PromptEditDialog(owner, library, "Edit prompt",
                prompt.name(), prompt.body(), prompt.name());
    }

    /** Opens "save as copy" for a built-in: body prefilled, name derived and free. */
    public static PromptEditDialog forCopy(Window owner, PromptLibrary library, PromptLibrary.Prompt prompt) {
        return new PromptEditDialog(owner, library, "Save as copy",
                suggestedCopyName(library, prompt.name()), prompt.body(), null);
    }

    /** True when a prompt was saved before the dialog closed. */
    boolean wasSaved() {
        return saved;
    }

    /** Validation message for the given name/body, or null when both are usable. */
    static String validationError(String name, String body) {
        if (name == null || name.isBlank()) {
            return "Name is required.";
        }
        if (PromptLibrary.isBuiltinName(name.trim())) {
            return "That name belongs to a built-in prompt - pick another.";
        }
        if (body == null || body.isBlank()) {
            return "Prompt text is required.";
        }
        return null;
    }

    /**
     * True when saving under this name would overwrite a different existing
     * user prompt (and the dialog should confirm first).
     */
    static boolean wouldOverwrite(PromptLibrary library, String name, String editingOriginal) {
        if (name == null || name.equals(editingOriginal)) {
            return false;
        }
        return library.find(name).map(p -> !p.builtin()).orElse(false);
    }

    /** Derives a free "Name (copy)" / "Name (copy N)" for the save-as-copy flow. */
    static String suggestedCopyName(PromptLibrary library, String baseName) {
        String candidate = baseName + " (copy)";
        int n = 2;
        while (library.find(candidate).isPresent()) {
            candidate = baseName + " (copy " + n++ + ")";
        }
        return candidate;
    }

    /**
     * Persists the edit: saves under the (possibly new) name first and drops
     * the original only after the save succeeded, so a failed write during a
     * rename can never lose the existing prompt. Returns false when the save
     * did not persist; the original is always kept in that case.
     */
    static boolean persistEdit(PromptLibrary library, String name, String body, String editingOriginal) {
        if (!library.save(name, body)) {
            return false;
        }
        if (editingOriginal != null && !editingOriginal.equals(name)) {
            library.delete(editingOriginal);
        }
        return true;
    }

    private void onSave() {
        String name = nameField.getText().trim();
        String body = bodyArea.getText().trim();
        String error = validationError(name, body);
        if (error != null) {
            errorLabel.setText(error);
            return;
        }
        if (wouldOverwrite(library, name, editingOriginal)
                && JOptionPane.showConfirmDialog(this,
                        "A prompt named \"" + name + "\" already exists. Overwrite it?",
                        "Overwrite prompt", JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        if (!persistEdit(library, name, body, editingOriginal)) {
            errorLabel.setText("Could not save the prompt to disk - check the log and try again.");
            return;
        }
        saved = true;
        dispose();
    }
}
