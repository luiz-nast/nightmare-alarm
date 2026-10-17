package com.implacavel.alarme

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.net.toUri
import java.io.File

/**
 * O barulho do alarme: música ou toque em loop, falas e bipes, vibração contínua, e do começo ao fim
 * (inclusive com a música calada depois do "stop") outras mídias pausadas. Enquanto a música ou uma
 * fala toca, o volume de alarme fica travado e subindo aos poucos ([EmAndamento.volumeAgora]); calado
 * (na etapa da câmera, com o YouTube), o volume é da pessoa. Só enquanto toca, cada bipe põe o volume
 * de alarme do celular no seu degrau, do levinho ao máximo. Usada só pelo [AlarmeService].
 *
 * Tudo usa USAGE_ALARM: o volume de alarme não depende do modo silencioso, e o Não Perturbe
 * deixa passar quando "alarmes" estão permitidos (o padrão do Android). E todo som sai no
 * alto-falante do celular, nunca no Bluetooth, no fone ou em outro aparelho conectado.
 */
class Sirene(private val ctx: Context) {
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var bipador: MediaPlayer? = null
    private var falante: MediaPlayer? = null // os sons de aviso do app
    private var faixa: AudioTrack? = null // uma fala ([falar])
    private var fimDaFaixa: Runnable? = null // garantia, se o fim da [faixa] não avisar
    private var volumeDoFalante = 0 // o da voz ou o dos avisos, conforme o que o [falante] ou a [faixa] toca
    private var vibrador: Vibrator? = null
    private var foco: AudioFocusRequest? = null
    private var acordador: MediaPlayer? = null // o quase silêncio em loop que deixa o alto-falante ligado ([manterAcordado])
    private var ondas: MediaPlayer? = null // o som de ondas do prazo pra sair de casa ([ondas])
    private var falaAcordando: Runnable? = null // uma fala esperando a saída do alto-falante acordar ([falarDepoisDeAcordar])
    private var acordadoPraFala = false // o [acordador] é só dessa fala: acaba com ela
    private var musica: String? = null
    private var foto: EmAndamento? = null
    private var fator = 1f // volume da música relativo ao de alarme ([volume])
    private var volumeAntesDoBipe: Int? = null // só enquanto um bipe toca no degrau dele

