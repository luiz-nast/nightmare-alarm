package com.implacavel.alarme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Regras da missão pra desligar (RegrasMissao.kt). */
class MissaoTest {

    @Test
    fun reconheceStopComSotaque() {
        for (frase in listOf("stop", "STOP!", "Stop.", "estop", "istópi", "stopi", "bus stop", "para, stop")) {
            assertTrue(frase, disseStop(frase))
        }
    }

    @Test
    fun ignoraOutrasPalavras() {
        for (frase in listOf("", "top", "bom dia", "para", "pare", "desliga")) {
            assertFalse(frase, disseStop(frase))
        }
    }

    @Test
    fun olhoMeioAbertoDeQuemEstaAcordandoJaConta() {
        assertEquals(Leitura.OLHANDO, classificarRosto(0.9f, 0.8f))
        assertEquals(Leitura.OLHANDO, classificarRosto(0.5f, 0.45f)) // sonolento, sem arregalar
        assertEquals(Leitura.OLHANDO, classificarRosto(null, 0.5f)) // um olho só visível
        assertEquals(Leitura.OLHANDO, classificarRosto(0.45f, 0.4f)) // ainda acordando, na primeira metade passa
        assertEquals(Leitura.OLHOS_FECHADOS, classificarRosto(0.4f, 0.35f)) // quase dormindo: não conta
        // Na segunda metade, um pouco mais exigente (sem chegar a 60% em cada olho)
        assertEquals(Leitura.OLHOS_FECHADOS, classificarRosto(0.5f, 0.5f, exigente = true))
        assertEquals(Leitura.OLHANDO, classificarRosto(0.6f, 0.55f, exigente = true))
    }

    @Test
    fun olhoFechadoOuSemInformacaoNaoConta() {
        assertEquals(Leitura.OLHOS_FECHADOS, classificarRosto(0.05f, 0.1f))
        assertEquals(Leitura.OLHOS_FECHADOS, classificarRosto(0.3f, 0.2f))
        assertEquals(Leitura.OLHOS_FECHADOS, classificarRosto(null, null))
    }

    @Test
    fun certezaDeOlhoAbertoEAMediaDosOlhosVistos() {
        assertEquals(0.7f, olhoAberto(0.9f, 0.5f)!!, 0.001f)
        assertEquals(0.6f, olhoAberto(null, 0.6f)!!, 0.001f)
        assertNull(olhoAberto(null, null))
        assertEquals(0.40f, minimoOlhoAberto(exigente = false), 0.001f)
        assertEquals(0.55f, minimoOlhoAberto(exigente = true), 0.001f)
    }

    @Test
    fun anelSoSobeOlhandoENuncaDesce() {
        var olhado = 0L
        repeat(50) { olhado = avancarOlhar(olhado, olhando = true, passoMs = 100) }
        assertEquals(5_000L, olhado)
        // Sem olhos abertos agora, o anel para na hora e fica onde está
        repeat(30) { olhado = avancarOlhar(olhado, olhando = false, passoMs = 100) }
        assertEquals(5_000L, olhado)
        // Conta o tempo real entre duas conferências, não um passo fixo
        assertEquals(5_130L, avancarOlhar(olhado, olhando = true, passoMs = 130))
    }

    @Test
    fun cameraPedeOsMinutosDosAjustesETeste30Segundos() {
        // De começo, 20 min fáceis + 20 exigentes, com a verificação aos 20
        assertEquals(40 * 60_000L, metaOlhar(Alarme(1, 7, 0)))
        assertEquals(20 * 60_000L, verificacaoEm(Alarme(1, 7, 0)))
        // O dono mexeu: 5 fáceis + 30 exigentes
        val tempos = Tempos(cameraFacilMin = 5, cameraDificilMin = 30)
        assertEquals(35 * 60_000L, metaOlhar(Alarme(1, 7, 0), tempos))
        assertEquals(5 * 60_000L, verificacaoEm(Alarme(1, 7, 0), tempos))
        // O teste não segue os ajustes: 30 s, metade fácil, metade exigente
        assertEquals(30_000L, metaOlhar(Alarme.teste(), tempos))
        assertEquals(15_000L, verificacaoEm(Alarme.teste(), tempos))
    }

