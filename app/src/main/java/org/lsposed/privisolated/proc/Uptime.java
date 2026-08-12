package org.lsposed.privisolated.proc;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Port of procps {@code uptime}: reads /proc/uptime and /proc/loadavg.
 * Output mirrors upstream: " HH:MM:SS up X days, H:MM, N user(s),  load average: ...".
 * The user count is approximated from /proc (utmp is not readable on Android).
 */
public final class Uptime implements ProcTool {
    public static final Uptime INSTANCE = new Uptime();

    private Uptime() {
    }

    @Override
    public String name() {
        return "uptime";
    }

    @Override
    public String run(String arg) {
        var uptimeText = ProcFiles.readText("/proc/uptime");
        var loadText = ProcFiles.readText("/proc/loadavg");
        if (uptimeText == null || loadText == null) {
            return "ERROR: cannot read /proc/uptime or /proc/loadavg";
        }
        String[] uptimeParts = uptimeText.trim().split("\\s+");
        double uptimeSecs;
        try {
            uptimeSecs = Double.parseDouble(uptimeParts[0]);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return "ERROR: malformed /proc/uptime: " + uptimeText.trim();
        }
        String[] loadParts = loadText.trim().split("\\s+");
        double av1, av5, av15;
        try {
            av1 = Double.parseDouble(loadParts[0]);
            av5 = Double.parseDouble(loadParts[1]);
            av15 = Double.parseDouble(loadParts[2]);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return "ERROR: malformed /proc/loadavg: " + loadText.trim();
        }

        long days = (long) (uptimeSecs / 86400);
        long hours = (long) ((uptimeSecs % 86400) / 3600);
        long minutes = (long) ((uptimeSecs % 3600) / 60);
        String upStr;
        if (days > 0) {
            upStr = String.format("%d days, %2d:%02d", days, hours, minutes);
        } else if (hours > 0) {
            upStr = String.format("%2d:%02d", hours, minutes);
        } else {
            upStr = String.format("%d min", minutes);
        }

        var now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        int users = countTtyUsers();
        return String.format(" %s up %s, %2d %s,  load average: %.2f, %.2f, %.2f",
                now, upStr, users, users == 1 ? "user" : "users", av1, av5, av15);
    }

    /** Counts processes with a non-zero tty_nr (field 7 of /proc/PID/stat). */
    static int countTtyUsers() {
        int users = 0;
        for (var pid : ProcFiles.listPids()) {
            var stat = ProcFiles.readText("/proc/" + pid + "/stat");
            if (stat == null) continue;
            int close = stat.lastIndexOf(')');
            if (close == -1) continue;
            String[] fields = stat.substring(close + 1).trim().split("\\s+");
            // state, ppid, pgrp, session, tty_nr
            if (fields.length > 4 && !"0".equals(fields[4])) {
                users++;
            }
        }
        return users;
    }
}
