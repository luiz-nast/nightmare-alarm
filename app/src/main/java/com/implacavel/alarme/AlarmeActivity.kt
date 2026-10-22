package com.implacavel.alarme

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/**
 * Tela do alarme tocando. Aberta pela notificação em tela cheia do [AlarmeService]; aparece por
 * cima da tela de bloqueio, acende a tela e fecha sozinha quando o alarme para.
 * Com a missão ligada, mostra as etapas de Missao.kt (e a da caminhada, de Caminhada.kt); sem ela, só o botão DESLIGAR.
 */
private const val TRANSPARENTE = android.graphics.Color.TRANSPARENT

class AlarmeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acordarTela()
        // Ícones claros na barra de cima, pro fundo vermelho; a TelaAlarme troca pra escuros na etapa da câmera
        enableEdgeToEdge(SystemBarStyle.dark(TRANSPARENTE), SystemBarStyle.dark(TRANSPARENTE))
        setContent {
            TemaImplacavel {
                val alarme by AlarmeService.tocando.collectAsStateWithLifecycle()
                BackHandler { /* o botão voltar não desliga o alarme */ }
                LaunchedEffect(alarme) { if (alarme == null) finish() }
                alarme?.let { TelaAlarme(it) }
            }
        }
    }

    // Abaixar ou silenciar pelos botões laterais não cala a música. Depois do STOP (câmera, YouTube) os
    // botões voltam a valer: o volume do vídeo é da pessoa, e as falas e bipes do alarme sobem o próprio volume
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        !AlarmeService.silenciado.value && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) ||
            super.onKeyDown(keyCode, event)

    // Fechar esta tela no meio (Home, arrastar o app, apagar a tela) faz o serviço reabri-la em segundos
    override fun onStart() {
        super.onStart()
        AlarmeService.tela(this, aberta = true)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) AlarmeService.tela(this, aberta = false)
    }

    private fun acordarTela() {
        // Aparecer por cima da tela de bloqueio e acender a tela: do Android 8.1 em diante isso vem do
        // manifesto (showWhenLocked e turnScreenOn); no 8.0, destas flags de janela
        if (Build.VERSION.SDK_INT < 27) {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun TelaAlarme(alarme: Alarme) {
    val ctx = LocalContext.current
    // A etapa vem do serviço (música calada = já disse STOP, falta a câmera). Assim ela sobrevive à
    // tela ser fechada ou recriada, e a vigia do serviço, ao religar a música, volta pra etapa de falar.
    val silenciado by AlarmeService.silenciado.collectAsStateWithLifecycle()
    val saudando by AlarmeService.saudando.collectAsStateWithLifecycle()
    val verificacao by AlarmeService.verificacao.collectAsStateWithLifecycle()
    val conversa by AlarmeService.conversa.collectAsStateWithLifecycle()
    val caminhada by AlarmeService.caminhada.collectAsStateWithLifecycle()
    val metadeVerificada by AlarmeService.metadeVerificada.collectAsStateWithLifecycle()
    // Os tempos da foto do alarme, não os ajustes de agora: mexer nos ajustes com o alarme tocando não encurta a missão
    val tempos by AlarmeService.temposDoAlarme.collectAsStateWithLifecycle()
    val ondasMudas by AlarmeService.ondasMudas.collectAsStateWithLifecycle()
    val miniTarefa by AlarmeService.miniTarefa.collectAsStateWithLifecycle()
    val metaCamera = metaOlhar(alarme, tempos)
    val comemorando by AlarmeService.comemorando.collectAsStateWithLifecycle()
    val comCaminhada by AlarmeService.comCaminhada.collectAsStateWithLifecycle()
    val desbloqueado = celularDesbloqueado()
    val agora = agoraACada(1_000)
    // O GPS e o acelerômetro da caminhada ligam no STOP: durante a conversa o GPS já acerta a mira
    val sentidos = rememberSentidosDaCaminhada(ligado = comCaminhada && silenciado)

    // A tela do alarme fica no brilho máximo do começo ao fim; na etapa da câmera, também clara, pra iluminar o rosto no escuro
    BrilhoMaximo()
    val claro = alarme.missao && silenciado
    BarrasDoSistema(iconesEscuros = claro)
    // A etapa da caminhada (com as ondas do prazo pra sair) em azul; o resto do dia claro em creme; a música, vermelho
    val naCaminhada = claro && caminhada != null
    val corTexto = when {
        naCaminhada -> AzulCaminhada
        claro -> VinhoAlarme
        else -> Color.White
    }
    val fundo = when {
        naCaminhada -> FundoCaminhada
        claro -> listOf(Color(0xFFFFFBF2), Color(0xFFFFE0B2))
        else -> listOf(VermelhoAlarme, VinhoAlarme, Color.Black)
    }

    // Lido só ao desenhar o ícone: o pulso não recompõe a tela inteira a cada quadro. Na etapa da
    // câmera (até 40 min) o ícone fica parado: animação sem fim redesenha a tela a cada quadro e esquenta
    val pulso: State<Float> = if (claro) {
        remember { mutableFloatStateOf(1f) }
    } else {
        rememberInfiniteTransition(label = "pulso").animateFloat(
            initialValue = 1f,
            targetValue = 1.18f,
            animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
            label = "escala",
        )
    }
    // Uma rolagem só pras páginas do alarme: o círculo da câmera, por cima da página, também rola ela
    val rolagem = rememberScrollState()

    // O YouTube da etapa da câmera vive enquanto esta tela estiver aberta; fora da etapa, pausado
    val youtube = rememberYoutubeDaMissao()
    val etapaOlhar = alarme.missao && desbloqueado && silenciado && !saudando && !comemorando && verificacao == null && conversa == null && caminhada == null
    LaunchedEffect(etapaOlhar) { if (etapaOlhar) youtube.retomar() else youtube.pausar() }

    // Num alarme de teste, os botões de fase ("Back", "Cancel test", "Forward") no pé das telas depois do bom dia. O
    // prazo pra sair anda: a tela já recompõe a cada segundo (por [agora]), e o relógio é relido aqui
    val fase = if (alarme.deTeste && claro && !saudando && !comemorando) {
        faseDoTeste(
            conversa = conversa != null, caminhada = caminhada != null,
            noPrazo = caminhada?.let { SystemClock.elapsedRealtime() < it.prazoAte } == true,
            verificacao = verificacao != null, metadeVerificada = metadeVerificada, naCamera = !comCaminhada,
        )
    } else {
        null
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(fundo))) {
        val alturaMinima = maxHeight
        val deitado = maxWidth > maxHeight
        val rodape: (@Composable () -> Unit)? = fase?.let { f ->
            { BotoesDoTeste(f, comFrases = tempos.frases > 0, corTexto, onIr = { AlarmeService.irPraFase(ctx, it) }, onCancelar = { AlarmeService.cancelarTeste(ctx) }) }
        }
        val pagina: @Composable (@Composable () -> Unit) -> Unit = { conteudo ->
            PaginaAlarme(alturaMinima, deitado, rolagem, corTexto, pulso, hhmm(agora.toLocalTime()), alarme.nome, rodape, conteudo)
        }
        when {
            comemorando -> pagina { EtapaFim() }
            !alarme.missao -> pagina { BotaoGrande("TURN OFF", { AlarmeService.desligar(ctx) }) }
            // Logo depois de o celular reiniciar, a voz do Google só roda depois do primeiro desbloqueio
            !desbloqueado -> pagina { EtapaDesbloquear() }
            !silenciado -> pagina { EtapaFalar(metaCamera, onStop = { AlarmeService.silenciar(ctx) }) }
            saudando -> pagina { EtapaSaudacao(agora.hour, caminhada = comCaminhada) }
            verificacao != null -> verificacao?.let { passo -> pagina { EtapaVerificacao(passo) } }
            conversa != null -> conversa?.let { c -> pagina { EtapaConversa(c) } }
            caminhada != null -> caminhada?.let { c ->
                pagina {
                    // Lido aqui dentro: só a etapa usa. O tempo andado em c vem atualizado do serviço (tela recriada no meio)
                    val ultimoPasso by AlarmeService.ultimoPasso.collectAsStateWithLifecycle()
                    EtapaCaminhada(
                        sentidos = sentidos,
                        andadoInicialMs = c.andadoMs,
                        metaMs = c.metaMs,
                        prazoAte = c.prazoAte,
                        ultimoPasso = ultimoPasso,
                        onAndando = { AlarmeService.andando(ctx, it) },
                        onDefeito = { AlarmeService.missaoCumprida(ctx, comemorar = false) },
                        onConcluiu = { AlarmeService.missaoCumprida(ctx) },
                        ondasMudas = ondasMudas,
                        onOndas = { AlarmeService.ondas(ctx, ligadas = it) },
                        miniTarefa = miniTarefa,
                        onMiniTarefaFeita = { AlarmeService.miniTarefaFeita(ctx) },
                    )
                }
            }
            else -> EtapaOlhar(
                metaMs = metaCamera,
                verificacaoMs = verificacaoEm(alarme, tempos),
                onDefeito = { AlarmeService.missaoCumprida(ctx, comemorar = false) },
                youtube = youtube,
                pagina = pagina,
                rolagem = rolagem,
                onOlhando = { AlarmeService.olhando(ctx, it) },
                onConcluiu = { AlarmeService.missaoCumprida(ctx) },
            )
        }
    }
}

/**
 * Moldura das telas do alarme: ícone pulsando, hora e nome, [conteudo] (a etapa) e, nos testes, o [rodape] (os
 * botões de fase). Em pé, um embaixo do outro, numa coluna rolável com altura mínima da tela (centraliza quando cabe e
 * rola em celulares pequenos); [deitado], lado a lado, com a etapa rolando sozinha na metade dela.
 */
@Composable
private fun PaginaAlarme(
    alturaMinima: Dp,
    deitado: Boolean,
    rolagem: ScrollState,
    corTexto: Color,
    pulso: State<Float>,
    hora: String,
    nome: String,
    rodape: (@Composable () -> Unit)?,
    conteudo: @Composable () -> Unit,
) {
    val cabecalho = @Composable {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painterResource(R.drawable.ic_alarme),
                contentDescription = null,
                tint = corTexto,
                modifier = Modifier.size(56.dp).graphicsLayer {
                    scaleX = pulso.value
                    scaleY = pulso.value
                },
            )
            Text(hora, color = corTexto, fontSize = 72.sp, fontWeight = FontWeight.Bold)
            Text(nome, color = corTexto, fontSize = 22.sp, textAlign = TextAlign.Center)
        }
    }
    // A etapa muda de lugar ao girar sem recomeçar (o microfone e a câmera seguem ligados)
    val etapa = remember { movableContentOf { corpo: @Composable () -> Unit -> corpo() } }
    if (deitado) {
        Row(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                cabecalho()
                if (rodape != null) {
                    Spacer(Modifier.height(16.dp))
                    rodape()
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Column(Modifier.verticalScroll(rolagem), horizontalAlignment = Alignment.CenterHorizontally) { etapa(conteudo) }
            }
        }
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rolagem)
            .heightIn(min = alturaMinima)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            cabecalho()
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            etapa(conteudo)
            Spacer(Modifier.height(24.dp))
        }
        if (rodape != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                rodape()
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Os botões de um alarme de teste, no pé da tela: "Back" e "Next" andam pelas fases do teste ([FaseDoTeste]:
 * conversa, prazo e caminhada; ou câmera, frases (se há alguma, [comFrases]) e câmera exigente), e "Cancel" acaba o
 * teste, com uma confirmação (o segundo toque em 4 s). Em cima, as fases, com a atual marcada. Textos curtos e pouca
 * margem interna: três botões numa tela de 384 dp.
 */
@Composable
private fun BotoesDoTeste(fase: FaseDoTeste, comFrases: Boolean, cor: Color, onIr: (FaseDoTeste) -> Unit, onCancelar: () -> Unit) {
    var confirmando by remember { mutableStateOf(false) }
    LaunchedEffect(confirmando) {
        if (confirmando) {
            delay(4_000)
            confirmando = false
        }
    }
    val anterior = fase.anterior(comFrases)
    val proxima = fase.proxima(comFrases)
    val margem = PaddingValues(horizontal = 6.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Test · " + fase.fases(comFrases).joinToString("  ›  ") { if (it == fase) "● ${it.rotulo}" else it.rotulo },
            color = cor.copy(alpha = 0.8f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val borda = BorderStroke(1.dp, cor.copy(alpha = 0.6f))
            OutlinedButton(
                onClick = { anterior?.let(onIr) },
                enabled = anterior != null,
                modifier = Modifier.weight(1f),
                contentPadding = margem,
                border = borda,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = cor, disabledContentColor = cor.copy(alpha = 0.3f)),
            ) { Text("◀ Back", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp) }
            Button(
                onClick = { if (confirmando) onCancelar() else confirmando = true },
                modifier = Modifier.weight(1.3f),
                contentPadding = margem,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (confirmando) VermelhoAlarme else cor.copy(alpha = 0.12f),
                    contentColor = if (confirmando) Color.White else cor,
                ),
            ) { Text(if (confirmando) "Tap again to end" else "✕ Cancel test", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp) }
            OutlinedButton(
                onClick = { proxima?.let(onIr) },
                enabled = proxima != null,
                modifier = Modifier.weight(1f),
                contentPadding = margem,
                border = borda,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = cor, disabledContentColor = cor.copy(alpha = 0.3f)),
            ) { Text("Next ▶", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp) }
        }
    }
}

