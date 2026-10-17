package com.implacavel.alarme

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Diálogo pra criar ou editar um alarme. Não grava nada: devolve o alarme pronto em [onSalvar]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorAlarme(
    inicial: Alarme,
    novo: Boolean,
    onSalvar: (Alarme) -> Unit,
    onExcluir: () -> Unit,
    onCancelar: () -> Unit,
) {
    val ctx = LocalContext.current
    val relogio = rememberTimePickerState(initialHour = inicial.hora, initialMinute = inicial.minuto, is24Hour = true)
    var rotulo by remember { mutableStateOf(inicial.rotulo) }
    var dias by remember { mutableStateOf(inicial.dias) }
    // Um tipo só: "câmera + frases", "conversa + caminhada" (os dois com missão) ou lembrete de remédio
    var tipo by remember { mutableStateOf(tipoDe(inicial)) }
    // O som deste alarme, da biblioteca (null = o padrão); "Add new sound…" copia um arquivo pra biblioteca e já o escolhe
    var som by remember { mutableStateOf(inicial.som) }
    val sons by Sons.lista.collectAsStateWithLifecycle()
    val padrao by Sons.padrao.collectAsStateWithLifecycle()
    val adicionarSom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val novoSom = Sons.adicionar(ctx, uri)
        if (novoSom == null) Toast.makeText(ctx, "Could not read that file.", Toast.LENGTH_LONG).show() else som = novoSom.nome
    }

    Dialog(onDismissRequest = onCancelar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.94f).padding(vertical = 24.dp),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (novo) "New alarm" else "Edit alarm", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                TimePicker(state = relogio)
                OutlinedTextField(
                    value = rotulo,
                    onValueChange = { rotulo = it.take(40) },
                    label = { Text("Name (e.g. Medicine)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text("Repeat", style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                SeletorDias(dias) { dias = it }
                Text(
                    resumoDias(dias),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text("Type", style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
                OpcaoTipo(
                    "Camera + phrases - ${META_OLHAR_MS / 60_000} min",
                    "The music, saying STOP and 40 min of camera with your eyes open, with 5 phrases to repeat in the middle",
                    tipo == TipoDeAlarme.ATIVIDADE,
                ) { tipo = TipoDeAlarme.ATIVIDADE }
                OpcaoTipo(
                    "Talk + walk - ${(CONVERSA_MS + PRAZO_SAIR_MS + CAMINHADA_MS) / 60_000} min",
                    "The music, saying STOP, 10 min of talk with the AI, 4 min to go out and 16 min of walking outside, rain or shine",
                    tipo == TipoDeAlarme.CONVERSA_E_CAMINHADA,
                ) { tipo = TipoDeAlarme.CONVERSA_E_CAMINHADA }
                OpcaoTipo(
                    "Medicine reminder",
                    "A gentle tone, from 15% to 75% volume in 2 min, and a TURN OFF button",
                    tipo == TipoDeAlarme.REMEDIO,
                ) { tipo = TipoDeAlarme.REMEDIO }
                // O remédio toca o toque suave do app: sem escolha de som
                if (tipo != TipoDeAlarme.REMEDIO) {
                    Spacer(Modifier.height(8.dp))
                    Text("Sound", style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
                    OpcaoSom("Default: ${padrao ?: "phone alarm ringtone"}", som == null) { som = null }
                    sons.forEach { s -> OpcaoSom(s.nome, som == s.nome) { som = s.nome } }
                    TextButton(onClick = { adicionarSom.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Add new sound…") }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (!novo) {
                        TextButton(
                            onClick = onExcluir,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Delete") }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancelar) { Text("Cancel") }
                    Button(
                        onClick = {
                            val alarme = inicial.copy(
                                hora = relogio.hour,
                                minuto = relogio.minute,
                                rotulo = rotulo.trim(),
                                dias = dias,
                                missao = tipo != TipoDeAlarme.REMEDIO,
                                remedio = tipo == TipoDeAlarme.REMEDIO,
                                caminhada = tipo == TipoDeAlarme.CONVERSA_E_CAMINHADA,
                                som = if (tipo == TipoDeAlarme.REMEDIO) null else som,
                                ativo = true,
                            )
                            onSalvar(alarme)
                        },
                    ) { Text("Save") }
                }
            }
        }
    }
}

/** Os tipos de alarme do editor, um só por alarme. */
private enum class TipoDeAlarme { ATIVIDADE, CONVERSA_E_CAMINHADA, REMEDIO }

private fun tipoDe(alarme: Alarme): TipoDeAlarme = when {
    alarme.remedio -> TipoDeAlarme.REMEDIO
    alarme.caminhada -> TipoDeAlarme.CONVERSA_E_CAMINHADA
    else -> TipoDeAlarme.ATIVIDADE
}

/** Sete bolinhas (D S T Q Q S S) que ligam e desligam cada dia da semana. */
@Composable
private fun SeletorDias(dias: Set<Int>, onMudar: (Set<Int>) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        ORDEM_DIAS.forEach { dia ->
            val marcado = dia in dias
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (marcado) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onMudar(if (marcado) dias - dia else dias + dia) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    LETRA_DIA.getValue(dia),
                    fontWeight = FontWeight.Bold,
                    color = if (marcado) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Uma linha da escolha do som: bolinha e nome. */
@Composable
private fun OpcaoSom(nome: String, escolhido: Boolean, onEscolher: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onEscolher).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = escolhido, onClick = onEscolher)
        Text(nome, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun OpcaoTipo(titulo: String, descricao: String, escolhida: Boolean, onEscolher: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onEscolher).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = escolhida, onClick = onEscolher)
        Column(Modifier.weight(1f)) {
            Text(titulo, style = MaterialTheme.typography.bodyLarge)
            Text(descricao, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
