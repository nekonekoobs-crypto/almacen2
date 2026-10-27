package com.taller.otalmacen

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class UsuarioFila(
    val email: String,
    val rol: String,
    val nombre: String,
    val creado: String,
    val ultimo: String
)

private fun texto(x: JSONObject, k: String): String = if (x.isNull(k)) "" else x.optString(k)

@Composable
private fun TarjetaUsuario(u: UsuarioFila, acciones: @Composable RowScope.() -> Unit) {
    val pal = paleta()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = pal.gris, contentColor = pal.texto)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(u.nombre.ifBlank { "(sin nombre)" }, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(u.email)
            val ingreso = "Primer ingreso: " + u.creado.take(10) +
                (if (u.ultimo.isNotBlank()) " · Último: " + u.ultimo.take(10) else "")
            Text(ingreso, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                acciones()
            }
        }
    }
}

/** Para almacenistas: ver quién pidió acceso y aprobarlo, rechazarlo o quitarlo. */
@Composable
fun UsuariosScreen(onVolver: () -> Unit, onPendientes: (Int) -> Unit) {
    val ctx = LocalContext.current
    val pal = paleta()
    val ui = remember { Handler(Looper.getMainLooper()) }
    var lista by remember { mutableStateOf<List<UsuarioFila>>(emptyList()) }
    var cargando by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun cargar() {
        cargando = true
        error = null
        Thread {
            val r = runCatching {
                val token = Supa.tokenVigente(ctx) ?: throw IOException("Sin sesión")
                val resp = Supa.peticion(
                    "GET",
                    "/rest/v1/usuarios?select=email,rol,nombre,creado,ultimo_acceso&order=creado.desc",
                    null, token
                )
                if (!resp.ok) throw IllegalStateException(Supa.errorDe(resp))
                val arr = JSONArray(resp.texto)
                (0 until arr.length()).map { i ->
                    val x = arr.getJSONObject(i)
                    UsuarioFila(
                        texto(x, "email"), texto(x, "rol"), texto(x, "nombre"),
                        texto(x, "creado"), texto(x, "ultimo_acceso")
                    )
                }
            }
            ui.post {
                cargando = false
                r.onSuccess { l ->
                    lista = l
                    onPendientes(l.count { u -> u.rol == "pendiente" })
                }
                r.onFailure { e -> error = Supa.mensaje(e) }
            }
        }.start()
    }

    fun cambiar(email: String, rol: String) {
        cargando = true
        error = null
        Thread {
            val r = runCatching {
                val token = Supa.tokenVigente(ctx) ?: throw IOException("Sin sesión")
                val cuerpo = JSONObject().put("p_email", email).put("p_rol", rol).toString().toByteArray()
                val resp = Supa.peticion(
                    "POST", "/rest/v1/rpc/cambiar_rol", cuerpo, token,
                    mapOf("Content-Type" to "application/json")
                )
                if (!resp.ok) throw IllegalStateException(Supa.errorDe(resp))
            }
            ui.post {
                r.onFailure { e -> error = Supa.mensaje(e) }
                cargar()
            }
        }.start()
    }

    LaunchedEffect(Unit) { cargar() }

    val pendientes = lista.filter { it.rol == "pendiente" }
    val conAcceso = lista.filter { it.rol == "lector" || it.rol == "almacenista" }
    val rechazados = lista.filter { it.rol == "rechazado" }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onVolver) { Text("← Volver") }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { cargar() }, enabled = !cargando) { Text("Actualizar") }
                }
                Text("Usuarios", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Quien entra por primera vez queda en \"Por aprobar\" hasta que lo apruebes.", fontSize = 13.sp)
                if (cargando) Text("Cargando…", fontSize = 13.sp)
                val e = error
                if (e != null) Text(e, color = pal.error)
            }
        }

        item { Text("Por aprobar (${pendientes.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        if (pendientes.isEmpty()) item { Text("No hay solicitudes pendientes.") }
        items(pendientes, key = { "p" + it.email }) { u ->
            TarjetaUsuario(u) {
                Button(onClick = { cambiar(u.email, "lector") }, enabled = !cargando) { Text("Aprobar") }
                OutlinedButton(onClick = { cambiar(u.email, "rechazado") }, enabled = !cargando) { Text("Rechazar") }
            }
        }

        item { Text("Con acceso (${conAcceso.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        items(conAcceso, key = { "a" + it.email }) { u ->
            TarjetaUsuario(u) {
                if (u.rol == "almacenista") {
                    Text("Almacenista (control total)")
                } else {
                    Text("Solo lectura")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { cambiar(u.email, "rechazado") }, enabled = !cargando) {
                        Text("Quitar acceso")
                    }
                }
            }
        }

        if (rechazados.isNotEmpty()) {
            item { Text("Rechazados (${rechazados.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            items(rechazados, key = { "r" + it.email }) { u ->
                TarjetaUsuario(u) {
                    Button(onClick = { cambiar(u.email, "lector") }, enabled = !cargando) { Text("Aprobar") }
                }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}
