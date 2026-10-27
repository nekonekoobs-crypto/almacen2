package com.taller.otalmacen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipInputStream

/**
 * Captura de una OT desde archivos: Excel (.xlsx), Word (.docx) y PDF.
 * Excel y Word se leen directo (sin OCR); el PDF se convierte en imágenes y se lee con el mismo OCR de las fotos.
 */
object Documentos {

    /** Tipos que muestra el selector de archivos. */
    val TIPOS = arrayOf(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/octet-stream"
    )

    private val FECHA_DOC = Regex("\\d{1,2}\\s*[/\\-.]\\s*(?:\\d{1,2}|[A-ZÁÉÍÓÚ]{3,10})\\s*[/\\-.]\\s*\\d{2,4}")
    private val MARCA_DOC = Regex(
        "\\b(KENWORTH|FREIGHTLINER|VOLVO|INTERNATIONAL|PETERBILT|MACK|SCANIA|MERCEDES|HINO|ISUZU|" +
            "FORD|CHEVROLET|DODGE|NISSAN|TOYOTA|FOTON|JAC|IVECO|DINA|MAN|KW)\\b"
    )
    private val ETIQUETA_DOC = Regex("^(SEGURO|ELABORACI|ENTREGA|MODELO|VIN\\b|TRAZO|UNIDAD|TALLER|JEFE|OT\\b)")

    // ---------- Entrada ----------
    fun leer(ctx: Context, uri: Uri, onDone: (Orden) -> Unit, onError: (String) -> Unit) {
        val nombre = nombreDe(ctx, uri).lowercase()
        val tipo = (ctx.contentResolver.getType(uri) ?: "").lowercase()
        when {
            nombre.endsWith(".pdf") || tipo.contains("pdf") -> leerPdf(ctx, uri, onDone, onError)
            nombre.endsWith(".xlsx") || tipo.contains("spreadsheetml") ->
                enSegundoPlano(onDone, onError) { desdeFilas(filasXlsx(ctx, uri)) }
            nombre.endsWith(".docx") || tipo.contains("wordprocessingml") ->
                enSegundoPlano(onDone, onError) { desdeFilas(filasDocx(ctx, uri)) }
            nombre.endsWith(".xls") || nombre.endsWith(".doc") || tipo.contains("ms-excel") || tipo.contains("msword") ->
                onError("Formato antiguo (.xls / .doc). Guárdalo como .xlsx o .docx e inténtalo otra vez.")
            else -> onError("Tipo de archivo no compatible. Usa Excel (.xlsx), Word (.docx) o PDF.")
        }
    }

    private fun enSegundoPlano(onDone: (Orden) -> Unit, onError: (String) -> Unit, trabajo: () -> Orden) {
        val ui = Handler(Looper.getMainLooper())
        Thread {
            try {
                val o = trabajo()
                ui.post { onDone(o) }
            } catch (e: Throwable) {
                ui.post { onError(e.message ?: "No se pudo leer el archivo") }
            }
        }.start()
    }

