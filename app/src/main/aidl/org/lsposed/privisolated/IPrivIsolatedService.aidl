package org.lsposed.privisolated;

import java.util.List;

interface IPrivIsolatedService {
    String getResult();

    String getToolResult(String tool, String arg);

    List<String> listDir(String path);

    String readFile(String path, int maxBytes);
}
