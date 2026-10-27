package com.taller.otalmacen

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.util.UUID

@Composable
fun LoginScreen(onSesion: (Sesion) -> Unit) {
    val ctx = LocalContext.current
    val pal = paleta()
    val ui = remember { Handler(Looper.getMainLooper()) }
    var correo by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    var ocupado by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun terminar(r: Result<Sesion>) {
        ocupado = false
        r.onSuccess { onSesion(it) }
        r.onFailure { error = Supa.mensaje(it) }
    }

    fun entrarConClave() {
        if (correo.isBlank() || clave.isBlank()) {
            error = "Escribe tu correo y contraseña."
            return
        }
        ocupado = true
        error = null
        Thread {
            val r = runCatching { Supa.entrarConClave(ctx, correo, clave) }
            ui.post { terminar(r) }
        }.start()
    }

    fun entrarConGoogle() {
        if (Supa.GOOGLE_WEB_CLIENT_ID.startsWith("PEGA")) {
            error = "Falta configurar el ID de cliente de Google (GOOGLE_WEB_CLIENT_ID en Supa.kt)."
            return
        }
        ocupado = true
        error = null
        val nonceCrudo = UUID.randomUUID().toString()
        val nonceHash = MessageDigest.getInstance("SHA-256")
            .digest(nonceCrudo.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val opcion = GetSignInWithGoogleOption.Builder(Supa.GOOGLE_WEB_CLIENT_ID)
            .setNonce(nonceHash)
            .build()
        val pedido = GetCredentialRequest.Builder().addCredentialOption(opcion).build()
        CredentialManager.create(ctx).getCredentialAsync(
            ctx,
            pedido,
            null,
            ContextCompat.getMainExecutor(ctx),
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val cred = result.credential
                    if (cred is CustomCredential &&
                        cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                    ) {
                        val token = try {
                            GoogleIdTokenCredential.createFrom(cred.data).idToken
                        } catch (e: Exception) {
                            ocupado = false
                            error = "No se pudo leer la cuenta de Google."
                            return
                        }
                        Thread {
                            val r = runCatching { Supa.entrarConGoogle(ctx, token, nonceCrudo) }
                            ui.post { terminar(r) }
                        }.start()
                    } else {
                        ocupado = false
                        error = "Tipo de cuenta no compatible."
                    }
                }

                override fun onError(e: GetCredentialException) {
                    ocupado = false
                    if (e !is GetCredentialCancellationException) {
                        error = "No se pudo iniciar con Google: " + (e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Almacén OT", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Inicia sesión para ver las órdenes de trabajo.")
        Text("Si es tu primer ingreso, tu correo quedará registrado y un almacenista aprobará tu acceso.", fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { entrarConGoogle() },
            enabled = !ocupado,
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) { Text("Continuar con Google", fontSize = 18.sp) }

        Spacer(Modifier.height(12.dp))
        Text("Almacenistas: también puedes entrar con correo y contraseña", fontSize = 14.sp)
        OutlinedTextField(
            value = correo,
            onValueChange = { correo = it },
            label = { Text("Correo") },
            singleLine = true,
            enabled = !ocupado,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = clave,
            onValueChange = { clave = it },
            label = { Text("Contraseña") },
            singleLine = true,
            enabled = !ocupado,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            onClick = { entrarConClave() },
            enabled = !ocupado,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Entrar") }

        if (ocupado) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Spacer(Modifier.width(12.dp))
                Text("Entrando…")
            }
        }
        val e = error
        if (e != null) Text(e, color = pal.error)
    }
}
