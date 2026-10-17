// Blocos da tela principal: cabeçalho, permissões, testes e ajustes (que abrem e fecham), cartão da música e cartão de cada alarme.
package com.implacavel.alarme

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZonedDateTime
import kotlin.math.roundToInt

@Composable
fun Cabecalho(alarmes: List<Alarme>, agora: ZonedDateTime) {
    val proximo = alarmes.filter { it.ativo }.map { it.proximoDisparo(agora) }.minByOrNull { it.toEpochSecond() }
    Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
        Text("Nightmare Alarm", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            if (proximo == null) {
                "No alarm on"
            } else {
                "Next: ${NOME_CURTO_DIA.getValue(proximo.dayOfWeek.value)}, " +
                    "${hhmm(proximo.toLocalTime())} · ${tempoAte(agora, proximo)}"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Um bloco da tela principal que abre e fecha num toque: fechado, só o [titulo] e o [resumo] (em vermelho com
 * [alerta]); aberto, o [conteudo]. Começa aberto com [alerta], e abre sozinho quando o alerta aparece.
 */
@Composable
fun SecaoRecolhivel(titulo: String, resumo: String, alerta: Boolean, conteudo: @Composable ColumnScope.() -> Unit) {
    var aberta by rememberSaveable { mutableStateOf(alerta) }
    LaunchedEffect(alerta) { if (alerta) aberta = true }
    val cores = if (alerta) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    } else {
        CardDefaults.cardColors()
    }
    Card(colors = cores, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { aberta = !aberta }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(titulo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(resumo, style = MaterialTheme.typography.bodyMedium)
            }
            Text(if (aberta) "▴" else "▾", fontSize = 22.sp)
        }
        AnimatedVisibility(aberta) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), content = conteudo)
        }
    }
}

/**
 * As permissões do alarme ([poderes]: cada uma e se está liberada), recolhidas: fechado, só "✓ Todas
 * liberadas" ou quantas faltam; aberto, todas, cada uma com ✓ ou com o botão "Liberar". Faltando alguma,
 * aparece aberto e em vermelho.
 */
@Composable
fun CartaoPoderes(poderes: Map<Poder, Boolean>, onLiberar: (Poder) -> Unit) {
    val faltam = poderes.count { !it.value }
    val resumo = when (faltam) {
        0 -> "✓ All allowed: the alarm rings locked, on silent and on Do Not Disturb"
        1 -> "⚠ 1 missing for a relentless alarm"
        else -> "⚠ $faltam missing for a relentless alarm"
    }
    SecaoRecolhivel("Permissions", resumo, alerta = faltam > 0) {
        poderes.forEach { (poder, liberado) ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(poder.titulo, fontWeight = FontWeight.SemiBold)
                    Text(poder.explicacao, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.width(8.dp))
                if (liberado) Text("✓", fontSize = 20.sp, fontWeight = FontWeight.Bold) else Button(onClick = { onLiberar(poder) }) { Text("Allow") }
            }
        }
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            Text(
                "Samsung: in Settings › Battery › Background usage limits, check that the app " +
                    "is not in \"Sleeping apps\" or \"Deep sleeping apps\".",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * Os botões de teste (tocam em 10 s, com cada etapa bem mais curta), recolhidos: os dois despertadores, "câmera +
 * frases" e "conversa + caminhada", e se a IA do celular está pronta ([iaPronta]: o modelo copiado pro celular e
 * memória livre).
 */
@Composable
fun SecaoTestes(iaPronta: Boolean, onTestar: () -> Unit, onTestarCaminhada: () -> Unit) {
    SecaoRecolhivel("Tests", "Ring in 10 s, to check the alarm", alerta = false) {
        OutlinedButton(onClick = onTestar, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Test camera + phrases") }
        OutlinedButton(onClick = onTestarCaminhada, modifier = Modifier.fillMaxWidth()) { Text("Test talk + walk") }
        Text(
            "Camera + phrases: 30 s of camera, with the phrases in the middle. Talk + walk: 5 min of talk, 30 s to go out and 8 min of walking.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            if (iaPronta) "Phone AI ready (Gemma 4 E2B, no internet)" else "Phone AI without the model: the test goes straight to the walk",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Os ajustes, recolhidos: o volume da voz do app (o bom dia, as frases e a IA), de 10% a 100% do volume de alarme, de
 * 5 em 5 ([volumeDaVoz]; de começo, [VOLUME_DA_VOZ]). Vale da próxima fala em diante, até no alarme tocando.
 */
@Composable
fun SecaoAjustes(volumeDaVoz: Float, onVolumeDaVoz: (Float) -> Unit) {
    var arrastando by remember(volumeDaVoz) { mutableFloatStateOf(volumeDaVoz) }
    SecaoRecolhivel("Settings", "App voice: ${porcento(volumeDaVoz)}%", alerta = false) {
        Text(
            "Voice volume: ${porcento(arrastando)}%",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )
        Slider(
            value = arrastando,
            onValueChange = { arrastando = it },
            onValueChangeFinished = { onVolumeDaVoz(arrastando) },
            valueRange = VOLUME_DA_VOZ_MIN..1f,
            steps = ((1f - VOLUME_DA_VOZ_MIN) / 0.05f).roundToInt() - 1,
        )
        Text(
            "The good morning, the phrases and the AI. The music, the beeps and the notices do not change.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Música que toca em todos os alarmes; sem escolha, vale o toque de alarme do celular. */
@Composable
fun CartaoMusica(nome: String?, onEscolher: () -> Unit, onTirar: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Alarm music", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    nome ?: "Phone alarm ringtone",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (nome != null) TextButton(onClick = onTirar) { Text("Remove") }
            Button(onClick = onEscolher) { Text("Choose") }
        }
    }
}

@Composable
fun CartaoAlarme(alarme: Alarme, agora: ZonedDateTime, onLigar: (Boolean) -> Unit, onClick: () -> Unit) {
    val corHorario = if (alarme.ativo) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(alarme.horario, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = corHorario)
                Text(
                    listOfNotNull(alarme.rotulo.ifBlank { null }, resumoDias(alarme.dias)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    when {
                        alarme.remedio -> "reminder: gentle tone"
                        alarme.caminhada -> "talk + walk: 10 min of AI and 16 min of walking"
                        else -> "camera + phrases: 40 min of camera"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (alarme.ativo) {
                    Text(
                        "Rings ${tempoAte(agora, alarme.proximoDisparo(agora))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = alarme.ativo, onCheckedChange = onLigar)
        }
    }
}
