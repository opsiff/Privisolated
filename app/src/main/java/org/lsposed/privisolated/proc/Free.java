package org.lsposed.privisolated.proc;

/**
 * Port of procps {@code free}: reads /proc/meminfo and renders the classic
 * "Mem:/Swap:" table in KiB (equivalent to upstream {@code free -k}).
 * Uses upstream semantics: used = total - available,
 * cache = Cached + SReclaimable, buff/cache column = Buffers + cache.
 * The label column is padded to 9 chars and numbers to 11, like upstream.
 */
public final class Free implements ProcTool {
    public static final Free INSTANCE = new Free();

    private Free() {
    }

    @Override
    public String name() {
        return "free";
    }

    @Override
    public String run(String arg) {
        var mem = ProcFiles.parseMeminfo();
        long total = get(mem, "MemTotal");
        long freeMem = get(mem, "MemFree");
        long available = get(mem, "MemAvailable");
        long buffers = get(mem, "Buffers");
        long cached = get(mem, "Cached") + get(mem, "SReclaimable");
        long shared = get(mem, "Shmem");
        long swapTotal = get(mem, "SwapTotal");
        long swapFree = get(mem, "SwapFree");

        long used = total - available;
        long swapUsed = swapTotal - swapFree;

        var sb = new StringBuilder();
        sb.append("               total        used        free      shared  buff/cache   available\n");
        sb.append(String.format("Mem:     %11s %11s %11s %11s %11s %11s\n",
                total, used, freeMem, shared, buffers + cached, available));
        sb.append(String.format("Swap:    %11s %11s %11s\n", swapTotal, swapUsed, swapFree));
        return sb.toString();
    }

    private static long get(java.util.Map<String, Long> map, String key) {
        return map.getOrDefault(key, 0L);
    }
}