    @Test
    fun contagemPraZerarApareceAos3Segundos() {
        assertNull(segundosParaZerar(0))
        assertNull(segundosParaZerar(2_999))
        assertEquals(17, segundosParaZerar(3_000))
        assertEquals(17, segundosParaZerar(3_500))
        assertEquals(16, segundosParaZerar(4_000))
        assertEquals(1, segundosParaZerar(19_500))
        assertEquals(0, segundosParaZerar(20_000))
    }

    @Test
    fun bipesSobemDoLevinhoAoMaximoDoCelular() {
        assertEquals(6, BIPES_ATE_ZERAR) // aos 3, 6, 9, 12, 15 e 18 s sem olhar
        assertTrue(BIPES_ATE_ZERAR * AVISO_SEM_OLHAR_MS < DESISTENCIA_MS)
        // Celular com 15 degraus de volume de alarme, como o Galaxy S24 FE: 20% … 100%
        assertEquals(listOf(3, 5, 8, 10, 13, 15), (1..BIPES_ATE_ZERAR).map { volumeDoBipe(it, 15) })
        assertEquals(listOf(1, 2, 4, 5, 6, 7), (1..BIPES_ATE_ZERAR).map { volumeDoBipe(it, 7) })
        assertEquals(1, volumeDoBipe(1, 2)) // nunca mudo
    }

    @Test
    fun youtubeSoAbreSiteNaPropriaPagina() {
        assertTrue(linkFicaNaPagina("https://m.youtube.com/watch?v=abc"))
        assertTrue(linkFicaNaPagina("HTTP://consent.youtube.com"))
        assertFalse(linkFicaNaPagina("intent://www.youtube.com/watch?v=abc#Intent;package=com.google.android.youtube;end"))
        assertFalse(linkFicaNaPagina("vnd.youtube:abc"))
        assertFalse(linkFicaNaPagina("market://details?id=com.google.android.youtube"))
        // Na página principal, só YouTube e Google; partes de dentro dela, qualquer site
        assertTrue(linkFicaNaPagina("https://accounts.google.com/signin"))
        assertFalse(linkFicaNaPagina("https://www.netflix.com"))
        assertFalse(linkFicaNaPagina("https://youtube.com.golpe.net"))
        assertTrue(linkFicaNaPagina("https://anuncio.exemplo.net/quadro", principal = false))
        assertFalse(linkFicaNaPagina("javascript:alert(1)", principal = false))
    }

    @Test
    fun saudacaoPelaHora() {
        assertEquals("Good morning", saudacao(5))
        assertEquals("Good afternoon", saudacao(12))
        assertEquals("Good evening", saudacao(19))
        assertEquals("Good evening", saudacao(2)) // madrugada
        assertEquals("Good morning! It's 5:05 AM. It's time for the activity check.", textoDaSaudacao(LocalTime.of(5, 5)))
        assertEquals("Good afternoon! It's 2:30 PM. It's time for the activity check.", textoDaSaudacao(LocalTime.of(14, 30)))
    }

