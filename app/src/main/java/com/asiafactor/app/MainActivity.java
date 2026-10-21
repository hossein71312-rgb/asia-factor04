package com.asiafactor.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 1001;
    private static final int REQ_LICENSE_EXPORT = 1002;
    private static final int REQ_LICENSE_IMPORT = 1003;
    private static final int NAVY = 0xFF02122E;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(NAVY);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false); // lets the intro jingle play automatically
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // logo upload + JSON import
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            // confirm() / alert() do nothing in a bare WebView unless handled here
            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton("بله", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { result.confirm(); }
                        })
                        .setNegativeButton("خیر", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { result.cancel(); }
                        })
                        .show();
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton("باشه", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { result.confirm(); }
                        })
                        .show();
                return true;
            }
        });

        if (LicenseManager.isLicenseValid(this)) {
            loadMainApp();
        } else {
            showLicenseGate();
        }
    }

    private void loadMainApp() {
        web.loadUrl("file:///android_asset/index.html");
    }

    private void showLicenseGate() {
        new AlertDialog.Builder(this)
                .setTitle("فعال‌سازی برنامه فاکتور")
                .setCancelable(false)
                .setItems(new String[]{"ساخت فایل درخواست فعال‌سازی", "انتخاب فایل مجوز", "خروج"}, (dialog, which) -> {
                    if (which == 0) exportActivationRequest();
                    else if (which == 1) importLicense();
                    else finish();
                })
                .show();
    }

    private void exportActivationRequest() {
        try {
            String request = LicenseManager.createActivationRequest(this);
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "AsiaFactor-Activation-Request.json");
            pendingActivationRequest = request;
            startActivityForResult(intent, REQ_LICENSE_EXPORT);
        } catch (Exception e) {
            toast("ساخت درخواست فعال‌سازی ناموفق بود.");
            showLicenseGate();
        }
    }

    private String pendingActivationRequest;

    private void importLicense() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, REQ_LICENSE_IMPORT);
    }

    private void handleLicenseImport(Uri uri) {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            if (in == null) throw new java.io.IOException("Cannot open license file");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            String licenseJson = new String(out.toByteArray(), "UTF-8");
            String error = LicenseManager.validateLicenseJson(this, licenseJson);
            if (error != null) {
                new AlertDialog.Builder(this).setTitle("فعال‌سازی ناموفق")
                        .setMessage(error).setPositiveButton("تلاش دوباره", (d, w) -> showLicenseGate())
                        .setNegativeButton("خروج", (d, w) -> finish()).show();
                return;
            }
            LicenseManager.installLicense(this, licenseJson);
            toast("مجوز با موفقیت فعال شد.");
            loadMainApp();
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("خطا در خواندن مجوز")
                    .setMessage("فایل مجوز خوانده نشد. لطفاً فایل درست را انتخاب کنید.")
                    .setPositiveButton("تلاش دوباره", (d, w) -> showLicenseGate())
                    .setNegativeButton("خروج", (d, w) -> finish()).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_LICENSE_EXPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingActivationRequest != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    if (out == null) throw new java.io.IOException("Cannot write request file");
                    out.write(pendingActivationRequest.getBytes("UTF-8"));
                    out.flush();
                    toast("فایل درخواست فعال‌سازی ذخیره شد. آن را به صادرکننده مجوز بدهید.");
                } catch (Exception e) {
                    toast("ذخیره فایل درخواست ناموفق بود.");
                }
            }
            pendingActivationRequest = null;
            if (!LicenseManager.isLicenseValid(this)) showLicenseGate();
            return;
        }
        if (requestCode == REQ_LICENSE_IMPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                handleLicenseImport(data.getData());
            } else if (!LicenseManager.isLicenseValid(this)) {
                showLicenseGate();
            }
            return;
        }
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        // first close the "saved invoices" dialog if it is open, otherwise leave the app
        web.evaluateJavascript(
                "(function(){var m=document.getElementById('loadModal');" +
                "if(m&&m.classList.contains('open')){InvoiceApp.closeModal();return 'closed';}return 'exit';})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if (value != null && value.contains("closed")) return;
                        MainActivity.super.onBackPressed();
                    }
                });
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); }
        });
    }

    /** Called from JavaScript as window.AndroidBridge.* */
    private class Bridge {

        @JavascriptInterface
        public String saveFile(String base64, String name, String mime) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                String where;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AsiaFactor");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    try { os.write(data); } finally { os.close(); }
                    where = "Downloads/AsiaFactor";
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) dir = getFilesDir();
                    File f = new File(dir, safe);
                    FileOutputStream os = new FileOutputStream(f);
                    try { os.write(data); } finally { os.close(); }
                    where = f.getParent();
                }
                toast("ذخیره شد: " + where + "/" + safe);
                return "ok";
            } catch (Exception e) {
                toast("خطا در ذخیره فایل");
                return "error";
            }
        }

        @JavascriptInterface
        public void print() {
            runOnUiThread(new Runnable() {
                public void run() {
                    PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                    String job = "فاکتور";
                    PrintDocumentAdapter adapter = web.createPrintDocumentAdapter(job);
                    pm.print(job, adapter, new PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
                }
            });
        }
    }
}
