package com.taller.otalmacen

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ResultadoSync(val mensaje: String, val sinSesion: Boolean = false, val usuariosPendientes: Int = -1)

/**
 * Sincronización con Supabase. La app sigue guardando todo en el teléfono (funciona sin internet);
 * los cambios del almacenista suben a la nube y todos los usuarios bajan lo que hay en la nube.
 */
object Sync {
    private val ui = Handler(Looper.getMainLooper())
    private var envioProgramado: Runnable? = null
    @Volatile private var ocupado = false

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)

    private fun conjunto(ctx: Context, clave: String): MutableSet<String> =
        (prefs(ctx).getStringSet(clave, emptySet()) ?: emptySet()).toMutableSet()

    private fun guardarConjunto(ctx: Context, clave: String, s: Set<String>) {
        prefs(ctx).edit().putStringSet(clave, HashSet(s)).apply()
    }

    private fun sellos(ctx: Context): JSONObject = try {
        JSONObject(prefs(ctx).getString("sellos", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private fun guardarSellos(ctx: Context, j: JSONObject) {
        prefs(ctx).edit().putString("sellos", j.toString()).apply()
    }

    // ---------- Almacenista restringido: solo modifica las OT que él subió ----------
    fun restringido(ctx: Context): Boolean = prefs(ctx).getBoolean("restringido", false)

    private fun duenos(ctx: Context): JSONObject = try {
        JSONObject(prefs(ctx).getString("duenos", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private fun guardarDuenos(ctx: Context, j: JSONObject) {
        prefs(ctx).edit().putString("duenos", j.toString()).apply()
    }

    /** Correo de quien subió la OT ("" si no se sabe); null si la OT es nueva y aún no está en la nube. */
    fun dueno(ctx: Context, id: Long): String? {
        val d = duenos(ctx)
        return if (d.has(id.toString())) d.optString(id.toString()) else null
    }

    /** ¿Esta persona puede modificar la OT? Lectores no; restringidos solo las suyas. */
    fun puedeEditar(ctx: Context, id: Long): Boolean {
        val s = Supa.sesion(ctx) ?: return false
        if (s.rol != "almacenista") return false
        if (!restringido(ctx)) return true
        val d = dueno(ctx, id) ?: return true
        return d.equals(s.email, ignoreCase = true)
    }

    // ---------- Cambios locales pendientes ----------
    @Synchronized
    fun marcarPendiente(ctx: Context, id: Long) {
        val p = conjunto(ctx, "pendientes")
        p.add(id.toString())
        guardarConjunto(ctx, "pendientes", p)
        val b = conjunto(ctx, "borradas")
        if (b.remove(id.toString())) guardarConjunto(ctx, "borradas", b)
    }

    @Synchronized
    fun marcarBorrada(ctx: Context, o: Orden) {
        val p = conjunto(ctx, "pendientes")
        if (p.remove(o.id.toString())) guardarConjunto(ctx, "pendientes", p)
        val b = conjunto(ctx, "borradas")
        b.add(o.id.toString())
        guardarConjunto(ctx, "borradas", b)
        val f = conjunto(ctx, "fotosPorBorrar")
        for (x in o.items) {
            for (nombre in x.fotos()) f.add("${o.id}/$nombre")
        }
        guardarConjunto(ctx, "fotosPorBorrar", f)
    }

    /** Fotos que se quitaron de una OT que sigue existiendo: se borran también de la nube. */
    @Synchronized
    fun marcarFotosPorBorrar(ctx: Context, ordenId: Long, nombres: List<String>) {
        if (nombres.isEmpty()) return
        val f = conjunto(ctx, "fotosPorBorrar")
        for (n in nombres) f.add("$ordenId/$n")
        guardarConjunto(ctx, "fotosPorBorrar", f)
    }

    @Synchronized
    private fun anadirPendientes(ctx: Context, ids: List<String>) {
        if (ids.isEmpty()) return
        val p = conjunto(ctx, "pendientes")
        p.addAll(ids)
        guardarConjunto(ctx, "pendientes", p)
    }

    @Synchronized
    private fun quitar(ctx: Context, clave: String, valor: String) {
        val s = conjunto(ctx, clave)
        if (s.remove(valor)) guardarConjunto(ctx, clave, s)
    }

    fun pendientes(ctx: Context): Int =
        conjunto(ctx, "pendientes").size + conjunto(ctx, "borradas").size

    // ---------- Disparadores ----------
    /** Sube los cambios 3 segundos después del último (así no se sube por cada letra escrita). */
    fun programarEnvio(ctx: Context, listo: (ResultadoSync) -> Unit) {
        val app = ctx.applicationContext
        envioProgramado?.let { ui.removeCallbacks(it) }
        val r = Runnable { sincronizar(app, false, listo) }
        envioProgramado = r
        ui.postDelayed(r, 3000L)
    }

    /** Sube lo pendiente (almacenista) y, si bajar es true, baja lo que haya en la nube. */
    fun sincronizar(ctx: Context, bajar: Boolean, listo: (ResultadoSync) -> Unit) {
        val app = ctx.applicationContext
        if (ocupado) {
            ui.postDelayed({ sincronizar(app, bajar, listo) }, 3000L)
            return
        }
        ocupado = true
        Thread {
            val res = try {
                ejecutar(app, bajar)
            } catch (e: Throwable) {
                resultadoDeError(app, e)
            }
            ocupado = false
            ui.post { listo(res) }
        }.start()
    }

    private fun tk(ctx: Context): String = Supa.tokenVigente(ctx) ?: throw IOException("Sin sesión")

    private fun ejecutar(ctx: Context, bajar: Boolean): ResultadoSync {
        val s = Supa.sesion(ctx) ?: return ResultadoSync("", true)
        val token = Supa.tokenVigente(ctx)
        if (token == null) {
            return if (Supa.sesion(ctx) == null) ResultadoSync("", true)
            else ResultadoSync("Sin conexión. Pendientes por subir: ${pendientes(ctx)}")
        }
        var rol = s.rol
        if (bajar) {
            // Si le quitaron el permiso, se cierra la sesión
            val actual = Supa.consultarRol(token)
            if (actual != "almacenista" && actual != "lector") {
                Supa.cerrarSesion(ctx)
                return ResultadoSync("", true)
            }
            if (actual != rol) {
                rol = actual
                Supa.guardarSesion(ctx, Sesion(s.access, s.refresh, s.expiraMs, s.email, actual))
            }
        }
        val editor = rol == "almacenista"
        if (bajar && editor) {
            val rest = Supa.consultarRestringido(token)
            if (rest != null) prefs(ctx).edit().putBoolean("restringido", rest).apply()
        }
        if (bajar) {
            Supa.registrarAccesoSiToca(ctx, token)
            bajarTodo(ctx, editor)
        }
        if (editor) empujar(ctx)
        var usuariosPend = -1
        if (editor && bajar) {
            usuariosPend = try {
                contarUsuariosPendientes(ctx)
            } catch (e: IOException) {
                -1
            }
        }
        val pend = pendientes(ctx)
        val hora = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val msg = if (pend > 0) "Pendientes por subir: $pend" else "Sincronizado a las $hora"
        return ResultadoSync(msg, false, usuariosPend)
    }

    /** Cuántas personas pidieron acceso y esperan aprobación. */
    private fun contarUsuariosPendientes(ctx: Context): Int {
        val r = Supa.peticion("GET", "/rest/v1/usuarios?select=email&rol=eq.pendiente", null, tk(ctx))
        if (!r.ok) return -1
        return JSONArray(r.texto).length()
    }

    private fun resultadoDeError(ctx: Context, e: Throwable): ResultadoSync {
        if (Supa.sesion(ctx) == null && e is IOException && e.message == "Sin sesión") {
            return ResultadoSync("", true)
        }
        val pend = pendientes(ctx)
        return if (e is IOException) {
            ResultadoSync("Sin conexión." + if (pend > 0) " Pendientes por subir: $pend" else "")
        } else {
            ResultadoSync("Error al sincronizar: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    // ---------- Subir ----------
    private fun empujar(ctx: Context) {
        val base = ctx.filesDir

        for (idS in conjunto(ctx, "borradas")) {
            val r = Supa.peticion("DELETE", "/rest/v1/ordenes?id=eq.$idS", null, tk(ctx), mapOf("Prefer" to "return=minimal"))
            if (!r.ok) throw IllegalStateException(Supa.errorDe(r))
            quitar(ctx, "borradas", idS)
            quitar(ctx, "sincronizadas", idS)
            val sl = sellos(ctx)
            sl.remove(idS)
            guardarSellos(ctx, sl)
        }
        for (clave in conjunto(ctx, "fotosPorBorrar")) {
            val ruta = clave.split("/").joinToString("/") { Uri.encode(it) }
            val r = Supa.peticion("DELETE", "/storage/v1/object/fotos/$ruta", null, tk(ctx))
            if (r.ok || r.codigo == 404 || r.codigo == 400) quitar(ctx, "fotosPorBorrar", clave)
        }

        for (idS in conjunto(ctx, "pendientes")) {
            val o = Store.cargar(base, idS.toLong())
            if (o == null) {
                quitar(ctx, "pendientes", idS)
                continue
            }
            // Una OT ajena no se sube (el servidor la rechazaría); se vuelve a bajar la versión de la nube
            if (!puedeEditar(ctx, o.id)) {
                quitar(ctx, "pendientes", idS)
                val sl0 = sellos(ctx)
                sl0.remove(idS)
                guardarSellos(ctx, sl0)
                continue
            }
            val fila = JSONObject().put("id", o.id).put("datos", Store.toJson(o))
            val r = Supa.peticion(
                "POST", "/rest/v1/ordenes?select=id,actualizado,creado_por", fila.toString().toByteArray(), tk(ctx),
                mapOf(
                    "Content-Type" to "application/json",
                    "Prefer" to "resolution=merge-duplicates,return=representation"
                )
            )
            if (r.codigo == 403) {
                // Sin permiso para esta OT: se descarta el cambio local y se baja la versión de la nube
                quitar(ctx, "pendientes", idS)
                val sl1 = sellos(ctx)
                sl1.remove(idS)
                guardarSellos(ctx, sl1)
                continue
            }
            if (!r.ok) throw IllegalStateException(Supa.errorDe(r))
            var sello = ""
            try {
                val fila0 = JSONArray(r.texto).getJSONObject(0)
                sello = fila0.optString("actualizado")
                val du = duenos(ctx)
                du.put(idS, if (fila0.isNull("creado_por")) "" else fila0.getString("creado_por"))
                guardarDuenos(ctx, du)
            } catch (e: Exception) {
                // se conserva lo que ya había
            }
            val fotosOk = subirFotos(ctx, o)
            val sl = sellos(ctx)
            sl.put(idS, sello)
            guardarSellos(ctx, sl)
            val sinc = conjunto(ctx, "sincronizadas")
            sinc.add(idS)
            guardarConjunto(ctx, "sincronizadas", sinc)
            // Si la OT cambió mientras subía, se queda pendiente para la siguiente vuelta
            if (fotosOk && Store.cargar(base, o.id) == o) quitar(ctx, "pendientes", idS)
        }
    }

    private fun subirFotos(ctx: Context, o: Orden): Boolean {
        var todoBien = true
        val subidas = conjunto(ctx, "fotosSubidas")
        val dir = File(ctx.filesDir, "fotos_baja")
        val nombres = o.items.flatMap { it.fotos() }
        for (nombre in nombres) {
            val clave = "${o.id}/$nombre"
            if (clave in subidas) continue
            val f = File(dir, nombre)
            if (!f.exists()) continue
            val r = Supa.peticion(
                "POST", "/storage/v1/object/fotos/${o.id}/${Uri.encode(nombre)}", f.readBytes(), tk(ctx),
                mapOf("Content-Type" to "image/jpeg", "x-upsert" to "true")
            )
            if (r.ok) {
                subidas.add(clave)
                guardarConjunto(ctx, "fotosSubidas", subidas)
            } else {
                todoBien = false
            }
        }
        return todoBien
    }

    // ---------- Bajar ----------
    private fun bajarTodo(ctx: Context, editor: Boolean) {
        val base = ctx.filesDir
        val r = Supa.peticion("GET", "/rest/v1/ordenes?select=id,actualizado,creado_por&order=id.desc", null, tk(ctx))
        if (!r.ok) throw IllegalStateException(Supa.errorDe(r))
        val lista = JSONArray(r.texto)
        val du = duenos(ctx)
        val sl = sellos(ctx)
        val pend = conjunto(ctx, "pendientes")
        val sinc = conjunto(ctx, "sincronizadas")
        val enNube = HashSet<String>()
        val aBajar = mutableListOf<String>()

        for (i in 0 until lista.length()) {
            val f = lista.getJSONObject(i)
            val id = f.get("id").toString()
            enNube.add(id)
            du.put(id, if (f.isNull("creado_por")) "" else f.getString("creado_por"))
            if (id in pend) continue
            if (sl.optString(id) != f.optString("actualizado") || Store.cargar(base, id.toLong()) == null) {
                aBajar.add(id)
            }
        }

        for (lote in aBajar.chunked(20)) {
            val rr = Supa.peticion(
                "GET", "/rest/v1/ordenes?select=id,datos,actualizado&id=in.(${lote.joinToString(",")})", null, tk(ctx)
            )
            if (!rr.ok) throw IllegalStateException(Supa.errorDe(rr))
            val arr = JSONArray(rr.texto)
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                val o = Store.fromJson(f.getJSONObject("datos"))
                Store.save(base, o)
                val id = f.get("id").toString()
                sl.put(id, f.optString("actualizado"))
                sinc.add(id)
            }
        }

        // OT locales que ya no están en la nube
        val nuevasPendientes = mutableListOf<String>()
        for (loc in Store.loadAll(base)) {
            val id = loc.id.toString()
            if (id in enNube) continue
            if (!editor) {
                Store.delete(base, loc.id)
                sinc.remove(id)
                sl.remove(id)
            } else if (id in sinc && id !in pend) {
                // la borró otra persona
                Store.delete(base, loc.id)
                sinc.remove(id)
                sl.remove(id)
            } else if (id !in pend) {
                // OT hecha antes de usar la nube: se sube
                nuevasPendientes.add(id)
            }
        }
        guardarConjunto(ctx, "sincronizadas", sinc)
        guardarSellos(ctx, sl)
        guardarDuenos(ctx, du)
        anadirPendientes(ctx, nuevasPendientes)

        for (o in Store.loadAll(base)) bajarFotos(ctx, o)
    }

    private fun bajarFotos(ctx: Context, o: Orden) {
        val dir = File(ctx.filesDir, "fotos_baja")
        dir.mkdirs()
        for (nombre in o.items.flatMap { it.fotos() }) {
            val f = File(dir, nombre)
            if (f.exists()) continue
            try {
                val r = Supa.peticion(
                    "GET", "/storage/v1/object/authenticated/fotos/${o.id}/${Uri.encode(nombre)}", null, tk(ctx)
                )
                if (r.ok && r.cuerpo.isNotEmpty()) f.writeBytes(r.cuerpo)
            } catch (e: IOException) {
                // se intenta de nuevo en la siguiente sincronización
            }
        }
    }
}
