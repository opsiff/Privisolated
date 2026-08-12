package org.lsposed.privisolated.proc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of procps {@code top} as a single-shot snapshot (like {@code top -b -n1}):
 * summary header (uptime, tasks, %Cpu, Mem/Swap) plus a process table sorted by
 * %CPU. %CPU is the lifetime average (cpu ticks / elapsed since start), since a
 * snapshot has no previous sample to diff against. User names are shown as uid
 * numbers because /etc/passwd is not readable from the isolated process.
 */
public final class Top implements ProcTool {
    public static final Top INSTANCE = new Top();

    /** Field offsets in /proc/PID/stat after the parenthesized comm. */
    private static final int STATE = 0;      // field 3
    private static final int UTIME = 11;     // field 14
    private static final int STIME = 12;     // field 15
    private static final int PRIORITY = 15;  // field 18
    private static final int NICE = 16;      // field 19
    private static final int STARTTIME = 19; // field 22
    private static final int VSIZE = 20;     // field 23
    private static final int RSS = 21;       // field 24

    private static final int PAGE = 4096;
    private static final int USER_HZ = 100;

    private Top() {
    }

    @Override
    public String name() {
        return "top";
    }

    @Override
    public String run(String arg) {
        var statText = ProcFiles.readText("/proc/stat");
        var uptimeText = ProcFiles.readText("/proc/uptime");
        var loadText = ProcFiles.readText("/proc/loadavg");
        if (statText == null || uptimeText == null || loadText == null) {
            return "ERROR: cannot read /proc/stat, /proc/uptime or /proc/loadavg";
        }
        double uptimeSecs;
        try {
            uptimeSecs = Double.parseDouble(uptimeText.trim().split("\\s+")[0]);
        } catch (Exception e) {
            return "ERROR: malformed /proc/uptime: " + uptimeText.trim();
        }
        String[] loadParts = loadText.trim().split("\\s+");
        double av1, av5, av15;
        try {
            av1 = Double.parseDouble(loadParts[0]);
            av5 = Double.parseDouble(loadParts[1]);
            av15 = Double.parseDouble(loadParts[2]);
        } catch (Exception e) {
            return "ERROR: malformed /proc/loadavg: " + loadText.trim();
        }

        var mem = ProcFiles.parseMeminfo();
        long memTotal = mem.getOrDefault("MemTotal", 0L);
        var cpu = parseStatCpu(statText);
        long cpuTotal = sum(cpu);

        var rows = new ArrayList<Row>();
        int running = 0, stopped = 0, zombie = 0;
        for (var pid : ProcFiles.listPids()) {
            try {
                var stat = ProcFiles.readText("/proc/" + pid + "/stat");
                if (stat == null) continue;
                var close = stat.lastIndexOf(')');
                if (close == -1) continue;
                String comm = stat.substring(stat.indexOf('(') + 1, close);
                String[] f = stat.substring(close + 1).trim().split("\\s+");
                if (f.length <= RSS) continue;

                String state = f[STATE];
                if ("R".equals(state)) running++;
                else if ("T".equals(state)) stopped++;
                else if ("Z".equals(state)) zombie++;

                long ticks = Long.parseLong(f[UTIME]) + Long.parseLong(f[STIME]);
                long startTicks = Long.parseLong(f[STARTTIME]);
                long vsize = Long.parseLong(f[VSIZE]);
                long rssPages = Long.parseLong(f[RSS]);
                double elapsed = uptimeSecs - startTicks / (double) USER_HZ;
                double cpuPct = elapsed > 0 ? 100.0 * ticks / USER_HZ / elapsed : 0;
                double memPct = memTotal > 0 ? 100.0 * rssPages * PAGE / 1024 / memTotal : 0;

                var status = ProcFiles.readText("/proc/" + pid + "/status");
                String uid = uidOf(status);
                var statm = ProcFiles.readText("/proc/" + pid + "/statm");
                long sharedKb = sharedKbOf(statm);
                var cmdline = ProcFiles.readText("/proc/" + pid + "/cmdline");
                String cmd = (cmdline == null || cmdline.isEmpty())
                        ? "[" + comm + "]"
                        : cmdline.replace('\0', ' ').trim();

                rows.add(new Row(pid, uid, Integer.parseInt(f[PRIORITY]), Integer.parseInt(f[NICE]),
                        vsize, rssPages * PAGE / 1024, sharedKb, state, cpuPct, memPct, ticks, cmd));
            } catch (RuntimeException e) {
                // skip one malformed process, keep the rest
            }
        }
        rows.sort((a, b) -> Double.compare(b.cpuPct, a.cpuPct));

        var sb = new StringBuilder();
        sb.append("top - ").append(uptimeString(uptimeSecs, av1, av5, av15)).append('\n');
        int total = rows.size();
        int sleeping = total - running - stopped - zombie;
        sb.append(String.format("Tasks: %3d total, %3d running, %3d sleeping, %3d stopped, %3d zombie\n\n",
                total, running, sleeping, stopped, zombie));
        sb.append(String.format("%%Cpu(s):%5.1f us, %5.1f sy, %5.1f ni, %5.1f id, %5.1f wa, %5.1f hi, %5.1f si, %5.1f st \n",
                pct(cpu[0], cpuTotal), pct(cpu[2], cpuTotal), pct(cpu[1], cpuTotal),
                pct(cpu[3], cpuTotal), pct(cpu[4], cpuTotal), pct(cpu[5], cpuTotal),
                pct(cpu[6], cpuTotal), pct(cpu[7], cpuTotal)));
        long availKb = mem.getOrDefault("MemAvailable", 0L);
        long buffCacheKb = mem.getOrDefault("Buffers", 0L) + mem.getOrDefault("Cached", 0L)
                + mem.getOrDefault("SReclaimable", 0L);
        sb.append(String.format("MiB Mem :%9.1f total, %9.1f free, %9.1f used, %9.1f buff/cache     \n",
                memTotal / 1024.0, mem.getOrDefault("MemFree", 0L) / 1024.0,
                (memTotal - availKb) / 1024.0, buffCacheKb / 1024.0));
        long swapTotalKb = mem.getOrDefault("SwapTotal", 0L);
        long swapFreeKb = mem.getOrDefault("SwapFree", 0L);
        sb.append(String.format("MiB Swap:%9.1f total, %9.1f free, %9.1f used. %9.1f avail Mem \n\n",
                swapTotalKb / 1024.0, swapFreeKb / 1024.0, (swapTotalKb - swapFreeKb) / 1024.0,
                availKb / 1024.0));
        sb.append(String.format("%7s %-8s %3s %3s %8s %7s %6s %s %5s %5s %10s %s\n",
                "PID", "USER", "PR", "NI", "VIRT", "RES", "SHR", "S", "%CPU", "%MEM", "TIME+", "COMMAND"));
        for (var row : rows) {
            sb.append(String.format("%7s %-8s %3d %3d %8s %7s %6s %s %5.1f %5.1f %10s %s\n",
                    row.pid, row.uid, row.priority, row.nice, scaleNum(row.vsizeKb),
                    scaleNum(row.resKb), scaleNum(row.sharedKb), row.state, row.cpuPct, row.memPct,
                    formatTicks(row.ticks), row.cmd));
        }
        sb.append(rows.size()).append(" processes\n");
        return sb.toString();
    }