    @Test
    fun vozAvisaACada10MinutosCompletos() {
        val meta = 40 * 60_000L
        assertNull(marcoCompletado(9 * 60_000L, 9 * 60_000L + 59_999, meta))
        assertEquals(10, marcoCompletado(9 * 60_000L + 59_500, 10 * 60_000L + 300, meta))
        assertNull(marcoCompletado(10 * 60_000L + 300, 10 * 60_000L + 1_300, meta)) // já avisou
        assertEquals(30, marcoCompletado(29 * 60_000L + 59_000, 30 * 60_000L, meta))
        assertNull(marcoCompletado(19 * 60_000L + 59_000, 20 * 60_000L, meta)) // na metade vem a verificação
        assertNull(marcoCompletado(39 * 60_000L + 59_000, 40 * 60_000L, meta)) // nos 40 o alarme já desliga
        assertNull(marcoCompletado(29_000, 30_000, 30_000)) // teste: nenhum aviso
        // Com a verificação aos 10 min (ajustes), os 10 não avisam e os 20 avisam
        assertNull(marcoCompletado(9 * 60_000L + 59_000, 10 * 60_000L, 35 * 60_000L, verificacaoMs = 10 * 60_000L))
        assertEquals(20, marcoCompletado(19 * 60_000L + 59_000, 20 * 60_000L, 35 * 60_000L, verificacaoMs = 10 * 60_000L))
        assertEquals("20 minutes passed.", falaDosMinutos(20))
    }

    @Test
    fun repetirAFraseAceitaSotaqueEContracao() {
        val frase = "I am fully awake and ready to make money"
        assertTrue(repetiu(frase, "I'm fully awake and ready to make money"))
        assertTrue(repetiu(frase, "fully awake and ready to make monney")) // uma letra a mais: 6 iguais + 1 parecida = 7 de 9
        assertFalse(repetiu(frase, "fully awake and ready to make many")) // duas letras trocadas não valem: 6 de 9
        assertFalse(repetiu("I rise early and I rise strong", "I rise early")) // cada palavra ouvida vale uma vez
        assertTrue(repetiu(frase, "fully awake and ready to make money")) // faltou o começo
        assertFalse(repetiu(frase, "good morning"))
        assertFalse(repetiu(frase, ""))
    }

    @Test
    fun palavrasJaDitasFicamVerdes() {
        fun verdes(frase: String, ouviu: String) = palavrasDitas(frase, ouviu).filter { it.second }.map { it.first }
        assertEquals(listOf("I", "am", "fully"), verdes("I am fully awake.", "I'm fuly"))
        assertEquals(listOf("awake."), verdes("I am fully awake.", "awake")) // a pontuação fica na tela
        assertEquals(listOf("I", "rise"), verdes("I rise early and I rise strong", "I rise")) // cada palavra ouvida pinta uma só
        assertEquals(4, palavrasDitas("I am fully awake.", "").size)
        assertTrue(palavrasDitas("I am fully awake.", "").none { it.second })
    }

    @Test
    fun frasesDiferentesPorDiaQuantasODonoPediu() {
        val hoje = frasesDoDia(LocalDate.of(2026, 10, 11))
        assertEquals(5, hoje.toSet().size) // de começo, 5
        assertEquals(hoje, frasesDoDia(LocalDate.of(2026, 10, 11))) // as mesmas no mesmo dia
        assertTrue(hoje != frasesDoDia(LocalDate.of(2026, 10, 12)))
        assertEquals(10, frasesDoDia(LocalDate.of(2026, 10, 11), quantas = 10).toSet().size)
        assertTrue(frasesDoDia(LocalDate.of(2026, 10, 11), quantas = 0).isEmpty()) // zero: sem verificação
        assertEquals(hoje, frasesDoDia(LocalDate.of(2026, 10, 11), quantas = 10).take(5)) // o mesmo sorteio, só mais frases
        // Curtas, mas não tanto quanto "I want to be rich"
        assertTrue(FRASES_MOTIVACIONAIS.all { it.split(" ").size in 6..12 })
    }

