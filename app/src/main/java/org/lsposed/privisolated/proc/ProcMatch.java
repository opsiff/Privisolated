package org.lsposed.privisolated.proc;

/**
 * Shared process-name matching used by {@code pidof} and {@code pgrep}.
 * A process matches if the program name (basename of comm or cmdline)
 * equals the queried name, like upstream.
 */
final class ProcMatch {
    private ProcMatch() {
    }

    /** Returns PIDs whose program name equals {@code name}. */
    static java.util.List<String> byName(String name) {
        var result = new java.util.ArrayList<String>();
        for (var pid : ProcFiles.listPids()) {
            var comm = ProcFiles.readText("/proc/" + pid + "/comm");
            var name1 = comm == null ? null : comm.trim();
            var cmdline = ProcFiles.readText("/proc/" + pid + "/cmdline");
            String name2 = null;
            if (cmdline != null && !cmdline.isEmpty()) {
                int end = cmdline.indexOf('\0');
                String first = end == -1 ? cmdline : cmdline.substring(0, end);
                int slash = first.lastIndexOf('/');
                name2 = slash == -1 ? first : first.substring(slash + 1);
            }
            if (name.equals(name1) || name.equals(name2)) {
                result.add(pid);
            }
        }
        return result;
    }
}
