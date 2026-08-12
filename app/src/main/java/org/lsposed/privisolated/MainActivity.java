package org.lsposed.privisolated;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import org.lsposed.privisolated.proc.ProcTool;
import org.lsposed.privisolated.proc.ProcTools;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView webView;
    private EditText argInput;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            var server = IPrivIsolatedService.Stub.asInterface(binder);
            try {
                setText(server.getResult());
            } catch (RemoteException e) {
                setText(Log.getStackTraceString(e));
            }
            unbindService(this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }

        @Override
        public void onNullBinding(ComponentName name) {
            setText("ERROR: Fake Environment");
            unbindService(this);
        }
    };

    private void setText(String text) {
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
                    </style>
                </head>
                <body>
                    <div class="content">""" + escapeHtml(text) + """
                    </div>
                </body>
                </html>
                """;
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private void runTool(ProcTool tool) {
        var arg = argInput.getText().toString().trim();
        setText("INFO: running " + tool.name() + "...");
        executor.execute(() -> {
            var result = tool.run(arg);
            runOnUiThread(() -> setText(result));
        });
    }

    private void bindMountCheck() {
        setText("INFO: Waiting for service...");
        try {
            if (!bindIsolatedService(new Intent(this, PrivIsolatedService.class),
                    Context.BIND_AUTO_CREATE, "priv_isolated", getMainExecutor(), connection)) {
                setText("ERROR: Failed to bind service, service disabled?");
                unbindService(connection);
            }
        } catch (SecurityException e) {
            setText(Log.getStackTraceString(e));
            unbindService(connection);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getActionBar().setSubtitle(BuildConfig.VERSION_NAME);

        var root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        var bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        var scroll = new HorizontalScrollView(this);
        for (var tool : ProcTools.ALL) {
            var button = new Button(this);
            button.setText(tool.name());
            button.setOnClickListener(v -> runTool(tool));
            bar.addView(button);
        }
        var mountsButton = new Button(this);
        mountsButton.setText("mounts");
        mountsButton.setOnClickListener(v -> bindMountCheck());
        bar.addView(mountsButton);
        scroll.addView(bar);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        argInput = new EditText(this);
        argInput.setHint("argument (process name or PID)");
        root.addView(argInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        bindMountCheck();
    }
}
