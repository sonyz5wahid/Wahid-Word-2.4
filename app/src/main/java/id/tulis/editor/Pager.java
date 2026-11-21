package id.tulis.editor;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.AlignmentSpan;
import java.util.ArrayList;

/** Menyusun dokumen menjadi halaman (pagination) dan menggambarnya, termasuk rata kiri-kanan. Satuan: piksel tata letak. */
final class Pager {
    static final class Piece { int p, first, last; float y; }

    final ArrayList<ArrayList<Piece>> pages = new ArrayList<ArrayList<Piece>>();
    Layout[] ls;
    boolean[] just;
    float pageW, pageH, mL, mT, mR, mB, contentW, contentH;
    int pageNum;
    final TextPaint numPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    static Pager build(Spanned doc) {
        Pager g = new Pager();
        float u = Docx.unit;
        g.pageW = Docx.pgW * u / 10f;
        g.pageH = Docx.pgH * u / 10f;
        g.mL = Docx.mLeft * u / 10f;
        g.mR = Docx.mRight * u / 10f;
        g.mT = Docx.mTop * u / 10f;
        g.mB = Docx.mBottom * u / 10f;
        g.contentW = Math.max(60f, g.pageW - g.mL - g.mR);
        g.contentH = Math.max(60f, g.pageH - g.mT - g.mB);
        g.pageNum = Docx.pageNum;

        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(Fonts.get(Docx.defName, false, false));
        tp.setTextSize(Docx.defHp * u);
        tp.setColor(0xFF000000);
        g.numPaint.setTypeface(tp.getTypeface());
        g.numPaint.setTextSize(Docx.defHp * u);
        g.numPaint.setColor(0xFF000000);

        String s = doc.toString();
        ArrayList<Layout> lays = new ArrayList<Layout>();
        ArrayList<Boolean> js = new ArrayList<Boolean>();
        ArrayList<Boolean> pbs = new ArrayList<Boolean>();
        int ps = 0;
        while (true) {
            int pe = s.indexOf('\n', ps);
            boolean last = pe < 0;
            if (last) pe = s.length();
            SpannableStringBuilder p = new SpannableStringBuilder(doc, ps, pe);
            if (p.length() == 0) p.append(' ');
            Layout.Alignment al = Layout.Alignment.ALIGN_NORMAL;
            for (AlignmentSpan a : p.getSpans(0, p.length(), AlignmentSpan.class)) al = a.getAlignment();
            js.add(p.getSpans(0, p.length(), JustifySpan.class).length > 0);
            pbs.add(p.getSpans(0, p.length(), PageBreakSpan.class).length > 0);
            lays.add(new StaticLayout(p, tp, (int) g.contentW, al, 1f, 0f, false));
            if (last) break;
            ps = pe + 1;
        }
        int n = lays.size();
        g.ls = lays.toArray(new Layout[0]);
        g.just = new boolean[n];
        for (int i = 0; i < n; i++) g.just[i] = js.get(i);

        ArrayList<Piece> cur = new ArrayList<Piece>();
        g.pages.add(cur);
        float y = 0;
        for (int pi = 0; pi < n; pi++) {
            Layout L = g.ls[pi];
            int lc = L.getLineCount();
            Piece pc = null;
            for (int j = 0; j < lc; j++) {
                float h = L.getLineBottom(j) - L.getLineTop(j);
                boolean force = j == 0 && pbs.get(pi) && (y > 0 || cur.size() > 0);
                if (force || (y + h > g.contentH && y > 0)) {
                    cur = new ArrayList<Piece>();
                    g.pages.add(cur);
                    y = 0;
                    pc = null;
                }
                if (pc == null) {
                    pc = new Piece();
                    pc.p = pi;
                    pc.first = j;
                    pc.y = y;
                    cur.add(pc);
                }
                pc.last = j;
                y += h;
            }
        }
        return g;
    }

    int count() { return pages.size(); }

    /** Menggambar satu halaman penuh (isi + nomor halaman). Kanvas: titik awal = sudut kiri-atas halaman. */
    void drawPage(Canvas c, int k) {
        c.save();
        c.translate(mL, mT);
        drawContent(c, k);
        c.restore();
        if (pageNum > 0) {
            String t = String.valueOf(k + 1);
            float w = numPaint.measureText(t);
            float x = pageNum == 1 ? mL + (contentW - w) / 2f : pageNum == 2 ? mL + contentW - w : mL;
            c.drawText(t, x, pageH - mB * 0.5f, numPaint);
        }
    }

    private void drawContent(Canvas c, int k) {
        for (Piece pc : pages.get(k)) {
            Layout L = ls[pc.p];
            float top = L.getLineTop(pc.first), bot = L.getLineBottom(pc.last);
            c.save();
            c.translate(0, pc.y - top);
            c.clipRect(-4f, top, contentW + 4f, bot);
            if (!just[pc.p]) L.draw(c);
            else for (int j = pc.first; j <= pc.last; j++) drawJustLine(c, L, j);
            c.restore();
        }
    }

    private static boolean ltrOnly(CharSequence t, int a, int b) {
        for (int k = a; k < b; k++) {
            char ch = t.charAt(k);
            if ((ch >= 0x0590 && ch <= 0x08FF) || (ch >= 0xFB1D && ch <= 0xFEFF)) return false;
        }
        return true;
    }

    private void drawJustLine(Canvas c, Layout L, int j) {
        float lt = L.getLineTop(j), lb = L.getLineBottom(j);
        int a = L.getLineStart(j), e = L.getLineVisibleEnd(j);
        CharSequence t = L.getText();
        c.save();
        c.clipRect(-4f, lt, contentW + 4f, lb);
        boolean lastLine = j == L.getLineCount() - 1;
        float right = L.getLineRight(j);
        float extra = contentW - right;
        int gaps = 0;
        for (int k = a; k < e; k++) if (t.charAt(k) == ' ') gaps++;
        if (lastLine || gaps == 0 || extra <= 0.5f || extra > contentW * 0.6f || !ltrOnly(t, a, e)) {
            L.draw(c);
            c.restore();
            return;
        }
        float per = extra / gaps, shift = 0f;
        int segStart = a;
        float x0 = L.getLineLeft(j) - 2f;
        for (int k = a; k <= e; k++) {
            if (k == e || t.charAt(k) == ' ') {
                float x1 = k == e ? right + 2f : L.getPrimaryHorizontal(k) + 1f;
                if (k > segStart) {
                    c.save();
                    c.translate(shift, 0f);
                    c.clipRect(x0, lt, x1, lb);
                    L.draw(c);
                    c.restore();
                }
                if (k < e) {
                    shift += per;
                    segStart = k + 1;
                    x0 = (k + 1 <= e ? L.getPrimaryHorizontal(k + 1) : right) - 1f;
                }
            }
        }
        c.restore();
    }
}
