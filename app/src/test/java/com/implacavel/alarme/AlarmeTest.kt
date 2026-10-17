package com.implacavel.alarme

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Regras de quando o alarme toca ([Alarme.proximoDisparo]), nome, JSON, tipo, testes e o alarme em andamento ([EmAndamento]). */
class AlarmeTest {
    private val zona = ZoneId.of("America/Sao_Paulo")

    // 29/09/2026 é uma terça-feira
    private fun em(mes: Int, dia: Int, hora: Int, minuto: Int) = ZonedDateTime.of(2026, mes, dia, hora, minuto, 0, 0, zona)

    @Test
    fun umaVezMaisTardeNoMesmoDia() {
        assertEquals(em(9, 29, 22, 0), Alarme(1, 22, 0).proximoDisparo(em(9, 29, 21, 30)))
    }

    @Test
    fun horarioQueJaPassouFicaParaAmanha() {
        assertEquals(em(9, 30, 7, 0), Alarme(1, 7, 0).proximoDisparo(em(9, 29, 21, 30)))
    }

    @Test
    fun exatamenteAgoraFicaParaAmanha() {
        assertEquals(em(9, 30, 7, 0), Alarme(1, 7, 0).proximoDisparo(em(9, 29, 7, 0)))
    }

    @Test
    fun diasUteisNaSextaDeNoitePulaParaSegunda() {
        val alarme = Alarme(1, 7, 0, dias = setOf(1, 2, 3, 4, 5))
        assertEquals(em(10, 5, 7, 0), alarme.proximoDisparo(em(10, 2, 21, 0)))
    }

    @Test
    fun soDomingo() {
        val alarme = Alarme(1, 9, 30, dias = setOf(7))
        assertEquals(em(10, 4, 9, 30), alarme.proximoDisparo(em(9, 29, 10, 0)))
    }

    @Test
    fun nomeSemRotuloViraAlarme() {
        assertEquals("Alarm", Alarme(1, 7, 0, rotulo = " ").nome)
        assertEquals("Remédio", Alarme(1, 7, 0, rotulo = "Remédio").nome)
    }

    @Test
    fun jsonIdaEVolta() {
        // A foto do alarme em andamento (Ajustes.emAndamento) depende disto
        val alarme = Alarme(3, 6, 45, rotulo = "Academia", dias = setOf(1, 3, 5), ativo = false, missao = false, remedio = true)
        assertEquals(alarme, Alarme.deJson(alarme.paraJson()))
        assertEquals(Alarme.teste(), Alarme.deJson(Alarme.teste().paraJson()))
        val caminhada = Alarme(4, 6, 0, rotulo = "Caminhada", dias = setOf(1, 2, 3, 4, 5), caminhada = true)
        assertEquals(caminhada, Alarme.deJson(caminhada.paraJson()))
        assertTrue(caminhada.paraJson().getBoolean("caminhada"))
        assertEquals(Alarme.teste(caminhada = true), Alarme.deJson(Alarme.teste(caminhada = true).paraJson()))
        // O som da biblioteca, por alarme; sem ele, nada no JSON (e o JSON antigo, sem "som", lê como nenhum)
        val comSom = Alarme(5, 7, 0, som = "Raya.mp3")
        assertEquals(comSom, Alarme.deJson(comSom.paraJson()))
        assertFalse(caminhada.paraJson().has("som"))
        assertNull(Alarme.deJson(caminhada.paraJson()).som)
    }

    @Test
    fun nomesDaBibliotecaDeSons() {
        assertEquals("Raya.mp3", nomeSeguro("Raya.mp3"))
        assertEquals("a_b_c.mp3", nomeSeguro("a/b\\c.mp3"))
        assertEquals("Sound", nomeSeguro(null))
        assertEquals("Sound", nomeSeguro("  "))
        assertEquals("Sound", nomeSeguro(".."))
        assertEquals("Raya.mp3", nomeLivre("Raya.mp3", listOf("SEM CONTROLE.mp3")))
        assertEquals("Raya (2).mp3", nomeLivre("Raya.mp3", listOf("Raya.mp3")))
        assertEquals("Raya (3).mp3", nomeLivre("Raya.mp3", listOf("Raya.mp3", "Raya (2).mp3")))
        assertEquals("semextensao (2)", nomeLivre("semextensao", listOf("semextensao")))
    }

