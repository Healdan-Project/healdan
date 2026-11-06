// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;

import com.logicaldoc.core.parser.Parser;

/**
 * Parses Office files with the parsers the server uses for them, on the WAR's own libraries. Maven's test classpath
 * has every library's dependencies, but WEB-INF/lib has only what the plugin zips ship, so a dependency missing from
 * every zip fails here and nowhere else before the server (Healdan-Project/healdan#202: POI's SparseBitSet).
 * The OOXML fixtures are written part by part, as POI can't make a presentation without the jar under test.
 */
public class WarParsersTest {

    private static final String POWERPOINT = "com.logicaldoc.healdan.parser.PowerPointParser"; // pptx, ppsx

    private static final String EXCEL = "com.logicaldoc.healdan.parser.ExcelParser"; // xlsx, xlsm

    private static final String CE_EXCEL = "com.logicaldoc.core.parser.XLSParser"; // xls, xlt

    /** Excel's Accounting format, in four parts, which POI formats with CellNumberFormatter rather than Java's. */
    private static final String ACCOUNTING = "_-\"£\"* #,##0.00_-;-\"£\"* #,##0.00_-;_-\"£\"* \"-\"??_-;_-@_-";

    public static void main(String[] args) throws Exception {
        parses(POWERPOINT, "slides.pptx", pptx("zqxwarslide"), "zqxwarslide");
        parses(EXCEL, "accounts.xlsx", xlsx("zqxwarcell", 1234.5), "zqxwarcell", "1,234.50");
        parses(CE_EXCEL, "legacy.xls", xls("zqxwarlegacy"), "zqxwarlegacy");
        Check.done("WarParsersTest");
    }

    private static void parses(String parserClass, String fileName, byte[] file, String... expected) {
        String text;
        try (InputStream in = new ByteArrayInputStream(file)) {
            Parser parser = (Parser) Class.forName(parserClass).getDeclaredConstructor().newInstance();
            text = parser.parse(in, fileName, "UTF-8", Locale.ENGLISH, "default");
        } catch (Throwable e) {
            Check.fail(fileName + " with " + parserClass + ": " + rootCause(e));
            return;
        }
        for (String fragment : expected)
            Check.isTrue(text.contains(fragment), fileName + ": text has \"" + fragment + "\": <" + text + ">");
    }

    /** The parsers report a failure in their parsing thread only as a message, which names the original error. */
    private static String rootCause(Throwable e) {
        while (e.getCause() != null && e.getCause() != e)
            e = e.getCause();
        return e.toString();
    }