    private static String uptimeString(double uptimeSecs, double av1, double av5, double av15) {
        long days = (long) (uptimeSecs / 86400);
        long hours = (long) ((uptimeSecs % 86400) / 3600);
        long minutes = (long) ((uptimeSecs % 3600) / 60);
        String up = days > 0 ? String.format("%d days, %2d:%02d", days, hours, minutes)
                : hours > 0 ? String.format("%2d:%02d", hours, minutes)
                : String.format("%d min", minutes);
        int users = ttyUsers();
        return String.format("%s up %s, %2d %s,  load average: %.2f, %.2f, %.2f",
                java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")),
                up, users, users == 1 ? "user" : "users", av1, av5, av15);
    }

    private static int ttyUsers() {
        int users = 0;
        for (var pid : ProcFiles.listPids()) {
            var stat = ProcFiles.readText("/proc/" + pid + "/stat");
            if (stat == null) continue;
            int close = stat.lastIndexOf(')');
            if (close == -1) continue;
            String[] f = stat.substring(close + 1).trim().split("\\s+");
            if (f.length > 4 && !"0".equals(f[4])) users++;
        }
        return users;
    }

    /** First Uid from /proc/PID/status, or "-". */
    private static String uidOf(String status) {
        if (status == null) return "-";
        for (var line : status.split("\n")) {
            if (line.startsWith("Uid:")) {
                var parts = line.trim().split("\\s+");
                return parts.length > 1 ? parts[1] : "-";
            }
        }
        return "-";
    }

    /** Shared pages (field 3) from /proc/PID/statm, in KiB, or 0. */
    private static long sharedKbOf(String statm) {
        if (statm == null) return 0;
        var parts = statm.trim().split("\\s+");
        if (parts.length < 3) return 0;
        try {
            return Long.parseLong(parts[2]) * PAGE / 1024;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long[] parseStatCpu(String text) {
        var cpu = new long[8];
        for (var line : text.split("\n")) {
            if (!line.startsWith("cpu ") && !line.startsWith("cpu\t")) continue;
            var parts = line.trim().split("\\s+");
            for (int i = 1; i < parts.length && i - 1 < cpu.length; i++) {
                try {
                    cpu[i - 1] = Long.parseLong(parts[i]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return cpu;
    }

    private static long sum(long[] a) {
        long s = 0;
        for (var v : a) s += v;
        return s == 0 ? 1 : s;
    }

    private static double pct(long part, long total) {
        return 100.0 * part / total;
    }

    /** Like top's scale_num: raw if it fits, then K/M/G/T suffixes (1024-based). */
    private static String scaleNum(long kb) {
        if (kb < 10000) return String.valueOf(kb);
        double v = kb;
        char[] suffix = {'K', 'M', 'G', 'T'};
        int i = -1;
        while (v >= 10000 && i < suffix.length - 1) {
            v /= 1024.0;
            i++;
        }
        if (v >= 100) {
            return String.format("%.0f%c", v, suffix[i]);
        }
        return String.format("%.1f%c", v, suffix[i]);
    }

    /** mm:ss.hh from cpu ticks at USER_HZ=100. */
    private static String formatTicks(long ticks) {
        long centi = ticks % USER_HZ;
        long secs = ticks / USER_HZ;
        return String.format("%d:%02d.%02d", secs / 60, secs % 60, centi);
    }

    private record Row(String pid, String uid, int priority, int nice, long vsizeKb,
                       long resKb, long sharedKb, String state, double cpuPct, double memPct,
                       long ticks, String cmd) {
    }
}
