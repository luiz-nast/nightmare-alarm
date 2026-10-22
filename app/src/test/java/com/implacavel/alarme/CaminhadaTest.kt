package com.implacavel.alarme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Regras da caminhada da manhã (RegrasCaminhada.kt). */
class CaminhadaTest {

    /** 1 m de latitude, em graus (a Terra de raio 6.371 km). */
    private val metroEmGraus = 1 / 111_195.0

    /** Um fixo em São Paulo, [norteM] metros ao norte (e [lesteM] a leste) do ponto de partida. */
    private fun fixo(
        tempoMs: Long,
        norteM: Double = 0.0,
        lesteM: Double = 0.0,
        precisao: Float? = 4f,
        velocidade: Float? = null,
        precisaoVelocidade: Float? = 0.3f,
        falso: Boolean = false,
        rumo: Float? = null,
        precisaoRumo: Float? = 10f,
    ) = Fixo(
        tempoMs, -23.55 + norteM * metroEmGraus, -46.63 + lesteM * metroEmGraus / cos(Math.toRadians(23.55)),
        precisao, velocidade, precisaoVelocidade, falso, rumo, precisaoRumo,
    )

    /**
     * Uma pessoa de mentira, segundo a segundo: o acelerômetro (50 amostras por segundo: a gravidade, mais o passo como
     * uma onda de [forca] m/s² a [hz] por segundo, mais um tremido) e, no fim de cada segundo, um fixo do GPS. Sempre
     * igual (semente fixa).
     */
    private inner class Pessoa(var contando: Boolean = true) {
        val detector = DetectorDeCaminhada()
        val avaliacoes = mutableListOf<Avaliacao>()
        private val r = Random(7)
        private var t = 0L
        var norte = 0.0
        var leste = 0.0

        val somado get() = avaliacoes.sumOf { it.soma }

        /**
         * [segundos] assim: passos a [hz] (0 = sem passo), indo a [v] m/s pro [rumo] (graus). O GPS mede a posição
         * com [erro] (leste, norte) no segundo i, a velocidade [vGps] (null = a de verdade) e o rumo [rumoGps] (null
         * = o de verdade, se andando), com [precisao]; [semFixo] pula o fixo do segundo.
         */
        fun fazer(
            segundos: Int,
            hz: Double = 0.0,
            forca: Double = 2.5,
            v: Double = 0.0,
            rumo: (Int) -> Double = { 0.0 },
            erro: (Int) -> Pair<Double, Double> = { 0.0 to 0.0 },
            vGps: ((Int) -> Float?)? = null,
            rumoGps: ((Int) -> Float?)? = null,
            precisao: Float = 4f,
            falso: Boolean = false,
            semFixo: (Int) -> Boolean = { false },
        ): List<Avaliacao> {
            val estas = mutableListOf<Avaliacao>()
            repeat(segundos) { i ->
                repeat(50) {
                    t += 20
                    val onda = if (hz > 0) forca * sin(2 * PI * hz * t / 1000.0) else 0.0
                    detector.acelerometro(t, (9.81 + onda + r.nextGaussian() * 0.03).toFloat())
                }
                val rumoAgora = rumo(i)
                leste += v * sin(Math.toRadians(rumoAgora))
                norte += v * cos(Math.toRadians(rumoAgora))
                if (semFixo(i)) return@repeat
                val (el, en) = erro(i)
                val f = fixo(
                    t, norteM = norte + en, lesteM = leste + el, precisao = precisao,
                    velocidade = vGps?.invoke(i) ?: v.toFloat(), falso = falso,
                    rumo = if (rumoGps != null) rumoGps(i) else if (v >= 0.3) rumoAgora.toFloat() else null,
                )
                estas += detector.fixo(f, chegouMs = t + 150, contando = contando)
            }
            avaliacoes += estas
            return estas
        }
    }

    /** Os passos de quem anda: [hz] por segundo, de [forca] m/s². */
    private fun DetectorDePassos.andar(segundos: Double, hz: Double, forca: Double = 2.5, desdeMs: Long = 0): Long? {
        var andandoEm: Long? = null
        var t = desdeMs
        while (t < desdeMs + segundos * 1000) {
            t += 20
            amostra(t, (9.81 + forca * sin(2 * PI * hz * t / 1000.0)).toFloat())
            if (andando && andandoEm == null) andandoEm = t - desdeMs
        }
        return andandoEm
    }

