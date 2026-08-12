package org.lsposed.privisolated.proc;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of procps {@code pmap}: renders the memory map of a process from
 * /proc/PID/maps. Output mirrors upstream default mode byte for byte:
 * "PID:   cmd" header, "%016lx %6luK %s %s" rows (zero-padded address,
 * 6-wide size with K suffix, 5-char perms, short mapping name) and a
 * " total %16ldK" footer.
 */
public final class Pmap implements ProcTool {
    public static final Pmap INSTANCE = new Pmap();

    private Pmap() {
    }

    @Override
    public String name() {
        return "pmap";
    }

    @Override
    public String run(String arg) {
        var pid = (arg == null || arg.isBlank())
                ? ProcFiles.selfPid()
                : arg.replace("/proc/", "").replace("/maps", "");
        var maps = ProcFiles.readText("/proc/" + pid + "/maps");
        if (maps == null) {
            return "ERROR: cannot read /proc/" + pid + "/maps (permission denied or no such process)";
        }

        var regions = new ArrayList<Region>();
        long total = 0;
        for (var line : maps.split("\n")) {
            if (line.isBlank()) continue;
            var parts = line.split("\\s+", 6);
            if (parts.length < 5) continue;
            var dash = parts[0].indexOf('-');
            if (dash == -1) continue;
            try {
                long start = Long.parseUnsignedLong(parts[0].substring(0, dash), 16);
                long end = Long.parseUnsignedLong(parts[0].substring(dash + 1), 16);
                long size = (end - start) >> 10; // KiB
                var path = parts.length >= 6 ? parts[5] : "";
                total += size;
                regions.add(new Region(start, size, perms(parts[1]), name(path)));
            } catch (NumberFormatException ignored) {
            }
        }

        var sb = new StringBuilder();
        sb.append(pid).append(":   ").append(cmdline(pid)).append('\n');
        for (var region : regions) {
            sb.append(String.format("%016x %6dK %s %s\n",
                    region.address, region.size, region.perms, region.name));
        }
        sb.append(String.format(" total %16dK\n", total));
        return sb.toString();
    }

    /** Upstream: p/s char of the 4-char maps perms becomes '-'/'s', plus a trailing '-'. */
    private static String perms(String raw) {
        if (raw.length() < 4) return raw;
        char p = raw.charAt(3) == 's' ? 's' : '-';
        return raw.substring(0, 3) + p + '-';
    }

    /** Short mapping name like upstream mapping_name(). */
    private static String name(String path) {
        if (path.isEmpty()) return "  [ anon ]";
        if (path.startsWith("[")) { // [stack], [heap], [vdso], [vvar], ...
            return "  " + path.substring(1, path.length() - 1).trim() + " ]";
        }
        int slash = path.lastIndexOf('/');
        return slash == -1 ? path : path.substring(slash + 1);
    }

    /** Header line: full cmdline joined with spaces, or [comm] for kernel threads. */
    private static String cmdline(String pid) {
        var cmdline = ProcFiles.readText("/proc/" + pid + "/cmdline");
        if (cmdline == null || cmdline.isEmpty()) {
            var comm = ProcFiles.readText("/proc/" + pid + "/comm");
            return comm == null ? pid : "[" + comm.trim() + "]";
        }
        return cmdline.replace('\0', ' ').trim();
    }

    private record Region(long address, long size, String perms, String name) {
    }
}
