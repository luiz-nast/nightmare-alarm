// Falas do alarme: a saudação depois do STOP ("Good morning! It's 5:05 AM. It's time for the activity
// check."), o "Activity starting now.", os avisos de 10 em 10 min de câmera e as falas fixas da conversa da
// manhã com a IA. Os textos são funções puras (testadas em MissaoTest); a Voz grava a fala do sintetizador do
// celular num arquivo pra Sirene tocar no alto-falante, como o resto do alarme. Quem decide a hora de cada fala
// é o AlarmeService.
package com.implacavel.alarme

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Good morning" das 4h ao meio-dia, "Good afternoon" até as 18h, "Good evening" no resto. */
fun saudacao(hora: Int): String = when (hora) {
    in 4..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

fun textoDaSaudacao(agora: LocalTime): String =
    "${saudacao(agora.hour)}! It's ${agora.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))}. It's time for the activity check."

const val FALA_INICIO = "Activity starting now."

const val FALA_VERIFICAR = "Let's verify that you are awake. Repeat the following sentences after me."

const val FALA_METADE = "Thank you very much for your effort, sir. You reached the middle of the activity."

const val FALA_FIM = "Congratulations, sir! The activity is done. Have a good time getting back to your activities."

fun falaDosMinutos(minutos: Int) = "$minutos minutes passed."

// Despertador "conversa + caminhada": as falas que não vêm da IA, gravadas junto com as outras fixas (só nesse alarme)

/** A conversa chegou na meta: em vez da IA, esta pergunta (a resposta passa por querContinuar). */
const val FALA_CONTINUAR = "Good talk, sir. Want to keep talking a little more?"

/**
 * Fim da conversa (ou, sem a IA, logo depois do bom dia): a caminhada em seguida, chova ou faça sol, com o prazo pra
 * sair dos ajustes ([prazoMs]; no teste, meio minuto; zero = já andando).
 */
fun falaDeSair(prazoMs: Long): String {
    val prazo = when {
        prazoMs <= 0L -> ""
        prazoMs < 60_000L -> ": you have half a minute to start walking"
        prazoMs < 120_000L -> ": you have one minute to start walking"
        else -> ": you have ${prazoMs / 60_000} minutes to start walking"
    }
    return "Time to move, sir. Get dressed and go outside$prazo."
}

/** No lugar de uma fala da IA que manda descansar ou diz que o alarme acabou (respostaSegura). */
const val FALA_NAO_NEGOCIA = "Not my call, sir. What is the first thing you will do today?"

/**
 * O botão da mini tarefa do prazo pra sair ([ofereceMiniTarefa]) apareceu, no primeiro bipe de parado: a voz avisa,
 * pelo tempo a mais que ela dá ([extraMs], o [preparoExtra]: 5 min; no teste, meio minuto).
 */
fun falaDoBotaoDaMiniTarefa(extraMs: Long): String =
    "Fell asleep, sir? Tap the button on the screen for ${tempoAMais(extraMs)}."

/** Na tela da mini tarefa, aberta pelo botão: a voz diz a [frase] pra repetir, pelo tempo a mais ([extraMs]). */
fun falaDaMiniTarefa(frase: String, extraMs: Long): String =
    "Fell asleep, sir? Repeat this sentence and you get ${tempoAMais(extraMs)}: $frase"

/** A mini tarefa feita: o prazo extra ([extraMs]) começa. */
fun falaMaisTempo(extraMs: Long): String =
    "${tempoAMais(extraMs).replaceFirstChar { it.uppercase() }}, sir. Get dressed and go outside."

/** "five more minutes", "one more minute" ou "half a minute more", falado. */
private fun tempoAMais(ms: Long): String = when {
    ms < 60_000L -> "half a minute more"
    ms < 120_000L -> "one more minute"
    else -> "${NUMEROS_FALADOS.getOrElse((ms / 60_000).toInt()) { (ms / 60_000).toString() }} more minutes"
}

private val NUMEROS_FALADOS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")

/** Enquanto a IA procura na internet o que a pessoa perguntou (a busca leva uns segundos): a voz avisa. */
const val FALA_PROCURAR = "Good question, sir. Let me check that."

/**
 * O que a pessoa disse não fez sentido (ruído, português, o reconhecedor errou feio): a IA responde isto, e não um
 * palpite ([naoEntendeu]). Gravada fixa, pra sair na hora.
 */
const val FALA_NAO_ENTENDI = "I did not get that, sir. Say it again."

/**
 * O sintetizador de voz do celular, em inglês, com uma voz instalada no celular ([vozSemInternet]): grava igual
 * com ou sem internet, sem esperar servidor. Criado quando o
 * alarme começa (com o celular já desbloqueado desde que ligou: antes, o sintetizador nem abre): assim
 * que fica pronto, já grava as falas fixas ([FALA_INICIO] e os avisos de minutos), que depois saem na
 * hora. [gravar] grava uma fala nova (a saudação, que tem a hora; as falas da IA); pedida antes de ele ficar
 * pronto, espera. Sem voz em inglês ou sem sintetizador, as falas não existem (null) e o alarme segue sem elas.
 */
class Voz(ctx: Context, fixas: Map<String, String>) {
    private val pasta = ctx.cacheDir
    private val handler = Handler(Looper.getMainLooper())
    private var pronta: Boolean? = null // null = ainda abrindo o sintetizador
    private val gravadas = mutableSetOf<String>()
    private val esperando = mutableMapOf<String, (File?) -> Unit>()
    private val naFila = mutableMapOf<String, String>() // pedidas antes de ficar pronta: id → texto
    private val comecou = mutableMapOf<String, Long>() // das gravadas na hora ([gravar]): id → quando pediu, pro log do tempo
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(ctx) { status ->
            pronta = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE
            if (pronta != true) {
                Log.w(TAG, "Voz: sem sintetizador ou sem voz em inglês, o alarme segue sem as falas")
                naFila.clear()
                esperando.values.toList().also { esperando.clear() }.forEach { it(null) }
                return@TextToSpeech
            }
            vozSemInternet()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {}
                override fun onDone(id: String) = entregar(id, ok = true)
                override fun onError(id: String, codigo: Int) = entregar(id, ok = false)

                @Deprecated("Exigido pela classe; o Android novo chama o onError com código")
                override fun onError(id: String) = entregar(id, ok = false)
            })
            fixas.forEach { (id, texto) -> sintetizar(id, texto) }
            naFila.forEach { (id, texto) -> sintetizar(id, texto) }
            naFila.clear()
        }
    }

    /** Uma fala fixa já gravada, ou null (ainda não, ou sem voz). */
    fun fixa(id: String): File? = arquivo(id).takeIf { id in gravadas }

    /**
     * Grava [texto] e chama [aoGravar] na thread principal com o arquivo (ou null, se não deu). Cada
     * pedido precisa de um [id] novo: uma gravação velha, que terminou depois, não chega no pedido seguinte.
     */
    fun gravar(id: String, texto: String, aoGravar: (File?) -> Unit) {
        comecou[id] = SystemClock.elapsedRealtime()
        when (pronta) {
            false -> return aoGravar(null)
            null -> naFila[id] = texto
            true -> sintetizar(id, texto)
        }
        esperando[id] = aoGravar
    }

    /** A música voltou: ninguém mais espera pelas falas em gravação. */
    fun cancelar() {
        esperando.clear()
        naFila.clear()
        comecou.clear()
    }

    fun desligar() {
        cancelar()
        runCatching { tts.shutdown() }
        pasta.listFiles { f -> f.name.startsWith("fala_") }?.forEach { it.delete() }
    }

    private fun arquivo(id: String) = File(pasta, "fala_$id.wav")

    /**
     * A voz em inglês dos EUA instalada no celular, a de melhor qualidade. A voz padrão do Google pode ser a da
     * internet, que depende do sinal (no teste no carro, o sintetizador chegou a ficar 15 s numa fala). Sem nenhuma
     * instalada, fica a padrão.
     */
    private fun vozSemInternet() {
        val atual = tts.voice
        if (atual != null && !atual.isNetworkConnectionRequired) {
            Log.i(TAG, "Voz: ${atual.name}")
            return
        }
        val instalada = runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { it.locale == Locale.US && !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            .maxByOrNull { it.quality }
        if (instalada != null && tts.setVoice(instalada) == TextToSpeech.SUCCESS) {
            Log.i(TAG, "Voz: ${instalada.name}, no lugar de ${atual?.name} (da internet)")
        } else {
            Log.w(TAG, "Voz: sem voz em inglês instalada no celular, fica a ${atual?.name}")
        }
    }

    private fun sintetizar(id: String, texto: String) {
        gravadas.remove(id)
        val ok = runCatching { tts.synthesizeToFile(texto, Bundle(), arquivo(id), id) == TextToSpeech.SUCCESS }.getOrDefault(false)
        if (!ok) entregar(id, ok = false)
    }

    private fun entregar(id: String, ok: Boolean) {
        handler.post {
            if (ok) gravadas += id
            comecou.remove(id)?.let { Log.i(TAG, "Voz: \"$id\" ${if (ok) "gravada" else "falhou"} em ${SystemClock.elapsedRealtime() - it} ms") }
            esperando.remove(id)?.invoke(if (ok) arquivo(id) else null)
        }
    }
}
