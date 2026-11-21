package id.tulis.editor;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import android.os.Build;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/** Ekspor PDF: vektor (teks bisa diseleksi) di Android 4.4+, dan PDF berbasis gambar di Android 4.2. */
final class PdfOut {
    static void write(Pager g, File out) throws Exception {
        if (Build.VERSION.SDK_INT >= 19) vector(g, out);
        else image(g, out);
    }

    @TargetApi(19)
    private static void vector(Pager g, File out) throws Exception {
        PdfDocument doc = new PdfDocument();
        try {
            float k = 1f / (2f * Docx.unit);
            int wPt = Math.round(Docx.pgW / 20f), hPt = Math.round(Docx.pgH / 20f);
            for (int i = 0; i < g.count(); i++) {
                PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(wPt, hPt, i + 1).create();
                PdfDocument.Page pg = doc.startPage(info);
                Canvas c = pg.getCanvas();
                c.scale(k, k);
                g.drawPage(c, i);
                doc.finishPage(pg);
            }
            FileOutputStream fo = new FileOutputStream(out);
            try { doc.writeTo(fo); } finally { fo.close(); }
        } finally {
            doc.close();
        }
    }

    private static final class Out {
        final OutputStream o;
        long pos;
        Out(OutputStream o) { this.o = o; }
        void str(String s) throws IOException { byte[] b = s.getBytes("ISO-8859-1"); o.write(b); pos += b.length; }
        void bytes(byte[] b) throws IOException { o.write(b); pos += b.length; }
    }

    private static void image(Pager g, File out) throws Exception {
        int n = g.count();
        float scale = 1.6f; // piksel per poin
        int wPt = Math.round(Docx.pgW / 20f), hPt = Math.round(Docx.pgH / 20f);
        int bw = Math.round(wPt * scale), bh = Math.round(hPt * scale);
        float f = scale / (2f * Docx.unit);
        Out o = new Out(new BufferedOutputStream(new FileOutputStream(out)));
        try {
            long[] off = new long[3 + 3 * n];
            o.str("%PDF-1.4\n");
            off[1] = o.pos;
            o.str("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
            StringBuilder kids = new StringBuilder();
            for (int i = 0; i < n; i++) kids.append(3 + 3 * i).append(" 0 R ");
            off[2] = o.pos;
            o.str("2 0 obj\n<< /Type /Pages /Kids [" + kids + "] /Count " + n + " >>\nendobj\n");
            for (int i = 0; i < n; i++) {
                int po = 3 + 3 * i, co = 4 + 3 * i, io = 5 + 3 * i;
                Bitmap bm = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                byte[] jpg;
                try {
                    Canvas c = new Canvas(bm);
                    c.drawColor(0xFFFFFFFF);
                    c.scale(f, f);
                    g.drawPage(c, i);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    bm.compress(Bitmap.CompressFormat.JPEG, 85, bo);
                    jpg = bo.toByteArray();
                } finally {
                    bm.recycle();
                }
                off[po] = o.pos;
                o.str(po + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + wPt + " " + hPt
                    + "] /Resources << /XObject << /Im0 " + io + " 0 R >> >> /Contents " + co + " 0 R >>\nendobj\n");
                String cs = "q " + wPt + " 0 0 " + hPt + " 0 0 cm /Im0 Do Q";
                off[co] = o.pos;
                o.str(co + " 0 obj\n<< /Length " + cs.length() + " >>\nstream\n" + cs + "\nendstream\nendobj\n");
                off[io] = o.pos;
                o.str(io + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + bw + " /Height " + bh
                    + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " + jpg.length + " >>\nstream\n");
                o.bytes(jpg);
                o.str("\nendstream\nendobj\n");
            }
            long xr = o.pos;
            int total = 3 + 3 * n;
            o.str("xref\n0 " + total + "\n0000000000 65535 f \n");
            for (int i = 1; i < total; i++) o.str(String.format(Locale.US, "%010d 00000 n \n", off[i]));
            o.str("trailer\n<< /Size " + total + " /Root 1 0 R >>\nstartxref\n" + xr + "\n%%EOF\n");
        } finally {
            o.o.close();
        }
    }
}
