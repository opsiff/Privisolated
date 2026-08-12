package org.lsposed.privisolated.proc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java browsing of the {@code /proc} tree. Called from the isolated
 * service, where gid 3009 grants access to other processes' entries.
 * Host-testable (no Android APIs).
 */
public final class ProcFs {
    private ProcFs() {
    }

    /** Sorted entries of {@code path}; directories carry a trailing '/'. */
    public static List<String> list(String path) throws IOException {
        var names = new ArrayList<String>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(path))) {
            for (var entry : stream) {
                var name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    name += "/";
                }
                names.add(name);
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    /** Reads at most {@code maxBytes} as text; possible truncation is marked. */
    public static String read(String path, int maxBytes) throws IOException {
        var bytes = readBytes(path, maxBytes);
        var text = new String(bytes, StandardCharsets.UTF_8);
        if (bytes.length == maxBytes) {
            text += "\n... [possibly truncated at " + maxBytes + " bytes]";
        }
        return text;
    }

    /** Reads raw bytes (binary-safe), capped at {@code maxBytes}. */
    public static byte[] readBytes(String path, int maxBytes) throws IOException {
        var all = Files.readAllBytes(Paths.get(path));
        if (all.length <= maxBytes) {
            return all;
        }
        var part = new byte[maxBytes];
        System.arraycopy(all, 0, part, 0, maxBytes);
        return part;
    }
}
