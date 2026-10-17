// Compartilhado pelas telas: tema (sempre claro), cores fixas da tela do alarme, o relógio agoraACada e lidoAoVoltar.
package com.implacavel.alarme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

/** Cores da tela do alarme, que não segue o tema do sistema. O vermelho é o mesmo de R.color.vermelho_alarme. */
val VermelhoAlarme = Color(0xFFB71C1C)
val VinhoAlarme = Color(0xFF3B0000)

/** A etapa da caminhada (as ondas do prazo pra sair e a rua) é azul, não vermelha: pedido do dono (10-17). */
val AzulCaminhada = Color(0xFF0D47A1)
val FundoCaminhada = listOf(Color(0xFFF3F9FF), Color(0xFFBBDEFB))

private val Claro = lightColorScheme(
    primary = Color(0xFFB3261E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF775652),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDAD6),
    onSecondaryContainer = Color(0xFF2C1512),
    background = Color(0xFFFFF8F7),
    onBackground = Color(0xFF231918),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF231918),
    surfaceVariant = Color(0xFFF5DDDA),
    onSurfaceVariant = Color(0xFF534341),
    outline = Color(0xFF857371),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFF0EE),
    surfaceContainer = Color(0xFFFCEAE7),
    surfaceContainerHigh = Color(0xFFF6E4E2),
    surfaceContainerHighest = Color(0xFFF1DEDC),
)

/**
 * Tema do app: tons de vermelho de alarme, sempre claro, mesmo com o sistema no modo escuro (o dono
 * pediu; assim o YouTube da etapa da câmera também fica claro).
 */
@Composable
fun TemaImplacavel(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Claro, content = content)
}

/** Hora atual, que se atualiza sozinha a cada [intervaloMs] (relógio da tela do alarme, textos "toca em 9 h"). */
@Composable
fun agoraACada(intervaloMs: Long): ZonedDateTime {
    var agora by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(intervaloMs) {
        while (true) {
            delay(intervaloMs)
            agora = ZonedDateTime.now()
        }
    }
    return agora
}

/**
 * O que [ler] devolve, lido de novo toda vez que a tela volta pro primeiro plano (depois do diálogo de
 * permissão do sistema ou de voltar das Configurações) e quando [chave] muda: permissões da tela
 * principal, câmera do alarme.
 */
@Composable
fun <T> lidoAoVoltar(chave: Any? = Unit, ler: () -> T): T {
    val lerAgora by rememberUpdatedState(ler)
    var valor by remember { mutableStateOf(ler()) }
    LifecycleResumeEffect(chave) {
        valor = lerAgora()
        onPauseOrDispose { }
    }
    return valor
}
