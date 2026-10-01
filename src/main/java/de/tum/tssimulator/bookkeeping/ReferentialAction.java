package de.tum.tssimulator.bookkeeping;

/**
 * Defines the action to take when a cross-model bookkeeper is triggered.
 * 
 * ON_CASCADE: Process bookkeeper normally (default behavior - already implemented)
 * ON_RESTRICT: Put parent transaction aside for user action, release locks, start new transaction
 */
public enum ReferentialAction {
    /**
     * ON CASCADE: Allow bookkeepers to execute normally.
     * This is the current default behavior.
     */
    ON_CASCADE,
    
    /**
     * ON RESTRICT: When bookkeeper is launched, put parent transaction aside
     * for user action, release its locks, undo its actions, and start a new
     * transaction in its place.
     */
    ON_RESTRICT
}
