package org.lsposed.privisolated.proc;

/**
 * Port of procps {@code pgrep}: lists PIDs whose program name matches the
 * queried name, one per line. The plain (non -f) upstream behaviour.
 */
public final class Pgrep implements ProcTool {
    public static final Pgrep INSTANCE = new Pgrep();

    private Pgrep() {
    }

    @Override
    public String name() {
        return "pgrep";
    }

    @Override
    public boolean requiresArg() {
        return true;
    }

    @Override
    public String run(String arg) {
        if (arg == null || arg.isBlank()) {
            return "ERROR: usage: pgrep <program name>";
        }
        var pids = ProcMatch.byName(arg);
        if (pids.isEmpty()) {
            return "";
        }
        return String.join("\n", pids);
    }
}
