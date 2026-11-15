package app.cashbook;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Shows the cashbook page and gives it two things a web page cannot do: read SMS and save files. */
public class MainActivity extends Activity {
    private static final int REQ_SMS = 1, REQ_FILE = 2;
    private static final String ORIGIN = "https://cashbook.app.local/";
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private BroadcastReceiver smsReceiver;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "CashbookNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (r.getUrl().toString().startsWith(ORIGIN)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                try { startActivityForResult(i, REQ_FILE); } catch (Exception e) { fileCallback = null; return false; }
                return true;
            }
        });
        String page = "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\"></head><body>"
                + readAsset("index.html") + "</body></html>";
        web.loadDataWithBaseURL(ORIGIN, page, "text/html", "utf-8", null);
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "<p>Could not load the app.</p>";
        }
    }

    private void js(String code) { web.post(() -> web.evaluateJavascript(code, null)); }

    private boolean canReadSms() { return checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED; }

    @Override
    protected void onResume() {
        super.onResume();
        js("window.nativeResume&&window.nativeResume()");
        if (smsReceiver == null && checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) {
            smsReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    // The message reaches the inbox a moment after this broadcast.
                    web.postDelayed(() -> web.evaluateJavascript("window.nativeResume&&window.nativeResume()", null), 2500);
                }
            };
            IntentFilter f = new IntentFilter("android.provider.Telephony.SMS_RECEIVED");
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(smsReceiver, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(smsReceiver, f);
        }
    }

    @Override
    protected void onDestroy() {
        if (smsReceiver != null) { try { unregisterReceiver(smsReceiver); } catch (Exception ignored) { } }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_SMS) js("window.onSmsPermission&&window.onSmsPermission(" + canReadSms() + ")");
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code != REQ_FILE || fileCallback == null) return;
        Uri u = result == RESULT_OK && data != null ? data.getData() : null;
        fileCallback.onReceiveValue(u == null ? null : new Uri[]{u});
        fileCallback = null;
    }

    private class Bridge {
        @JavascriptInterface
        public boolean hasSmsPermission() { return canReadSms(); }

        @JavascriptInterface
        public void requestSmsPermission() {
            runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS}, REQ_SMS));
        }

        /** Inbox messages newer than sinceMs, oldest first, as JSON [{a: sender, b: text, d: time}]. */
        @JavascriptInterface
        public String readSms(double sinceMs) {
            JSONArray out = new JSONArray();
            if (!canReadSms()) return out.toString();
            try (Cursor c = getContentResolver().query(Uri.parse("content://sms/inbox"), new String[]{"address", "body", "date"},
                    "date > ?", new String[]{String.valueOf((long) sinceMs)}, "date ASC")) {
                while (c != null && c.moveToNext()) {
                    JSONObject o = new JSONObject();
                    o.put("a", c.getString(0));
                    o.put("b", c.getString(1));
                    o.put("d", c.getLong(2));
                    out.put(o);
                }
            } catch (Exception ignored) { }
            return out.toString();
        }

        /** Saves text as a file in Downloads. Returns where it went, or "" on failure. */
        @JavascriptInterface
        public String saveFile(String name, String text) {
            try {
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, name.endsWith(".csv") ? "text/csv" : "application/json");
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(bytes); }
                    return "Downloads";
                }
                File f = new File(getExternalFilesDir(null), name);
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(bytes); }
                return f.getAbsolutePath();
            } catch (Exception e) {
                return "";
            }
        }
    }
}
