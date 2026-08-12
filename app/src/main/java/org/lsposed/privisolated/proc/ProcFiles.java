package org.lsposed.privisolated.proc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Shared low-level access to {@code /proc} for the procps-style tools.
 * All methods are pure Java so they can be exercised on a host too.
 */
public final class ProcFiles {
    private ProcFiles() {
    }

    /** Reads a whole file as a String, or {@code null} if unreadable. */
    public static String readText(String path) {
        try {
            return Files.readString(Paths.get(path));
        } catch (IOException e) {
            return null;
        }
    }

    /** Resolves a symlink, or {@code null} if unreadable. */
    public static String readLink(String path) {
        try {
            return Files.readSymbolicLink(Paths.get(path)).toString();
        } catch (IOException e) {
            return null;
        }
    }

    /** Lists numeric PIDs present in /proc, sorted ascending. */
    public static List<String> listPids() {
        var pids = new ArrayList<String>();
        try (Stream<Path> stream = Files.list(Paths.get("/proc"))) {
            stream.forEach(path -> {
                var name = path.getFileName().toString();
                if (name.chars().allMatch(Character::isDigit)) {
                    pids.add(name);
                }
            });
        } catch (IOException ignored) {
        }
        pids.sort((a, b) -> Integer.compareUnsigned(Integer.parseUnsignedInt(a),
                Integer.parseUnsignedInt(b)));
        return pids;
    }

    /** Returns the PID of the current process by reading /proc/self/stat. */
    public static String selfPid() {
        var stat = readText("/proc/self/stat");
        if (stat == null) return "0";
        var space = stat.indexOf(' ');
        return space == -1 ? "0" : stat.substring(0, space);
    }

    /** Parses "key: value kB" lines from /proc/meminfo into a map. */
    public static java.util.Map<String, Long> parseMeminfo() {
        var map = new java.util.LinkedHashMap<String, Long>();
        var text = readText("/proc/meminfo");
        if (text == null) return map;
        for (var line : text.split("\n")) {
            var colon = line.indexOf(':');
            if (colon <= 0) continue;
            var key = line.substring(0, colon);
            var rest = line.substring(colon + 1).trim();
            var space = rest.indexOf(' ');
            try {
                var value = Long.parseLong(space == -1 ? rest : rest.substring(0, space));
                map.put(key, value);
            } catch (NumberFormatException ignored) {
            }
        }
        return map;
    }
}