    private fun nombreDe(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) return c.getString(i) ?: ""
                }
            }
        } catch (e: Exception) {
            // se usa el nombre de la ruta
        }
        return uri.lastPathSegment ?: ""
    }

    // ---------- PDF ----------
    private fun leerPdf(ctx: Context, uri: Uri, onDone: (Orden) -> Unit, onError: (String) -> Unit) {
        val ui = Handler(Looper.getMainLooper())
        Thread {
            val paginas = mutableListOf<File>()
            try {
                val dir = File(ctx.cacheDir, "pdf")
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                // Se copia a un archivo propio: PdfRenderer necesita un archivo con acceso directo
                val copia = File(dir, "documento.pdf")
                val entrada = ctx.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("No se pudo abrir el PDF")
                entrada.use { i -> copia.outputStream().use { o -> i.copyTo(o) } }

                val pfd = ParcelFileDescriptor.open(copia, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(pfd)
                try {
                    val n = minOf(r.pageCount, 6)
                    for (i in 0 until n) {
                        val pagina = r.openPage(i)
                        try {
                            val escala = 2000f / pagina.width
                            val alto = (pagina.height * escala).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(2000, alto, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            pagina.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val f = File(dir, "pagina$i.jpg")
                            f.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, out) }
                            bmp.recycle()
                            paginas.add(f)
                        } finally {
                            pagina.close()
                        }
                    }
                } finally {
                    r.close()
                    pfd.close()
                }
            } catch (e: Throwable) {
                ui.post { onError("No se pudo abrir el PDF: " + (e.message ?: "archivo dañado o protegido")) }
                return@Thread
            }
            ui.post { leerPaginas(ctx, paginas, 0, null, onDone, onError) }
        }.start()
    }

    private fun leerPaginas(
        ctx: Context,
        paginas: List<File>,
        i: Int,
        acum: Orden?,
        onDone: (Orden) -> Unit,
        onError: (String) -> Unit
    ) {
        if (i >= paginas.size) {
            if (acum == null) onError("El PDF no tiene páginas legibles.") else onDone(acum)
            return
        }
        OcrParser.leer(ctx, Uri.fromFile(paginas[i]),
            { o -> leerPaginas(ctx, paginas, i + 1, if (acum == null) o else unir(acum, o), onDone, onError) },
            { err -> if (acum != null) onDone(acum) else onError(err) })
    }

    /** Une dos páginas: el encabezado solo llena huecos y las piezas pasan por el validador de repetidas. */
    private fun unir(a: Orden, b: Orden): Orden = a.copy(
        seguro = a.seguro.ifBlank { b.seguro },
        elaboracion = a.elaboracion.ifBlank { b.elaboracion },
        entrega = a.entrega.ifBlank { b.entrega },
        ot = a.ot.ifBlank { b.ot },
        unidad = a.unidad.ifBlank { b.unidad },
        vin = a.vin.ifBlank { b.vin },
        modelo = a.modelo.ifBlank { b.modelo },
        taller = a.taller.ifBlank { b.taller },
        items = fusionarItems(a.items, b.items).items
    )

    // ---------- XML (Excel y Word) ----------
    private fun nuevoParser(bytes: ByteArray): XmlPullParser {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(bytes), "UTF-8")
        return p
    }

    /** Nombre del elemento sin el prefijo (w:tbl -> tbl). */
    private fun local(p: XmlPullParser): String = (p.name ?: "").substringAfter(':')

    private fun attr(p: XmlPullParser, nombre: String): String? {
        for (i in 0 until p.attributeCount) {
            if (p.getAttributeName(i).substringAfter(':') == nombre) return p.getAttributeValue(i)
        }
        return null
    }

    private fun leerZip(ctx: Context, uri: Uri, quiere: (String) -> Boolean): Map<String, ByteArray> {
        val res = HashMap<String, ByteArray>()
        val entrada = ctx.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("No se pudo abrir el archivo")
        ZipInputStream(entrada).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (quiere(e.name)) res[e.name] = z.readBytes()
                z.closeEntry()
                e = z.nextEntry
            }
        }
        return res
    }

    // ---------- Excel ----------
    private fun filasXlsx(ctx: Context, uri: Uri): List<List<String>> {
        val hojaRx = Regex("xl/worksheets/sheet\\d+\\.xml")
        val z = leerZip(ctx, uri) { it == "xl/sharedStrings.xml" || hojaRx.matches(it) }
        val hoja = z.keys.filter { hojaRx.matches(it) }
            .minByOrNull { it.substringAfter("sheet").substringBefore(".xml").toIntOrNull() ?: 9999 }
            ?: throw IllegalStateException("El Excel no tiene hojas")
        val compartidas = z["xl/sharedStrings.xml"]?.let { cadenasCompartidas(it) } ?: emptyList()
        return celdasHoja(z.getValue(hoja), compartidas)
    }

    private fun cadenasCompartidas(bytes: ByteArray): List<String> {
        val p = nuevoParser(bytes)
        val lista = mutableListOf<String>()
        var actual: StringBuilder? = null
        var enT = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    when (local(p)) {
                        "si" -> { actual = StringBuilder() }
                        "t" -> { enT = true }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (enT) actual?.append(p.text)
                }
                XmlPullParser.END_TAG -> {
                    when (local(p)) {
                        "t" -> { enT = false }
                        "si" -> {
                            lista.add(actual?.toString() ?: "")
                            actual = null
                        }
                    }
                }
            }
            ev = p.next()
        }
        return lista
    }

    private fun columnaDe(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break
        }
        return maxOf(n - 1, 0)
    }

    private fun numeroLimpio(s: String): String {
        val t = s.trim()
        return if (Regex("^-?\\d+\\.0+$").matches(t)) t.substringBefore('.') else t
    }

    private fun celdasHoja(bytes: ByteArray, comp: List<String>): List<List<String>> {
        val p = nuevoParser(bytes)
        val filas = sortedMapOf<Int, MutableMap<Int, String>>()
        var fila = 0
        var col = 0
        var tipo = ""
        var valor = StringBuilder()
        var enV = false
        var enT = false
        var enCelda = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    when (local(p)) {
                        "row" -> { fila = attr(p, "r")?.toIntOrNull() ?: (fila + 1) }
                        "c" -> {
                            enCelda = true
                            col = columnaDe(attr(p, "r") ?: "")
                            tipo = attr(p, "t") ?: ""
                            valor = StringBuilder()
                        }
                        "v" -> { enV = true }
                        "t" -> { if (enCelda) enT = true }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (enV || enT) valor.append(p.text)
                }
                XmlPullParser.END_TAG -> {
                    when (local(p)) {
                        "v" -> { enV = false }
                        "t" -> { enT = false }
                        "c" -> {
                            enCelda = false
                            var texto = valor.toString()
                            if (tipo == "s") {
                                texto = comp.getOrNull(texto.trim().toIntOrNull() ?: -1) ?: ""
                            } else if (tipo == "e") {
                                texto = ""
                            } else if (tipo == "n" || tipo == "") {
                                texto = numeroLimpio(texto)
                            }
                            if (texto.isNotBlank()) {
                                filas.getOrPut(fila) { mutableMapOf() }[col] = texto
                            }
                        }
                    }
                }
            }
            ev = p.next()
        }
        return filas.values.map { m ->
            val ancho = (m.keys.maxOrNull() ?: -1) + 1
            List(ancho) { c -> m[c] ?: "" }
        }
    }

    // ---------- Word ----------
    /** Devuelve las filas del documento en orden: cada párrafo suelto es una fila de 1 celda y cada fila de tabla sus celdas. */
    private fun filasDocx(ctx: Context, uri: Uri): List<List<String>> {
        val z = leerZip(ctx, uri) { it == "word/document.xml" }
        val bytes = z["word/document.xml"] ?: throw IllegalStateException("El Word no tiene contenido")
        val p = nuevoParser(bytes)
        val filas = mutableListOf<List<String>>()
        var profTabla = 0
        var fila: MutableList<String>? = null
        var celda: StringBuilder? = null
        var parrafo = StringBuilder()
        var span = 1
        var enT = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    when (local(p)) {
                        "tbl" -> { profTabla++ }
                        "tr" -> { if (profTabla == 1) fila = mutableListOf() }
                        "tc" -> {
                            if (profTabla == 1) {
                                celda = StringBuilder()
                                span = 1
                            }
                        }
                        "gridSpan" -> {
                            if (profTabla == 1) span = attr(p, "val")?.toIntOrNull() ?: 1
                        }
                        "p" -> { parrafo = StringBuilder() }
                        "t" -> { enT = true }
                        "tab" -> { parrafo.append(' ') }
                        "br" -> { parrafo.append('\n') }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (enT) parrafo.append(p.text)
                }
                XmlPullParser.END_TAG -> {
                    when (local(p)) {
                        "t" -> { enT = false }
                        "p" -> {
                            val txt = parrafo.toString()
                            if (profTabla == 0) {
                                if (txt.isNotBlank()) filas.add(listOf(txt.trim()))
                            } else {
                                celda?.append(txt)?.append('\n')
                            }
                            parrafo = StringBuilder()
                        }
                        "tc" -> {
                            if (profTabla == 1) {
                                fila?.add(celda?.toString()?.trim() ?: "")
                                repeat(span - 1) { fila?.add("") }
                                celda = null
                            }
                        }
                        "tr" -> {
                            if (profTabla == 1) {
                                fila?.let { filas.add(it) }
                                fila = null
                            }
                        }
                        "tbl" -> { profTabla-- }
                    }
                }
            }
            ev = p.next()
        }
        return filas
    }

    // ---------- Interpretación de la tabla ----------
    private fun limpiar(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    private fun esEtiquetaDoc(s: String): Boolean = ETIQUETA_DOC.containsMatchIn(s.uppercase().trim())

    /** Valor de una etiqueta: en el mismo texto ("SEGURO: MAPFRE") o en el texto siguiente ("VIN:" / "3WKA..."). */
    private fun valorDe(items: List<String>, etiqueta: Regex, valor: Regex): String {
        for (i in items.indices) {
            val u = items[i].uppercase()
            val m = etiqueta.find(u) ?: continue
            val resto = u.substring(m.range.last + 1)
            valor.find(resto)?.let { return it.value.trim() }
            val sig = items.getOrNull(i + 1) ?: continue
            if (esEtiquetaDoc(sig)) continue
            valor.matchEntire(sig.uppercase().trim())?.let { return it.value.trim() }
        }
        return ""
    }

    private fun serialAFecha(s: String): String {
        val dias = s.toDoubleOrNull()?.toLong() ?: return ""
        val f = LocalDate.of(1899, 12, 30).plusDays(dias)
        return "%02d/%02d/%04d".format(f.dayOfMonth, f.monthValue, f.year)
    }

    private fun fechaDe(cab: List<String>, etiqueta: Regex): String {
        val f = valorDe(cab, etiqueta, FECHA_DOC)
        if (f.isNotEmpty()) return normalizarFecha(f)
        // Excel guarda las fechas como número de serie
        return serialAFecha(valorDe(cab, etiqueta, Regex("\\d{5}(?:\\.\\d+)?")))
    }

    private fun desdeFilas(filas: List<List<String>>): Orden {
        val hdr = filas.indexOfFirst { f ->
            f.any { c ->
                val u = c.uppercase()
                u.contains("CONCEPTO") || u.contains("DESCRIPCI")
            }
        }
        if (hdr < 0) throw IllegalStateException("No se encontró la tabla de piezas (columna CONCEPTO).")

        // ----- Encabezado: todo lo que está antes de la tabla -----
        val cab = filas.subList(0, hdr)
            .flatMap { f -> f.flatMap { c -> c.split("\n") } }
            .map { limpiar(it) }
            .filter { it.isNotEmpty() }

        val seguro = valorDe(cab, Regex("SEGURO"), Regex("[A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ0-9 .&-]*"))
        val elaboracion = fechaDe(cab, Regex("ELABORACI.N"))
        val entrega = fechaDe(cab, Regex("ENTREGA"))
        val ot = valorDe(cab, Regex("(?<![A-Z])OT(?![A-Z])"), Regex("\\d{3,7}"))
        val modelo = valorDe(cab, Regex("MODELO"), Regex("(?:19|20)\\d{2}"))

        var vin = ""
        for (s in cab) {
            val t = s.uppercase().replace(" ", "")
            val m = Regex("[A-HJ-NPR-Z0-9]{17}").find(t) ?: continue
            if (m.value.count { it.isDigit() } >= 3 && m.value.count { it.isLetter() } >= 3) {
                vin = m.value
                break
            }
        }

        var unidad = valorDe(cab, Regex("UNIDAD\\W*"), Regex(".+"))
        if (unidad.isEmpty()) {
            unidad = cab.firstOrNull { s ->
                val u = s.uppercase()
                MARCA_DOC.containsMatchIn(u) && !FECHA_DOC.containsMatchIn(u) && !esEtiquetaDoc(u) && u.length <= 40
            } ?: ""
        }

        var taller = valorDe(cab, Regex("^TALLER\\W*"), Regex(".+"))
        if (taller.isEmpty()) {
            taller = cab.lastOrNull { s ->
                val u = s.uppercase().trim()
                u.length >= 4 && u.all { it.isLetter() } && !esEtiquetaDoc(u) &&
                    u != seguro && !MARCA_DOC.containsMatchIn(u)
            } ?: ""
        }
        val jefe = valorDe(cab, Regex("JEFE\\s+DE\\s+TALLER\\W*"), Regex("[A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ .]*"))

        // ----- Piezas -----
        val titulos = filas[hdr]
        val cuerpo = filas.subList(hdr + 1, filas.size)
        fun col(rx: Regex): Int = titulos.indexOfFirst { rx.containsMatchIn(it.uppercase()) }
        val cConcepto = col(Regex("CONCEPTO|DESCRIPCI"))
        var cPz = col(Regex("^\\s*(PZS?|CANT\\w*)\\W*$"))
        val cProv = col(Regex("PROVEED"))
        if (cPz < 0 && cConcepto > 0) cPz = cConcepto - 1

        val numRx = Regex("^\\d{1,3}\\s*[.,]?$")
        fun cuantos(c: Int): Int = cuerpo.count { f ->
            val v = f.getOrNull(c)?.trim()
            v != null && numRx.matches(v)
        }
        // Columna "No." (consecutivo): la que está justo antes de PZ y trae números
        val cNum = if (cPz > 0 && cuantos(cPz - 1) >= 2) cPz - 1 else -1

        val base = System.currentTimeMillis()
        val items = mutableListOf<Item>()
        for (f in cuerpo) {
            val concepto = limpiar(f.getOrNull(cConcepto) ?: "")
            if (concepto.isEmpty() || !concepto.any { it.isLetter() }) continue
            val pzTxt = limpiar(f.getOrNull(cPz) ?: "")
            val numLeido = if (cNum >= 0) {
                Regex("\\d{1,3}").find(f.getOrNull(cNum) ?: "")?.value?.toIntOrNull() ?: 0
            } else {
                0
            }
            // Una fila sin cantidad ni número no es una pieza (notas, totales, firmas)
            if (pzTxt.isEmpty() && numLeido == 0) continue
            val pz = Regex("\\d+(?:[.,]\\d+)?").find(pzTxt)?.value?.replace(',', '.')?.removeSuffix(".0") ?: "1"
            val prov = if (cProv >= 0) limpiar(f.getOrNull(cProv) ?: "") else ""
            items.add(
                Item(
                    id = base + items.size,
                    pz = pz,
                    concepto = concepto,
                    proveedor = prov,
                    num = if (numLeido > 0) numLeido else items.size + 1
                )
            )
        }
        if (items.isEmpty()) throw IllegalStateException("No se encontraron piezas en el archivo.")

        return Orden(
            id = base,
            seguro = seguro,
            elaboracion = elaboracion,
            entrega = entrega,
            ot = ot,
            unidad = unidad,
            vin = vin,
            modelo = modelo,
            taller = taller,
            jefeTaller = jefe,
            items = items
        )
    }
}
