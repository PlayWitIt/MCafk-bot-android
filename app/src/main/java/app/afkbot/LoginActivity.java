package app.afkbot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Microsoft's own sign-in, full screen: choose an account, email, password, "Forgot password", codes by email - all
 * of Microsoft's page. Starts with nothing saved, so it never signs in with an account by itself. When Microsoft
 * sends the page back with a code (or the code page says it's done), it finishes and hands the code to the main
 * screen. Each bot slot has its own (see Slot), in its own process.
 */
public class LoginActivity extends Activity {
    static final String EXTRA_URL = "url", EXTRA_CODE_PAGE = "codePage", RESULT_CODE = "code", RESULT_ERROR = "error";

    private WebView web;
    private TextView status;
    private boolean codePage, done;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 28 && Slot.current() > 0) {
            try {
                WebView.setDataDirectorySuffix("bot" + Slot.current());
            } catch (Exception ignored) { // already set in this process
            }
        }
        getWindow().setStatusBarColor(MainActivity.SURFACE);
        final String url = getIntent().getStringExtra(EXTRA_URL);
        codePage = getIntent().getBooleanExtra(EXTRA_CODE_PAGE, false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.BG);
        root.setFitsSystemWindows(true);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(MainActivity.SURFACE);
        int p = (int) (getResources().getDisplayMetrics().density * 12);
        bar.setPadding(p, p, p / 2, p);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Microsoft sign-in" + (Slot.current() > 0 ? " · Bot " + (Slot.current() + 1) : ""));
        title.setTextColor(MainActivity.TEXT);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titles.addView(title);
        status = new TextView(this);
        status.setTextColor(MainActivity.ACCENT2);
        status.setTextSize(12);
        status.setText("Loading Microsoft's sign-in page...");
        titles.addView(status);
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button close = new Button(this);
        close.setText("Close");
        close.setAllCaps(false);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(close);
        root.addView(bar);

        try {
            web = new WebView(this);
        } catch (Throwable t) { // no WebView on this phone (Android System WebView switched off)
            status.setText("This phone's web viewer (Android System WebView) is missing or switched off. Use \"Microsoft's page in my browser\" instead.");
            status.setTextColor(MainActivity.BAD);
            setContentView(root);
            return;
        }
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true); // Microsoft's sign-in needs it
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setSaveFormData(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int pr) {
                if (pr >= 100) status.setText(codePage ? "Enter the code, sign in, then come back" : "Choose the account, or type its email");
                else status.setText("Loading... " + pr + "%");
                status.setTextColor(MainActivity.ACCENT2);
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) Hub.log("Sign-in page: " + m.message());
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (redirect(r.getUrl().toString())) return true;
                String scheme = r.getUrl().getScheme();
                return scheme != null && !scheme.equals("https") && !scheme.equals("http"); // stay on the web
            }

            @Override
            public void onPageStarted(WebView v, String u, android.graphics.Bitmap icon) {
                if (redirect(u)) v.stopLoading();
            }

            @Override
            public void onPageFinished(WebView v, String u) {
                // the code page says it's done: the app's own sign-in (waiting in the background) takes it from here
                if (codePage && u != null && (u.contains("/oauth20_remoteconnect.srf") || u.contains("microsoft.com/link"))) {
                    v.evaluateJavascript("(document.body && document.body.innerText || '').slice(0, 400)", new android.webkit.ValueCallback<String>() {
                        public void onReceiveValue(String text) {
                            if (text != null && (text.contains("You have signed in") || text.contains("signed in to Minecraft") || text.contains("You're all set"))) {
                                status.setText("Done - finishing in the app...");
                                web.postDelayed(new Runnable() {
                                    public void run() {
                                        finish();
                                    }
                                }, 1200);
                            }
                        }
                    });
                }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    status.setText("Couldn't load: " + e.getDescription() + " - check the connection and try again");
                    status.setTextColor(MainActivity.BAD);
                }
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        // a clean start (no saved sign-ins), then the page
        WebStorage.getInstance().deleteAllData();
        CookieManager.getInstance().removeAllCookies(new android.webkit.ValueCallback<Boolean>() {
            public void onReceiveValue(Boolean ok) {
                if (web != null && !isFinishing()) web.loadUrl(url);
            }
        });
        // in case the cookie callback never comes
        web.postDelayed(new Runnable() {
            public void run() {
                if (web != null && !isFinishing() && (web.getUrl() == null || web.getUrl().equals("about:blank"))) web.loadUrl(url);
            }
        }, 1500);
    }

    /** Microsoft sent the page back: hand the code (or the error) to the main screen. */
    private boolean redirect(String url) {
        if (done || url == null || !url.startsWith(Auth.REDIRECT)) return false;
        done = true;
        Intent r = new Intent();
        String code = Auth.query(url, "code");
        if (code != null) r.putExtra(RESULT_CODE, code);
        String err = Auth.query(url, "error_description");
        if (err == null) err = Auth.query(url, "error");
        if (err != null && !"access_denied".equals(Auth.query(url, "error"))) r.putExtra(RESULT_ERROR, err);
        setResult(RESULT_OK, r);
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        // nothing of the Microsoft session stays in the app
        CookieManager.getInstance().removeAllCookies(null);
        WebStorage.getInstance().deleteAllData();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /** The sign-in screen of bot slot n (its own process, as Slot explains). */
    public static final class Bot1 extends LoginActivity {
    }

    public static final class Bot2 extends LoginActivity {
    }

    public static final class Bot3 extends LoginActivity {
    }
}
