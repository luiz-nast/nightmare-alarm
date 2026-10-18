// Testes do som pelo cabo (permissão DUMP, como o TesteDaIa), com a tela do app aberta por cima do bloqueio:
//   adb shell am start -n com.implacavel.alarme/.TesteDoSom              (a rampa, baixinho)
//   adb shell am start -n com.implacavel.alarme/.TesteDoSom --ez cano true (o reconhecedor pelo cano, sem som)
//   adb shell am start -n com.implacavel.alarme/.TesteDoSom --ez escuta true (a Escuta da conversa por 20 s, sem som)
//   adb logcat -s Implacavel
// A rampa: um tom de 6 s pelo MediaPlayer e o mesmo pelo AudioTrack ([faixaDaFala], o caminho das falas), no volume
// de alarme mínimo e bem abaixo dele (dá pra ouvir de perto, sem acordar a casa). O que mede é a saída pro
// alto-falante, sem microfone: `adb shell dumpsys media.audio_flinger`, "Signal power history" da saída AudioOut_D.
// Pelo MediaPlayer, o 1º segundo sai uns 20 dB mais baixo que o 3º (a rampa); pelo AudioTrack, igual do começo ao fim.
// O cano: o reconhecedor do Google ouvindo, em vez do microfone, uma fala gravada pelo sintetizador (o jeito da
// [Escuta]); escreve no log o que ele entendeu. Com --ei minutos N, uma frase a cada 20 s por N minutos (a sessão
// aguenta?). A escuta: a [Escuta] de verdade, no microfone, por 20 s: escreve no log cada som que o detector achou e o
// tamanho do que o reconhecedor escreveu (o texto não).
package com.implacavel.alarme

import android.app.Activity
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

