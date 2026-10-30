package com.taller.otalmacen

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Item(
    val id: Long,
    val pz: String,
    val concepto: String,
    val proveedor: String = "",
    val alta: Boolean = false,
    val baja: Boolean = false,
    val fotoBaja: String = "",
    val num: Int = 0,   // No. de renglón (columna roja de la hoja), antes de PZ
    val prestamo: Boolean = false,
    val descPrestamo: String = "",
    val fotoPrestamo: String = "",
    val devolucion: Boolean = false,
    val descDevolucion: String = "",
    val sobrante: Boolean = false
)

/** Nombres de las fotos que tiene la pieza (salida y préstamo). */
fun Item.fotos(): List<String> = listOf(fotoBaja, fotoPrestamo).filter { it.isNotBlank() }

/**
 * Estado de la pieza: solo puede tener uno a la vez (alta, baja, préstamo, devolución o sobrante).
 * Al desmarcar el que estaba activo, la pieza queda sin estado. Las descripciones y fotos se conservan.
 */
fun Item.conEstado(estado: String, activo: Boolean): Item {
    val limpio = copy(alta = false, baja = false, prestamo = false, devolucion = false, sobrante = false)
    if (!activo) return limpio
    return when (estado) {
        "alta" -> limpio.copy(alta = true)
        "baja" -> limpio.copy(baja = true)
        "prestamo" -> limpio.copy(prestamo = true)
        "devolucion" -> limpio.copy(devolucion = true)
        "sobrante" -> limpio.copy(sobrante = true)
        else -> limpio
    }
}

data class Orden(
    val id: Long,
    val seguro: String = "",
    val elaboracion: String = "",
    val entrega: String = "",
    val ot: String = "",
    val unidad: String = "",
    val vin: String = "",
    val modelo: String = "",
    val taller: String = "",
    val jefeTaller: String = "",
    val items: List<Item> = emptyList(),
    val baja: Boolean = false   // OT dada de baja (se ve roja en la lista)
)

object Store {
    private fun dir(base: File): File = File(base, "ordenes").also { it.mkdirs() }

    fun save(base: File, o: Orden) {
        File(dir(base), "${o.id}.json").writeText(toJson(o).toString())
    }

    fun cargar(base: File, id: Long): Orden? {
        val f = File(dir(base), "$id.json")
        if (!f.exists()) return null
        return try {
            fromJson(JSONObject(f.readText()))
        } catch (e: Exception) {
            null
        }
    }

    fun delete(base: File, id: Long) {
        File(dir(base), "$id.json").delete()
    }

    fun loadAll(base: File): List<Orden> {
        val files = dir(base).listFiles() ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                fromJson(JSONObject(f.readText()))
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.id }
    }

    fun toJson(o: Orden): JSONObject {
        val arr = JSONArray()
        for (i in o.items) {
            arr.put(
                JSONObject()
                    .put("id", i.id)
                    .put("pz", i.pz)
                    .put("concepto", i.concepto)
                    .put("proveedor", i.proveedor)
                    .put("alta", i.alta)
                    .put("baja", i.baja)
                    .put("fotoBaja", i.fotoBaja)
                    .put("num", i.num)
                    .put("prestamo", i.prestamo)
                    .put("descPrestamo", i.descPrestamo)
                    .put("fotoPrestamo", i.fotoPrestamo)
                    .put("devolucion", i.devolucion)
                    .put("descDevolucion", i.descDevolucion)
                    .put("sobrante", i.sobrante)
            )
        }
        return JSONObject()
            .put("id", o.id)
            .put("seguro", o.seguro)
            .put("elaboracion", o.elaboracion)
            .put("entrega", o.entrega)
            .put("ot", o.ot)
            .put("unidad", o.unidad)
            .put("vin", o.vin)
            .put("modelo", o.modelo)
            .put("taller", o.taller)
            .put("jefeTaller", o.jefeTaller)
            .put("items", arr)
            .put("baja", o.baja)
    }

    fun fromJson(j: JSONObject): Orden {
        val arr = j.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).map { n ->
            val x = arr.getJSONObject(n)
            Item(
                id = x.optLong("id", n.toLong()),
                pz = x.optString("pz"),
                concepto = x.optString("concepto"),
                proveedor = x.optString("proveedor"),
                alta = x.optBoolean("alta"),
                baja = x.optBoolean("baja"),
                fotoBaja = x.optString("fotoBaja"),
                num = x.optInt("num", 0),
                prestamo = x.optBoolean("prestamo"),
                descPrestamo = x.optString("descPrestamo"),
                fotoPrestamo = x.optString("fotoPrestamo"),
                devolucion = x.optBoolean("devolucion"),
                descDevolucion = x.optString("descDevolucion"),
                sobrante = x.optBoolean("sobrante")
            )
        }
        return Orden(
            id = j.getLong("id"),
            seguro = j.optString("seguro"),
            elaboracion = j.optString("elaboracion"),
            entrega = j.optString("entrega"),
            ot = j.optString("ot"),
            unidad = j.optString("unidad"),
            vin = j.optString("vin"),
            modelo = j.optString("modelo"),
            taller = j.optString("taller"),
            jefeTaller = j.optString("jefeTaller"),
            items = items,
            baja = j.optBoolean("baja", false)
        )
    }
}

