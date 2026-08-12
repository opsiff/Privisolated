package org.lsposed.privisolated.proc;

import java.util.HashMap;
import java.util.Map;

/**
 * Port of procps {@code vmstat} single-sample output: reads /proc/stat,
 * /proc/vmstat and /proc/meminfo and renders one row of averages since boot,
 * formatted exactly like upstream {@code vmstat 1 1} first row (with -n).
 * Follows upstream semantics: cs = ctxt/Div, guest cpu subtracted from user
 * and reported separately in the trailing "gu" column.
 */
public final class VmStat implements ProcTool {
    public static final VmStat INSTANCE = new VmStat();

    private VmStat() {
    }

    @Override
    public String name() {
        return "vmstat";
    }

    @Override
    public String run(String arg) {
        var statText = ProcFiles.readText("/proc/stat");
        var vmText = ProcFiles.readText("/proc/vmstat");
        var uptimeText = ProcFiles.readText("/proc/uptime");
        var mem = ProcFiles.parseMeminfo();
        if (statText == null || vmText == null || uptimeText == null) {
            return "ERROR: cannot read /proc/stat, /proc/vmstat or /proc/uptime";
        }
        double uptimeSecs;
        try {
            uptimeSecs = Double.parseDouble(uptimeText.trim().split("\\s+")[0]);
        } catch (Exception e) {
            uptimeSecs = 1;
        }

        var stat = parseStat(statText);
        var vm = parseVmstat(vmText);

        long[] cpu = stat.getOrDefault("cpu", new long[10]);
        long userNice = cpu[0] + cpu[1];         // user + nice
        long guest = cpu.length > 8 ? cpu[8] + cpu[9] : 0; // guest + guest_nice
        long sys = cpu[2] + cpu[5] + cpu[6];     // system + irq + softirq
        long idle = cpu[3];
        long iowait = cpu[4];
        long steal = cpu.length > 7 ? cpu[7] : 0;
        long div = userNice + sys + idle + iowait + steal;
        if (div == 0) {
            div = 1;
            idle = 1;
        }
        long divo2 = div / 2;
        long user = userNice >= guest ? userNice - guest : 0;
        int us = pct100(user, div, divo2);
        int sy = pct100(sys, div, divo2);
        int id = pct100(idle, div, divo2);
        int wa = pct100(iowait, div, divo2);
        int st = pct100(steal, div, divo2);
        int gu = pct100(guest, div, divo2);

        int r = (int) stat.getOrDefault("procs_running", new long[]{0})[0];
        int b = (int) stat.getOrDefault("procs_blocked", new long[]{0})[0];
        long intr = stat.getOrDefault("intr", new long[]{0})[0];
        long ctxt = stat.getOrDefault("ctxt", new long[]{0})[0];

        long swpd = get(mem, "SwapTotal") - get(mem, "SwapFree");
        long freeMem = get(mem, "MemFree");
        long buff = get(mem, "Buffers");
        long cache = get(mem, "Cached") + get(mem, "SReclaimable");

        long kbPerPage = 4; // sysconf(_SC_PAGESIZE) / 1024, 4K pages
        long pgpgin = vm.getOrDefault("pgpgin", 0L);
        long pgpgout = vm.getOrDefault("pgpgout", 0L);
        long pswpin = vm.getOrDefault("pswpin", 0L);
        long pswpout = vm.getOrDefault("pswpout", 0L);

        var sb = new StringBuilder();
        sb.append("procs -----------memory---------- ---swap-- -----io---- -system-- -------cpu-------\n");
        sb.append(" r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa st gu\n");
        sb.append(String.format("%2d %2d %6d %6d %6d %6d %4d %4d %5d %5d %4d %4d %2d %2d %2d %2d %2d %2d\n",
                r, b, swpd, freeMem, buff, cache,
                (long) (pswpin * kbPerPage / uptimeSecs), (long) (pswpout * kbPerPage / uptimeSecs),
                (long) (pgpgin / uptimeSecs), (long) (pgpgout / uptimeSecs),
                (long) (intr / uptimeSecs), (long) (ctxt / div),
                us, sy, id, wa, st, gu));
        return sb.toString();
    }

    /** Upstream: (100 * value + div/2) / div. */
    private static int pct100(long value, long div, long divo2) {
        return (int) ((100 * value + divo2) / div);
    }

    private static Map<String, long[]> parseStat(String text) {
        var map = new HashMap<String, long[]>();
        for (var line : text.split("\n")) {
            if (line.trim().isEmpty()) continue;
            var parts = line.split("\\s+");
            var key = parts[0];
            var values = new long[parts.length - 1];
            for (int i = 1; i < parts.length; i++) {
                try {
                    values[i - 1] = Long.parseLong(parts[i]);
                } catch (NumberFormatException ignored) {
                }
            }
            map.put(key, values);
        }
        return map;
    }

    private static Map<String, Long> parseVmstat(String text) {
        var map = new HashMap<String, Long>();
        for (var line : text.split("\n")) {
            var parts = line.trim().split("\\s+");
            if (parts.length < 2) continue;
            try {
                map.put(parts[0], Long.parseLong(parts[1]));
            } catch (NumberFormatException ignored) {
            }
        }
        return map;
    }

    private static long get(Map<String, Long> map, String key) {
        return map.getOrDefault(key, 0L);
    }
}