    @Test
    fun passosDeQuemAnda() {
        // Andando (1,8 passo por segundo): reconhece em uns 3 s, a ~108 passos por minuto
        val passos = DetectorDePassos()
        assertTrue(passos.andar(10.0, hz = 1.8)!! <= 3_500)
        assertEquals(108f, passos.cadencia!!, 5f)
        // Parado: nunca
        val parado = DetectorDePassos()
        assertNull(parado.andar(20.0, hz = 1.8, forca = 0.02))
        // Sacudindo rápido (4 por segundo): suavizado em 0,2 s, o pico fica fraco demais pra ser passo
        assertNull(DetectorDePassos().andar(20.0, hz = 4.0, forca = 3.0))
        // Correndo (3 por segundo): é passo, no ritmo de quem corre (dois picos colados contam como um só, não derrubam o ritmo)
        val correndo = DetectorDePassos()
        assertTrue(correndo.andar(10.0, hz = 3.0, forca = 4.0) != null)
        assertEquals(180f, correndo.cadencia!!, 10f)
        // Parou: deixa de andar em 2 s
        passos.andar(3.0, hz = 1.8, forca = 0.0, desdeMs = 10_000)
        assertTrue(!passos.andando)
    }

    @Test
    fun andarNaRuaContaEmPoucosSegundos() {
        val p = Pessoa()
        p.fazer(60) // em casa, parado, o GPS já ligado
        val andando = p.fazer(60, hz = 1.8, v = 1.2, rumo = { 30.0 })
        val primeira = andando.indexOfFirst { it.movimento == Movimento.ANDANDO }
        assertTrue("começou a somar em ${primeira + 1} s", primeira in 0..14)
        // Os segundos conferindo somam de uma vez quando sai do lugar: perde só os primeiros
        assertTrue("somou ${p.somado} ms", p.somado >= 52_000)
        assertTrue(andando.drop(primeira).all { it.movimento == Movimento.ANDANDO })
        assertTrue(andando.drop(8).all { it.valeProBipe }) // os bipes calam logo
        assertEquals(1.2f, andando.last().velocidade!!, 0.01f)
    }

    @Test
    fun andarDevagarConta() {
        // Andando devagar, com sono (0,7 m/s, uns 84 passos por minuto): reto, e em volta de uma piscina de 25 x 12 m
        val reto = Pessoa()
        reto.fazer(60)
        reto.fazer(180, hz = 1.4, v = 0.7, rumo = { 45.0 })
        assertTrue("reto somou ${reto.somado} ms", reto.somado >= 150_000)
        val piscina = Pessoa()
        piscina.fazer(60)
        piscina.fazer(180, hz = 1.4, v = 0.7, rumo = { i ->
            when ((i * 0.7) % 74) {
                in 0.0..25.0 -> 90.0
                in 25.0..37.0 -> 0.0
                in 37.0..62.0 -> 270.0
                else -> 180.0
            }
        })
        assertTrue("piscina somou ${piscina.somado} ms", piscina.somado >= 150_000)
    }

    @Test
    fun paradoComOGpsEscorregandoNaoConta() {
        // Como em 10-17: o ponto escorregou 15 m pro sul em 70 s, dizendo ±1 m, e a velocidade em 0
        val p = Pessoa()
        val tudo = p.fazer(120, erro = { i -> 0.0 to -minOf(i, 70) * 0.21 }, precisao = 1f)
        assertEquals(0L, p.somado)
        assertTrue(tudo.all { it.movimento == Movimento.PARADO })
        // O caminho congela: a trilha não anda sozinha
        val (l, n) = tudo.last().ponto!!
        assertTrue(hypot(l, n) < 1.0)
    }

    @Test
    fun balancarOCelularNaCamaNaoConta() {
        val r = Random(3)
        // O braço balançando: passos no ritmo, e o GPS vendo velocidade de andar, com o rumo pra todo lado (10 sorteios)
        for (semente in 1L..10L) {
            val sorte = Random(semente)
            val p = Pessoa()
            p.fazer(30)
            p.fazer(180, hz = 2.0, forca = 3.0, vGps = { 1.2f }, rumoGps = { sorte.nextFloat() * 360 }, erro = { sorte.nextGaussian() * 3 to sorte.nextGaussian() * 3 })
            assertEquals("sorteio $semente", 0L, p.somado)
        }
        // E com o GPS dizendo sempre o mesmo rumo: o ponto do GPS não anda junto, só treme
        val q = Pessoa()
        q.fazer(30)
        val balancando = q.fazer(180, hz = 2.0, forca = 3.0, vGps = { 1.2f }, rumoGps = { 90f }, erro = { r.nextGaussian() * 1.5 to r.nextGaussian() * 1.5 })
        assertEquals(0L, q.somado)
        assertEquals("não saiu do lugar", balancando.last().motivo) // depois de 1 min conferindo, a tela fica vermelha
        assertTrue(balancando.last().valeProBipe) // mas quem balança está acordado: não bipa, só não soma
    }

