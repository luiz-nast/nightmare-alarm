package com.implacavel.alarme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/** Regras da conversa da manhã (RegrasConversa.kt) e as notícias. */
class ConversaTest {

    @Test
    fun conversaPede10MinutosETeste5() {
        assertEquals(10 * 60_000L, metaConversa(Alarme(1, 7, 0, caminhada = true)))
        assertEquals(3 * 60_000L, metaConversa(Alarme.teste(caminhada = true)))
        assertEquals(3 * 60_000L, extraDaConversa(Alarme(1, 7, 0, caminhada = true)))
        assertEquals(20_000L, extraDaConversa(Alarme.teste(caminhada = true)))
    }

    @Test
    fun relogioSoAndaComAVozDaIaOuComAPessoaFalando() {
        // A voz da IA tocando: anda
        assertTrue(conversaAnda(iaFalando = true, suaVez = false, vezComecouHaMs = 60_000, ouviuHaMs = 60_000))
        // Esperando a IA (internet, voz gravando): parado
        assertFalse(conversaAnda(iaFalando = false, suaVez = false, vezComecouHaMs = 0, ouviuHaMs = 0))
        // Na vez da pessoa, os 3 primeiros segundos contam (o tempo de reagir)
        assertTrue(conversaAnda(iaFalando = false, suaVez = true, vezComecouHaMs = 2_999, ouviuHaMs = 60_000))
        assertFalse(conversaAnda(iaFalando = false, suaVez = true, vezComecouHaMs = 3_000, ouviuHaMs = 60_000))
        // Depois, só enquanto ela fala (algo ouvido há menos de 1,5 s); em silêncio, para
        assertTrue(conversaAnda(iaFalando = false, suaVez = true, vezComecouHaMs = 20_000, ouviuHaMs = 1_499))
        assertFalse(conversaAnda(iaFalando = false, suaVez = true, vezComecouHaMs = 20_000, ouviuHaMs = 1_500))
    }

    @Test
    fun relogioDaConversaSoSobe() {
        var conversado = 0L
        repeat(8) { conversado = avancarConversa(conversado, anda = true, passoMs = TIQUE_CONVERSA_MS) }
        assertEquals(2_000L, conversado)
        repeat(8) { conversado = avancarConversa(conversado, anda = false, passoMs = TIQUE_CONVERSA_MS) }
        assertEquals(2_000L, conversado)
        assertEquals(2_270L, avancarConversa(conversado, anda = true, passoMs = 270)) // o tempo real, não um passo fixo
    }

    @Test
    fun silencioBipaAos10SegundosDepoisACada3SemFim() {
        assertEquals(0, bipesDoSilencio(0))
        assertEquals(0, bipesDoSilencio(9_999))
        assertEquals(1, bipesDoSilencio(10_000))
        assertEquals(1, bipesDoSilencio(12_999))
        assertEquals(2, bipesDoSilencio(13_000))
        // O 6º, aos 25 s, já no volume máximo
        assertEquals(BIPES_ATE_ZERAR, bipesDoSilencio(SILENCIO_CONVERSA_MS + (BIPES_ATE_ZERAR - 1) * AVISO_SEM_OLHAR_MS))
        // E continua, a cada 3 s, sem passar do máximo
        assertEquals(BIPES_ATE_ZERAR + 10, bipesDoSilencio(SILENCIO_CONVERSA_MS + (BIPES_ATE_ZERAR + 9) * AVISO_SEM_OLHAR_MS))
        assertEquals(volumeDoBipe(BIPES_ATE_ZERAR, 15), volumeDoBipe(BIPES_ATE_ZERAR + 10, 15))
    }

    @Test
    fun respostaAoQuerContinuar() {
        for (fala in listOf("yes", "Yeah, sure", "OK", "okay let's go", "of course", "lets keep talking", "Sim", "claro")) {
            assertEquals(fala, true, querContinuar(fala))
        }
        for (fala in listOf("no", "Nope", "not now", "I'm good", "I’m good, thanks", "não", "yeah, no", "sure, not now", "stop")) {
            assertEquals(fala, false, querContinuar(fala))
        }
        for (fala in listOf("", "what?", "I know", "tomorrow")) {
            assertNull(fala, querContinuar(fala))
        }
    }