    @Test
    fun doisBotoesDeTeste() {
        val comum = Alarme.teste()
        val caminhada = Alarme.teste(caminhada = true)
        assertTrue(comum.deTeste)
        assertTrue(caminhada.deTeste)
        assertFalse(comum.caminhada)
        assertTrue(caminhada.caminhada && caminhada.missao)
        // Ids diferentes e nunca o de um alarme salvo (esses começam em 1)
        assertEquals(-1, Alarme.ID_TESTE_CAMINHADA)
        assertTrue(Alarme.ID_TESTE_CAMINHADA != Alarme.ID_TESTE && Alarme.ID_TESTE_CAMINHADA < 1)
        assertFalse(Alarme(1, 7, 0).deTeste)
        assertFalse(Alarme(1, 7, 0, caminhada = true).deTeste)
        // Se um câmera + frases tomar o lugar dele, a câmera do teste também é curta
        assertEquals(META_OLHAR_TESTE_MS, metaOlhar(caminhada))
    }

    @Test
    fun umTipoSoPorAlarme() {
        // Versões antigas: missão e remédio juntos viram despertador; nenhum dos dois, lembrete
        assertEquals(Alarme(1, 7, 0, missao = true, remedio = false), Alarme(1, 7, 0, missao = true, remedio = true).normalizado())
        assertEquals(Alarme(1, 7, 0, missao = false, remedio = true), Alarme(1, 7, 0, missao = false, remedio = false).normalizado())
        // A conversa e caminhada só vale com missão
        assertEquals(Alarme(1, 7, 0, missao = false, remedio = true), Alarme(1, 7, 0, missao = false, caminhada = true).normalizado())
        assertEquals(Alarme(1, 7, 0, caminhada = true), Alarme(1, 7, 0, caminhada = true).normalizado())
        assertEquals(Alarme(1, 7, 0), Alarme(1, 7, 0).normalizado())
    }

    @Test
    fun fotoDoAlarmeEmAndamentoIdaEVolta() {
        val em = EmAndamento(Alarme(3, 6, 45, rotulo = "Academia"), desde = 1_790_000_000_000, musica = "content://musica/1", volume = 7)
        assertEquals(em, EmAndamento.deJson(em.paraJson()))
        val semMusica = em.copy(musica = null)
        assertEquals(semMusica, EmAndamento.deJson(semMusica.paraJson()))
        val caminhada = em.copy(alarme = Alarme(4, 6, 0, caminhada = true))
        assertEquals(caminhada, EmAndamento.deJson(caminhada.paraJson()))
    }

    @Test
    fun doisAlarmesJuntosFicamComAExigenciaMaior() {
        // Pré-alarme sem missão ainda tocando quando o alarme de verdade dispara
        val preAlarme = EmAndamento(Alarme(1, 6, 55, missao = false), desde = 0, musica = null, volume = 5)
        val junto = preAlarme.juntar(Alarme(2, 7, 0, missao = true), agora = 300_000)
        assertTrue(junto.alarme.missao)
        assertEquals(1, junto.alarme.id) // segue o mesmo alarme, desde a mesma hora, com a música e o volume de antes
        assertEquals(0L, junto.desde)
        assertEquals(5, junto.volume)
        // Juntar com um alarme mais fraco não afrouxa nada
        assertEquals(junto, junto.juntar(Alarme(3, 7, 5, missao = false), agora = 600_000))
        // Sem música, pega a do outro; com a sua, fica com ela
        assertEquals("file:///sons/a.mp3", junto.juntar(Alarme(4, 7, 5), agora = 600_000, musicaDoOutro = "file:///sons/a.mp3").musica)
        val comMusica = junto.copy(musica = "file:///sons/b.mp3")
        assertEquals("file:///sons/b.mp3", comMusica.juntar(Alarme(4, 7, 5), agora = 600_000, musicaDoOutro = "file:///sons/a.mp3").musica)
    }