class TesteDoSom : Activity() {
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply {
            text = "Testing the sound…"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE) // o fundo da janela é escuro (Tema.kt)
        })
        // Um teste por vez (a tela acendendo pode recriar a Activity: a cópia nova só fecha)
        if (savedInstanceState != null || emTeste) return finish()
        if (Ajustes.emAndamento != null) {
            Log.w(TAG, "Som teste: tem alarme tocando, o teste não roda")
            return finish()
        }
        emTeste = true
        if (intent.getBooleanExtra("escuta", false) && Build.VERSION.SDK_INT >= 33) return escutar()
        if (intent.getBooleanExtra("suporte", false) && Build.VERSION.SDK_INT >= 33) return suporte()
        val pelocano = intent.getBooleanExtra("cano", false)
        val voz = if (pelocano) Voz(applicationContext, emptyMap()) else null
        val minutos = intent.getIntExtra("minutos", 0)
        thread(name = "teste-do-som") {
            try {
                if (voz != null) cano(voz, minutos) else rampa()
            } catch (e: Throwable) {
                Log.w(TAG, "Som teste: falhou (${e.javaClass.simpleName}: ${e.message?.take(160)})")
            } finally {
                principal.post {
                    voz?.desligar()
                    emTeste = false
                    finish()
                }
            }
        }
    }

    /**
     * Que idiomas cada reconhecedor do celular tem no aparelho (instalados, baixando, baixáveis) e online: só pergunta,
     * não baixa nada. --ez suporte true.
     */
    @androidx.annotation.RequiresApi(33)
    private fun suporte() {
        val pedido = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        val servicos = listOf("tts", "as").mapNotNull { servicoDeReconhecimento(it) }
        val faltam = CountDownLatch(servicos.size)
        val reconhecedores = servicos.map { servico ->
            SpeechRecognizer.createSpeechRecognizer(this, servico).also { r ->
                r.checkRecognitionSupport(pedido, mainExecutor, object : android.speech.RecognitionSupportCallback {
                    override fun onSupportResult(s: android.speech.RecognitionSupport) {
                        Log.i(
                            TAG,
                            "Som teste: suporte de ${servico.packageName}: no aparelho ${s.installedOnDeviceLanguages}, baixando ${s.pendingOnDeviceLanguages}, " +
                                "baixáveis ${s.supportedOnDeviceLanguages.size} (en-US? ${"en-US" in s.supportedOnDeviceLanguages}), online ${s.onlineLanguages.size}",
                        )
                        faltam.countDown()
                    }
                    override fun onError(error: Int) {
                        Log.i(TAG, "Som teste: suporte de ${servico.packageName}: erro $error")
                        faltam.countDown()
                    }
                })
            }
        }
        thread(name = "teste-suporte") {
            faltam.await(15, TimeUnit.SECONDS)
            principal.post {
                reconhecedores.forEach { it.destroy() }
                emTeste = false
                finish()
            }
        }
    }

    /** A [Escuta] da conversa por 20 s, no microfone. */
    @androidx.annotation.RequiresApi(33)
    private fun escutar() {
        emTeste = true
        val inicio = android.os.SystemClock.elapsedRealtime()
        val agora = { "${(android.os.SystemClock.elapsedRealtime() - inicio) / 100 / 10.0} s" }
        val escuta = Escuta(
            this,
            onSom = { comecou, db -> Log.i(TAG, "Som teste: escuta, ${agora()}: ${if (comecou) "som (${db.toInt()} dB)" else "silêncio"}") },
            onTexto = { Log.i(TAG, "Som teste: escuta, ${agora()}: texto com ${it.length} letras") },
            onIndisponivel = { Log.w(TAG, "Som teste: escuta indisponível") },
        )
        if (!escuta.comecar()) {
            emTeste = false
            return finish()
        }
        principal.postDelayed({
            Log.i(TAG, "Som teste: escuta, fim (${escuta.resumo()})")
            escuta.parar()
            emTeste = false
            finish()
        }, 20_000)
    }

    /** O tom de 6 s, frio (depois de 6 s calado), pelo MediaPlayer e pelo AudioTrack, duas vezes cada. */
    private fun rampa() {
        val arquivo = File(cacheDir, "teste_tom.wav").also { gravarWav(it, tom(), TAXA_TOM) }
        val antes = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val acordador = tocar("android.resource://$packageName/${R.raw.silencio}", emLoop = true)
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMinVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1), 0)
            repeat(2) {
                Thread.sleep(6_000)
                Log.i(TAG, "Som teste: MediaPlayer às ${LocalTime.now()}")
                tocar(Uri.fromFile(arquivo).toString(), emLoop = false)?.let {
                    Thread.sleep(6_300)
                    it.release()
                }
                Thread.sleep(6_000)
                Log.i(TAG, "Som teste: AudioTrack às ${LocalTime.now()}")
                faixaDaFala(arquivo, ATRIBUTOS)?.first?.let {
                    try {
                        noAltoFalante(it) // como na Sirene: sem isso, o Android manda o AudioTrack pra outra saída, sem a medição
                        it.setVolume(GANHO)
                        it.play()
                        Thread.sleep(6_300)
                    } finally {
                        it.release()
                    }
                } ?: Log.w(TAG, "Som teste: o AudioTrack não abriu")
            }
        } finally {
            acordador?.release()
            audio.setStreamVolume(AudioManager.STREAM_ALARM, antes, 0)
            arquivo.delete()
        }
        Log.i(TAG, "Som teste: fim. Agora: adb shell dumpsys media.audio_flinger (Signal power history da AudioOut_D)")
    }

    private fun noAltoFalante(saida: android.media.AudioRouting) {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let(saida::setPreferredDevice)
    }

    private fun tocar(caminho: String, emLoop: Boolean): MediaPlayer? {
        val mp = MediaPlayer()
        return runCatching {
            mp.setAudioAttributes(ATRIBUTOS)
            mp.setDataSource(this, Uri.parse(caminho))
            noAltoFalante(mp)
            mp.isLooping = emLoop
            mp.setVolume(GANHO, GANHO)
            mp.prepare()
            mp.start()
            mp
        }.getOrElse {
            mp.release()
            null
        }
    }

    /** Sem som e sem microfone: a fala do sintetizador entra no reconhecedor pelo cano, como na [Escuta]. */
    private fun cano(voz: Voz, minutos: Int) {
        if (Build.VERSION.SDK_INT < 33) {
            Log.w(TAG, "Som teste: o cano é do Android 13 em diante")
            return
        }
        val gravada = CountDownLatch(1)
        var arquivo: File? = null
        runOnUiThread {
            voz.gravar("teste_som", FRASE_DE_TESTE) {
                arquivo = it
                gravada.countDown()
            }
        }
        gravada.await(20, TimeUnit.SECONDS)
        val pcm = arquivo?.let(::lerPcm16)
        if (pcm == null) {
            Log.w(TAG, "Som teste: o sintetizador não gravou a fala")
            return
        }
        val fala = reamostrar(pcm.first, pcm.second, 16_000)
        val (leitura, escrita) = ParcelFileDescriptor.createPipe()
        val fim = CountDownLatch(1)
        var reconhecedor: SpeechRecognizer? = null
        // --es servico as|tts|pacote/classe: qual reconhecedor do celular (o padrão do sistema sem nada)
        val servico = servicoDeReconhecimento(intent.getStringExtra("servico"))
        Log.i(TAG, "Som teste: cano, reconhecedor ${servico?.flattenToShortString() ?: "padrão do sistema"}")
        fun hipoteses(b: Bundle?): String {
            val textos = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val notas = b?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            return textos.mapIndexed { i, t -> "\"$t\"" + (notas?.getOrNull(i)?.let { " (%.2f)".format(it) } ?: "") }.joinToString(" | ")
        }
        runOnUiThread {
            reconhecedor = (if (servico != null) SpeechRecognizer.createSpeechRecognizer(this, servico) else SpeechRecognizer.createSpeechRecognizer(this)).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onSegmentResults(segmentResults: Bundle) {
                        Log.i(TAG, "Som teste: cano, frase: ${hipoteses(segmentResults)}")
                    }
                    override fun onEndOfSegmentedSession() {
                        Log.i(TAG, "Som teste: cano, fim da sessão")
                        fim.countDown()
                    }
                    override fun onError(error: Int) {
                        Log.i(TAG, "Som teste: cano, erro $error")
                        fim.countDown()
                    }
                    override fun onResults(results: Bundle?) {
                        Log.i(TAG, "Som teste: cano, final: ${hipoteses(results)}")
                        fim.countDown()
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, intent.getStringExtra("idioma") ?: "en-US") // --es idioma en-GB
                        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, leitura)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                        .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE),
                )
            }
        }
        val pedacos = if (minutos <= 0) {
            listOf(ShortArray(16_000), fala, ShortArray(24_000), fala, ShortArray(48_000))
        } else {
            List(minutos * 3) { listOf(fala, ShortArray(16_000 * 17)) }.flatten()
        }
        Log.i(TAG, "Som teste: cano, mandando ${pedacos.sumOf { it.size } / 16_000} s de som")
        ParcelFileDescriptor.AutoCloseOutputStream(escrita).use { saida ->
            val bytes = ByteBuffer.allocate(640).order(ByteOrder.LITTLE_ENDIAN)
            for (pedaco in pedacos) {
                // No ritmo do microfone: 20 ms de som a cada 20 ms
                for (i in pedaco.indices step 320) {
                    bytes.clear()
                    for (k in i until minOf(i + 320, pedaco.size)) bytes.putShort(pedaco[k])
                    saida.write(bytes.array(), 0, bytes.position())
                    Thread.sleep(20)
                }
            }
        }
        fim.await(8, TimeUnit.SECONDS)
        runOnUiThread { reconhecedor?.destroy() }
        leitura.close()
        Log.i(TAG, "Som teste: fim")
    }

    private val principal = android.os.Handler(android.os.Looper.getMainLooper())

    private companion object {
        @Volatile var emTeste = false

        const val FRASE_DE_TESTE = "Nice. What are you going to eat for breakfast today?"

        /**
         * O reconhecedor pedido no teste: "as" é o da Inteligência do Sistema Android (o das legendas ao vivo, o modelo
         * grande que o Pixel usa), "tts" o dos Serviços de Fala do Google (o padrão do S24 FE), ou "pacote/classe".
         */
        fun servicoDeReconhecimento(nome: String?): android.content.ComponentName? = when (nome) {
            null, "", "padrao" -> null
            "as" -> android.content.ComponentName("com.google.android.as", "com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService")
            "tts" -> android.content.ComponentName("com.google.android.tts", "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService")
            else -> android.content.ComponentName.unflattenFromString(nome)
        }
        const val TAXA_TOM = 24_000

        /** Por cima do volume de alarme mínimo: bem baixinho. */
        const val GANHO = 0.3f

        val ATRIBUTOS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /** 6 s de tom de 1 kHz a -12 dB, com 5 ms de entrada e saída (sem estalo). */
        fun tom(): ShortArray {
            val n = TAXA_TOM * 6
            val borda = TAXA_TOM / 200
            return ShortArray(n) { i ->
                val env = minOf(1.0, i / borda.toDouble(), (n - 1 - i) / borda.toDouble())
                (0.25 * env * sin(2 * PI * 1_000 * i / TAXA_TOM) * 32767).toInt().toShort()
            }
        }

        /** Muda a taxa de [amostras] (mono) de [de] pra [para], por interpolação linear (basta pro reconhecedor). */
        fun reamostrar(amostras: ShortArray, de: Int, para: Int): ShortArray {
            val n = (amostras.size.toLong() * para / de).toInt()
            return ShortArray(n) { i ->
                val x = i.toDouble() * de / para
                val k = x.toInt().coerceAtMost(amostras.size - 2)
                (amostras[k] + (amostras[k + 1] - amostras[k]) * (x - k)).toInt().toShort()
            }
        }
    }
}
