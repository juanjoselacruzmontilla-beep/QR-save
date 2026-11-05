package com.qrsave.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** Vista de recorte cuadrado: se arrastra el recuadro y se cambia su tamaño con pellizco o barra. */
public class CropView extends View {

    public interface Listener { void onChanged(); }

    private Bitmap bmp;
    private float cx, cy;          // centro del recuadro (coordenadas de la imagen)
    private float fraction = 0.8f; // lado = fraction * min(ancho, alto)
    private float scale = 1f, offX = 0f, offY = 0f;
    private float lastX, lastY;
    private Listener listener;

    private final Paint dim = new Paint();
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector sgd;

    public CropView(Context c) {
        super(c);
        dim.setColor(Color.argb(150, 0, 0, 0));
        border.setColor(Color.WHITE);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(4f);
        grid.setColor(Color.argb(120, 255, 255, 255));
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(1.5f);
        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                setFraction(fraction / d.getScaleFactor());
                return true;
            }
        });
    }

    public void setListener(Listener l) { listener = l; }

    public void setBitmap(Bitmap b) {
        bmp = b;
        cx = b.getWidth() / 2f;
        cy = b.getHeight() / 2f;
        clampCenter();
        invalidate();
    }

    public float getFraction() { return fraction; }

    public void setFraction(float f) {
        fraction = Math.max(0.1f, Math.min(1f, f));
        clampCenter();
        invalidate();
        if (listener != null) listener.onChanged();
    }

    private float side() { return fraction * Math.min(bmp.getWidth(), bmp.getHeight()); }

    private void clampCenter() {
        if (bmp == null) return;
        float h = side() / 2f;
        cx = Math.max(h, Math.min(bmp.getWidth() - h, cx));
        cy = Math.max(h, Math.min(bmp.getHeight() - h, cy));
    }

    /** Devuelve el recorte cuadrado actual. */
    public Bitmap getCropped() {
        int s = Math.max(1, Math.round(side()));
        s = Math.min(s, Math.min(bmp.getWidth(), bmp.getHeight()));
        int x = Math.round(cx - s / 2f);
        int y = Math.round(cy - s / 2f);
        x = Math.max(0, Math.min(bmp.getWidth() - s, x));
        y = Math.max(0, Math.min(bmp.getHeight() - s, y));
        return Bitmap.createBitmap(bmp, x, y, s, s);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bmp == null) return;
        int vw = getWidth(), vh = getHeight();
        scale = Math.min((float) vw / bmp.getWidth(), (float) vh / bmp.getHeight());
        offX = (vw - bmp.getWidth() * scale) / 2f;
        offY = (vh - bmp.getHeight() * scale) / 2f;
        RectF dst = new RectF(offX, offY, offX + bmp.getWidth() * scale, offY + bmp.getHeight() * scale);
        c.drawBitmap(bmp, null, dst, null);

        float h = side() / 2f;
        float l = offX + (cx - h) * scale, t = offY + (cy - h) * scale;
        float r = offX + (cx + h) * scale, b = offY + (cy + h) * scale;
        c.drawRect(dst.left, dst.top, dst.right, t, dim);
        c.drawRect(dst.left, b, dst.right, dst.bottom, dim);
        c.drawRect(dst.left, t, l, b, dim);
        c.drawRect(r, t, dst.right, b, dim);
        float w3 = (r - l) / 3f, h3 = (b - t) / 3f;
        for (int i = 1; i < 3; i++) {
            c.drawLine(l + w3 * i, t, l + w3 * i, b, grid);
            c.drawLine(l, t + h3 * i, r, t + h3 * i, grid);
        }
        c.drawRect(l, t, r, b, border);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bmp == null) return false;
        sgd.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX();
                lastY = e.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!sgd.isInProgress() && e.getPointerCount() == 1) {
                    cx += (e.getX() - lastX) / scale;
                    cy += (e.getY() - lastY) / scale;
                    clampCenter();
                    invalidate();
                    if (listener != null) listener.onChanged();
                }
                lastX = e.getX();
                lastY = e.getY();
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                lastX = e.getX();
                lastY = e.getY();
                break;
            default:
                break;
        }
        return true;
    }
}