/** Botão grande das telas do alarme: branco no fundo vermelho, vermelho no fundo claro. */
@Composable
fun BotaoGrande(texto: String, onClick: () -> Unit, fundoClaro: Boolean = false) {
    val (fundo, letra) = if (fundoClaro) VermelhoAlarme to Color.White else Color.White to VermelhoAlarme
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(76.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = fundo, contentColor = letra),
    ) { Text(texto, fontSize = 24.sp, fontWeight = FontWeight.Black) }
}

/** Se o celular já foi desbloqueado desde que ligou. Enquanto não foi, confere de novo a cada segundo. */
@Composable
private fun celularDesbloqueado(): Boolean {
    val usuario = LocalContext.current.getSystemService(UserManager::class.java)
    var desbloqueado by remember { mutableStateOf(usuario.isUserUnlocked) }
    LaunchedEffect(desbloqueado) {
        while (!desbloqueado) {
            delay(1_000)
            desbloqueado = usuario.isUserUnlocked
        }
    }
    return desbloqueado
}

/** Ícones das barras do sistema (hora, bateria, navegação) escuros no fundo claro, claros no vermelho. */
@Composable
private fun BarrasDoSistema(iconesEscuros: Boolean) {
    val janela = LocalActivity.current?.window ?: return
    val vista = LocalView.current
    SideEffect {
        WindowCompat.getInsetsController(janela, vista).run {
            isAppearanceLightStatusBars = iconesEscuros
            isAppearanceLightNavigationBars = iconesEscuros
        }
    }
}

/** Força o brilho máximo da tela; ao sair, devolve o brilho do sistema. */
@Composable
private fun BrilhoMaximo() {
    val janela = LocalActivity.current?.window ?: return
    DisposableEffect(janela) {
        val original = janela.attributes.screenBrightness
        janela.attributes = janela.attributes.apply { screenBrightness = 1f }
        onDispose { janela.attributes = janela.attributes.apply { screenBrightness = original } }
    }
}
