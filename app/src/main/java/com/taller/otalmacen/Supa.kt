package com.taller.otalmacen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class Sesion(
    val access: String,
    val refresh: String,
    val expiraMs: Long,
    val email: String,
    val rol: String
)

class Resp(val codigo: Int, val cuerpo: ByteArray) {
    val texto: String get() = String(cuerpo, Charsets.UTF_8)
    val ok: Boolean get() = codigo in 200..299
}

/** Conexión con Supabase (sin librerías externas): inicio de sesión, base de datos y fotos. */
object Supa {
    const val URL_BASE = "https://rrxwhelqvbxiwqfcpwus.supabase.co"
    const val LLAVE = "sb_publishable_SyqWOd6GVH_AtxI4qnIZnw_u6C34uB1"

    /** ID de cliente "Web" de Google Cloud (termina en .apps.googleusercontent.com). */
    const val GOOGLE_WEB_CLIENT_ID = "106435452047-bkq9tcvut1obv253pvkc0s65hnsnr7gg.apps.googleusercontent.com"

    private val JSON = mapOf("Content-Type" to "application/json")

    fun peticion(
        metodo: String,
        ruta: String,
        cuerpo: ByteArray? = null,
        token: String? = null,
        cabeceras: Map<String, String> = emptyMap()
    ): Resp {
        val con = URL(URL_BASE + ruta).openConnection() as HttpURLConnection
        try {
            con.requestMethod = metodo
            con.connectTimeout = 15000
            con.readTimeout = 30000
            con.setRequestProperty("apikey", LLAVE)
            if (token != null) con.setRequestProperty("Authorization", "Bearer $token")
            for ((k, v) in cabeceras) con.setRequestProperty(k, v)
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

    fun errorDe(r: Resp): String {
        return try {
            val j = JSONObject(r.texto)
            val m = j.optString("error_description").ifBlank {
                j.optString("msg").ifBlank {
                    j.optString("message").ifBlank { j.optString("error") }
                }
            }
            if (m.isBlank()) "Error ${r.codigo}" else m
        } catch (e: Exception) {
            "Error ${r.codigo}"
        }
    }

    /** Texto para mostrar al usuario cuando algo falla. */
    fun mensaje(e: Throwable): String = when (e) {
        is IOException -> "Sin conexión a internet."
        else -> e.message ?: "No se pudo completar la operación."
    }

    // ---------- Sesión guardada en el teléfono ----------
    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("sesion", Context.MODE_PRIVATE)

    fun sesion(ctx: Context): Sesion? {
        val p = prefs(ctx)
        val a = p.getString("access", null) ?: return null
        val r = p.getString("refresh", null) ?: return null
        return Sesion(a, r, p.getLong("expira", 0L), p.getString("email", "") ?: "", p.getString("rol", "") ?: "")
    }

    fun guardarSesion(ctx: Context, s: Sesion) {
        prefs(ctx).edit()
            .putString("access", s.access)
            .putString("refresh", s.refresh)
            .putLong("expira", s.expiraMs)
            .putString("email", s.email)
            .putString("rol", s.rol)
            .apply()
    }

    fun cerrarSesion(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    private fun leerSesion(j: JSONObject, rol: String): Sesion {
        val expira = System.currentTimeMillis() + j.optLong("expires_in", 3600L) * 1000L
        val email = j.optJSONObject("user")?.optString("email") ?: ""
        return Sesion(j.getString("access_token"), j.getString("refresh_token"), expira, email, rol)
    }

    // ---------- Inicio de sesión (bloquea: llamar desde un hilo) ----------
    fun entrarConClave(ctx: Context, correo: String, clave: String): Sesion {
        val cuerpo = JSONObject().put("email", correo.trim()).put("password", clave).toString().toByteArray()
        val r = peticion("POST", "/auth/v1/token?grant_type=password", cuerpo, null, JSON)
        if (!r.ok) throw IllegalStateException("Correo o contraseña incorrectos.")
        return completarLogin(ctx, JSONObject(r.texto))
    }

    fun entrarConGoogle(ctx: Context, idToken: String, nonce: String): Sesion {
        val cuerpo = JSONObject()
            .put("provider", "google")
            .put("id_token", idToken)
            .put("nonce", nonce)
            .toString().toByteArray()
        val r = peticion("POST", "/auth/v1/token?grant_type=id_token", cuerpo, null, JSON)
        if (!r.ok) throw IllegalStateException("No se pudo entrar con Google: " + errorDe(r))
        return completarLogin(ctx, JSONObject(r.texto))
    }

    private fun nombreDe(j: JSONObject): String {
        val m = j.optJSONObject("user")?.optJSONObject("user_metadata") ?: return ""
        val completo = if (m.isNull("full_name")) "" else m.optString("full_name")
        if (completo.isNotBlank()) return completo
        return if (m.isNull("name")) "" else m.optString("name")
    }

    /** Guarda en la base la fecha de acceso y el nombre; si es la primera vez, queda como pendiente. */
    fun registrarAcceso(token: String, nombre: String) {
        val cuerpo = JSONObject().put("p_nombre", nombre).toString().toByteArray()
        peticion("POST", "/rest/v1/rpc/registrar_acceso", cuerpo, token, JSON)
    }

    /** Actualiza la fecha de último acceso como máximo cada 6 horas. */
    fun registrarAccesoSiToca(ctx: Context, token: String) {
        val p = prefs(ctx)
        val ahora = System.currentTimeMillis()
        if (ahora - p.getLong("ult_acceso", 0L) < 6L * 3600L * 1000L) return
        registrarAcceso(token, "")
        p.edit().putLong("ult_acceso", ahora).apply()
    }

    private fun completarLogin(ctx: Context, j: JSONObject): Sesion {
        val base = leerSesion(j, "")
        // Primer inicio de sesión: se guardan su correo y nombre en la base de datos
        registrarAcceso(base.access, nombreDe(j))
        val rol = consultarRol(base.access)
        when (rol) {
            "almacenista", "lector" -> {
                val s = Sesion(base.access, base.refresh, base.expiraMs, base.email, rol)
                guardarSesion(ctx, s)
                prefs(ctx).edit().putLong("ult_acceso", System.currentTimeMillis()).apply()
                return s
            }
            "pendiente" -> throw IllegalStateException(
                "Tu solicitud con ${base.email} quedó registrada. Un almacenista debe aprobar tu acceso; " +
                    "vuelve a intentarlo después."
            )
            "rechazado" -> throw IllegalStateException("Tu acceso (${base.email}) no fue autorizado.")
            else -> throw IllegalStateException("La cuenta ${base.email} no está autorizada.")
        }
    }

    /** Rol de la persona conectada, o null si no está en la lista de autorizados. */
    fun consultarRol(token: String): String? {
        // La función mi_rol() de la base devuelve el rol de quien está conectado
        // (leer la tabla completa devolvería la fila de otra persona si eres almacenista).
        val r = peticion("POST", "/rest/v1/rpc/mi_rol", "{}".toByteArray(), token, JSON)
        if (!r.ok) throw IllegalStateException("No se pudo verificar la cuenta (" + errorDe(r) + ").")
        val t = r.texto.trim()
        if (t.isEmpty() || t == "null") return null
        return try {
            JSONArray("[$t]").getString(0).ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Devuelve un token vigente y lo renueva si está por vencer.
     * Devuelve null si no hay sesión o no se pudo renovar (sin conexión): la sesión solo se borra
     * cuando el servidor rechaza la renovación.
     */
    @Synchronized
    fun tokenVigente(ctx: Context): String? {
        val s = sesion(ctx) ?: return null
        if (System.currentTimeMillis() < s.expiraMs - 60_000L) return s.access
        return try {
            val cuerpo = JSONObject().put("refresh_token", s.refresh).toString().toByteArray()
            val r = peticion("POST", "/auth/v1/token?grant_type=refresh_token", cuerpo, null, JSON)
            if (r.ok) {
                val n = leerSesion(JSONObject(r.texto), s.rol)
                guardarSesion(ctx, n)
                n.access
            } else {
                if (r.codigo in 400..403) cerrarSesion(ctx)
                null
            }
        } catch (e: IOException) {
            null
        }
    }
}
