package org.qainsights.jmeter.ai.gui;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowFocusListener;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.qainsights.jmeter.ai.cli.SubscriptionCliProvider;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;
import org.qainsights.jmeter.ai.service.prefs.ModelSelectorPreferences;
import org.qainsights.jmeter.ai.service.reasoning.ModelCapabilityCatalog;

/**
 * Dropdown-style model picker docked to the selector button (opening upward
 * towards the chat when that side has more room - the button sits at the
 * bottom of the chat panel): a search field on top and a metadata-rich list
 * below (two-line rows via
 * {@link ModelPickerRenderer}). With an empty filter the list is ordered
 * pinned → recently used → everything else; typing filters on display name,
 * provider, or raw id. Clicking a row's star zone toggles its pin (persisted
 * immediately); Enter or double-click confirms, Esc or clicking elsewhere
 * cancels. Implemented as an undecorated, non-modal {@link JDialog} owned by
 * the main window: a {@link JWindow} cannot take keyboard focus on the JDK
 * (probe-verified: {@code isFocusableWindow()} is false even owned and
 * showing), which would leave the search field untypable.
 */
class ModelPickerPopup extends JDialog {

    /** Width of the clickable star zone at the left of each row (pixels). */
    static final int STAR_ZONE_WIDTH = 26;

    private static final int MIN_WIDTH = 340;

    /** Desired popup height before capping to available space (visible for tests). */
    static final int HEIGHT = 360;

    private final List<String> allModels;
    private String pendingCustomModel;
    private final ModelSelectorPreferences prefs;
    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JList<String> modelList;
    private final JScrollPane modelScroll;
    private final JTextField filterField;
    private final Consumer<List<String>> onModelsDiscovered;
    private final AWTEventListener outsideClickListener = this::handleAwtEvent;
    private Consumer<String> onSelect = model -> { };
    private boolean outsideClickListenerInstalled;

    ModelPickerPopup(java.awt.Window owner, List<String> models, String currentModel,
                     ModelSelectorPreferences prefs, ModelCapabilityCatalog catalog) {
        this(owner, models, currentModel, prefs, catalog, List.of());
    }

    /**
     * @param cliProviders subscription CLI providers (Codex, Claude Code) whose
     *                     sign-in state and actions are shown in the footer;
     *                     empty hides the footer entirely
     */
    ModelPickerPopup(java.awt.Window owner, List<String> models, String currentModel,
                     ModelSelectorPreferences prefs, ModelCapabilityCatalog catalog,
                     List<SubscriptionCliProvider> cliProviders) {
        this(owner, models, currentModel, prefs, catalog, cliProviders, discovered -> { });
    }

