package id.tulis.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

/** Penggaris horizontal dengan penanda indent baris pertama dan indent kiri yang bisa digeser. */
class RulerView extends View {
    interface Callback { void onIndent(int left, int first); }

    Callback cb;
    private final float d;
    private float pageX, unit = 1f;
    private int mL = 1440, mR = 1440, pageW = 11906, left, first, drag;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    RulerView(Context c, float density) {
        super(c);
        d = density;
    }

    void setPage(float pageX, float unit, int mL, int mR, int pageW) {
        this.pageX = pageX; this.unit = unit; this.mL = mL; this.mR = mR; this.pageW = pageW;
        invalidate();
    }

    void setIndent(int left, int first) {
        if (drag != 0) return;
        this.left = left; this.first = first;
        invalidate();
    }

    private float cx() { return pageX + mL * unit / 10f; }
    private float xOf(int tw) { return cx() + tw * unit / 10f; }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawColor(0xFFF3F3F3);
        float px0 = pageX, px1 = pageX + pageW * unit / 10f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFD2D2D2);
        c.drawRect(Math.max(0, px0), h * 0.28f, Math.min(w, px1), h * 0.72f, p);
        p.setColor(0xFFFFFFFF);
        c.drawRect(Math.max(0, px0 + mL * unit / 10f), h * 0.28f, Math.min(w, px1 - mR * unit / 10f), h * 0.72f, p);
        p.setColor(0xFF666666);
        p.setStrokeWidth(1f);
        p.setTextSize(9f * d);
        p.setTextAlign(Paint.Align.CENTER);
        float cmPx = 567f * unit / 10f;
        int contentCm = (int) ((pageW - mL - mR) / 567f);
        for (int k = 0; k <= contentCm * 2; k++) {
            float x = cx() + k * cmPx / 2f;
            if (x < 0 || x > w) continue;
            if (k % 2 == 0) {
                c.drawLine(x, h * 0.28f, x, h * 0.72f, p);
                if (k > 0) c.drawText(String.valueOf(k / 2), x, h * 0.62f, p);
            } else {
                c.drawLine(x, h * 0.42f, x, h * 0.58f, p);
            }
        }
        p.setColor(0xFF2B579A);
        p.setStyle(Paint.Style.FILL);
        float s = 5f * d;
        float xf = xOf(left + first), xl = xOf(left);
        path.reset();
        path.moveTo(xf - s, 0f); path.lineTo(xf + s, 0f); path.lineTo(xf, s * 1.6f); path.close();
        c.drawPath(path, p);
        path.reset();
        path.moveTo(xl - s, h); path.lineTo(xl + s, h); path.lineTo(xl, h - s * 1.6f); path.close();
        c.drawPath(path, p);
    }

    private int toTw(float x) {
        int tw = Math.round((x - cx()) * 10f / unit);
        return Math.round(tw / 142f) * 142;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float x = ev.getX(), y = ev.getY();
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                float df = Math.abs(x - xOf(left + first)), dl = Math.abs(x - xOf(left));
                float lim = 28f * d;
                if (y < getHeight() / 2f && df < lim) drag = 1;
                else if (y >= getHeight() / 2f && dl < lim) drag = 2;
                else if (Math.min(df, dl) < lim) drag = df <= dl ? 1 : 2;
                else drag = 0;
                return drag != 0;
            }
            case MotionEvent.ACTION_MOVE: {
                if (drag == 0) return false;
                int tw = Math.max(0, toTw(x));
                if (drag == 1) first = tw - left;
                else { int abs = left + first; left = tw; first = abs - left; }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: {
                int was = drag;
                drag = 0;
                if (was != 0 && cb != null) cb.onIndent(Math.max(0, left), left + first < 0 ? -left : first);
                return true;
            }
            default: return false;
        }
    }
}
