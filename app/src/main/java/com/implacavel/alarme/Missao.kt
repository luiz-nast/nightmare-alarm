// Telas da missão pra desligar o alarme, mostradas pela AlarmeActivity: 1) FALAR: dizer "stop" (a
// música para); entre as duas, a SAUDAÇÃO falada (Saudacao.kt); 2) OLHAR: olhar pra câmera de olhos abertos até fechar o anel, com o YouTube ao lado
// (PaginaYoutube.kt) e a câmera numa bolinha por cima dele. Logo depois de o celular
// reiniciar, antes do primeiro desbloqueio, vem antes a etapa DESBLOQUEAR. No despertador com conversa e
// caminhada, depois da saudação vem a CONVERSA com a IA (e a caminhada, em Caminhada.kt). Os tempos e as regras
// ficam em RegrasMissao.kt e RegrasConversa.kt; a vigia (bipes, zerar o anel, música de volta), no AlarmeService.
package com.implacavel.alarme

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil

/** Na etapa de falar a música alterna: alta pra acordar, baixa pra voz se destacar no microfone. */
private const val MUSICA_ALTA_MS = 5_000L
private const val MUSICA_BAIXA_MS = 4_000L
private const val VOLUME_ESCUTA = 0.25f

private const val PASSO_MS = 100L

/** De quanto em quanto tempo a etapa da câmera avisa o serviço que a pessoa está olhando. */
private const val AVISO_OLHANDO_MS = 1_000L

/** Sem quadro novo da câmera por esse tempo (câmera travada), a última leitura deixa de valer. Bem mais que o intervalo da análise (CameraOlhos). */
private const val QUADRO_VELHO_MS = 1_500L

/** Páginas da etapa da câmera: o YouTube à esquerda (o dedo desliza pra direita) e a câmera, onde começa. */
private const val PAGINA_YOUTUBE = 0
private const val PAGINA_CAMERA = 1

/**
 * O círculo da câmera com o anel, na página da câmera (deitado, menor, pra caber com os textos na
 * altura da tela), e a bolinha em que ele encolhe na página do YouTube.
 */
private val CIRCULO = 252.dp
private val BOLHA = 126.dp
private val MARGEM_BOLHA = 16.dp

/** Distância da bolinha até o pé da tela: acima da barra de baixo do YouTube. */
private val BOLHA_ACIMA_DO_FUNDO = 72.dp
private val LEGENDA = 160.dp
private val LEGENDA_ACIMA = 34.dp

/** O anel do tempo de conversa, na etapa da conversa com a IA: menor que o da câmera, pra caber a fala da IA. */
private val ANEL_CONVERSA = 190.dp

/**
 * Etapa 0, só logo depois de o celular reiniciar: antes do primeiro desbloqueio o reconhecimento de
 * voz do Google não roda (o serviço dele cai), então a missão espera o desbloqueio. O botão abre o
 * teclado do PIN por cima do alarme, com um tempo sem a tela do alarme voltar; a música continua.
 */
@Composable
fun EtapaDesbloquear() {
    val ctx = LocalContext.current
    val activity = LocalActivity.current
    AvisoComBotao("Unlock the phone\nto do the mission", "UNLOCK") {
        AlarmeService.darTempo(ctx)
        desbloquear(activity) {}
    }
}

/**
 * Etapa 1: ouvir "stop". Sem reconhecimento de voz disponível, aparece um botão no lugar; ele só
 * cala a música e passa pra etapa da câmera, que continua obrigatória. Se a pessoa já tinha olhado
 * um tanto (e saiu da tela), mostra quanto falta dos [metaMs]: o anel continua de onde parou.
 */
