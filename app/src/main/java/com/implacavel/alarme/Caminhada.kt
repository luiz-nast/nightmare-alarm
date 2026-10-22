// Tela da caminhada da manhã (despertador "conversa + caminhada"), mostrada pela AlarmeActivity depois da
// conversa: o prazo pra sair de casa e a caminhada contada pelo GPS e pelos passos, num anel. Sem plano B: dentro de
// casa, sem sinal, com a localização desligada ou sem a permissão dela, o tempo não anda e os bipes não param. As
// regras e os tempos ficam em RegrasCaminhada.kt; a vigia (bipes de parado), no AlarmeService.
package com.implacavel.alarme

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** O GPS manda um fixo por segundo, mesmo parado: o mais rápido que o GPS do celular dá. */
private const val INTERVALO_GPS_MS = 1_000L

/** De quanto em quanto tempo o [ContadorDaCaminhada] confere o relógio (e a tela se redesenha): contagens, fixo velho e o sinal de vida. */
private const val CONFERIR_MS = 250L

/** De quanto em quanto tempo, andando, o [ContadorDaCaminhada] dá o sinal de vida (aoViver). */
private const val AVISO_ANDANDO_MS = 1_000L

/** Sem fixo novo por esse tempo (o GPS manda um por segundo), o último deixa de valer: "sem sinal", e parado. */
private const val FIXO_VELHO_MS = 4_000L

/** De quanto em quanto tempo os sentidos releem a permissão da localização (liberada ou tirada no meio). */
private const val RELER_PERMISSAO_MS = 1_000L

private val ANEL = 220.dp

private val VerdeAndando = Color(0xFF2E7D32)
private val VerdeConferindo = Color(0xFF7CB342)
private val VermelhoParado = Color(0xFFC62828)
private val CinzaParado = Color(0xFF9E9E9E)

/** A última avaliação do GPS ([avaliacao]), com a [precisao] do fixo (m) e [quando] chegou (SystemClock.elapsedRealtime). */
data class LeituraGps(val avaliacao: Avaliacao, val precisao: Float?, val quando: Long)

/** Como um trecho da trilha contou: somou, ainda conferindo (vira um dos outros), ou não contou. */
enum class Trecho { CONTOU, CONFERINDO, NAO }

/** Um ponto da trilha na tela: onde (o caminho filtrado, em metros) e como o trecho até ele contou. */
data class PontoDaTrilha(val leste: Double, val norte: Double, val trecho: Trecho)

/** Os pontos da trilha que a tela guarda: os últimos 20 min (um fixo por segundo). */
private const val TRILHA_MAX = 1_200

/** A trilha mostra no mínimo um quadrado deste lado, em metros: parado, o ponto fica no meio. */
private const val TRILHA_LADO_MIN_M = 40.0

/**
 * O GPS (um fixo por segundo), o acelerômetro e os satélites da caminhada, com o [detector] que lê os três. Ligados
 * pelo serviço desde o STOP de uma manhã com caminhada (AlarmeService.ligarSentidos; no TesteDoGps, pela tela, com
 * [rememberSentidosDaCaminhada]): o GPS acerta a mira durante a conversa e, na hora de sair, já está pronto (a ideia
 * do dono; ligado só na caminhada, o 1º minuto dela era o GPS escorregando e saltando). Vivem no serviço, não na tela
 * (serviço com o tipo "location", ganho no STOP com a tela na frente), pra caminhada seguir com a tela fechada ou no
 * bolso, e passam da conversa pra caminhada sem desligar. Só o relógio da caminhada ([ContadorDaCaminhada], via
 * [aoAvaliar]) soma tempo e desenha a trilha; antes, o caminho anda (pra saber de onde a pessoa saiu) sem contar. Cada fixo vai pro log ([detalheDoFixo],
 * sem coordenadas): na caminhada de 10-17 à noite só havia o resumo de 30 s, e não deu pra saber por que os passos
 * sumiram.
 */
class SentidosDaCaminhada {
    val detector = DetectorDeCaminhada()

    /** A última avaliação, pra tela; null antes do primeiro fixo. */
    var leitura by mutableStateOf<LeituraGps?>(null)
        private set

    val trilha = mutableStateListOf<PontoDaTrilha>()

    /** A permissão da localização, relida a cada [RELER_PERMISSAO_MS]. */
    var permitida by mutableStateOf(true)
        internal set

    /** O celular não tem GPS nenhum (defeito: a caminhada não prende ninguém). */
    var semGps by mutableStateOf(false)
        internal set