    @Test
    fun menuDeDesligarPelaClasseOuPelosTextos() {
        // O Android puro: a classe do menu; a Samsung: os textos dos botões
        assertTrue(ehMenuDeDesligar("com.android.systemui", "com.android.systemui.globalactions.GlobalActionsDialogLite", emptyList()))
        assertTrue(ehMenuDeDesligar("com.android.systemui", "android.widget.FrameLayout", listOf("Power off", "Restart", "Emergency call")))
        assertTrue(ehMenuDeDesligar("com.android.systemui", null, listOf("Desligar", "Reiniciar")))
        assertTrue(ehMenuDeDesligar("com.samsung.android.globalactions.ui", null, listOf("Power off")))
        // Outra janela do sistema (a barra de notificações, um aviso) não é
        assertFalse(ehMenuDeDesligar("com.android.systemui", "android.widget.FrameLayout", listOf("Wi-Fi", "Bluetooth", "Battery 80%")))
        // Um app comum com o texto "restart" (um jogo, uma página) não é
        assertFalse(ehMenuDeDesligar("com.instagram.android", "GlobalActionsDialog", listOf("Power off")))
        assertFalse(ehMenuDeDesligar(null, "GlobalActionsDialog", listOf("Power off")))
    }

    @Test
    fun fasesDosBotoesDoTeste() {
        // Conversa + caminhada: conversa › prazo › caminhada
        assertEquals(FaseDoTeste.CONVERSA, faseDoTeste(conversa = true, caminhada = false, noPrazo = false, verificacao = false, metadeVerificada = false, naCamera = false))
        assertEquals(FaseDoTeste.PREPARO, faseDoTeste(conversa = false, caminhada = true, noPrazo = true, verificacao = false, metadeVerificada = false, naCamera = false))
        assertEquals(FaseDoTeste.CAMINHADA, faseDoTeste(conversa = false, caminhada = true, noPrazo = false, verificacao = false, metadeVerificada = false, naCamera = false))
        // Câmera + frases: câmera fácil › frases › câmera exigente
        assertEquals(FaseDoTeste.CAMERA_FACIL, faseDoTeste(conversa = false, caminhada = false, noPrazo = false, verificacao = false, metadeVerificada = false, naCamera = true))
        assertEquals(FaseDoTeste.FRASES, faseDoTeste(conversa = false, caminhada = false, noPrazo = false, verificacao = true, metadeVerificada = false, naCamera = true))
        assertEquals(FaseDoTeste.CAMERA_EXIGENTE, faseDoTeste(conversa = false, caminhada = false, noPrazo = false, verificacao = false, metadeVerificada = true, naCamera = true))
        // Entre uma etapa e outra (a música, o bom dia, a espera da IA): nenhuma
        assertNull(faseDoTeste(conversa = false, caminhada = false, noPrazo = false, verificacao = false, metadeVerificada = false, naCamera = false))
        // Voltar e avançar, nas pontas não
        assertNull(FaseDoTeste.CONVERSA.anterior())
        assertEquals(FaseDoTeste.PREPARO, FaseDoTeste.CONVERSA.proxima())
        assertEquals(FaseDoTeste.PREPARO, FaseDoTeste.CAMINHADA.anterior())
        assertNull(FaseDoTeste.CAMINHADA.proxima())
        assertEquals(FaseDoTeste.FRASES, FaseDoTeste.CAMERA_EXIGENTE.anterior())
        assertNull(FaseDoTeste.CAMERA_EXIGENTE.proxima())
        assertEquals(FASES_DA_CAMERA, FaseDoTeste.FRASES.fases())
        assertEquals(FASES_DA_CAMINHADA, FaseDoTeste.PREPARO.fases())
        // Sem frases nos ajustes, a câmera vai da fácil direto pra exigente
        assertEquals(listOf(FaseDoTeste.CAMERA_FACIL, FaseDoTeste.CAMERA_EXIGENTE), FaseDoTeste.CAMERA_FACIL.fases(comFrases = false))
        assertEquals(FaseDoTeste.CAMERA_EXIGENTE, FaseDoTeste.CAMERA_FACIL.proxima(comFrases = false))
        assertEquals(FaseDoTeste.CAMERA_FACIL, FaseDoTeste.CAMERA_EXIGENTE.anterior(comFrases = false))
        assertEquals(FASES_DA_CAMINHADA, FaseDoTeste.CONVERSA.fases(comFrases = false))
    }
}
