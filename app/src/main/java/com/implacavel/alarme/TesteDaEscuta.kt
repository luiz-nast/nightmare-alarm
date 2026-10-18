// Teste do reconhecimento de voz com a voz da pessoa, pelo cabo (permissão DUMP), sem som nenhum:
//   adb shell am start -n com.implacavel.alarme/.TesteDaEscuta
//   adb pull /sdcard/Android/data/com.implacavel.alarme/files/escuta/   (os .wav e o resultado.txt)
// A tela mostra uma frase em inglês por vez; a pessoa lê em voz alta, com o celular onde ele fica de manhã. Cada frase
// é gravada (o mesmo microfone e a mesma taxa da [Escuta]) e, no fim, cada gravação passa pelo cano em cada
// reconhecedor offline do celular ([RECONHECEDORES]), com até 5 hipóteses. Sai na tela, no log e em resultado.txt:
// o que cada um ouviu, a taxa de erro por palavra (a 1ª hipótese e a melhor das 5) e a demora do fim da fala até o
// texto. Só aqui o texto ouvido vai pro log: é um teste, de propósito. Os .wav ficam pra testar outros modelos no PC.
package com.implacavel.alarme

import android.app.Activity
import android.content.ComponentName
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
import android.view.Gravity
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

class TesteDaEscuta : Activity() {
    private val principal = Handler(Looper.getMainLooper())
    private lateinit var texto: TextView
    @Volatile private var acabou = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        texto = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(48, 96, 48, 96)
        }
        setContentView(ScrollView(this).apply { addView(texto) })
        if (savedInstanceState != null || emTeste) return finish()
        if (Build.VERSION.SDK_INT < 33 || Ajustes.emAndamento != null || !Poder.MICROFONE.liberado(this)) {
            Log.w(TAG, "Escuta teste: não roda (Android 13+, sem alarme tocando e com o microfone liberado)")
            return finish()
        }
        emTeste = true
        thread(name = "teste-da-escuta") {
            try {
                rodar()
            } catch (e: Throwable) {
                Log.w(TAG, "Escuta teste: falhou (${e.javaClass.simpleName}: ${e.message?.take(160)})")
                mostrar("Failed: ${e.javaClass.simpleName}")
            } finally {
                emTeste = false
                acabou = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        emTeste = false
    }

    private fun mostrar(t: String) {
        principal.post { texto.text = t }
    }

    private fun rodar() {
        val pasta = File(getExternalFilesDir(null), "escuta").apply { mkdirs() }
        val gravacoes = gravarFrases(pasta)
        if (gravacoes.none { it != null }) return mostrar("No sentence was recorded.")
        val relatorio = StringBuilder()
        val resumo = StringBuilder()
        for ((nome, servico, idioma) in RECONHECEDORES) {
            val resultados = FRASES.indices.map { i ->
                val wav = gravacoes[i] ?: return@map null
                mostrar("Recognizing with $nome ($idioma)…\n\nsentence ${i + 1} of ${FRASES.size}")
                reconhecer(wav, servico, idioma)
            }
            var erro1 = 0.0
            var erroMelhor = 0.0
            var contadas = 0
            var demoras = mutableListOf<Long>()
            relatorio.appendLine("== $nome ($idioma) ==")
            resultados.forEachIndexed { i, r ->
                if (r == null) return@forEachIndexed
                val esperado = FRASES[i]
                val primeira = r.hipoteses.firstOrNull().orEmpty()
                val e1 = taxaDeErro(esperado, primeira)
                val eMelhor = r.hipoteses.minOfOrNull { taxaDeErro(esperado, it) } ?: 1.0
                erro1 += e1
                erroMelhor += eMelhor
                contadas++
                r.demoraMs?.let { demoras += it }
                relatorio.appendLine("${i + 1}. expected: $esperado")
                relatorio.appendLine("   heard: ${r.hipoteses.joinToString(" | ") { "\"$it\"" }.ifEmpty { "(nothing)" }}  errors ${pct(e1)}, best of ${r.hipoteses.size}: ${pct(eMelhor)}, ${r.demoraMs ?: "-"} ms")
            }
            val linha = "$nome ($idioma): word errors ${pct(erro1 / contadas.coerceAtLeast(1))} (best of the hypotheses: ${pct(erroMelhor / contadas.coerceAtLeast(1))}), " +
                "text ${demoras.sorted().getOrNull(demoras.size / 2) ?: "-"} ms after the end of speech"
            resumo.appendLine(linha)
            relatorio.appendLine(linha)
            relatorio.appendLine()
            Log.i(TAG, "Escuta teste: $linha")
        }
        File(pasta, "resultado.txt").writeText(relatorio.toString())
        Log.i(TAG, "Escuta teste: resultado em ${File(pasta, "resultado.txt")}")
        mostrar("Done.\n\n$resumo\nDetails in files/escuta/resultado.txt")
    }

    /** Cada frase lida pela pessoa, gravada num .wav (null se ela não falou em 12 s). */
    private fun gravarFrases(pasta: File): List<File?> {
        val minimo = AudioRecord.getMinBufferSize(TAXA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission") // conferida no onCreate
        val microfone = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, TAXA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimo, AMOSTRAS * 2 * 25))
        check(microfone.state == AudioRecord.STATE_INITIALIZED) { "o microfone não abriu" }
        val arquivos = mutableListOf<File?>()
        try {
            microfone.startRecording()
            val pedaco = ShortArray(AMOSTRAS)
            FRASES.forEachIndexed { i, frase ->
                val cabecalho = "Sentence ${i + 1} of ${FRASES.size}. Read it aloud, normal voice, phone where it stays in the morning:\n\n"
                mostrar("$cabecalho“$frase”\n\n(get ready…)")
                // 1,5 s pra ler antes de falar: o microfone roda, o som vai fora
                repeat(1_500 / QUADRO_MS) { ler(microfone, pedaco) }
                mostrar("$cabecalho“$frase”\n\n🎤 Speak now")
                val detector = DetectorDeSom()
                val gravado = ArrayList<Short>(TAXA * 12)
                var comecou = false
                var fim: Int? = null
                var quadros = 0
                while (quadros < 12_000 / QUADRO_MS) {
                    ler(microfone, pedaco)
                    pedaco.forEach { gravado.add(it) }
                    quadros++
                    when (detector.quadro(volumeDb(pedaco))) {
                        true -> if (!comecou) {
                            comecou = true
                            mostrar("$cabecalho“$frase”\n\n🎤 listening…")
                        }
                        false -> if (comecou) fim = quadros
                        null -> {}
                    }
                    // Acabou o som: mais 1 s de folga (uma pausa no meio da frase não é o fim), e chega
                    if (fim != null && quadros - fim!! >= 1_000 / QUADRO_MS && !detector.falando) break
                    if (detector.falando) fim = null
                }
                if (!comecou) {
                    mostrar("$cabecalho“$frase”\n\nNo sound heard: skipping")
                    arquivos += null
                    repeat(1_500 / QUADRO_MS) { ler(microfone, pedaco) }
                    return@forEachIndexed
                }
                val arquivo = File(pasta, "frase_%02d.wav".format(Locale.ROOT, i + 1))
                gravarWav(arquivo, gravado.toShortArray(), TAXA)
                arquivos += arquivo
                mostrar("$cabecalho“$frase”\n\n✓ got it (${gravado.size / TAXA} s)")
                Log.i(TAG, "Escuta teste: frase ${i + 1} gravada, ${gravado.size / TAXA} s")
                repeat(1_200 / QUADRO_MS) { ler(microfone, pedaco) }
            }
        } finally {
            runCatching { microfone.stop() }
            microfone.release()
        }
        return arquivos
    }

    private fun ler(microfone: AudioRecord, pedaco: ShortArray) {
        var lidas = 0
        while (lidas < pedaco.size) {
            val n = microfone.read(pedaco, lidas, pedaco.size - lidas)
            check(n >= 0) { "microfone: erro $n" }
            lidas += n
        }
    }

    private class Resultado(val hipoteses: List<String>, val demoraMs: Long?)

    /** O [wav] pelo cano no reconhecedor [servico] (null = o padrão do sistema), em [idioma]: as hipóteses e a demora. */
    private fun reconhecer(wav: File, servico: ComponentName?, idioma: String): Resultado {
        val (amostras, taxa) = lerPcm16(wav) ?: return Resultado(emptyList(), null)
        check(taxa == TAXA)
        val (leitura, escrita) = ParcelFileDescriptor.createPipe()
        val fim = CountDownLatch(1)
        val hipoteses = mutableListOf<String>()
        var fimDaFala = 0L
        var chegou: Long? = null
        var reconhecedor: SpeechRecognizer? = null
        fun guardar(b: Bundle?) {
            val textos = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
            if (textos.isNotEmpty() && hipoteses.isEmpty()) {
                chegou = SystemClock.elapsedRealtime()
                hipoteses += textos
            }
        }
        principal.post {
            reconhecedor = (if (servico != null) SpeechRecognizer.createSpeechRecognizer(this, servico) else SpeechRecognizer.createSpeechRecognizer(this)).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onSegmentResults(segmentResults: Bundle) = guardar(segmentResults)
                    override fun onResults(results: Bundle?) {
                        guardar(results)
                        fim.countDown()
                    }
                    override fun onEndOfSegmentedSession() = fim.countDown()
                    override fun onError(error: Int) {
                        Log.i(TAG, "Escuta teste: ${servico?.packageName ?: "padrão"} erro $error")
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
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, idioma)
                        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, leitura)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TAXA)
                        .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE),
                )
            }
        }
        // No ritmo do microfone (20 ms a cada 20 ms): meio segundo de silêncio, a fala, e 1,5 s de silêncio pra fechar a frase
        ParcelFileDescriptor.AutoCloseOutputStream(escrita).use { saida ->
            val bytes = ByteBuffer.allocate(AMOSTRAS * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (pedaco in listOf(ShortArray(TAXA / 2), amostras, ShortArray(TAXA * 3 / 2))) {
                for (i in pedaco.indices step AMOSTRAS) {
                    bytes.clear()
                    for (k in i until minOf(i + AMOSTRAS, pedaco.size)) bytes.putShort(pedaco[k])
                    saida.write(bytes.array(), 0, bytes.position())
                    Thread.sleep(QUADRO_MS.toLong())
                }
                if (pedaco === amostras) fimDaFala = SystemClock.elapsedRealtime()
            }
        }
        fim.await(6, TimeUnit.SECONDS)
        principal.post { reconhecedor?.destroy() }
        leitura.close()
        return Resultado(hipoteses.toList(), chegou?.let { it - fimDaFala })
    }

    private fun volumeDb(pedaco: ShortArray): Float {
        var soma = 0.0
        for (x in pedaco) soma += x.toDouble() * x
        return (20 * log10((sqrt(soma / pedaco.size) / 32768).coerceAtLeast(1e-6))).toFloat()
    }

    private fun pct(x: Double) = "${(x * 100).toInt()}%"

    private companion object {
        @Volatile var emTeste = false
        const val TAXA = 16_000
        const val AMOSTRAS = TAXA * QUADRO_MS / 1_000

        /** Respostas típicas da conversa da manhã, com os sons em que o sotaque brasileiro mais escorrega (th, r, h, vogais). */
        val FRASES = listOf(
            "I slept well but I am still a little tired",
            "Coffee and bread with butter, like every day",
            "I think the president is wrong about that",
            "My sister is coming for lunch on Sunday",
            "Thirty minutes of walking is enough for me",
            "I have a meeting at nine thirty with my boss",
            "Yes, I heard about that on the news yesterday",
            "Probably rice, beans and chicken for dinner",
            "I would rather stay at home tonight and watch a movie",
            "The weather is nice today, maybe I will go to the beach",
        )

        /** Os reconhecedores offline do S24 FE: o dos Serviços de Fala do Google (o padrão) e o da Inteligência do Sistema (só en-GB instalado). */
        val RECONHECEDORES = listOf(
            Triple("Google Speech Services", ComponentName("com.google.android.tts", "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"), "en-US"),
            Triple("Android System Intelligence", ComponentName("com.google.android.as", "com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService"), "en-GB"),
        )
    }
}