    /** Posto pelo [ContadorDaCaminhada]: recebe cada avaliação e soma. Null antes dele (o GPS esquentando). */
    internal var aoAvaliar: ((Avaliacao) -> Unit)? = null

    /** O sinal médio dos 4 satélites mais fortes da banda L1 (dB-Hz), pro log; null sem satélite. */
    internal var ceu: Float? = null

    private val resumo = ResumoDoGps()

    internal fun fixo(local: Location) {
        val atual = fixoDe(local)
        val chegou = SystemClock.elapsedRealtime()
        val contando = aoAvaliar != null
        val avaliacao = detector.fixo(atual, chegou, contando)
        resumo.somar(atual, avaliacao, chegou, ceu)
        leitura = LeituraGps(avaliacao, atual.precisaoM, chegou)
        Log.i(TAG, detalheDoFixo(detector.trilho.origem ?: atual, atual, local, chegou, avaliacao))
        if (!contando) return
        avaliacao.ponto?.let { (leste, norte) ->
            val trecho = when (avaliacao.movimento) {
                Movimento.ANDANDO -> Trecho.CONTOU
                Movimento.CONFERINDO -> Trecho.CONFERINDO
                else -> Trecho.NAO
            }
            // O que estava conferindo vira verde quando soma, e cinza quando a conferência se perde
            if (trecho == Trecho.CONTOU || avaliacao.conferindo == 0L) repintarConferindo(if (trecho == Trecho.CONTOU) Trecho.CONTOU else Trecho.NAO)
            trilha += PontoDaTrilha(leste, norte, trecho)
            if (trilha.size > TRILHA_MAX) trilha.removeAt(0)
        }
        aoAvaliar?.invoke(avaliacao)
    }

    // Toda conferência termina aqui (somou, perdeu ou recomeçou): todo trecho conferindo na trilha é da de agora
    private fun repintarConferindo(como: Trecho) {
        for (i in trilha.indices) if (trilha[i].trecho == Trecho.CONFERINDO) trilha[i] = trilha[i].copy(trecho = como)
    }

    private var gps: LocationManager? = null
    private var sensores: SensorManager? = null
    private val principal = Handler(Looper.getMainLooper())
    private val ouvinte = object : LocationListener {
        override fun onLocationChanged(local: Location) = fixo(local)

        // Antes do Android 11 estes três não têm corpo pronto: sem eles, o app cai quando a localização liga ou desliga
        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Chamado só antes do Android 10")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }
    private val satelites = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            ceu = sinalDaL1(status)
        }
    }
    private val acelerometro = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val (x, y, z) = e.values
            // O relógio do sensor é o mesmo dos fixos (elapsedRealtimeNanos)
            detector.acelerometro(e.timestamp / 1_000_000, sqrt(x * x + y * y + z * z))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** O GPS, os satélites e o acelerômetro estão ligados. */
    var ligados = false
        private set

    /**
     * Liga o GPS (um fixo por segundo), os satélites e o acelerômetro, se a localização está permitida ([permitida],
     * relida aqui). Já ligados, não faz nada. Sem permissão, [permitida] fica false e nada liga; sem GPS no celular,
     * [semGps]. Chamado no serviço (a caminhada segue com a tela fechada) e no TesteDoGps.
     */
    fun ligar(ctx: Context) {
        permitida = Poder.LOCALIZACAO.liberado(ctx)
        if (ligados) return
        if (!permitida) {
            Log.w(TAG, "Caminhada: sem permissão de localização, o tempo não anda")
            return
        }
        val gps = ctx.getSystemService(LocationManager::class.java)
        val sensores = ctx.getSystemService(SensorManager::class.java)
        try {
            ouvirGps(gps, ouvinte, satelites, principal)
            sensores.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensores.registerListener(acelerometro, it, SensorManager.SENSOR_DELAY_GAME) }
                ?: Log.w(TAG, "Caminhada: o celular não tem acelerômetro")
            this.gps = gps
            this.sensores = sensores
            ligados = true
            Log.i(TAG, "Caminhada: GPS e acelerômetro ligados")
        } catch (e: SecurityException) {
            // Permissão tirada entre a conferência e o pedido: quem chama relê e a tela mostra o botão
            Log.w(TAG, "Caminhada: sem permissão de localização", e)
            permitida = false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Caminhada: o celular não tem GPS, defeito: o alarme desliga", e)
            semGps = true
        }
    }

    /** Desliga tudo (fim da caminhada, a música voltou, o alarme acabou). */
    fun desligar() {
        if (!ligados) return
        gps?.removeUpdates(ouvinte)
        gps?.unregisterGnssStatusCallback(satelites)
        sensores?.unregisterListener(acelerometro)
        ligados = false
        Log.i(TAG, "Caminhada: GPS e acelerômetro desligados")
    }
}

