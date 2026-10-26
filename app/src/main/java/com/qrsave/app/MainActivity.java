package com.qrsave.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int REQ_IMG = 2001;
    private static final int BG = Color.parseColor("#0F172A");
    private static final int ACCENT = Color.parseColor("#F59E0B");
    private static final int CARD = Color.parseColor("#1E293B");

    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private Runnable backAction;
    private ImageCallback imageCallback;

    private interface ImageCallback { void onImage(Bitmap bmp); }

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("qrsave", MODE_PRIVATE);
        showHome();
    }

    @Override
    public void onBackPressed() {
        if (backAction != null) backAction.run(); else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_IMG) {
            ImageCallback cb = imageCallback;
            imageCallback = null;
            Bitmap bmp = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                bmp = decodeScaled(data.getData(), 600);
            }
            if (cb != null) cb.onImage(bmp);
            return;
        }
        IntentResult r = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (r != null) {
            if (r.getContents() != null) onScanned(r.getContents());
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ------------------------------------------------------------------ pantallas

    private void showHome() {
        backAction = null;
        LinearLayout box = screen();
        TextView title = title("QR-save");
        title.setOnLongClickListener(v -> { askServer(null); return true; });
        box.addView(title);
        box.addView(space(48));
        box.addView(bigButton("1. Generar", v -> showGenerate()));
        box.addView(space(16));
        box.addView(bigButton("2. Editar", v -> showEdit()));
        setContentView(box);
    }

    private void showGenerate() {
        backAction = this::showHome;
        LinearLayout box = screen();
        box.addView(title("Generar"));
        box.addView(space(24));

        final EditText input = new EditText(this);
        input.setHint("URL o texto (en dinámico es opcional)");
        input.setHintTextColor(Color.GRAY);
        input.setTextColor(Color.WHITE);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        box.addView(input, matchWrap());
        box.addView(space(24));

        box.addView(bigButton("Generar QR Dinámico", v -> {
            String t = input.getText().toString().trim();
            if (!t.isEmpty() && !isHttp(t)) { toast("La URL debe empezar con http:// o https://"); return; }
            ensureServer(() -> askLogo(true, t));
        }));
        box.addView(space(16));
        box.addView(bigButton("Generar QR Normal", v -> {
            String t = input.getText().toString().trim();
            if (t.isEmpty()) { toast("Escribe la URL o el texto"); return; }
            askLogo(false, t);
        }));
        setContentView(box);
    }

    private void showResult(final Bitmap bmp, final String id) {
        backAction = this::showHome;
        LinearLayout box = screen();
        box.addView(title("Tu QR"));
        box.addView(space(16));

        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        iv.setBackgroundColor(Color.WHITE);
        box.addView(iv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (id != null) {
            TextView t = new TextView(this);
            t.setText("Número de este QR: " + id);
            t.setTextColor(ACCENT);
            t.setTextSize(18);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(12), 0, 0);
            box.addView(t, matchWrap());
        }
        box.addView(space(16));
        box.addView(bigButton("Guardar en galería", v -> saveToGallery(bmp, id)));
        box.addView(space(12));
        box.addView(bigButton("Compartir", v -> share(bmp)));
        box.addView(space(12));
        box.addView(bigButton("Inicio", v -> showHome()));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(box);
        setContentView(sv);
    }

    private void showEdit() {
        backAction = this::showHome;
        LinearLayout box = screen();
        box.addView(title("Editar"));
        box.addView(space(32));
        box.addView(bigButton("Escanear y editar", v -> ensureServer(this::scan)));
        setContentView(box);
    }

    // ------------------------------------------------------------------ generar

    private void askLogo(final boolean dynamic, final String text) {
        new AlertDialog.Builder(this)
                .setTitle("¿Logo o imagen en el centro?")
                .setItems(new String[]{"Elegir imagen", "Sin imagen"}, (d, which) -> {
                    if (which == 0) {
                        imageCallback = bmp -> generate(dynamic, text, bmp);
                        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                        i.setType("image/*");
                        startActivityForResult(Intent.createChooser(i, "Elegir imagen"), REQ_IMG);
                    } else {
                        generate(dynamic, text, null);
                    }
                })
                .show();
    }

    private void generate(final boolean dynamic, final String text, final Bitmap logo) {
        if (!dynamic) {
            try {
                showResult(makeQr(text, logo, null), null);
            } catch (Exception e) {
                toast("No se pudo generar: " + e.getMessage());
            }
            return;
        }
        toast("Creando QR dinámico...");
        io.execute(() -> {
            try {
                String server = server();
                JSONObject req = new JSONObject();
                req.put("url", text);
                JSONObject res = new JSONObject(http("POST", server + "/api/create", req.toString()));
                String id = res.getString("id");
                String secret = res.getString("secret");
                prefs.edit().putString("sec_" + id, secret).apply();
                Bitmap bmp = makeQr(server + "/r/" + id, logo, id);
                ui.post(() -> showResult(bmp, id));
            } catch (Exception e) {
                ui.post(() -> toast("Error de conexión con el servidor: " + e.getMessage()));
            }
        });
    }

    private Bitmap makeQr(String content, Bitmap logo, String label) throws Exception {
        final int size = 1000;
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
        hints.put(EncodeHintType.MARGIN, 2);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
        int w = m.getWidth(), h = m.getHeight();
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
        Bitmap qr = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        qr.setPixels(px, 0, w, 0, 0, w, h);

        if (logo != null) {
            Canvas c = new Canvas(qr);
            int box = (int) (w * 0.24f);
            int pad = (int) (box * 0.08f);
            float left = (w - box) / 2f, top = (h - box) / 2f;
            Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(Color.WHITE);
            c.drawRoundRect(new RectF(left, top, left + box, top + box), 24, 24, white);
            int s = Math.min(logo.getWidth(), logo.getHeight());
            Bitmap sq = Bitmap.createBitmap(logo, (logo.getWidth() - s) / 2, (logo.getHeight() - s) / 2, s, s);
            Bitmap scaled = Bitmap.createScaledBitmap(sq, box - 2 * pad, box - 2 * pad, true);
            c.drawBitmap(scaled, left + pad, top + pad, null);
        }

        if (label == null) return qr;
        Bitmap out = Bitmap.createBitmap(w, h + 110, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);
        c.drawBitmap(qr, 0, 0, null);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTextSize(64);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("N° " + label, w / 2f, h + 80, p);
        return out;
    }

    private void saveToGallery(Bitmap bmp, String id) {
        if (Build.VERSION.SDK_INT < 29) { share(bmp); return; }
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "QR-save-" + (id != null ? id : System.currentTimeMillis()) + ".png");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/QR-save");
            Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new IOException("sin destino");
            try (OutputStream o = getContentResolver().openOutputStream(u)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
            }
            toast("Guardado en Imágenes/QR-save");
        } catch (Exception e) {
            toast("No se pudo guardar: " + e.getMessage());
        }
    }

    private void share(Bitmap bmp) {
        try {
            File f = new File(getCacheDir(), "qr-" + System.currentTimeMillis() + ".png");
            try (FileOutputStream o = new FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
            }
            Uri u = FileProvider.getUriForFile(this, "com.qrsave.app.fileprovider", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("image/png");
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Compartir QR"));
        } catch (Exception e) {
            toast("No se pudo compartir: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ editar

    private void scan() {
        new IntentIntegrator(this)
                .setPrompt("Escanea el QR dinámico")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan();
    }

    private void onScanned(String content) {
        Matcher m = Pattern.compile("^(https?://[^/]+)/r/(\\d{6,12})$").matcher(content.trim());
        if (!m.matches()) {
            toast("Este no es un QR dinámico de QR-save");
            return;
        }
        final String id = m.group(2);
        final String secret = prefs.getString("sec_" + id, null);
        if (secret == null) {
            toast("Este QR no fue creado en este teléfono, no se puede editar");
            return;
        }
        io.execute(() -> {
            try {
                JSONObject res = new JSONObject(http("GET", server() + "/api/get/" + id, null));
                final String current = res.optString("url", "");
                ui.post(() -> showEditDialog(id, secret, current));
            } catch (Exception e) {
                ui.post(() -> toast("Error de conexión: " + e.getMessage()));
            }
        });
    }

    private void showEditDialog(final String id, final String secret, String current) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(12));

        TextView t = new TextView(this);
        t.setText("QR número " + id);
        t.setTextSize(16);
        box.addView(t, matchWrap());

        final EditText et = new EditText(this);
        et.setText(current);
        et.setHint("https://...");
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.addView(et, matchWrap());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(16), 0, 0);

        ImageButton paste = iconButton(R.drawable.ic_paste, "Pegar");
        ImageButton save = iconButton(R.drawable.ic_save, "Guardar");
        row.addView(paste);
        row.addView(save);
        box.addView(row, matchWrap());

        final AlertDialog d = new AlertDialog.Builder(this).setView(box).create();

        paste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData cd = cm != null ? cm.getPrimaryClip() : null;
            if (cd != null && cd.getItemCount() > 0) {
                CharSequence s = cd.getItemAt(0).coerceToText(this);
                et.setText(s);
                et.setSelection(et.getText().length());
            } else {
                toast("No hay nada copiado");
            }
        });

        save.setOnClickListener(v -> {
            final String nu = et.getText().toString().trim();
            if (!nu.isEmpty() && !isHttp(nu)) { toast("La URL debe empezar con http:// o https://"); return; }
            io.execute(() -> {
                try {
                    JSONObject req = new JSONObject();
                    req.put("secret", secret);
                    req.put("url", nu);
                    http("PUT", server() + "/api/update/" + id, req.toString());
                    ui.post(() -> { toast("Guardado"); d.dismiss(); });
                } catch (Exception e) {
                    ui.post(() -> toast("No se pudo guardar: " + e.getMessage()));
                }
            });
        });

        d.show();
    }

    // ------------------------------------------------------------------ servidor

    private String server() { return prefs.getString("server", ""); }

    private void ensureServer(final Runnable next) {
        if (!server().isEmpty()) { next.run(); return; }
        askServer(next);
    }

    private void askServer(final Runnable next) {
        final EditText et = new EditText(this);
        et.setHint("https://qr-save.tuusuario.workers.dev");
        et.setText(server());
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this)
                .setTitle("Dirección de tu servidor QR-save")
                .setView(et)
                .setPositiveButton("Guardar", (d, w) -> {
                    String s = et.getText().toString().trim();
                    while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
                    if (!s.startsWith("https://")) { toast("Debe empezar con https://"); return; }
                    prefs.edit().putString("server", s).apply();
                    if (next != null) next.run();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private String http(String method, String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes("UTF-8")); }
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String s = in == null ? "" : readAll(in);
        if (code >= 400) throw new IOException("HTTP " + code + " " + s);
        return s;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    // ------------------------------------------------------------------ utilidades

    private static boolean isHttp(String s) {
        String l = s.toLowerCase();
        return l.startsWith("http://") || l.startsWith("https://");
    }

    private Bitmap decodeScaled(Uri uri, int max) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
            int sample = 1;
            while (o.outWidth / sample > max * 2 || o.outHeight / sample > max * 2) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            try (InputStream in = getContentResolver().openInputStream(uri)) { return BitmapFactory.decodeStream(in, null, o2); }
        } catch (Exception e) {
            return null;
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private LinearLayout screen() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setBackgroundColor(BG);
        l.setPadding(dp(24), dp(48), dp(24), dp(24));
        return l;
    }

    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(32);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private Button bigButton(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setTextColor(BG);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setColor(ACCENT);
        g.setCornerRadius(dp(14));
        b.setBackground(g);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        return b;
    }

    private ImageButton iconButton(int res, String desc) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setContentDescription(desc);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(dp(24), dp(12), dp(24), dp(12));
        return b;
    }

    private View space(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
