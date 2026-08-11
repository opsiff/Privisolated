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
import android.webkit.WebView;

public class MainActivity extends Activity {
    private WebView webView;
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
                            font-size: 20px;
                            text-align: center;
                            word-break: break-all;
                            overflow-wrap: break-word;
                            white-space: normal;
                        }
                    </style>
                </head>
                <body>
                    <div class="content">""" + text + """
                    </div>
                </body>
                </html>
                """;
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getActionBar().setSubtitle(BuildConfig.VERSION_NAME);

        webView = new WebView(this);
        setText("INFO: Waiting for service...");
        setContentView(webView);
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
}