/**
 * O relógio da caminhada, fora da tela (no serviço, ou no TesteDoGps): a cada [CONFERIR_MS] lê os [sentidos], soma o
 * que cada avaliação manda ([Avaliacao.soma]) a partir de [andadoInicialMs] e avisa: [aoSomar] quando o somado ou o
 * que está conferindo mudam; [aoViver] a cada [AVISO_ANDANDO_MS] com sinal de vida ([Avaliacao.valeProBipe]: passo
 * ou velocidade no GPS, somando ou não) e no instante em que o sinal para (os bipes de parado contam daí);
 * [aoConcluir] uma vez, com [metaMs] somados (a meta pode subir no meio: alarme de verdade no lugar do teste);
 * [aoDefeito] num celular sem GPS nenhum (um defeito não prende ninguém). Era o laço da tela da caminhada: foi pro
 * serviço pra caminhada seguir com a tela fechada, no bolso (10-22).
 */
class ContadorDaCaminhada(
    private val ctx: Context,
    private val sentidos: SentidosDaCaminhada,
    andadoInicialMs: Long,
    private val metaMs: () -> Long,
    private val aoSomar: (andadoMs: Long, conferindoMs: Long) -> Unit,
    private val aoViver: (andadoMs: Long) -> Unit,
    private val aoConcluir: () -> Unit,
    private val aoDefeito: () -> Unit,
) {
    var andadoMs = andadoInicialMs
        private set
    var conferindoMs = 0L
        private set
    private val principal = Handler(Looper.getMainLooper())
    private var ultimoAviso = 0L
    private var andavaAntes = false
    private var ligadaAntes = true
    private var acabou = false
    private val tique = object : Runnable {
        override fun run() {
            conferir()
            if (!acabou) principal.postDelayed(this, CONFERIR_MS)
        }
    }

    fun ligar() {
        Log.i(TAG, "Caminhada: começou, ${andadoMs / 1000} s já andados de ${metaMs() / 1000}")
        sentidos.aoAvaliar = { avaliacao ->
            andadoMs += avaliacao.soma
            conferindoMs = avaliacao.conferindo
            aoSomar(andadoMs, conferindoMs)
        }
        principal.post(tique)
    }

    fun desligar() {
        acabou = true
        sentidos.aoAvaliar = null
        principal.removeCallbacks(tique)
    }

    private fun conferir() {
        val agora = SystemClock.elapsedRealtime()
        val ligada = gpsLigado(ctx)
        if (ligada != ligadaAntes) Log.i(TAG, "Caminhada: localização ${if (ligada) "ligada" else "desligada"}")
        ligadaAntes = ligada
        if (sentidos.semGps) {
            acabou = true
            return aoDefeito()
        }
        val leitura = recente(sentidos.leitura, agora)
        val andando = leitura?.avaliacao?.valeProBipe == true
        if (andando != andavaAntes) Log.i(TAG, "Caminhada: ${retorno(leitura).second}, ${andadoMs / 1000} s de ${metaMs() / 1000}")
        // A cada segundo andando, e no instante em que parou: a contagem pro bipe começa daí
        if (andando && agora - ultimoAviso >= AVISO_ANDANDO_MS || andavaAntes && !andando) {
            aoViver(andadoMs)
            ultimoAviso = agora
        }
        andavaAntes = andando
        if (andadoMs >= metaMs()) {
            acabou = true
            Log.i(TAG, "Missão: caminhada completa, alarme desligado")
            aoConcluir()
        }
    }
}

/**
 * Os [SentidosDaCaminhada] de uma tela (o TesteDoGps), ligados enquanto ela vive e a localização está permitida
 * (relida a cada [RELER_PERMISSAO_MS]). No alarme, quem os tem é o serviço.
 */
@Composable
fun rememberSentidosDaCaminhada(): SentidosDaCaminhada {
    val ctx = LocalContext.current
    val sentidos = remember { SentidosDaCaminhada() }
    DisposableEffect(Unit) {
        sentidos.ligar(ctx)
        onDispose { sentidos.desligar() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(RELER_PERMISSAO_MS)
            sentidos.ligar(ctx) // liberada nas Configurações no meio: liga; já ligados, não faz nada
        }
    }
    return sentidos
}