/** Busca en todos los campos del encabezado; cada palabra escrita debe aparecer en alguno. */
fun Orden.coincide(q: String): Boolean {
    val texto = listOf(ot, seguro, elaboracion, entrega, unidad, vin, modelo, taller, jefeTaller).joinToString(" ")
    return q.split(" ").filter { it.isNotBlank() }.all { texto.contains(it, ignoreCase = true) }
}


// ---------- Validador de piezas repetidas ----------

/** Texto en minúsculas, sin acentos, espacios ni signos, para comparar conceptos. */
fun normConcepto(s: String): String =
    java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .replace(Regex("[^a-z0-9]"), "")

private fun distancia(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        for (j in 1..b.length) {
            val costo = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + costo)
        }
        prev = cur
    }
    return prev[b.length]
}

/** Qué tan parecidos son dos conceptos, de 0.0 a 1.0 (tolera errores típicos del OCR). */
fun parecido(a: String, b: String): Double {
    val x = normConcepto(a)
    val y = normConcepto(b)
    if (x.isEmpty() || y.isEmpty()) return 0.0
    if (x == y) return 1.0
    val corto = minOf(x.length, y.length)
    if (corto >= 18 && (x.startsWith(y) || y.startsWith(x))) return 1.0
    return 1.0 - distancia(x, y).toDouble() / maxOf(x.length, y.length)
}

/** Dos conceptos se consideran la misma pieza si son iguales o casi iguales. */
fun mismaPieza(a: String, b: String): Boolean = parecido(a, b) >= 0.85

class ResultadoFusion(val items: List<Item>, val agregadas: Int, val repetidas: Int)

/**
 * Agrega a la OT solo las piezas que todavía no existen. Una pieza es repetida si:
 *  - tiene el mismo No. de renglón y un concepto parecido, o
 *  - su concepto es casi idéntico a uno existente.
 * Las piezas ya guardadas (con su alta, baja, proveedor y foto) nunca se tocan.
 */
fun fusionarItems(actuales: List<Item>, nuevos: List<Item>): ResultadoFusion {
    val lista = actuales.toMutableList()
    var agregadas = 0
    var repetidas = 0
    var siguienteId = (lista.maxOfOrNull { it.id } ?: System.currentTimeMillis()) + 1
    for (n in nuevos) {
        if (n.concepto.isBlank()) continue
        val igualNumero = n.num > 0 && lista.any { it.num == n.num && parecido(it.concepto, n.concepto) >= 0.5 }
        val igualTexto = lista.any { mismaPieza(it.concepto, n.concepto) }
        if (igualNumero || igualTexto) {
            repetidas++
        } else {
            val id = if (lista.any { it.id == n.id }) siguienteId++ else n.id
            lista.add(n.copy(id = id))
            agregadas++
        }
    }
    return ResultadoFusion(lista, agregadas, repetidas)
}

// ---------- Límite de caracteres al capturar por foto ----------

/** Máximo de caracteres por concepto cuando viene de una foto. Captura manual o edición no tienen límite. */
const val MAX_CARACTERES_FOTO = 50

/**
 * Limita el concepto leído por OCR a MAX_CARACTERES_FOTO. Si el límite cae a mitad de una palabra,
 * esa palabra se completa; las palabras siguientes ya no se agregan.
 */
fun limitarConcepto(texto: String): String {
    val t = texto.trim()
    if (t.length <= MAX_CARACTERES_FOTO) return t
    val fin = if (t[MAX_CARACTERES_FOTO - 1] == ' ' || t[MAX_CARACTERES_FOTO] == ' ') {
        MAX_CARACTERES_FOTO
    } else {
        val i = t.indexOf(' ', MAX_CARACTERES_FOTO)
        if (i == -1) t.length else i
    }
    return t.substring(0, fin).trim()
}

/** Parte un concepto largo en varios, cada uno dentro del límite, sin perder texto. */
fun partirConcepto(texto: String): List<String> {
    val partes = mutableListOf<String>()
    var resto = texto.trim()
    while (resto.isNotEmpty()) {
        val parte = limitarConcepto(resto)
        partes.add(parte)
        resto = resto.substring(parte.length).trim()
    }
    return if (partes.isEmpty()) listOf("") else partes
}
