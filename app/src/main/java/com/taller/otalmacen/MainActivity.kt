package com.taller.otalmacen

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("ajustes", MODE_PRIVATE)
        setContent {
            val sistema = isSystemInDarkTheme()
            var modo by remember { mutableStateOf(prefs.getInt("tema", 0)) }
            val oscuro = when (modo) {
                1 -> false
                2 -> true
                else -> sistema
            }
            val esquema = if (oscuro) {
                darkColorScheme(primary = Color(0xFF81C784), onPrimary = Color.Black)
            } else {
                lightColorScheme(primary = Color(0xFF2E7D32), onPrimary = Color.White)
            }
            CompositionLocalProvider(LocalOscuro provides oscuro) {
                MaterialTheme(colorScheme = esquema) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        App(
                            modo = modo,
                            onModo = {
                                modo = (modo + 1) % 3
                                prefs.edit().putInt("tema", modo).apply()
                            }
                        )
                    }
                }
            }
        }
    }
}

val LocalOscuro = compositionLocalOf { true }
val LocalSoloLectura = compositionLocalOf { false }

class Paleta(
    val verde: Color, val rojo: Color, val gris: Color, val texto: Color, val error: Color,
    val amarillo: Color, val azul: Color, val cafe: Color
)

@Composable
fun paleta(): Paleta =
    if (LocalOscuro.current) {
        Paleta(
            Color(0xFF2E7D32), Color(0xFFC62828), Color(0xFF2B2B2B), Color.White, Color(0xFFFF8A80),
            Color(0xFFFBC02D), Color(0xFF1565C0), Color(0xFF6D4C41)
        )
    } else {
        Paleta(
            Color(0xFFA5D6A7), Color(0xFFEF9A9A), Color(0xFFF1F1F1), Color.Black, Color(0xFFB00020),
            Color(0xFFFFF176), Color(0xFF90CAF9), Color(0xFFA1887F)
        )
    }

/** Texto negro o blanco, el que mejor contraste tenga con el fondo (así siempre se lee bien). */
fun colorSobre(fondo: Color): Color = if (fondo.luminance() > 0.179f) Color.Black else Color.White

/** Logo de TRACSA para la parte superior derecha. */
@Composable
fun LogoTracsa(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.logo_tracsa),
        contentDescription = "TRACSA",
        contentScale = ContentScale.Fit,
        modifier = modifier.size(width = 81.dp, height = 36.dp)
    )
}

