package com.openwrt.luci;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final String PREFS = "owrt_prefs";
    private static final String KEY_HOST = "router_host";
    private static final String KEY_USER = "router_user";
    private static final String KEY_PASS = "router_pass";
    private static final String DEFAULT_HOST = "http://192.168.1.1";
    private static final String LOGIN_URL = "file:///android_asset/www/login.html";
    private static final String APP_URL = "file:///android_asset/www/app.html";

    private WebView webView;
    private View fab;
    private SharedPreferences sp;
    private volatile String routerHost;
    private volatile String sid;
    private boolean autoLoginPending = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        fab = findViewById(R.id.fab);

        sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        routerHost = sp.getString(KEY_HOST, DEFAULT_HOST);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setBackgroundColor(0xFF0C0D10);

        CookieManager.getInstance().setAcceptCookie(true);

        webView.addJavascriptInterface(new Bridge(), "App");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                fab.setVisibility(url.startsWith("file:") ? View.GONE : View.VISIBLE);
                if (autoLoginPending && !url.startsWith("file:")) {
                    autoLoginPending = false;
                    injectWebLogin();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    webView.loadUrl(LOGIN_URL + "?err=1");
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });

        fab.setOnClickListener(v -> showMenu());

        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                String title = message.contains("\n") ? message.substring(0, message.indexOf("\n")).trim() : "Konfirmasi";
                String body = message.contains("\n") ? message.substring(message.indexOf("\n")).trim() : "";
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(title).setMessage(body)
                        .setPositiveButton("Lanjutkan", (d, w) -> result.confirm())
                        .setNegativeButton("Batal", (d, w) -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message).setPositiveButton("OK", null).show();
                result.confirm();
                return true;
            }
        });

        webView.loadUrl(LOGIN_URL);
    }

    // ================= JS BRIDGE =================

    private class Bridge {
        @JavascriptInterface
        public String getSaved() {
            try {
                JSONObject j = new JSONObject();
                j.put("u", sp.getString(KEY_USER, "root"));
                j.put("p", sp.getString(KEY_PASS, ""));
                j.put("h", routerHost);
                return j.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void login(final String user, final String pass) {
            new Thread(() -> {
                final String err = doLogin(user, pass);
                runOnUiThread(() -> {
                    if (err == null) webView.loadUrl(APP_URL);
                    else js("window.__loginFail&&__loginFail(" + JSONObject.quote(err) + ")");
                });
            }).start();
        }

        @JavascriptInterface
        public void relogin() {
            new Thread(() -> {
                final String err = doLogin(sp.getString(KEY_USER, "root"),
                        sp.getString(KEY_PASS, ""));
                runOnUiThread(() -> {
                    if (err == null) webView.loadUrl(APP_URL);
                    else webView.loadUrl(LOGIN_URL + "?err=2");
                });
            }).start();
        }

        /** Panggil ubus RPC: namespace+method+params(JSON). Hasil -> window.__cb(id, json). */
        @JavascriptInterface
        public void rpc(final String ns, final String method, final String params, final long cbId) {
            new Thread(() -> {
                String out = rpcCall(ns, method, params);
                if (out != null && (out.contains("not authenticated") || out.contains("NOT_FOUND"))) {
                    String err = doLogin(sp.getString(KEY_USER, "root"),
                            sp.getString(KEY_PASS, ""));
                    if (err == null) out = rpcCall(ns, method, params);
                }
                final String payload = out == null ? "{\"error\":{\"message\":\"unreachable\"}}" : out;
                runOnUiThread(() -> js("window.__cb&&__cb(" + cbId + "," + JSONObject.quote(payload) + ")"));
            }).start();
        }

        @JavascriptInterface
        public void openWeb() {
            runOnUiThread(() -> {
                autoLoginPending = true;
                webView.loadUrl(routerHost + "/cgi-bin/luci");
            });
        }

        @JavascriptInterface
        public void changeHost() {
            runOnUiThread(MainActivity.this::showHostDialog);
        }

        @JavascriptInterface
        public void goLogin() {
            runOnUiThread(() -> webView.loadUrl(LOGIN_URL + "?back=1"));
        }

        @JavascriptInterface
        public void back() {
            runOnUiThread(MainActivity.this::onBackPressed);
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }
    }

    private void js(String script) {
        webView.evaluateJavascript(script, null);
    }

    // ================= RPC core =================

    private String post(String body) {
        try {
            HttpURLConnection c = (HttpURLConnection)
                    new URL(routerHost + "/jsonrpc").openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setConnectTimeout(4000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** session.login rpcd -> simpan sid. Null = sukses. */
    private String doLogin(String user, String pass) {
        sid = null;
        try {
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("id", 1);
            req.put("method", "session.login");
            req.put("params", new JSONArray().put(user).put(pass));
            String r = post(req.toString());
            if (r == null) return "Router tidak terjangkau — cek Wi-Fi";
            JSONObject o = new JSONObject(r);
            JSONArray a = o.optJSONArray("result");
            if (a != null && a.length() > 0 && !a.isNull(0)) {
                sid = a.getString(0);
                sp.edit().putString(KEY_USER, user).putString(KEY_PASS, pass).apply();
                return null;
            }
            return "Nama pengguna atau kata sandi salah";
        } catch (Exception e) {
            return "Router tidak menjawab via RPC (jsonrpc)";
        }
    }

    private String rpcCall(String ns, String method, String params) {
        if (sid == null) return "{\"error\":{\"message\":\"no session\"}}";
        try {
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("id", 1);
            req.put("method", "call");
            req.put("params", new JSONArray()
                    .put(sid).put(ns).put(method)
                    .put(new JSONObject(params == null || params.isEmpty() ? "{}" : params)));
            return post(req.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** Auto-login form LuCI web (dipakai hanya saat membuka panel web lengkap). */
    private void injectWebLogin() {
        final String user = sp.getString(KEY_USER, "root");
        final String pass = sp.getString(KEY_PASS, "");
        if (pass.isEmpty()) return;
        try {
            String js = "(function(){"
                    + "var p=document.querySelector('input[name=\"luci_password\"]');"
                    + "if(p){p.value=" + JSONObject.quote(pass) + ";if(p.form)p.form.submit();return;}"
                    + "var pw=document.querySelector('input[type=\"password\"]');"
                    + "if(pw){var u=document.querySelector('input[type=\"text\"]');"
                    + "if(u)u.value=" + JSONObject.quote(user) + ";pw.value=" + JSONObject.quote(pass) + ";"
                    + "var b=document.querySelector('button,input[type=\"submit\"]');"
                    + "if(b){b.click();return;}if(pw.form)pw.form.submit();}})();";
            webView.evaluateJavascript(js, null);
        } catch (Exception ignored) {
        }
    }

    // ================= MENU / DIALOG =================

    private void showMenu() {
        String[] items = {"Muat Ulang", "Kembali ke Dashboard", "Ganti Alamat Router", "Logout / Bersihkan Sesi"};
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: webView.reload(); break;
                        case 1: webView.loadUrl(APP_URL); break;
                        case 2: showHostDialog(); break;
                        case 3: clearSession(); break;
                    }
                })
                .show();
    }

    private void showHostDialog() {
        final EditText input = new EditText(this);
        input.setText(routerHost);
        input.setHint(DEFAULT_HOST);
        input.setSingleLine();

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Alamat Router")
                .setMessage("Contoh: http://192.168.1.1")
                .setView(input)
                .setPositiveButton("Simpan", (d, w) -> {
                    String host = input.getText().toString().trim();
                    if (host.isEmpty()) host = DEFAULT_HOST;
                    if (!host.startsWith("http://") && !host.startsWith("https://")) host = "http://" + host;
                    routerHost = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
                    sp.edit().putString(KEY_HOST, routerHost).apply();
                    sid = null;
                    webView.loadUrl(LOGIN_URL);
                })
                .setNegativeButton("Batal", null)
                .create();
        dlg.setOnShowListener(d -> {
            input.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
        dlg.show();
    }

    private void clearSession() {
        sid = null;
        CookieManager.getInstance().removeAllCookies(null);
        webView.clearCache(true);
        webView.clearHistory();
        Toast.makeText(this, "Sesi dibersihkan", Toast.LENGTH_SHORT).show();
        webView.loadUrl(LOGIN_URL);
    }

    @Override
    public void onBackPressed() {
        String url = webView.getUrl() == null ? "" : webView.getUrl();
        if (!url.startsWith("file:")) {
            webView.loadUrl(APP_URL); // dari web LuCI kembali ke dashboard
        } else if (url.contains("app.html")) {
            webView.loadUrl(LOGIN_URL + "?back=1");
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }
}