    ModelPickerPopup(java.awt.Window owner, List<String> models, String currentModel,
                     ModelSelectorPreferences prefs, ModelCapabilityCatalog catalog,
                     List<SubscriptionCliProvider> cliProviders,
                     Consumer<List<String>> onModelsDiscovered) {
        super(owner); // owned, so it stays out of the taskbar and shares the owner's focus cycle
        setUndecorated(true);
        setFocusableWindowState(true);
        this.allModels = new ArrayList<>(models);
        this.prefs = prefs;
        this.onModelsDiscovered = onModelsDiscovered;

        filterField = new JTextField();
        filterField.putClientProperty("JTextField.placeholderText", "Search models and providers");
        filterField.getAccessibleContext().setAccessibleName("Search models and providers");
        filterField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeColors.separator(), 1, true),
                BorderFactory.createEmptyBorder(
                        UiTokens.SPACE_2, UiTokens.SPACE_3,
                        UiTokens.SPACE_2, UiTokens.SPACE_3)));
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refresh();
            }
        });
        filterField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    confirm();
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN && listModel.getSize() > 0) {
                    modelList.requestFocusInWindow();
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    dispose();
                }
            }
        });

        modelList = new JList<>(listModel) {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return true;
            }
        };
        modelList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        modelList.setSelectionBackground(ThemeColors.selectedBackground());
        modelList.setSelectionForeground(ThemeColors.foreground());
        modelList.setCellRenderer(new ModelPickerRenderer(prefs, catalog));
        modelList.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    confirm();
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    dispose();
                }
            }
        });
        modelList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = modelList.locationToIndex(e.getPoint());
                if (index < 0) {
                    return;
                }
                if (e.getX() <= STAR_ZONE_WIDTH) {
                    prefs.togglePinned(listModel.get(index));
                    refresh();
                } else if (e.getClickCount() == 2) {
                    confirm();
                }
            }
        });

        getContentPane().setLayout(new BorderLayout(0, UiTokens.SPACE_2));
        getContentPane().setBackground(ThemeColors.elevatedSurface());
        getContentPane().add(filterField, BorderLayout.NORTH);
        modelScroll = new JScrollPane(modelList);
        modelScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        modelScroll.setBorder(BorderFactory.createLineBorder(ThemeColors.separator()));
        modelScroll.getViewport().setBackground(ThemeColors.elevatedSurface());
        getContentPane().add(modelScroll, BorderLayout.CENTER);
        if (!cliProviders.isEmpty()) {
            getContentPane().add(new CliProviderStatusPanel(cliProviders, this::promptForCustomModel,
                    this::providerRefreshed), BorderLayout.SOUTH);
        }
        getRootPane().setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeColors.separator()),
                BorderFactory.createEmptyBorder(
                        UiTokens.SPACE_2, UiTokens.SPACE_2,
                        UiTokens.SPACE_2, UiTokens.SPACE_2)));

        addWindowFocusListener(new WindowFocusListener() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
            }

            @Override
            public void windowLostFocus(WindowEvent e) {
                SwingUtilities.invokeLater(() -> {
                    Window ownerWindow = getOwner();
                    if (isDisplayable() && !isActive()
                            && (ownerWindow == null || !ownerWindow.isActive())) {
                        dispose();
                    }
                });
            }
        });

        refresh();
        selectVisible(currentModel);
    }

    private void providerRefreshed(SubscriptionCliProvider provider) {
        List<String> discovered = new ArrayList<>();
        boolean changed = false;
        for (String model : provider.listModels()) {
            String selectorId = provider.modelPrefix() + model;
            discovered.add(selectorId);
            if (!allModels.contains(selectorId)) {
                allModels.add(selectorId);
                changed = true;
            }
        }
        if (changed) {
            refresh();
        }
        onModelsDiscovered.accept(discovered);
    }

    /**
     * Asks for a model id for {@code provider}. The prompt is parented on this
     * popup's owner and the popup is hidden first: a dialog owned by the popup
     * would be disposed together with it the moment it took focus, so the
     * dialog would never appear.
     */
    private void promptForCustomModel(SubscriptionCliProvider provider) {
        Window owner = getOwner();
        setVisible(false);
        String id = JOptionPane.showInputDialog(owner,
                "Model id to send to the " + provider.displayName() + " CLI:",
                provider.displayName() + " custom model", JOptionPane.PLAIN_MESSAGE);
        if (id == null || id.isBlank()) {
            dispose();
            return;
        }
        useCustomModel(provider.modelPrefix() + id.trim());
    }

    /**
     * Adopts a model id typed into the footer: it is remembered in the
     * preferences (so it survives restarts and shows up in the list next time)
     * and selected straight away.
     */
    void useCustomModel(String modelId) {
        prefs.addCustomModel(modelId);
        if (!allModels.contains(modelId)) {
            allModels.add(modelId);
        }
        pendingCustomModel = modelId;
        filterField.setText("");
        refresh();
        selectVisible(modelId);
        confirm();
    }

    /** The id adopted by the last "Custom model…" action (visible for tests). */
    String lastCustomModel() {
        return pendingCustomModel;
    }

    /**
     * Filters and orders the candidate models: pinned first (pin order), then
     * recently used (most-recent first), then the rest alphabetically by
     * display name. The filter matches the raw id, the display name, or the
     * provider (case-insensitive substring); an empty filter keeps everything.
     */
    static List<String> filterAndSort(List<String> models, String filter,
                                      ModelSelectorPreferences prefs) {
        String needle = filter == null ? "" : filter.trim().toLowerCase(java.util.Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String model : models) {
            if (needle.isEmpty() || matches(model, needle)) {
                matches.add(model);
            }
        }
        List<String> pinned = prefs.pinned();
        List<String> recents = prefs.recents();
        matches.sort(curationOrder(pinned, recents));
        return matches;
    }

    /**
     * The product rule for row order: pinned first (pin order), then recently
     * used (most-recent first), then everything else by display name.
     */
    private static Comparator<String> curationOrder(List<String> pinned, List<String> recents) {
        return Comparator
                .comparingInt((String m) -> pinned.contains(m) ? 0 : recents.contains(m) ? 1 : 2)
                .thenComparingInt(m -> positionIn(m, pinned, recents))
                .thenComparing(m -> ModelDisplay.parse(m)[0], String.CASE_INSENSITIVE_ORDER);
    }

    private static int positionIn(String model, List<String> pinned, List<String> recents) {
        int pin = pinned.indexOf(model);
        return pin >= 0 ? pin : Math.max(recents.indexOf(model), 0);
    }

    private static boolean matches(String model, String needle) {
        String[] parts = ModelDisplay.parse(model);
        return model.toLowerCase(java.util.Locale.ROOT).contains(needle)
                || parts[0].toLowerCase(java.util.Locale.ROOT).contains(needle)
                || parts[1].toLowerCase(java.util.Locale.ROOT).contains(needle);
    }

    /** Where the popup lands relative to the anchor (visible for tests). */
    record Placement(int y, int height, boolean above) {
    }

    /**
     * Decides whether the popup opens above or below the anchor and how tall
     * it gets. The selector button sits at the bottom of the chat panel, so
     * opening upward (towards the transcript) is preferred; it only flips
     * downward when more room is available there. Height is capped to the
     * available space.
     */
    static Placement verticalPlacement(int anchorY, int anchorHeight, int desiredHeight,
                                       int screenTop, int screenBottom) {
        int spaceAbove = anchorY - screenTop;
        int spaceBelow = screenBottom - anchorY - anchorHeight;
        boolean above = spaceAbove >= spaceBelow;
        int height = Math.min(desiredHeight, Math.max(spaceAbove, spaceBelow));
        return above
                ? new Placement(anchorY - height, height, true)
                : new Placement(anchorY + anchorHeight, height, false);
    }

    static int popupWidth(int anchorWidth, int availableWidth) {
        return Math.min(Math.max(anchorWidth, MIN_WIDTH), Math.max(1, availableWidth));
    }

    /** Shows the popup docked to the anchor; {@code onSelect} fires on confirm. */
    void showFor(Component anchor, Consumer<String> onSelect) {
        this.onSelect = onSelect != null ? onSelect : model -> { };
        Point location = anchor.getLocationOnScreen();
        java.awt.GraphicsConfiguration gc = anchor.getGraphicsConfiguration();
        java.awt.Rectangle screen = gc != null
                ? gc.getBounds()
                : new java.awt.Rectangle(0, 0, 1920, 1080);
        java.awt.Insets insets = gc != null
                ? java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
                : new java.awt.Insets(0, 0, 0, 0);
        Placement placement = verticalPlacement(location.y, anchor.getHeight(), HEIGHT,
                screen.y + insets.top, screen.y + screen.height - insets.bottom);
        int screenLeft = screen.x + insets.left;
        int screenRight = screen.x + screen.width - insets.right;
        int popupWidth = popupWidth(anchor.getWidth(), screenRight - screenLeft);
        int popupX = Math.max(screenLeft, Math.min(location.x, screenRight - popupWidth));
        setBounds(popupX, placement.y(), popupWidth, placement.height());
        setVisible(true);
        installOutsideClickListener();
        // after the showing event cycle completes, or the button that opened
        // us reclaims focus and typing goes to the main window
        SwingUtilities.invokeLater(() -> filterField.requestFocusInWindow());
    }

    private void installOutsideClickListener() {
        if (!outsideClickListenerInstalled) {
            Toolkit.getDefaultToolkit().addAWTEventListener(
                    outsideClickListener, AWTEvent.MOUSE_EVENT_MASK);
            outsideClickListenerInstalled = true;
        }
    }

    private void uninstallOutsideClickListener() {
        if (outsideClickListenerInstalled) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(outsideClickListener);
            outsideClickListenerInstalled = false;
        }
    }

    private void handleAwtEvent(AWTEvent event) {
        if (isShowing() && shouldDismissForMouseEvent(event, getRootPane())) {
            dispose();
        }
    }

    static boolean shouldDismissForMouseEvent(AWTEvent event, Component popupRoot) {
        if (!(event instanceof MouseEvent mouseEvent)
                || mouseEvent.getID() != MouseEvent.MOUSE_PRESSED
                || !(mouseEvent.getSource() instanceof Component source)) {
            return false;
        }
        return source != popupRoot && !SwingUtilities.isDescendingFrom(source, popupRoot);
    }

    @Override
    public void dispose() {
        uninstallOutsideClickListener();
        super.dispose();
    }

    /** Number of models currently passing the filter (visible for tests). */
    int visibleModelCount() {
        return listModel.getSize();
    }

    /** The model currently highlighted in the list (visible for tests). */
    String selectedVisibleModel() {
        return modelList.getSelectedValue();
    }

    /** The text in the filter field (visible for tests). */
    String filterText() {
        return filterField.getText();
    }

    int horizontalScrollBarPolicy() {
        return modelScroll.getHorizontalScrollBarPolicy();
    }

    boolean modelListTracksViewportWidth() {
        return modelList.getScrollableTracksViewportWidth();
    }

    int modelListFixedCellHeight() {
        return modelList.getFixedCellHeight();
    }

    private void refresh() {
        String keep = modelList.getSelectedValue();
        listModel.clear();
        for (String model : filterAndSort(allModels, filterField.getText(), prefs)) {
            listModel.addElement(model);
        }
        if (!selectVisible(keep) && listModel.getSize() > 0) {
            modelList.setSelectedIndex(0);
        }
    }

    private boolean selectVisible(String model) {
        if (model == null) {
            return false;
        }
        int index = listModel.indexOf(model);
        if (index >= 0) {
            modelList.setSelectedIndex(index);
            modelList.ensureIndexIsVisible(index);
            return true;
        }
        return false;
    }

    private void confirm() {
        String value = modelList.getSelectedValue();
        if (value != null) {
            dispose();
            onSelect.accept(value);
        }
    }
}