    @Test
    fun andarNoLugarNaoConta() {
        val p = Pessoa()
        p.fazer(30)
        val noLugar = p.fazer(90, hz = 1.8)
        assertEquals(0L, p.somado)
        // Passo é sinal de vida: nenhum bipe (depois dos 3 s pra reconhecer o ritmo). Mas sem o GPS ver sair do lugar,
        // fica conferindo e, depois de 1 min, vermelho
        assertTrue(noLugar.drop(5).all { it.valeProBipe })
        assertEquals(Movimento.CONFERINDO, noLugar[30].movimento)
        assertEquals("no lugar", noLugar[30].motivo)
        assertEquals(Movimento.PARADO, noLugar.last().movimento)
        assertEquals("não saiu do lugar", noLugar.last().motivo)
    }

    @Test
    fun corredorCobertoDepoisDaRuaContaPelaConfianca() {
        // 2 min andando na área aberta (o GPS confirma) e, de lá, um corredor coberto: os passos seguem, o GPS perde a
        // velocidade (como no corredor da piscina, 10-17). Soma direto pelos passos, com a confiança que juntou, sem bipe
        val p = Pessoa()
        p.fazer(30)
        p.fazer(120, hz = 1.8, v = 1.2)
        val coberto = p.fazer(60, hz = 1.8, vGps = { 0.2f })
        assertTrue("coberto somou ${coberto.sumOf { it.soma }} ms", coberto.sumOf { it.soma } >= 55_000)
        assertTrue(coberto.all { it.valeProBipe })
        assertTrue(coberto.drop(15).all { it.movimento == Movimento.ANDANDO && it.motivo == "pelos passos" })
        // A confiança (até 90 s) acaba: fica conferindo e, em 1 min, a tela avisa que o GPS não vê. Ainda sem bipe
        val longo = p.fazer(120, hz = 1.8, vGps = { 0.2f })
        assertTrue("longo somou ${longo.sumOf { it.soma }} ms", longo.sumOf { it.soma } in 20_000..45_000)
        assertTrue(longo.all { it.valeProBipe })
        assertEquals("não saiu do lugar", longo.last().motivo)
        // Quem nunca andou na área aberta não tem confiança: os passos na cama (ou em casa) não somam nada
        val cama = Pessoa()
        cama.fazer(30)
        cama.fazer(120, hz = 1.8, vGps = { 0.2f })
        assertEquals(0L, cama.somado)
    }

    @Test
    fun semPassoReconhecidoOGpsSozinhoConta() {
        // O celular quieto na mão, o acelerômetro sem ver passo (10-17 à noite: 15 de 30 s), e o GPS vendo 1 m/s com o
        // ponto andando junto: conta
        val p = Pessoa()
        p.fazer(30)
        val andando = p.fazer(60, v = 1.0)
        assertTrue("somou ${p.somado} ms", p.somado >= 45_000)
        assertEquals("pelo GPS", andando.last().motivo)
        assertTrue(andando.drop(4).all { it.valeProBipe })
        // Mais rápido que isso sem passo é bicicleta: não soma (mas quem pedala está acordado: não bipa)
        val bicicleta = Pessoa()
        bicicleta.fazer(30)
        val pedalando = bicicleta.fazer(60, v = 3.0)
        assertEquals(0L, bicicleta.somado)
        assertEquals("sem passo", pedalando.last().motivo)
        assertEquals(Movimento.PARADO, pedalando.last().movimento)
    }

    @Test
    fun irEVoltarNumCorredorConta() {
        // 3 min indo e voltando num trecho de 15 m é caminhada: só se perdem uns segundos em cada volta
        val p = Pessoa()
        p.fazer(60)
        val corredor = p.fazer(180, hz = 1.8, v = 1.1, rumo = { i -> if (i / 14 % 2 == 0) 0.0 else 180.0 })
        assertTrue("somou ${p.somado} ms", p.somado >= 120_000)
        assertTrue(corredor.drop(10).count { it.valeProBipe } >= 0.75 * (corredor.size - 10)) // os bipes ficam calados
    }

