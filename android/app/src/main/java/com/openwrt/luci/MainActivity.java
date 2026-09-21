package com.openwrt.luci;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final String PREFS = "owrt_prefs";
    private static final String KEY_HOST = "router_host";
    private static final String KEY_USER = "router_user";
    private static final String KEY_PASS = "router_pass";
    private static final String DEFAULT_HOST = "http://192.168.1.1";
    private static final String LOGIN_URL = "file:///android_asset/www/login.html";

    private WebView webView;
    private View fab;
    private SharedPreferences sp;
    private String routerHost;
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

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                fab.setVisibility(url.startsWith("file:") ? View.GONE : View.VISIBLE);
                if (autoLoginPending && !url.startsWith("file:")) {
                    autoLoginPending = false;
                    injectLogin();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    webView.loadUrl(LOGIN_URL + "?err=1");
                    Toast.makeText(MainActivity.this,
                            "Router tidak terjangkau — cek Wi-Fi", Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed(); // sertifikat self-signed router
            }
        });

        fab.setOnClickListener(v -> showMenu());

        // Layar pertama = login Husky Guard, bukan halaman router
        webView.loadUrl(LOGIN_URL);
    }

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
                return "{\"u\":\"root\",\"p\":\"\"}";
            }
        }

        @JavascriptInterface
        public void changeHost() {
            runOnUiThread(MainActivity.this::showHostDialog);
        }

        @JavascriptInterface
        public void login(final String user, final String pass) {
            runOnUiThread(() -> {
                sp.edit().putString(KEY_USER, user).putString(KEY_PASS, pass).apply();
                autoLoginPending = true;
                webView.loadUrl(routerHost + "/cgi-bin/luci");
            });
        }

        @JavascriptInterface
        public void skip() {
            runOnUiThread(() -> {
                autoLoginPending = false;
                webView.loadUrl(routerHost + "/");
            });
        }
    }

    /** Isi & kirim form login LuCI dari kredensial tersimpan.
     *  Mendukung LuCI lama (input name="luci_password") dan baru (user + password). */
    private void injectLogin() {
        final String user = sp.getString(KEY_USER, "root");
        final String pass = sp.getString(KEY_PASS, "");
        if (pass.isEmpty()) return;
        try {
            String js = "(function(){"
                    + "var p=document.querySelector('input[name=\"luci_password\"]');"
                    + "if(p){p.value=" + JSONObject.quote(pass) + ";if(p.form)p.form.submit();return;}"
                    + "var pw=document.querySelector('input[type=\"password\"]');"
                    + "if(pw){"
                    + "  var u=document.querySelector('input[type=\"text\"],input[name=\"user\"]');"
                    + "  if(u)u.value=" + JSONObject.quote(user) + ";"
                    + "  pw.value=" + JSONObject.quote(pass) + ";"
                    + "  var b=document.querySelector('button,input[type=\"submit\"]');"
                    + "  if(b){b.click();return;}"
                    + "  if(pw.form)pw.form.submit();"
                    + "}})();";
            webView.evaluateJavascript(js, null);
        } catch (Exception ignored) {
        }
    }

    private void showMenu() {
        String[] items = {"Muat Ulang", "Beranda", "Halaman Login Husky", "Ganti Alamat Router", "Logout / Bersihkan Sesi"};
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: webView.reload(); break;
                        case 1: webView.loadUrl(routerHost + "/"); break;
                        case 2: webView.loadUrl(LOGIN_URL); break;
                        case 3: showHostDialog(); break;
                        case 4: clearSession(); break;
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
                    if (!host.startsWith("http://") && !host.startsWith("https://")) {
                        host = "http://" + host;
                    }
                    routerHost = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
                    sp.edit().putString(KEY_HOST, routerHost).apply();
                    webView.loadUrl(routerHost + "/");
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
        CookieManager.getInstance().removeAllCookies(null);
        webView.clearCache(true);
        webView.clearHistory();
        Toast.makeText(this, "Sesi dibersihkan", Toast.LENGTH_SHORT).show();
        webView.loadUrl(LOGIN_URL);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }
}
