package com.taller.otalmacen

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Escribe un .xlsx mínimo sin librerías externas. */
object XlsxWriter {
    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    private fun str(ref: String, texto: String, estilo: Int) =
        "<c r=\"$ref\" s=\"$estilo\" t=\"inlineStr\"><is><t>${esc(texto)}</t></is></c>"

    private fun num(ref: String, n: Double, estilo: Int) = "<c r=\"$ref\" s=\"$estilo\"><v>$n</v></c>"

    private fun vacia(ref: String, estilo: Int) = "<c r=\"$ref\" s=\"$estilo\"/>"

    fun escribir(o: Orden, destino: File) {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"$NS\">")
        sb.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"11\" topLeftCell=\"A12\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
        sb.append("<cols>")
        val anchos = listOf(6, 8, 55, 26, 15, 15, 13, 8, 40)
        anchos.forEachIndexed { i, w ->
            sb.append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>")
        }
        sb.append("</cols><sheetData>")

        val campos = listOf(
            "SEGURO:" to o.seguro, "ELABORACIÓN:" to o.elaboracion, "ENTREGA:" to o.entrega,
            "OT:" to o.ot, "UNIDAD:" to o.unidad, "VIN:" to o.vin,
            "MODELO:" to o.modelo, "TALLER:" to o.taller, "JEFE DE TALLER:" to o.jefeTaller
        )
        campos.forEachIndexed { i, (k, v) ->
            val r = i + 1
            sb.append("<row r=\"$r\">").append(str("A$r", k, 5)).append(str("B$r", v, 0)).append("</row>")
        }

        val titulos = listOf("No.", "PZ.", "CONCEPTO", "PROVEEDOR", "ALTA ALMACÉN", "BAJA ALMACÉN", "EXISTENCIA", "OK", "OBSERVACIONES")
        sb.append("<row r=\"11\">")
        titulos.forEachIndexed { i, t -> sb.append(str("${'A' + i}11", t, 1)) }
        sb.append("</row>")

        o.items.forEachIndexed { idx, p ->
            val r = 12 + idx
            val e = when {
                p.alta -> 3
                p.baja -> 4
                p.prestamo -> 6
                p.devolucion -> 7
                p.sobrante -> 8
                else -> 2
            }
            val n = p.pz.toDoubleOrNull()
            sb.append("<row r=\"$r\">")
            sb.append(if (p.num > 0) num("A$r", p.num.toDouble(), e) else vacia("A$r", e))
            sb.append(if (n != null) num("B$r", n, e) else str("B$r", p.pz, e))
            sb.append(str("C$r", p.concepto, e))
            sb.append(if (p.proveedor.isBlank()) vacia("D$r", e) else str("D$r", p.proveedor, e))
            sb.append(if (p.alta) num("E$r", n ?: 1.0, e) else vacia("E$r", e))
            sb.append(if (p.baja) num("F$r", n ?: 1.0, e) else vacia("F$r", e))
            sb.append("<c r=\"G$r\" s=\"$e\"><f>E$r-F$r</f></c>")
            sb.append(vacia("H$r", e))
            val obs = when {
                p.prestamo -> "Préstamo" + if (p.descPrestamo.isBlank()) "" else ": " + p.descPrestamo.trim()
                p.devolucion -> "Devolución" + if (p.descDevolucion.isBlank()) "" else ": " + p.descDevolucion.trim()
                p.sobrante -> "Sobrante"
                else -> ""
            }
            sb.append(if (obs.isBlank()) vacia("I$r", e) else str("I$r", obs, e))
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")

        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="$NS">
<fonts count="3">
<font><sz val="10"/><name val="Arial"/></font>
<font><b/><sz val="10"/><color rgb="FFFFFFFF"/><name val="Arial"/></font>
<font><b/><sz val="10"/><name val="Arial"/></font>
</fonts>
<fills count="8">
<fill><patternFill patternType="none"/></fill>
<fill><patternFill patternType="gray125"/></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FF4F6F52"/><bgColor indexed="64"/></patternFill></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FFA5D6A7"/><bgColor indexed="64"/></patternFill></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FFEF9A9A"/><bgColor indexed="64"/></patternFill></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FFFFF176"/><bgColor indexed="64"/></patternFill></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FF90CAF9"/><bgColor indexed="64"/></patternFill></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FFA1887F"/><bgColor indexed="64"/></patternFill></fill>
</fills>
<borders count="2">
<border><left/><right/><top/><bottom/><diagonal/></border>
<border><left style="thin"><color rgb="FF999999"/></left><right style="thin"><color rgb="FF999999"/></right><top style="thin"><color rgb="FF999999"/></top><bottom style="thin"><color rgb="FF999999"/></bottom><diagonal/></border>
</borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="9">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"/>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"/>
<xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFill="1" applyBorder="1"/>
<xf numFmtId="0" fontId="0" fillId="4" borderId="1" xfId="0" applyFill="1" applyBorder="1"/>
<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>
<xf numFmtId="0" fontId="0" fillId="5" borderId="1" xfId="0" applyFill="1" applyBorder="1"/>
<xf numFmtId="0" fontId="0" fillId="6" borderId="1" xfId="0" applyFill="1" applyBorder="1"/>
<xf numFmtId="0" fontId="0" fillId="7" borderId="1" xfId="0" applyFill="1" applyBorder="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""

        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

        val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

        val workbook = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="$NS" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="Orden" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""

        val wbRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

        ZipOutputStream(destino.outputStream()).use { z ->
            fun add(nombre: String, contenido: String) {
                z.putNextEntry(ZipEntry(nombre))
                z.write(contenido.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            add("[Content_Types].xml", contentTypes)
            add("_rels/.rels", rootRels)
            add("xl/workbook.xml", workbook)
            add("xl/_rels/workbook.xml.rels", wbRels)
            add("xl/styles.xml", styles)
            add("xl/worksheets/sheet1.xml", sb.toString())
        }
    }
}
