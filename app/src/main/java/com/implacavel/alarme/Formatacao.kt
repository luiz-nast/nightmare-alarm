// Textos de hora, dias e tempo que falta mostrados na tela. Funções puras, testadas em FormatacaoTest.
package com.implacavel.alarme

import java.text.Normalizer
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.roundToInt

/** Ordem dos dias na tela: domingo primeiro, como nos calendários brasileiros (e americanos). */
val ORDEM_DIAS = listOf(7, 1, 2, 3, 4, 5, 6)

val NOME_CURTO_DIA = mapOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun")

val LETRA_DIA = mapOf(1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S", 7 to "S")

fun hhmm(hora: Int, minuto: Int): String = String.format(Locale.ROOT, "%02d:%02d", hora, minuto)

fun hhmm(hora: LocalTime): String = hhmm(hora.hour, hora.minute)

/** "Once", "Every day", "Weekdays", "Weekends" ou a lista, como "Sun, Wed". */
fun resumoDias(dias: Set<Int>): String = when {
    dias.isEmpty() -> "Once"
    dias.size == 7 -> "Every day"
    dias == setOf(1, 2, 3, 4, 5) -> "Weekdays"
    dias == setOf(6, 7) -> "Weekends"
    else -> ORDEM_DIAS.filter { it in dias }.joinToString(", ") { NOME_CURTO_DIA.getValue(it) }
}

/** "in 9 h 12 min", arredondando pra cima até o minuto seguinte. */
fun tempoAte(agora: ZonedDateTime, quando: ZonedDateTime): String {
    val minutos = ((Duration.between(agora, quando).seconds + 59) / 60).coerceAtLeast(0)
    val d = minutos / (24 * 60)
    val h = minutos / 60 % 24
    val m = minutos % 60
    val partes = buildList {
        if (d > 0) add("$d d")
        if (h > 0) add("$h h")
        if (m > 0 || isEmpty()) add("$m min")
    }
    return "in " + partes.joinToString(" ")
}

/** Tempo que falta no anel da câmera, arredondando pra cima até o segundo: "19:42" a partir de 1 min, senão "28 s". */
fun tempoRestante(ms: Long): String {
    val s = ((ms + 999) / 1000).coerceAtLeast(0)
    return if (s >= 60) String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60) else "$s s"
}

/** Uma fração (0,3) em porcentagem inteira pra tela (30). */
fun porcento(fracao: Float): Int = (fracao * 100).roundToInt()

/** Minúsculas, sem acento ("não" vira "nao") e com o apóstrofo comum, pra comparar fala (o "stop", as frases, a conversa). */
fun semAcento(texto: String): String =
    Normalizer.normalize(texto.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").replace("’", "'")
