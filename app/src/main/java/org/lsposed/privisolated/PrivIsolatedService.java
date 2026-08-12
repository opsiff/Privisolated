package org.lsposed.privisolated;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import org.lsposed.privisolated.proc.ProcTools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;

public class PrivIsolatedService extends Service {
    private final IPrivIsolatedService.Stub binder = new IPrivIsolatedService.Stub() {
        @Override
        public String getResult() {
            return readProc();
        }

        @Override
        public String getToolResult(String tool, String arg) {
            for (var procTool : ProcTools.ALL) {
                if (procTool.name().equals(tool)) {
                    // An uncaught exception on the binder thread kills the whole
                    // isolated process (the client then sees DeadObjectException),
                    // so convert any tool failure into a returned error instead.
                    try {
                        return procTool.run(arg);
                    } catch (Throwable t) {
                        return "ERROR: " + tool + " crashed: " + Log.getStackTraceString(t);
                    }
                }
            }
            return "ERROR: unknown tool: " + tool;
        }
    };

    private static String readProc() {
        var dir = Paths.get("/proc");
        try (var stream = Files.newDirectoryStream(dir, Files::isDirectory)) {
            int expected = 1;
            var set = new HashSet<String>();
            for (var path : stream) {
                var name = path.getFileName().toString();
                if (!name.chars().allMatch(Character::isDigit)) continue;

                var infos = MountInfo.scan(name);
                if (infos.get(0).optional().startsWith("shared")) {
                    expected = 2;
                }

                var builder = new StringBuilder();
                infos.sort(null);
                for (var info : infos) {
                    var str = info.source() + ' ' + info.root() + ' ' + info.point() + ' ' +
                            info.type() + ' ' + info.options() + ' ' + info.superOptions();
                    if (str.contains("magisk") || str.contains("KSU") || str.contains("/adb/")) {
                        return "WARN: " + str;
                    }
                    builder.append(str).append('\n');
                }
                set.add(builder.toString());
            }
            if (set.size() != expected) {
                return "WARN: Found hidden mount points";
            } else {
                return "OK: Not found";
            }
        } catch (IOException e) {
            return "ERROR: Failed to read /proc: " + e.getMessage();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return Process.isIsolated() ? binder : null;
    }
}
