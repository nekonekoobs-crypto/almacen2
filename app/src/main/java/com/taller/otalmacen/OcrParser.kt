package com.taller.otalmacen

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.hypot

object OcrParser {

    private class L(val text: String, val x: Int, val cx: Int, val y: Int, val h: Int, val els: List<Pair<String, Int>> = emptyList())

    private val ETIQUETAS = listOf("SEGURO", "ELABORACI", "ENTREGA", "MODELO", "VIN", "TRAZO")
    private val MARCAS = listOf(
        "KENWORTH", "FREIGHTLINER", "VOLVO", "INTERNATIONAL", "PETERBILT", "MACK", "SCANIA",
        "MERCEDES", "HINO", "ISUZU", "FORD", "CHEVROLET", "DODGE", "NISSAN", "TOYOTA",
        "FOTON", "JAC", "IVECO", "DINA", "MAN", "KW"
    )
    private val MARCA_RX = Regex("\\b(" + MARCAS.joinToString("|") + ")\\b")
    private val FECHA = Regex("\\d{1,2}\\s*[/\\-.]\\s*(?:\\d{1,2}|[A-ZÁÉÍÓÚ]{3,10})\\s*[/\\-.]\\s*\\d{2,4}")

    // ---------- Lectura ----------
    fun leer(context: Context, uri: Uri, onDone: (Orden) -> Unit, onError: (String) -> Unit) {
        val ui = Handler(Looper.getMainLooper())
        Thread {
            val mejorada: Bitmap? = try {
                Imagen.mejorar(context, uri)
            } catch (e: Throwable) {
                null
            }
            ui.post {
                if (mejorada == null) {
                    leerOriginal(context, uri, null, onDone, onError)
                } else {
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    recognizer.process(InputImage.fromBitmap(mejorada, 0))
                        .addOnSuccessListener { t ->
                            val o = parse(t)
                            // Si falta algo importante se lee también la foto original y se completan los huecos
                            if (completo(o)) onDone(o) else leerOriginal(context, uri, o, onDone, onError)
                        }
                        .addOnFailureListener { leerOriginal(context, uri, null, onDone, onError) }
                }
            }
        }.start()
    }

    private fun completo(o: Orden): Boolean =
        o.items.isNotEmpty() && o.ot.isNotBlank() && o.unidad.isNotBlank() && o.modelo.isNotBlank()