    @Test
    fun alarmeDeVerdadeNoMeioDoTesteTomaOLugarDele() {
        val teste = EmAndamento(Alarme.teste(), desde = 0, musica = "content://musica/1", volume = 3)
        val junto = teste.juntar(Alarme(5, 7, 0), agora = 99)
        assertEquals(EmAndamento(Alarme(5, 7, 0), desde = 99, musica = "content://musica/1", volume = 3), junto)
        assertEquals(META_OLHAR_MS, metaOlhar(junto.alarme)) // 40 min, não os 30 s do teste
        assertEquals(99L, junto.desde) // a subida do volume recomeça com ele
        // Com as regras dele: alarme sem missão continua sem missão
        assertFalse(teste.juntar(Alarme(6, 7, 0, missao = false), agora = 99).alarme.missao)
        // O contrário: o teste no meio do alarme de verdade não muda nada
        val deVerdade = EmAndamento(Alarme(5, 7, 0, missao = false), desde = 0, musica = null, volume = 3)
        assertEquals(deVerdade, deVerdade.juntar(Alarme.teste(), agora = 99))
    }

    @Test
    fun conversaECaminhadaAoJuntar() {
        // Dois alarmes de verdade: se um tem conversa e caminhada, o junto tem
        val comum = EmAndamento(Alarme(1, 6, 0), desde = 0, musica = null, volume = 5)
        val junto = comum.juntar(Alarme(2, 6, 0, caminhada = true), agora = 10)
        assertEquals(EmAndamento(Alarme(1, 6, 0, caminhada = true), desde = 0, musica = null, volume = 5), junto)
        // E não perde por juntar com um alarme comum ou com um lembrete sem missão
        val caminhada = EmAndamento(Alarme(2, 6, 0, caminhada = true), desde = 0, musica = null, volume = 5)
        assertEquals(caminhada, caminhada.juntar(Alarme(3, 6, 5), agora = 10))
        assertEquals(caminhada, caminhada.juntar(Alarme(4, 6, 5, missao = false, remedio = true), agora = 10))
        // O teste da caminhada no meio de um alarme de verdade não muda nada
        assertEquals(comum, comum.juntar(Alarme.teste(caminhada = true), agora = 10))
        // Alarme de verdade no meio do teste da caminhada toma o lugar dele, com as regras dele
        val teste = EmAndamento(Alarme.teste(caminhada = true), desde = 0, musica = null, volume = 3)
        assertEquals(EmAndamento(Alarme(5, 7, 0), desde = 99, musica = null, volume = 3), teste.juntar(Alarme(5, 7, 0), agora = 99))
        // Um teste no meio do outro: segue o primeiro
        assertEquals(teste, teste.juntar(Alarme.teste(), agora = 99))
        val testeComum = EmAndamento(Alarme.teste(), desde = 0, musica = null, volume = 3)
        assertEquals(testeComum, testeComum.juntar(Alarme.teste(caminhada = true), agora = 99))
    }

