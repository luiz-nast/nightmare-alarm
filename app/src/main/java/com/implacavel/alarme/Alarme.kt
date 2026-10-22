package com.implacavel.alarme

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt

/** Volume do alarme tocando: começa em [inicio] e sobe em linha reta até [fim] em [ms] (frações do volume de alarme do celular). */
data class Subida(val inicio: Float, val fim: Float, val ms: Long)

/**
 * Alarme e teste, de começo: de 10% a 40% em 30 s, exato (mesmo com o celular mais alto). É música, pra ouvir um
 * pouco antes de acordar, e o teste é feito em casa, com a família por perto. O dono muda o começo e o fim nos
 * ajustes ([Tempos]); a subida de cada alarme fica gravada nele quando toca ([EmAndamento.subida]).
 */
val SUBIDA_ALARME = Subida(0.10f, 0.40f, 30_000)

/** Remédio: um toque suave, sem a música, de 15% a 75% em 2 min. */
val SUBIDA_REMEDIO = Subida(0.15f, 0.75f, 120_000)

/**
 * A voz do app (bom dia, avisos, frases, a IA, parabéns) de começo: 30%. O dono muda na tela principal (Ajustes ▾,
 * [Ajustes.volumeDaVoz]); instalado de novo, volta pra cá. 45% ficou alto.
 */
const val VOLUME_DA_VOZ = 0.30f

/**
 * Um alarme salvo pelo usuário, com as regras de quando ele toca (e, no fim do arquivo, o
 * [EmAndamento], o alarme que tocou e ainda não foi cumprido). Sem dependência do Android (fora a
 * serialização JSON), por isso as regras são testadas em AlarmeTest.
 *
 * @property dias dias em que repete, no padrão DayOfWeek.value (1 = segunda … 7 = domingo).
 *   Vazio = toca uma vez e se desliga sozinho.
 * @property missao só desliga dizendo STOP e olhando pra câmera de olhos abertos (veja Missao.kt).
 * @property remedio lembrete de remédio: toca um toque suave do app em vez da música, subindo como
 *   [SUBIDA_REMEDIO].
 * @property caminhada despertador "conversa + caminhada": depois do STOP, a conversa com a IA do celular, o prazo pra
 *   sair e a caminhada na rua, chova ou faça sol (sem a IA, direto pra caminhada); os tempos são os dos ajustes
 *   ([Tempos]: de começo 10, 4 e 10 min). Sem ele, o despertador é o "câmera + frases". Só vale com [missao].
 * @property som o som deste alarme: o nome de um arquivo da biblioteca ([Sons]); null = o padrão da biblioteca (ou,
 *   sem padrão, o toque de alarme do celular). O remédio não usa: toca o toque suave do app.
 */
data class Alarme(
    val id: Int,
    val hora: Int,
    val minuto: Int,
    val rotulo: String = "",
    val dias: Set<Int> = emptySet(),
    val ativo: Boolean = true,
    val missao: Boolean = true,
    val remedio: Boolean = false,
    val caminhada: Boolean = false,
    val som: String? = null,
) {
    val horario: String get() = hhmm(hora, minuto)

    /** Nome pra mostrar: o rótulo, ou "Alarm" se ele estiver em branco. */
    val nome: String get() = rotulo.ifBlank { "Alarm" }

    /** Um dos alarmes dos botões de teste ([ID_TESTE] e [ID_TESTE_CAMINHADA]): cada etapa pede bem menos tempo (veja [metaOlhar]). */
    val deTeste: Boolean get() = id == ID_TESTE || id == ID_TESTE_CAMINHADA

    /**
     * Um tipo só por alarme: com missão é despertador ("câmera + frases" ou "conversa + caminhada"), sem
     * missão é lembrete de remédio. Versões antigas deixavam missão e remédio juntos, ou nenhum dos dois.
     */
    fun normalizado(): Alarme = copy(remedio = !missao, caminhada = caminhada && missao)

    /** Próximo horário que bate com hora, minuto e dias, estritamente depois de [agora]. */
    fun proximoDisparo(agora: ZonedDateTime): ZonedDateTime {
        var alvo = agora.withHour(hora).withMinute(minuto).withSecond(0).withNano(0)
        if (!alvo.isAfter(agora)) alvo = alvo.plusDays(1)
        if (dias.isNotEmpty()) {
            while (alvo.dayOfWeek.value !in dias) alvo = alvo.plusDays(1)
        }
        return alvo
    }

    fun paraJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("hora", hora)
        .put("minuto", minuto)
        .put("rotulo", rotulo)
        .put("dias", JSONArray(dias.sorted()))
        .put("ativo", ativo)
        .put("missao", missao)
        .put("remedio", remedio)
        .put("caminhada", caminhada)
        .apply { if (som != null) put("som", som) }

    companion object {
        /** Id reservado pro botão "Testar câmera + frases". Os alarmes salvos começam em 1. */
        const val ID_TESTE = 0

        /** Id reservado pro botão "Testar conversa + caminhada". */
        const val ID_TESTE_CAMINHADA = -1

        /** O alarme de um botão de teste: "câmera + frases", ou com [caminhada], "conversa + caminhada". */
        fun teste(caminhada: Boolean = false) =
            if (caminhada) {
                Alarme(id = ID_TESTE_CAMINHADA, hora = 0, minuto = 0, rotulo = "Test talk + walk", caminhada = true)
            } else {
                Alarme(id = ID_TESTE, hora = 0, minuto = 0, rotulo = "Test camera + phrases")
            }

        fun deJson(o: JSONObject): Alarme {
            val dias = o.optJSONArray("dias")
            return Alarme(
                id = o.getInt("id"),
                hora = o.getInt("hora"),
                minuto = o.getInt("minuto"),
                rotulo = o.optString("rotulo"),
                dias = if (dias == null) emptySet() else (0 until dias.length()).map { dias.getInt(it) }.toSet(),
                ativo = o.optBoolean("ativo", true),
                missao = o.optBoolean("missao", true),
                remedio = o.optBoolean("remedio", false),
                caminhada = o.optBoolean("caminhada", false),
                som = o.optString("som").ifEmpty { null },
                // "volumeForte" (e "volumeMaximo"), das versões anteriores, não existe mais: ignorado
            )
        }
    }
}