    private fun leerOriginal(
        context: Context,
        uri: Uri,
        previo: Orden?,
        onDone: (Orden) -> Unit,
        onError: (String) -> Unit
    ) {
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (e: Exception) {
            if (previo != null) onDone(previo) else onError(e.message ?: "No se pudo abrir la imagen")
            return
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { t ->
                val o = parse(t)
                onDone(if (previo == null) o else combinar(previo, o))
            }
            .addOnFailureListener { e ->
                if (previo != null) onDone(previo) else onError(e.message ?: "Error de lectura")
            }
    }

    private fun combinar(a: Orden, b: Orden): Orden = a.copy(
        seguro = a.seguro.ifBlank { b.seguro },
        elaboracion = a.elaboracion.ifBlank { b.elaboracion },
        entrega = a.entrega.ifBlank { b.entrega },
        ot = a.ot.ifBlank { b.ot },
        unidad = a.unidad.ifBlank { b.unidad },
        vin = a.vin.ifBlank { b.vin },
        modelo = a.modelo.ifBlank { b.modelo },
        taller = a.taller.ifBlank { b.taller },
        items = if (a.items.isNotEmpty()) a.items else b.items
    )

    // ---------- Utilidades ----------
    /** Inclinación típica del texto en la foto (pendiente), para enderezar las alturas. */
    private fun pendiente(t: Text): Double {
        val lista = mutableListOf<Double>()
        for (b in t.textBlocks) {
            for (l in b.lines) {
                val p = l.cornerPoints
                if (p == null || p.size < 2 || l.text.length < 8) continue
                val dx = (p[1].x - p[0].x).toDouble()
                if (dx < 100) continue
                lista.add((p[1].y - p[0].y) / dx)
            }
        }
        if (lista.isEmpty()) return 0.0
        lista.sort()
        val m = lista[lista.size / 2]
        return if (m > 0.25 || m < -0.25) 0.0 else m
    }

    private fun esEtiqueta(s: String): Boolean {
        val u = s.uppercase()
        return ETIQUETAS.any { u.contains(it) }
    }

    /**
     * Busca el valor de una etiqueta: primero en la misma línea ("SEGURO: MAFRE"),
     * y si no, la línea más cercana a la derecha o justo debajo ("ELABORACION" / "20/10/2026").
     */
    private fun buscar(cab: List<L>, etiqueta: Regex, valor: Regex, mismaFila: Boolean = false): String {
        val lab = cab.firstOrNull { etiqueta.containsMatchIn(it.text.uppercase()) } ?: return ""
        val mismo = Regex(etiqueta.pattern + "\\W{0,4}(" + valor.pattern + ")").find(lab.text.uppercase())
        if (mismo != null) return mismo.groupValues[1]
        var mejor = ""
        var mejorD = Double.MAX_VALUE
        for (l in cab) {
            if (l === lab) continue
            if (esEtiqueta(l.text)) continue
            val txt = l.text.uppercase().trim()
            // El valor debe ser la línea completa: así nunca se toma un pedazo de otro texto
            if (!valor.matches(txt)) continue
            val dy = (l.y - lab.y).toDouble()
            val dx = (l.cx - lab.cx).toDouble()
            val d = if (abs(dy) < lab.h * 0.7 && dx > 0) {
                dx
            } else if (!mismaFila && dy > 0 && dy < lab.h * 4) {
                dy * 1.5 + abs(dx)
            } else {
                continue
            }
            if (d < mejorD) {
                mejorD = d
                mejor = txt
            }
        }
        return mejor
    }

    /**
     * Convierte una línea del OCR en una o más filas. Si el OCR junta varias filas de la tabla
     * en una sola línea, se separa una fila por cada altura distinta.
     */
    private fun separarFilas(l: Text.Line, k: Double): List<L> {
        val b = l.boundingBox ?: return emptyList()
        val p = l.cornerPoints
        val alto = if (p != null && p.size >= 4) {
            hypot((p[3].x - p[0].x).toDouble(), (p[3].y - p[0].y).toDouble()).toInt()
        } else {
            b.height()
        }
        val cx = (b.left + b.right) / 2
        val els = l.elements.mapNotNull { e -> e.boundingBox?.let { bb -> Pair(e.text, bb.left) } }
        val unica = listOf(L(l.text.trim(), b.left, cx, (b.centerY() - k * cx).toInt(), alto, els))

        val conCaja = l.elements.filter { it.boundingBox != null }
        if (conCaja.size < 2) return unica
        val altoEl = conCaja.map { it.boundingBox!!.height() }.sorted()[conCaja.size / 2]
        if (altoEl <= 0) return unica
        val yDe = { e: Text.Element ->
            val bb = e.boundingBox!!
            bb.centerY() - k * bb.centerX()
        }
        val grupos = mutableListOf<MutableList<Text.Element>>()
        for (e in conCaja.sortedBy { yDe(it) }) {
            val g = grupos.lastOrNull()
            if (g != null && abs(yDe(e) - yDe(g.last())) < altoEl * 0.8) g.add(e) else grupos.add(mutableListOf(e))
        }
        if (grupos.size < 2) return unica
        return grupos.map { g ->
            val ord = g.sortedBy { it.boundingBox!!.left }
            val izq = ord.minOf { it.boundingBox!!.left }
            val der = ord.maxOf { it.boundingBox!!.right }
            val yy = ord.map { yDe(it) }.average().toInt()
            L(
                ord.joinToString(" ") { it.text }, izq, (izq + der) / 2, yy, altoEl,
                ord.map { Pair(it.text, it.boundingBox!!.left) }
            )
        }
    }

    // ---------- Interpretación ----------
    private fun parse(t: Text): Orden {
        val k = pendiente(t)
        val lines = t.textBlocks.flatMap { it.lines }.flatMap { l -> separarFilas(l, k) }

        if (lines.isEmpty()) return Orden(id = System.currentTimeMillis())

        // El encabezado termina donde empieza la tabla: títulos PZ / CONCEPTO / PROVEEDOR / COSTOS
        val titulos = Regex("CONC|PROVEED|COSTO")
        val soloPz = Regex("^PZS?\\W*$")
        val ancla = lines
            .filter {
                val u = it.text.uppercase().trim()
                titulos.containsMatchIn(u) || soloPz.matches(u)
            }
            .minByOrNull { it.y }
        // Si no se leyeron los títulos, el encabezado termina en la primera pieza (renglón con cantidad)
        val primeraPieza = lines
            .filter { Regex("^\\d{1,2}(\\s+\\p{L}.*)?$").matches(it.text.trim()) }
            .minByOrNull { it.y }
        val headerY = when {
            ancla != null -> ancla.y
            primeraPieza != null -> primeraPieza.y - primeraPieza.h
            else -> {
                val mn = lines.minOf { it.y }
                val mx = lines.maxOf { it.y }
                mn + ((mx - mn) * 0.10).toInt()
            }
        }
        val cab = lines.filter { it.y < headerY }

        // Seguro
        var seguro = buscar(cab, Regex("SEGURO"), Regex("\\p{L}{3,}"), true)
        if (esEtiqueta(seguro)) seguro = ""

        // Fechas
        var elaboracion = buscar(cab, Regex("ELABORACI.N"), FECHA)
        var entrega = buscar(cab, Regex("ENTREGA"), FECHA)
        if (elaboracion.isBlank() || entrega.isBlank()) {
            val fechas = cab.sortedBy { it.cx }
                .mapNotNull { FECHA.find(it.text.uppercase())?.value }
            if (elaboracion.isBlank() && fechas.isNotEmpty()) elaboracion = fechas[0]
            if (entrega.isBlank() && fechas.size > 1) entrega = fechas[1]
        }

        // OT
        var ot = buscar(cab, Regex("\\b[O0][T7]\\b"), Regex("\\d{3,6}"), true)
        if (ot.isBlank()) {
            ot = cab.firstNotNullOfOrNull {
                Regex("^[O0][T7]\\W*(\\d{3,6})$").find(it.text.uppercase().trim())?.groupValues?.get(1)
            } ?: ""
        }

        // Modelo (año)
        var modelo = buscar(cab, Regex("MODEL[O0]"), Regex("(?:19|20)\\d{2}"), true)
        if (modelo.isBlank()) {
            modelo = cab.firstNotNullOfOrNull {
                Regex("^((?:19|20)\\d{2})$").find(it.text.trim())?.groupValues?.get(1)
            } ?: ""
        }

        // VIN: 17 caracteres con letras y números
        var vin = ""
        for (l in cab) {
            val s = l.text.uppercase().replace(" ", "")
            val m = Regex("[A-Z0-9]{17}").find(s) ?: continue
            val v = m.value
            if (v.count { it.isDigit() } >= 3 && v.count { it.isLetter() } >= 3) {
                vin = v
                break
            }
        }
        vin = vin.replace('O', '0').replace('I', '1').replace('Q', '0')

        // Unidad: línea con marca conocida o letras + números (ej. KENWORTH T880)
        fun candidata(l: L): Boolean {
            val u = l.text.uppercase().trim()
            if (u.length < 4) return false
            if (esEtiqueta(u)) return false
            if (FECHA.containsMatchIn(u)) return false
            if (u.replace(" ", "").length >= 15) return false
            if (MARCA_RX.containsMatchIn(u)) return true
            return u.count { it.isLetter() } >= 3 && u.any { it.isDigit() }
        }
        val candidatas = cab.filter { candidata(it) }
        val unidad = (candidatas.firstOrNull { MARCA_RX.containsMatchIn(it.text.uppercase()) }
            ?: candidatas.minWithOrNull(compareBy({ it.y }, { it.x })))?.text ?: ""

        // Taller: palabra suelta (solo letras) más a la derecha del encabezado
        val taller = cab.filter { l ->
            val u = l.text.uppercase().trim()
            u.length >= 4 && u.all { it.isLetter() } && !esEtiqueta(u) &&
                u != seguro.uppercase() && !MARCA_RX.containsMatchIn(u)
        }.maxByOrNull { it.cx }?.text ?: ""

        // ---------- Piezas ----------
        // La columna PROVEEDOR (letra a mano, fechas) queda fuera: no es parte del concepto
        val titProv = lines.filter { it.text.uppercase().contains("PROVEED") }.minByOrNull { it.y }
        val anchoPag = lines.maxOf { it.cx }
        val limiteX = if (titProv != null && titProv.x > anchoPag * 0.45) {
            titProv.x - (titProv.h * 1.5).toInt()
        } else {
            Int.MAX_VALUE
        }

        val body = lines.filter { it.y > headerY + (ancla?.h ?: 0) / 2 }.sortedBy { it.y }
        val soloNum = Regex("^\\d{1,2}$")
        val conQty = Regex("^(\\d{1,2})\\s+(\\p{L}.*)$")
        val numRx = Regex("^(\\d{1,3})\\s*[.,]?$")
        val encabezadoRx = Regex("^(SEGURO\\b|ELABORACI|ENTREGA\\b|MODELO\\b|VIN\\b|TRAZO\\b)")

        // Columna "No." (franja roja de la izquierda): números sueltos a la izquierda del título PZ
        val titPz = lines.firstOrNull { soloPz.matches(it.text.uppercase().trim()) }
        val colNum: List<L> = if (titPz == null) {
            emptyList()
        } else {
            body.filter { numRx.matches(it.text.replace(" ", "")) && it.cx < titPz.x }
        }
        val numeros = body.filter { soloNum.matches(it.text.replace(" ", "")) && it !in colNum }

        val altos = body.filter { it.text.length >= 6 }.map { it.h }.sorted()
        val altura = if (altos.isEmpty()) 30 else altos[altos.size / 2]

        // No. de renglón que está en la misma fila que la línea
        fun numCercano(l: L): Int? {
            val c = colNum.filter { abs(it.y - l.y) < altura * 0.8 }
                .minByOrNull { abs(it.y - l.y) } ?: return null
            return numRx.find(c.text.replace(" ", ""))?.groupValues?.get(1)?.toIntOrNull()
        }

        val porNumero = colNum.size >= 3
        val items = mutableListOf<Item>()
        val base = System.currentTimeMillis()
        var ultX = 0
        var ultY = Int.MIN_VALUE
        var continuo = false
        for (l in body) {
            val recorte = if (limiteX != Int.MAX_VALUE && l.els.isNotEmpty()) {
                l.els.filter { it.second < limiteX }.joinToString(" ") { it.first }
            } else {
                l.text
            }
            val up = recorte.trim()
            val u = up.uppercase()
            if (!up.any { it.isLetter() }) continue
            if (u.contains("PROVEEDOR") || u.contains("COSTOS") || u.contains("CONCEPTO")) continue
            // Nada del encabezado (etiquetas, fechas, VIN, unidad, taller) puede entrar como pieza
            if (encabezadoRx.containsMatchIn(u) || FECHA.containsMatchIn(u)) continue
            if (u.replace(" ", "").let { it.length == 17 && it.any { c -> c.isDigit() } && it.any { c -> c.isLetter() } }) continue
            if (u == unidad.uppercase() || u == taller.uppercase() || u == seguro.uppercase()) continue

            var texto = up.replaceFirst(Regex("^\\d{1,2}[.,]\\s+"), "")
            var pz: String? = null
            val m = conQty.find(texto)
            if (m != null) {
                pz = m.groupValues[1]
                texto = m.groupValues[2]
            } else {
                val q = numeros
                    .filter { it.x < l.x && abs(it.y - l.y) < altura * 0.7 && l.x - it.x < altura * 3.5 }
                    .maxByOrNull { it.x }
                if (q != null) pz = q.text.replace(" ", "")
            }
            val numFila = numCercano(l)
            // Si hay No. de renglón pero no se leyó la cantidad, es una pieza de 1
            if (pz == null && porNumero && numFila != null) pz = "1"

            if (pz != null) {
                items.add(Item(base + items.size, pz, texto.trim(), num = numFila ?: 0))
                ultX = l.x
                ultY = l.y
                continuo = false
            } else {
                val dy = l.y - ultY
                // Un concepto solo puede ocupar 2 renglones de texto como máximo
                val esContinuacion = items.isNotEmpty() && !continuo &&
                    dy > 0 && dy < altura * 1.25 && abs(l.x - ultX) < 50
                if (esContinuacion) {
                    val last = items.removeAt(items.size - 1)
                    items.add(last.copy(concepto = (last.concepto + " " + texto).trim()))
                    ultY = l.y
                    continuo = true
                } else if (!porNumero && texto.length >= 4) {
                    items.add(Item(base + items.size, "1", texto.trim()))
                    ultX = l.x
                    ultY = l.y
                    continuo = false
                }
            }
        }

        // Relación por número: si no se leyó un No., sigue la secuencia; los números van siempre en orden
        var ult = 0
        val numerados = items.mapIndexed { i, p ->
            val ok = p.num > 0 && (i == 0 || (p.num > ult && p.num <= ult + 3))
            val n = if (ok) p.num else ult + 1
            ult = n
            p.copy(num = n)
        }.flatMap { p ->
            // Un concepto que pasa del límite se parte en piezas separadas; el usuario las revisa
            partirConcepto(p.concepto).mapIndexed { i, parte ->
                p.copy(
                    id = p.id + 1000L * i,
                    concepto = parte,
                    pz = if (i == 0) p.pz else "1",
                    num = if (i == 0) p.num else 0
                )
            }
        }

        return Orden(
            id = base,
            seguro = seguro,
            elaboracion = normalizarFecha(elaboracion),
            entrega = normalizarFecha(entrega),
            ot = ot,
            unidad = unidad,
            vin = vin,
            modelo = modelo,
            taller = taller,
            items = numerados
        )
    }
}