@Composable
fun EtapaFalar(metaMs: Long, onStop: () -> Unit) {
    val ctx = LocalContext.current
    val aoDizerStop by rememberUpdatedState(onStop)
    val olhado by AlarmeService.olhado.collectAsStateWithLifecycle()
    var ouvido by remember { mutableStateOf("") }
    var semVoz by remember { mutableStateOf(false) }
    var janelaDeEscuta by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            while (true) {
                janelaDeEscuta = false
                AlarmeService.volume(ctx, 1f)
                delay(MUSICA_ALTA_MS)
                janelaDeEscuta = true
                AlarmeService.volume(ctx, VOLUME_ESCUTA)
                delay(MUSICA_BAIXA_MS)
            }
        } finally {
            // Saiu da etapa ou fechou a tela: a música não pode ficar presa no volume baixo
            AlarmeService.volume(ctx, 1f)
        }
    }
    DisposableEffect(Unit) {
        val ouvinte = Ouvinte(ctx, "STOP", ::disseStop, onOuviu = { ouvido = it }, onAceito = { aoDizerStop() }, onIndisponivel = { semVoz = true })
        ouvinte.comecar()
        onDispose { ouvinte.parar() }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (janelaDeEscuta) "🎤 Speak now!" else "To turn it off, say", color = Color.White, fontSize = 20.sp)
        Text("STOP", color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Black)
        Text(
            if (ouvido.isBlank()) "" else "Heard: \"$ouvido\"",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        if (olhado > 0) {
            Spacer(Modifier.height(8.dp))
            Text("Ring saved: ${tempoRestante(metaMs - olhado)} left", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        if (semVoz) {
            Spacer(Modifier.height(12.dp))
            AvisoComBotao("Speech recognition unavailable", "STOP THE MUSIC", onClick = onStop)
        }
    }
}

/**
 * Verificação do meio da atividade ([AlarmeService.verificacao]): mostra a frase que a voz fala e, na vez
 * da pessoa, escuta em inglês até ela repetir ([repetiu]), mostrando o que ouve e pintando de verde as
 * palavras já ditas ([palavrasDitas]). Qualquer fala ouvida avisa o serviço, que conta o silêncio
 * (apitos e zerar); sem reconhecimento de voz, o serviço pula a verificação.
 */
@Composable
fun EtapaVerificacao(passo: Verificacao) {
    val ctx = LocalContext.current
    var ouvido by remember(passo.frase) { mutableStateOf("") }
    if (passo.suaVez) {
        DisposableEffect(passo.frase) {
            val ouvinte = Ouvinte(
                ctx, "frase", aceita = { repetiu(passo.frase, it) },
                onOuviu = {
                    ouvido = it
                    AlarmeService.ouviu(ctx)
                },
                onAceito = { AlarmeService.repetiu(ctx) },
                onIndisponivel = { AlarmeService.semVozNaVerificacao(ctx) },
                idioma = "en-US",
                juntar = true, // com pausa no meio da frase, as palavras já ditas continuam valendo
            )
            ouvinte.comecar()
            onDispose { ouvinte.parar() }
        }
    }
    val ultimaResposta by AlarmeService.ultimaResposta.collectAsStateWithLifecycle()
    var agora by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            agora = SystemClock.elapsedRealtime()
        }
    }
    val semResposta = agora - ultimaResposta
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val titulo = when {
            passo.frase.isNotEmpty() -> "Repeat after me"
            passo.numero < passo.total -> "Let's verify that you are awake"
            else -> "Thank you! Now the camera gets stricter"
        }
        Text(titulo, color = VinhoAlarme, fontSize = 20.sp, textAlign = TextAlign.Center)
        if (passo.frase.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val ditas = palavrasDitas(passo.frase, ouvido)
            // Na vez da pessoa, um cursor vermelho piscando depois da última palavra certa (antes da
            // primeira, se nenhuma): de onde continuar. Apagado, fica transparente, pra frase não pular
            val depoisDe = if (passo.suaVez && !ditas.all { it.second }) ditas.indexOfLast { it.second } else null
            val cursor = AnnotatedString("|", SpanStyle(color = if (agora / 500 % 2 == 0L) VermelhoAviso else Color.Transparent, fontWeight = FontWeight.Black))
            val frase = buildAnnotatedString {
                append("\u201C")
                if (depoisDe == -1) append(cursor)
                ditas.forEachIndexed { i, (palavra, dita) ->
                    if (i > 0) append(" ")
                    if (dita) withStyle(SpanStyle(color = VerdeVez)) { append(palavra) } else append(palavra)
                    if (i == depoisDe) append(cursor)
                }
                append("\u201D")
            }
            Text(frase, color = VinhoAlarme, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            // O que o reconhecedor está ouvindo, em tempo real (as últimas palavras); duas linhas sempre, pra tela não pular
            Text(
                if (passo.suaVez && ouvido.isNotBlank()) "Heard: \"${ouvido.split(" ").takeLast(12).joinToString(" ")}\"" else "",
                color = VinhoAlarme.copy(alpha = 0.7f),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                minLines = 2,
                maxLines = 2,
            )
            Text("Phrase ${passo.numero} of ${passo.total}", color = VinhoAlarme.copy(alpha = 0.7f), fontSize = 16.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(if (passo.suaVez) "🎤 Your turn" else "", color = VerdeVez, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        // Como na câmera: sem resposta há um tempo, quanto falta pra zerar
        val zeraEm = if (passo.suaVez && semResposta >= SILENCIO_APITO_MS) ceil((SILENCIO_ZERA_MS - semResposta) / 1000.0).toInt().coerceAtLeast(0) else null
        Text(zeraEm?.let { "$it s to reset" } ?: "", color = VermelhoAviso, fontSize = 28.sp, fontWeight = FontWeight.Black)
    }
}

/**
 * Entre o STOP e a câmera, enquanto a voz do celular dá bom dia ([AlarmeService.saudando]): a câmera
 * começa uns segundos depois que ela termina. [hora] escolhe entre bom dia, boa tarde e boa noite. No
 * despertador "conversa + caminhada" ([caminhada]), a tela fica aqui até a IA responder (uns segundos depois do bom dia).
 */
@Composable
fun EtapaSaudacao(hora: Int, caminhada: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${saudacao(hora)}!", color = VinhoAlarme, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("It's time for the activity check", color = VinhoAlarme, fontSize = 20.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(if (caminhada) "Preparing the morning…" else "The camera starts soon", color = VinhoAlarme.copy(alpha = 0.7f), fontSize = 16.sp)
    }
}

/**
 * Conversa da manhã com a IA ([AlarmeService.conversa]): mostra a fala da IA e o que a pessoa disse nesta vez
 * ("Ouvi: …"). Quem ouve é o serviço ([Escuta]): a vez de cada um, o relógio da conversa e os bipes do silêncio são
 * de lá. O anel enche com o tempo de conversa ("3:12 de 10:00"); em silêncio na sua vez, a contagem pro bipe (a
 * partir de [AlarmeService.ultimaFala]).
 */
@Composable
fun EtapaConversa(conversa: Conversa) {
    val ouvido = conversa.ouvido
    val ultimaFala by AlarmeService.ultimaFala.collectAsStateWithLifecycle()
    var agora by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            agora = SystemClock.elapsedRealtime()
        }
    }
    val calado = agora - ultimaFala
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(ANEL_CONVERSA), contentAlignment = Alignment.Center) {
            AnelProgresso((conversa.conversadoMs.toFloat() / conversa.metaMs.coerceAtLeast(1)).coerceIn(0f, 1f), VerdeVez, Modifier.fillMaxSize())
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tempoAndado(conversa.conversadoMs), color = VinhoAlarme, fontSize = 44.sp, fontWeight = FontWeight.Black)
                Text("of ${tempoAndado(conversa.metaMs)} of talk", color = VinhoAlarme.copy(alpha = 0.7f), fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            conversa.falaDaIa.ifEmpty { "…" },
            color = VinhoAlarme,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        // Duas linhas sempre, pra tela não pular: o que o reconhecedor está ouvindo (as últimas palavras)
        Text(
            if (ouvido.isNotBlank()) "Heard: \"${ouvido.split(" ").takeLast(12).joinToString(" ")}\"" else "",
            color = VinhoAlarme.copy(alpha = 0.7f),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 2,
        )
        Spacer(Modifier.height(12.dp))
        val vez = when {
            conversa.suaVez && conversa.perguntandoSeContinua -> "🎤 Your turn: yes or no?"
            conversa.suaVez -> "🎤 Your turn"
            conversa.pensando && conversa.procurando.isNotEmpty() -> "🔎 Looking up: ${conversa.procurando}"
            conversa.pensando -> "Thinking…"
            else -> ""
        }
        Text(vez, color = VerdeVez, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        // Calado na sua vez: quanto falta pro bipe (na pergunta, pra valer como não); bipando, o jeito de parar
        val faltam = ceil((SILENCIO_CONVERSA_MS - calado) / 1000.0).toInt().coerceAtLeast(0)
        val aviso = when {
            !conversa.suaVez || calado < REACAO_CONVERSA_MS -> ""
            conversa.perguntandoSeContinua -> "$faltam s to answer"
            calado < SILENCIO_CONVERSA_MS -> "Beep in $faltam s"
            else -> "🔊 Talk and the beep stops"
        }
        Text(aviso, color = VermelhoAviso, fontSize = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
    }
}

/** Missão cumprida: enquanto o app comemora (bipe leve, a fala de parabéns e o estouro) antes de fechar. */
@Composable
fun EtapaFim() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Congratulations!", color = VinhoAlarme, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("The activity is done", color = VinhoAlarme, fontSize = 20.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text("Have a good time getting back to your activities", color = VinhoAlarme.copy(alpha = 0.7f), fontSize = 16.sp)
    }
}

