package com.implacavel.alarme

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Textos de hora, dias e tempo que falta (Formatacao.kt). */
class FormatacaoTest {
    private val zona = ZoneId.of("America/Sao_Paulo")

    private fun em(mes: Int, dia: Int, hora: Int, minuto: Int) = ZonedDateTime.of(2026, mes, dia, hora, minuto, 0, 0, zona)

    @Test
    fun horaComDoisDigitos() {
        assertEquals("07:05", hhmm(7, 5))
    }

    @Test
    fun resumoDosDias() {
        assertEquals("Once", resumoDias(emptySet()))
        assertEquals("Every day", resumoDias((1..7).toSet()))
        assertEquals("Weekdays", resumoDias(setOf(1, 2, 3, 4, 5)))
        assertEquals("Weekends", resumoDias(setOf(6, 7)))
        assertEquals("Sun, Wed", resumoDias(setOf(3, 7)))
    }

    @Test
    fun tempoAteOAlarme() {
        assertEquals("in 9 h 12 min", tempoAte(em(9, 29, 21, 48), em(9, 30, 7, 0)))
        assertEquals("in 1 min", tempoAte(em(9, 29, 21, 48).plusSeconds(10), em(9, 29, 21, 49)))
        assertEquals("in 2 d 1 h", tempoAte(em(9, 29, 6, 0), em(10, 1, 7, 0)))
    }

    @Test
    fun tempoQueFaltaNoAnel() {
        assertEquals("20:00", tempoRestante(20 * 60_000L))
        assertEquals("19:42", tempoRestante(19 * 60_000L + 41_001)) // arredonda pra cima
        assertEquals("1:00", tempoRestante(59_001))
        assertEquals("59 s", tempoRestante(59_000))
        assertEquals("30 s", tempoRestante(30_000))
        assertEquals("0 s", tempoRestante(0))
    }
}