    @Test
    fun umSaltoDoGpsNaoAtrapalhaNemSoma() {
        fun caminhada(salto: Boolean): Pessoa = Pessoa().apply {
            fazer(60)
            fazer(90, hz = 1.8, v = 1.2, erro = { i -> if (salto && i in 40..41) 40.0 to 0.0 else 0.0 to 0.0 })
        }
        val com = caminhada(salto = true)
        val sem = caminhada(salto = false)
        assertEquals(sem.somado.toDouble(), com.somado.toDouble(), 3_000.0)
        // A trilha não pula junto
        val pontos = com.avaliacoes.drop(60).map { it.ponto!! }
        assertTrue(pontos.zipWithNext().all { (a, b) -> hypot(b.first - a.first, b.second - a.second) < 4.0 })
    }

    @Test
    fun oGpsMudarDeLugarNaoTrava() {
        // A posição de dentro de casa estava 25 m errada: andando, o caminho vai atrás (sem saltar) e continua somando
        val p = Pessoa()
        p.fazer(60, erro = { 25.0 to 0.0 })
        val andando = p.fazer(120, hz = 1.8, v = 1.2)
        assertTrue("somou ${p.somado} ms", p.somado >= 100_000)
        val pontos = andando.map { it.ponto!! }
        assertTrue(pontos.zipWithNext().all { (a, b) -> hypot(b.first - a.first, b.second - a.second) < 10.0 })
        // O caminho conta a partir do 1º fixo, que estava 25 m a leste de onde a pessoa estava
        val (l, n) = pontos.last()
        assertEquals(p.leste - 25, l, 3.0)
        assertEquals(p.norte, n, 3.0)
    }

    @Test
    fun paradaNoFarolNaoZera() {
        val p = Pessoa()
        p.fazer(60)
        p.fazer(60, hz = 1.8, v = 1.2)
        val antes = p.somado
        // O farol: o 1º segundo parado ainda soma (os passos levam 2 s pra parar), e nada do que somou some
        val farol = p.fazer(20)
        assertTrue(farol.sumOf { it.soma } <= 2_000)
        assertTrue(farol.drop(3).none { it.valeProBipe })
        val depois = p.fazer(30, hz = 1.8, v = 1.2)
        assertTrue(p.somado >= antes)
        // Voltando a andar: os bipes calam assim que os passos e o GPS confirmam, e só os primeiros segundos se perdem
        assertTrue(depois.drop(6).all { it.valeProBipe })
        assertTrue("somou ${depois.sumOf { it.soma }} ms", depois.sumOf { it.soma } >= 22_000)
    }

    @Test
    fun deCarroOuBicicletaNaoConta() {
        // Carro: sem passo, rápido
        val carro = Pessoa()
        carro.fazer(30)
        val noCarro = carro.fazer(60, hz = 12.0, forca = 0.2, v = 12.0)
        assertEquals(0L, carro.somado)
        assertEquals(Movimento.RAPIDO_DEMAIS, noCarro.last().movimento)
        // Bicicleta devagar com o celular no bolso: a perna dá ritmo, mas o celular anda mais que um passo
        val bicicleta = Pessoa()
        bicicleta.fazer(30)
        val pedalando = bicicleta.fazer(60, hz = 1.4, v = 3.0)
        assertEquals(0L, bicicleta.somado)
        assertEquals("passada longa", pedalando.last().motivo)
        // Depois de ir rápido, nada conta por 30 s, nem andando
        val desceu = carro.fazer(20, hz = 1.8, v = 1.2)
        assertEquals(0L, desceu.sumOf { it.soma })
    }

    @Test
    fun localizacaoFalsaOuGpsImprecisoNaoConta() {
        val falsa = Pessoa()
        falsa.fazer(30)
        assertEquals(Movimento.FALSO, falsa.fazer(60, hz = 1.8, v = 1.2, falso = true).last().movimento)
        assertEquals(0L, falsa.somado)
        val fraco = Pessoa()
        fraco.fazer(30, precisao = 30f)
        assertEquals(Movimento.IMPRECISO, fraco.fazer(60, hz = 1.8, v = 1.2, precisao = 30f).last().movimento)
        assertEquals(0L, fraco.somado)
    }

