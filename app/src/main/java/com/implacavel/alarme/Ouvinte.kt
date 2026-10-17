package com.implacavel.alarme

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Escuta o microfone sem parar até ouvir o que [aceita] (o "stop", em [disseStop]; a frase da
 * verificação, em [repetiu]). Usa o reconhecedor de voz do sistema (no Galaxy, o do Google), no
 * [idioma] pedido (null = o do sistema), de preferência offline, e recomeça sozinho depois de cada frase,
 * silêncio ou erro passageiro. [onOuviu] recebe tudo que ele ouve, ainda no meio da fala. Com [juntar],
 * vale também o que ele já ouviu antes (até [PALAVRAS_JUNTAS]): a frase pode sair em pedaços, com
 * pausa no meio. Depois de [LIMITE_FALHAS] erros seguidos (ou na hora, sem o idioma), ou se o
 * reconhecedor ficar [ESPERA_RESPOSTA_MS] sem dar sinal de vida (o serviço do Google caiu), avisa
 * [onIndisponivel]. O log mostra o que ele ouviu. Tudo roda na thread principal. (A conversa com a IA tem um
 * ouvido só dela, a [Escuta].)
 */
class Ouvinte(
    private val ctx: Context,
    private val nome: String,
    private val aceita: (String) -> Boolean,
    private val onOuviu: (String) -> Unit,
    private val onAceito: () -> Unit,
    private val onIndisponivel: () -> Unit,
    private val idioma: String? = null,
    private val juntar: Boolean = false,
) : RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private var reconhecedor: SpeechRecognizer? = null
    private var falhasSeguidas = 0
    private var jaOuvido = "" // com [juntar]: as falas que já terminaram

    /** O reconhecedor parou de responder: vale como indisponível, pra aparecer o botão no lugar. */
    private val travou = Runnable {
        Log.w(TAG, "Voz: o reconhecedor não responde há ${ESPERA_RESPOSTA_MS / 1000} s")
        parar()
        onIndisponivel()
    }

    fun comecar() {
        val microfone = Poder.MICROFONE.liberado(ctx)
        val reconhecimento = SpeechRecognizer.isRecognitionAvailable(ctx)
        if (!microfone || !reconhecimento) {
            Log.w(TAG, "Voz: indisponível (microfone=$microfone, reconhecimento=$reconhecimento)")
            onIndisponivel()
            return
        }
        Log.i(TAG, "Voz: escutando")
        reconhecedor = SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(this) }
        escutar()
    }

    fun parar() {
        handler.removeCallbacksAndMessages(null)
        reconhecedor?.destroy()
        reconhecedor = null
    }

    private fun escutar() {
        val pedido = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .apply { idioma?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) } }
        reconhecedor?.startListening(pedido)
        aguardarResposta()
    }

    /** Qualquer sinal do reconhecedor mostra que ele está vivo: o prazo de [travou] recomeça. */
    private fun aguardarResposta() {
        handler.removeCallbacks(travou)
        handler.postDelayed(travou, ESPERA_RESPOSTA_MS)
    }

    private fun escutarDeNovo(esperaMs: Long) {
        handler.postDelayed({ escutar() }, esperaMs)
    }

    /** [terminou]: o resultado final da fala (os outros são parciais, enquanto a pessoa ainda fala). */
    private fun conferir(resultado: Bundle?, terminou: Boolean) {
        aguardarResposta()
        // Resultado sem nenhuma palavra (o reconhecedor manda "" com barulho) não é fala de ninguém
        val frases = resultado?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.any(Char::isLetterOrDigit) }
        if (frases.isEmpty()) return
        Log.i(TAG, "Voz ouviu: $frases")
        val ouvidas = if (juntar) frases.map { juntarAoOuvido(it) } else frases
        onOuviu(ouvidas.first())
        if (ouvidas.any(aceita)) {
            Log.i(TAG, "Voz: $nome reconhecido")
            parar()
            onAceito()
        } else if (terminou && juntar) {
            jaOuvido = ouvidas.first()
        }
    }

    private fun juntarAoOuvido(frase: String) =
        "$jaOuvido $frase".split(" ").filter(String::isNotBlank).takeLast(PALAVRAS_JUNTAS).joinToString(" ")

    override fun onPartialResults(partialResults: Bundle?) = conferir(partialResults, terminou = false)

    override fun onResults(results: Bundle?) {
        falhasSeguidas = 0
        conferir(results, terminou = true)
        if (reconhecedor != null) escutarDeNovo(100)
    }

    override fun onError(error: Int) {
        if (reconhecedor == null) return
        aguardarResposta()
        when (error) {
            // Silêncio ou fala que não virou texto: normal, só escutar de novo
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> falhasSeguidas = 0
            // Sem permissão, ou sem o idioma pedido (ex.: inglês sem internet e sem o pacote offline)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, ERRO_IDIOMA_NAO_SUPORTADO, ERRO_IDIOMA_INDISPONIVEL -> falhasSeguidas = LIMITE_FALHAS
            else -> falhasSeguidas++
        }
        Log.i(TAG, "Voz: erro $error do reconhecedor (falhas seguidas: $falhasSeguidas)")
        if (falhasSeguidas >= LIMITE_FALHAS) {
            parar()
            onIndisponivel()
            return
        }
        reconhecedor?.cancel()
        escutarDeNovo(if (falhasSeguidas == 0) 100 else 800)
    }

    override fun onReadyForSpeech(params: Bundle?) = aguardarResposta()
    override fun onBeginningOfSpeech() = aguardarResposta()
    override fun onRmsChanged(rmsdB: Float) = aguardarResposta()
    override fun onBufferReceived(buffer: ByteArray?) = aguardarResposta()
    override fun onEndOfSpeech() = aguardarResposta()
    override fun onEvent(eventType: Int, params: Bundle?) = aguardarResposta()

    private companion object {
        const val LIMITE_FALHAS = 5

        /** Com [juntar], só as últimas palavras ouvidas: uma TV ligada não junta palavras até formar a frase. */
        const val PALAVRAS_JUNTAS = 20
        const val ESPERA_RESPOSTA_MS = 8_000L

        // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED e ERROR_LANGUAGE_UNAVAILABLE (Android 12+)
        const val ERRO_IDIOMA_NAO_SUPORTADO = 12
        const val ERRO_IDIOMA_INDISPONIVEL = 13
    }
}