    @Test
    fun iaNuncaMandaDormirNemDizQueAcabou() {
        for (fala in listOf(
            "Good morning, sir! The Senate votes today on the 6x1 work schedule. What do you think about it?",
            "Did you sleep well, sir? What is the first task on your list today?",
            "Not my call, sir. The alarm decides. Tell me, what is the first thing you will do today?",
            "Coffee snaps me awake. What snaps you awake, sir?",
        )) {
            assertTrue(fala, respostaSegura(fala))
        }
        for (fala in listOf(
            "Maybe go back to sleep, sir.",
            "Hit snooze for now.",
            "Take five more minutes, sir.",
            "You deserve 5 more minutes.",
            "Get some rest, sir.",
            "The alarm is off now.",
            "You can stop now.",
            "A quick nap would help.",
            "The activity is over, well done.",
            "Stay in bed a little longer.",
        )) {
            assertFalse(fala, respostaSegura(fala))
        }
    }

    @Test
    fun falaDaIaSaiLimpaPraVoz() {
        assertEquals("Good morning, sir! Today is big.", limparFala("**Good morning**, sir! 😀\n# Today is _big_. ☀️"))
        assertEquals("", limparFala("  ** \n "))
    }

    @Test
    fun falaDaIaFicaSoComAPergunta() {
        // A explicação que ela emenda depois da pergunta não vai pra voz
        assertEquals("Nice, what are you training today, sir?", limparFala("Nice, what are you training today, sir? I love the gym. It keeps you sharp."))
        assertEquals("Not my call, sir. What is the first thing you will do today?", soAPergunta("Not my call, sir. What is the first thing you will do today? Tell me."))
        // Um "What?" sozinho no começo não é a pergunta inteira
        assertEquals("What? Why did you skip it?", soAPergunta("What? Why did you skip it? That's a shame."))
        assertEquals("Good morning, sir.", soAPergunta("Good morning, sir."))
    }

    @Test
    fun aIaPensaMaisDevagarQuandoAFalaParaNoMeio() {
        assertEquals(PAUSA_PRA_PENSAR_MS, pausaPraPensar("I want to go to the gym today"))
        assertEquals(PAUSA_PRA_PENSAR_MS, pausaPraPensar("Yeah."))
        assertEquals(PAUSA_NO_MEIO_MS, pausaPraPensar("I want to go to the gym and"))
        assertEquals(PAUSA_NO_MEIO_MS, pausaPraPensar("I think, um..."))
        assertEquals(PAUSA_NO_MEIO_MS, pausaPraPensar("Because"))
        assertTrue(PAUSA_PRA_PENSAR_MS < PAUSA_NO_MEIO_MS)
    }

    @Test
    fun aberturaPerguntaDaPrimeiraManchete() {
        val pedido = pedidoDeAbertura(listOf("Preço do café sobe 30% no ano - g1", "Dólar cai - Folha"))
        assertTrue(pedido.contains("\"Preço do café sobe 30% no ano\""))
        assertFalse(pedido.contains("g1"))
        assertFalse(pedido.contains("Dólar"))
        assertTrue(pedidoDeAbertura(emptyList()).contains("his plans for today"))
    }

    @Test
    fun somDeLongeNaoSeguraAIa() {
        // Sem saber a voz dele ainda, todo som pode ser dele
        assertTrue(somDaPessoa(-60f, null))
        // A voz dele em -35 dB: um "hmm" a -45 é dele; a TV a -55, de longe
        val voz = nivelDaVoz(nivelDaVoz(null, -34f), -36f)
        assertEquals(-34.6f, voz, 0.01f)
        assertTrue(somDaPessoa(-45f, voz))
        assertFalse(somDaPessoa(-55f, voz))
    }