/**
 * O alarme que tocou e ainda não foi cumprido, com tudo pra ele voltar igual se o app morrer: o
 * [alarme] como estava, quando tocou ([desde], epoch em ms), a [musica] (URI: o som da biblioteca que o alarme
 * toca, [Sons.uriDoAlarme]; null = o toque do celular), o [volume] de alarme de antes (a Sirene o devolve no fim;
 * veja [volumeAgora]) e os [tempos] dos ajustes na hora em que tocou (a subida do volume da música e quanto cada etapa
 * pede). Editar o alarme, trocar o som, mexer nos ajustes ou abaixar o volume depois não muda nada: mexer nos ajustes
 * com o alarme tocando encurtaria a missão, e isso seria uma saída.
 */
data class EmAndamento(val alarme: Alarme, val desde: Long, val musica: String?, val volume: Int, val tempos: Tempos = TEMPOS_PADRAO) {
    /**
     * Outro alarme disparou no meio, [agora] (epoch em ms), com a música dele ([musicaDoOutro]). Teste no meio de um
     * alarme não muda nada. Alarme de verdade no meio do teste toma o lugar dele, com as regras e a música dele (a
     * câmera do teste pede bem menos, veja [metaOlhar]), tocando desde agora e com o volume de antes do teste. Dois
     * alarmes de verdade viram um: este continua, com a exigência maior dos dois (com a música deste, ou a do outro
     * se este não tiver; e com a conversa + caminhada, se um deles tiver).
     */
    fun juntar(outro: Alarme, agora: Long, musicaDoOutro: String? = musica): EmAndamento = when {
        outro.deTeste -> this
        alarme.deTeste -> copy(alarme = outro, desde = agora, musica = musicaDoOutro)
        else -> copy(
            alarme = alarme.copy(
                missao = alarme.missao || outro.missao,
                remedio = alarme.remedio && outro.remedio,
                caminhada = alarme.caminhada || outro.caminhada,
            ),
            musica = musica ?: musicaDoOutro,
        )
    }

    /**
     * Volume de alarme em [agoraMs] (epoch), numa escala até [maximo], pela subida do alarme ([subida]: a dos
     * [tempos] gravados; no remédio, [SUBIDA_REMEDIO]; num teste, [SUBIDA_ALARME], a de sempre, pra um teste em casa
     * nunca tocar alto) desde que tocou ([desde]); no fim dela, fica lá. Depois de o app cair, continua de onde estava.
     * Relógio que voltou (antes de [desde]) conta como subida feita: o volume só sobe. A Sirene põe o volume nele no
     * começo e não deixa abaixar dele (subir pode).
     */
    fun volumeAgora(maximo: Int, agoraMs: Long): Int {
        val s = subida
        val passou = agoraMs - desde
        val subiu = if (passou < 0) 1f else (passou.toFloat() / s.ms).coerceAtMost(1f)
        return (maximo * (s.inicio + (s.fim - s.inicio) * subiu)).roundToInt()
    }

    /** Volume dos sons de aviso (bipe positivo, plim, parabéns): o do fim da subida (de começo, 40%), sempre, mesmo que a subida não tenha acabado. */
    fun volumeDosAvisos(maximo: Int): Int = (maximo * subida.fim).roundToInt()

    /** A subida do volume deste alarme (veja [volumeAgora]). */
    val subida: Subida
        get() = when {
            alarme.remedio -> SUBIDA_REMEDIO
            alarme.deTeste -> SUBIDA_ALARME
            else -> tempos.subida
        }

    /**
     * Volume da voz do app: o degrau do volume de alarme logo acima da [fracao] do máximo (a escolhida na tela
     * principal; de começo, [VOLUME_DA_VOZ]), de 1 ao máximo. O resto vem do [ganhoDaVoz]: com 15 degraus, 30% e 35%
     * cairiam no mesmo degrau.
     */
    fun volumeDaVoz(maximo: Int, fracao: Float = VOLUME_DA_VOZ): Int = ceil(maximo * fracao - 0.001).toInt().coerceIn(1, maximo)

