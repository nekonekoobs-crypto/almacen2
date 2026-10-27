# Configuración de Supabase y Google (una sola vez)

## 1. Base de datos
1. Supabase > SQL Editor > New query.
2. Pega todo `esquema.sql` y presiona Run (se puede volver a ejecutar sin problema).
3. No tienes que dar de alta a jefes y oficinas: cuando inicien sesión por primera vez se guardan solos
   (correo, nombre y fecha) como **pendientes**. Un almacenista los aprueba desde la app, en el botón
   **Usuarios** (Aprobar = solo lectura, Rechazar = sin acceso).
4. Más almacenistas: solo se crean desde el SQL (hay un ejemplo comentado en el script).

## 2. Google (botón "Continuar con Google")
1. console.cloud.google.com > crea un proyecto.
2. APIs y servicios > Pantalla de consentimiento OAuth > Externo. Llena nombre y correo.
   Publícala ("Publish app"); solo usa permisos básicos, no requiere revisión.
3. Credenciales > Crear credenciales > ID de cliente de OAuth > **Aplicación web**.
   - URI de redireccionamiento autorizado: `https://rrxwhelqvbxiwqfcpwus.supabase.co/auth/v1/callback`
   - Copia el **ID de cliente** y el **secreto**.
4. Credenciales > Crear credenciales > ID de cliente de OAuth > **Android**.
   - Nombre del paquete: `com.taller.otalmacen`
   - Huella SHA-1: `95:0C:AB:1B:5A:79:E9:71:3D:C7:02:D4:5A:72:BF:BA:AE:FA:34:30`
     (es la de `debug.keystore` de este repositorio; si cambias esa llave, cambia la huella)
5. Supabase > Authentication > Sign In / Providers > Google: actívalo y pega
   el ID de cliente web y el secreto. Deja apagado "Skip nonce checks".
6. En `app/src/main/java/com/taller/otalmacen/Supa.kt` reemplaza
   `PEGA_AQUI_EL_CLIENT_ID_WEB` por el ID de cliente **web** (termina en `.apps.googleusercontent.com`).

## 3. Almacenistas con correo y contraseña (opcional)
Authentication > Users > Add user (marca "Auto Confirm User") y agrega el correo en `usuarios`
con rol `almacenista` (ver el ejemplo comentado en `esquema.sql`). También pueden entrar con Google
si su correo es de Google.

## Notas
- Deja encendido "Allow new users to sign up" (Google lo necesita). Aunque alguien se registre,
  no ve nada si su correo no está en `usuarios`.
- Deja encendido "Confirm email" en el proveedor Email.
- Nunca pongas la llave `service_role` o `secret` en la app.
