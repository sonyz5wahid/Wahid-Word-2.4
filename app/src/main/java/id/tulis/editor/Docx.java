package id.tulis.editor;

import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AlignmentSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.UnderlineSpan;
import android.util.Xml;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.xmlpull.v1.XmlPullParser;

/** Baca/tulis .docx dengan style, penomoran, spasi paragraf, font, warna, dan perataan. */
final class Docx {
    static String defName = "Calibri";
    static int defHp = 22, defLine = 240, defBefore = 0, defAfter = 0;
    static final LinkedHashSet<String> lost = new LinkedHashSet<String>(); // hilang jika disimpan
    static final LinkedHashSet<String> conv = new LinkedHashSet<String>(); // disederhanakan
    static float unit = 1f;                                                  // piksel per setengah poin
    static int pgW = 11906, pgH = 16838, mTop = 1440, mBottom = 1440, mLeft = 1440, mRight = 1440; // twips
    static int pageNum = 0;                                                  // 0 tanpa, 1 tengah, 2 kanan, 3 kiri
    private static int numFirst = Integer.MIN_VALUE;

    private static final int U = Integer.MIN_VALUE;
    private static final int INC = Spanned.SPAN_EXCLUSIVE_INCLUSIVE;
    private static final int PAR = Spanned.SPAN_PARAGRAPH;
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";

    static void resetDefaults() {
        defName = "Calibri"; defHp = 22; defLine = 240; defBefore = 0; defAfter = 0;
        lost.clear(); conv.clear();
        pgW = 11906; pgH = 16838; mTop = 1440; mBottom = 1440; mLeft = 1440; mRight = 1440; pageNum = 0;
    }

    // ---------- model format ----------
    private static final class Fmt {
        String font, jc, pstyle, rstyle;
        int b = U, i = U, u = U, st = U, hp = U, col = U, shd = U, va = U;
        int line = U, before = U, after = U, ind = U, first = U, pbb = U, numId = U, ilvl = U;
        void merge(Fmt o) {
            if (o == null) return;
            if (o.font != null) font = o.font;
            if (o.jc != null) jc = o.jc;
            if (o.pstyle != null) pstyle = o.pstyle;
            if (o.rstyle != null) rstyle = o.rstyle;
            if (o.b != U) b = o.b;
            if (o.i != U) i = o.i;
            if (o.u != U) u = o.u;
            if (o.st != U) st = o.st;
            if (o.hp != U) hp = o.hp;
            if (o.col != U) col = o.col;
            if (o.shd != U) shd = o.shd;
            if (o.va != U) va = o.va;
            if (o.line != U) line = o.line;
            if (o.before != U) before = o.before;
            if (o.after != U) after = o.after;
            if (o.ind != U) ind = o.ind;
            if (o.first != U) first = o.first;
            if (o.pbb != U) pbb = o.pbb;
            if (o.numId != U) numId = o.numId;
            if (o.ilvl != U) ilvl = o.ilvl;
        }
    }

    private static final class Style { String based; Fmt f = new Fmt(); }
    private static final class Lvl { String fmt = "decimal", text = "%1."; int start = 1, left = U, hang = U; }

    private static final HashMap<String, Style> styles = new HashMap<String, Style>();
    private static final HashMap<Integer, Integer> numMap = new HashMap<Integer, Integer>();
    private static final HashMap<Integer, Lvl[]> absMap = new HashMap<Integer, Lvl[]>();
    private static Fmt docDef = new Fmt();
    private static String defPara = "Normal";

    private static Fmt resolve(String id) {
        Fmt out = new Fmt();
        if (id == null) return out;
        ArrayList<Style> chain = new ArrayList<Style>();
        String cur = id;
        int guard = 0;
        while (cur != null && guard++ < 12) {
            Style s = styles.get(cur);
            if (s == null) break;
            chain.add(s);
            cur = s.based;
        }
        for (int k = chain.size() - 1; k >= 0; k--) out.merge(chain.get(k).f);
        return out;
    }

    private static Fmt base(Fmt pdir) {
        Fmt b = new Fmt();
        b.merge(docDef);
        b.merge(resolve(pdir.pstyle != null ? pdir.pstyle : defPara));
        return b;
    }

