package com.taller.otalmacen

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Parte un concepto en dos por la mitad, sin cortar palabras. */
private fun mitades(texto: String): Pair<String, String> {
    val t = texto.trim()
    val medio = t.length / 2
    val izq = t.lastIndexOf(' ', medio)
    val der = t.indexOf(' ', medio)
    if (izq == -1 && der == -1) return Pair(t, "")
    val corte = when {
        izq == -1 -> der
        der == -1 -> izq
        medio - izq <= der - medio -> izq
        else -> der
    }
    return Pair(t.substring(0, corte).trim(), t.substring(corte).trim())
}

/** Ordena por No. de renglón; las piezas sin No. se quedan detrás de la pieza que traían antes. */
private fun ordenarPorNumero(lista: List<Item>): List<Item> {
    var ult = 0.0
    val clave = lista.map { x ->
        if (x.num > 0) {
            ult = x.num.toDouble()
        } else {
            ult += 0.01
        }
        ult
    }
    return lista.indices.sortedBy { clave[it] }.map { lista[it] }
}

/**
 * Revisión antes de guardar lo leído de una foto (cámara o galería):
 * permite corregir, dividir piezas que salieron juntas, unir las que se partieron y enumerar.
 */
@Composable
fun RevisionScreen(
    orden: Orden,
    anexando: Boolean,
    onCambio: (Orden) -> Unit,
    onGuardar: () -> Unit,
    onCancelar: () -> Unit
) {
    val pal = paleta()
    val lista = orden.items
    var dividir by remember { mutableStateOf<Item?>(null) }

    val cambiar: (Item) -> Unit = { n ->
        onCambio(orden.copy(items = lista.map { x -> if (x.id == n.id) n else x }))
    }

    val d = dividir
    if (d != null) {
        val mitad = mitades(d.concepto)
        var a by remember(d.id) { mutableStateOf(mitad.first) }
        var b by remember(d.id) { mutableStateOf(mitad.second) }
        AlertDialog(
            onDismissRequest = { dividir = null },
            title = { Text("Dividir pieza") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Deja cada pieza en su propio campo:")
                    OutlinedTextField(
                        value = a,
                        onValueChange = { v -> a = v },
                        label = { Text("Pieza 1") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = b,
                        onValueChange = { v -> b = v },
                        label = { Text("Pieza 2") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val nuevoId = (lista.maxOfOrNull { x -> x.id } ?: 0L) + 1
                    val resultado = lista.flatMap { x ->
                        if (x.id == d.id) {
                            listOf(
                                x.copy(concepto = a.trim()),
                                Item(id = nuevoId, pz = "1", concepto = b.trim())
                            )
                        } else {
                            listOf(x)
                        }
                    }
                    onCambio(orden.copy(items = resultado))
                    dividir = null
                }) { Text("Dividir") }
            },
            dismissButton = {
                TextButton(onClick = { dividir = null }) { Text("Cancelar") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onCancelar, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text("Cancelar")
                    }
                    Button(onClick = onGuardar, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text(if (anexando) "Agregar a la OT" else "Guardar OT")
                    }
                }
                Text("Revisar piezas (${lista.size})", fontWeight = FontWeight.Bold)
                Text("Antes de guardar: divide las piezas que salieron juntas, une las que se partieron y enumera para fijar el orden.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onCambio(orden.copy(items = ordenarPorNumero(lista))) }) {
                        Text("Ordenar por No.")
                    }
                    OutlinedButton(onClick = {
                        onCambio(orden.copy(items = lista.mapIndexed { i, x -> x.copy(num = i + 1) }))
                    }) { Text("Enumerar 1 a ${lista.size}") }
                }
            }
        }
        items(lista, key = { it.id }) { p ->
            val idx = lista.indexOfFirst { x -> x.id == p.id }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = pal.gris, contentColor = pal.texto)
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = if (p.num > 0) p.num.toString() else "",
                            onValueChange = { v ->
                                cambiar(p.copy(num = v.filter { c -> c.isDigit() }.take(3).toIntOrNull() ?: 0))
                            },
                            label = { Text("No.") },
                            singleLine = true,
                            modifier = Modifier.width(72.dp)
                        )
                        OutlinedTextField(
                            value = p.pz,
                            onValueChange = { v -> cambiar(p.copy(pz = v)) },
                            label = { Text("PZ") },
                            singleLine = true,
                            modifier = Modifier.width(72.dp)
                        )
                        OutlinedTextField(
                            value = p.concepto,
                            onValueChange = { v -> cambiar(p.copy(concepto = v)) },
                            label = { Text("Concepto") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (p.num == 0) {
                        Text(
                            "Sin No.: si es una pieza aparte déjala; si es parte de la anterior, únela.",
                            color = pal.error
                        )
                    } else if (p.concepto.length >= 40) {
                        Text("Concepto largo: revisa que no sean 2 piezas.", color = pal.error)
                    }
                    Row {
                        TextButton(onClick = { dividir = p }) { Text("Dividir") }
                        if (idx > 0) {
                            TextButton(onClick = {
                                val ant = lista[idx - 1]
                                onCambio(
                                    orden.copy(
                                        items = lista.filter { x -> x.id != p.id }.map { x ->
                                            if (x.id == ant.id) x.copy(concepto = (ant.concepto + " " + p.concepto).trim()) else x
                                        }
                                    )
                                )
                            }) { Text("Unir con anterior") }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            onCambio(orden.copy(items = lista.filter { x -> x.id != p.id }))
                        }) { Text("Quitar") }
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    val nuevoId = (lista.maxOfOrNull { x -> x.id } ?: 0L) + 1
                    val nuevo = Item(id = nuevoId, pz = "1", concepto = "", num = (lista.maxOfOrNull { x -> x.num } ?: 0) + 1)
                    onCambio(orden.copy(items = lista + nuevo))
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Agregar pieza") }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}