    @Test
    fun reescritaDoReconhecedorNaoEhFalaNova() {
        assertEquals(quantasPalavras("Knife what are you going"), quantasPalavras("Nice, what are you going."))
        assertEquals(3, quantasPalavras("I'm going to"))
        assertEquals(0, quantasPalavras("  ... "))
    }

    @Test
    fun respostaJogadaForaAvisaAIa() {
        // Ele continuou a frase: vai só o resto (a IA já tem o começo na memória)
        val continuou = pedidoDepoisDeInterrompida("I want to go to the gym and", "I want to go to the gym and then work")
        assertTrue(continuou.contains("did not hear your last reply"))
        assertTrue(continuou.endsWith("He went on:] then work"))
        // O reconhecedor reescreveu o começo ("Knife" vira "Nice", pontuação): ainda é continuação
        assertTrue(pedidoDepoisDeInterrompida("Knife what are you", "Nice, what are you doing today?").endsWith("He went on:] doing today?"))
        // Outra fala (ou a retomada depois da música): vai inteira
        val outra = pedidoDepoisDeInterrompida("I want to run", "Let's talk about coffee")
        assertTrue(outra.endsWith("Answer this instead:] Let's talk about coffee"))
    }

    @Test
    fun detectorPegaUmAhnCurtoNoQuartoQuieto() {
        val d = DetectorDeSom()
        repeat(200) { assertNull(d.quadro(-62f + it % 3)) } // o ruído do quarto
        // Um "ahn" de 200 ms, 20 dB acima: começa no 3º pedaço (60 ms)
        assertNull(d.quadro(-40f))
        assertNull(d.quadro(-40f))
        assertEquals(true, d.quadro(-40f))
        repeat(7) { assertNull(d.quadro(-40f)) }
        // E acaba com 300 ms de silêncio
        repeat(14) { assertNull(d.quadro(-62f)) }
        assertEquals(false, d.quadro(-62f))
    }

    @Test
    fun detectorOuveQuemJaComecaFalando() {
        // Sem ter ouvido o quarto, o piso é o de um quarto quieto: a fala logo no começo conta
        val d = DetectorDeSom()
        assertNull(d.quadro(-40f))
        assertNull(d.quadro(-38f))
        assertEquals(true, d.quadro(-41f))
    }

    @Test
    fun detectorIgnoraEstaloLencolEVentilador() {
        val d = DetectorDeSom()
        repeat(100) { d.quadro(-62f) }
        // Um estalo de 40 ms não é a pessoa
        assertNull(d.quadro(-30f))
        assertNull(d.quadro(-30f))
        assertNull(d.quadro(-62f))
        assertFalse(d.falando)
        // Um ventilador que liga: no começo é som, mas em uns segundos vira o ruído do quarto
        var ultimo: Boolean? = null
        repeat(300) { d.quadro(-45f)?.let { ultimo = it } }
        assertEquals(false, ultimo)
        assertFalse(d.falando)
        // Num quarto em silêncio total, um lençol mexendo (-75 dB) não conta
        val quieto = DetectorDeSom()
        repeat(200) { quieto.quadro(-90f) }
        repeat(10) { assertNull(quieto.quadro(-75f)) }
    }

    @Test
    fun instrucaoDoTreinadorTemAsRegrasAsNoticiasEAsMetas() {
        val instrucao = instrucaoDoTreinador(
            listOf("Senado vota fim da escala 6x1 - O GLOBO", "Dólar cai - g1"), "Correr 3x por semana", LocalTime.of(6, 40),
        )
        assertTrue(instrucao.contains("6:40 AM"))
        assertTrue(instrucao.contains("\"sir\""))
        assertTrue(instrucao.contains("1. Senado vota fim da escala 6x1 - O GLOBO"))
        assertTrue(instrucao.contains("2. Dólar cai - g1"))
        assertTrue(instrucao.contains("Correr 3x por semana"))
        assertTrue(instrucao.contains("Not my call, sir."))
        assertTrue(instrucao.contains("Stay on the same subject"))
        assertTrue(instrucao.contains("Change the subject only when it has died"))
        assertTrue(instrucao.contains("ONE short, direct question, 15 words at most"))
        // Sem notícias nem metas
        val vazia = instrucaoDoTreinador(emptyList(), "  ", LocalTime.of(14, 5))
        assertTrue(vazia.contains("2:05 PM"))
        assertTrue(vazia.contains("no headlines"))
        assertFalse(vazia.contains("His goals"))
        // Metas grandes são cortadas; manchetes, no máximo MANCHETES_MAX (o modelo do celular lê tudo antes de falar)
        val grande = instrucaoDoTreinador((1..10).map { "Manchete $it" }, "§".repeat(5_000), LocalTime.of(6, 0))
        assertEquals(METAS_MAX_LETRAS, grande.count { it == '§' })
        assertTrue(grande.contains("$MANCHETES_MAX. Manchete $MANCHETES_MAX"))
        assertFalse(grande.contains("Manchete ${MANCHETES_MAX + 1}"))
    }

