package org.lsposed.privisolated;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;

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
