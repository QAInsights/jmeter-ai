package org.qainsights.jmeter.ai.wrap;

import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.ThreadGroup;
import org.qainsights.jmeter.ai.utils.JMeterElementManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.tree.TreeNode;
import java.util.*;

/**
 * Handler for the @wrap command that intelligently groups HTTP request samplers
 * under a Transaction Controller when a Thread Group is selected.
 */
public class WrapCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(WrapCommandHandler.class);
    
    // Store the state for undo functionality
    private static List<WrapOperation> lastWrapOperation = new ArrayList<>();
    
    // Store the undone operations for redo functionality
    private static List<WrapOperation> lastUndoneOperation = new ArrayList<>();

    /**
     * Processes the @wrap command, checking if a Thread Group is selected and grouping
     * HTTP request samplers under Transaction Controllers.
     *
     * @return A response message indicating the result of the operation
     */
    public String processWrapCommand() {
        log.info("Processing @wrap command");

        try {
            // Check if a Thread Group is selected
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                log.error("GuiPackage is null, cannot process @wrap command");
                return "Error: JMeter GUI is not available.";
            }

            JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
            if (currentNode == null) {
                log.error("No node is currently selected in the test plan");
                return "Please select a Thread Group in the test plan before using the @wrap command.";
            }

            TestElement element = currentNode.getTestElement();
            if (!(element instanceof ThreadGroup)) {
                log.error("Selected element is not a Thread Group");
                return "Please select a Thread Group in the test plan before using the @wrap command. " +
                       "The currently selected element is a " + element.getClass().getSimpleName() + ".";
            }

            // Find all samplers in the Thread Group
            log.info("Finding samplers in Thread Group: {}", currentNode.getName());
            List<JMeterTreeNode> samplers = findSamplers(currentNode);
            log.info("Found {} samplers in Thread Group", samplers.size());

            if (samplers.isEmpty()) {
                log.warn("No samplers found in Thread Group");
                return "No samplers found in the selected Thread Group.";
            }

            // Group samplers by similarity
            log.info("Grouping samplers by similarity");
            Map<String, List<JMeterTreeNode>> samplerGroups = groupSamplersBySimilarity(samplers);
            log.info("Created {} sampler groups based on similarity", samplerGroups.size());

            // Create Transaction Controllers and move samplers
            log.info("Creating Transaction Controllers and moving samplers");
            int groupsCreated = createTransactionControllersAndMoveSamplers(currentNode, samplerGroups);

            // Refresh the tree
            guiPackage.getTreeModel().nodeStructureChanged(currentNode);

            return "Successfully grouped samplers into " + groupsCreated + " Transaction Controllers based on similarity.";
        } catch (Exception e) {
            log.error("Error processing @wrap command", e);
            return "Error processing @wrap command: " + e.getMessage();
        }
    }

    /**
     * Finds all samplers in the given node and its children.
     *
     * @param node The node to search in
     * @return A list of sampler nodes
     */
    private List<JMeterTreeNode> findSamplers(JMeterTreeNode node) {
        List<JMeterTreeNode> samplers = new ArrayList<>();
        
        log.debug("Searching for samplers in node: {} ({})", node.getName(), 
                node.getTestElement().getClass().getSimpleName());
        log.debug("Node has {} children", node.getChildCount());
        
        // Skip the node itself, only process children
        for (int i = 0; i < node.getChildCount(); i++) {
            TreeNode childNode = node.getChildAt(i);
            if (childNode instanceof JMeterTreeNode) {
                JMeterTreeNode jmeterChildNode = (JMeterTreeNode) childNode;
                TestElement childElement = jmeterChildNode.getTestElement();
                
                log.debug("Examining child node: {} ({})", jmeterChildNode.getName(), 
                        childElement.getClass().getSimpleName());
                
                if (childElement instanceof Sampler) {
                    // Check if the class name contains "Sampler"
                    String className = childElement.getClass().getSimpleName();
                    if (className.contains("Sampler")) {
                        // Found a valid sampler
                        log.debug("Found sampler: {}", jmeterChildNode.getName());
                        samplers.add(jmeterChildNode);
                    } else {
                        log.debug("Skipping non-Sampler element: {} ({})", jmeterChildNode.getName(), className);
                    }
                } else if (!(childElement instanceof TransactionController)) {
                    // If it's not a Transaction Controller, recursively search its children
                    // This ensures we don't process samplers that are already in Transaction Controllers
                    log.debug("Recursively searching in: {}", jmeterChildNode.getName());
                    List<JMeterTreeNode> childSamplers = findSamplers(jmeterChildNode);
                    log.debug("Found {} samplers in child node: {}", childSamplers.size(), jmeterChildNode.getName());
                    samplers.addAll(childSamplers);
                } else {
                    log.debug("Skipping Transaction Controller: {}", jmeterChildNode.getName());
                }
            }
        }
        
        return samplers;
    }

    /**
     * Groups samplers by similarity based on their name and properties.
     *
     * @param samplers The list of sampler nodes to group
     * @return A map of group keys to lists of sampler nodes
     */
    private Map<String, List<JMeterTreeNode>> groupSamplersBySimilarity(List<JMeterTreeNode> samplers) {
        Map<String, List<JMeterTreeNode>> samplerGroups = new HashMap<>();
        
        log.info("Grouping {} samplers by similarity", samplers.size());
        
        // First, build a map of the full path for each sampler to maintain hierarchy context
        Map<JMeterTreeNode, String> samplerPaths = new HashMap<>();
        for (JMeterTreeNode samplerNode : samplers) {
            // Build the full path of the sampler (including all parent controllers)
            TreeNode parent = samplerNode.getParent();
            List<String> parentNames = new ArrayList<>();
            
            while (parent != null && parent instanceof JMeterTreeNode) {
                JMeterTreeNode jmeterParent = (JMeterTreeNode) parent;
                parentNames.add(0, jmeterParent.getName());
                parent = parent.getParent();
            }
            
            // Join the parent names to create a path
            String path = String.join(" > ", parentNames);
            samplerPaths.put(samplerNode, path);
            log.debug("Sampler: '{}' has path: '{}'", samplerNode.getName(), path);
        }
        
        // Group samplers by their parent node to maintain hierarchy
        Map<JMeterTreeNode, List<JMeterTreeNode>> parentToSamplersMap = new HashMap<>();
        for (JMeterTreeNode samplerNode : samplers) {
            JMeterTreeNode parentNode = (JMeterTreeNode) samplerNode.getParent();
            parentToSamplersMap.computeIfAbsent(parentNode, k -> new ArrayList<>()).add(samplerNode);
        }
        
        // For each parent node, group its samplers by name pattern
        for (Map.Entry<JMeterTreeNode, List<JMeterTreeNode>> entry : parentToSamplersMap.entrySet()) {
            JMeterTreeNode parentNode = entry.getKey();
            List<JMeterTreeNode> parentSamplers = entry.getValue();
            
            log.info("Processing {} samplers under parent: '{}'", parentSamplers.size(), parentNode.getName());
            
            // Group samplers by their simplified name pattern
            Map<String, List<JMeterTreeNode>> patternGroups = new HashMap<>();
            for (JMeterTreeNode samplerNode : parentSamplers) {
                String name = samplerNode.getName();
                String simplifiedPattern = simplifyName(name);
                
                log.debug("Sampler: '{}' has simplified pattern: '{}'", name, simplifiedPattern);
                
                // Use the parent path + simplified pattern as the group key
                String path = samplerPaths.get(samplerNode);
                String groupKey = path + ": " + simplifiedPattern;
                
                patternGroups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(samplerNode);
            }
            
            // Add all pattern groups to the final result
            samplerGroups.putAll(patternGroups);
        }
        
        // Log the groups
        for (Map.Entry<String, List<JMeterTreeNode>> entry : samplerGroups.entrySet()) {
            log.info("Group '{}' has {} samplers", entry.getKey(), entry.getValue().size());
        }
        
        return samplerGroups;
    }

    /**
     * Simplifies a name by replacing numeric IDs and UUIDs with placeholders.
     *
     * @param name The name to simplify
     * @return The simplified name pattern
     */
    private String simplifyName(String name) {
        // Replace numeric IDs (sequences of digits) with a placeholder
        String simplified = name.replaceAll("\\b\\d+\\b", "{ID}");
        
        // Replace UUIDs with a placeholder
        simplified = simplified.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "{UUID}");
        
        // Replace any remaining sequences of digits with a placeholder
        simplified = simplified.replaceAll("\\d+", "{NUM}");
        
        return simplified;
    }

    /**
     * Creates Transaction Controllers and moves samplers into them while preserving the original order.
     *
     * @param threadGroupNode The Thread Group node
     * @param samplerGroups The map of sampler groups
     * @return The number of Transaction Controllers created
     */
    private int createTransactionControllersAndMoveSamplers(JMeterTreeNode threadGroupNode, Map<String, List<JMeterTreeNode>> samplerGroups) {
        // Clear previous undo operations when performing a new wrap
        lastWrapOperation.clear();
        lastUndoneOperation.clear();
        
        // Keep track of created Transaction Controllers to avoid duplicates
        Map<String, JMeterTreeNode> createdControllers = new HashMap<>();
        int groupsCreated = 0;
        
        // Process each group of samplers
        for (Map.Entry<String, List<JMeterTreeNode>> entry : samplerGroups.entrySet()) {
            String groupKey = entry.getKey();
            List<JMeterTreeNode> samplers = entry.getValue();
            
            if (samplers.isEmpty()) {
                log.info("Skipping empty group: '{}'", groupKey);
                continue;
            }
            
            log.info("Processing group '{}' with {} samplers", groupKey, samplers.size());
            
            // Find the position of the first sampler in this group
            JMeterTreeNode firstSampler = samplers.get(0);
            JMeterTreeNode parentNode = (JMeterTreeNode) firstSampler.getParent();
            int insertIndex = parentNode.getIndex(firstSampler);
            
            log.info("First sampler '{}' found at index {} under parent '{}'", 
                    firstSampler.getName(), insertIndex, parentNode.getName());
            
            // Create a descriptive name for the Transaction Controller
            // Extract the most meaningful part of the group key for naming
            String simpleName;
            if (groupKey.contains(":")) {
                // If the group key contains a colon, use the part after the last colon
                simpleName = groupKey.substring(groupKey.lastIndexOf(":") + 1).trim();
            } else {
                // Otherwise use the group key directly
                simpleName = groupKey;
            }
            
            // Create a concise and meaningful name
            String controllerName = "Transaction - " + simpleName;
            log.info("Creating Transaction Controller with name: '{}'", controllerName);
            
            // Check if we've already created this controller (for this parent)
            JMeterTreeNode transactionNode = createdControllers.get(parentNode.getName() + "-" + controllerName);
            if (transactionNode == null) {
                // Create a Transaction Controller
                boolean success = JMeterElementManager.addElement("transactioncontroller", controllerName);
                if (!success) {
                    log.error("Failed to create Transaction Controller");
                    continue;
                }
                log.info("Successfully created Transaction Controller: '{}'", controllerName);
                
                // Get the newly created Transaction Controller node
                transactionNode = findNewlyCreatedTransactionController(threadGroupNode, controllerName);
                if (transactionNode == null) {
                    log.error("Could not find the created Transaction Controller: '{}'", controllerName);
                    continue;
                }
                log.info("Found Transaction Controller node: '{}'", transactionNode.getName());
                
                // Move the Transaction Controller to the position of the first sampler
                GuiPackage guiPackage = GuiPackage.getInstance();
                try {
                    // First, move the Transaction Controller to the parent of the first sampler
                    guiPackage.getTreeModel().removeNodeFromParent(transactionNode);
                    guiPackage.getTreeModel().insertNodeInto(transactionNode, parentNode, insertIndex);
                    log.info("Moved Transaction Controller to index {} under parent '{}'", 
                            insertIndex, parentNode.getName());
                    
                    // Store the created controller
                    createdControllers.put(parentNode.getName() + "-" + controllerName, transactionNode);
                } catch (Exception e) {
                    log.error("Failed to move Transaction Controller: {}", e.getMessage());
                    continue;
                }
            } else {
                log.info("Using existing Transaction Controller: '{}'", controllerName);
            }
            
            // Store original indices for undo functionality
            Map<JMeterTreeNode, Integer> originalIndices = new HashMap<>();
            List<JMeterTreeNode> movedSamplers = new ArrayList<>();
            
            // Move samplers and their direct children to the Transaction Controller
            // Process samplers in order of their original position to maintain sequence
            List<JMeterTreeNode> orderedSamplers = new ArrayList<>(samplers);
            orderedSamplers.sort((s1, s2) -> {
                JMeterTreeNode parent1 = (JMeterTreeNode) s1.getParent();
                JMeterTreeNode parent2 = (JMeterTreeNode) s2.getParent();
                
                if (parent1.equals(parent2)) {
                    return Integer.compare(parent1.getIndex(s1), parent2.getIndex(s2));
                }
                return 0; // Different parents, preserve original order
            });
            
            for (JMeterTreeNode samplerNode : orderedSamplers) {
                log.info("Moving sampler: '{}' to Transaction Controller: '{}'", 
                        samplerNode.getName(), controllerName);
                
                // Store the sampler's original parent and index for reference
                JMeterTreeNode originalParent = (JMeterTreeNode) samplerNode.getParent();
                int originalIndex = originalParent.getIndex(samplerNode);
                
                // Store original index for undo
                originalIndices.put(samplerNode, originalIndex);
                
                // Get all direct children of the sampler (pre/post processors, etc.)
                List<JMeterTreeNode> samplerChildren = new ArrayList<>();
                for (int i = 0; i < samplerNode.getChildCount(); i++) {
                    TreeNode childNode = samplerNode.getChildAt(i);
                    if (childNode instanceof JMeterTreeNode) {
                        JMeterTreeNode jmeterChild = (JMeterTreeNode) childNode;
                        samplerChildren.add(jmeterChild);
                    }
                }
                
                // Move the sampler to the Transaction Controller
                moveNode(samplerNode, transactionNode);
                movedSamplers.add(samplerNode);
                
                log.info("Successfully moved sampler: '{}' to Transaction Controller: '{}'", 
                        samplerNode.getName(), controllerName);
            }
            
            // Store the operation for undo functionality
            lastWrapOperation.add(new WrapOperation(transactionNode, movedSamplers, parentNode, originalIndices));
            
            groupsCreated++;
            log.info("Successfully created group {} with Transaction Controller: '{}'", 
                    groupsCreated, controllerName);
        }
        
        return groupsCreated;
    }
    
    /**
     * Finds a newly created Transaction Controller by name.
     *
     * @param parentNode The parent node to search in
     * @param controllerName The name of the controller to find
     * @return The Transaction Controller node, or null if not found
     */
    private JMeterTreeNode findNewlyCreatedTransactionController(JMeterTreeNode parentNode, String controllerName) {
        log.debug("Searching for Transaction Controller: '{}' in parent: '{}'", controllerName, parentNode.getName());
        log.debug("Parent node has {} children", parentNode.getChildCount());
        
        for (int i = 0; i < parentNode.getChildCount(); i++) {
            TreeNode child = parentNode.getChildAt(i);
            if (child instanceof JMeterTreeNode) {
                JMeterTreeNode jmeterChild = (JMeterTreeNode) child;
                log.debug("Examining child: '{}' ({})", jmeterChild.getName(), 
                        jmeterChild.getTestElement().getClass().getSimpleName());
                
                if (jmeterChild.getTestElement() instanceof TransactionController && 
                        jmeterChild.getName().equals(controllerName)) {
                    log.debug("Found Transaction Controller: '{}'", controllerName);
                    return jmeterChild;
                }
            }
        }
        return null;
    }
    
    /**
     * Moves a node to a new parent node while preserving its children.
     *
     * @param nodeToMove The node to move
     * @param newParent The new parent node
     */
    private void moveNode(JMeterTreeNode nodeToMove, JMeterTreeNode newParent) {
        try {
            log.debug("Moving node: '{}' to new parent: '{}'", nodeToMove.getName(), newParent.getName());
            
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                log.error("GuiPackage is null, cannot move node");
                return;
            }
            
            // Get the current parent of the node to move
            JMeterTreeNode oldParent = (JMeterTreeNode) nodeToMove.getParent();
            if (oldParent == null) {
                log.error("Cannot move node '{}' as it has no parent", nodeToMove.getName());
                return;
            }
            
            int oldIndex = oldParent.getIndex(nodeToMove);
            log.debug("Current parent of node: '{}' is '{}' at index {}", 
                    nodeToMove.getName(), oldParent.getName(), oldIndex);
            
            // First, collect all children of the node to move to preserve them
            List<JMeterTreeNode> children = new ArrayList<>();
            for (int i = 0; i < nodeToMove.getChildCount(); i++) {
                TreeNode child = nodeToMove.getChildAt(i);
                if (child instanceof JMeterTreeNode) {
                    children.add((JMeterTreeNode) child);
                }
            }
            log.debug("Node '{}' has {} children to preserve", nodeToMove.getName(), children.size());
            
            // Remove the node from its current parent
            guiPackage.getTreeModel().removeNodeFromParent(nodeToMove);
            log.debug("Removed node: '{}' from parent: '{}'", nodeToMove.getName(), oldParent.getName());
            
            // Add the node to the new parent
            guiPackage.getTreeModel().insertNodeInto(nodeToMove, newParent, newParent.getChildCount());
            log.debug("Added node: '{}' to new parent: '{}' at position {}", 
                    nodeToMove.getName(), newParent.getName(), newParent.getChildCount() - 1);
            
            // If this is a sampler being moved to a Transaction Controller, ensure children are preserved
            if (!children.isEmpty()) {
                log.debug("Preserving {} children of node '{}'", children.size(), nodeToMove.getName());
                
                // Reattach all children to the moved node in their original order
                for (JMeterTreeNode child : children) {
                    // The child might have been moved already if it was a direct child
                    if (child.getParent() != null) {
                        log.debug("Moving child '{}' to parent '{}'", child.getName(), nodeToMove.getName());
                        guiPackage.getTreeModel().removeNodeFromParent(child);
                        guiPackage.getTreeModel().insertNodeInto(child, nodeToMove, nodeToMove.getChildCount());
                    }
                }
            }
            
            // Refresh the tree
            guiPackage.getTreeModel().nodeStructureChanged(oldParent);
            guiPackage.getTreeModel().nodeStructureChanged(newParent);
            guiPackage.getTreeModel().nodeStructureChanged(nodeToMove);
            log.debug("Tree structure updated for all affected nodes");
        } catch (Exception e) {
            log.error("Error moving node: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Undoes the last wrap operation performed.
     * 
     * @return A message indicating the result of the undo operation
     */
    public String undoLastWrap() {
        if (lastWrapOperation.isEmpty()) {
            return "Nothing to undo.";
        }
        
        try {
            log.info("Undoing last wrap operation");
            
            // Store operations for redo
            lastUndoneOperation.clear();
            lastUndoneOperation.addAll(lastWrapOperation);
            
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                log.error("GuiPackage is null, cannot undo wrap operation");
                return "Error: JMeter GUI is not available.";
            }
            
            // Get the currently selected node for special handling
            JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
            
            int unwrappedCount = 0;
            
            // Process each wrap operation in reverse order
            for (int i = lastWrapOperation.size() - 1; i >= 0; i--) {
                WrapOperation operation = lastWrapOperation.get(i);
                
                // Move samplers back to their original parent at their original indices
                for (JMeterTreeNode sampler : operation.movedSamplers) {
                    // Get the original index of this sampler
                    Integer originalIndex = operation.originalIndices.get(sampler);
                    if (originalIndex == null) {
                        originalIndex = 0; // Default to 0 if not found
                    }
                    
                    // Move the sampler back to its original parent
                    log.info("Moving sampler '{}' back to original parent '{}' at index {}", 
                            sampler.getName(), operation.parentNode.getName(), originalIndex);
                    
                    try {
                        // Remove from current parent (Transaction Controller)
                        guiPackage.getTreeModel().removeNodeFromParent(sampler);
                        
                        // Insert at original position
                        guiPackage.getTreeModel().insertNodeInto(sampler, operation.parentNode, originalIndex);
                        unwrappedCount++;
                    } catch (Exception e) {
                        log.error("Error moving sampler back to original position: {}", e.getMessage());
                    }
                }
                
                // Remove the Transaction Controller if it's now empty
                if (operation.transactionController.getChildCount() == 0) {
                    log.info("Removing empty Transaction Controller: {}", operation.transactionController.getName());
                    try {
                        guiPackage.getTreeModel().removeNodeFromParent(operation.transactionController);
                    } catch (Exception e) {
                        log.error("Error removing Transaction Controller: {}", e.getMessage());
                    }
                }
                
                // Update the GUI if this was the current node
                if (currentNode != null && currentNode.equals(operation.transactionController)) {
                    // Update to the parent node since we removed the current node
                    guiPackage.updateCurrentNode();
                }
                
                // Refresh the tree structure
                guiPackage.getTreeModel().nodeStructureChanged(operation.parentNode);
            }
            
            // Clear the undo stack after processing
            lastWrapOperation.clear();
            
            // Final UI refresh
            guiPackage.getMainFrame().repaint();
            
            return "Successfully unwrapped " + unwrappedCount + " samplers.";
        } catch (Exception e) {
            log.error("Error undoing wrap operation", e);
            return "Error undoing wrap operation: " + e.getMessage();
        }
    }
    
    /**
     * Redoes the last undone wrap operation.
     * 
     * @return A message indicating the result of the redo operation
     */
    public String redoLastUndo() {
        if (lastUndoneOperation.isEmpty()) {
            return "Nothing to redo.";
        }
        
        try {
            log.info("Redoing last undone wrap operation");
            
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                log.error("GuiPackage is null, cannot redo wrap operation");
                return "Error: JMeter GUI is not available.";
            }
            
            int rewrappedCount = 0;
            List<WrapOperation> operationsToRedo = new ArrayList<>(lastUndoneOperation);
            
            // Process each wrap operation
            for (WrapOperation operation : operationsToRedo) {
                // First check if the Transaction Controller still exists
                boolean transactionControllerExists = false;
                JMeterTreeNode parentNode = operation.parentNode;
                
                for (int i = 0; i < parentNode.getChildCount(); i++) {
                    JMeterTreeNode child = (JMeterTreeNode) parentNode.getChildAt(i);
                    if (child == operation.transactionController) {
                        transactionControllerExists = true;
                        break;
                    }
                }
                
                // If the Transaction Controller doesn't exist, recreate it
                if (!transactionControllerExists) {
                    log.info("Transaction Controller does not exist, recreating it");
                    
                    // Create a new Transaction Controller with the same name
                    String controllerName = operation.transactionController.getName();
                    boolean success = JMeterElementManager.addElement("transactioncontroller", controllerName);
                    
                    if (!success) {
                        log.error("Failed to recreate Transaction Controller: {}", controllerName);
                        continue;
                    }
                    
                    // Get the newly created Transaction Controller
                    JMeterTreeNode newController = guiPackage.getTreeListener().getCurrentNode();
                    operation.transactionController = newController;
                    
                    // Move the new controller to the parent node
                    guiPackage.getTreeModel().removeNodeFromParent(newController);
                    guiPackage.getTreeModel().insertNodeInto(newController, parentNode, parentNode.getChildCount());
                }
                
                // Add this operation to the undo stack for future undo
                lastWrapOperation.add(operation);
                
                // Move samplers back under the Transaction Controller
                for (JMeterTreeNode sampler : operation.movedSamplers) {
                    log.info("Moving sampler '{}' back under Transaction Controller '{}'", 
                            sampler.getName(), operation.transactionController.getName());
                    
                    try {
                        // Remove from current parent if it has one
                        JMeterTreeNode currentParent = (JMeterTreeNode) sampler.getParent();
                        if (currentParent != null) {
                            guiPackage.getTreeModel().removeNodeFromParent(sampler);
                        }
                        
                        // Add to Transaction Controller
                        guiPackage.getTreeModel().insertNodeInto(sampler, operation.transactionController, 
                                operation.transactionController.getChildCount());
                        rewrappedCount++;
                    } catch (Exception e) {
                        log.error("Error moving sampler back under Transaction Controller: {}", e.getMessage());
                    }
                }
                
                // Refresh the tree structure
                guiPackage.getTreeModel().nodeStructureChanged(operation.transactionController);
                guiPackage.getTreeModel().nodeStructureChanged(operation.parentNode);
            }
            
            // Clear the redo stack after processing
            lastUndoneOperation.clear();
            
            // Final UI refresh
            guiPackage.getMainFrame().repaint();
            
            return "Successfully rewrapped " + rewrappedCount + " samplers.";
        } catch (Exception e) {
            log.error("Error redoing wrap operation", e);
            return "Error redoing wrap operation: " + e.getMessage();
        }
    }
    
    /**
     * Class to store information about a wrap operation for undo/redo functionality.
     */
    private static class WrapOperation {
        JMeterTreeNode transactionController;
        List<JMeterTreeNode> movedSamplers;
        JMeterTreeNode parentNode;
        Map<JMeterTreeNode, Integer> originalIndices;
        
        public WrapOperation(JMeterTreeNode transactionController, List<JMeterTreeNode> movedSamplers, 
                            JMeterTreeNode parentNode, Map<JMeterTreeNode, Integer> originalIndices) {
            this.transactionController = transactionController;
            this.movedSamplers = movedSamplers;
            this.parentNode = parentNode;
            this.originalIndices = originalIndices;
        }
    }
}