@Composable
fun App(modo: Int, onModo: () -> Unit) {
    val ctx = LocalContext.current
    val base = ctx.filesDir
    var ordenes by remember { mutableStateOf(Store.loadAll(base)) }
    var actual by remember { mutableStateOf<Orden?>(null) }
    var cargando by remember { mutableStateOf(false) }
    var mensaje by remember { mutableStateOf<String?>(null) }
    var anexando by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf<Orden?>(null) }
    var revisionAnexa by remember { mutableStateOf(false) }

    var sesion by remember { mutableStateOf(Supa.sesion(ctx)) }
    var estadoSync by remember { mutableStateOf("") }
    var sincronizando by remember { mutableStateOf(false) }
    var verUsuarios by remember { mutableStateOf(false) }
    var usuariosPend by remember { mutableStateOf(0) }
    val soloLectura = sesion?.rol != "almacenista"

    fun sincronizar(bajar: Boolean) {
        if (sesion == null) return
        sincronizando = true
        Sync.sincronizar(ctx, bajar) { r ->
            sincronizando = false
            estadoSync = r.mensaje
            if (r.usuariosPendientes >= 0) usuariosPend = r.usuariosPendientes
            if (r.sinSesion) sesion = null
            ordenes = Store.loadAll(base)
        }
    }

    /** Guarda en el teléfono y, si es almacenista, programa la subida a la nube. */
    fun guardar(o: Orden) {
        Store.save(base, o)
        if (!soloLectura) {
            Sync.marcarPendiente(ctx, o.id)
            Sync.programarEnvio(ctx) { r ->
                estadoSync = r.mensaje
                if (r.sinSesion) sesion = null
            }
        }
    }

    /** Lectores no editan; un almacenista restringido solo edita las OT que él subió. */
    fun puedeEditarOrden(o: Orden): Boolean = !soloLectura && Sync.puedeEditar(ctx, o.id)

    fun borrarOrden(o: Orden) {
        if (!puedeEditarOrden(o)) return
        limpiarFotos(base, o, null)
        Store.delete(base, o.id)
        if (!soloLectura) {
            Sync.marcarBorrada(ctx, o)
            Sync.programarEnvio(ctx) { r ->
                estadoSync = r.mensaje
                if (r.sinSesion) sesion = null
            }
        }
        ordenes = Store.loadAll(base)
    }

    // Al abrir la app y cada minuto: sube lo pendiente y baja lo nuevo (si no se está editando)
    LaunchedEffect(sesion?.email) {
        while (sesion != null) {
            if (actual == null && revision == null) sincronizar(true)
            delay(60_000L)
        }
    }

    val fotoFile = remember {
        val d = File(ctx.cacheDir, "fotos")
        d.mkdirs()
        File(d, "foto.jpg")
    }
    val fotoUri = remember {
        FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", fotoFile)
    }

    val bajaFile = remember { File(File(ctx.cacheDir, "fotos"), "baja.jpg") }
    val bajaUri = remember {
        FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", bajaFile)
    }
    var piezaFotoId by remember { mutableStateOf<Long?>(null) }
    var piezaFotoTipo by remember { mutableStateOf("baja") }
    val camaraBaja = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val id = piezaFotoId
        val tipo = piezaFotoTipo
        val d = actual
        if (ok && id != null && d != null && puedeEditarOrden(d)) {
            val nombre = "${d.id}_${id}_${tipo}_${System.currentTimeMillis()}.jpg"
            val res = try {
                guardarFoto(ctx, bajaFile, nombre)
            } catch (e: Exception) {
                ""
            }
            if (res.isNotBlank()) {
                val n = d.copy(items = d.items.map { x ->
                    if (x.id != id) x
                    else if (tipo == "prestamo") x.copy(fotoPrestamo = res)
                    else x.copy(fotoBaja = res)
                })
                guardar(n)
                val quitadas = limpiarFotos(base, d, n)
                if (!soloLectura) Sync.marcarFotosPorBorrar(ctx, d.id, quitadas)
                actual = n
            } else {
                mensaje = "No se pudo guardar la foto."
            }
        }
    }

    fun procesar(uri: Uri) {
        cargando = true
        mensaje = null
        val anex = anexando
        OcrParser.leer(ctx, uri,
            { nuevo ->
                cargando = false
                revisionAnexa = anex && actual != null
                revision = nuevo
            },
            { err ->
                cargando = false
                mensaje = "No se pudo leer la foto: $err"
            })
    }

    fun guardarRevision() {
        val nuevo = revision ?: return
        val d = actual
        val anex = revisionAnexa
        val res = if (anex && d != null) fusionar(d, nuevo) else nuevo
        if (anex && d != null) {
            val agregadas = res.items.size - d.items.size
            val repetidas = nuevo.items.size - agregadas
            mensaje = "Se agregaron $agregadas piezas nuevas" +
                if (repetidas > 0) " y se omitieron $repetidas repetidas." else "."
        }
        guardar(res)
        ordenes = Store.loadAll(base)
        actual = res
        revision = null
    }

    fun procesarArchivo(uri: Uri) {
        cargando = true
        mensaje = null
        val anex = anexando
        Documentos.leer(ctx, uri,
            { nuevo ->
                cargando = false
                revisionAnexa = anex && actual != null
                revision = nuevo
            },
            { err ->
                cargando = false
                mensaje = err
            })
    }

    val camara = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) procesar(fotoUri)
    }
    val galeria = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) procesar(uri)
    }

    val archivo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) procesarArchivo(uri)
    }

    BackHandler(enabled = revision != null || actual != null || verUsuarios) {
        if (verUsuarios) {
            verUsuarios = false
        } else if (revision != null) {
            revision = null
        } else {
            actual = null
            ordenes = Store.loadAll(base)
        }
    }

    val ses = sesion
    val rev = revision
    val o = actual
    CompositionLocalProvider(LocalSoloLectura provides soloLectura) {
    if (ses == null) {
        LoginScreen(onSesion = { sesion = it })
    } else if (rev != null) {
        RevisionScreen(
            orden = rev,
            anexando = revisionAnexa,
            onCambio = { n -> revision = n },
            onGuardar = { guardarRevision() },
            onCancelar = { revision = null }
        )
    } else if (verUsuarios && !soloLectura) {
        UsuariosScreen(
            onVolver = { verUsuarios = false },
            onPendientes = { usuariosPend = it }
        )
    } else if (o == null) {
        ListaScreen(
            modo = modo,
            onModo = onModo,
            ordenes = ordenes,
            cargando = cargando,
            mensaje = mensaje,
            onFoto = { anexando = false; camara.launch(fotoUri) },
            onGaleria = { anexando = false; galeria.launch("image/*") },
            onArchivo = { anexando = false; archivo.launch(Documentos.TIPOS) },
            onNueva = {
                val nueva = Orden(id = System.currentTimeMillis())
                guardar(nueva)
                ordenes = Store.loadAll(base)
                actual = nueva
            },
            onAbrir = { actual = it },
            onBorrar = { borrarOrden(it) },
            puedeBorrar = { puedeEditarOrden(it) },
            soloLectura = soloLectura,
            correo = ses.email,
            estado = estadoSync,
            sincronizando = sincronizando,
            onActualizar = { sincronizar(true) },
            usuariosPendientes = usuariosPend,
            onUsuarios = { verUsuarios = true },
            onSalir = {
                Supa.cerrarSesion(ctx)
                sesion = null
                verUsuarios = false
            }
        )
    } else {
        val editable = puedeEditarOrden(o)
        CompositionLocalProvider(LocalSoloLectura provides !editable) {
        DetalleScreen(
            orden = o,
            onCambio = { n ->
                if (editable) {
                    guardar(n)
                    val quitadas = limpiarFotos(base, o, n)
                    Sync.marcarFotosPorBorrar(ctx, o.id, quitadas)
                    actual = n
                }
            },
            onVolver = {
                actual = null
                ordenes = Store.loadAll(base)
            },
            cargando = cargando,
            mensaje = mensaje,
            onFotoMas = { anexando = true; camara.launch(fotoUri) },
            onGaleriaMas = { anexando = true; galeria.launch("image/*") },
            onArchivoMas = { anexando = true; archivo.launch(Documentos.TIPOS) },
            onFotoPieza = { p, tipo ->
                piezaFotoId = p.id
                piezaFotoTipo = tipo
                camaraBaja.launch(bajaUri)
            },
            onExportar = { exportar(ctx, o) }
        )
        }
    }
    }
}

