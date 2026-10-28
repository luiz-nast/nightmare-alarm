// O ouvido da conversa com a IA (Android 13+). O microfone fica com o app o tempo todo da conversa, e o mesmo som vai
// pro reconhecedor de voz do sistema por um cano (RecognizerIntent.EXTRA_AUDIO_SOURCE), numa sessão só que não fecha
// entre as frases (medido no S24 FE: 5 min seguidos, as 15 frases reconhecidas, sem erro; a palavra aparece uns 0,2 s
// depois de dita). Assim cada pedaço de 20 ms passa antes pelo detector de barulho ([DetectorDeSom]): qualquer som da
// pessoa, até um "ahn" ou um "hein" que o reconhecedor nem escreve, chega na hora. Dois microfones ao mesmo tempo não
// dá: com o app na tela, o do app ganha e o reconhecedor recebe silêncio. A STOP e a verificação continuam no Ouvinte.
package com.implacavel.alarme

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Escuta a conversa: [onSom] quando a pessoa começa (true, com o volume do começo do som, em dB) e para (false) de
 * fazer som; [nivelDoUltimoSom], o volume do começo do som de agora (ou do último); [onTexto] com tudo que ela
 * disse desde a [novaVez] (as frases terminadas e o pedaço de agora); [onIndisponivel] se o microfone ou o
 * reconhecedor não funcionarem. Enquanto o app fala ou bipa ([surdo], [surdoPor]), o reconhecedor recebe silêncio e o
 * detector não avisa nada: o eco da própria voz não é a pessoa. O texto não vai pro log (privacidade), só tamanhos e
 * volumes. Os avisos chegam na thread principal.
 */
