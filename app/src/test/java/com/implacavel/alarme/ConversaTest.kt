package com.implacavel.alarme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Regras da conversa da manhã (RegrasConversa.kt), as notícias e a busca que a IA pede (Pesquisa.kt). */
class ConversaTest {

    @Test
    fun conversaPedeOsMinutosDosAjustesETeste3() {
        assertEquals(10 * 60_000L, metaConversa(Alarme(1, 7, 0, caminhada = true))) // de começo
        assertEquals(25 * 60_000L, metaConversa(Alarme(1, 7, 0, caminhada = true), Tempos(conversaMin = 25)))
        assertEquals(3 * 60_000L, metaConversa(Alarme.teste(caminhada = true), Tempos(conversaMin = 25))) // o teste não segue os ajustes
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
    fun falaDaIaVaiInteiraSemAFraseCortadaNoFim() {
        // Tudo o que ela disse vai pra voz (antes, só a primeira pergunta: o dono achou a IA só perguntando, sem contar nada)
        assertEquals(
            "Nice, what are you training today, sir? I love the gym. It keeps you sharp.",
            limparFala("Nice, what are you training today, sir? I love the gym. It keeps you sharp."),
        )
        // Bateu no teto de tokens no meio de uma frase: fica até a última frase inteira
        assertEquals("Start with an Arduino kit, sir. In one weekend you blink an LED.", ateAUltimaFrase("Start with an Arduino kit, sir. In one weekend you blink an LED. Then you can read a"))
        assertEquals("Good morning, sir!", ateAUltimaFrase("Good morning, sir! The coffee price went up because"))
        // Sem nenhuma frase inteira (ou curta demais), vai como está
        assertEquals("Not my call", ateAUltimaFrase("Not my call"))
        assertEquals("Yes. The coffee went up because", ateAUltimaFrase("Yes. The coffee went up because"))
        assertEquals("Good morning, sir.", limparFala("Good morning, sir."))
    }

    @Test
    fun aIaPedeBuscaNumaLinhaSo() {
        assertEquals("coffee price rise Brazil", consultaDaBusca("SEARCH: coffee price rise Brazil"))
        assertEquals("who invented the transistor", consultaDaBusca("  search: \"who invented the transistor\". "))
        assertEquals("Arduino starter kit", consultaDaBusca("- Search: Arduino starter kit\nI will check."))
        // O chute do modelo emendado na consulta fica de fora (10-18: "…transistor: Alexander Fleming invented…")
        assertEquals("who invented the transistor", consultaDaBusca("SEARCH: who invented the transistor: Alexander Fleming invented the transistor in 1947 a"))
        assertEquals("coffee price Brazil", consultaDaBusca("SEARCH: coffee price Brazil. It rose 30% this year"))
        assertEquals(CONSULTA_MAX_PALAVRAS, consultaDaBusca("SEARCH: " + "w ".repeat(30))!!.split(" ").size)
        assertNull(consultaDaBusca("I would search for that, sir. What do you think?"))
        assertNull(consultaDaBusca("SEARCH:"))
        assertNull(consultaDaBusca(""))
        assertEquals(CONSULTA_MAX_LETRAS, consultaDaBusca("SEARCH: " + "a".repeat(200))!!.length)
        // O que volta pra IA: o resultado (cortado) e a ordem de não citar a busca; sem resultado, responde com o que sabe
        val com = pedidoComResultado("coffee price", "Coffee: " + "x".repeat(2_000), pergunta = "Why is coffee so expensive?")
        assertTrue(com.startsWith("[Search result for \"coffee price\":"))
        assertTrue(com.contains("He asked: \"Why is coffee so expensive?\". Now answer exactly that"))
        assertTrue(com.contains("Do not mention the search"))
        assertTrue(com.length < RESULTADO_MAX_LETRAS + 500)
        assertTrue(pedidoComResultado("coffee price", null).contains("Nothing was found"))
        assertTrue(pedidoComResultado("coffee price", "  ").contains("Nothing was found"))
        // A pergunta dele sem as linhas do app entre colchetes; sem pergunta, só "answer him"
        assertTrue(pedidoComResultado("x", "r", "[He did not hear your last reply. Answer this instead:]\nWho made it?").contains("He asked: \"Who made it?\""))
        assertTrue(pedidoComResultado("x", "r", "").contains("Now answer him in plain speech"))
    }

    @Test
    fun resultadoDaBuscaTrataOTextoAntesDaIa() {
        // A resposta da Wikipédia: o trecho onde a busca bateu (search, em HTML) e o texto do artigo (pages)
        val json = """{"batchcomplete":true,"query":{
            "search":[{"ns":0,"title":"Transistor","pageid":1,"snippet":"The first working <span class=\"searchmatch\">transistor</span> was invented in 1947 by Bardeen &amp; Brattain"}],
            "pages":[
            {"pageid":2,"ns":0,"title":"Second","index":2,"extract":"Second article.\nMore."},
            {"pageid":1,"ns":0,"title":"Transistor","index":1,"extract":"A transistor is a semiconductor device."},
            {"pageid":3,"ns":0,"title":"Empty","index":3,"extract":"  "}]}}"""
        assertEquals(
            listOf("Transistor" to "The first working transistor was invented in 1947 by Bardeen & Brattain\nA transistor is a semiconductor device.", "Second" to "Second article.\nMore."),
            paginasDaWikipedia(json),
        )
        assertTrue(paginasDaWikipedia("{\"batchcomplete\":true}").isEmpty()) // nada achado: sem "query"
        assertTrue(paginasDaWikipedia("<html>erro</html>").isEmpty())
        // As frases com as palavras da busca vão; a que tem a data passa na frente; as sem nenhuma palavra, não
        val fontes = listOf(
            "Transistor" to "A transistor is a semiconductor device used to amplify signals. Bell Labs is a research company in New Jersey. " +
                "The transistor was invented in 1947 by John Bardeen, Walter Brattain and William Shockley.",
            "Bell Labs" to "Bell Labs is famous for many inventions in physics and computing.",
        )
        val fatos = fatosRelevantes(fontes, "who invented the transistor")
        assertEquals("Transistor: The transistor was invented in 1947 by John Bardeen, Walter Brattain and William Shockley.", fatos.first())
        assertTrue(fatos.none { it.contains("New Jersey") })
        assertTrue(fatosRelevantes(fontes, "who what when").isEmpty()) // só palavras vazias: nada a procurar
        assertTrue(fatosRelevantes(fontes, "transistor", max = 1).size == 1)
        val texto = resultadoDaBusca(listOf("Transistor: A semiconductor device."), listOf("Chip makers rise - Reuters", "New transistor record - Nature"))
        assertEquals("Best facts found:\n- Transistor: A semiconductor device.\nRecent news:\n- Chip makers rise\n- New transistor record", texto)
        assertEquals("Recent news:\n- Only news", resultadoDaBusca(emptyList(), listOf("Only news - g1")))
        assertNull(resultadoDaBusca(emptyList(), emptyList()))
        assertEquals(RESULTADO_MAX_LETRAS, resultadoDaBusca(listOf("W: " + "x".repeat(3_000)), emptyList())!!.length)
        assertTrue(enderecoDaWikipedia("coffee price").contains("gsrsearch=coffee+price"))
        assertTrue(enderecoDaWikipedia("cafe", "pt").startsWith("https://pt.wikipedia.org/"))
        assertTrue(enderecoDaWikipedia("cafe", inteiro = true).contains("exlimit=1") && !enderecoDaWikipedia("cafe", inteiro = true).contains("exintro"))
        assertTrue(enderecoDasNoticias("coffee price").contains("q=coffee+price"))
    }

    @Test
    fun juizoDaIaSobreARespostaDele() {
        assertEquals(Veredito.BOM to "Start with a kit.", vereditoDaFala("[OK] Start with a kit."))
        assertEquals(Veredito.VAGO to "Lazy answer, sir.", vereditoDaFala("[VAGUE] Lazy answer, sir."))
        assertEquals(Veredito.PESSIMO to "Garbage, you dumbass.", vereditoDaFala("  [bad]: Garbage, you dumbass."))
        assertEquals(Veredito.VAGO to "Come on.", vereditoDaFala("VAGUE: Come on."))
        // Sem juízo, ou "OK" como palavra da fala: vale bom, e a fala fica inteira
        assertEquals(Veredito.BOM to "OK, sir, here is one.", vereditoDaFala("OK, sir, here is one."))
        assertEquals(Veredito.BOM to "Fine.", vereditoDaFala("Fine."))
        // Do motor pro serviço o juízo vai na frente, e volta igual
        assertEquals(Veredito.PESSIMO to "Wake up.", vereditoDaFala(comVeredito(Veredito.PESSIMO, "Wake up.")))
        assertEquals("Fine.", comVeredito(Veredito.BOM, "Fine."))
        // A busca com o juízo na frente ainda é busca
        assertEquals("who invented the transistor", consultaDaBusca("[OK] SEARCH: who invented the transistor"))
        // Bipe: na resposta péssima, e na segunda enrolada seguida; uma boa zera a conta
        assertEquals(false to 1, bipeDoJuizo(Veredito.VAGO, 0))
        assertEquals(true to 0, bipeDoJuizo(Veredito.VAGO, 1))
        assertEquals(true to 0, bipeDoJuizo(Veredito.PESSIMO, 0))
        assertEquals(false to 0, bipeDoJuizo(Veredito.BOM, 1))
        // A instrução manda julgar, deixa o português e leva as palavras dele
        val instrucao = instrucaoDoTreinador(emptyList(), "", LocalTime.of(6, 40), palavras = listOf("Arduino", "Opala"))
        assertTrue(instrucao.contains("[VAGUE]") && instrucao.contains("[BAD]") && instrucao.contains("He may answer in Portuguese"))
        assertTrue(instrucao.contains("Words he often says, which the speech recognizer may mishear: Arduino, Opala."))
        assertFalse(instrucaoDoTreinador(emptyList(), "", LocalTime.of(6, 40)).contains("Words he often says"))
    }

    @Test
    fun palavrasDeleEmLista() {
        assertEquals(listOf("Arduino", "Opala", "Nubank"), listaDePalavras("Arduino, Opala;\n Nubank , arduino"))
        assertTrue(listaDePalavras("  ,, ").isEmpty())
        assertEquals(PALAVRAS_MAX, listaDePalavras((1..50).joinToString(",") { "w$it" }).size)
    }

    @Test
    fun anotacoesDaIaFicamLimpasECurtas() {
        val cruas = "* Wants to learn electronics (Oct 17)\n- **Bought an Arduino kit** (Oct 18)\n\n  Works as a nurse (Oct 17)  \n"
        assertEquals("Wants to learn electronics (Oct 17)\nBought an Arduino kit (Oct 18)\nWorks as a nurse (Oct 17)", limparAnotacoes(cruas))
        assertEquals(MEMORIA_MAX_LINHAS, limparAnotacoes((1..30).joinToString("\n") { "fato $it" }).lines().size)
        assertTrue(limparAnotacoes("x".repeat(5_000)).length <= MEMORIA_MAX_LETRAS)
        assertEquals("", limparAnotacoes("  \n \n"))
    }

    @Test
    fun aIaDizQueNaoEntendeuEmPoucasPalavras() {
        for (fala in listOf(FALA_NAO_ENTENDI, "I didn't get that, sir.", "Sorry, I could not understand you.", "I didn't catch that. Say it again?", "I can't follow what you said, sir.")) {
            assertTrue(fala, naoEntendeu(fala))
        }
        // Uma fala de verdade, mesmo com a expressão no meio, não conta
        for (fala in listOf(
            "Good morning, sir. What are your plans today?",
            "I didn't get that joke at first, sir, but the Senate vote is serious: six days of work for one of rest. What do you think about it?",
            "",
        )) {
            assertFalse(fala, naoEntendeu(fala))
        }
        assertEquals(3, NAO_ENTENDIDOS_ATE_O_BIPE)
        assertTrue(volumeDoBipe(BIPE_DO_NAO_ENTENDI, 15) in 3..8) // leve pra médio, não o máximo
        assertTrue(instrucaoDoTreinador(emptyList(), "", LocalTime.of(6, 40)).contains("reply exactly \"$FALA_NAO_ENTENDI\""))
    }

    @Test
    fun anotacoesValemUmDiaSoDaUltimaConversa() {
        val escrita = 1_790_000_000_000L
        assertTrue(memoriaVale(escrita, escrita + 1_000))
        assertTrue(memoriaVale(escrita, escrita + 26 * 3_600_000L)) // a manhã seguinte, mesmo mais tarde
        assertTrue(memoriaVale(escrita, escrita + MEMORIA_VALIDA_MS))
        assertFalse(memoriaVale(escrita, escrita + MEMORIA_VALIDA_MS + 1)) // dois dias depois, não
        assertFalse(memoriaVale(escrita, escrita - 1)) // relógio que voltou
        assertFalse(memoriaVale(0L, escrita)) // nunca escrita
    }

    @Test
    fun pedidoDeAnotacoesLevaSoAConversaDeHoje() {
        val transcricao = listOf(Turno(daIa = true, texto = "Good morning, sir! How did the Arduino kit go?"), Turno(daIa = false, texto = "I bought it yesterday"))
        val pedido = pedidoDeAnotacoes(transcricao, LocalDate.of(2026, 10, 18))
        assertTrue(pedido.contains("Today is Sunday, October 18, 2026."))
        assertTrue(pedido.contains("You: Good morning, sir! How did the Arduino kit go?\nHe: I bought it yesterday"))
        assertTrue(pedido.contains("like (Oct 18)"))
        assertTrue(pedido.contains("nothing about the alarm or about wanting to sleep"))
        assertFalse(pedido.contains("Old notes")) // nada de antes: cada conversa escreve as suas do zero
        // Conversa longa: ficam as últimas falas, até o limite
        val longa = (1..100).map { Turno(daIa = it % 2 == 0, texto = "fala $it " + "palavra ".repeat(20)) }
        val cortado = pedidoDeAnotacoes(longa, LocalDate.of(2026, 10, 18))
        assertFalse(cortado.contains("fala 1 "))
        assertTrue(cortado.contains("fala 100 "))
        assertTrue(cortado.length < TRANSCRICAO_MAX_LETRAS + 1_000)
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
    fun aberturaRetomaAsAnotacoesOuContaDaPrimeiraManchete() {
        val manchetes = listOf("Preço do café sobe 30% no ano - g1", "Dólar cai - Folha")
        val pedido = pedidoDeAbertura(manchetes)
        assertTrue(pedido.contains("\"Preço do café sobe 30% no ano\""))
        assertTrue(pedido.contains("tell him in one simple sentence what this headline is about"))
        assertFalse(pedido.contains("g1"))
        assertFalse(pedido.contains("Dólar"))
        assertTrue(pedidoDeAbertura(emptyList()).contains("his plans for today"))
        // Com anotações das manhãs de antes, abre retomando uma coisa dele (a manchete fica pra depois)
        val comMemoria = pedidoDeAbertura(manchetes, memoria = "Wants to learn electronics (Oct 17)")
        assertTrue(comMemoria.contains("follow up on one thing from your notes"))
        assertFalse(comMemoria.contains("café"))
        assertEquals(pedido, pedidoDeAbertura(manchetes, memoria = "  "))
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
        assertTrue(instrucao.contains("Stay on the subject while it is alive"))
        assertTrue(instrucao.contains("Change the subject only when it has died"))
        // Conta coisas antes de perguntar, responde o que ele pergunta e pode pedir busca
        assertTrue(instrucao.contains("two short sentences, three at most, 30 words at most"))
        assertTrue(instrucao.contains("Never reply with only a question"))
        assertTrue(instrucao.contains("never by judging what he said"))
        assertTrue(instrucao.contains("mention them lightly, never insist"))
        assertTrue(instrucao.contains("SEARCH: <his question in 3 to 8 plain words>"))
        assertTrue(instrucao.contains("never your own guess of the answer"))
        assertFalse(instrucao.contains("About him"))
        assertFalse(instrucao.contains("Your notes from your last talk"))
        // O que ele contou de si e as anotações da última conversa
        val comEle = instrucaoDoTreinador(emptyList(), "", LocalTime.of(6, 40), sobreEle = "I'm Luiz, I work with software.", memoria = "Wants to learn electronics (Oct 17)")
        assertTrue(comEle.contains("About him, in his own words:\nI'm Luiz, I work with software."))
        assertTrue(comEle.contains("Your notes from your last talk with him (the date is when it happened):\nWants to learn electronics (Oct 17)"))
        assertEquals(SOBRE_MIM_MAX_LETRAS, instrucaoDoTreinador(emptyList(), "", LocalTime.of(6, 40), sobreEle = "§".repeat(5_000)).count { it == '§' })
        assertEquals(MEMORIA_MAX_LETRAS, instrucaoDoTreinador(emptyList(), "", LocalTime.of(6, 40), memoria = "§".repeat(5_000)).count { it == '§' })
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
