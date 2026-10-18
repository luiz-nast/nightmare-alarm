package com.implacavel.alarme

import org.junit.Assert.assertEquals
import org.junit.Test

/** O que a Escuta entrega à IA (RegrasConversa.kt) e a medida do teste da escuta (TesteDaEscuta.kt). */
class EscutaTest {
    @Test
    fun aFalaVaiComAsOutrasHipotesesDoReconhecedor() {
        assertEquals("Nice, I slept well", falaComAlternativas("Nice, I slept well", emptyList()))
        // Iguais à fala (mesmo de outro jeito) e repetidas ficam de fora; no máximo duas
        assertEquals("Nice, I slept well", falaComAlternativas("Nice, I slept well", listOf("nice, i slept well", " Nice, I slept well ")))
        assertEquals(
            "Nice, I slept well\n(The speech recognizer also heard: \"Knife, I slept well\" / \"Nice, I slept while\".)",
            falaComAlternativas("Nice, I slept well", listOf("Knife, I slept well", "Nice, I slept while", "Knife, I slept well", "Mice, I slept well")),
        )
    }

    @Test
    fun taxaDeErroPorPalavra() {
        assertEquals(0.0, taxaDeErro("I slept well but I am still a little tired", "I slept well, but I'm still a little tired."), 1e-9)
        // Uma troca em 5 palavras
        assertEquals(0.2, taxaDeErro("coffee and bread with butter", "coffee and bread with water"), 1e-9)
        // Uma falta e uma sobra
        assertEquals(0.4, taxaDeErro("coffee and bread with butter", "coffee bread with butter now"), 1e-9)
        assertEquals(1.0, taxaDeErro("coffee and bread", ""), 1e-9)
        assertEquals(0.0, taxaDeErro("", ""), 1e-9)
    }
}