    /** One slide holding {@code text}, with the layout, master and theme every presentation has. */
    static byte[] pptx(String text) throws IOException {
        String p = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                + "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"";
        String ml = "application/vnd.openxmlformats-officedocument.presentationml.";
        String emptyTree = "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/>"
                + "</p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld>";
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", types(
                override("/ppt/presentation.xml", ml + "presentation.main+xml"),
                override("/ppt/slides/slide1.xml", ml + "slide+xml"),
                override("/ppt/slideLayouts/slideLayout1.xml", ml + "slideLayout+xml"),
                override("/ppt/slideMasters/slideMaster1.xml", ml + "slideMaster+xml"),
                override("/ppt/theme/theme1.xml", "application/vnd.openxmlformats-officedocument.theme+xml")));
        parts.put("_rels/.rels", rels(rel("officeDocument", "ppt/presentation.xml")));
        parts.put("ppt/presentation.xml", xml("<p:presentation " + p + ">"
                + "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>"
                + "<p:sldIdLst><p:sldId id=\"256\" r:id=\"rId2\"/></p:sldIdLst>"
                + "<p:sldSz cx=\"9144000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/>"
                + "</p:presentation>"));
        parts.put("ppt/_rels/presentation.xml.rels", rels(rel("slideMaster", "slideMasters/slideMaster1.xml"),
                rel("slide", "slides/slide1.xml"), rel("theme", "theme/theme1.xml")));
        parts.put("ppt/slides/slide1.xml", xml("<p:sld " + p + "><p:cSld><p:spTree>"
                + "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>"
                + "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Text\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>"
                + "<p:spPr/><p:txBody><a:bodyPr/><a:p><a:r><a:t>" + text + "</a:t></a:r></a:p></p:txBody></p:sp>"
                + "</p:spTree></p:cSld></p:sld>"));
        parts.put("ppt/slides/_rels/slide1.xml.rels", rels(rel("slideLayout", "../slideLayouts/slideLayout1.xml")));
        parts.put("ppt/slideLayouts/slideLayout1.xml", xml("<p:sldLayout " + p + ">" + emptyTree + "</p:sldLayout>"));
        parts.put("ppt/slideLayouts/_rels/slideLayout1.xml.rels",
                rels(rel("slideMaster", "../slideMasters/slideMaster1.xml")));
        parts.put("ppt/slideMasters/slideMaster1.xml", xml("<p:sldMaster " + p + ">" + emptyTree
                + "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\""
                + " accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\""
                + " folHlink=\"folHlink\"/>"
                + "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>"
                + "</p:sldMaster>"));
        parts.put("ppt/slideMasters/_rels/slideMaster1.xml.rels",
                rels(rel("slideLayout", "../slideLayouts/slideLayout1.xml"), rel("theme", "../theme/theme1.xml")));
        parts.put("ppt/theme/theme1.xml", xml("<a:theme " + p + " name=\"Fixture\"><a:themeElements>"
                + "<a:clrScheme name=\"Fixture\"><a:dk1><a:srgbClr val=\"000000\"/></a:dk1>"
                + "<a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1></a:clrScheme>"
                + "<a:fontScheme name=\"Fixture\"><a:majorFont><a:latin typeface=\"Arial\"/></a:majorFont>"
                + "<a:minorFont><a:latin typeface=\"Arial\"/></a:minorFont></a:fontScheme>"
                + "</a:themeElements></a:theme>"));
        return zip(parts);
    }

    /** One sheet: {@code text} in A1, and {@code amount} in B1 in the Accounting format. */
    static byte[] xlsx(String text, double amount) throws IOException {
        String s = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"";
        String ml = "application/vnd.openxmlformats-officedocument.spreadsheetml.";
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", types(override("/xl/workbook.xml", ml + "sheet.main+xml"),
                override("/xl/worksheets/sheet1.xml", ml + "worksheet+xml"),
                override("/xl/styles.xml", ml + "styles+xml")));
        parts.put("_rels/.rels", rels(rel("officeDocument", "xl/workbook.xml")));
        parts.put("xl/workbook.xml", xml("<workbook " + s
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"Accounts\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"));
        parts.put("xl/_rels/workbook.xml.rels",
                rels(rel("worksheet", "worksheets/sheet1.xml"), rel("styles", "styles.xml")));
        parts.put("xl/styles.xml", xml("<styleSheet " + s + ">"
                + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"" + escape(ACCOUNTING) + "\"/></numFmts>"
                + "<fonts count=\"1\"><font/></fonts><fills count=\"1\"><fill/></fills><borders count=\"1\"><border/>"
                + "</borders><cellStyleXfs count=\"1\"><xf/></cellStyleXfs>"
                + "<cellXfs count=\"2\"><xf numFmtId=\"0\"/><xf numFmtId=\"164\" applyNumberFormat=\"1\"/></cellXfs>"
                + "</styleSheet>"));
        parts.put("xl/worksheets/sheet1.xml", xml("<worksheet " + s + "><sheetData><row r=\"1\">"
                + "<c r=\"A1\" t=\"inlineStr\"><is><t>" + text + "</t></is></c>"
                + "<c r=\"B1\" s=\"1\"><v>" + amount + "</v></c></row></sheetData></worksheet>"));
        return zip(parts);
    }

    /** One sheet with {@code text} in A1. Writing an .xls this plain doesn't reach SparseBitSet; reading any does. */
    static byte[] xls(String text) throws IOException {
        try (HSSFWorkbook workbook = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("Legacy").createRow(0).createCell(0).setCellValue(text);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static String types(String... overrides) {
        return xml("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\""
                + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" + String.join("", overrides)
                + "</Types>");
    }

    private static String override(String part, String type) {
        return "<Override PartName=\"" + part + "\" ContentType=\"" + type + "\"/>";
    }

    private static String rels(String... relationships) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < relationships.length; i++)
            body.append(relationships[i].replace("Id=\"\"", "Id=\"rId" + (i + 1) + "\""));
        return xml("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + body
                + "</Relationships>");
    }

    private static String rel(String type, String target) {
        return "<Relationship Id=\"\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
                + type + "\" Target=\"" + target + "\"/>";
    }

    private static String xml(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" + body;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    private static byte[] zip(Map<String, String> parts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