/**
 * A taxa de erro por palavra (WER) entre o [esperado] e o [ouvido]: trocas, faltas e sobras de palavras (distância de
 * edição) divididas pelas palavras esperadas. Sem maiúsculas nem pontuação; "I'm" e "I am" contam como iguais.
 */
internal fun taxaDeErro(esperado: String, ouvido: String): Double {
    val a = palavras(esperado)
    val b = palavras(ouvido)
    if (a.isEmpty()) return if (b.isEmpty()) 0.0 else 1.0
    var anterior = IntArray(b.size + 1) { it }
    for (i in 1..a.size) {
        val atual = IntArray(b.size + 1)
        atual[0] = i
        for (j in 1..b.size) {
            val troca = anterior[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            atual[j] = minOf(troca, anterior[j] + 1, atual[j - 1] + 1)
        }
        anterior = atual
    }
    return anterior[b.size].toDouble() / a.size
}

private val CONTRACOES = mapOf("i'm" to "i am", "i'll" to "i will", "don't" to "do not", "it's" to "it is", "i'd" to "i would", "can't" to "cannot", "that's" to "that is")

private fun palavras(frase: String): List<String> {
    var t = frase.lowercase(Locale.ROOT).replace('’', '\'')
    CONTRACOES.forEach { (de, para) -> t = t.replace(de, para) }
    return t.replace(Regex("[^a-z0-9' ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
}
