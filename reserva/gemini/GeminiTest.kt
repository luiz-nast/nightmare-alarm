// GUARDADO, FORA DO APP: os testes do Gemini (reserva/gemini/Gemini.kt). Pra religar, volta pra
// app/src/test/java/com/implacavel/alarme/ junto com ele (veja o README.md).
package com.implacavel.alarme

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** O pedido e a resposta do Gemini (funções puras do Gemini.kt). */
class GeminiTest {

    @Test
    fun pedidoProGeminiTemInstrucaoHistoricoEPoucoPensar() {
        val turnos = listOf(Turno(daIa = false, texto = pedidoDeAbertura(emptyList())), Turno(daIa = true, texto = "Bom dia!"), Turno(daIa = false, texto = "Ele disse \"oi\"\nde novo"))
        val corpo = JSONObject(corpoDoPedido("Regras", turnos))
        assertEquals("Regras", corpo.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val conteudos = corpo.getJSONArray("contents")
        assertEquals(listOf("user", "model", "user"), (0 until conteudos.length()).map { conteudos.getJSONObject(it).getString("role") })
        assertEquals("Ele disse \"oi\"\nde novo", conteudos.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getString("text"))
        val config = corpo.getJSONObject("generationConfig")
        assertEquals("MINIMAL", config.getJSONObject("thinkingConfig").getString("thinkingLevel"))
        assertFalse(config.getJSONObject("thinkingConfig").has("thinkingBudget")) // os dois juntos dão erro 400
        assertFalse(config.has("temperature"))
        assertTrue(config.getInt("maxOutputTokens") in 100..400)
    }

    @Test
    fun respostaDoGeminiSemOPensamento() {
        val normal = """{"candidates":[{"content":{"parts":[{"text":"Good morning, sir! "},{"text":"What is new?"}],"role":"model"},"finishReason":"STOP"}]}"""
        assertEquals("Good morning, sir! What is new?", textoDaResposta(normal))
        val pensando = """{"candidates":[{"content":{"parts":[{"text":"Let me think...","thought":true},{"text":"Hi, sir.","thoughtSignature":"abc"}]}}]}"""
        assertEquals("Hi, sir.", textoDaResposta(pensando))
        val bloqueada = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
        assertNull(textoDaResposta(bloqueada))
        assertEquals("SAFETY", motivoSemTexto(bloqueada))
        val cortada = """{"candidates":[{"content":{"parts":[{"text":"  "}]},"finishReason":"MAX_TOKENS"}]}"""
        assertNull(textoDaResposta(cortada))
        assertEquals("MAX_TOKENS", motivoSemTexto(cortada))
        assertNull(textoDaResposta("""{"candidates":[{"finishReason":"SAFETY"}]}"""))
        assertNull(textoDaResposta(""))
        assertEquals("sem motivo", motivoSemTexto(""))
    }

    @Test
    fun errosDoGemini() {
        val porMinuto = """{"error":{"code":429,"message":"You exceeded your current quota, please check your plan.","status":"RESOURCE_EXHAUSTED",""" +
            """"details":[{"violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]}]}}"""
        val doDia = porMinuto.replace("PerMinute", "PerDay")
        assertTrue(resumoDoErro(429, porMinuto).startsWith("429 RESOURCE_EXHAUSTED: You exceeded"))
        assertEquals("429 rate_limit_exceeded: Slow down", resumoDoErro(429, """{"error":{"code":"rate_limit_exceeded","message":"Slow down"}}"""))
        assertEquals("503", resumoDoErro(503, ""))
        // Tenta de novo no que passa sozinho; na cota do dia, chave ruim ou pedido errado, não
        assertTrue(falhaPassageira(429, porMinuto))
        assertFalse(falhaPassageira(429, doDia))
        for (codigo in listOf(408, 500, 503, 504)) assertTrue("$codigo", falhaPassageira(codigo, ""))
        for (codigo in listOf(400, 401, 403, 404)) assertFalse("$codigo", falhaPassageira(codigo, ""))
    }
}
