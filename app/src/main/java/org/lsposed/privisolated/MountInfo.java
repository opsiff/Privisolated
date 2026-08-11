package org.lsposed.privisolated;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public record MountInfo(
        int id,
        int parent,
        long device,
        String root,
        String point,
        String options,
        String optional,
        String type,
        String source,
        String superOptions
) implements Comparable<MountInfo> {

    @Override
    public int compareTo(MountInfo o) {
        var cmp = Integer.compareUnsigned(getPeerGroup(), o.getPeerGroup());
        if (cmp != 0) return cmp;
        cmp = point.compareTo(o.point);
        if (cmp != 0) return cmp;
        return Integer.compareUnsigned(id, o.id);
    }

    public int getPeerGroup() {
        var colonPos = optional.indexOf(':');
        if (colonPos == -1) return 0;
        var spacePos = optional.indexOf(' ', colonPos);
        if (spacePos == -1) spacePos = optional.length();
        var groupStr = optional.substring(colonPos + 1, spacePos);
        return Integer.parseUnsignedInt(groupStr);
    }

    public static List<MountInfo> scan(String pid) {
        var infoList = new ArrayList<MountInfo>();
        var path = Paths.get("/proc", pid, "mountinfo");
        try (var reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                infoList.add(parseLine(line));
            }
            return infoList;
        } catch (IOException e) {
            return List.of();
        }
    }

    public static MountInfo parseLine(String line) {
        int cursor = 0;

        // 1. mount ID
        int nextSpace = line.indexOf(' ', cursor);
        int id = Integer.parseUnsignedInt(line, cursor, nextSpace, 10);
        cursor = nextSpace + 1;

        // 2. parent ID
        nextSpace = line.indexOf(' ', cursor);
        int parent = Integer.parseUnsignedInt(line, cursor, nextSpace, 10);
        cursor = nextSpace + 1;

        // 3. major:minor
        nextSpace = line.indexOf(' ', cursor);
        int colonPos = line.indexOf(':', cursor);
        int major = Integer.parseUnsignedInt(line, cursor, colonPos, 10);
        int minor = Integer.parseUnsignedInt(line, colonPos + 1, nextSpace, 10);
        long device = makeDev(major, minor);
        cursor = nextSpace + 1;

        // 4. root
        nextSpace = line.indexOf(' ', cursor);
        String root = line.substring(cursor, nextSpace);
        cursor = nextSpace + 1;

        // 5. mount point
        nextSpace = line.indexOf(' ', cursor);
        String point = line.substring(cursor, nextSpace);
        cursor = nextSpace + 1;

        // 6. mount options
        nextSpace = line.indexOf(' ', cursor);
        String options = line.substring(cursor, nextSpace);
        cursor = nextSpace;

        // 7. optional fields
        int separatorPos = line.indexOf(" - ", cursor);
        String optional = line.substring(cursor, separatorPos).trim();
        cursor = separatorPos + 3;

        // 8. filesystem type
        nextSpace = line.indexOf(' ', cursor);
        String type = line.substring(cursor, nextSpace);
        cursor = nextSpace + 1;

        // 9. mount source
        nextSpace = line.indexOf(' ', cursor);
        String source = line.substring(cursor, nextSpace);
        cursor = nextSpace + 1;

        // 10. super options
        String superOptions = line.substring(cursor);

        return new MountInfo(
                id,
                parent,
                device,
                root,
                point,
                options,
                optional,
                type,
                source,
                superOptions
        );
    }

    private static long makeDev(int major, int minor) {
        return (((long) major & ~0xfff) << 32) | ((major & 0xfff) << 8) |
                (((long) minor & ~0xff) << 12) | (minor & 0xff);
    }
}
