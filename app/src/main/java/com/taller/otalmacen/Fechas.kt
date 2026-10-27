package com.taller.otalmacen

private val MESES = listOf("ENE", "FEB", "MAR", "ABR", "MAY", "JUN", "JUL", "AGO", "SEP", "OCT", "NOV", "DIC")
private val FECHA_RX = Regex("(\\d{1,2})\\s*[/\\-.]\\s*(\\d{1,2}|[A-ZÁÉÍÓÚ]{3,10})\\s*[/\\-.]\\s*(\\d{2,4})")

/** Devuelve (día, mes, año) o null si el texto no es una fecha. Entiende "20/10/2026" y "13/ENERO/2027". */
fun fechaAPartes(s: String): Triple<Int, Int, Int>? {
    val m = FECHA_RX.find(s.uppercase()) ?: return null
    val dia = m.groupValues[1].toIntOrNull() ?: return null
    val mesTxt = m.groupValues[2]
    val mes = mesTxt.toIntOrNull() ?: (MESES.indexOf(mesTxt.take(3)) + 1)
    var anio = m.groupValues[3].toIntOrNull() ?: return null
    if (anio < 100) anio += 2000
    if (dia !in 1..31 || mes !in 1..12) return null
    return Triple(dia, mes, anio)
}

/** Deja la fecha siempre como dd/MM/aaaa. Si no es una fecha reconocible, regresa el texto igual. */
fun normalizarFecha(s: String): String {
    val p = fechaAPartes(s) ?: return s.trim()
    return "%02d/%02d/%04d".format(p.first, p.second, p.third)
}
