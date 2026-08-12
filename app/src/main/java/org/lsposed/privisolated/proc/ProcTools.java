package org.lsposed.privisolated.proc;

import java.util.List;

/**
 * Registry of the procps-style tools shown in the GUI. Adding a tool here
 * automatically adds its button in MainActivity.
 */
public final class ProcTools {
    private ProcTools() {
    }

    public static final List<ProcTool> ALL = List.of(
            Uptime.INSTANCE,
            Free.INSTANCE,
            VmStat.INSTANCE,
            Ps.INSTANCE,
            Pidof.INSTANCE,
            Pgrep.INSTANCE,
            Pwdx.INSTANCE,
            Pmap.INSTANCE,
            Top.INSTANCE
    );
}
