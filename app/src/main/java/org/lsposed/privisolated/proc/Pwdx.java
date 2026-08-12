package org.lsposed.privisolated.proc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Port of procps {@code pwdx}: prints the current working directory of one or
 * more processes by resolving the /proc/PID/cwd symlink, e.g. "123: /path".
 * With no argument it prints the caller's own cwd, like upstream.
 */
public final class Pwdx implements ProcTool {
    public static final Pwdx INSTANCE = new Pwdx();

    private Pwdx() {
    }

    @Override
    public String name() {
        return "pwdx";
    }

    @Override
    public String run(String arg) {
        if (arg == null || arg.trim().isEmpty()) {
            return line(ProcFiles.selfPid());
        }
        var sb = new StringBuilder();
        for (var token : arg.split("\\s+")) {
            sb.append(line(normalize(token))).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static String line(String pid) {
        String target = ProcFiles.readLink("/proc/" + pid + "/cwd");
        if (target == null) {
            return pid + ": ERROR: cannot read cwd (permission denied or no such process)";
        }
        return pid + ": " + target;
    }

    /** Accepts both "PID" and "/proc/PID" forms, like upstream pwdx. */
    private static String normalize(String token) {
        return token.startsWith("/proc/") && token.endsWith("/cwd")
                ? token.substring("/proc/".length(), token.length() - "/cwd".length())
                : token.replace("/proc/", "");
    }
}
