package app.lightcraft.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Runs the LightCraft web build (assets/www, built by `cargo xtask web`) in a WebView.
 *
 * - Serves the bundle from https://appassets.androidplatform.net/ (a secure context: OPFS, workers, WASM).
 * - Import: the page's <input type=file> opens Android's picker (onShowFileChooser).
 * - Export: the web build downloads through a blob link; a small script catches that click and hands
 *   the bytes to {@link Bridge}, which saves them with MediaStore (Pictures/LightCraft or
 *   Downloads/LightCraft).
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_FILES = 1;

    /** Turns the web build's blob downloads into saves on the device (see Bridge). */
    private static final String SHIM = """
        (function () {
          if (window.__lcShim) return;
          window.__lcShim = true;
          var CHUNK = 3 * 256 * 1024;
          var queue = Promise.resolve();
          function toBase64(blob) {
            return new Promise(function (resolve, reject) {
              var r = new FileReader();
              r.onload = function () { var s = String(r.result); resolve(s.substring(s.indexOf(',') + 1)); };
              r.onerror = function () { reject(r.error); };
              r.readAsDataURL(blob);
            });
          }
          async function save(blob, name) {
            var mime = blob.type || 'application/octet-stream';
            if (!AndroidBridge.begin(name, mime)) { throw new Error('could not create the file'); }
            for (var o = 0; o < blob.size; o += CHUNK) {
              var part = await toBase64(blob.slice(o, Math.min(o + CHUNK, blob.size)));
              if (!AndroidBridge.chunk(part)) { throw new Error('write failed'); }
            }
            AndroidBridge.end();
          }
          var originalClick = HTMLAnchorElement.prototype.click;
          HTMLAnchorElement.prototype.click = function () {
            if (this.hasAttribute('download') && this.href && this.href.indexOf('blob:') === 0) {
              var name = this.getAttribute('download') || 'download';
              var pending = fetch(this.href).then(function (r) { return r.blob(); });
              queue = queue
                .then(function () { return pending; })
                .then(function (blob) { return save(blob, name); })
                .catch(function (e) { AndroidBridge.abort(String(e)); });
              return;
            }
            return originalClick.apply(this, arguments);
          };
        })();
        """;

    private WebView web;
    private ValueCallback<Uri[]> pendingChooser;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true); // chrome://inspect for debug builds
        }

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(0x1e, 0x23, 0x27));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/", new BundleHandler(getAssets()))
                .build();

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) {
                    return false;
                }
                // links to other sites open in the browser, never inside the app
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                    // no browser installed
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(SHIM, null);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingChooser != null) {
                    pendingChooser.onReceiveValue(null);
                }
                pendingChooser = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILES);
                } catch (ActivityNotFoundException e) {
                    pendingChooser = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl("https://" + HOST + "/index.html");
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILES) {
            if (pendingChooser != null) {
                pendingChooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                pendingChooser = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }

    private void toast(final String text) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show());
    }

    /** Serves assets/www/* with the right MIME types (.wasm must be application/wasm). */
    private static final class BundleHandler implements WebViewAssetLoader.PathHandler {
        private final AssetManager assets;

        BundleHandler(AssetManager assets) {
            this.assets = assets;
        }

        @Override
        public WebResourceResponse handle(String path) {
            if (path.isEmpty() || path.endsWith("/")) {
                path = path + "index.html";
            }
            if (path.contains("..")) {
                return notFound();
            }
            try {
                InputStream in = assets.open("www/" + path);
                String mime = mimeOf(path);
                boolean text = mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("javascript");
                WebResourceResponse r = new WebResourceResponse(mime, text ? "utf-8" : null, in);
                Map<String, String> headers = new HashMap<>();
                headers.put("Cache-Control", "no-cache");
                r.setResponseHeaders(headers);
                return r;
            } catch (IOException e) {
                return notFound();
            }
        }

        private static WebResourceResponse notFound() {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<String, String>(),
                    new ByteArrayInputStream(new byte[0]));
        }

        private static String mimeOf(String path) {
            String p = path.toLowerCase(Locale.ROOT);
            if (p.endsWith(".html")) return "text/html";
            if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript";
            if (p.endsWith(".wasm")) return "application/wasm";
            if (p.endsWith(".css")) return "text/css";
            if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json";
            if (p.endsWith(".otf")) return "font/otf";
            if (p.endsWith(".ttf")) return "font/ttf";
            if (p.endsWith(".woff2")) return "font/woff2";
            if (p.endsWith(".png")) return "image/png";
            if (p.endsWith(".svg")) return "image/svg+xml";
            if (p.endsWith(".ico")) return "image/x-icon";
            return "application/octet-stream";
        }
    }

    /** Saves what the page "downloads": begin, then chunk (base64) repeatedly, then end. */
    private final class Bridge {
        private OutputStream out;
        private Uri uri;
        private String shownName;
        private boolean image;

        @JavascriptInterface
        public synchronized boolean begin(String name, String mime) {
            discard();
            try {
                ContentResolver cr = getContentResolver();
                image = mime != null && mime.startsWith("image/");
                String folder = (image ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS) + "/LightCraft";
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri collection = image
                        ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                uri = cr.insert(collection, v);
                if (uri == null) {
                    return false;
                }
                out = cr.openOutputStream(uri);
                shownName = name;
                return out != null;
            } catch (Exception e) {
                discard();
                return false;
            }
        }

        @JavascriptInterface
        public synchronized boolean chunk(String base64) {
            if (out == null) {
                return false;
            }
            try {
                out.write(Base64.decode(base64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                discard();
                return false;
            }
        }

        @JavascriptInterface
        public synchronized void end() {
            if (out == null || uri == null) {
                return;
            }
            try {
                out.close();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, v, null, null);
                toast("Saved " + shownName + " to " + (image ? "Pictures/LightCraft" : "Downloads/LightCraft"));
                out = null;
                uri = null;
            } catch (Exception e) {
                discard();
                toast("Could not save " + shownName);
            }
        }

        @JavascriptInterface
        public synchronized void abort(String why) {
            discard();
            toast("Could not save the file: " + why);
        }

        /** Closes and deletes a half-written file. */
        private void discard() {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException ignored) {
                // already closed
            }
            try {
                if (uri != null) {
                    getContentResolver().delete(uri, null, null);
                }
            } catch (Exception ignored) {
                // nothing to delete
            }
            out = null;
            uri = null;
        }
    }
}