@RequiresApi(33)
class Escuta(
    private val ctx: Context,
    private val onSom: (comecou: Boolean, db: Float) -> Unit,
    private val onTexto: (String) -> Unit,
    private val onIndisponivel: () -> Unit,
    private val servico: ComponentName? = null, // qual reconhecedor do celular; null = o padrão do sistema
    private val idioma: String = "en-US",
    private val palavras: List<String> = emptyList(), // as dele ([Ajustes.palavras]): o reconhecedor tende a elas
) {
    private var hipotesesDaFrase: List<String> = emptyList() // as outras hipóteses da última frase terminada
    private var frasesDaVez = 0 // frases terminadas desde a [novaVez]
    private val handler = Handler(Looper.getMainLooper())
    private val detector = DetectorDeSom()
    private val fila = ArrayBlockingQueue<ShortArray>(FILA_QUADROS)
    @Volatile private var ativa = false
    @Volatile private var surdoAte = 0L // SystemClock.elapsedRealtime; Long.MAX_VALUE enquanto o app fala
    @Volatile private var cano: OutputStream? = null // a ponta do cano da sessão de agora do reconhecedor
    @Volatile private var pico = -120f // o som mais alto desde o último [resumo]
    @Volatile private var sons = 0 // quantos sons desde o último [resumo]
    @Volatile private var nivelDoSom = -120f // o volume do começo do som de agora (ou do último), em dB
    @Volatile private var somSemTexto = 0 // pedaços com som desde o último texto do reconhecedor ([SOM_SEM_TEXTO_QUADROS])
    private var leitura: ParcelFileDescriptor? = null // a outra ponta, nossa cópia (a do reconhecedor vai no pedido)
    private var reconhecedor: SpeechRecognizer? = null // um por sessão: o de uma sessão velha não fala mais
    private var sessao = 0 // muda a cada sessão aberta ou fechada: aviso de uma sessão velha não vale
    private val reabrir = Runnable { abrirSessao() }
    private var frases = "" // as terminadas desde a [novaVez]
    private var falhasSeguidas = 0

    /** Liga o microfone e o reconhecedor. Falso (e [onIndisponivel] logo depois) se não deu. */
    @SuppressLint("MissingPermission") // conferida logo abaixo
    fun comecar(): Boolean {
        if (!Poder.MICROFONE.liberado(ctx) || !SpeechRecognizer.isRecognitionAvailable(ctx)) {
            Log.w(TAG, "Escuta: indisponível (microfone=${Poder.MICROFONE.liberado(ctx)})")
            handler.post(onIndisponivel)
            return false
        }
        val minimo = AudioRecord.getMinBufferSize(TAXA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val microfone = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, TAXA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimo, AMOSTRAS * 2 * 25))
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        if (microfone == null) {
            Log.w(TAG, "Escuta: o microfone não abriu")
            handler.post(onIndisponivel)
            return false
        }
        ativa = true
        abrirSessao()
        thread(name = "escuta-microfone") { gravar(microfone) }
        thread(name = "escuta-cano") { mandarProCano() }
        Log.i(TAG, "Escuta: ouvindo")
        return true
    }

    fun parar() {
        if (!ativa) return
        ativa = false
        handler.removeCallbacksAndMessages(null)
        fecharSessao()
        fila.clear()
        Log.i(TAG, "Escuta: parou")
    }

    /** O app vai falar ([ligado]): até desligar, e mais [FOLGA_ECO_MS], nada do microfone vale. */
    fun surdo(ligado: Boolean) {
        surdoAte = if (ligado) Long.MAX_VALUE else SystemClock.elapsedRealtime() + FOLGA_ECO_MS
    }

    /** Um som curto do app (um bipe de [ms]): esse tempo, e mais [FOLGA_ECO_MS], não vale. */
    fun surdoPor(ms: Long) {
        val ate = SystemClock.elapsedRealtime() + ms + FOLGA_ECO_MS
        if (surdoAte != Long.MAX_VALUE) surdoAte = maxOf(surdoAte, ate)
    }

    /** A vez nova da pessoa: o que ela disse antes não conta mais. */
    fun novaVez() {
        frases = ""
        frasesDaVez = 0
        hipotesesDaFrase = emptyList()
    }

    /**
     * O que o reconhecedor também achou que ouviu na vez de agora (as outras hipóteses, da mais provável pra menos),
     * pra IA escolher a que faz sentido. Só quando a vez foi uma frase só, já terminada; senão, nada.
     */
    fun alternativas(): List<String> = if (frasesDaVez == 1) hipotesesDaFrase else emptyList()

    /** O volume do começo do som de agora, ou do último que acabou (dB): o mesmo que vai no [onSom], pra comparar igual com igual. */
    fun nivelDoUltimoSom(): Float = nivelDoSom

    /** O ruído do quarto, o som mais alto e quantos sons desde a última vez (pro log, pra afinar o detector). */
    fun resumo(): String = "piso ${detector.piso.toInt()} dB, pico ${pico.toInt()} dB, $sons sons".also {
        pico = -120f
        sons = 0
    }

    /** A thread do microfone: cada pedaço de 20 ms vai pro detector e pro cano (silêncio, enquanto surdo). */
    private fun gravar(microfone: AudioRecord) {
        val pedaco = ShortArray(AMOSTRAS)
        var anterior = 0f
        var filtrado = 0f
        var eraSurdo = false
        try {
            microfone.startRecording()
            while (ativa) {
                var lidas = 0
                while (lidas < AMOSTRAS && ativa) {
                    val n = microfone.read(pedaco, lidas, AMOSTRAS - lidas)
                    if (n < 0) throw IOException("microfone: erro $n")
                    lidas += n
                }
                if (!ativa) break
                // Sem o grave abaixo de ~80 Hz (zumbido, batida na mesa): a energia que fica é a da voz
                var soma = 0.0
                for (x in pedaco) {
                    filtrado = 0.97f * (filtrado + x - anterior)
                    anterior = x.toFloat()
                    soma += filtrado.toDouble() * filtrado
                }
                val db = (20 * log10((sqrt(soma / AMOSTRAS) / 32768).coerceAtLeast(1e-6))).toFloat()
                val surdoAgora = SystemClock.elapsedRealtime() < surdoAte
                if (surdoAgora && !eraSurdo && detector.falando) handler.post { if (ativa) onSom(false, db) }
                if (surdoAgora) detector.calar()
                eraSurdo = surdoAgora
                if (!surdoAgora) {
                    if (db > pico) pico = db
                    val mudou = detector.quadro(db)
                    if (mudou == true) {
                        sons++
                        nivelDoSom = db
                    }
                    mudou?.let { comecou -> handler.post { if (ativa) onSom(comecou, db) } }
                    // Muito som e nenhuma palavra: o reconhecedor travou sem avisar (ou não está ouvindo o cano)
                    if (detector.falando && ++somSemTexto >= SOM_SEM_TEXTO_QUADROS) {
                        somSemTexto = 0
                        handler.post { recomecar("${SOM_SEM_TEXTO_QUADROS * QUADRO_MS / 1000} s de som sem nenhuma palavra") }
                    }
                }
                val paraOCano = if (surdoAgora) ShortArray(AMOSTRAS) else pedaco.copyOf()
                if (!fila.offer(paraOCano)) {
                    fila.poll() // o reconhecedor atrasou: o pedaço mais velho fica pra trás
                    fila.offer(paraOCano)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Escuta: o microfone parou (${e.javaClass.simpleName}: ${e.message?.take(80)})")
            handler.post { if (ativa) indisponivel() }
        } finally {
            runCatching { microfone.stop() }
            microfone.release()
        }
    }

    /** A thread do cano: escreve no reconhecedor o que o microfone mandou, no ritmo dele. */
    private fun mandarProCano() {
        val bytes = ByteBuffer.allocate(AMOSTRAS * 2).order(ByteOrder.LITTLE_ENDIAN)
        while (ativa) {
            val pedaco = fila.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val saida = cano ?: continue
            bytes.clear()
            pedaco.forEach { bytes.putShort(it) }
            try {
                saida.write(bytes.array())
            } catch (e: IOException) {
                if (cano === saida) cano = null // a sessão acabou do lado de lá: a próxima traz outro cano
            }
        }
    }

    /** Uma sessão nova do reconhecedor (um reconhecedor novo, com um cano novo). Na thread principal. */
    private fun abrirSessao() {
        if (!ativa) return
        fecharSessao()
        val (lado, ponta) = ParcelFileDescriptor.createPipe()
        leitura = lado
        cano = ParcelFileDescriptor.AutoCloseOutputStream(ponta)
        val esta = sessao
        val r = (if (servico != null) SpeechRecognizer.createSpeechRecognizer(ctx, servico) else SpeechRecognizer.createSpeechRecognizer(ctx)).also { reconhecedor = it }
        r.setRecognitionListener(Ouvido { esta == sessao && ativa })
        val pedido = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, idioma)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5) // as outras hipóteses vão pra IA ([alternativas])
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, lado)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TAXA)
                // A sessão só acaba quando o cano fecha: nada de recomeçar a cada frase
            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        // Android 13+: o reconhecedor tende às palavras dele (um que não sabe, ignora)
        if (palavras.isNotEmpty() && Build.VERSION.SDK_INT >= 33) pedido.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(palavras))
        r.startListening(pedido)
    }

    /** Fecha a sessão de agora: o cano, a nossa ponta dele e o reconhecedor (que não avisa mais nada). */
    private fun fecharSessao() {
        sessao++
        cano?.let { runCatching { it.close() } }
        cano = null
        leitura?.let { runCatching { it.close() } }
        leitura = null
        reconhecedor?.destroy()
        reconhecedor = null
    }

    /**
     * A sessão caiu: abre outra. Uma sessão que acaba sem ter escrito nada conta como falha; muitas seguidas (ou sem o
     * idioma), o reconhecimento não serve.
     */
    private fun recomecar(motivo: String) {
        if (!ativa) return
        falhasSeguidas++
        Log.i(TAG, "Escuta: sessão do reconhecedor acabou ($motivo), abrindo outra (falhas seguidas: $falhasSeguidas)")
        if (falhasSeguidas >= LIMITE_FALHAS) return indisponivel()
        fecharSessao()
        handler.removeCallbacks(reabrir)
        handler.postDelayed(reabrir, if (falhasSeguidas <= 1) 100 else 1_000)
    }

    private fun indisponivel() {
        parar()
        onIndisponivel()
    }

    /** Uma palavra do reconhecedor: ele está vivo. */
    private fun escreveu() {
        falhasSeguidas = 0
        somSemTexto = 0
    }

    private fun publicar(pedacoDeAgora: String) {
        onTexto("$frases $pedacoDeAgora".trim())
    }

    /** Os avisos de uma sessão do reconhecedor; [vale] é falso depois que ela fecha. */
    private inner class Ouvido(private val vale: () -> Boolean) : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            if (!vale()) return
            val texto = textoDe(partialResults) ?: return
            escreveu()
            publicar(texto)
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            if (!vale()) return
            fraseTerminada(segmentResults)
        }

        // Reconhecedor que não fatia em pedaços: o resultado final vale como uma frase, e a sessão recomeça
        override fun onResults(results: Bundle?) {
            if (!vale()) return
            fraseTerminada(results)
            recomecar("resultado final")
        }

        private fun fraseTerminada(resultado: Bundle?) {
            val hipoteses = hipotesesDe(resultado)
            val texto = hipoteses.firstOrNull() ?: return
            escreveu()
            frases = "$frases $texto".trim()
            frasesDaVez++
            hipotesesDaFrase = hipoteses.drop(1)
            publicar("")
        }

        override fun onEndOfSegmentedSession() {
            if (vale()) recomecar("fim da sessão")
        }

        override fun onError(error: Int) {
            if (!vale()) return
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
            ) {
                falhasSeguidas = LIMITE_FALHAS
            }
            recomecar("erro $error")
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** O texto mais provável de um resultado do reconhecedor, se tiver alguma palavra (com barulho ele manda ""). */
    private fun textoDe(resultado: Bundle?): String? = hipotesesDe(resultado).firstOrNull()

    /** As hipóteses de um resultado, da mais provável pra menos, só as com alguma palavra. */
    private fun hipotesesDe(resultado: Bundle?): List<String> =
        resultado?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().map { it.trim() }.filter { t -> t.any(Char::isLetterOrDigit) }

    private companion object {
        const val TAXA = 16_000
        const val AMOSTRAS = TAXA * QUADRO_MS / 1_000

        /** Até 3 s de som esperando o reconhecedor ler. */
        const val FILA_QUADROS = 150

        /** Depois de o app falar ou bipar, o eco e a reverberação ainda chegam no microfone por isso. */
        const val FOLGA_ECO_MS = 300L

        const val LIMITE_FALHAS = 5

        /** Tanto som (6 s, somado) sem nenhuma palavra do reconhecedor: a sessão é aberta de novo. */
        const val SOM_SEM_TEXTO_QUADROS = 6_000 / QUADRO_MS
    }
}
