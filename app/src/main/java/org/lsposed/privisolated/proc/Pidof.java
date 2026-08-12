package org.lsposed.privisolated.proc;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of procps {@code pidof}: lists PIDs whose program name matches the
 * queried name. Matching follows upstream: the process argv[0] basename or
 * the /proc/PID/exe basename must equal the query. PIDs are printed in
 * descending order (newest first), like upstream.
 */
public final class Pidof implements ProcTool {
    public static final Pidof INSTANCE = new Pidof();

    private Pidof() {
    }

    @Override
    public String name() {
        return "pidof";
    }

    @Override
    public boolean requiresArg() {
        return true;
    }

    @Override
    public String run(String arg) {
        if (arg == null || arg.trim().isEmpty()) {
            return "ERROR: usage: pidof <program name>";
        }
        var pids = new ArrayList<String>();
        for (var pid : ProcFiles.listPids()) {
            if (matches(pid, arg)) {
                pids.add(pid);
            }
        }
        if (pids.isEmpty()) {
            return "";
        }
        // upstream displays pids from newest to oldest
        pids.sort((a, b) -> Integer.compareUnsigned(
                Integer.parseUnsignedInt(b), Integer.parseUnsignedInt(a)));
        return String.join(" ", pids);
    }

    private static boolean matches(String pid, String program) {
        var cmdline = ProcFiles.readText("/proc/" + pid + "/cmdline");
        if (cmdline != null && !cmdline.isEmpty()) {
            int end = cmdline.indexOf('\0');
            String argv0 = end == -1 ? cmdline : cmdline.substring(0, end);
            if (argv0.startsWith("-")) argv0 = argv0.substring(1); // login shells
            if (program.equals(argv0) || program.equals(basename(argv0))) {
                return true;
            }
        }
        var exe = ProcFiles.readLink("/proc/" + pid + "/exe");
        return exe != null && program.equals(basename(exe));
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash == -1 ? path : path.substring(slash + 1);
    }
}
