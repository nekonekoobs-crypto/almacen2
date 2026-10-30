package com.taller.otalmacen

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Fotos cifradas en un chat privado de Telegram, sin pasar por Supabase.
 *
 * La foto se cifra en el teléfono (AES-256-GCM) y se manda como un archivo .bin al chat de tu bot;
 * Telegram solo guarda texto cifrado. Para mostrarla, la app baja el archivo y lo descifra.
 * El token del bot, el chat y la llave se inyectan al compilar (secretos de GitHub), no están en el código.
 */
object Telegram {
    class Archivo(val fileId: String, val mensajeId: Long)

    private class Resp(val codigo: Int, val cuerpo: ByteArray)

    private val token: String get() = BuildConfig.TG_BOT_TOKEN.trim()
    private val chat: String get() = BuildConfig.TG_CHAT_ID.trim()

    private fun llave(): ByteArray? = try {
        val k = Base64.decode(BuildConfig.TG_FOTOS_KEY.trim(), Base64.DEFAULT)
        if (k.size == 32) k else null
    } catch (e: Exception) {
        null
    }

    /** ¿Están puestos el token, el chat y la llave (de 32 bytes) en esta versión de la app? */
    fun configurado(): Boolean = token.isNotEmpty() && chat.isNotEmpty() && llave() != null

    // ---------- Cifrado: 12 bytes de IV + datos cifrados (la ruta de la foto se liga al cifrado) ----------
    fun cifrar(datos: ByteArray, ruta: String): ByteArray {
        val k = llave() ?: throw IllegalStateException("Falta la llave de cifrado")
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(ruta.toByteArray(Charsets.UTF_8))
        return iv + c.doFinal(datos)
    }

    fun descifrar(datos: ByteArray, ruta: String): ByteArray {
        val k = llave() ?: throw IllegalStateException("Falta la llave de cifrado")
        if (datos.size < 13) throw IOException("Archivo cifrado incompleto")
        val iv = datos.copyOfRange(0, 12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(ruta.toByteArray(Charsets.UTF_8))
        return c.doFinal(datos, 12, datos.size - 12)
    }

    // ---------- HTTP ----------
    private fun http(metodo: String, url: String, cuerpo: ByteArray?, tipo: String?): Resp {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.requestMethod = metodo
            con.connectTimeout = 15000
            con.readTimeout = 60000
            if (tipo != null) con.setRequestProperty("Content-Type", tipo)
            if (cuerpo != null) {
                con.doOutput = true
                con.setFixedLengthStreamingMode(cuerpo.size)
                con.outputStream.use { it.write(cuerpo) }
            }
            val codigo = con.responseCode
            val flujo = if (codigo in 200..299) con.inputStream else con.errorStream
            val bytes = flujo?.use { it.readBytes() } ?: ByteArray(0)
            return Resp(codigo, bytes)
        } finally {
            con.disconnect()
        }
    }

    /** Llama a un método del bot. Si Telegram pide esperar (429), espera y reintenta una vez. */
    private fun api(metodo: String, cuerpo: ByteArray, tipo: String): JSONObject {
        for (intento in 0..1) {
            val r = http("POST", "https://api.telegram.org/bot$token/$metodo", cuerpo, tipo)
            val j = try {
                JSONObject(String(r.cuerpo, Charsets.UTF_8))
            } catch (e: Exception) {
                JSONObject()
            }
            if (r.codigo == 429 && intento == 0) {
                val espera = j.optJSONObject("parameters")?.optInt("retry_after", 2) ?: 2
                Thread.sleep(minOf(espera, 8) * 1000L)
                continue
            }
            return j
        }
        return JSONObject()
    }

    // ---------- Operaciones ----------
    /** Cifra la foto y la manda al chat. Devuelve los datos para volver a bajarla o borrarla. */
    fun subir(ruta: String, foto: ByteArray): Archivo {
        if (!configurado()) throw IllegalStateException("Telegram no está configurado en esta versión de la app")
        val cifrado = cifrar(foto, ruta)
        val limite = "----OTTRACSA" + UUID.randomUUID().toString().replace("-", "")
        val out = ByteArrayOutputStream()
        out.write("--$limite\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n$chat\r\n".toByteArray())
        // Nombre sin significado: en Telegram solo se ve un archivo .bin
        out.write(
            ("--$limite\r\nContent-Disposition: form-data; name=\"document\"; filename=\"${UUID.randomUUID()}.bin\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
        )
        out.write(cifrado)
        out.write("\r\n--$limite--\r\n".toByteArray())
        val j = api("sendDocument", out.toByteArray(), "multipart/form-data; boundary=$limite")
        val res = j.optJSONObject("result")
        val fileId = res?.optJSONObject("document")?.optString("file_id") ?: ""
        if (!j.optBoolean("ok") || fileId.isEmpty()) {
            throw IOException("Telegram rechazó el archivo: " + j.optString("description", "sin detalle"))
        }
        return Archivo(fileId, res?.optLong("message_id", 0L) ?: 0L)
    }

    /** Baja el archivo cifrado del chat y lo descifra. */
    fun bajar(fileId: String, ruta: String): ByteArray {
        if (!configurado()) throw IllegalStateException("Telegram no está configurado en esta versión de la app")
        val j = api("getFile", JSONObject().put("file_id", fileId).toString().toByteArray(), "application/json")
        val rutaTg = j.optJSONObject("result")?.optString("file_path") ?: ""
        if (!j.optBoolean("ok") || rutaTg.isEmpty()) {
            throw IOException("Telegram no encontró el archivo: " + j.optString("description", "sin detalle"))
        }
        val r = http("GET", "https://api.telegram.org/file/bot$token/$rutaTg", null, null)
        if (r.codigo != 200) throw IOException("No se pudo bajar de Telegram (${r.codigo})")
        return descifrar(r.cuerpo, ruta)
    }

    /** Intenta borrar el mensaje. Telegram solo deja borrar mensajes recientes; si no se puede, no pasa nada. */
    fun borrar(mensajeId: Long) {
        if (!configurado() || mensajeId <= 0L) return
        api(
            "deleteMessage",
            JSONObject().put("chat_id", chat).put("message_id", mensajeId).toString().toByteArray(),
            "application/json"
        )
    }
}
