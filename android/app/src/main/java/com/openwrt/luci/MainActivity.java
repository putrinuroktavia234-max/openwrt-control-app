package com.openwrt.luci;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.ProgressBar;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String PREFS = "owrt_prefs";
    private static final String KEY_HOST = "router_host";
    private static final String DEFAULT_HOST = "http://192.168.1.1";

    private WebView webView;
    private View statusDot;
    private TextView titleText;
    private ProgressBar progressBar;
    private String routerHost;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        statusDot = findViewById(R.id.status_dot);
        titleText = findViewById(R.id.title_text);
        progressBar = findViewById(R.id.progress);

        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                setStatus(true);
                String t = view.getTitle();
                titleText.setText((t == null || t.isEmpty()) ? getString(R.string.app_name) : t);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) setStatus(false);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Router pakai sertifikat self-signed: terima agar LuCI tetap bisa dibuka.
                handler.proceed();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
                progressBar.setProgress(newProgress);
            }
        });

        findViewById(R.id.btn_reload).setOnClickListener(v -> webView.reload());
        findViewById(R.id.btn_home).setOnClickListener(v -> loadUrl(routerHost + "/"));
        findViewById(R.id.btn_menu).setOnClickListener(v -> showMenu());

        loadUrl(routerHost + "/");
    }

    private void loadUrl(String url) {
        webView.loadUrl(url);
    }

    private void setStatus(boolean ok) {
        statusDot.setBackgroundResource(ok ? R.drawable.dot_online : R.drawable.dot_offline);
    }

    private void showMenu() {
        String[] items = {"Ganti Alamat Router", "Logout / Bersihkan Sesi", "Muat Ulang Paksa"};
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) showHostDialog();
                    else if (which == 1) clearSession();
                    else webView.reload();
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
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putString(KEY_HOST, routerHost).apply();
                    loadUrl(routerHost + "/");
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
        loadUrl(routerHost + "/");
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
