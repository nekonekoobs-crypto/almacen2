# OT Almacén

App Android para registrar órdenes de trabajo desde una foto:
- Lee la hoja con OCR (ML Kit, sin internet) y llena encabezado y piezas.
- Todo es editable. PROVEEDOR es texto libre; no hay costos.
- Alta = renglón verde, Baja = renglón rojo.
- Exporta a Excel (.xlsx) con los mismos colores y EXISTENCIA = ALTA − BAJA.

## Obtener la APK
1. Sube este proyecto a GitHub (rama `main`).
2. Pestaña **Actions → Build APK**. Si no corrió sola, pulsa **Run workflow**.
3. Al terminar, descarga el artefacto **app-debug** (un .zip con `app-debug.apk`).
