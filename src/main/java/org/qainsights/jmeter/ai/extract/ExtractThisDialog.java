package org.qainsights.jmeter.ai.extract;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Preview of the checked extractors; the user picks one, names the variable and clicks Add. */
final class ExtractThisDialog extends JDialog {

    static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** What the user chose. */
    static final class Choice {
        final ExtractorSuggestion suggestion;
        final String variableName;
        final boolean replaceLaterUses;

        Choice(ExtractorSuggestion suggestion, String variableName, boolean replaceLaterUses) {
            this.suggestion = suggestion;
            this.variableName = variableName;
            this.replaceLaterUses = replaceLaterUses;
        }
    }

    private Choice choice;

    private ExtractThisDialog(Window owner, String value, String samplerName,
            List<ExtractorSuggestion> suggestions, List<String> laterUseNames) {
        super(owner, "Extract this", ModalityType.APPLICATION_MODAL);
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));

        JLabel title = new JLabel("Extract " + quote(abbreviate(value, 48)) + " from " + quote(samplerName));
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 1f));
        JLabel subtitle = new JLabel("Each option below was run against the real response and returns exactly this value.");
        subtitle.setForeground(ThemeColors.secondaryText());
        JPanel header = column(title, subtitle);
        content.add(header, BorderLayout.NORTH);

        JTextField variable = new JTextField(suggestions.get(0).variableName(), 22);
        JCheckBox replace = new JCheckBox();
        boolean[] edited = {false};

        ButtonGroup group = new ButtonGroup();
        List<JRadioButton> radios = new ArrayList<>();
        JPanel options = new JPanel();
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        for (ExtractorSuggestion s : suggestions) {
            JRadioButton radio = new JRadioButton("<html><b>" + html(s.type().label()) + "</b>&nbsp;&nbsp;<code>"
                    + html(abbreviate(s.describe(), 90)) + "</code></html>");
            radio.addActionListener(e -> {
                if (!edited[0]) {
                    variable.setText(s.variableName());
                    edited[0] = false;
                }
            });
            JLabel check = new JLabel("\u2714 returns " + quote(abbreviate(value, 40)));
            check.setForeground(ThemeColors.success());
            check.setBorder(BorderFactory.createEmptyBorder(0, 26, 6, 0));
            group.add(radio);
            radios.add(radio);
            options.add(column(radio, check));
        }
        radios.get(0).setSelected(true);
        content.add(options, BorderLayout.CENTER);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        nameRow.add(new JLabel("Variable name  "));
        nameRow.add(variable);
        JLabel error = new JLabel(" ");
        error.setForeground(ThemeColors.error());
        form.add(nameRow);
        form.add(error);
        if (!laterUseNames.isEmpty()) {
            Runnable relabel = () -> replace.setText("Also use ${" + variable.getText().trim() + "} in "
                    + laterUseNames.size() + (laterUseNames.size() == 1 ? " later element" : " later elements")
                    + " that hard-code this value: " + abbreviate(String.join(", ", laterUseNames), 70));
            relabel.run();
            variable.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { relabel.run(); }
                public void removeUpdate(DocumentEvent e) { relabel.run(); }
                public void changedUpdate(DocumentEvent e) { relabel.run(); }
            });
            replace.setToolTipText(String.join(", ", laterUseNames));
            form.add(replace);
        }
        variable.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyTyped(java.awt.event.KeyEvent e) {
                edited[0] = true;
            }
        });

        JButton add = new JButton("Add under " + abbreviate(samplerName, 30));
        JButton cancel = new JButton("Cancel");
        add.addActionListener(e -> {
            String name = variable.getText().trim();
            if (!VARIABLE.matcher(name).matches()) {
                error.setText("Use letters, digits and _ only, starting with a letter or _.");
                return;
            }
            ExtractorSuggestion picked = suggestions.get(0);
            for (int i = 0; i < radios.size(); i++) {
                if (radios.get(i).isSelected()) {
                    picked = suggestions.get(i);
                }
            }
            choice = new Choice(picked, name, replace.isSelected());
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(cancel);
        buttons.add(add);
        JPanel south = new JPanel(new BorderLayout(0, 10));
        south.add(form, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.SOUTH);
        content.add(south, BorderLayout.SOUTH);

        setContentPane(content);
        getRootPane().setDefaultButton(add);
        pack();
        setLocationRelativeTo(owner);
    }

    static Choice show(Component parent, String value, String samplerName,
            List<ExtractorSuggestion> suggestions, List<String> laterUseNames) {
        Window owner = parent == null ? null : javax.swing.SwingUtilities.getWindowAncestor(parent);
        ExtractThisDialog d = new ExtractThisDialog(owner, value, samplerName, suggestions, laterUseNames);
        d.setVisible(true);
        return d.choice;
    }

    private static JPanel column(Component... parts) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        for (Component c : parts) {
            ((javax.swing.JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(c);
        }
        p.add(Box.createVerticalStrut(2));
        return p;
    }

    static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static String quote(String s) {
        return "\u201C" + s + "\u201D";
    }

    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
