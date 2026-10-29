package com.taller.otalmacen

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Copias de seguridad locales: un .zip con todas las OT (ordenes/*.json) y sus fotos (fotos_baja/*),
 * comprimido al máximo. Se guarda en Descargas/OT TRACSA (sobrevive aunque se desinstale la app).
 */
object Respaldo {
    private const val DIAS = 15
    private const val CARPETA = "OT TRACSA"
    private const val PREFIJO = "OT_TRACSA_respaldo_"
    private const val CONSERVAR = 6

    class Resumen(val ordenes: Int, val fotos: Int)

    private val rxOrden = Regex("^ordenes/[0-9]+\\.json$")
    private val rxFoto = Regex("^fotos_baja/[A-Za-z0-9_.\\-]+$")

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    private fun archivosOrdenes(base: File): List<File> =
        (File(base, "ordenes").listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".json") }

    private fun archivosFotos(base: File): List<File> =
        (File(base, "fotos_baja").listFiles() ?: emptyArray()).filter { it.isFile }

    /** ¿Ya pasaron 15 días desde la última copia (y hay algo que respaldar)? */
    fun toca(ctx: Context): Boolean {
        if (archivosOrdenes(ctx.filesDir).isEmpty()) return false
        val ultimo = prefs(ctx).getLong("ultimoRespaldo", 0L)
        return System.currentTimeMillis() - ultimo >= DIAS * 24L * 60L * 60L * 1000L
    }

    /** Hace la copia y devuelve un mensaje con el lugar donde quedó. */
    fun crear(ctx: Context): String {
        val base = ctx.filesDir
        if (archivosOrdenes(base).isEmpty()) throw IllegalStateException("No hay órdenes para respaldar.")
        val nombre = PREFIJO + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date()) + ".zip"
        val tmp = File(ctx.cacheDir, nombre)
        try {
            escribirZip(base, tmp)
            val lugar = guardarEnDescargas(ctx, tmp, nombre)
            prefs(ctx).edit().putLong("ultimoRespaldo", System.currentTimeMillis()).apply()
            return "Copia de seguridad guardada en $lugar"
        } finally {
            tmp.delete()
        }
    }

    private fun escribirZip(base: File, destino: File) {
        val ordenes = archivosOrdenes(base)
        val fotos = archivosFotos(base)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destino))).use { z ->
            z.setLevel(Deflater.BEST_COMPRESSION)
            for (f in ordenes) {
                z.putNextEntry(ZipEntry("ordenes/" + f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
            for (f in fotos) {
                z.putNextEntry(ZipEntry("fotos_baja/" + f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
            val manifiesto = JSONObject()
                .put("app", "OT TRACSA")
                .put("fecha", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
                .put("ordenes", ordenes.size)
                .put("fotos", fotos.size)
            z.putNextEntry(ZipEntry("manifiesto.json"))
            z.write(manifiesto.toString().toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
    }

    private fun guardarEnDescargas(ctx: Context, origen: File, nombre: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = ctx.contentResolver
            val valores = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, nombre)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/$CARPETA")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
                ?: throw IOException("No se pudo crear el archivo en Descargas")
            val salida = cr.openOutputStream(uri) ?: throw IOException("No se pudo escribir en Descargas")
            salida.use { out -> origen.inputStream().use { it.copyTo(out) } }
            val listo = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            cr.update(uri, listo, null, null)
            podarAntiguas(ctx)
            return "Descargas/$CARPETA"
        }
        // Android 8 y 9: carpeta de la app (no requiere permisos)
        val dir = ctx.getExternalFilesDir("respaldos") ?: File(ctx.filesDir, "respaldos")
        dir.mkdirs()
        origen.copyTo(File(dir, nombre), overwrite = true)
        val viejas = (dir.listFiles() ?: emptyArray()).filter { it.name.startsWith(PREFIJO) }.sortedByDescending { it.name }
        for (v in viejas.drop(CONSERVAR)) v.delete()
        return dir.path
    }

    /** Deja solo las últimas copias para no llenar el teléfono. Solo toca archivos creados por esta app. */
    private fun podarAntiguas(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val cr = ctx.contentResolver
            val tabla = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val ids = mutableListOf<Long>()
            cr.query(
                tabla,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                MediaStore.Downloads.RELATIVE_PATH + " LIKE ? AND " + MediaStore.Downloads.DISPLAY_NAME + " LIKE ?",
                arrayOf("Download/$CARPETA%", "$PREFIJO%"),
                MediaStore.Downloads.DISPLAY_NAME + " DESC"
            )?.use { c ->
                while (c.moveToNext()) ids.add(c.getLong(0))
            }
            for (id in ids.drop(CONSERVAR)) cr.delete(ContentUris.withAppendedId(tabla, id), null, null)
        } catch (e: Exception) {
            // si no se puede limpiar, no pasa nada
        }
    }

    /** Revisa el .zip sin guardar nada: cuántas OT y fotos trae. Lanza error si no es una copia válida. */
    fun inspeccionar(ctx: Context, uri: Uri): Resumen {
        var o = 0
        var f = 0
        val flujo = ctx.contentResolver.openInputStream(uri) ?: throw IOException("No se pudo abrir el archivo")
        ZipInputStream(flujo).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                if (rxOrden.matches(e.name)) o++
                else if (rxFoto.matches(e.name) && nombreSeguro(e.name.removePrefix("fotos_baja/"))) f++
            }
        }
        if (o == 0) throw IllegalStateException("El archivo no es una copia de seguridad de OT")
        return Resumen(o, f)
    }

    private fun nombreSeguro(n: String): Boolean = n.length > 2 && n != "." && n != ".."

    /**
     * Agrega al teléfono las OT y fotos de la copia tal cual estaban. Si una OT ya existe, se reemplaza
     * por la de la copia. Si subir es true, quedan pendientes de subir a la nube.
     */
    fun restaurar(ctx: Context, uri: Uri, subir: Boolean): Resumen {
        val base = ctx.filesDir
        val dirFotos = File(base, "fotos_baja")
        dirFotos.mkdirs()
        val ordenes = mutableListOf<Orden>()
        var fotos = 0
        val flujo = ctx.contentResolver.openInputStream(uri) ?: throw IOException("No se pudo abrir el archivo")
        ZipInputStream(flujo).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                if (rxOrden.matches(e.name)) {
                    val texto = String(z.readBytes(), Charsets.UTF_8)
                    ordenes.add(Store.fromJson(JSONObject(texto)))
                } else if (rxFoto.matches(e.name)) {
                    val n = e.name.removePrefix("fotos_baja/")
                    if (!nombreSeguro(n)) continue
                    File(dirFotos, n).outputStream().use { out -> z.copyTo(out) }
                    fotos++
                }
            }
        }
        // Primero las fotos (ya están), luego las OT
        for (o in ordenes) {
            Store.save(base, o)
            if (subir) Sync.alRestaurar(ctx, o)
        }
        return Resumen(ordenes.size, fotos)
    }
}
