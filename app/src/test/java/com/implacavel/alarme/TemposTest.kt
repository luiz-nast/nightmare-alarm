package com.implacavel.alarme

import org.junit.Assert.assertEquals
import org.junit.Test

/** Os tempos e o volume dos despertadores que o dono ajusta (Tempos.kt). */
class TemposTest {

    @Test
    fun deComecoSaoOsDeSempre() {
        val t = TEMPOS_PADRAO
        assertEquals(Subida(0.10f, 0.40f, 30_000), t.subida)
        assertEquals(40, t.cameraMin) // 20 fáceis + 20 exigentes
        assertEquals(5, t.frases)
        assertEquals(25, t.manhaMin) // 10 de conversa + 5 pra sair + 10 andando
        assertEquals(t, t.ajustado())
    }

    @Test
    fun gravacaoTortaEntraNasFaixas() {
        val torto = Tempos(volumeInicio = 0.9f, volumeFim = 0.3f, cameraFacilMin = 0, frases = 99, cameraDificilMin = 60, conversaMin = -1, prepararMin = 11, caminhadaMin = 2)
        val certo = torto.ajustado()
        assertEquals(0.9f, certo.volumeInicio)
        assertEquals(0.9f, certo.volumeFim) // o fim nunca abaixo do início
        assertEquals(5, certo.cameraFacilMin)
        assertEquals(10, certo.frases)
        assertEquals(30, certo.cameraDificilMin)
        assertEquals(5, certo.conversaMin)
        assertEquals(10, certo.prepararMin)
        assertEquals(5, certo.caminhadaMin)
        assertEquals(Tempos(volumeInicio = 0f).ajustado().volumeInicio, VOLUME_DA_MUSICA.start)
    }

    @Test
    fun jsonIdaEVoltaEOQueFaltaVemDoPadrao() {
        val t = Tempos(volumeInicio = 0.15f, volumeFim = 0.65f, cameraFacilMin = 8, frases = 0, cameraDificilMin = 12, conversaMin = 5, prepararMin = 0, caminhadaMin = 30)
        assertEquals(t, Tempos.deJson(t.paraJson()))
        assertEquals(TEMPOS_PADRAO, Tempos.deJson(org.json.JSONObject("{}")))
        assertEquals(TEMPOS_PADRAO.copy(caminhadaMin = 30), Tempos.deJson(org.json.JSONObject("""{"caminhadaMin":30}""")))
        assertEquals(30, Tempos.deJson(org.json.JSONObject("""{"caminhadaMin":99}""")).caminhadaMin) // torto entra na faixa
    }

    @Test
    fun aFalaDeSairDizOPrazoDosAjustes() {
        assertEquals("Time to move, sir. Get dressed and go outside: you have 4 minutes to start walking.", falaDeSair(minutos(4)))
        assertEquals("Time to move, sir. Get dressed and go outside: you have one minute to start walking.", falaDeSair(minutos(1)))
        assertEquals("Time to move, sir. Get dressed and go outside: you have half a minute to start walking.", falaDeSair(PRAZO_SAIR_TESTE_MS))
        assertEquals("Time to move, sir. Get dressed and go outside.", falaDeSair(0))
        // A mini tarefa promete o tempo que dá de fato: 5 min de verdade, meio minuto no teste
        assertEquals("Fell asleep, sir? Repeat this sentence and you get five more minutes: Go.", falaDaMiniTarefa("Go.", PREPARO_EXTRA_MS))
        assertEquals("Fell asleep, sir? Tap the button on the screen for five more minutes.", falaDoBotaoDaMiniTarefa(PREPARO_EXTRA_MS))
        assertEquals("Fell asleep, sir? Tap the button on the screen for half a minute more.", falaDoBotaoDaMiniTarefa(PREPARO_EXTRA_TESTE_MS))
        assertEquals("Five more minutes, sir. Get dressed and go outside.", falaMaisTempo(PREPARO_EXTRA_MS))
        assertEquals("Half a minute more, sir. Get dressed and go outside.", falaMaisTempo(PREPARO_EXTRA_TESTE_MS))
    }
}