/**
 * Etapa da caminhada, só a tela: o anel com o tempo andando ([andadoMs], somado pelo serviço com o [ContadorDaCaminhada]
 * sobre os [sentidos] dele) até [metaMs], com o que ainda está conferindo ([conferindoMs]) mais claro. Até [prazoAte]
 * (SystemClock.elapsedRealtime) é o prazo pra sair de casa: andar nele já conta, e a tela mostra quanto falta; depois,
 * parado, a contagem pro bipe a partir do último sinal de vida recebido pelo serviço ([ultimoPasso], [paradoHa], [bipeDoParado]).
 * Tudo o que decide (somar, bipar, concluir) é do serviço, que segue com a tela fechada: aqui só se desenha.
 *
 * Funciona sem internet: é o GPS puro, ouvindo os satélites. Sem sinal (dentro de casa), com a localização desligada
 * (botão pra ligar) ou sem a permissão dela (botão pra liberar), não conta: tem que ir pra rua. Com [detalhar] (o
 * TesteDoGps), sem prazo nem bipe. No prazo, o botão das ondas ([ondasMudas], [onOndas]): quem não quer ouvir o mar
 * emudece nesta manhã. Depois do prazo, no primeiro bipe de parado, a [miniTarefa] do serviço: a frase pra repetir e
 * ganhar mais prazo (quem escuta é o serviço; a tela mostra o que ele ouve).
 */