    @Test
    fun esquentandoNaoSomaEDepoisSomaLogo() {
        // Na conversa (o GPS esquentando), andar não soma nem confere
        val p = Pessoa(contando = false)
        p.fazer(60)
        p.fazer(30, hz = 1.8, v = 1.2)
        assertEquals(0L, p.somado)
        // Na caminhada, já andando e longe de onde estava: soma no 1º segundo
        p.contando = true
        assertEquals(Movimento.ANDANDO, p.fazer(1, hz = 1.8, v = 1.2).single().movimento)
    }

    @Test
    fun buracoNoSinalSomaNoMaximo3Segundos() {
        val p = Pessoa()
        p.fazer(60)
        p.fazer(40, hz = 1.8, v = 1.2)
        // 10 s sem fixo e 6 com: soma no máximo 3 s pelo buraco, mais 1 s por fixo
        val depois = p.fazer(16, hz = 1.8, v = 1.2, semFixo = { it < 10 })
        assertTrue(depois.all { it.soma <= 3_000 })
        assertTrue(depois.sumOf { it.soma } <= 8_000)
    }

    /**
     * Uma gravação (em resources, sem coordenadas: o formato está no cabeçalho dela) passada pela regra, fixo a fixo:
     * a avaliação de cada um pelo tempo (ms desde o começo dela) e, nos fixos de [andandoDe] a [andandoAte] com o GPS
     * a 0,6 m/s ou mais (andando na área aberta), quantos eram e em quantos os passos estavam reconhecidos.
     */
    private inner class Gravacao(arquivo: String, andandoDe: Long, andandoAte: Long) {
        val porTempo = mutableListOf<Pair<Long, Avaliacao>>()
        var fixosAndando = 0
        var passosAndando = 0

        init {
            val detector = DetectorDeCaminhada()
            val linhas = GZIPInputStream(javaClass.getResourceAsStream("/$arquivo")!!).bufferedReader().readLines()
            for (l in linhas.filter { !it.startsWith("#") }) {
                val c = l.split(" ")
                val ms = c[1].toLong()
                if (c[0] == "A") {
                    val amostras = c.drop(2).filter { it.isNotEmpty() }.map { it.toInt() / 10f }
                    amostras.forEachIndexed { i, a -> detector.acelerometro(ms - 1000 + (i + 1) * 1000L / amostras.size, a) }
                } else {
                    fun num(s: String) = s.takeIf { it != "-" }?.toFloat()
                    val f = fixo(
                        ms - c[2].toLong(), norteM = c[4].toDouble(), lesteM = c[3].toDouble(), precisao = num(c[5]),
                        velocidade = num(c[6]), precisaoVelocidade = num(c[7]), rumo = num(c[8]), precisaoRumo = num(c[9]),
                    )
                    porTempo += ms to detector.fixo(f, chegouMs = ms, contando = true)
                    if (ms in andandoDe..andandoAte && (f.velocidadeMs ?: 0f) >= 0.6f) {
                        fixosAndando++
                        if (detector.passos.andando) passosAndando++
                    }
                }
            }
        }

        fun somou(de: Long, ate: Long = Long.MAX_VALUE) = porTempo.filter { it.first in de..ate }.sumOf { it.second.soma }
    }

    /**
     * A 1ª gravação de 10-17: GPS ligando com o celular parado (com um salto de 14 m), sentado balançando o celular, 11
     * min andando em volta da piscina e num corredor (às vezes correndo), e parado de novo. Parado e balançando, nada;
     * andando, quase tudo: na área aberta pelo GPS, e no corredor coberto (o GPS não vê a velocidade) pelos passos, com
     * a confiança da área aberta (somou 542 s pros 457 fixos da área aberta: o corredor entrou). Parando, os 2 s até os
     * passos pararem ainda somam.
     */
    @Test
    fun aGravacaoDaPiscina() {
        val g = Gravacao("caminhada_10-17.txt.gz", andandoDe = 100_000, andandoAte = 770_000)
        println("piscina 1: parado ${g.somou(0, 60_000)} ms, balançando ${g.somou(60_001, 87_000)} ms, andando ${g.somou(87_001, 780_000)} ms (${g.fixosAndando} fixos andando na área aberta), depois ${g.somou(780_001)} ms; passos em ${g.passosAndando}")
        assertEquals(0L, g.somou(0, 87_000))
        assertTrue(g.somou(780_001) <= 5_000)
        assertTrue(g.somou(87_001, 780_000) >= 1_000L * g.fixosAndando)
        assertTrue(g.passosAndando >= 0.9 * g.fixosAndando)
    }