fun exportar(ctx: Context, o: Orden) {
    val dir = File(ctx.cacheDir, "export")
    dir.mkdirs()
    val nombre = "OT_" + o.ot.ifBlank { "sin_numero" }.replace(Regex("[^A-Za-z0-9]"), "_") + "_almacen.xlsx"
    val f = File(dir, nombre)
    XlsxWriter.escribir(o, f)
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, "Compartir Excel"))
}

fun fusionar(a: Orden, n: Orden): Orden = a.copy(
    seguro = a.seguro.ifBlank { n.seguro },
    elaboracion = a.elaboracion.ifBlank { n.elaboracion },
    entrega = a.entrega.ifBlank { n.entrega },
    ot = a.ot.ifBlank { n.ot },
    unidad = a.unidad.ifBlank { n.unidad },
    vin = a.vin.ifBlank { n.vin },
    modelo = a.modelo.ifBlank { n.modelo },
    taller = a.taller.ifBlank { n.taller },
    jefeTaller = a.jefeTaller.ifBlank { n.jefeTaller },
    items = fusionarItems(a.items, n.items).items
)

@Composable
fun ListaScreen(
    modo: Int,
    onModo: () -> Unit,
    ordenes: List<Orden>,
    cargando: Boolean,
    mensaje: String?,
    onFoto: () -> Unit,
    onGaleria: () -> Unit,
    onArchivo: () -> Unit,
    onNueva: () -> Unit,
    onAbrir: (Orden) -> Unit,
    onBorrar: (Orden) -> Unit,
    puedeBorrar: (Orden) -> Boolean,
    soloLectura: Boolean,
    correo: String,
    estado: String,
    sincronizando: Boolean,
    onActualizar: () -> Unit,
    usuariosPendientes: Int,
    onUsuarios: () -> Unit,
    onSalir: () -> Unit
) {
    val pal = paleta()
    var busqueda by remember { mutableStateOf("") }
    var porBorrar by remember { mutableStateOf<Orden?>(null) }
    val filtradas = if (busqueda.isBlank()) ordenes
    else ordenes.filter { it.coincide(busqueda) }

    val pendiente = porBorrar
    if (pendiente != null) {
        AlertDialog(
            onDismissRequest = { porBorrar = null },
            title = { Text("Eliminar OT") },
            text = {
                Text("¿Estás seguro de eliminar la OT ${pendiente.ot.ifBlank { "(sin número)" }}? Esta acción no se puede deshacer.")
            },
            confirmButton = {
                TextButton(onClick = {
                    onBorrar(pendiente)
                    porBorrar = null
                }) { Text("Sí, eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { porBorrar = null }) { Text("Cancelar") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column {
                Spacer(Modifier.height(28.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Órdenes de trabajo",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onModo) {
                        Text(
                            when (modo) {
                                1 -> "☀ Claro"
                                2 -> "☾ Oscuro"
                                else -> "◐ Auto"
                            }
                        )
                    }
                    LogoTracsa()
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    correo + " · " + (if (soloLectura) "Solo lectura" else "Almacenista"),
                    fontSize = 13.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onActualizar, enabled = !sincronizando) { Text("Actualizar") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onSalir) { Text("Salir") }
                }
                if (!soloLectura) {
                    OutlinedButton(onClick = onUsuarios, modifier = Modifier.fillMaxWidth()) {
                        Text(if (usuariosPendientes > 0) "Usuarios ($usuariosPendientes por aprobar)" else "Usuarios")
                    }
                }
                if (sincronizando) Text("Sincronizando…", fontSize = 13.sp)
                else if (estado.isNotBlank()) Text(estado, fontSize = 13.sp)
            }
        }
        if (!soloLectura) {
        item {
            Button(onClick = onFoto, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text("Tomar foto de la OT", fontSize = 18.sp)
            }
        }
        item {
            OutlinedButton(onClick = onGaleria, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Elegir foto de la galería")
            }
        }
        item {
            OutlinedButton(onClick = onArchivo, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Importar archivo (Excel, PDF, Word)")
            }
        }
        item {
            OutlinedButton(onClick = onNueva, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Nueva OT vacía")
            }
        }
        }
        item {
            OutlinedTextField(
                value = busqueda,
                onValueChange = { busqueda = it },
                label = { Text("Buscar (OT, jefe de taller, unidad, VIN…)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (cargando) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(12.dp))
                    Text("Leyendo la foto…")
                }
            }
        }
        if (mensaje != null) {
            item { Text(mensaje, color = pal.error) }
        }
        if (filtradas.isEmpty() && busqueda.isNotBlank()) {
            item { Text("No hay OT que coincidan con la búsqueda.") }
        }
        items(filtradas, key = { it.id }) { o ->
            Card(
                onClick = { onAbrir(o) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = pal.gris, contentColor = pal.texto)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("OT ${o.ot.ifBlank { "(sin número)" }}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${o.unidad} · ${o.seguro} · ${o.items.size} piezas")
                    if (puedeBorrar(o)) {
                        Row {
                            TextButton(onClick = { porBorrar = o }) { Text("Borrar") }
                        }
                    } else if (!soloLectura) {
                        Text("Solo lectura: la subió otro almacenista", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun DetalleScreen(
    orden: Orden,
    onCambio: (Orden) -> Unit,
    cargando: Boolean,
    mensaje: String?,
    onVolver: () -> Unit,
    onFotoMas: () -> Unit,
    onGaleriaMas: () -> Unit,
    onArchivoMas: () -> Unit,
    onFotoPieza: (Item, String) -> Unit,
    onExportar: () -> Unit
) {
    val pal = paleta()
    val ro = LocalSoloLectura.current
    val campos = listOf(
        CampoUi(1) { CampoTexto("OT", orden.ot) { v -> onCambio(orden.copy(ot = v)) } },
        CampoUi(1) { CampoTexto("Seguro", orden.seguro) { v -> onCambio(orden.copy(seguro = v)) } },
        CampoUi(1) { CampoFecha("Elaboración", orden.elaboracion) { v -> onCambio(orden.copy(elaboracion = v)) } },
        CampoUi(1) { CampoFecha("Entrega", orden.entrega) { v -> onCambio(orden.copy(entrega = v)) } },
        CampoUi(2) { CampoTexto("Unidad", orden.unidad) { v -> onCambio(orden.copy(unidad = v)) } },
        CampoUi(2) { CampoTexto("VIN", orden.vin) { v -> onCambio(orden.copy(vin = v.uppercase())) } },
        CampoUi(1) { CampoTexto("Modelo", orden.modelo) { v -> onCambio(orden.copy(modelo = v)) } },
        CampoUi(1) { CampoTexto("Taller", orden.taller) { v -> onCambio(orden.copy(taller = v)) } },
        CampoUi(2) { CampoTexto("Jefe de taller", orden.jefeTaller) { v -> onCambio(orden.copy(jefeTaller = v)) } }
    )

    var busq by remember { mutableStateOf("") }
    var fotoAmpliada by remember { mutableStateOf<String?>(null) }
    var porBorrarPieza by remember { mutableStateOf<Item?>(null) }
    val visibles = if (busq.isBlank()) orden.items
    else orden.items.filter { it.concepto.contains(busq.trim(), ignoreCase = true) }

    val pend = porBorrarPieza
    if (pend != null) {
        AlertDialog(
            onDismissRequest = { porBorrarPieza = null },
            title = { Text("Eliminar pieza") },
            text = {
                Text("¿Estás seguro de eliminar la pieza \"${pend.concepto.ifBlank { "(sin concepto)" }}\"? Esta acción no se puede deshacer.")
            },
            confirmButton = {
                TextButton(onClick = {
                    onCambio(orden.copy(items = orden.items.filter { x -> x.id != pend.id }))
                    porBorrarPieza = null
                }) { Text("Sí, eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { porBorrarPieza = null }) { Text("Cancelar") }
            }
        )
    }

    val ampliada = fotoAmpliada
    if (ampliada != null) {
        val quitarFoto: () -> Unit = {
            onCambio(
                orden.copy(
                    items = orden.items.map { x ->
                        x.copy(
                            fotoBaja = if (x.fotoBaja == ampliada) "" else x.fotoBaja,
                            fotoPrestamo = if (x.fotoPrestamo == ampliada) "" else x.fotoPrestamo
                        )
                    }
                )
            )
            fotoAmpliada = null
        }
        VisorFoto(
            nombre = ampliada,
            onCerrar = { fotoAmpliada = null },
            onEliminar = if (ro) null else quitarFoto
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column {
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onVolver) { Text("← Volver") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onExportar) { Text("Exportar Excel") }
                    Spacer(Modifier.width(8.dp))
                    LogoTracsa()
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ro) {
                Button(onClick = onFotoMas, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Capturar más fotos", fontSize = 17.sp)
                }
                OutlinedButton(onClick = onGaleriaMas, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("Agregar desde galería")
                }
                OutlinedButton(onClick = onArchivoMas, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("Agregar desde archivo (Excel, PDF, Word)")
                }
                }
                if (cargando) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Spacer(Modifier.width(12.dp))
                        Text("Leyendo la foto…")
                    }
                }
                if (mensaje != null) {
                    Text(mensaje, color = pal.error)
                }
            }
        }
        item { EncabezadoResponsivo(campos) }
        item {
            OutlinedTextField(
                value = busq,
                onValueChange = { busq = it },
                label = { Text("Buscar por concepto") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            val titulo = if (busq.isBlank()) "Piezas (${orden.items.size})"
            else "Piezas (${visibles.size} de ${orden.items.size})"
            Text(titulo, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        if (visibles.isEmpty() && busq.isNotBlank()) {
            item { Text("No hay piezas que coincidan con la búsqueda.") }
        }
        items(visibles, key = { it.id }) { p ->
            FilaPieza(
                p = p,
                onChange = { n ->
                    onCambio(orden.copy(items = orden.items.map { x -> if (x.id == n.id) n else x }))
                },
                onBorrar = { porBorrarPieza = p },
                onFoto = { tipo -> onFotoPieza(p, tipo) },
                onVerFoto = { nombre -> fotoAmpliada = nombre }
            )
        }
        if (!ro) {
        item {
            OutlinedButton(
                onClick = {
                    val nuevo = Item(id = System.currentTimeMillis(), pz = "1", concepto = "", num = (orden.items.maxOfOrNull { x -> x.num } ?: 0) + 1)
                    onCambio(orden.copy(items = orden.items + nuevo))
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Agregar pieza manualmente") }
        }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

/** Casilla con su texto; todo el renglón se puede tocar. Los colores se calculan según el fondo de la pieza. */
@Composable
fun Casilla(
    texto: String,
    marcada: Boolean,
    habilitada: Boolean,
    color: Color,
    fondo: Color,
    onCambio: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(enabled = habilitada) { onCambio(!marcada) }
    ) {
        Checkbox(
            checked = marcada,
            onCheckedChange = null,
            enabled = habilitada,
            colors = CheckboxDefaults.colors(
                checkedColor = color,
                uncheckedColor = color,
                checkmarkColor = fondo,
                disabledCheckedColor = color.copy(alpha = 0.6f),
                disabledUncheckedColor = color.copy(alpha = 0.6f)
            )
        )
        Text(texto, fontSize = 14.sp, maxLines = 1, color = color)
        Spacer(Modifier.width(6.dp))
    }
}

@Composable
fun FilaPieza(
    p: Item,
    onChange: (Item) -> Unit,
    onBorrar: () -> Unit,
    onFoto: (String) -> Unit,
    onVerFoto: (String) -> Unit
) {
    val pal = paleta()
    val ro = LocalSoloLectura.current
    val fondo = when {
        p.alta -> pal.verde
        p.baja -> pal.rojo
        p.prestamo -> pal.amarillo
        p.devolucion -> pal.azul
        p.sobrante -> pal.cafe
        else -> pal.gris
    }
    // Texto negro o blanco según el fondo, para que siempre se lea bien
    val txt = colorSobre(fondo)
    val campo = OutlinedTextFieldDefaults.colors(
        focusedTextColor = txt,
        unfocusedTextColor = txt,
        focusedLabelColor = txt,
        unfocusedLabelColor = txt.copy(alpha = 0.8f),
        focusedBorderColor = txt,
        unfocusedBorderColor = txt.copy(alpha = 0.55f),
        cursorColor = txt
    )
    val botonTexto = ButtonDefaults.textButtonColors(contentColor = txt)
    val botonBorde = ButtonDefaults.outlinedButtonColors(contentColor = txt)
    val lineaBoton = BorderStroke(1.dp, txt.copy(alpha = 0.6f))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = fondo, contentColor = txt)
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    readOnly = ro,
                    value = if (p.num > 0) p.num.toString() else "",
                    onValueChange = { v -> onChange(p.copy(num = v.filter { c -> c.isDigit() }.take(3).toIntOrNull() ?: 0)) },
                    label = { Text("No.") },
                    singleLine = true,
                    colors = campo,
                    modifier = Modifier.width(72.dp)
                )
                OutlinedTextField(
                    readOnly = ro,
                    value = p.pz,
                    onValueChange = { v -> onChange(p.copy(pz = v)) },
                    label = { Text("PZ") },
                    singleLine = true,
                    colors = campo,
                    modifier = Modifier.width(80.dp)
                )
                OutlinedTextField(
                    readOnly = ro,
                    value = p.concepto,
                    onValueChange = { v -> onChange(p.copy(concepto = v)) },
                    label = { Text("Concepto") },
                    colors = campo,
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                readOnly = ro,
                value = p.proveedor,
                onValueChange = { v -> onChange(p.copy(proveedor = v)) },
                label = { Text("Proveedor") },
                singleLine = true,
                colors = campo,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Casilla("Alta", p.alta, !ro, txt, fondo) { v -> onChange(p.conEstado("alta", v)) }
                Casilla("Baja", p.baja, !ro, txt, fondo) { v -> onChange(p.conEstado("baja", v)) }
                Casilla("Préstamo", p.prestamo, !ro, txt, fondo) { v -> onChange(p.conEstado("prestamo", v)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Casilla("Devolución", p.devolucion, !ro, txt, fondo) { v -> onChange(p.conEstado("devolucion", v)) }
                Casilla("Sobrante", p.sobrante, !ro, txt, fondo) { v -> onChange(p.conEstado("sobrante", v)) }
                Spacer(Modifier.weight(1f))
                if (!ro) TextButton(onClick = onBorrar, colors = botonTexto) { Text("Borrar") }
            }
            if (p.baja) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!ro) {
                        OutlinedButton(onClick = { onFoto("baja") }, colors = botonBorde, border = lineaBoton) {
                            Text("📷 Foto de salida")
                        }
                    }
                    if (p.fotoBaja.isNotBlank()) {
                        MiniFoto(p.fotoBaja) { onVerFoto(p.fotoBaja) }
                    }
                }
            }
            if (p.prestamo) {
                OutlinedTextField(
                    readOnly = ro,
                    value = p.descPrestamo,
                    onValueChange = { v -> onChange(p.copy(descPrestamo = v)) },
                    label = { Text("Descripción del préstamo") },
                    minLines = 2,
                    maxLines = 5,
                    colors = campo,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!ro) {
                        OutlinedButton(onClick = { onFoto("prestamo") }, colors = botonBorde, border = lineaBoton) {
                            Text("📷 Foto del préstamo")
                        }
                    }
                    if (p.fotoPrestamo.isNotBlank()) {
                        MiniFoto(p.fotoPrestamo) { onVerFoto(p.fotoPrestamo) }
                    }
                }
            }
            if (p.devolucion) {
                OutlinedTextField(
                    readOnly = ro,
                    value = p.descDevolucion,
                    onValueChange = { v -> onChange(p.copy(descDevolucion = v)) },
                    label = { Text("Descripción de la devolución") },
                    minLines = 2,
                    maxLines = 5,
                    colors = campo,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private const val MAX_LADO_FOTO = 1280
private const val MAX_BYTES_FOTO = 250_000

/**
 * Comprime la foto para que ocupe poco: la reduce a 1280 px por el lado largo, corrige su rotación
 * y la guarda como JPEG bajando la calidad hasta que pese unos 250 KB o menos. Devuelve el nombre del archivo.
 */
fun guardarFoto(ctx: Context, origen: File, nombre: String): String {
    val dir = File(ctx.filesDir, "fotos_baja")
    dir.mkdirs()
    val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(origen.path, medidas)
    if (medidas.outWidth <= 0 || medidas.outHeight <= 0) return ""
    var muestra = 1
    while (maxOf(medidas.outWidth, medidas.outHeight) / muestra > MAX_LADO_FOTO * 2) muestra *= 2
    val opciones = BitmapFactory.Options().apply { inSampleSize = muestra }
    var bmp: Bitmap = BitmapFactory.decodeFile(origen.path, opciones) ?: return ""
    val orient = try {
        ExifInterface(origen.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }
    val grados = when (orient) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (grados != 0f) {
        val m = Matrix()
        m.postRotate(grados)
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
    val lado = maxOf(bmp.width, bmp.height)
    if (lado > MAX_LADO_FOTO) {
        val escala = MAX_LADO_FOTO.toFloat() / lado.toFloat()
        val an = (bmp.width * escala).toInt().coerceAtLeast(1)
        val al = (bmp.height * escala).toInt().coerceAtLeast(1)
        bmp = Bitmap.createScaledBitmap(bmp, an, al, true)
    }
    var calidad = 75
    var bytes = ByteArray(0)
    while (true) {
        val salida = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, calidad, salida)
        bytes = salida.toByteArray()
        if (bytes.size <= MAX_BYTES_FOTO || calidad <= 40) break
        calidad -= 10
    }
    File(dir, nombre).writeBytes(bytes)
    return nombre
}

/** Borra las fotos que ya no pertenecen a ninguna pieza y devuelve sus nombres. */
fun limpiarFotos(base: File, viejo: Orden, nuevo: Orden?): List<String> {
    val quedan = nuevo?.items?.flatMap { it.fotos() }?.toSet() ?: emptySet()
    val quitadas = mutableListOf<String>()
    for (p in viejo.items) {
        for (f in p.fotos()) {
            if (f !in quedan) {
                File(File(base, "fotos_baja"), f).delete()
                quitadas.add(f)
            }
        }
    }
    return quitadas
}

@Composable
fun MiniFoto(nombre: String, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val bmp = remember(nombre) {
        val f = File(File(ctx.filesDir, "fotos_baja"), nombre)
        if (f.exists()) {
            val o = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(f.path, o)?.asImageBitmap()
        } else {
            null
        }
    }
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = "Foto",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onClick() }
        )
    }
}

/** Foto en grande. Si se puede editar, trae el botón Eliminar (pide confirmación). */
@Composable
fun VisorFoto(nombre: String, onCerrar: () -> Unit, onEliminar: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    var confirmar by remember { mutableStateOf(false) }
    val bmp = remember(nombre) {
        BitmapFactory.decodeFile(File(File(ctx.filesDir, "fotos_baja"), nombre).path)?.asImageBitmap()
    }
    Dialog(
        onDismissRequest = onCerrar,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = "Foto ampliada",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            TextButton(
                onClick = onCerrar,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 8.dp)
            ) {
                Text("✕", fontSize = 30.sp, color = Color.White)
            }
            if (onEliminar != null) {
                Button(
                    onClick = { confirmar = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Color.White),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                ) {
                    Text("🗑 Eliminar foto")
                }
            }
        }
    }
    if (confirmar && onEliminar != null) {
        AlertDialog(
            onDismissRequest = { confirmar = false },
            title = { Text("Eliminar foto") },
            text = { Text("¿Estás seguro de eliminar esta foto? Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmar = false
                    onEliminar()
                }) { Text("Sí, eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmar = false }) { Text("Cancelar") }
            }
        )
    }
}

class CampoUi(val span: Int, val contenido: @Composable () -> Unit)

@Composable
fun CampoTexto(label: String, valor: String, onCambio: (String) -> Unit) {
    OutlinedTextField(
        value = valor,
        onValueChange = onCambio,
        readOnly = LocalSoloLectura.current,
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun CampoFecha(label: String, valor: String, onCambio: (String) -> Unit) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = valor,
            onValueChange = {},
            readOnly = true,
            label = { Text(label, maxLines = 1) },
            singleLine = true,
            trailingIcon = { Text("📅") },
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            Modifier
                .matchParentSize()
                .clickable(enabled = !LocalSoloLectura.current) {
                    val hoy = java.util.Calendar.getInstance()
                    val p = fechaAPartes(valor)
                    val dia = p?.first ?: hoy.get(java.util.Calendar.DAY_OF_MONTH)
                    val mes = (p?.second ?: (hoy.get(java.util.Calendar.MONTH) + 1)) - 1
                    val anio = p?.third ?: hoy.get(java.util.Calendar.YEAR)
                    DatePickerDialog(
                        ctx,
                        { _, a, m, d -> onCambio("%02d/%02d/%04d".format(d, m + 1, a)) },
                        anio, mes, dia
                    ).show()
                }
        )
    }
}

/** Acomoda los cuadros del encabezado en columnas según el ancho de la pantalla. */
@Composable
fun EncabezadoResponsivo(campos: List<CampoUi>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val n = ((maxWidth + 8.dp) / 158.dp).toInt().coerceIn(1, 4)
        val filas = mutableListOf<MutableList<CampoUi>>()
        var fila = mutableListOf<CampoUi>()
        var usados = 0
        for (c in campos) {
            val sp = minOf(c.span, n)
            if (usados + sp > n) {
                filas.add(fila)
                fila = mutableListOf()
                usados = 0
            }
            fila.add(c)
            usados += sp
        }
        if (fila.isNotEmpty()) filas.add(fila)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in filas) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    var suma = 0
                    for (c in f) {
                        val sp = minOf(c.span, n)
                        suma += sp
                        Box(Modifier.weight(sp.toFloat())) { c.contenido() }
                    }
                    if (suma < n) Spacer(Modifier.weight((n - suma).toFloat()))
                }
            }
        }
    }
}
