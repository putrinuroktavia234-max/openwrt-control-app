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
        webView.setBackgroundColor(0xFF091018);

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
                String err = doLogin(user, pass);
                if (err != null) err = webLogin(user, pass); // fallback ala browser
                final String fe = err;
                runOnUiThread(() -> {
                    if (fe == null) webView.loadUrl(APP_URL);
                    else js("window.__loginFail&&__loginFail(" + JSONObject.quote(fe) + ")");
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
        public void openAndro() {
            runOnUiThread(() -> {
                autoLoginPending = false;
                webView.loadUrl(routerHost + ":49153");
            });
        }

        @JavascriptInterface
        public void openPage(final String page) {
            runOnUiThread(() -> {
                java.util.Map<String, String> m = new java.util.HashMap<>();
                m.put("modemband", "/cgi-bin/luci/admin/modem/luci-app-modemband");
                m.put("modeminfo", "/cgi-bin/luci/admin/modem/main/main");
                m.put("3ginfo-lite", "/cgi-bin/luci/admin/modem/3ginfo-lite");
                m.put("droidnet", "/cgi-bin/luci/admin/services/droidnet");
                m.put("openclash", "/cgi-bin/luci/admin/services/openclash");
                m.put("tailscale", "/cgi-bin/luci/admin/vpn/tailscale");
                m.put("amlogic", "/cgi-bin/luci/admin/system/amlogic");
                m.put("diskman", "/cgi-bin/luci/admin/system/diskman");
                m.put("eqosplus", "/cgi-bin/luci/admin/control/eqosplus");
                m.put("mac-todong", "/cgi-bin/luci/admin/services/mac-todong");
                m.put("netmonitor", "/cgi-bin/luci/admin/status/netmonitor");
                m.put("release-ram", "/cgi-bin/luci/admin/status/release_ram");
                m.put("packages", "/cgi-bin/luci/admin/system/packages");
                m.put("ttyd", "/cgi-bin/luci/admin/system/ttyd");
                String path = m.get(page);
                autoLoginPending = true;
                webView.loadUrl(routerHost + (path != null ? path : "/cgi-bin/luci"));
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
        public void skip() {
            runOnUiThread(() -> webView.loadUrl(routerHost + "/cgi-bin/luci"));
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

    private volatile int lastStatus = 0;
    private volatile String rpcPath = "/ubus"; // bisa berubah ke /jsonrpc atau /cgi-bin/luci/admin/ubus
    private volatile String webToken = "";
    private volatile String webCookie = "";

    private static final String[] RPC_CANDIDATES = {
            "/ubus", "/jsonrpc", "/cgi-bin/luci/admin/ubus"
    };

    private static javax.net.ssl.SSLSocketFactory trustAllFactory() {
        try {
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }
            }}, new java.security.SecureRandom());
            return ctx.getSocketFactory();
        } catch (Exception e) {
            return null;
        }
    }

    private String postRaw(String base, String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        tuneSsl(c);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Origin", routerHost);
        c.setRequestProperty("Referer", routerHost + "/");
        c.setConnectTimeout(4000);
        c.setReadTimeout(8000);
        c.setInstanceFollowRedirects(true);
        c.setDoOutput(true);
        // /admin/ubus memakai sesi cookie sysauth saat sid nol; /ubus mengabaikannya.
        if (path != null && path.contains("/admin/ubus") && webCookie != null && !webCookie.isEmpty())
            c.setRequestProperty("Cookie", webCookie);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        lastStatus = c.getResponseCode();
        java.io.InputStream is = lastStatus >= 400 ? c.getErrorStream() : c.getInputStream();
        if (is == null) {
            String loc = c.getHeaderField("Location");
            throw new java.io.IOException("HTTP " + lastStatus + (loc != null ? " -> " + loc : ""));
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private String post(String body) {
        try {
            String[] paths = {rpcPath, "/ubus", "/jsonrpc", "/cgi-bin/luci/admin/ubus"};
            for (String p : paths) {
                try {
                    String r = postRaw(routerHost, p, body);
                    rpcPath = p;
                    return r;
                } catch (Exception e) {
                    lastErr = p + " -> " + e.getMessage();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
    private volatile String lastErr = "";

    /** Login ala browser: form sysauth + cookie + sessionid dari halaman admin. */
    private String webLogin(String user, String pass) {
        try {
            String url = routerHost + "/cgi-bin/luci/admin";
            // 1. GET awal utk cookie tamu
            java.net.HttpURLConnection g = (java.net.HttpURLConnection) new URL(url).openConnection();
            tuneSsl(g);
            g.setInstanceFollowRedirects(false);
            g.connect();
            String cookie = grabCookies(g, "");
            g.disconnect();
            // 2. POST kredensial
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new URL(url).openConnection();
            tuneSsl(c);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("Origin", routerHost);
            c.setRequestProperty("Referer", url);
            if (!cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            c.setInstanceFollowRedirects(false);
            c.setDoOutput(true);
            String form = "username=" + java.net.URLEncoder.encode(user, "UTF-8")
                    + "&password=" + java.net.URLEncoder.encode(pass, "UTF-8")
                    + "&luci_username=" + java.net.URLEncoder.encode(user, "UTF-8")
                    + "&luci_password=" + java.net.URLEncoder.encode(pass, "UTF-8");
            try (OutputStream os = c.getOutputStream()) {
                os.write(form.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            cookie = grabCookies(c, cookie);
            String bodyLoc = c.getHeaderField("Location");
            c.disconnect();
            if (code == 200) return "login http 200 (kemungkinan salah sandi)";
            // 3. Halaman admin -> sessionid
            // Setelah POST sukses, LuCI memunculkan sessionid di URL redirect
            // (?sessionid=xxx) atau di halaman tujuan (L.env.sessionid).
            java.util.regex.Matcher mLoc = java.util.regex.Pattern
                    .compile("[?&]sessionid=([0-9a-fA-F]{8,64})").matcher(bodyLoc == null ? "" : bodyLoc);
            if (mLoc.find()) {
                sid = mLoc.group(1);
                webCookie = cookie;
                rpcPath = "/cgi-bin/luci/admin/ubus";
                sp.edit().putString(KEY_USER, user).putString(KEY_PASS, pass).apply();
                return null;
            }
            String url2 = bodyLoc != null && !bodyLoc.isEmpty() && bodyLoc.startsWith("/")
                    ? routerHost + bodyLoc : url;
            java.net.HttpURLConnection g2 = (java.net.HttpURLConnection) new URL(url2).openConnection();
            tuneSsl(g2);
            if (!cookie.isEmpty()) g2.setRequestProperty("Cookie", cookie);
            g2.setInstanceFollowRedirects(true);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    g2.getResponseCode() >= 400 ? g2.getErrorStream() : g2.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                int n = 0;
                while ((line = r.readLine()) != null && n++ < 2000) sb.append(line).append('\n');
            }
            g2.disconnect();
            // L.env.sessionid di-render header.ut sebagai "sessionid": "..." (atau
            // 'sessionid': ...) setelah objek LuCI dibuat; cocokkan kata utuh.
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("['\"]sessionid['\"]\\s*:\\s*['\"]([0-9a-fA-F]{8,64})['\"]")
                    .matcher(sb);
            if (!m.find()) {
                if (sb.indexOf("Authorization Required") >= 0
                        || sb.indexOf("Invalid username") >= 0
                        || sb.indexOf("luci_password") >= 0)
                    return "form login ditolak (cek sandi root)";
                return "sessionid tidak ditemukan di halaman admin";
            }
            sid = m.group(1);
            webCookie = cookie;
            rpcPath = "/cgi-bin/luci/admin/ubus";
            sp.edit().putString(KEY_USER, user).putString(KEY_PASS, pass).apply();
            return null;
        } catch (Exception e) {
            return "web login: " + e.getMessage();
        }
    }

    private void tuneSsl(java.net.HttpURLConnection c) {
        if (c instanceof javax.net.ssl.HttpsURLConnection) {
            javax.net.ssl.HttpsURLConnection h = (javax.net.ssl.HttpsURLConnection) c;
            javax.net.ssl.SSLSocketFactory f = trustAllFactory();
            if (f != null) h.setSSLSocketFactory(f);
            h.setHostnameVerifier((hostname, session) -> true);
        }
    }

    private String grabCookies(java.net.HttpURLConnection c, String have) {
        try {
            java.util.List<String> sc = c.getHeaderFields().get("Set-Cookie");
            if (sc == null) return have;
            java.util.LinkedHashMap<String, String> jar = new java.util.LinkedHashMap<>();
            for (String part : have.split(";\\s*")) {
                int eq = part.indexOf('=');
                if (eq > 0) jar.put(part.substring(0, eq), part.substring(eq + 1));
            }
            for (String raw : sc) {
                String kv = raw.split(";", 2)[0].trim();
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    String k = kv.substring(0, eq), v = kv.substring(eq + 1);
                    if (v.isEmpty()) jar.remove(k); else jar.put(k, v);
                }
            }
            StringBuilder out = new StringBuilder();
            for (String k : jar.keySet()) {
                if (out.length() > 0) out.append("; ");
                out.append(k).append('=').append(jar.get(k));
            }
            return out.toString();
        } catch (Exception e) {
            return have;
        }
    }

    /** session.login rpcd -> simpan sid. Null = sukses. */
    private String doLogin(String user, String pass) {
        sid = null;
        JSONObject req = new JSONObject();
        JSONObject reqCall = new JSONObject();
        try {
            req.put("jsonrpc", "2.0");
            req.put("id", 1);
            req.put("method", "session.login");
            req.put("params", new JSONArray().put(user).put(pass));
            reqCall.put("jsonrpc", "2.0");
            reqCall.put("id", 1);
            reqCall.put("method", "call");
            reqCall.put("params", new JSONArray()
                    .put("00000000000000000000000000000000").put("session").put("login")
                    .put(new JSONObject().put("username", user).put("password", pass)));
        } catch (Exception e) {
            return "Internal error";
        }
        String last = "Router tidak menjawab";
        for (String p : RPC_CANDIDATES) {
            for (JSONObject body : new JSONObject[]{req, reqCall}) {
                String r;
                try {
                    r = postRaw(routerHost, p, body.toString());
                } catch (Exception e) {
                    last = p + ": " + e.getMessage();
                    continue;
                }
                try {
                    JSONObject o = new JSONObject(r);
                    Object res = o.opt("result");
                    String tok = null;
                    if (res instanceof String) tok = (String) res;
                    else if (res instanceof JSONArray) {
                        JSONArray a = (JSONArray) res;
                        if (a.length() >= 2 && a.get(1) instanceof String) tok = a.getString(1);
                        else if (a.length() >= 2 && a.get(1) instanceof JSONObject)
                            tok = a.getJSONObject(1).optString("ubus_rpc_session");
                    } else if (res instanceof JSONObject)
                        tok = ((JSONObject) res).optString("ubus_rpc_session");
                    if (tok != null && !tok.isEmpty() && !"null".equals(tok)) {
                        sid = tok;
                        rpcPath = p;
                        sp.edit().putString(KEY_USER, user).putString(KEY_PASS, pass).apply();
                        return null;
                    }
                    if (o.has("error")) {
                        String m = o.getJSONObject("error").optString("message", "");
                        if (m.contains("login failed") || m.contains("Authentication failed"))
                            return "Nama pengguna atau kata sandi salah";
                        last = p + " menolak: " + m;
                    }
                } catch (Exception notJson) {
                    String snip = r.replaceAll("\\s+", " ").trim();
                    if (snip.length() > 60) snip = snip.substring(0, 60) + "…";
                    last = p + " -> " + snip;
                }
            }
        }
        return "RPC tidak cocok: " + last;
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
