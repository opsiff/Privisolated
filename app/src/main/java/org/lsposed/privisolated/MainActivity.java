package org.lsposed.privisolated;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.lsposed.privisolated.proc.ProcTool;
import org.lsposed.privisolated.proc.ProcTools;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView webView;
    private EditText argInput;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile IPrivIsolatedService server;
    private boolean bound;

    /** One binding kept alive for the whole activity, like the original app. */
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            server = IPrivIsolatedService.Stub.asInterface(binder);
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
        sb.append("<p>Tap a tool to run it; fill the field below for pidof/pgrep (name) or pwdx/pmap (PID).</p>");
        for (var tool : ProcTools.ALL) {
            sb.append("<p><a href='#' onclick=\"Android.run('")
                    .append(tool.name()).append("');return false;\">[")
                    .append(tool.name()).append("]</a></p>");
        }
        sb.append("<p><a href='#' onclick=\"Android.run('mounts');return false;\">[mounts]</a></p>");
        setHtml(sb.toString());
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /**
     * Runs a procps tool. The work is executed by the isolated process
     * (PrivIsolatedService): the isolated process holds gid 3009, which grants
     * unrestricted /proc access, so the tools must run there to be meaningful.
     */
    private void runTool(ProcTool tool) {
        var arg = argInput.getText().toString().trim();
        if (tool.requiresArg() && arg.isEmpty()) {
            setText("usage: " + tool.name() + " <argument>\n"
                    + "Fill the field above first (process name or PID).");
            return;
        }
        setText("INFO: running " + tool.name() + "...");
        runInIsolatedProcess(tool.name(), arg);
    }

    private void bindMountCheck() {
        setText("INFO: Waiting for service...");
        runInIsolatedProcess(null, null);
    }

    /** Serial call to the held isolated-service binder (single-thread executor). */
    private void runInIsolatedProcess(String tool, String arg) {
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
                runOnUiThread(() -> setText(result));
            } catch (DeadObjectException e) {
                server = null;
                runOnUiThread(() -> {
                    setText("ERROR: isolated service died, rebinding...");
                    bindService();
                });
            } catch (RemoteException e) {
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
        return super.onOptionsItemSelected(item);
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

        argInput = new EditText(this);
        argInput.setHint("argument (process name or PID)");
        root.addView(argInput, new LinearLayout.LayoutParams(
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
