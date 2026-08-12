package org.lsposed.privisolated;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.lsposed.privisolated.proc.ProcTool;
import org.lsposed.privisolated.proc.ProcTools;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView webView;
    /** Last argument typed in a tool dialog, remembered for convenience. */
    private String lastArg = "";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile IPrivIsolatedService server;
    private boolean bound;
    /** Tool call waiting to be retried after a rebind (see DeadObjectException). */
    private volatile String pendingTool;
    private volatile String pendingArg;
    private volatile boolean retryPending;

    /** One binding kept alive for the whole activity, like the original app. */
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            server = IPrivIsolatedService.Stub.asInterface(binder);
            // The process died mid-call before; replay the pending call once.
            if (retryPending) {
                var tool = pendingTool;
                var arg = pendingArg;
                retryPending = false;
                dispatchCall(tool, arg, false);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            server = null;
        }

        @Override
        public void onNullBinding(ComponentName name) {
            setText("ERROR: Fake Environment");
        }
    };

    /** Bridge called from the HTML tool menu (Android.run('uptime')). */
    @SuppressLint("SetJavaScriptEnabled")
    private final class ToolBridge {
        @JavascriptInterface
        public void run(String name) {
            if ("mounts".equals(name)) {
                bindMountCheck();
                return;
            }
            if ("browser".equals(name)) {
                openBrowser("/proc");
                return;
            }
            for (var tool : ProcTools.ALL) {
                if (tool.name().equals(name)) {
                    runTool(tool);
                    return;
                }
            }
        }
    }

    private void setText(String text) {
        setHtml(escapeHtml(text));
    }

    /** Renders a full HTML page whose body is {@code bodyHtml} verbatim
     *  (used by the tool menu, which contains real markup). */
    private void setHtml(String bodyHtml) {
        var html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <style>
                        html {
                            width: 100dvw;
                            min-height: 100dvh;
                            box-sizing: border-box;
                            padding-top: env(safe-area-inset-top);
                            padding-bottom: env(safe-area-inset-bottom);
                            display: flex;
                            justify-content: center;
                            align-items: center;
                        }
                        .content {
                            font-family: monospace;
                            font-size: 14px;
                            text-align: left;
                            white-space: pre-wrap;
                            word-break: break-all;
                            overflow-wrap: break-word;
                        }
                        .content a { display: block; }
                    </style>
                </head>
                <body>
                    <div class="content">""" + bodyHtml + """
                    </div>
                </body>
                </html>
                """;
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    /** Startup page: version fingerprint + clickable tool list (works even if
     *  the native button row fails to render on some devices). */
    private void showMenu() {
        var sb = new StringBuilder();
        sb.append("<h3>Privisolated v").append(BuildConfig.VERSION_NAME)
                .append(" &mdash; ").append(ProcTools.ALL.size()).append(" tools</h3>");
        sb.append("<p>Tap a tool: a dialog asks for the optional argument, then the isolated process runs it.</p>");
        for (var tool : ProcTools.ALL) {
            sb.append("<p><a href='#' onclick=\"Android.run('")
                    .append(tool.name()).append("');return false;\">[")
                    .append(tool.name()).append("]</a></p>");
        }
        sb.append("<p><a href='#' onclick=\"Android.run('mounts');return false;\">[mounts]</a></p>");
        sb.append("<p><a href='#' onclick=\"Android.run('browser');return false;\">[browser]</a></p>");
        setHtml(sb.toString());
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /**
     * Runs a procps tool. A dialog asks for the optional argument first
     * (the field lives in the dialog so nothing covers it); the work is then
     * executed by the isolated process (PrivIsolatedService): the isolated
     * process holds gid 3009, which grants unrestricted /proc access, so the
     * tools must run there to be meaningful.
     */
    private void runTool(ProcTool tool) {
        var input = new EditText(this);
        input.setText(lastArg);
        input.setHint(tool.requiresArg() ? "process name" : "process name or PID (optional)");
        new AlertDialog.Builder(this)
                .setTitle(tool.name())
                .setView(input)
                .setPositiveButton("Run", (d, w) -> {
                    lastArg = input.getText().toString().trim();
                    setText("INFO: running " + tool.name() + "...");
                    runInIsolatedProcess(tool.name(), lastArg);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void bindMountCheck() {
        setText("INFO: Waiting for service...");
        runInIsolatedProcess(null, null);
    }

    /** Serial call to the held isolated-service binder (single-thread executor). */
    private void runInIsolatedProcess(String tool, String arg) {
        pendingTool = tool;
        pendingArg = arg;
        retryPending = true;
        dispatchCall(tool, arg, true);
    }

    /** Executes one tool call; {@code allowRetry} permits one replay after a rebind. */
    private void dispatchCall(String tool, String arg, boolean allowRetry) {
        executor.execute(() -> {
            var svc = server;
            if (svc == null) {
                runOnUiThread(() -> setText("ERROR: isolated service not connected"));
                return;
            }
            try {
                var result = tool == null
                        ? svc.getResult()
                        : svc.getToolResult(tool, arg);
                retryPending = false;
                runOnUiThread(() -> setText(result));
            } catch (DeadObjectException e) {
                server = null;
                if (allowRetry) {
                    // Keep retryPending set: onServiceConnected replays the call.
                    runOnUiThread(() -> {
                        setText("ERROR: isolated service died, rebinding and retrying...");
                        bindService();
                    });
                } else {
                    retryPending = false;
                    runOnUiThread(() -> setText("ERROR: isolated service keeps dying: " + e));
                }
            } catch (RemoteException e) {
                retryPending = false;
                runOnUiThread(() -> setText(Log.getStackTraceString(e)));
            }
        });
    }

    private void bindService() {
        try {
            bound = bindIsolatedService(new Intent(this, PrivIsolatedService.class),
                    Context.BIND_AUTO_CREATE, "priv_isolated", getMainExecutor(), connection);
            if (!bound) {
                setText("ERROR: Failed to bind service, service disabled?");
            }
        } catch (SecurityException e) {
            bound = false;
            setText(Log.getStackTraceString(e));
        }
    }

    /** Tool selector in the top-right ActionBar menu (the WebView menu is a fallback). */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        for (int i = 0; i < ProcTools.ALL.size(); i++) {
            menu.add(Menu.NONE, i, Menu.NONE, ProcTools.ALL.get(i).name());
        }
        menu.add(Menu.NONE, ProcTools.ALL.size(), Menu.NONE, "mounts");
        menu.add(Menu.NONE, ProcTools.ALL.size() + 1, Menu.NONE, "browser");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id >= 0 && id < ProcTools.ALL.size()) {
            runTool(ProcTools.ALL.get(id));
            return true;
        }
        if (id == ProcTools.ALL.size()) {
            bindMountCheck();
            return true;
        }
        if (id == ProcTools.ALL.size() + 1) {
            openBrowser("/proc");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Current directory shown in the /proc browser dialog. */
    private String browserPath = "/proc";

    /**
     * Opens a navigation dialog over /proc. Listing happens in the isolated
     * process (gid 3009), the dialog itself runs in the app process.
     */
    private void openBrowser(String start) {
        browserPath = start;
        refreshBrowser();
    }

    private void refreshBrowser() {
        var svc = server;
        if (svc == null) {
            setText("ERROR: isolated service not connected");
            return;
        }
        final String path = browserPath;
        executor.execute(() -> {
            try {
                List<String> entries = svc.listDir(path);
                runOnUiThread(() -> showBrowserDialog(path, entries));
            } catch (RemoteException e) {
                runOnUiThread(() -> setText(Log.getStackTraceString(e)));
            }
        });
    }

    private void showBrowserDialog(String path, List<String> entries) {
        var names = new ArrayList<String>();
        names.add("..");
        names.addAll(entries);
        new AlertDialog.Builder(this)
                .setTitle(path)
                .setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names),
                        (d, which) -> {
                            var entry = names.get(which);
                            if ("..".equals(entry)) {
                                int slash = path.lastIndexOf('/');
                                browserPath = slash > 0 ? path.substring(0, slash) : "/";
                                refreshBrowser();
                            } else if (entry.endsWith("/")) {
                                browserPath = path.endsWith("/") ? path + entry : path + "/" + entry;
                                refreshBrowser();
                            } else {
                                openBrowserFile(path.endsWith("/") ? path + entry : path + "/" + entry);
                            }
                        })
                .setNegativeButton("Close", null)
                .show();
    }

    private void openBrowserFile(String filePath) {
        var svc = server;
        if (svc == null) return;
        setText("INFO: reading " + filePath + "...");
        executor.execute(() -> {
            try {
                String content = svc.readFile(filePath, 64 * 1024);
                runOnUiThread(() -> showBrowserFileDialog(filePath, content));
            } catch (RemoteException e) {
                runOnUiThread(() -> setText(Log.getStackTraceString(e)));
            }
        });
    }

    private void showBrowserFileDialog(String filePath, String content) {
        var text = new TextView(this);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(12);
        text.setPadding(24, 24, 24, 24);
        text.setText(content);
        var scroll = new ScrollView(this);
        scroll.addView(text);
        new AlertDialog.Builder(this)
                .setTitle(filePath)
                .setView(scroll)
                .setPositiveButton("Share", (d, w) -> shareText(filePath, content))
                .setNeutralButton("Save", (d, w) -> saveBrowserFile(filePath))
                .setNegativeButton("Close", null)
                .show();
    }

    private void shareText(String filePath, String content) {
        var send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, filePath);
        send.putExtra(Intent.EXTRA_TEXT, content);
        startActivity(Intent.createChooser(send, "Share " + filePath));
    }

    /**
     * Saves the raw bytes (binary-safe, e.g. config.gz) to the public
     * Downloads directory via MediaStore; no storage permission needed on
     * API 29+. The read is capped below the 1 MiB binder limit.
     */
    private void saveBrowserFile(String filePath) {
        var svc = server;
        if (svc == null) {
            setText("ERROR: isolated service not connected");
            return;
        }
        final int max = 768 * 1024;
        setText("INFO: saving " + filePath + "...");
        executor.execute(() -> {
            try {
                byte[] bytes = svc.readFileBytes(filePath, max);
                if (bytes == null) {
                    runOnUiThread(() -> setText("ERROR: cannot read " + filePath));
                    return;
                }
                boolean truncated = bytes.length == max;
                Uri uri = writeToDownloads(filePath, bytes);
                runOnUiThread(() -> {
                    if (uri != null) {
                        Toast.makeText(this, "Saved: " + uri.getLastPathSegment()
                                + (truncated ? " (truncated at " + max + " bytes)" : ""),
                                Toast.LENGTH_LONG).show();
                    } else {
                        setText("ERROR: failed to save " + filePath);
                    }
                });
            } catch (RemoteException e) {
                runOnUiThread(() -> setText(Log.getStackTraceString(e)));
            }
        });
    }

    private Uri writeToDownloads(String filePath, byte[] bytes) {
        try {
            int slash = filePath.lastIndexOf('/');
            String name = slash == -1 ? filePath : filePath.substring(slash + 1);
            var values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            var uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            try (var os = getContentResolver().openOutputStream(uri)) {
                if (os == null) return null;
                os.write(bytes);
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return uri;
        } catch (Exception e) {
            Log.e("Privisolated", "save failed", e);
            return null;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No ActionBar subtitle: it occupies a second title line that pushes
        // the tool button row out of sight. The version is shown by the
        // fingerprint TextView below instead.

        var root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // Version fingerprint that cannot be missed.
        var fingerprint = new TextView(this);
        fingerprint.setText("Privisolated v" + BuildConfig.VERSION_NAME
                + " — " + ProcTools.ALL.size() + " tools");
        root.addView(fingerprint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.addJavascriptInterface(new ToolBridge(), "Android");
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        showMenu();
        bindService();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bound) {
            unbindService(connection);
        }
        executor.shutdownNow();
    }
}
