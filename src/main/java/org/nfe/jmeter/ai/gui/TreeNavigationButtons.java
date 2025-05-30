package org.nfe.jmeter.ai.gui;

import javax.swing.JButton;
import javax.swing.ImageIcon;
import javax.swing.SwingConstants;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TreeNavigationButtons {

    private static final Logger log = LoggerFactory.getLogger(TreeNavigationButtons.class);
    private JButton upButton;
    private JButton downButton;

    public TreeNavigationButtons() {
        try {
            // Load PNG icons
            ImageIcon upIcon = new ImageIcon(getClass().getResource("/org/qainsights/jmeter/ai/icons/up.png"));
            ImageIcon downIcon = new ImageIcon(getClass().getResource("/org/qainsights/jmeter/ai/icons/down.png"));
            
            // Initialize buttons with icons
            upButton = new JButton(upIcon);
            downButton = new JButton(downIcon);
            
            // Configure buttons
            configureButton(upButton, "Navigate Up");
            configureButton(downButton, "Navigate Down");
        } catch (Exception e) {
            log.error("Error loading PNG icons", e);
            // Fallback to text buttons
            upButton = new JButton("↑");
            downButton = new JButton("↓");
            
            // Configure buttons
            configureButton(upButton, "Navigate Up");
            configureButton(downButton, "Navigate Down");
        }
        
        log.info("TreeNavigationButtons initialized");
    }
    
    /**
     * Configure a button with consistent styling
     * @param button The button to configure
     * @param tooltip The tooltip text
     */
    private void configureButton(JButton button, String tooltip) {
        // Set tooltip
        button.setToolTipText(tooltip);
        
        // Set consistent size
        // button.setPreferredSize(new Dimension(36, 36));
        
        // Center the text
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.setVerticalAlignment(SwingConstants.CENTER);
        
        // Set padding
        button.setMargin(new Insets(2, 2, 2, 2));
        
        // No need to set font for icon buttons
        
        // Improve appearance
        button.setFocusPainted(false);
        button.setBorderPainted(true);
        button.setContentAreaFilled(true);
    }

    public JButton getUpButton() {
        return upButton;
    }

    public JButton getDownButton() {
        return downButton;
    }

    public void setUpButtonActionListener() {
        // get current JMeter selected element
        log.info("Setting up button action listener");
        
        // Initial check to disable button if Test Plan is selected
        updateUpButtonState();
        
        // Add a property change listener to update button state when selection changes
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage != null && guiPackage.getTreeListener() != null) {
            guiPackage.getTreeListener().getJTree().addTreeSelectionListener(e -> {
                // Small delay to ensure the current node is updated
                javax.swing.SwingUtilities.invokeLater(() -> {
                    log.info("Tree selection changed, updating button state");
                    updateUpButtonState();
                });
            });
        } else {
            log.warn("GuiPackage or TreeListener is null, navigation buttons may not work properly");
        }
        
        upButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                log.info("Up button clicked");
                
                // get current JMeter selected element
                GuiPackage guiPackage = GuiPackage.getInstance();
                if (guiPackage == null) {
                    log.error("GuiPackage is null, cannot navigate");
                    return;
                }
                
                if (guiPackage.getTreeListener() == null) {
                    log.error("TreeListener is null, cannot navigate");
                    return;
                }
                
                JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
                if (currentNode == null) {
                    log.error("Current node is null, cannot navigate");
                    return;
                }
                
                // Get parent node
                TreeNode parentNode = currentNode.getParent();
                if (parentNode == null) {
                    log.warn("Parent node is null, cannot navigate up");
                    return;
                }
                
                log.info("Up button pressed for node: {} ({})", currentNode.getName(), currentNode.getStaticLabel());
                log.info("Parent node: {}", parentNode instanceof JMeterTreeNode ? ((JMeterTreeNode)parentNode).getName() : "non-JMeterTreeNode");
                
                // Find the index of the current node among its siblings
                int currentIndex = -1;
                for (int i = 0; i < parentNode.getChildCount(); i++) {
                    TreeNode node = parentNode.getChildAt(i);
                    if (node instanceof JMeterTreeNode) {
                        JMeterTreeNode jmNode = (JMeterTreeNode) node;
                        log.info("Sibling {} at index {}: {}", i, i, jmNode.getName());
                    }
                    
                    if (parentNode.getChildAt(i) == currentNode) {
                        currentIndex = i;
                        log.info("Current node found at index: {}", currentIndex);
                    }
                }
                
                // If not the first child, navigate to the previous sibling
                if (currentIndex > 0 && currentIndex != -1) {
                    TreeNode previousSibling = parentNode.getChildAt(currentIndex - 1);
                    if (previousSibling instanceof JMeterTreeNode) {
                        JMeterTreeNode jmPreviousSibling = (JMeterTreeNode) previousSibling;
                        log.info("Navigating to previous sibling: {}", jmPreviousSibling.getName());
                        
                        // If the previous sibling has children, navigate to its last child
                        if (jmPreviousSibling.getChildCount() > 0) {
                            navigateToLastDescendant(jmPreviousSibling);
                        } else {
                            // Otherwise navigate to the previous sibling itself
                            guiPackage.getTreeListener().getJTree().setSelectionPath(new TreePath(jmPreviousSibling.getPath()));
                        }
                    }
                } 
                // If it's the first child, navigate to the parent
                else if (parentNode instanceof JMeterTreeNode) {
                    JMeterTreeNode jmParentNode = (JMeterTreeNode) parentNode;
                    log.info("Navigating to parent: {}", jmParentNode.getName());
                    guiPackage.getTreeListener().getJTree().setSelectionPath(new TreePath(jmParentNode.getPath()));
                }
                
                // Update button state after navigation with a small delay to ensure UI is updated
                javax.swing.SwingUtilities.invokeLater(() -> updateUpButtonState());
            }
        });
    }
    
    /**
     * Updates the enabled state of the Up button based on the current selection.
     * Disables the button if the Test Plan element is selected or if we're at the root.
     */
    private void updateUpButtonState() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            upButton.setEnabled(false);
            return;
        }
        
        JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
        if (currentNode == null) {
            upButton.setEnabled(false);
            return;
        }
        
        String nodeName = currentNode.getName();
        String nodeLabel = currentNode.getStaticLabel();
        log.info("Checking up button state for node: {} ({})", nodeName, nodeLabel);
        
        // Disable if this is the Test Plan node (which is typically the root)
        if (currentNode.getParent() == null) {
            log.info("Disabling up button - node has no parent");
            upButton.setEnabled(false);
        } else if ("Test Plan".equals(nodeLabel)) {
            log.info("Disabling up button - Test Plan node detected");
            upButton.setEnabled(false);
        } else {
            log.info("Enabling up button for node: {}", nodeName);
            upButton.setEnabled(true);
        }
    }

    public void setDownButtonActionListener() {
        log.info("Setting down button action listener");
        downButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // get current JMeter selected element
                GuiPackage guiPackage = GuiPackage.getInstance();
                if (guiPackage == null) {
                    return;
                }
                JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
                if (currentNode == null) {
                    return;
                }
                
                log.info("Down button pressed for node: {} ({})", currentNode.getName(), currentNode.getStaticLabel());
                
                // First check if the node has children - if yes, navigate to first child
                if (currentNode.getChildCount() > 0) {
                    TreeNode childNode = currentNode.getChildAt(0);
                    if (childNode instanceof JMeterTreeNode) {
                        JMeterTreeNode jmChildNode = (JMeterTreeNode) childNode;
                        log.info("Navigating to first child: {}", jmChildNode.getName());
                        guiPackage.getTreeListener().getJTree().setSelectionPath(new TreePath(jmChildNode.getPath()));
                        // Ensure button state is updated after navigation
                        javax.swing.SwingUtilities.invokeLater(() -> updateUpButtonState());
                        return;
                    }
                }
                
                // If no children, try to navigate to the next sibling
                TreeNode parentNode = currentNode.getParent();
                if (parentNode != null) {
                    log.info("Parent node has {} children", parentNode.getChildCount());
                    
                    // Find the index of the current node among its siblings
                    int currentIndex = -1;
                    for (int i = 0; i < parentNode.getChildCount(); i++) {
                        TreeNode node = parentNode.getChildAt(i);
                        if (node instanceof JMeterTreeNode) {
                            JMeterTreeNode jmNode = (JMeterTreeNode) node;
                            log.info("Child {} at index {}: {}", i, i, jmNode.getName());
                        }
                        
                        if (parentNode.getChildAt(i) == currentNode) {
                            currentIndex = i;
                            log.info("Current node found at index: {}", currentIndex);
                        }
                    }
                    
                    // If not the last child, navigate to the next sibling
                    if (currentIndex < parentNode.getChildCount() - 1 && currentIndex != -1) {
                        TreeNode nextSibling = parentNode.getChildAt(currentIndex + 1);
                        if (nextSibling instanceof JMeterTreeNode) {
                            JMeterTreeNode jmNextSibling = (JMeterTreeNode) nextSibling;
                            log.info("Navigating to next sibling: {}", jmNextSibling.getName());
                            guiPackage.getTreeListener().getJTree().setSelectionPath(new TreePath(jmNextSibling.getPath()));
                            return;
                        }
                    }
                    // If it's the last child, try to navigate to the parent's next sibling
                    else {
                        log.info("This is the last child, trying to navigate to parent's next sibling");
                        navigateToNextParentSibling(currentNode);
                    }
                }
            }
        });
    }
    
    /**
     * Navigate to the next sibling of the parent node or up the tree until finding a node with a next sibling
     * @param currentNode The current node
     */
    private void navigateToNextParentSibling(JMeterTreeNode currentNode) {
        TreeNode parent = currentNode.getParent();
        if (!(parent instanceof JMeterTreeNode)) {
            log.info("Parent is not a JMeterTreeNode");
            return;
        }
        
        JMeterTreeNode parentNode = (JMeterTreeNode) parent;
        log.info("Parent node: {}", parentNode.getName());
        
        TreeNode grandParent = parentNode.getParent();
        if (grandParent == null) {
            log.info("No grandparent found");
            return;
        }
        
        log.info("Grandparent has {} children", grandParent.getChildCount());
        
        // Find the index of the parent among its siblings
        int parentIndex = -1;
        for (int i = 0; i < grandParent.getChildCount(); i++) {
            TreeNode node = grandParent.getChildAt(i);
            if (node instanceof JMeterTreeNode) {
                JMeterTreeNode jmNode = (JMeterTreeNode) node;
                log.info("Grandparent child {} at index {}: {}", i, i, jmNode.getName());
            }
            
            if (grandParent.getChildAt(i) == parentNode) {
                parentIndex = i;
                log.info("Parent node found at index: {}", parentIndex);
            }
        }
        
        // If parent is not the last child, navigate to its next sibling
        if (parentIndex < grandParent.getChildCount() - 1 && parentIndex != -1) {
            TreeNode parentNextSibling = grandParent.getChildAt(parentIndex + 1);
            if (parentNextSibling instanceof JMeterTreeNode) {
                JMeterTreeNode jmParentNextSibling = (JMeterTreeNode) parentNextSibling;
                log.info("Navigating to parent's next sibling: {}", jmParentNextSibling.getName());
                GuiPackage guiPackage = GuiPackage.getInstance();
                if (guiPackage != null) {
                    guiPackage.getTreeListener().getJTree().setSelectionPath(new TreePath(jmParentNextSibling.getPath()));
                    return; // Add return to prevent further navigation
                }
            }
        }
        
        // Check if we're in a nested controller structure and need to navigate to a sibling of a higher-level controller
        log.info("Checking for higher-level controller siblings");
        JMeterTreeNode currentParent = parentNode;
        TreeNode currentGrandParent = grandParent;
        
        // Try to find a higher-level controller with a next sibling
        while (currentGrandParent != null && currentGrandParent instanceof JMeterTreeNode) {
            int currentParentIndex = -1;
            for (int i = 0; i < currentGrandParent.getChildCount(); i++) {
                if (currentGrandParent.getChildAt(i) == currentParent) {
                    currentParentIndex = i;
                    log.info("Found parent '{}' at index {} in grandparent '{}'", 
                            currentParent.getName(), i, 
                            ((JMeterTreeNode)currentGrandParent).getName());
                    break;
                }
            }
            
            // If we found the parent and it has a next sibling, navigate to it
            if (currentParentIndex != -1 && currentParentIndex < currentGrandParent.getChildCount() - 1) {
                TreeNode nextSibling = currentGrandParent.getChildAt(currentParentIndex + 1);
                if (nextSibling instanceof JMeterTreeNode) {
                    JMeterTreeNode jmNextSibling = (JMeterTreeNode) nextSibling;
                    log.info("Navigating to higher-level sibling: {}", jmNextSibling.getName());
                    GuiPackage guiPackage = GuiPackage.getInstance();
                    if (guiPackage != null) {
                        guiPackage.getTreeListener().getJTree().setSelectionPath(new TreePath(jmNextSibling.getPath()));
                        return;
                    }
                }
            }
            
            // Move up one level in the tree
            currentParent = (JMeterTreeNode) currentGrandParent;
            currentGrandParent = currentParent.getParent();
        }
        
        log.info("No higher-level siblings found, trying to navigate to grandparent's next sibling");
        // If we couldn't find any higher-level siblings, try the original approach
        navigateToNextGrandParentSibling(parentNode);
    }
    
    /**
     * Navigate to the next sibling of the grandparent node or up the tree until finding a node with a next sibling
     * @param parentNode The parent node
     */
    private void navigateToNextGrandParentSibling(JMeterTreeNode parentNode) {
        TreeNode grandParent = parentNode.getParent();
        if (!(grandParent instanceof JMeterTreeNode)) {
            log.info("Grandparent is not a JMeterTreeNode");
            return;
        }
        
        JMeterTreeNode jmGrandParent = (JMeterTreeNode) grandParent;
        log.info("Grandparent node: {}", jmGrandParent.getName());
        
        TreeNode greatGrandParent = jmGrandParent.getParent();
        if (greatGrandParent == null) {
            log.info("No great-grandparent found");
            return;
        }
        
        log.info("Great-grandparent has {} children", greatGrandParent.getChildCount());
        
        // Find the index of the grandparent among its siblings
        int grandParentIndex = -1;
        for (int i = 0; i < greatGrandParent.getChildCount(); i++) {
            TreeNode node = greatGrandParent.getChildAt(i);
            if (node instanceof JMeterTreeNode) {
                JMeterTreeNode jmNode = (JMeterTreeNode) node;
                log.info("Great-grandparent child {} at index {}: {}", i, i, jmNode.getName());
            }
            
            if (greatGrandParent.getChildAt(i) == jmGrandParent) {
                grandParentIndex = i;
                log.info("Grandparent node found at index: {}", grandParentIndex);
            }
        }
        
        // If grandparent is not the last child, navigate to its next sibling
        if (grandParentIndex < greatGrandParent.getChildCount() - 1 && grandParentIndex != -1) {
            TreeNode grandParentNextSibling = greatGrandParent.getChildAt(grandParentIndex + 1);
            if (grandParentNextSibling instanceof JMeterTreeNode) {
                JMeterTreeNode jmGrandParentNextSibling = (JMeterTreeNode) grandParentNextSibling;
                log.info("Navigating to grandparent's next sibling: {}", jmGrandParentNextSibling.getName());
                GuiPackage guiPackage = GuiPackage.getInstance();
                if (guiPackage != null) {
                    guiPackage.getTreeListener().getJTree().setSelectionPath(new TreePath(jmGrandParentNextSibling.getPath()));
                }
            }
        } else {
            log.info("Grandparent is the last child or not found");
        }
    }
    
    /**
     * Navigate to the last descendant of a node (the deepest, rightmost node in the subtree)
     * @param node The starting node
     */
    private void navigateToLastDescendant(JMeterTreeNode node) {
        if (node.getChildCount() == 0) {
            // If node has no children, navigate to it
            log.info("Navigating to leaf node: {}", node.getName());
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage != null) {
                guiPackage.getTreeListener().getJTree().setSelectionPath(new TreePath(node.getPath()));
            }
            return;
        }
        
        // Get the last child
        TreeNode lastChild = node.getChildAt(node.getChildCount() - 1);
        if (lastChild instanceof JMeterTreeNode) {
            JMeterTreeNode jmLastChild = (JMeterTreeNode) lastChild;
            log.info("Checking last child: {}", jmLastChild.getName());
            
            // Recursively navigate to the last descendant of the last child
            navigateToLastDescendant(jmLastChild);
        } else {
            // If last child is not a JMeterTreeNode, navigate to the current node
            log.info("Last child is not a JMeterTreeNode, navigating to: {}", node.getName());
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage != null) {
                guiPackage.getTreeListener().getJTree().setSelectionPath(new TreePath(node.getPath()));
            }
        }
    }
}