@Composable
fun EtapaCaminhada(
    sentidos: SentidosDaCaminhada,
    andadoMs: Long,
    conferindoMs: Long,
    metaMs: Long,
    prazoAte: Long,
    ultimoPasso: Long,
    detalhar: Boolean = false,
    ondasMudas: Boolean = false,
    onOndas: ((ligadas: Boolean) -> Unit)? = null,
    miniTarefa: MiniTarefa? = null,
) {
    val ctx = LocalContext.current
    val activity = LocalActivity.current
    var ligada by remember { mutableStateOf(gpsLigado(ctx)) }
    var agora by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CONFERIR_MS)
            agora = SystemClock.elapsedRealtime()
            ligada = gpsLigado(ctx)
        }
    }

    val leitura = recente(sentidos.leitura, agora)
    val andando = leitura?.avaliacao?.valeProBipe == true
    val (corAlvo, status) = when {
        !sentidos.permitida -> VermelhoParado to "Location has no permission"
        !ligada -> VermelhoParado to "Turn on location"
        else -> retorno(leitura)
    }
    val cor by animateColorAsState(corAlvo, label = "cor da caminhada")
    val noPrazo = if (detalhar) "GPS test: no beeps" else avisoDoPrazo(agora, prazoAte)
    val paradoMs = if (andando) 0L else paradoHa(agora, ultimoPasso, prazoAte) ?: 0L
    val metaSegura = metaMs.coerceAtLeast(1)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(ANEL), contentAlignment = Alignment.Center) {
            AnelDaCaminhada(
                (andadoMs.toFloat() / metaSegura).coerceIn(0f, 1f), ((andadoMs + conferindoMs).toFloat() / metaSegura).coerceIn(0f, 1f),
                cor, Modifier.fillMaxSize(),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tempoAndado(andadoMs), color = AzulCaminhada, fontSize = 56.sp, fontWeight = FontWeight.Black)
                Text("of ${tempoAndado(metaMs)} walking", color = AzulCaminhada.copy(alpha = 0.7f), fontSize = 18.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(status, color = cor, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        // Sempre presente, pra tela não pular: o prazo pra sair ou, depois dele, a barra do bipe
        if (noPrazo != null) {
            Text(noPrazo, color = AzulCaminhada, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            // As ondas do mar tocam no prazo: quem não quer, emudece (e fica assim nas próximas manhãs)
            if (onOndas != null) {
                TextButton(onClick = { onOndas(ondasMudas) }, colors = ButtonDefaults.textButtonColors(contentColor = AzulCaminhada)) {
                    Text(if (ondasMudas) "🔇 Waves off · tap to turn on" else "🌊 Waves on · tap to mute", fontSize = 15.sp)
                }
            }
        } else {
            BarraDoBipe(paradoMs)
        }
        if (miniTarefa != null) {
            Spacer(Modifier.height(12.dp))
            MiniTarefaDoPrazo(miniTarefa)
        }
        if (!sentidos.permitida) {
            Spacer(Modifier.height(12.dp))
            PedirLocalizacao()
        } else if (!ligada) {
            Spacer(Modifier.height(12.dp))
            BotaoGrande("TURN ON LOCATION", { desbloquear(activity) { abrirLocalizacao(ctx) } }, fundoClaro = true)
        }
        Spacer(Modifier.height(12.dp))
        Trilha(sentidos.trilha, Modifier.size(TRILHA_TAMANHO))
        Text("Your trail: green where it counted", color = AzulCaminhada.copy(alpha = 0.7f), fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Text("Only walking counts, outdoors, on foot", color = AzulCaminhada.copy(alpha = 0.7f), fontSize = 15.sp)
    }
}

private val TRILHA_TAMANHO = 180.dp

/**
 * A mini tarefa do prazo pra sair ([MiniTarefa]), só a tela: a frase que a voz ofereceu e, na vez da pessoa
 * ([MiniTarefa.suaVez], depois de a voz terminar), o que o serviço está ouvindo ([MiniTarefa.ouvido]), pintando de
 * verde as palavras já ditas ([palavrasDitas]), como na verificação da câmera. Quem escuta e dá o prazo é o serviço.
 */
@Composable
private fun MiniTarefaDoPrazo(tarefa: MiniTarefa) {
    val ouvido = tarefa.ouvido
    Column(
        Modifier.fillMaxWidth(0.92f).background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(20.dp)).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Fell asleep? Repeat this and you get more time:", color = AzulCaminhada, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        val frase = buildAnnotatedString {
            append("“")
            palavrasDitas(tarefa.frase, ouvido).forEachIndexed { i, (palavra, dita) ->
                if (i > 0) append(" ")
                if (dita) withStyle(SpanStyle(color = VerdeAndando)) { append(palavra) } else append(palavra)
            }
            append("”")
        }
        Text(frase, color = AzulCaminhada, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(
            if (tarefa.suaVez && ouvido.isNotBlank()) "Heard: \"${ouvido.split(" ").takeLast(12).joinToString(" ")}\"" else "",
            color = AzulCaminhada.copy(alpha = 0.7f), fontSize = 14.sp, textAlign = TextAlign.Center, minLines = 1, maxLines = 2,
        )
        Text(if (tarefa.suaVez) "🎤 Your turn" else "Listen…", color = if (tarefa.suaVez) VerdeAndando else AzulCaminhada.copy(alpha = 0.7f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * A barra do bipe, depois do prazo pra sair: o tempo parado ([paradoMs], sem sinal de vida) enche a barra de 0 a
 * [PARADO_MAXIMO_MS] (41 s), de verde a vermelho; os [BIPES_PARADO_ATE_O_MAXIMO] traços são os bipes, cada um mais
 * alto (o traço, como o volume), o 1º aos [PARADO_MS]. Em cima, a linha que diz onde está ([textoDoParado]); andando,
 * a barra vazia e a linha em branco (pra tela não pular).
 */
@Composable
private fun BarraDoBipe(paradoMs: Long) {
    val fracao = (paradoMs.toFloat() / PARADO_MAXIMO_MS).coerceIn(0f, 1f)
    val bipe = bipeDoParado(paradoMs)
    val cor = when {
        paradoMs == 0L -> VerdeAndando
        bipe == 0 -> AzulCaminhada
        bipe < BIPES_PARADO_ATE_O_MAXIMO -> lerp(AzulCaminhada, VermelhoParado, bipe.toFloat() / BIPES_PARADO_ATE_O_MAXIMO)
        else -> VermelhoParado
    }
    Column(Modifier.fillMaxWidth(0.85f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (paradoMs == 0L) "" else textoDoParado(paradoMs), color = cor, fontSize = 20.sp, fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(30.dp)) {
            val trilho = 10.dp.toPx()
            val baseY = size.height - trilho / 2
            val largura = size.width
            drawLine(AzulCaminhada.copy(alpha = 0.2f), Offset(0f, baseY), Offset(largura, baseY), trilho, StrokeCap.Round)
            if (fracao > 0f) {
                drawLine(cor, Offset(0f, baseY), Offset(largura * fracao, baseY), trilho, StrokeCap.Round)
            }
            // Um traço por bipe, do fraco ao máximo: mais alto conforme o volume; aceso quando já tocou
            for (n in 1..BIPES_PARADO_ATE_O_MAXIMO) {
                val x = largura * (PARADO_MS + (n - 1) * ENTRE_BIPES_PARADO_MS).toFloat() / PARADO_MAXIMO_MS
                val altura = 6.dp.toPx() + (size.height - trilho - 6.dp.toPx()) * n / BIPES_PARADO_ATE_O_MAXIMO
                val aceso = bipe >= n
                drawLine(
                    if (aceso) VermelhoParado else AzulCaminhada.copy(alpha = 0.45f), Offset(x, baseY - trilho / 2 - 2.dp.toPx()),
                    Offset(x, baseY - trilho / 2 - 2.dp.toPx() - altura), if (aceso) 4.dp.toPx() else 2.dp.toPx(), StrokeCap.Round,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("quiet", color = AzulCaminhada.copy(alpha = 0.7f), fontSize = 12.sp)
            Text("beeps from ${PARADO_MS / 1000} s", color = AzulCaminhada.copy(alpha = 0.7f), fontSize = 12.sp)
            Text("max at ${PARADO_MAXIMO_MS / 1000} s", color = VermelhoParado.copy(alpha = 0.8f), fontSize = 12.sp)
        }
    }
}

/**
 * A trilha da caminhada vista de cima (norte pra cima), na escala que cabe tudo ([TRILHA_LADO_MIN_M] no mínimo):
 * verde onde contou, verde claro onde ainda está conferindo, cinza onde não contou. É o caminho filtrado, não o GPS
 * cru: parado, o ponto não escorrega; um salto do GPS não aparece.
 */
@Composable
private fun Trilha(pontos: List<PontoDaTrilha>, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.6f))) {
        if (pontos.isEmpty()) return@Canvas
        val minX = pontos.minOf { it.leste }
        val maxX = pontos.maxOf { it.leste }
        val minY = pontos.minOf { it.norte }
        val maxY = pontos.maxOf { it.norte }
        val lado = maxOf(TRILHA_LADO_MIN_M, (maxX - minX) * 1.2, (maxY - minY) * 1.2)
        val pxPorM = (minOf(size.width, size.height) / lado).toFloat()
        val meioX = (minX + maxX) / 2
        val meioY = (minY + maxY) / 2
        fun ponto(p: PontoDaTrilha) = Offset(
            size.width / 2 + ((p.leste - meioX) * pxPorM).toFloat(),
            size.height / 2 - ((p.norte - meioY) * pxPorM).toFloat(), // norte pra cima
        )
        for (i in 1 until pontos.size) {
            val cor = when (pontos[i].trecho) {
                Trecho.CONTOU -> VerdeAndando
                Trecho.CONFERINDO -> VerdeConferindo
                Trecho.NAO -> CinzaParado
            }
            drawLine(cor, ponto(pontos[i - 1]), ponto(pontos[i]), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        }
        drawCircle(AzulCaminhada, radius = 6.dp.toPx(), center = ponto(pontos.last()))
    }
}

/**
 * O GPS do celular está ligado (do Android 9 em diante, segue o botão da localização): sem ele, a
 * caminhada não tem como contar. É o que a etapa da caminhada confere.
 */
fun gpsLigado(ctx: Context): Boolean = ctx.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER)

/** A última leitura, se ainda vale (chegou há até [FIXO_VELHO_MS]); senão null: sem sinal. */
private fun recente(leitura: LeituraGps?, agora: Long): LeituraGps? = leitura?.takeIf { agora - it.quando <= FIXO_VELHO_MS }

/**
 * O retorno de cada leitura do GPS (null = sem sinal): cor (verde andando, verde claro conferindo, vermelho no resto)
 * e mensagem, com o porquê do jeito que a regra viu ([Avaliacao.motivo]), pra pessoa saber o que fazer.
 */
private fun retorno(leitura: LeituraGps?): Pair<Color, String> {
    if (leitura == null) return VermelhoParado to "No GPS signal: go outside, away from buildings"
    val avaliacao = leitura.avaliacao
    return when (avaliacao.movimento) {
        Movimento.ANDANDO -> VerdeAndando to when (avaliacao.motivo) {
            "pelos passos" -> "Walking by your steps ✓"
            else -> "Walking ${avaliacao.velocidade?.let(::textoDaVelocidade).orEmpty()} ✓"
        }
        Movimento.CONFERINDO -> VerdeConferindo to when (avaliacao.motivo) {
            "começando" -> "Starting to walk…"
            "no lugar" -> "Steps, but the GPS does not see you move yet: open sky helps"
            else -> "Walking… checking"
        }
        Movimento.PARADO -> when (avaliacao.motivo) {
            "passada longa", "sem passo" -> VermelhoParado to "Only on foot counts"
            "não saiu do lugar" -> VermelhoParado to "The GPS does not see you moving: go outside"
            else -> VermelhoParado to "Stopped: walk!"
        }
        Movimento.RAPIDO_DEMAIS -> VermelhoParado to "Too fast: only on foot counts"
        Movimento.IMPRECISO -> VermelhoParado to "Weak GPS${leitura.precisao?.let { " (±${it.roundToInt()} m)" }.orEmpty()}: go outside, away from buildings"
        Movimento.FALSO -> VermelhoParado to "Fake location does not count"
    }
}

/** O fixo do Android sem o Android, pras regras de RegrasCaminhada.kt. */
private fun fixoDe(local: Location) = Fixo(
    // O relógio do fixo, que não pula; se o celular não disser, a hora em que chegou
    tempoMs = (local.elapsedRealtimeNanos / 1_000_000).takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
    latitude = local.latitude,
    longitude = local.longitude,
    precisaoM = if (local.hasAccuracy()) local.accuracy else null,
    velocidadeMs = if (local.hasSpeed()) local.speed else null,
    precisaoVelocidade = if (local.hasSpeedAccuracy()) local.speedAccuracyMetersPerSecond else null,
    falso = localizacaoFalsa(local),
    rumo = if (local.hasBearing()) local.bearing else null,
    precisaoRumo = if (local.hasBearingAccuracy()) local.bearingAccuracyDegrees else null,
)

/**
 * O sinal médio (dB-Hz) dos 4 satélites mais fortes usados no fixo, só da banda L1 (o S24 FE ouve também a L5, que
 * mistura a conta); null sem nenhum. Na rua, andando, deu 41 a 46; dentro de casa, parado, 36 a 37 (10-17): ainda
 * só pro log, pra calibrar um dia um "céu aberto".
 */
fun sinalDaL1(status: GnssStatus): Float? {
    val l1 = (0 until status.satelliteCount).filter {
        status.usedInFix(it) && status.hasCarrierFrequencyHz(it) && status.getCarrierFrequencyHz(it) in 1_559e6f..1_610e6f
    }
    return l1.map(status::getCn0DbHz).sortedDescending().take(4).takeIf { it.isNotEmpty() }?.average()?.toFloat()
}

/**
 * O resumo da caminhada no log a cada 30 s, pra ajustar as regras com o GPS de verdade: quantos fixos, a precisão do
 * meio, a velocidade do GPS e os passos por minuto do meio, o sinal do céu, quanto somou, e o que a regra disse de
 * cada um ([Avaliacao.motivo]). Sem as coordenadas (privacidade): só números.
 */
private class ResumoDoGps {
    private var desde = 0L
    private val precisoes = mutableListOf<Float>()
    private val velocidades = mutableListOf<Float>()
    private val cadencias = mutableListOf<Float>()
    private val ceus = mutableListOf<Float>()
    private var somado = 0L
    private val motivos = mutableMapOf<String, Int>()

    fun somar(fixo: Fixo, avaliacao: Avaliacao, agora: Long, ceu: Float?) {
        if (desde == 0L) desde = agora
        fixo.precisaoM?.let { precisoes += it }
        avaliacao.velocidade?.let { velocidades += it }
        avaliacao.cadencia?.let { cadencias += it }
        ceu?.let { ceus += it }
        somado += avaliacao.soma
        motivos[avaliacao.motivo] = (motivos[avaliacao.motivo] ?: 0) + 1
        if (agora - desde < 30_000) return
        fun meio(lista: List<Float>) = lista.sorted().getOrNull(lista.size / 2)
        Log.i(
            TAG,
            String.format(
                Locale.ROOT, "Caminhada (30 s): %d fixos, precisão ±%s m, GPS %s m/s, %s passos/min, céu %s dB-Hz, somou %d s, conferindo %d s; %s",
                motivos.values.sum(), meio(precisoes)?.roundToInt(), meio(velocidades)?.let { "%.1f".format(Locale.ROOT, it) },
                meio(cadencias)?.roundToInt(), meio(ceus)?.roundToInt(), somado / 1000, avaliacao.conferindo / 1000, motivos,
            ),
        )
        desde = agora
        precisoes.clear()
        velocidades.clear()
        cadencias.clear()
        ceus.clear()
        somado = 0
        motivos.clear()
    }
}

/**
 * Uma linha do log por fixo: onde o [atual] cru está, em metros a leste e ao norte da [origem] do caminho (sem as
 * coordenadas: privacidade), a precisão, a velocidade e o rumo medidos (com as incertezas), o atraso do fixo; e o que
 * a regra disse: o movimento e o porquê, o ponto do caminho, a velocidade do meio, os passos por minuto, a passada, a
 * coerência do rumo, quanto andou na janela (pela posição / pela velocidade, ✓ confirmou), quanto somou, quanto está
 * conferindo e a confiança guardada.
 */
private fun detalheDoFixo(origem: Fixo, atual: Fixo, local: Location, chegou: Long, a: Avaliacao): String {
    val (leste, norte) = emMetros(origem, atual)
    fun num(v: Number?, casas: Int = 1) = v?.let { String.format(Locale.ROOT, "%.${casas}f", it.toDouble()) } ?: "-"
    val rumoIncerteza = if (local.hasBearingAccuracy()) local.bearingAccuracyDegrees else null
    return String.format(
        Locale.ROOT, "GPS %+7.1f L %+7.1f N ±%s v %s±%s r %s±%s atraso %d -> %s %s | caminho %s %s v %s passos %s passada %s coer %s andou %s/%s%s soma %d conf %d confiança %d",
        leste, norte, num(atual.precisaoM), num(atual.velocidadeMs, 2), num(atual.precisaoVelocidade, 2), num(atual.rumo, 0), num(rumoIncerteza, 0),
        chegou - atual.tempoMs, a.movimento, a.motivo, num(a.ponto?.first), num(a.ponto?.second), num(a.velocidade, 2), num(a.cadencia, 0),
        num(a.passada, 2), num(a.coerencia, 2), num(a.deslocamento?.pelaPosicao), num(a.deslocamento?.pelaVelocidade),
        if (a.deslocamento?.confirma == true) " ✓" else "", a.soma / 1000, a.conferindo / 1000, a.confianca / 1000,
    )
}

/** O fixo veio de um app de localização falsa (opção de desenvolvedor). */
@Suppress("DEPRECATION") // isFromMockProvider, o único jeito antes do Android 12
private fun localizacaoFalsa(local: Location): Boolean = if (Build.VERSION.SDK_INT >= 31) local.isMock else local.isFromMockProvider

/**
 * Pede ao GPS um fixo por segundo, mesmo parado (distância mínima 0), e os satélites, entregues no fio principal.
 * Sem a permissão, lança SecurityException; celular sem GPS, IllegalArgumentException.
 */
@SuppressLint("MissingPermission") // quem chama trata a falta da permissão
private fun ouvirGps(gps: LocationManager, ouvinte: LocationListener, satelites: GnssStatus.Callback, principal: Handler) {
    gps.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVALO_GPS_MS, 0f, ouvinte, Looper.getMainLooper())
    gps.registerGnssStatusCallback(satelites, principal)
}

/**
 * Localização sem permissão: desbloqueia o celular, se preciso, e pede no diálogo do sistema (como a câmera, em
 * PedirCamera). Se o Android não mostrar mais o diálogo (negado de vez), abre as Configurações do app, com um tempo
 * sem a tela do alarme voltar por cima. Os sentidos releem a permissão a cada segundo.
 */
@Composable
private fun PedirLocalizacao() {
    val ctx = LocalContext.current
    val activity = LocalActivity.current
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!Poder.LOCALIZACAO.liberado(ctx)) {
            AlarmeService.darTempo(ctx)
            Poder.LOCALIZACAO.abrirConfiguracao(ctx)
        }
    }
    BotaoGrande("ALLOW LOCATION", { desbloquear(activity) { pedir.launch(Poder.LOCALIZACAO.pedido) } }, fundoClaro = true)
}

/** Abre a localização nas Configurações, numa tarefa separada (como [Poder.abrirConfiguracao]), com um tempo sem a tela do alarme voltar por cima. */
private fun abrirLocalizacao(ctx: Context) {
    AlarmeService.darTempo(ctx)
    runCatching { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Log.w(TAG, "Caminhada: não abriu as Configurações da localização", it) }
}

/**
 * Trilha fraca do anel inteiro + arco claro até o tempo andado mais o que está conferindo ([conferindo]) + arco
 * forte com o tempo andado ([progresso]), começando no topo (como o anel da câmera). O forte nunca desce.
 */
@Composable
private fun AnelDaCaminhada(progresso: Float, conferindo: Float, cor: Color, modifier: Modifier) {
    Canvas(modifier) {
        val espessura = 14.dp.toPx()
        val canto = Offset(espessura / 2, espessura / 2)
        val area = Size(size.width - espessura, size.height - espessura)
        drawArc(cor.copy(alpha = 0.25f), 0f, 360f, useCenter = false, topLeft = canto, size = area, style = Stroke(espessura))
        if (conferindo > progresso) {
            drawArc(
                VerdeConferindo.copy(alpha = 0.6f), -90f, 360f * conferindo, useCenter = false, topLeft = canto, size = area,
                style = Stroke(espessura, cap = StrokeCap.Round),
            )
        }
        drawArc(
            cor, -90f, 360f * progresso, useCenter = false, topLeft = canto, size = area,
            style = Stroke(espessura, cap = StrokeCap.Round),
        )
    }
}