    @Test
    fun quemContinuaLevaARespostaAnteriorJunto() {
        val pedido = pedidoDeContinuar("I want to run today", "yes")
        assertTrue(pedido.startsWith("I want to run today"))
        assertTrue(pedido.contains("\"yes\""))
    }

    @Test
    fun manchetesDoRssDoGoogleNoticias() {
        val rss = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel>
            <generator>NFE/5.0</generator><title>Principais notícias - Google Notícias</title><link>https://news.google.com/?hl=pt-BR</link>
            <item><title>Fim da escala 6x1: Senado faz última sessão - O GLOBO</title><link>https://news.google.com/rss/articles/CBMi1</link>
            <guid isPermaLink="false">CBMi1</guid><pubDate>Wed, 14 Oct 2026 11:13:57 GMT</pubDate>
            <description>&lt;ol&gt;&lt;li&gt;&lt;a href="https://news.google.com/x"&gt;Outro título&lt;/a&gt;&lt;/li&gt;&lt;/ol&gt;</description>
            <source url="https://oglobo.globo.com">O GLOBO</source></item>
            <item><title>Petrobras &amp; Vale sobem; dólar &#39;despenca&#39; - g1</title></item>
            <item><title><![CDATA[Chuva em SP: "alerta" & frio - Folha]]></title></item>
            <item><title>   </title></item>
            <item>
              <title>Copa
                2026 - ge</title>
            </item>
            </channel></rss>"""
        assertEquals(
            listOf(
                "Fim da escala 6x1: Senado faz última sessão - O GLOBO",
                "Petrobras & Vale sobem; dólar 'despenca' - g1",
                "Chuva em SP: \"alerta\" & frio - Folha",
                "Copa 2026 - ge",
            ),
            manchetesDoRss(rss),
        )
        assertEquals(2, manchetesDoRss(rss, maximo = 2).size)
        assertTrue(manchetesDoRss("").isEmpty())
        assertTrue(manchetesDoRss("<html><title>Erro</title></html>").isEmpty())
    }

    @Test
    fun textoDoXmlViraLetraUmaVezSo() {
        assertEquals("<b> & A é", textoDoXml("&lt;b&gt; &amp; &#x41; &#233;"))
        assertEquals("&lt;", textoDoXml("&amp;lt;")) // uma volta só: não vira "<"
        assertEquals("&bogus; &#1114112;", textoDoXml("&bogus; &#1114112;")) // desconhecida ou inválida fica como está
        assertEquals("a &amp; b & c", textoDoXml("<![CDATA[a &amp; b]]> &amp; c"))
    }

    @Test
    fun metasEmTextoSimples() {
        val html = "<html><head><style>body{color:red}</style><SCRIPT>alert(1)</SCRIPT></head>" +
            "<body><h1>Metas</h1>\n<p>Correr 3x &amp; ler</p></body></html>"
        assertEquals("Metas Correr 3x & ler", textoDasMetas(html))
        assertEquals("Ler 20 páginas", textoDasMetas("  Ler 20 páginas \n"))
        assertEquals(METAS_MAX_LETRAS, textoDasMetas("a".repeat(5_000)).length)
    }
}