    /** O volume de alarme de agora, pela subida do alarme (30 s no normal, 2 min no remédio; veja [EmAndamento.volumeAgora]). */
    private fun piso() = foto?.volumeAgora(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), System.currentTimeMillis()) ?: 0

    /**
     * A cada segundo, enquanto a música ou uma fala toca, sobe o volume junto com a subida e desfaz
     * qualquer tentativa de abaixar. Calado, não mexe (o volume é da pessoa); durante um bipe, quem manda é ele.
     */
    private val travarVolume = object : Runnable {
        override fun run() {
            when {
                volumeAntesDoBipe != null -> {}
                falante != null || faixa != null -> subirAte(volumeDoFalante)
                player != null -> subirAoPiso()
            }
            handler.postDelayed(this, 1_000)
        }
    }

    private fun subirAoPiso() = subirAte(piso())

    private fun subirAte(volume: Int) {
        if (audio.getStreamVolume(AudioManager.STREAM_ALARM) < volume) runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
    }

    /** Os sons de aviso saem sempre no volume do fim da subida (40%), mesmo com ela no começo; a voz, no da tela principal ([Ajustes.volumeDaVoz], 30% de começo). */
    private fun volumeDosAvisos() = foto?.volumeDosAvisos(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)) ?: 0
    private fun volumeDaVoz() = foto?.volumeDaVoz(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), Ajustes.volumeDaVoz) ?: 0

    /**
     * Quanto a fala toca dentro do degrau dela ([EmAndamento.volumeDaVoz]). Os degraus do volume de alarme são em dB:
     * do Android 9 em diante, o ganho sai da interpolação entre o degrau de baixo e o de cima, em dB, no alto-falante
     * ([ganhoEntreDegraus]); antes dele, a conta simples ([EmAndamento.ganhoDaVoz]).
     */
    private fun ganhoDaVoz(): Float {
        val em = foto ?: return 1f
        val maximo = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val fracao = Ajustes.volumeDaVoz
        val degrau = em.volumeDaVoz(maximo, fracao)
        if (Build.VERSION.SDK_INT < 28 || degrau <= 1) return em.ganhoDaVoz(maximo, fracao)
        return runCatching {
            val db = { d: Int -> audio.getStreamVolumeDb(AudioManager.STREAM_ALARM, d, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) }
            ganhoEntreDegraus(maximo * fracao - (degrau - 1), db(degrau - 1), db(degrau))
        }.getOrElse { em.ganhoDaVoz(maximo, fracao) }
    }

    /** Fim do bipe: o volume de alarme volta ao de antes dele. */
    private val voltarDoBipe = Runnable {
        volumeAntesDoBipe?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
        volumeAntesDoBipe = null
    }

    /**
     * Música e volume vêm da foto do alarme [em]; a trava do volume vale até [desligar]. No [comeco]
     * (o disparo, ou a volta depois de o app cair), o volume vai pro de agora, exato: começa baixo mesmo
     * com o celular mais alto, e um app que caiu no meio de um bipe não deixa o alarme preso no degrau dele.
     */
    fun preparar(em: EmAndamento, comeco: Boolean) {
        val trocouSom = foto != null && foto?.alarme?.remedio != em.alarme.remedio
        musica = em.musica
        foto = em
        if (comeco) runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, piso(), 0) }
        // Outro alarme juntou no meio e trocou o som (música ↔ toque de remédio): troca já, se está tocando
        if (trocouSom) player?.let {
            runCatching { it.stop() }
            it.release()
            player = null
            tocarSom()
            player?.setVolume(fator, fator)
        }
        handler.removeCallbacks(travarVolume)
        handler.post(travarVolume)
    }

    /** Som e vibração (do começo, se já estavam tocando). */
    fun tocar() {
        calar()
        fator = 1f // a música nova começa no volume cheio
        subirAoPiso() // calado, o volume era da pessoa (ex.: abaixou pro YouTube)
        // Foco de áudio pausa a música ou o vídeo de outros apps até o alarme acabar ([desligar]).
        // Pedido de novo a cada vez: se outro app tomou o foco no meio, ele pausa de novo
        val pedido = foco ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(ATRIBUTOS)
            .build()
            .also { foco = it }
        audio.requestAudioFocus(pedido)
        tocarSom()
        vibrar()
    }

    /** Cala som e vibração (um bipe no meio devolve o volume). O volume continua travado, e as outras mídias, pausadas. */
    fun calar() {
        falaAcordando?.let(handler::removeCallbacks)
        falaAcordando = null
        if (acordadoPraFala) manterAcordado(false)
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        bipador?.release()
        bipador = null
        falante?.release()
        falante = null
        pararFala()
        handler.removeCallbacks(voltarDoBipe)
        voltarDoBipe.run()
        vibrador?.cancel()
        vibrador = null
    }

    /** Volume da música, de 0 a 1, relativo ao volume de alarme (a missão abaixa pra ouvir a voz). */
    fun volume(fator: Float) {
        this.fator = fator
        player?.setVolume(fator, fator)
    }

    /**
     * Bipe curto número [n] da vigia (a pessoa parou de olhar pra câmera): enquanto ele toca (e mais
     * [FOLGA_BIPE_MS]), o volume de alarme fica no degrau dele ([volumeDoBipe]), por cima da trava;
     * depois, volta ao de antes. O último, já no volume máximo, só fica mais alto com um som mais forte. No teste, param
     * no volume dos avisos (40%). Devolve quanto o bipe dura (ms), pra conversa não ouvir o próprio bipe.
     */
    fun bipe(n: Int, som: Int = if (n >= BIPES_ATE_ZERAR) R.raw.bipe_final else R.raw.bipe): Long {
        val maximo = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        // No teste (em casa, com gente dormindo), os bipes param no volume dos avisos (40%); no alarme, vão ao máximo
        val degrau = volumeDoBipe(n, maximo).let { if (foto?.alarme?.deTeste == true) minOf(it, volumeDosAvisos()) else it }
        if (volumeAntesDoBipe == null) volumeAntesDoBipe = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, degrau, 0) }
        Log.i(TAG, "Sirene: bipe $n no volume $degrau de $maximo")
        bipador?.release()
        bipador = tocarArquivo(somDoApp(som), emLoop = false)
        val duracao = (bipador?.duration?.coerceAtLeast(0) ?: 0).toLong()
        handler.removeCallbacks(voltarDoBipe)
        handler.postDelayed(voltarDoBipe, duracao + FOLGA_BIPE_MS)
        return duracao
    }

    /**
     * Toca uma vez uma fala gravada em [arquivo] (sem arquivo, só segue), no volume da voz; [aoTerminar] quando acaba ou
     * se não deu pra tocar. Pelo AudioTrack ([faixaDaFala]): pelo MediaPlayer, as falas de 5 s ou mais começavam baixinho.
     */
    fun falar(arquivo: File?, aoTerminar: () -> Unit = {}) {
        if (arquivo == null) return aoTerminar()
        pararFala()
        falante?.release()
        falante = null
        val volume = volumeDaVoz()
        volumeDoFalante = volume
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
        val (f, quadros) = faixaDaFala(arquivo, ATRIBUTOS) ?: run {
            Log.w(TAG, "Sirene: fala fora do formato do sintetizador, vai pelo MediaPlayer")
            return tocarUmaVez(Uri.fromFile(arquivo), volume, aoTerminar, ganhoDaVoz())
        }
        noAltoFalante(f)
        f.setVolume(ganhoDaVoz())
        val fim = Runnable {
            if (faixa !== f) return@Runnable
            pararFala()
            aoTerminar()
        }
        f.notificationMarkerPosition = quadros
        f.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack) = fim.run()
            override fun onPeriodicNotification(track: AudioTrack) {}
        }, handler)
        // Se o aviso do fim não vier, a fala acaba assim mesmo; mas, se ela começou atrasada e ainda está andando, espera
        var ondeEstava = -1
        val garantia = object : Runnable {
            override fun run() {
                if (faixa !== f) return
                val agora = runCatching { f.playbackHeadPosition }.getOrDefault(quadros)
                if (agora < quadros && agora > ondeEstava) {
                    ondeEstava = agora
                    handler.postDelayed(this, FOLGA_FIM_DA_FALA_MS)
                    return
                }
                Log.w(TAG, "Sirene: a fala acabou sem o aviso do fim ($agora de $quadros quadros)")
                fim.run()
            }
        }
        faixa = f
        fimDaFaixa = garantia
        handler.postDelayed(garantia, quadros * 1_000L / f.sampleRate + FOLGA_FIM_DA_FALA_MS)
        runCatching { f.play() }.onFailure {
            pararFala()
            aoTerminar()
        }
    }

    /** A fala tocando para, sem avisar o fim dela. */
    private fun pararFala() {
        fimDaFaixa?.let(handler::removeCallbacks)
        fimDaFaixa = null
        faixa?.let {
            it.setPlaybackPositionUpdateListener(null)
            runCatching { it.stop() }
            it.release()
        }
        faixa = null
    }

    /**
     * Uma fala depois de um silêncio longo, fora da conversa (os avisos de 10 em 10 min da câmera): acorda a saída do
     * alto-falante ([manterAcordado]) e fala [ACORDAR_ANTES_MS] depois, sem o meio segundo mais alto do começo; com a
     * fala, a saída volta a poder dormir. Já acordada, fala na hora.
     */
    fun falarDepoisDeAcordar(arquivo: File?) {
        if (arquivo == null) return
        if (acordador != null) return falar(arquivo)
        falaAcordando?.let(handler::removeCallbacks)
        manterAcordado(true)
        acordadoPraFala = true
        val fala = Runnable {
            falaAcordando = null
            falar(arquivo) {
                if (acordadoPraFala) manterAcordado(false)
            }
        }
        falaAcordando = fala
        handler.postDelayed(fala, ACORDAR_ANTES_MS)
    }

    /** O bipe positivo de "pode começar", antes da câmera. */
    fun positivo(aoTerminar: () -> Unit) = tocarUmaVez(somDoApp(R.raw.positivo), volumeDosAvisos(), aoTerminar)

    /**
     * Apito alto [n] (1 a 3) da verificação do meio, sem resposta: o som do último bipe da vigia, nos três
     * degraus de cima do [volumeDoBipe], cada vez mais alto.
     */
    fun apito(n: Int) = bipe(BIPES_ATE_ZERAR - 3 + n, R.raw.bipe_final)

    /** O "plim" de uma frase repetida certa, na verificação do meio. */
    fun acerto(aoTerminar: () -> Unit) = tocarUmaVez(somDoApp(R.raw.acerto), volumeDosAvisos(), aoTerminar)

    /** O bipe leve antes da fala de parabéns, no fim da atividade (e antes da verificação do meio). */
    fun leve(aoTerminar: () -> Unit) = tocarUmaVez(somDoApp(R.raw.leve), volumeDosAvisos(), aoTerminar)

    /** O estouro de comemoração, depois da fala de parabéns. */
    fun comemoracao(aoTerminar: () -> Unit) = tocarUmaVez(somDoApp(R.raw.comemoracao), volumeDosAvisos(), aoTerminar)

    /** Som de aviso (ou fala fora do formato do sintetizador), uma vez, exatamente no [volume] (travado enquanto toca). */
    private fun tocarUmaVez(uri: Uri, volume: Int, aoTerminar: () -> Unit, ganho: Float = 1f) {
        falante?.release()
        pararFala()
        volumeDoFalante = volume
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
        falante = tocarArquivo(uri, emLoop = false)?.apply {
            setVolume(ganho, ganho)
            setOnCompletionListener {
                it.release()
                if (falante === it) falante = null
                aoTerminar()
            }
        }
        if (falante == null) aoTerminar()
    }

    /**
     * Deixa o alto-falante ligado ([ligado]) com um quase silêncio em loop (`silencio.wav`, ruído a -84 dB, inaudível).
     * Sem som por 2 s, o Android desliga a saída do alto-falante (no S24 FE, "Standby delay 2 s"), e o primeiro meio
     * segundo de som depois que ela acorda sai uns 3 dB mais alto (medido na saída, `dumpsys media.audio_flinger`): as
     * falas da conversa, que vêm depois de uns segundos de silêncio, começariam um pouco mais altas que o resto. Ligado
     * no STOP, com a música acabando de tocar, a saída nem chega a dormir. (A voz que começava baixinha e ia subindo era
     * outra coisa, o MediaPlayer: veja [faixaDaFala].)
     */
    fun manterAcordado(ligado: Boolean) {
        acordadoPraFala = false
        if (!ligado) {
            acordador?.release()
            acordador = null
        } else if (acordador == null) {
            acordador = tocarArquivo(somDoApp(R.raw.silencio), emLoop = true)
            if (acordador == null) Log.w(TAG, "Sirene: não conseguiu deixar o alto-falante ligado")
        }
    }

    /**
     * O som de ondas do mar em loop (`ondas.ogg`, gerado no computador, sem música de ninguém) durante o prazo pra se
     * vestir e sair de casa ([ligado]), no volume em que estiver o alarme (o da voz, que acabou de falar).
     */
    fun ondas(ligado: Boolean) {
        if (!ligado) {
            ondas?.release()
            ondas = null
        } else if (ondas == null) {
            ondas = tocarArquivo(somDoApp(R.raw.ondas), emLoop = true)
        }
    }

    /** Fim: cala, solta a trava e as outras mídias e, com [volumeAntes], devolve o volume de alarme de antes. */
    fun desligar(volumeAntes: Int? = null) {
        handler.removeCallbacks(travarVolume)
        calar()
        manterAcordado(false)
        ondas(false)
        foco?.let { audio.abandonAudioFocusRequest(it) }
        foco = null
        volumeAntes?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
    }

    /**
     * A música da foto do alarme (no remédio, o toque suave do app); senão o toque de alarme do
     * celular; se nada puder ser lido (ex.: antes do primeiro desbloqueio), os bipes do app.
     */
    private fun tocarSom() {
        val candidatos = listOfNotNull(
            if (foto?.alarme?.remedio == true) somDoApp(R.raw.lembrete) else musica?.toUri(),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            somDoApp(R.raw.alarme_reserva),
        )
        for (uri in candidatos) {
            player = tocarArquivo(uri, emLoop = true)
            if (player != null) {
                Log.i(TAG, "Sirene: tocando $uri")
                return
            }
            Log.w(TAG, "Sirene: não conseguiu tocar $uri")
        }
    }

    /** Um som que vem dentro do app (res/raw). */
    private fun somDoApp(id: Int) = "android.resource://${ctx.packageName}/$id".toUri()

    /** Toca [uri] no alto-falante do celular, no volume de alarme. Null se não deu pra ler. */
    private fun tocarArquivo(uri: Uri, emLoop: Boolean): MediaPlayer? {
        val mp = MediaPlayer()
        return runCatching {
            mp.setAudioAttributes(ATRIBUTOS)
            mp.setDataSource(ctx, uri)
            noAltoFalante(mp) // só depois do setDataSource: antes dele o MediaPlayer ignora a saída escolhida
            mp.isLooping = emLoop
            mp.prepare()
            mp.start()
            mp
        }.getOrElse {
            mp.release()
            null
        }
    }

    /**
     * Fixa a saída no alto-falante do celular: com fone ou Bluetooth conectado, o Android tocaria o
     * alarme neles também (ou só neles). Antes do Android 9 o MediaPlayer não tem como escolher a saída.
     */
    private fun noAltoFalante(saida: AudioRouting) {
        if (Build.VERSION.SDK_INT < 28) return
        val alto = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (alto == null || !saida.setPreferredDevice(alto)) Log.w(TAG, "Sirene: não conseguiu fixar o som no alto-falante")
    }

    @Suppress("DEPRECATION") // as APIs antigas só são usadas nas versões do Android que não têm as novas
    private fun vibrar() {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            ctx.getSystemService(Vibrator::class.java)
        }
        if (!v.hasVibrator()) return
        val efeito = VibrationEffect.createWaveform(longArrayOf(0, 900, 400, 900, 400, 1_800, 700), 1)
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(efeito, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            v.vibrate(efeito, ATRIBUTOS)
        }
        vibrador = v
    }

    private companion object {
        /** Antes de uma fala depois de um silêncio longo, o tempo pra saída do alto-falante acordar ([falarDepoisDeAcordar]). */
        const val ACORDAR_ANTES_MS = 1_500L

        /** Depois do tempo de uma fala, quanto ainda espera o aviso do fim dela antes de dar por terminada. */
        const val FOLGA_FIM_DA_FALA_MS = 1_000L

        /** Depois do som do bipe, quanto o volume ainda fica no degrau dele: folga pro som sair do alto-falante. */
        const val FOLGA_BIPE_MS = 700L

        val ATRIBUTOS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
