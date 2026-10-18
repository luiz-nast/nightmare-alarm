// Blocos da tela principal: cabeçalho, permissões, sons, testes, ajustes e a IA (que abrem e fecham) e o cartão de cada alarme.
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
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
        Text("Nightmare Alarm", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
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
                if (liberado) {
                    Text("✓", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                } else {
                    Button(onClick = { onLiberar(poder) }) { Text("Allow") }
                }
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
 * frases" (com as [frases] dos ajustes) e "conversa + caminhada", e se a IA do celular está pronta ([iaPronta]: o
 * modelo copiado pro celular e memória livre).
 */
@Composable
fun SecaoTestes(iaPronta: Boolean, frases: Int, onTestar: () -> Unit, onTestarCaminhada: () -> Unit) {
    SecaoRecolhivel("Tests", "Ring in 10 s, to check the alarm", alerta = false) {
        OutlinedButton(onClick = onTestar, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Test camera + phrases") }
        OutlinedButton(onClick = onTestarCaminhada, modifier = Modifier.fillMaxWidth()) { Text("Test talk + walk") }
        val meio = when (frases) {
            0 -> "no phrases (as set)"
            1 -> "1 phrase in the middle"
            else -> "$frases phrases in the middle"
        }
        Text(
            "Camera + phrases: 30 s of camera, $meio. Talk + walk: 3 min of talk, 30 s to go out and 3 min of walking. Test beeps are quiet, at the default volume. " +
                "On the test screen, Back, Cancel test and Next move between the phases.",
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
 * 5 em 5 ([volumeDaVoz]; de começo, [VOLUME_DA_VOZ]; vale da próxima fala em diante, até no alarme tocando); e os
 * [tempos] dos despertadores ([Tempos]): o começo e o fim da subida da música (um controle de duas pontas), a câmera
 * fácil, as frases e a câmera exigente do "câmera + frases", e a conversa, o prazo pra sair e a caminhada do
 * "conversa + caminhada". Cada um vale do próximo alarme em diante.
 */
@Composable
fun SecaoAjustes(volumeDaVoz: Float, onVolumeDaVoz: (Float) -> Unit, tempos: Tempos, onTempos: (Tempos) -> Unit) {
    var arrastando by remember(volumeDaVoz) { mutableFloatStateOf(volumeDaVoz) }
    var faixa by remember(tempos.volumeInicio, tempos.volumeFim) { mutableStateOf(tempos.volumeInicio..tempos.volumeFim) }
    val resumo = "Voice ${porcento(volumeDaVoz)}% · music ${porcento(tempos.volumeInicio)}–${porcento(tempos.volumeFim)}% · " +
        "camera ${tempos.cameraMin} min · talk + walk ${tempos.manhaMin} min"
    SecaoRecolhivel("Settings", resumo, alerta = false) {
        TituloDoAjuste("Voice volume: ${porcento(arrastando)}%")
        Slider(
            value = arrastando,
            onValueChange = { arrastando = it },
            onValueChangeFinished = { onVolumeDaVoz(arrastando) },
            valueRange = VOLUME_DA_VOZ_MIN..1f,
            steps = ((1f - VOLUME_DA_VOZ_MIN) / PASSO_DO_VOLUME).roundToInt() - 1,
        )
        Text("The good morning, the phrases and the AI. The beeps and the notices do not change.", style = MaterialTheme.typography.bodySmall)

        TituloDoAjuste("Music volume: ${porcento(faixa.start)}% rising to ${porcento(faixa.endInclusive)}%")
        RangeSlider(
            value = faixa,
            onValueChange = { faixa = it },
            onValueChangeFinished = { onTempos(tempos.copy(volumeInicio = passoDoVolume(faixa.start), volumeFim = passoDoVolume(faixa.endInclusive))) },
            valueRange = VOLUME_DA_MUSICA,
            steps = ((VOLUME_DA_MUSICA.endInclusive - VOLUME_DA_MUSICA.start) / PASSO_DO_VOLUME).roundToInt() - 1,
        )
        Text("The alarm music starts at the first value and rises to the second in 30 s (the phone's alarm volume). The medicine reminder has its own.", style = MaterialTheme.typography.bodySmall)

        SubtituloDoAjuste("Camera + phrases")
        AjusteDeMinutos("Easy camera (eyes open above 40%)", tempos.cameraFacilMin, MINUTOS_DA_CAMERA) { onTempos(tempos.copy(cameraFacilMin = it)) }
        AjusteDeInteiro("Phrases to repeat after it", tempos.frases, FRASES_DA_VERIFICACAO, if (tempos.frases == 1) "phrase" else "phrases") { onTempos(tempos.copy(frases = it)) }
        AjusteDeMinutos("Strict camera (eyes open above 55%)", tempos.cameraDificilMin, MINUTOS_DA_CAMERA) { onTempos(tempos.copy(cameraDificilMin = it)) }

        SubtituloDoAjuste("Talk + walk")
        AjusteDeMinutos("Talk with the AI", tempos.conversaMin, MINUTOS_DA_CONVERSA) { onTempos(tempos.copy(conversaMin = it)) }
        AjusteDeMinutos("Time to get ready and go out (with the waves)", tempos.prepararMin, MINUTOS_PRA_SAIR) { onTempos(tempos.copy(prepararMin = it)) }
        AjusteDeMinutos("Walking outside", tempos.caminhadaMin, MINUTOS_DA_CAMINHADA) { onTempos(tempos.copy(caminhadaMin = it)) }
        Text(
            "Each change applies from the next alarm on: an alarm that is already ringing keeps the times it rang with. Tests keep their own short times (30 s of camera; 3 min of talk, 30 s to go out, 3 min of walking) and the default 10% → 40% music volume.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** O volume no passo de 5% mais perto (o controle anda contínuo). */
private fun passoDoVolume(valor: Float): Float = (valor / PASSO_DO_VOLUME).roundToInt() * PASSO_DO_VOLUME

@Composable
private fun TituloDoAjuste(texto: String) {
    Text(texto, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun SubtituloDoAjuste(texto: String) {
    Text(texto, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp))
}

/** Um ajuste em minutos, de [faixa].first a [faixa].last, de 1 em 1: o título com o valor e o controle. [onMudar] só ao soltar. */
@Composable
private fun AjusteDeMinutos(titulo: String, valor: Int, faixa: IntRange, onMudar: (Int) -> Unit) =
    AjusteDeInteiro(titulo, valor, faixa, "min", onMudar)

@Composable
private fun AjusteDeInteiro(titulo: String, valor: Int, faixa: IntRange, unidade: String, onMudar: (Int) -> Unit) {
    var arrastando by remember(valor) { mutableFloatStateOf(valor.toFloat()) }
    val mostrado = arrastando.roundToInt()
    Text(
        "$titulo: ${if (mostrado == 0 && unidade != "min") "none" else "$mostrado $unidade"}",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp),
    )
    Slider(
        value = arrastando,
        onValueChange = { arrastando = it },
        onValueChangeFinished = { onMudar(arrastando.roundToInt().coerceIn(faixa)) },
        valueRange = faixa.first.toFloat()..faixa.last.toFloat(),
        steps = (faixa.last - faixa.first - 1).coerceAtLeast(0),
    )
}

/**
 * A IA da manhã, recolhida: o que o dono conta de si ([sobreMim], gravado enquanto digita, até
 * [SOBRE_MIM_MAX_LETRAS]) e o que a IA anotou dele na última conversa ([memoria], escrita em [memoriaEm]; com o
 * botão de esquecer). Os dois vão na instrução da IA de manhã (as notas, só enquanto valem: [memoriaVale]): é o que
 * faz a conversa continuar de onde parou, em vez de recomeçar do zero, sem virar uma pilha de dias.
 */
@Composable
fun SecaoIa(sobreMim: String, onSobreMim: (String) -> Unit, memoria: String, memoriaEm: Long, onEsquecer: () -> Unit) {
    var texto by remember(sobreMim) { mutableStateOf(sobreMim) }
    // Grava um instante depois da última letra, não a cada tecla
    LaunchedEffect(texto) {
        if (texto != sobreMim) {
            delay(800)
            onSobreMim(texto)
        }
    }
    val linhas = memoria.lines().count { it.isNotBlank() }
    val valem = memoriaVale(memoriaEm, System.currentTimeMillis())
    val resumo = when {
        sobreMim.isBlank() && linhas == 0 -> "Tell it about you · no notes from a last talk"
        linhas == 0 -> "Knows what you wrote · no notes from a last talk"
        valem -> "Remembers $linhas ${if (linhas == 1) "thing" else "things"} from your last talk"
        else -> "Notes from the last talk expired"
    }
    SecaoRecolhivel("AI companion", resumo, alerta = false) {
        TituloDoAjuste("About you")
        Text(
            "Who you are, what you want to learn, what you like talking about. The AI reads this every morning.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        OutlinedTextField(
            value = texto,
            onValueChange = { texto = it.take(SOBRE_MIM_MAX_LETRAS) },
            placeholder = { Text("I'm Luiz, I work with… I want to learn electronics…") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("${texto.length}/$SOBRE_MIM_MAX_LETRAS", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))

        TituloDoAjuste("What the AI remembers from the last talk")
        Text(
            "Only the last talk counts: at the end of each conversation the AI writes these lines again from scratch, and they expire after a day and a half. Nothing piles up.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        if (memoria.isBlank()) {
            Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            val quando = java.time.Instant.ofEpochMilli(memoriaEm).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm", java.util.Locale.US))
            Text(
                if (valem) "Written $quando · used in the next morning talk" else "Written $quando · too old, not used anymore",
                style = MaterialTheme.typography.labelMedium,
                color = if (valem) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(memoria, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            TextButton(onClick = onEsquecer, modifier = Modifier.padding(top = 4.dp)) { Text("Forget it") }
        }
        Text(
            "Looking things up: when you ask about a fact, the AI may search Wikipedia and the news for a few seconds. The model itself stays on the phone.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * A biblioteca de sons ([Sons]), recolhida: fechada, o padrão e quantos sons há; aberta, cada som com a bolinha do
 * padrão (o que toca nos alarmes sem som próprio) e "Remove"; "Phone alarm ringtone" é o padrão sem som nenhum. "Add
 * sound" copia um arquivo do celular pra dentro do app.
 */
@Composable
fun SecaoSons(sons: List<Sons.Som>, padrao: String?, onAdicionar: () -> Unit, onPadrao: (String?) -> Unit, onRemover: (String) -> Unit) {
    val quantos = when (sons.size) {
        0 -> "no sounds in the app yet"
        1 -> "1 sound in the app"
        else -> "${sons.size} sounds in the app"
    }
    SecaoRecolhivel("Sounds", "Default: ${padrao ?: "phone alarm ringtone"} · $quantos", alerta = false) {
        Text(
            "Each alarm picks its sound in the editor; the marked one plays when an alarm has none. Sounds are copied into the app: they keep playing even if the original file is gone.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        LinhaDoSom("Phone alarm ringtone", padrao == null, { onPadrao(null) }, onRemover = null)
        sons.forEach { som -> LinhaDoSom(som.nome, padrao == som.nome, { onPadrao(som.nome) }) { onRemover(som.nome) } }
        OutlinedButton(onClick = onAdicionar, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Add sound") }
    }
}

@Composable
private fun LinhaDoSom(nome: String, padrao: Boolean, onPadrao: () -> Unit, onRemover: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onPadrao), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = padrao, onClick = onPadrao)
        Text(nome, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onRemover != null) TextButton(onClick = onRemover) { Text("Remove") }
    }
}

@Composable
fun CartaoAlarme(alarme: Alarme, agora: ZonedDateTime, tempos: Tempos, onLigar: (Boolean) -> Unit, onClick: () -> Unit) {
    val corHorario = if (alarme.ativo) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(alarme.horario, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = corHorario)
                Text(
                    listOfNotNull(alarme.rotulo.ifBlank { null }, resumoDias(alarme.dias), alarme.som?.let { "♪ $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        alarme.remedio -> "reminder: gentle tone"
                        alarme.caminhada -> "talk + walk: ${tempos.conversaMin} min of AI and ${tempos.caminhadaMin} min of walking"
                        else -> "camera + phrases: ${tempos.cameraMin} min of camera"
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