    private static void finishDefaults() {
        Fmt b = new Fmt();
        b.merge(docDef);
        b.merge(resolve(defPara));
        defName = b.font != null ? b.font : "Calibri";
        defHp = b.hp != U ? b.hp : 22;
        defLine = b.line != U ? b.line : 240;
        defBefore = b.before != U ? b.before : 0;
        defAfter = b.after != U ? b.after : 0;
    }

    // ---------- TULIS ----------
    static void write(Spanned t, File f) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)));
        try {
            put(z, "[Content_Types].xml", DECL
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + (pageNum > 0 ? "<Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/>" : "")
                + "</Types>");
            put(z, "_rels/.rels", DECL
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>");
            put(z, "word/_rels/document.xml.rels", DECL
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + (pageNum > 0 ? "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer\" Target=\"footer1.xml\"/>" : "")
                + "</Relationships>");
            if (pageNum > 0) {
                String jc = pageNum == 1 ? "center" : pageNum == 2 ? "right" : "left";
                put(z, "word/footer1.xml", DECL + "<w:ftr xmlns:w=\"" + W + "\"><w:p><w:pPr><w:jc w:val=\"" + jc
                    + "\"/></w:pPr><w:fldSimple w:instr=\" PAGE \"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p></w:ftr>");
            }
            String fn = esc(defName);
            put(z, "word/styles.xml", DECL + "<w:styles xmlns:w=\"" + W + "\"><w:docDefaults><w:rPrDefault><w:rPr>"
                + "<w:rFonts w:ascii=\"" + fn + "\" w:hAnsi=\"" + fn + "\" w:eastAsia=\"" + fn + "\" w:cs=\"" + fn + "\"/>"
                + "<w:sz w:val=\"" + defHp + "\"/><w:szCs w:val=\"" + defHp + "\"/><w:lang w:val=\"id-ID\"/>"
                + "</w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:before=\"" + defBefore + "\" w:after=\"" + defAfter
                + "\" w:line=\"" + defLine + "\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
                + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style></w:styles>");
            put(z, "word/document.xml", body(t));
        } finally {
            z.close();
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("Tidak bisa menulis file");
        }
    }

    private static void put(ZipOutputStream z, String name, String s) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(s.getBytes("UTF-8"));
        z.closeEntry();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static Object at(Spanned t, Object[] arr, int ps, int pe) {
        for (Object o : arr) {
            int s = t.getSpanStart(o), e = t.getSpanEnd(o);
            if (s <= ps && (e > ps || (e == ps && pe == ps))) return o;
        }
        return null;
    }

    private static String body(Spanned t) {
        StringBuilder x = new StringBuilder(DECL);
        x.append("<w:document xmlns:w=\"").append(W).append("\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>");
        String s = t.toString();
        int len = t.length();
        Object[] al = t.getSpans(0, len, AlignmentSpan.class);
        Object[] js = t.getSpans(0, len, JustifySpan.class);
        Object[] ls = t.getSpans(0, len, LineSpacingSpan.class);
        Object[] ids = t.getSpans(0, len, IndentSpan.class);
        Object[] pbs = t.getSpans(0, len, PageBreakSpan.class);
        int ps = 0;
        while (true) {
            int pe = s.indexOf('\n', ps);
            boolean last = pe < 0;
            if (last) pe = s.length();
            x.append("<w:p>");
            String jc = null;
            AlignmentSpan a = (AlignmentSpan) at(t, al, ps, pe);
            if (a != null) {
                Layout.Alignment k = a.getAlignment();
                if (k == Layout.Alignment.ALIGN_CENTER) jc = "center";
                else if (k == Layout.Alignment.ALIGN_OPPOSITE) jc = "right";
            }
            if (jc == null && at(t, js, ps, pe) != null) jc = "both";
            LineSpacingSpan l = (LineSpacingSpan) at(t, ls, ps, pe);
            IndentSpan ind = (IndentSpan) at(t, ids, ps, pe);
            boolean pb = at(t, pbs, ps, pe) != null;
            if (jc != null || l != null || ind != null || pb) {
                x.append("<w:pPr>");
                if (pb) x.append("<w:pageBreakBefore/>");
                if (l != null) x.append("<w:spacing w:before=\"").append(l.before).append("\" w:after=\"").append(l.after)
                    .append("\" w:line=\"").append(l.line).append("\" w:lineRule=\"auto\"/>");
                if (ind != null) {
                    x.append("<w:ind w:left=\"").append(ind.left).append("\"");
                    if (ind.first > 0) x.append(" w:firstLine=\"").append(ind.first).append("\"");
                    else if (ind.first < 0) x.append(" w:hanging=\"").append(-ind.first).append("\"");
                    x.append("/>");
                }
                if (jc != null) x.append("<w:jc w:val=\"").append(jc).append("\"/>");
                x.append("</w:pPr>");
            }
            int i = ps;
            while (i < pe) {
                int n = t.nextSpanTransition(i, pe, CharacterStyle.class);
                if (n <= i) n = pe;
                run(x, t, i, n);
                i = n;
            }
            x.append("</w:p>");
            if (last) break;
            ps = pe + 1;
        }
        x.append("<w:sectPr>");
        if (pageNum > 0) x.append("<w:footerReference w:type=\"default\" r:id=\"rId2\"/>");
        x.append("<w:pgSz w:w=\"").append(pgW).append("\" w:h=\"").append(pgH).append("\"");
        if (pgW > pgH) x.append(" w:orient=\"landscape\"");
        x.append("/><w:pgMar w:top=\"").append(mTop).append("\" w:right=\"").append(mRight).append("\" w:bottom=\"").append(mBottom)
            .append("\" w:left=\"").append(mLeft).append("\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>");
        x.append("</w:sectPr></w:body></w:document>");
        return x.toString();
    }

    private static String hex(int c) { return String.format("%06X", c & 0xFFFFFF); }

    private static void run(StringBuilder x, Spanned t, int a, int b) {
        FontSpan fs = null;
        SizeSpan sz = null;
        ForegroundColorSpan fg = null;
        BackgroundColorSpan bg = null;
        boolean un = false, st = false, sup = false, sub = false;
        for (Object o : t.getSpans(a, b, Object.class)) {
            if (o instanceof FontSpan) fs = (FontSpan) o;
            else if (o instanceof SizeSpan) sz = (SizeSpan) o;
            else if (o instanceof ForegroundColorSpan) fg = (ForegroundColorSpan) o;
            else if (o instanceof BackgroundColorSpan) bg = (BackgroundColorSpan) o;
            else if (o instanceof UnderlineSpan) un = true;
            else if (o instanceof StrikethroughSpan) st = true;
            else if (o instanceof SuperscriptSpan) sup = true;
            else if (o instanceof SubscriptSpan) sub = true;
        }
        x.append("<w:r>");
        boolean hasSz = sz != null && sz.hp != defHp;
        if (fs != null || hasSz || fg != null || bg != null || un || st || sup || sub) {
            x.append("<w:rPr>");
            if (fs != null) {
                String n = esc(fs.name);
                x.append("<w:rFonts w:ascii=\"").append(n).append("\" w:hAnsi=\"").append(n).append("\" w:cs=\"").append(n).append("\"/>");
                if (fs.bold) x.append("<w:b/>");
                if (fs.italic) x.append("<w:i/>");
            }
            if (st) x.append("<w:strike/>");
            if (fg != null) x.append("<w:color w:val=\"").append(hex(fg.getForegroundColor())).append("\"/>");
            if (hasSz) x.append("<w:sz w:val=\"").append(sz.hp).append("\"/>");
            if (un) x.append("<w:u w:val=\"single\"/>");
            if (bg != null) x.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(hex(bg.getBackgroundColor())).append("\"/>");
            if (sup) x.append("<w:vertAlign w:val=\"superscript\"/>");
            else if (sub) x.append("<w:vertAlign w:val=\"subscript\"/>");
            x.append("</w:rPr>");
        }
        String s = t.subSequence(a, b).toString();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t') { flush(x, buf); x.append("<w:tab/>"); }
            else if (c == '&') buf.append("&amp;");
            else if (c == '<') buf.append("&lt;");
            else if (c == '>') buf.append("&gt;");
            else if (c >= 0x20) buf.append(c);
        }
        flush(x, buf);
        x.append("</w:r>");
    }

    private static void flush(StringBuilder x, StringBuilder buf) {
        if (buf.length() > 0) {
            x.append("<w:t xml:space=\"preserve\">").append(buf).append("</w:t>");
            buf.setLength(0);
        }
    }

    // ---------- BACA ----------
    static SpannableStringBuilder read(File f) throws Exception {
        ZipFile zf = new ZipFile(f);
        try {
            ZipEntry de = zf.getEntry("word/document.xml");
            if (de == null) throw new IOException("Bukan file .docx yang valid");
            resetDefaults();
            styles.clear();
            numMap.clear();
            absMap.clear();
            docDef = new Fmt();
            defPara = "Normal";
            ZipEntry se = zf.getEntry("word/styles.xml");
            if (se != null) {
                InputStream si = zf.getInputStream(se);
                try { parseStyles(si); } catch (Exception ignore) { } finally { si.close(); }
            }
            finishDefaults();
            ZipEntry ne = zf.getEntry("word/numbering.xml");
            if (ne != null) {
                InputStream ni = zf.getInputStream(ne);
                try { parseNumbering(ni); } catch (Exception ignore) { } finally { ni.close(); }
            }
            InputStream in = zf.getInputStream(de);
            SpannableStringBuilder out;
            try { out = parseDoc(in); } finally { in.close(); }
            try { scanFooters(zf); } catch (Exception ignore) { }
            return out;
        } finally {
            zf.close();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        } finally {
            in.close();
        }
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static void scanFooters(ZipFile zf) throws IOException {
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String n = e.getName();
            if (!(n.startsWith("word/footer") && n.endsWith(".xml"))) continue;
            String x = readAll(zf.getInputStream(e));
            if (x.contains("PAGE") && pageNum == 0) {
                Matcher m = Pattern.compile("<w:jc w:val=\"(\\w+)\"").matcher(x);
                String jc = m.find() ? m.group(1) : "left";
                pageNum = jc.equals("center") ? 1 : (jc.equals("right") || jc.equals("end")) ? 2 : 3;
            }
            String rest = x.replaceAll("(?s)<w:instrText[^>]*>.*?</w:instrText>", "").replaceAll("(?s)<w:fldSimple[^>]*>.*?</w:fldSimple>", "")
                .replaceAll("<[^>]+>", "").trim();
            if (Pattern.compile("\\p{L}").matcher(rest).find()) lost.add("header/footer");
        }
    }

    /** Cadangan: ambil teks saja jika pembacaan lengkap gagal. */
    static SpannableStringBuilder plain(File f) throws Exception {
        ZipFile zf = new ZipFile(f);
        try {
            ZipEntry de = zf.getEntry("word/document.xml");
            if (de == null) throw new IOException("Bukan file .docx yang valid");
            String x = readAll(zf.getInputStream(de));
            x = x.replaceAll("(?s)<w:instrText[^>]*>.*?</w:instrText>", "").replaceAll("(?s)<w:delText[^>]*>.*?</w:delText>", "");
            x = x.replace("<w:tab/>", "\t").replace("</w:p>", "\n").replaceAll("<[^>]+>", "");
            x = x.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
            if (x.endsWith("\n")) x = x.substring(0, x.length() - 1);
            return new SpannableStringBuilder(x);
        } finally {
            zf.close();
        }
    }

    private static boolean on(String v) {
        return v == null || !(v.equals("0") || v.equals("false") || v.equals("none"));
    }

    private static int toInt(String v) {
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return U; }
    }

    private static int hl(String v) {
        if (v == null) return 0;
        if (v.equals("yellow")) return 0xFFFFFF00;
        if (v.equals("green")) return 0xFF00FF00;
        if (v.equals("cyan")) return 0xFF00FFFF;
        if (v.equals("magenta")) return 0xFFFF00FF;
        if (v.equals("blue")) return 0xFF0000FF;
        if (v.equals("red")) return 0xFFFF0000;
        if (v.equals("lightGray")) return 0xFFC0C0C0;
        if (v.equals("darkGray")) return 0xFF808080;
        return 0;
    }

    private static int rgb(String v) {
        if (v == null || v.length() != 6) return 0;
        try { return 0xFF000000 | Integer.parseInt(v, 16); } catch (Exception e) { return 0; }
    }

    /** ctx: 1 = di dalam pPr, 2 = di dalam rPr. */
    private static void prop(XmlPullParser p, String n, Fmt f, int ctx) {
        String v = p.getAttributeValue(null, "w:val");
        if (ctx == 2) {
            if (n.equals("w:rFonts")) {
                String a = p.getAttributeValue(null, "w:ascii");
                if (a == null) a = p.getAttributeValue(null, "w:hAnsi");
                if (a != null) f.font = a;
            }
            else if (n.equals("w:b")) f.b = on(v) ? 1 : 0;
            else if (n.equals("w:i")) f.i = on(v) ? 1 : 0;
            else if (n.equals("w:u")) f.u = on(v) ? 1 : 0;
            else if (n.equals("w:strike")) f.st = on(v) ? 1 : 0;
            else if (n.equals("w:color")) f.col = rgb(v);
            else if (n.equals("w:highlight")) f.shd = hl(v);
            else if (n.equals("w:shd")) f.shd = rgb(p.getAttributeValue(null, "w:fill"));
            else if (n.equals("w:sz")) { int h = toInt(v); if (h != U) f.hp = h; }
            else if (n.equals("w:vertAlign")) f.va = "superscript".equals(v) ? 1 : "subscript".equals(v) ? 2 : 0;
            else if (n.equals("w:rStyle")) f.rstyle = v;
        } else if (ctx == 1) {
            if (n.equals("w:jc")) f.jc = v;
            else if (n.equals("w:spacing")) {
                String l = p.getAttributeValue(null, "w:line");
                String r = p.getAttributeValue(null, "w:lineRule");
                if (l != null && (r == null || r.equals("auto"))) f.line = toInt(l);
                String bf = p.getAttributeValue(null, "w:before");
                if (bf != null) f.before = toInt(bf);
                String af = p.getAttributeValue(null, "w:after");
                if (af != null) f.after = toInt(af);
            }
            else if (n.equals("w:ind")) {
                String x = p.getAttributeValue(null, "w:left");
                if (x == null) x = p.getAttributeValue(null, "w:start");
                if (x != null) f.ind = toInt(x);
                String fl = p.getAttributeValue(null, "w:firstLine");
                if (fl != null) { int q = toInt(fl); if (q != U) f.first = q; }
                String hg = p.getAttributeValue(null, "w:hanging");
                if (hg != null) { int q = toInt(hg); if (q != U) f.first = -q; }
            }
            else if (n.equals("w:pageBreakBefore")) f.pbb = on(v) ? 1 : 0;
            else if (n.equals("w:pStyle")) f.pstyle = v;
            else if (n.equals("w:ilvl")) f.ilvl = toInt(v);
            else if (n.equals("w:numId")) f.numId = toInt(v);
        }
    }

    private static void parseStyles(InputStream in) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(in, "UTF-8");
        Style cur = null;
        boolean inPPr = false, inRPr = false, inDefs = false;
        int skip = 0;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String n = p.getName();
                if (skip > 0) { if (n.equals("w:tblStylePr")) skip++; continue; }
                if (n.equals("w:tblStylePr")) { skip = 1; continue; }
                if (n.equals("w:docDefaults")) inDefs = true;
                else if (n.equals("w:style")) {
                    String id = p.getAttributeValue(null, "w:styleId");
                    String type = p.getAttributeValue(null, "w:type");
                    inPPr = false;
                    inRPr = false;
                    if (id != null && ("paragraph".equals(type) || "character".equals(type))) {
                        cur = new Style();
                        styles.put(id, cur);
                        if ("paragraph".equals(type) && "1".equals(p.getAttributeValue(null, "w:default"))) defPara = id;
                    } else cur = null;
                }
                else if (n.equals("w:pPr")) inPPr = true;
                else if (n.equals("w:rPr")) inRPr = true;
                else if (cur != null && n.equals("w:basedOn")) cur.based = p.getAttributeValue(null, "w:val");
                else if (inDefs || cur != null) prop(p, n, inDefs ? docDef : cur.f, inPPr ? 1 : inRPr ? 2 : 0);
            } else if (ev == XmlPullParser.END_TAG) {
                String n = p.getName();
                if (skip > 0) { if (n.equals("w:tblStylePr")) skip--; continue; }
                if (n.equals("w:docDefaults")) inDefs = false;
                else if (n.equals("w:style")) cur = null;
                else if (n.equals("w:pPr")) inPPr = false;
                else if (n.equals("w:rPr")) inRPr = false;
            }
        }
    }

    private static void parseNumbering(InputStream in) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(in, "UTF-8");
        Lvl[] cur = null;
        Lvl lv = null;
        int curNum = -1;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String n = p.getName();
                String v = p.getAttributeValue(null, "w:val");
                if (n.equals("w:abstractNum")) {
                    int id = toInt(p.getAttributeValue(null, "w:abstractNumId"));
                    cur = new Lvl[9];
                    absMap.put(id, cur);
                } else if (n.equals("w:lvl")) {
                    int il = toInt(p.getAttributeValue(null, "w:ilvl"));
                    lv = new Lvl();
                    if (cur != null && il >= 0 && il < 9) cur[il] = lv;
                } else if (lv != null && n.equals("w:start")) { int s = toInt(v); if (s != U) lv.start = s; }
                else if (lv != null && n.equals("w:numFmt") && v != null) lv.fmt = v;
                else if (lv != null && n.equals("w:lvlText") && v != null) lv.text = v;
                else if (lv != null && n.equals("w:ind")) {
                    String x = p.getAttributeValue(null, "w:left");
                    if (x == null) x = p.getAttributeValue(null, "w:start");
                    if (x != null) lv.left = toInt(x);
                    String hg = p.getAttributeValue(null, "w:hanging");
                    if (hg != null) lv.hang = toInt(hg);
                } else if (n.equals("w:num")) curNum = toInt(p.getAttributeValue(null, "w:numId"));
                else if (n.equals("w:abstractNumId") && curNum >= 0) numMap.put(curNum, toInt(v));
            } else if (ev == XmlPullParser.END_TAG) {
                String n = p.getName();
                if (n.equals("w:lvl")) lv = null;
                else if (n.equals("w:abstractNum")) cur = null;
                else if (n.equals("w:num")) curNum = -1;
            }
        }
    }

    private static String roman(int n) {
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length; i++) while (n >= v[i]) { b.append(s[i]); n -= v[i]; }
        return b.toString();
    }

    private static String fmtNum(int n, String f) {
        if (n < 1) n = 1;
        if ("lowerLetter".equals(f)) return String.valueOf((char) ('a' + (n - 1) % 26));
        if ("upperLetter".equals(f)) return String.valueOf((char) ('A' + (n - 1) % 26));
        if ("lowerRoman".equals(f)) return roman(n).toLowerCase();
        if ("upperRoman".equals(f)) return roman(n);
        if ("decimalZero".equals(f)) return n < 10 ? "0" + n : String.valueOf(n);
        return String.valueOf(n);
    }

    /** Menulis nomor/butir sebagai teks di awal paragraf. Mengembalikan indentasi kiri dari daftar (atau U). */
    private static int numbering(SpannableStringBuilder sb, Fmt pdir, Fmt pbase, HashMap<Integer, int[]> counters) {
        int numId = pdir.numId != U ? pdir.numId : pbase.numId;
        int lvl = pdir.ilvl != U ? pdir.ilvl : (pbase.ilvl != U ? pbase.ilvl : 0);
        numFirst = U;
        if (numId == U || numId <= 0 || lvl < 0 || lvl > 8) return U;
        Integer abs = numMap.get(numId);
        Lvl[] lv = abs == null ? null : absMap.get(abs);
        if (lv == null || lv[lvl] == null) return U;
        conv.add("daftar bernomor/berbutir (diubah menjadi teks biasa)");
        int[] c = counters.get(numId);
        if (c == null) {
            c = new int[9];
            for (int k = 0; k < 9; k++) c[k] = (lv[k] != null ? lv[k].start : 1) - 1;
            counters.put(numId, c);
        }
        c[lvl]++;
        for (int k = lvl + 1; k < 9; k++) c[k] = (lv[k] != null ? lv[k].start : 1) - 1;
        Lvl L = lv[lvl];
        String pre;
        if ("bullet".equals(L.fmt)) pre = "\u2022";
        else {
            pre = L.text;
            for (int k = 0; k < 9; k++) pre = pre.replace("%" + (k + 1), fmtNum(c[k], lv[k] != null ? lv[k].fmt : "decimal"));
        }
        sb.append(pre).append(' ');
        if (L.hang != U) numFirst = -L.hang;
        return L.left;
    }

    private static void paraSpans(SpannableStringBuilder sb, int ps, Fmt pdir, Fmt pbase, int numLeft) {
        Fmt e = new Fmt();
        e.merge(pbase);
        e.merge(pdir);
        int end = sb.length();
        try {
            String jc = e.jc;
            if ("center".equals(jc)) sb.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), ps, end, PAR);
            else if ("right".equals(jc) || "end".equals(jc)) sb.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), ps, end, PAR);
            else if ("both".equals(jc) || "distribute".equals(jc)) sb.setSpan(new JustifySpan(), ps, end, PAR);
            int line = e.line != U ? e.line : defLine;
            int bef = e.before != U ? e.before : defBefore;
            int aft = e.after != U ? e.after : defAfter;
            if (line != defLine || bef != defBefore || aft != defAfter) sb.setSpan(new LineSpacingSpan(line, bef, aft), ps, end, PAR);
            int left = pdir.ind != U ? pdir.ind : (numLeft != U ? numLeft : (e.ind != U ? e.ind : 0));
            int first = pdir.first != U ? pdir.first : (numFirst != U ? numFirst : (e.first != U ? e.first : 0));
            if (left > 0 || first != 0) sb.setSpan(new IndentSpan(left, first), ps, end, PAR);
            if (e.pbb == 1) sb.setSpan(new PageBreakSpan(), ps, end, PAR);
        } catch (RuntimeException ignore) { }
    }

    private static boolean eq(String x, String y) { return x == null ? y == null : x.equals(y); }

    private static boolean same(Fmt a, Fmt b) {
        return eq(a.font, b.font) && a.b == b.b && a.i == b.i && a.hp == b.hp && a.col == b.col
            && a.shd == b.shd && a.u == b.u && a.st == b.st && a.va == b.va;
    }

    private static SpannableStringBuilder parseDoc(InputStream in) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(in, "UTF-8");
        SpannableStringBuilder sb = new SpannableStringBuilder();
        HashMap<Integer, int[]> counters = new HashMap<Integer, int[]>();
        int ps = 0, tbl = 0, numLeft = U, openS = 0;
        boolean inPPr = false, inRPr = false, sawP = false, pendBreak = false;
        Fmt pdir = new Fmt(), rdir = new Fmt(), pbase = null, openF = null;
        numFirst = U;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String n = p.getName();
                if (n.equals("w:drawing") || n.equals("w:pict") || n.equals("w:object")) lost.add("gambar/objek");
                else if (n.equals("w:footnoteReference") || n.equals("w:endnoteReference")) lost.add("catatan kaki");
                else if (n.equals("w:headerReference")) lost.add("header/footer");
                else if (n.equals("w:pgSz")) {
                    int a = toInt(p.getAttributeValue(null, "w:w")), b = toInt(p.getAttributeValue(null, "w:h"));
                    if (a != U && b != U && a > 1000 && b > 1000) { pgW = a; pgH = b; }
                }
                else if (n.equals("w:pgMar")) {
                    int t = toInt(p.getAttributeValue(null, "w:top")), bt = toInt(p.getAttributeValue(null, "w:bottom"));
                    int l = toInt(p.getAttributeValue(null, "w:left")), r = toInt(p.getAttributeValue(null, "w:right"));
                    if (t != U) mTop = Math.abs(t);
                    if (bt != U) mBottom = Math.abs(bt);
                    if (l != U) mLeft = Math.abs(l);
                    if (r != U) mRight = Math.abs(r);
                }
                if (n.equals("w:tbl")) { tbl++; lost.add("tabel (hanya teksnya yang tampil)"); }
                else if (n.equals("w:p")) {
                    ps = sb.length(); pdir = new Fmt(); pbase = null; numLeft = U; numFirst = U; sawP = true;
                    if (pendBreak) { pdir.pbb = 1; pendBreak = false; }
                }
                else if (n.equals("w:pPr")) inPPr = true;
                else if (n.equals("w:r")) { if (pbase == null) pbase = base(pdir); rdir = new Fmt(); }
                else if (n.equals("w:rPr")) inRPr = true;
                else if (inPPr && !inRPr) prop(p, n, pdir, 1);
                else if (inRPr && !inPPr) prop(p, n, rdir, 2);
                else if (n.equals("w:tab")) sb.append('\t');
                else if (n.equals("w:br")) {
                    if ("page".equals(p.getAttributeValue(null, "w:type"))) pendBreak = true; else sb.append('\n');
                }
                else if (n.equals("w:cr")) sb.append('\n');
                else if (n.equals("w:noBreakHyphen")) sb.append('-');
                else if (n.equals("w:t")) {
                    String txt = p.nextText();
                    if (txt.length() == 0) continue;
                    if (pbase == null) pbase = base(pdir);
                    Fmt eff = new Fmt();
                    eff.merge(pbase);
                    eff.merge(resolve(rdir.rstyle));
                    eff.merge(rdir);
                    if (openF != null && same(openF, eff)) {
                        sb.append(txt);
                    } else {
                        if (openF != null) applyRun(sb, openS, sb.length(), openF);
                        openF = eff;
                        openS = sb.length();
                        sb.append(txt);
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                String n = p.getName();
                if (n.equals("w:pPr")) {
                    inPPr = false;
                    pbase = base(pdir);
                    numLeft = numbering(sb, pdir, pbase, counters);
                }
                else if (n.equals("w:rPr")) inRPr = false;
                else if (n.equals("w:tbl")) tbl = Math.max(0, tbl - 1);
                else if (n.equals("w:tr")) {
                    if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\t') sb.replace(sb.length() - 1, sb.length(), "\n");
                    else sb.append('\n');
                }
                else if (n.equals("w:p")) {
                    if (openF != null) { applyRun(sb, openS, sb.length(), openF); openF = null; }
                    if (pbase == null) pbase = base(pdir);
                    if (tbl > 0) sb.append('\t');
                    else { sb.append('\n'); paraSpans(sb, ps, pdir, pbase, numLeft); }
                }
            }
        }
        if (openF != null) applyRun(sb, openS, sb.length(), openF);
        if (sawP && sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') sb.delete(sb.length() - 1, sb.length());
        return sb;
    }

    private static void applyRun(SpannableStringBuilder sb, int s, int e, Fmt f) {
        if (e <= s) return;
        String fn = f.font != null ? f.font : defName;
        boolean bo = f.b == 1, it = f.i == 1;
        if (!fn.equals(defName) || bo || it) sb.setSpan(new FontSpan(fn, bo, it), s, e, INC);
        int hp = f.hp != U ? f.hp : defHp;
        if (hp > 0 && hp != defHp) sb.setSpan(new SizeSpan(hp), s, e, INC);
        if (f.col != U && f.col != 0) sb.setSpan(new ForegroundColorSpan(f.col), s, e, INC);
        if (f.shd != U && f.shd != 0) sb.setSpan(new BackgroundColorSpan(f.shd), s, e, INC);
        if (f.u == 1) sb.setSpan(new UnderlineSpan(), s, e, INC);
        if (f.st == 1) sb.setSpan(new StrikethroughSpan(), s, e, INC);
        if (f.va == 1) { sb.setSpan(new SuperscriptSpan(), s, e, INC); sb.setSpan(new RelativeSizeSpan(0.65f), s, e, INC); }
        else if (f.va == 2) { sb.setSpan(new SubscriptSpan(), s, e, INC); sb.setSpan(new RelativeSizeSpan(0.65f), s, e, INC); }
    }
}
