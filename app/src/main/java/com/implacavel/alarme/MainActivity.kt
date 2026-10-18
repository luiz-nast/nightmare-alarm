package com.implacavel.alarme

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/** Tela principal, em duas páginas: os alarmes (à esquerda) e o resto (permissões, sons, testes, ajustes e a IA). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // "Forçar parada" nas Configurações apaga os agendamentos do app; abrir o app os refaz. Só ao
        // abrir, não ao recriar a tela (girar o celular): reagendar um alarme segundos depois da hora
        // em que ele deveria tocar o joga pro dia seguinte.
        if (savedInstanceState == null) Agendador.reagendarTodos(this)
        // A tela principal é escura (a Noite de Tema.kt): ícones claros nas barras do sistema
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setContent { TemaImplacavel(escuro = true) { TelaPrincipal() } }
    }
}

/** As duas páginas, pela ordem. */
private val PAGINAS = listOf("Alarms", "More")

@Composable
private fun TelaPrincipal() {
    val ctx = LocalContext.current
    val alarmes by Alarmes.lista.collectAsStateWithLifecycle()
    val tocando by AlarmeService.tocando.collectAsStateWithLifecycle()
    val sons by Sons.lista.collectAsStateWithLifecycle()
    val somPadrao by Sons.padrao.collectAsStateWithLifecycle()
    val volumeDaVoz by Ajustes.volumeDaVozAgora.collectAsStateWithLifecycle()
    val tempos by Ajustes.tempos.collectAsStateWithLifecycle()
    val sobreMim by Ajustes.sobreMim.collectAsStateWithLifecycle()
    val memoriaDaIa by Ajustes.memoriaDaIa.collectAsStateWithLifecycle()
    lidoAoVoltar { Sons.reler() } // um som pode ter chegado pelo cabo
    val agora = agoraACada(15_000) // pros textos "rings in 9 h 12 min"
    var editando by remember { mutableStateOf<Alarme?>(null) }
    val paginas = rememberPagerState { PAGINAS.size }
    val rolar = rememberCoroutineScope()

    // Permissões e a IA do celular: relidas toda vez que a tela volta (do diálogo do sistema ou das Configurações)
    val poderes = lidoAoVoltar { Poder.entries.associateWith { it.liberado(ctx) } }
    val iaPronta = lidoAoVoltar { MotorDaConversa.disponivel(ctx) }

    // Permissões com diálogo do sistema (notificação, microfone, câmera, localização). Se o usuário já
    // negou, o sistema não pergunta de novo; aí, se o pedido veio de um botão "Liberar", abre as Configurações.
    var pedidoDoBotao by remember { mutableStateOf<Poder?>(null) }
    val pedirPermissao = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        pedidoDoBotao?.let { if (it.permissaoFaltando(ctx) != null) it.abrirConfiguracao(ctx) }
        pedidoDoBotao = null
    }
    LaunchedEffect(Unit) { Poder.NOTIFICACOES.permissaoFaltando(ctx)?.let { pedirPermissao.launch(Poder.NOTIFICACOES.pedido) } }
    val liberar: (Poder) -> Unit = { poder ->
        if (poder.permissaoFaltando(ctx) != null) {
            pedidoDoBotao = poder
            pedirPermissao.launch(poder.pedido)
        } else {
            poder.abrirConfiguracao(ctx)
        }
    }

    val adicionarSom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && Sons.adicionar(ctx, uri) == null) Toast.makeText(ctx, "Could not read that file.", Toast.LENGTH_LONG).show()
    }

    Scaffold(
        floatingActionButton = {
            // Só na página dos alarmes
            AnimatedVisibility(paginas.currentPage == 0) {
                ExtendedFloatingActionButton(
                    onClick = { editando = Alarme(id = Alarmes.novoId(), hora = 7, minuto = 0) },
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text("New alarm") },
                )
            }
        },
    ) { margens ->
        Column(Modifier.fillMaxSize().padding(top = margens.calculateTopPadding())) {
            // Atalho de volta pra tela do alarme, caso a notificação tenha sumido: visível nas duas páginas
            tocando?.let { alarme ->
                Button(
                    onClick = { ctx.startActivity(Intent(ctx, AlarmeActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(56.dp),
                ) { Text("⏰ ${alarme.nome} ringing · open") }
            }
            TabRow(selectedTabIndex = paginas.currentPage) {
                PAGINAS.forEachIndexed { i, titulo ->
                    Tab(
                        selected = paginas.currentPage == i,
                        onClick = { rolar.launch { paginas.animateScrollToPage(i) } },
                        text = { Text(titulo, fontWeight = if (paginas.currentPage == i) FontWeight.Bold else FontWeight.Normal) },
                    )
                }
            }
            HorizontalPager(paginas, Modifier.fillMaxSize()) { pagina ->
                val margensDaLista = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = margens.calculateBottomPadding() + 96.dp)
                if (pagina == 0) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = margensDaLista, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Cabecalho(alarmes, agora) }
                        items(alarmes, key = { it.id }) { alarme ->
                            CartaoAlarme(
                                alarme = alarme,
                                agora = agora,
                                tempos = tempos,
                                onLigar = { ligado -> salvarEAgendar(ctx, alarme.copy(ativo = ligado)) },
                                onClick = { editando = alarme },
                            )
                        }
                        if (alarmes.isEmpty()) {
                            item {
                                Text(
                                    "No alarms yet. Tap \"New alarm\" to create the first one.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 24.dp),
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = margensDaLista, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { CartaoPoderes(poderes, liberar) }
                        item {
                            SecaoSons(
                                sons = sons,
                                padrao = somPadrao,
                                onAdicionar = { adicionarSom.launch(arrayOf("audio/*")) },
                                onPadrao = Sons::escolherPadrao,
                                onRemover = Sons::remover,
                            )
                        }
                        item {
                            SecaoTestes(
                                iaPronta = iaPronta,
                                frases = tempos.frases,
                                onTestar = {
                                    Agendador.agendarTeste(ctx, 10)
                                    Toast.makeText(ctx, "Rings in 10 seconds. Lock the screen to see the full effect.", Toast.LENGTH_LONG).show()
                                },
                                onTestarCaminhada = {
                                    // Sem a localização, a caminhada não conta: pede no diálogo agora (sem abrir as Configurações;
                                    // a tela da caminhada também tem o botão de liberar)
                                    val comIa = MotorDaConversa.disponivel(ctx)
                                    val semLocalizacao = Poder.LOCALIZACAO.permissaoFaltando(ctx) != null
                                    if (semLocalizacao) pedirPermissao.launch(Poder.LOCALIZACAO.pedido)
                                    Agendador.agendarTeste(ctx, 10, caminhada = true)
                                    val aviso = when {
                                        semLocalizacao -> "Allow location in the prompt: without it, the walk does not count."
                                        !comIa -> "The phone AI is not ready: it goes straight to the walk."
                                        else -> "Lock the screen to see the full effect."
                                    }
                                    Toast.makeText(ctx, "Rings in 10 seconds. $aviso", Toast.LENGTH_LONG).show()
                                },
                            )
                        }
                        item { SecaoAjustes(volumeDaVoz, Ajustes::escolherVolumeDaVoz, tempos, Ajustes::escolherTempos) }
                        item { SecaoIa(sobreMim, Ajustes::escreverSobreMim, memoriaDaIa, onEsquecer = { Ajustes.guardarMemoriaDaIa("") }) }
                    }
                }
            }
        }
    }

    editando?.let { alarme ->
        EditorAlarme(
            inicial = alarme,
            novo = Alarmes.buscar(alarme.id) == null,
            onSalvar = {
                salvarEAgendar(ctx, it)
                editando = null
            },
            onExcluir = {
                Agendador.cancelar(ctx, alarme.id)
                Alarmes.remover(alarme.id)
                editando = null
            },
            onCancelar = { editando = null },
        )
    }
}

private fun salvarEAgendar(ctx: Context, alarme: Alarme) {
    Alarmes.salvar(alarme)
    val quando = Agendador.agendar(ctx, alarme) ?: return
    Toast.makeText(ctx, "Alarm ${tempoAte(ZonedDateTime.now(), quando)}", Toast.LENGTH_SHORT).show()
}
