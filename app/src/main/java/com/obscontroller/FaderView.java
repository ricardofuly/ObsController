package com.obscontroller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Fader de volume desenhado à mão, leve e sem "pulo":
 * - arrastar move o volume a partir de onde ele está (relativo), então tocar sem querer não muda nada;
 * - toque rápido = onTap, toque longo parado = onLongPress;
 * - desenha o preenchimento do volume, a marca de posição e o medidor de nível (pico).
 * Funciona na horizontal (faixas) e na vertical (mixer).
 */
public class FaderView extends View {

    public interface Listener {
        void onStart();
        void onChange(double pos);
        void onEnd(double pos);
        void onTap();
        void onLongPress();
    }

    private final boolean vertical;
    private final float density;
    private final int slop;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private Listener listener;

    private double pos;          // 0..1 (posição do fader)
    private float meter;         // 0..1 (nível de pico)
    private int meterColor = 0xFF3DD68C;
    private int bgColor = 0xFF181A1F, strokeColor = 0xFF262930, fillColor = 0x334C9EFF, markColor = 0xFF4C9EFF;
    private float strokeW;
    private float radius;
    private float insetStart;      // início da área útil (px) — p.ex. depois do botão de mudo
    private boolean tickMark;      // marca curta em cima e embaixo (não cruza o texto)

    private float downX, downY;
    private double startPos;
    private long downAt;
    private boolean dragging, moved, longFired;
    private boolean atEdge;
    private final Runnable longPress = () -> {
        if (!dragging && !moved) {
            longFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (listener != null) listener.onLongPress();
        }
    };

    public FaderView(Context ctx, boolean vertical) {
        super(ctx);
        this.vertical = vertical;
        density = ctx.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        radius = 14 * density;
        strokeW = density;
        setClickable(true);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setPosition(double p) {
        if (dragging) return; // não briga com o dedo
        pos = clamp(p);
        invalidate();
    }

    public double getPosition() {
        return pos;
    }

    public boolean isDragging() {
        return dragging;
    }

    public void setMeter(float level, int color) {
        float m = Math.max(0, Math.min(1, level));
        if (Math.abs(m - meter) < 0.004f && color == meterColor) return; // nada mudou: não redesenha
        meter = m;
        meterColor = color;
        invalidate();
    }

    public void setColors(int bg, int stroke, float strokeDp, int fill, int mark) {
        bgColor = bg;
        strokeColor = stroke;
        strokeW = strokeDp * density;
        fillColor = fill;
        markColor = mark;
        invalidate();
    }

    public void setBgColor(int bg) {
        bgColor = bg;
        invalidate();
    }

    /** Faixa: o volume começa depois do botão de mudo e a marca vira dois "ticks" que não cobrem o texto. */
    public void setStripStyle(float insetStartDp) {
        insetStart = insetStartDp * density;
        tickMark = true;
        invalidate();
    }

    public void setRadiusDp(float dp) {
        radius = dp * density;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float half = strokeW / 2f;
        r.set(half, half, w - half, h - half);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(bgColor);
        c.drawRoundRect(r, radius, radius, paint);

        float p = (float) pos;
        float m = 4 * density;
        float t = 3 * density, inset = 8 * density;
        if (vertical) {
            c.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            paint.setColor(fillColor);
            c.drawRect(0, h - h * p, w, h, paint);
            // Medidor de nível à esquerda, no mesmo eixo do fader.
            paint.setColor(0x33FFFFFF);
            c.drawRect(0, 0, m, h, paint);
            paint.setColor(meterColor);
            c.drawRect(0, h - h * meter, m, h, paint);
            c.restore();
            paint.setColor(markColor);
            float y = Math.max(t, Math.min(h - t, h - h * p));
            c.drawRoundRect(inset, y - t, w - inset, y + t, t, t, paint);
        } else if (insetStart > 0) {
            // Faixa: o volume é um preenchimento sobre a faixa inteira (da borda esquerda até a posição).
            // O medidor usa o MESMO eixo, então começa na borda esquerda junto com o preenchimento.
            float span = Math.max(1f, w);
            c.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            paint.setColor(fillColor);
            c.drawRect(0, 0, span * p, h, paint);
            paint.setColor(0x22FFFFFF);
            c.drawRect(0, h - m, w, h, paint);
            paint.setColor(meterColor);
            c.drawRect(0, h - m, span * meter, h, paint);
            c.restore();
            // Marca da posição: dois traços curtos (em cima e embaixo) que não cobrem o texto.
            paint.setColor(markColor);
            float x = Math.max(t, Math.min(w - t, span * p));
            float tl = 9 * density, hw = 1.5f * density;
            c.drawRoundRect(x - hw, 0, x + hw, tl, hw, hw, paint);
            c.drawRoundRect(x - hw, h - tl - m, x + hw, h - m, hw, hw, paint);
        } else {
            float span = Math.max(1f, w);
            c.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            paint.setColor(fillColor);
            c.drawRect(0, 0, span * p, h, paint);
            paint.setColor(0x33FFFFFF);
            c.drawRect(0, h - m, w, h, paint);
            paint.setColor(meterColor);
            c.drawRect(0, h - m, span * meter, h, paint);
            c.restore();
            paint.setColor(markColor);
            float x = Math.max(t, Math.min(w - t, span * p));
            c.drawRoundRect(x - t / 1.5f, inset, x + t / 1.5f, h - inset, t, t, paint);
        }

        if (strokeW > 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokeW);
            paint.setColor(strokeColor);
            c.drawRoundRect(r, radius, radius, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!isEnabled()) return false;
        float x = ev.getX(), y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                startPos = pos;
                downAt = SystemClock.uptimeMillis();
                dragging = moved = longFired = false;
                setPressed(true);
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE: {
                float along = vertical ? downY - y : x - downX;
                float across = vertical ? x - downX : y - downY;
                if (!dragging && !longFired && Math.abs(along) > slop && Math.abs(along) > Math.abs(across)) {
                    dragging = true;
                    moved = true;
                    removeCallbacks(longPress);
                    getParent().requestDisallowInterceptTouchEvent(true);
                    // Recomeça a contagem daqui, para não dar um salto do tamanho do "slop".
                    downX = x;
                    downY = y;
                    along = 0;
                    if (listener != null) listener.onStart();
                }
                // Qualquer movimento perceptível cancela o "segurar" (evita abrir "Ocultar" ao arrastar devagar).
                if (!dragging && (Math.abs(along) > 4 * density || Math.abs(across) > 4 * density)) {
                    moved = true;
                    removeCallbacks(longPress);
                }
                if (dragging) {
                    float len = vertical ? getHeight() : getWidth();
                    double np = clamp(startPos + along / Math.max(1f, len));
                    boolean edge = np <= 0 || np >= 1;
                    if (edge && !atEdge) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    atEdge = edge;
                    if (np != pos) {
                        pos = np;
                        invalidate();
                        if (listener != null) listener.onChange(pos);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                setPressed(false);
                if (dragging) {
                    dragging = false;
                    if (listener != null) listener.onEnd(pos);
                } else if (ev.getActionMasked() == MotionEvent.ACTION_UP && !moved && !longFired
                        && SystemClock.uptimeMillis() - downAt < ViewConfiguration.getLongPressTimeout()) {
                    performClick();
                    if (listener != null) listener.onTap();
                }
                return true;
        }
        return false;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
