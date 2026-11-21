package id.tulis.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

/** Tampilan halaman kertas (pratinjau cetak). Hanya halaman yang terlihat yang digambar. */
class PageView extends View {
    private Pager pg;
    private float zoom = 1f;
    private final Paint paint = new Paint();
    private final Rect clip = new Rect();

    PageView(Context c) { super(c); }

    void setPager(Pager p) {
        pg = p;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int wms, int hms) {
        int w = MeasureSpec.getSize(wms);
        if (pg == null || w <= 0) { setMeasuredDimension(Math.max(w, 1), 100); return; }
        float gap = pg.pageW * 0.03f;
        zoom = w / (pg.pageW + 2f * gap);
        int h = (int) ((pg.count() * (pg.pageH + gap) + gap) * zoom);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (pg == null) return;
        float gap = pg.pageW * 0.03f;
        c.drawColor(0xFFE6E6E6);
        c.scale(zoom, zoom);
        c.getClipBounds(clip);
        for (int k = 0; k < pg.count(); k++) {
            float top = gap + k * (pg.pageH + gap);
            if (top + pg.pageH < clip.top || top > clip.bottom) continue;
            c.save();
            c.translate(gap, top);
            paint.setColor(0x33000000);
            c.drawRect(3f, 3f, pg.pageW + 3f, pg.pageH + 3f, paint);
            paint.setColor(0xFFFFFFFF);
            c.drawRect(0f, 0f, pg.pageW, pg.pageH, paint);
            c.clipRect(0f, 0f, pg.pageW, pg.pageH);
            pg.drawPage(c, k);
            c.restore();
        }
    }
}