/**
 * Etapa 2: câmera num círculo com um anel que enche enquanto a pessoa olha de olhos abertos, até
 * somar [metaMs]; antes da verificação do meio (no serviço), para em [verificacaoMs]. [onOlhando] recebe o tempo já
 * somado a cada [AVISO_OLHANDO_MS] enquanto ela olha
 * e no instante em que ela para (mantém a música calada; o serviço guarda o tempo, em
 * [AlarmeService.olhado]). Sem olhar, a tela mostra a contagem pra zerar, a partir do último sinal
 * recebido pelo serviço ([AlarmeService.ultimoOlhar]); bipes e zerar são da vigia de lá.
 *
 * Deslizando pra direita, a página do [youtube]: a câmera vira uma bolinha no canto (CameraMovel) e
 * continua valendo. Lá o deslize de lado fica desligado (o dedo é dos vídeos: um Short puxado pra cima
 * um pouco torto passava de página); volta-se tocando na bolinha ou no voltar. [pagina] é a moldura das
 * telas do alarme (hora em cima, a etapa embaixo), que rola com [rolagem].
 */
@Composable
fun EtapaOlhar(
    metaMs: Long,
    verificacaoMs: Long,
    onDefeito: () -> Unit,
    youtube: YoutubeDaMissao,
    pagina: @Composable (conteudo: @Composable () -> Unit) -> Unit,
    rolagem: ScrollState,
    onOlhando: (olhadoMs: Long) -> Unit,
    onConcluiu: () -> Unit,
) {
    val ctx = LocalContext.current
    // Tirar a permissão da câmera não é saída: sem ela, o único caminho é liberar de novo
    var respostas by remember { mutableIntStateOf(0) }
    if (!lidoAoVoltar(respostas) { Poder.CAMERA.liberado(ctx) }) {
        pagina { PedirCamera(onLiberada = { respostas++ }) }
        return
    }
    var leitura by remember { mutableStateOf(Leitura.SEM_ROSTO) }
    var aberto by remember { mutableStateOf<Float?>(null) } // a certeza de olho aberto do último quadro
    var quandoLeu by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    if (leitura == Leitura.SEM_CAMERA) {
        // A câmera deu defeito (a permissão existe): sem como conferir os olhos, desliga no botão
        pagina { AvisoComBotao("The camera is not working.", "TURN OFF", fundoClaro = true, onClick = onDefeito) }
        return
    }
    val avisarOlhando by rememberUpdatedState(onOlhando)
    val concluir by rememberUpdatedState(onConcluiu)
    // A meta pode subir no meio: alarme de verdade disparando durante o teste (EmAndamento.juntar)
    val meta by rememberUpdatedState(metaMs)
    val verificacao by rememberUpdatedState(verificacaoMs)
    // Continua do que o serviço guardou: voltou depois de sair da tela, ou a tela foi recriada no meio
    // (ex.: o tamanho da fonte do sistema mudou). Lá ele só zera nos 20 s sem olhar
    var olhadoMs by remember { mutableLongStateOf(AlarmeService.olhado.value) }
    var zeraEm by remember { mutableStateOf<Int?>(null) }
    // Depois da verificação do meio, a câmera fica mais exigente com os olhos
    val metadeVerificada by AlarmeService.metadeVerificada.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        var antes = SystemClock.elapsedRealtime()
        var ultimoAviso = 0L
        var olhavaAntes = false
        while (true) {
            delay(PASSO_MS)
            val agora = SystemClock.elapsedRealtime()
            // Câmera travada vale como sem rosto: imagem velha não enche o anel
            if (agora - quandoLeu > QUADRO_VELHO_MS) {
                leitura = Leitura.SEM_ROSTO
                aberto = null
            }
            // Vale o quadro atual: tirou o rosto ou fechou os olhos, o anel para na hora
            val olhando = leitura == Leitura.OLHANDO
            if (olhando != olhavaAntes) {
                val olho = aberto?.let { ", olho ${porcento(it)}%" }.orEmpty()
                Log.i(TAG, "Câmera: ${if (olhando) "olhando" else "parou de olhar ($leitura)"}$olho, anel ${olhadoMs * 100 / meta}%")
            }
            // Antes da verificação do meio (no serviço), o anel para no fim do modo fácil
            val limite = if (AlarmeService.metadeVerificada.value) meta else verificacao
            olhadoMs = minOf(avancarOlhar(olhadoMs, olhando, passoMs = agora - antes), maxOf(limite, olhadoMs))
            antes = agora
            // A cada segundo olhando, e no instante em que parou: a contagem pra zerar começa daí
            if (olhando && agora - ultimoAviso >= AVISO_OLHANDO_MS || olhavaAntes && !olhando) {
                avisarOlhando(olhadoMs)
                ultimoAviso = agora
            }
            olhavaAntes = olhando
            zeraEm = if (olhando) null else segundosParaZerar(agora - AlarmeService.ultimoOlhar.value)
            if (olhadoMs >= meta) {
                Log.i(TAG, "Missão: anel completo, alarme desligado")
                concluir()
                break
            }
        }
    }

    val (corAlvo, mensagem) = retorno(leitura)
    val cor by animateColorAsState(corAlvo, label = "cor da leitura")
    val paginas = rememberPagerState(initialPage = PAGINA_CAMERA) { 2 }
    val configuracao = LocalConfiguration.current
    val circulo = if (configuracao.screenWidthDp > configuracao.screenHeightDp) {
        (configuracao.screenHeightDp - 212).dp.coerceIn(150.dp, CIRCULO)
    } else {
        CIRCULO
    }
    val escopo = rememberCoroutineScope()
    var lugarDoCirculo by remember { mutableStateOf<Offset?>(null) }
    // O YouTube só carrega quando a pessoa começa a ir pra página dele (e de novo, se a página dele caiu)
    LaunchedEffect(Unit) {
        snapshotFlow { paginas.currentPage + paginas.currentPageOffsetFraction < PAGINA_CAMERA - 0.05f }
            .collect { if (it) youtube.abrir() }
    }
    // No YouTube, voltar volta a página dele; sem mais pra onde, volta pra câmera
    BackHandler(enabled = paginas.currentPage == PAGINA_YOUTUBE) {
        if (!youtube.voltar()) escopo.launch { paginas.animateScrollToPage(PAGINA_CAMERA) }
    }
    Box(Modifier.fillMaxSize()) {
        // Desliga só quando a página do YouTube firma: o deslize que leva até ela continua inteiro
        HorizontalPager(paginas, Modifier.fillMaxSize(), beyondViewportPageCount = 1, userScrollEnabled = paginas.settledPage != PAGINA_YOUTUBE) { p ->
            if (p == PAGINA_YOUTUBE) {
                PaginaYoutube(youtube)
            } else {
                pagina {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // O lugar do círculo: a câmera é desenhada por cima (CameraMovel), pra poder virar a bolinha
                        Spacer(Modifier.size(circulo).onGloballyPositioned { lugarDoCirculo = it.positionInRoot() })
                        Spacer(Modifier.height(16.dp))
                        Text(mensagem, color = cor, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        // A certeza do detector de que os olhos estão abertos (100% = certeza que sim), e o mínimo pra contar
                        val minimo = porcento(minimoOlhoAberto(metadeVerificada))
                        Text("Eye open: ${aberto?.let { "${porcento(it)}%" } ?: "—"} · minimum $minimo%", color = cor, fontSize = 16.sp)
                        Text("${tempoRestante(metaMs - olhadoMs)} more with your eyes open", color = VinhoAlarme, fontSize = 16.sp)
                        // Linha sempre presente (vazia enquanto olha), pra tela não pular quando a contagem aparece
                        Text(zeraEm?.let { "$it s to reset" } ?: "", color = VermelhoAviso, fontSize = 28.sp, fontWeight = FontWeight.Black)
                        Text("👉 Swipe right: YouTube", color = VinhoAlarme.copy(alpha = 0.7f), fontSize = 15.sp)
                    }
                }
            }
        }
        CameraMovel(
            paginas,
            rolagem,
            circulo,
            lugarDoCirculo = { lugarDoCirculo },
            progresso = olhadoMs.toFloat() / metaMs,
            cor = cor,
            legenda = zeraEm?.let { "$it s to reset" } ?: "${tempoRestante(metaMs - olhadoMs)} left",
            alerta = zeraEm != null,
            exigente = metadeVerificada,
            onLeitura = { nova, olho ->
                leitura = nova
                aberto = olho
                quandoLeu = SystemClock.elapsedRealtime()
            },
        )
    }
}

