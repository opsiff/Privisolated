package org.lsposed.privisolated.proc;

/**
 * Common interface for the procps-style tools rendered in the GUI.
 * Implementations must be pure Java so they can be run on a host too.
 */
public interface ProcTool {
    /** Short tool name used for the GUI button and titles. */
    String name();

    /**
     * Produces the tool output. {@code arg} is the optional user-supplied
     * argument (process name or PID); tools that do not need one ignore it.
     */
    String run(String arg);

    /** Whether the tool needs a non-empty argument (name/PID) to be useful. */
    default boolean requiresArg() {
        return false;
    }
}
