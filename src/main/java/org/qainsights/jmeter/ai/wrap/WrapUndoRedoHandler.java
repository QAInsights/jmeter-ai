package org.qainsights.jmeter.ai.wrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for undo and redo operations related to the @wrap command.
 * This class provides methods to undo and redo wrap operations performed on samplers.
 */
public class WrapUndoRedoHandler {
    private static final Logger log = LoggerFactory.getLogger(WrapUndoRedoHandler.class);
    
    // Singleton instance
    private static WrapUndoRedoHandler instance;
    
    // The WrapCommandHandler to delegate operations to
    private final WrapCommandHandler wrapCommandHandler;
    
    /**
     * Private constructor to enforce singleton pattern.
     */
    private WrapUndoRedoHandler() {
        wrapCommandHandler = new WrapCommandHandler();
    }
    
    /**
     * Gets the singleton instance of the WrapUndoRedoHandler.
     * 
     * @return The singleton instance
     */
    public static synchronized WrapUndoRedoHandler getInstance() {
        if (instance == null) {
            instance = new WrapUndoRedoHandler();
        }
        return instance;
    }
    
    /**
     * Undoes the last wrap operation.
     * 
     * @return A message indicating the result of the undo operation
     */
    public String undoLastWrap() {
        log.info("Undoing last wrap operation");
        return wrapCommandHandler.undoLastWrap();
    }
    
    /**
     * Redoes the last undone wrap operation.
     * 
     * @return A message indicating the result of the redo operation
     */
    public String redoLastUndo() {
        log.info("Redoing last undone wrap operation");
        return wrapCommandHandler.redoLastUndo();
    }
}