/**
 * A câmera com o anel, desenhada por cima das páginas. Na página da câmera fica no [lugarDoCirculo];
 * indo pro YouTube, segue o dedo e encolhe até a bolinha no canto de baixo à direita, como a da
 * chamada de vídeo do WhatsApp. É sempre a mesma câmera: trocar de página não para a detecção.
 * Na bolinha, dá pra arrastá-la, e um toque volta pra câmera. O círculo grande cobre o meio da tela,
 * onde o dedo costuma deslizar: arrastar nele pro lado troca de página, e pra cima ou pra baixo rola.
 */
@Composable
private fun CameraMovel(
    paginas: PagerState,
    rolagem: ScrollState,
    circulo: Dp,
    lugarDoCirculo: () -> Offset?,
    progresso: Float,
    cor: Color,
    legenda: String,
    alerta: Boolean,
    exigente: Boolean,
    onLeitura: (Leitura, olhoAberto: Float?) -> Unit,
) {
    val escopo = rememberCoroutineScope()
    val densidade = LocalDensity.current
    val direcao = LocalLayoutDirection.current
    val barras = WindowInsets.safeDrawing
    var tela by remember { mutableStateOf(IntSize.Zero) }
    var origem by remember { mutableStateOf(Offset.Zero) }
    var arrasto by remember { mutableStateOf(Offset.Zero) } // quanto a pessoa moveu a bolinha do canto

    // Tudo aqui é lido só na hora de posicionar e desenhar: deslizar não recompõe a tela a cada quadro
    // 0 = página da câmera, 1 = página do YouTube
    fun naBolha() = (PAGINA_CAMERA - paginas.currentPage - paginas.currentPageOffsetFraction).coerceIn(0f, 1f)
    val escalaBolha = BOLHA / circulo
    fun escala() = 1f - (1f - escalaBolha) * naBolha()
    fun lado() = with(densidade) { BOLHA.toPx() }
    // Dentro da tela, fora das barras do sistema, com lugar pra legenda em cima. Antes de a tela ser
    // medida (tamanho zero), fica no canto de cima
    fun limitar(canto: Offset) = with(densidade) {
        val margem = MARGEM_BOLHA.toPx()
        val minimo = Offset(barras.getLeft(this, direcao) + margem, barras.getTop(this) + margem + LEGENDA_ACIMA.toPx())
        val maximo = Offset(
            tela.width - barras.getRight(this, direcao) - lado() - margem,
            tela.height - barras.getBottom(this) - lado() - margem,
        )
        Offset(canto.x.coerceIn(minimo.x, maxOf(minimo.x, maximo.x)), canto.y.coerceIn(minimo.y, maxOf(minimo.y, maximo.y)))
    }
    fun cantoPadrao() = with(densidade) {
        Offset(
            tela.width - barras.getRight(this, direcao) - MARGEM_BOLHA.toPx() - lado(),
            tela.height - barras.getBottom(this) - BOLHA_ACIMA_DO_FUNDO.toPx() - lado(),
        )
    }
    fun cantoDaBolha() = limitar(cantoPadrao() + arrasto)
    fun assentar() = escopo.launch { paginas.animateScrollToPage(if (naBolha() > 0.25f) PAGINA_YOUTUBE else PAGINA_CAMERA) }

    Box(
        Modifier.fillMaxSize().onGloballyPositioned {
            tela = it.size
            origem = it.positionInRoot()
        },
    ) {
        Box(
            Modifier
                .offset { lerp((lugarDoCirculo() ?: origem) - origem, cantoDaBolha(), naBolha()).round() }
                .size(circulo)
                .graphicsLayer {
                    scaleX = escala()
                    scaleY = escala()
                    transformOrigin = TransformOrigin(0f, 0f)
                    alpha = if (lugarDoCirculo() == null) 0f else 1f // até a página da câmera dizer onde é o círculo
                    // Só o círculo pega o toque: os cantos do quadrado são do YouTube
                    shape = CircleShape
                    clip = true
                }
                .pointerInput(Unit) {
                    detectTapGestures { if (naBolha() > 0.99f) escopo.launch { paginas.animateScrollToPage(PAGINA_CAMERA) } }
                }
                .pointerInput(Unit) {
                    var moverBolha = false
                    var deLado: Boolean? = null // no círculo grande: o primeiro movimento decide se troca de página ou rola
                    var percorrido = 0f
                    fun soltar() {
                        when {
                            !moverBolha -> assentar()
                            // Na bolinha encolhida o movimento conta em dobro: um toque tremido vira arrasto
                            percorrido < viewConfiguration.touchSlop -> escopo.launch { paginas.animateScrollToPage(PAGINA_CAMERA) }
                        }
                    }
                    detectDragGestures(
                        onDragStart = {
                            moverBolha = naBolha() > 0.99f
                            deLado = null
                            percorrido = 0f
                        },
                        onDragEnd = ::soltar,
                        onDragCancel = ::soltar,
                    ) { mudanca, delta ->
                        mudanca.consume()
                        val naTela = delta * escala() // o arrasto vem no tamanho do círculo, que encolhe
                        percorrido += naTela.getDistance()
                        if (moverBolha) {
                            arrasto = limitar(cantoPadrao() + arrasto + naTela) - cantoPadrao()
                        } else {
                            val lado = deLado ?: (abs(naTela.x) >= abs(naTela.y)).also { deLado = it }
                            if (lado) paginas.dispatchRawDelta(-naTela.x) else rolagem.dispatchRawDelta(-naTela.y)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            CameraOlhos(Modifier.size(circulo - 32.dp).clip(CircleShape), exigente, onLeitura)
            AnelProgresso(progresso, cor, Modifier.fillMaxSize())
        }
        // Em cima da bolinha, o tempo que falta ou, em vermelho, a contagem pra zerar (só na página do YouTube)
        Box(
            Modifier
                .offset {
                    val canto = cantoDaBolha()
                    IntOffset((canto.x + lado() / 2).toInt() - LEGENDA.roundToPx() / 2, (canto.y - LEGENDA_ACIMA.toPx()).toInt())
                }
                .width(LEGENDA)
                .graphicsLayer { alpha = naBolha() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                legenda,
                Modifier
                    .background(if (alerta) VermelhoAviso else Color.Black.copy(alpha = 0.65f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/**
 * Câmera sem permissão: desbloqueia o celular, se preciso, e pede no diálogo do sistema. Se o
 * Android não mostrar mais o diálogo (negado de vez), abre as Configurações, com um tempo sem a tela
 * do alarme voltar por cima (a música continua). A etapa da câmera relê a permissão quando a tela volta
 * e em [onLiberada]: com a permissão já dada (ex.: nas Configurações em tela dividida), não há diálogo nem volta.
 */
@Composable
private fun PedirCamera(onLiberada: () -> Unit) {
    val ctx = LocalContext.current
    val activity = LocalActivity.current
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            onLiberada()
        } else {
            AlarmeService.darTempo(ctx)
            Poder.CAMERA.abrirConfiguracao(ctx)
        }
    }
    AvisoComBotao("The camera has no permission.\nAllow it to turn off the alarm.", "ALLOW CAMERA", fundoClaro = true) {
        desbloquear(activity) { pedir.launch(Manifest.permission.CAMERA) }
    }
}

/** Aviso curto com um botão grande embaixo: os pedidos e as saídas das etapas da missão. */
@Composable
private fun AvisoComBotao(aviso: String, botao: String, fundoClaro: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            aviso,
            color = if (fundoClaro) VinhoAlarme else Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        BotaoGrande(botao, onClick, fundoClaro)
    }
}

/** Com o celular bloqueado, pede o desbloqueio (PIN, digital) antes de [depois]; a tela do alarme continua por cima. */
fun desbloquear(activity: Activity?, depois: () -> Unit) {
    val bloqueio = activity?.getSystemService(KeyguardManager::class.java)
    if (activity == null || bloqueio?.isKeyguardLocked != true) return depois()
    bloqueio.requestDismissKeyguard(
        activity,
        object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = depois()
        },
    )
}

/** Trilha fraca do anel inteiro + arco forte com o progresso, começando no topo. */
@Composable
private fun AnelProgresso(progresso: Float, cor: Color, modifier: Modifier) {
    Canvas(modifier) {
        val espessura = 12.dp.toPx()
        val canto = Offset(espessura / 2, espessura / 2)
        val area = Size(size.width - espessura, size.height - espessura)
        drawArc(cor.copy(alpha = 0.25f), 0f, 360f, useCenter = false, topLeft = canto, size = area, style = Stroke(espessura))
        drawArc(
            cor, -90f, 360f * progresso, useCenter = false, topLeft = canto, size = area,
            style = Stroke(espessura, cap = StrokeCap.Round),
        )
    }
}


private val VermelhoAviso = Color(0xFFC62828)
private val VerdeVez = Color(0xFF2E7D32)

/** O retorno visual de cada leitura da câmera: cor (verde ou vermelho) e mensagem. */
private fun retorno(leitura: Leitura): Pair<Color, String> = when (leitura) {
    Leitura.OLHANDO -> Color(0xFF2E7D32) to "That's it! Keep looking"
    Leitura.OLHOS_FECHADOS -> VermelhoAviso to "Open those eyes!"
    Leitura.SEM_ROSTO, Leitura.SEM_CAMERA -> VermelhoAviso to "Where are you? Bring your face closer"
}