    @Test
    fun volumeSobeDe10A40PorCentoEm30Segundos() {
        // 15 degraus de volume de alarme, como o Galaxy S24 FE; igual no teste e qualquer que fosse o volume antes
        for (em in listOf(EmAndamento(Alarme(1, 7, 0), desde = 1_000, musica = null, volume = 12), EmAndamento(Alarme.teste(), desde = 1_000, musica = null, volume = 2))) {
            assertEquals(2, em.volumeAgora(maximo = 15, agoraMs = 1_000)) // 10% (1,5)
            assertEquals(4, em.volumeAgora(maximo = 15, agoraMs = 16_000)) // 25% na metade
            assertEquals(6, em.volumeAgora(maximo = 15, agoraMs = 31_000)) // 40%
            assertEquals(6, em.volumeAgora(maximo = 15, agoraMs = 600_000)) // e fica
            assertEquals(6, em.volumeAgora(maximo = 15, agoraMs = em.desde - 86_400_000)) // relógio voltou um dia: não baixa
        }
        // Remédio: de 15% a 75% em 2 min
        val remedio = EmAndamento(Alarme(2, 8, 0, missao = false, remedio = true), desde = 0, musica = null, volume = 5)
        assertEquals(2, remedio.volumeAgora(maximo = 15, agoraMs = 0)) // 15% (2,25)
        assertEquals(7, remedio.volumeAgora(maximo = 15, agoraMs = 60_000)) // 45% (6,75)
        assertEquals(11, remedio.volumeAgora(maximo = 15, agoraMs = 120_000)) // 75% (11,25)
        assertEquals(11, remedio.volumeAgora(maximo = 15, agoraMs = 900_000))
        // Os avisos saem sempre no fim da subida, mesmo logo no começo dela; a voz, de começo a 30%, um degrau abaixo
        val alarme = EmAndamento(Alarme(1, 7, 0), desde = 0, musica = null, volume = 2)
        assertEquals(6, alarme.volumeDosAvisos(maximo = 15)) // 40%
        assertEquals(11, remedio.volumeDosAvisos(maximo = 15)) // 75%
        assertEquals(5, alarme.volumeDaVoz(maximo = 15)) // 30% (4,5): o degrau 5, tocando a 90% dentro dele
        assertEquals(0.9f, alarme.ganhoDaVoz(maximo = 15), 0.001f)
        assertEquals(6, alarme.volumeDaVoz(maximo = 15, fracao = 0.35f)) // 35% não cai no mesmo degrau dos 30%
        assertEquals(0.875f, alarme.ganhoDaVoz(maximo = 15, fracao = 0.35f), 0.001f)
        assertEquals(9, alarme.volumeDaVoz(maximo = 15, fracao = 0.6f)) // conta exata (9,0000004) fica no 9
        assertEquals(1f, alarme.ganhoDaVoz(maximo = 15, fracao = 0.6f), 0.001f)
        assertEquals(1, alarme.volumeDaVoz(maximo = 15, fracao = 0.01f)) // nunca muda
        assertEquals(15, alarme.volumeDaVoz(maximo = 15, fracao = 1f))
        // Em dB (o Android diz quantos dB tem cada degrau): na metade do caminho entre degraus de 4 dB, 2 dB abaixo
        assertEquals(0.794f, ganhoEntreDegraus(0.5f, dbBaixo = -24f, dbCima = -20f), 0.001f)
        assertEquals(1f, ganhoEntreDegraus(1f, dbBaixo = -24f, dbCima = -20f), 0.001f)
        assertEquals(1f, ganhoEntreDegraus(0.5f, dbBaixo = Float.NEGATIVE_INFINITY, dbCima = -20f), 0.001f) // degrau mudo
        // Remédio junto com um alarme de música: fica a música
        assertFalse(remedio.juntar(Alarme(3, 8, 0), agora = 10).alarme.remedio)
        assertTrue(remedio.juntar(Alarme(4, 8, 0, remedio = true), agora = 10).alarme.remedio)
        // Alarme salvo por uma versão antiga, com a opção de volume forte: abre igual, sem ela
        val antigo = JSONObject("""{"id":4,"hora":5,"minuto":5,"volumeForte":true}""")
        assertEquals(Alarme(4, 5, 5), Alarme.deJson(antigo))
    }

    @Test
    fun jsonDeVersaoAntigaAindaLe() {
        // Versões anteriores gravavam "sonecaAte", que agora é ignorado
        val antigo = JSONObject(
            """{"id":2,"hora":7,"minuto":0,"rotulo":"","dias":[],"ativo":true,"missao":true,"volumeMaximo":true,"sonecaAte":1790000000000}""",
        )
        assertEquals(Alarme(2, 7, 0), Alarme.deJson(antigo))
        assertFalse(Alarme.deJson(antigo).caminhada) // antes da conversa e caminhada: o despertador de sempre
    }

    @Test
    fun progressoSobreviveAoAppMorrer() {
        val p = Progresso(
            desde = 1_000, olhadoMs = 600_000, metadeVerificada = true, caminho = "CONVERSA_E_CAMINHADA", etapa = "CAMINHADA",
            conversadoMs = 300_000, extras = 1, andadoMs = 95_000, prazoRestanteMs = 12_000,
        )
        assertEquals(p, Progresso.deJson(JSONObject(p.paraJson().toString())))
        // Do alarme de sempre, antes de a manhã decidir: sem caminho, etapa nem prazo
        val simples = Progresso(desde = 2_000, olhadoMs = 30_000)
        assertEquals(simples, Progresso.deJson(JSONObject(simples.paraJson().toString())))
    }
}