    /**
     * Quanto da fala toca dentro do degrau de [volumeDaVoz] (de 0 a 1), pra ficar na [fracao] escolhida, pela conta
     * simples (os degraus como se fossem iguais). A Sirene usa [ganhoEntreDegraus], em dB, quando o Android diz os dB.
     */
    fun ganhoDaVoz(maximo: Int, fracao: Float = VOLUME_DA_VOZ): Float = (maximo * fracao / volumeDaVoz(maximo, fracao)).coerceIn(0.05f, 1f)

    fun paraJson(): JSONObject = JSONObject()
        .put("alarme", alarme.paraJson())
        .put("desde", desde)
        .put("volume", volume)
        .put("tempos", tempos.paraJson())
        .apply { if (musica != null) put("musica", musica) }

    companion object {
        fun deJson(o: JSONObject) = EmAndamento(
            alarme = Alarme.deJson(o.getJSONObject("alarme")),
            desde = o.getLong("desde"),
            musica = if (o.has("musica")) o.getString("musica") else null,
            volume = o.getInt("volume"),
            // Gravação de antes dos ajustes: os tempos de sempre
            tempos = o.optJSONObject("tempos")?.let(Tempos::deJson) ?: TEMPOS_PADRAO,
        )
    }
}

/**
 * Onde a missão do alarme em andamento estava ([desde] diz de qual alarme): o anel da câmera ([olhadoMs]) e se a
 * verificação do meio já foi feita; no "conversa + caminhada", o caminho e a etapa da manhã (pelo nome), o tempo de
 * conversa pura e os extras, o tempo andado, quanto faltava do prazo pra sair ([prazoRestanteMs]; null = ainda não
 * começou) e a mini tarefa do prazo (se o extra já foi [preparoExtraUsado] ou se ela foi [miniTarefaRetirada]). O
 * serviço grava a cada poucos segundos ([Ajustes.progresso]): se o app morrer ou o celular desligar, o alarme volta
 * tocando e, depois do STOP, continua daqui.
 */
data class Progresso(
    val desde: Long,
    val olhadoMs: Long = 0,
    val metadeVerificada: Boolean = false,
    val caminho: String? = null,
    val etapa: String? = null,
    val conversadoMs: Long = 0,
    val extras: Int = 0,
    val andadoMs: Long = 0,
    val prazoRestanteMs: Long? = null,
    val preparoExtraUsado: Boolean = false,
    val miniTarefaRetirada: Boolean = false,
) {
    fun paraJson(): JSONObject = JSONObject()
        .put("desde", desde)
        .put("olhadoMs", olhadoMs)
        .put("metadeVerificada", metadeVerificada)
        .put("conversadoMs", conversadoMs)
        .put("extras", extras)
        .put("andadoMs", andadoMs)
        .put("preparoExtraUsado", preparoExtraUsado)
        .put("miniTarefaRetirada", miniTarefaRetirada)
        .apply {
            caminho?.let { put("caminho", it) }
            etapa?.let { put("etapa", it) }
            prazoRestanteMs?.let { put("prazoRestanteMs", it) }
        }

    companion object {
        fun deJson(o: JSONObject) = Progresso(
            desde = o.getLong("desde"),
            olhadoMs = o.optLong("olhadoMs"),
            metadeVerificada = o.optBoolean("metadeVerificada"),
            caminho = o.optString("caminho").ifEmpty { null },
            etapa = o.optString("etapa").ifEmpty { null },
            conversadoMs = o.optLong("conversadoMs"),
            extras = o.optInt("extras"),
            andadoMs = o.optLong("andadoMs"),
            prazoRestanteMs = if (o.has("prazoRestanteMs")) o.getLong("prazoRestanteMs") else null,
            preparoExtraUsado = o.optBoolean("preparoExtraUsado"),
            miniTarefaRetirada = o.optBoolean("miniTarefaRetirada"),
        )
    }
}

/**
 * O ganho da fala no degrau de cima, pra ela soar [fracaoDoDegrau] (de 0 a 1) do caminho entre o degrau de baixo
 * ([dbBaixo]) e o de cima ([dbCima]), em dB: os degraus do volume do Android são em dB, e o ganho do MediaPlayer é
 * linear. Com 15 degraus de uns 3 dB, 30% e 35% soam diferente.
 */
fun ganhoEntreDegraus(fracaoDoDegrau: Float, dbBaixo: Float, dbCima: Float): Float {
    if (!dbBaixo.isFinite() || !dbCima.isFinite() || dbCima <= dbBaixo) return 1f
    val fracao = fracaoDoDegrau.coerceIn(0f, 1f)
    return 10.0.pow(((fracao - 1) * (dbCima - dbBaixo) / 20).toDouble()).toFloat().coerceIn(0.05f, 1f)
}