    /** A 2ª gravação de 10-17, sem cabo: 3 min andando normal em volta da piscina, depois parado. */
    @Test
    fun aGravacaoDaPiscinaSemCabo() {
        val g = Gravacao("caminhada_10-17b.txt.gz", andandoDe = 41_000, andandoAte = 226_000)
        println("piscina 2: antes ${g.somou(0, 30_000)} ms, andando ${g.somou(30_001, 235_000)} ms (${g.fixosAndando} fixos andando), depois ${g.somou(235_001)} ms; passos em ${g.passosAndando}")
        assertEquals(0L, g.somou(0, 30_000))
        assertEquals(0L, g.somou(240_001))
        assertTrue(g.somou(30_001, 235_000) >= 800L * g.fixosAndando)
        assertTrue(g.passosAndando >= 0.9 * g.fixosAndando)
    }

    /** A 3ª gravação de 10-17, sem cabo, já com esta regra: 4 min e 20 s andando em volta da piscina, sempre as mesmas voltas. */
    @Test
    fun aGravacaoDaPiscinaComARegraNova() {
        val g = Gravacao("caminhada_10-17c.txt.gz", andandoDe = 23_000, andandoAte = 283_000)
        println("piscina 3: andando ${g.somou(0, 290_000)} ms (${g.fixosAndando} fixos andando), depois ${g.somou(290_001)} ms; passos em ${g.passosAndando}")
        assertTrue(g.somou(0, 290_000) >= 850L * g.fixosAndando)
        assertEquals(0L, g.somou(300_001))
        assertTrue(g.passosAndando >= 0.9 * g.fixosAndando)
    }

    @Test
    fun trilhaEmMetros() {
        val (leste, norte) = emMetros(fixo(0), fixo(0, norteM = 30.0, lesteM = 10.0))
        assertEquals(10.0, leste, 0.05)
        assertEquals(30.0, norte, 0.05)
    }

    @Test
    fun distanciaPeloHaversine() {
        val a = Fixo(0, -23.55, -46.63, 5f)
        assertEquals(0.0, distanciaM(a, a), 0.001)
        assertEquals(111.19, distanciaM(a, a.copy(latitude = -23.549)), 0.05) // 0,001° de latitude
        // 0,001° de longitude encolhe com o cosseno da latitude (em São Paulo, 0,92)
        assertEquals(101.93, distanciaM(a, a.copy(longitude = -46.629)), 0.05)
    }

    @Test
    fun miniTarefaDoPrazoUmaVezSoNoPrimeiroBipe() {
        assertEquals(3 * 60_000L, preparoExtra(Alarme(1, 7, 0, caminhada = true)))
        assertEquals(30_000L, preparoExtra(Alarme.teste(caminhada = true)))
        // Só no 1º bipe de parado depois do prazo, se o extra não foi usado e ela não foi retirada
        assertTrue(ofereceMiniTarefa(bipe = 1, usado = false, retirada = false))
        assertFalse(ofereceMiniTarefa(bipe = 2, usado = false, retirada = false))
        assertFalse(ofereceMiniTarefa(bipe = 1, usado = true, retirada = false))
        assertFalse(ofereceMiniTarefa(bipe = 1, usado = false, retirada = true)) // andou depois do 1º bipe: já acordou
        // A frase do dia: sempre a mesma no dia, e não é uma das 5 da verificação da câmera
        val dia = java.time.LocalDate.of(2026, 10, 22)
        assertEquals(fraseDaMiniTarefa(dia), fraseDaMiniTarefa(dia))
        assertTrue(fraseDaMiniTarefa(dia) in FRASES_MOTIVACIONAIS)
        assertFalse(fraseDaMiniTarefa(dia) in frasesDoDia(dia, 10))
    }

