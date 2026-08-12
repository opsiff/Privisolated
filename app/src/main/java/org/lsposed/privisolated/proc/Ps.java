package org.lsposed.privisolated.proc;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of procps {@code ps}: renders a process snapshot from /proc/PID/stat,
 * /proc/PID/comm and /proc/PID/cmdline. Columns are a compact subset of the
 * upstream defaults: PID, PPID, STAT, TIME, CMD (sorted by PID).
 */
public final class Ps implements ProcTool {
    public static final Ps INSTANCE = new Ps();

    /** Order of fields inside /proc/PID/stat after the parenthesized comm. */
    private static final int STATE = 0;   // field 3
    private static final int PPID = 1;    // field 4
    private static final int UTIME = 11;  // field 14
    private static final int STIME = 12;  // field 15

    private Ps() {
    }

    @Override
    public String name() {
        return "ps";
    }

    @Override
    public String run(String arg) {
        var pids = ProcFiles.listPids();
        var rows = new ArrayList<Row>();
        int unreadable = 0;
        for (var pid : pids) {
            try {
                var stat = ProcFiles.readText("/proc/" + pid + "/stat");
                if (stat == null) {
                    // SELinux may deny reading other domains' /proc/PID/stat
                    // even with gid 3009 (DAC); count it so filtering is visible.
                    unreadable++;
                    continue;
                }
                var close = stat.lastIndexOf(')');
                if (close == -1) {
                    unreadable++;
                    continue;
                }
                String comm = stat.substring(stat.indexOf('(') + 1, close);
                String[] fields = stat.substring(close + 1).trim().split("\\s+");
                if (fields.length <= STIME) {
                    unreadable++;
                    continue;
                }
                var cmdline = ProcFiles.readText("/proc/" + pid + "/cmdline");
                String cmd;
                if (cmdline == null || cmdline.isEmpty()) {
                    cmd = "[" + comm + "]";
                } else {
                    cmd = cmdline.replace('\0', ' ').trim();
                }
                long ticks = Long.parseLong(fields[UTIME]) + Long.parseLong(fields[STIME]);
                rows.add(new Row(pid, fields[PPID], fields[STATE], ticks, cmd));
            } catch (RuntimeException e) {
                unreadable++;
            }
        }
        rows.sort((a, b) -> Integer.compareUnsigned(Integer.parseUnsignedInt(a.pid),
                Integer.parseUnsignedInt(b.pid)));

        var sb = new StringBuilder();
        sb.append(String.format("%-7s %-7s %-5s %-8s %s\n", "PID", "PPID", "STAT", "TIME", "CMD"));
        for (var row : rows) {
            sb.append(String.format("%-7s %-7s %-5s %-8s %s\n",
                    row.pid, row.ppid, row.state, formatTicks(row.ticks), row.cmd));
        }
        if (unreadable > 0) {
            sb.append(rows.size()).append(" readable of ").append(pids.size())
                    .append(" processes (").append(unreadable)
                    .append(" hidden by SELinux/perms)\n");
        } else {
            sb.append(rows.size()).append(" processes\n");
        }
        return sb.toString();
    }

    private static String formatTicks(long ticks) {
        long seconds = ticks / 100; // USER_HZ
        return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    private record Row(String pid, String ppid, String state, long ticks, String cmd) {
    }
}