    @Test
    fun caminhadaEPrazoSeguemOsAjustesETeste3Minutos() {
        val alarme = Alarme(1, 7, 0, caminhada = true)
        val teste = Alarme.teste(caminhada = true)
        // De começo: 10 min andando (era 16: o dono pediu 10 em 10-18) e 4 pra sair
        assertEquals(10 * 60_000L, metaCaminhada(alarme))
        assertEquals(4 * 60_000L, prazoSair(alarme))
        // O dono mexeu: 30 min andando, sem prazo (os bipes de parado contam já)
        val tempos = Tempos(caminhadaMin = 30, prepararMin = 0)
        assertEquals(30 * 60_000L, metaCaminhada(alarme, tempos))
        assertEquals(0L, prazoSair(alarme, tempos))
        // O teste não segue os ajustes
        assertEquals(3 * 60_000L, metaCaminhada(teste, tempos))
        assertEquals(30_000L, prazoSair(teste, tempos))
    }

    @Test
    fun bipeDeParadoSoDepoisDoPrazoPraSair() {
        val prazo = 240_000L // 4 min depois do começo da etapa
        // No prazo, nada de bipe: a tela mostra quanto falta dele
        assertNull(segundosProBipe(agora = 220_000, ultimoPasso = 0, prazoAte = prazo))
        assertEquals("You have 3:32 to start walking", avisoDoPrazo(agora = 28_000, prazoAte = prazo))
        assertEquals("You have 20 s to start walking", avisoDoPrazo(agora = 220_000, prazoAte = prazo))
        assertNull(avisoDoPrazo(agora = prazo, prazoAte = prazo))
        // Parado desde antes do fim do prazo: o 1º bipe 23 s depois dele
        assertEquals(prazo + 23_000, primeiroBipeParado(ultimoPasso = 100_000, prazoAte = prazo))
        assertEquals(23, segundosProBipe(agora = prazo, ultimoPasso = 100_000, prazoAte = prazo))
        assertEquals(1, segundosProBipe(agora = prazo + 22_500, ultimoPasso = 100_000, prazoAte = prazo))
        assertEquals(0, segundosProBipe(agora = prazo + 30_000, ultimoPasso = 100_000, prazoAte = prazo)) // já bipando
        // Parou de andar depois do prazo: 23 s depois do último sinal de vida
        assertEquals(prazo + 28_000, primeiroBipeParado(ultimoPasso = prazo + 5_000, prazoAte = prazo))
        assertEquals(20, segundosProBipe(agora = prazo + 8_000, ultimoPasso = prazo + 5_000, prazoAte = prazo))
        assertNull(paradoHa(agora = prazo - 1, ultimoPasso = 0, prazoAte = prazo))
        assertEquals(3_000L, paradoHa(agora = prazo + 8_000, ultimoPasso = prazo + 5_000, prazoAte = prazo))
        assertEquals(8_000L, paradoHa(agora = prazo + 8_000, ultimoPasso = 100_000, prazoAte = prazo))
    }

    @Test
    fun barraDoBipe() {
        // 20 s em paz, bipe fraco aos 23 s, um degrau a cada 3 s, o máximo (7º bipe, som forte) aos 41 s
        assertEquals(41_000L, PARADO_MAXIMO_MS)
        assertEquals(0, bipeDoParado(22_999))
        assertEquals(1, bipeDoParado(23_000))
        assertEquals(1, bipeDoParado(25_999))
        assertEquals(2, bipeDoParado(26_000))
        assertEquals(7, bipeDoParado(41_000))
        assertEquals(listOf(2, 4, 6, 9, 11, 13, 15), (1..BIPES_PARADO_ATE_O_MAXIMO).map { volumeDoBipe(it, 15, BIPES_PARADO_ATE_O_MAXIMO) })
        assertEquals("Stopped 12 s · beeps start in 11 s", textoDoParado(12_000))
        assertEquals("Stopped 0 s · beeps start in 23 s", textoDoParado(0))
        assertEquals("Beep 1 of 7 · maximum in 18 s", textoDoParado(23_000))
        assertEquals("Beep 3 of 7 · maximum in 12 s", textoDoParado(29_000))
        assertEquals("🔊 Maximum · walk and it stops", textoDoParado(41_000))
        assertEquals("🔊 Maximum · walk and it stops", textoDoParado(90_000))
    }

    @Test
    fun textosDaTela() {
        assertEquals("7:12", tempoAndado(432_999)) // o que já foi: não arredonda pra cima
        assertEquals("0:28", tempoAndado(28_400))
        assertEquals("16:00", tempoAndado(minutos(16)))
        assertEquals("1.3 m/s", textoDaVelocidade(1.34f))
        assertEquals("0.0 m/s", textoDaVelocidade(0f))
    }
}
