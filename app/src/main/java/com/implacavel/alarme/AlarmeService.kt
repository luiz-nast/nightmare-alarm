package com.implacavel.alarme

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Serviço em primeiro plano que toca o alarme em andamento ([Ajustes.emAndamento], gravado pelo
 * [AlarmeReceiver]) até a missão ser cumprida, ou até o DESLIGAR, em alarme sem missão: esse é o
 * único fim do alarme ([concluir]). Controla a [Sirene] e a notificação que abre a
 * [AlarmeActivity] em tela cheia, e não deixa ninguém fugir:
 * - tela do alarme fechada (Home, arrastar o app, apagar a tela): em [TELA_FECHADA_MS] a música
 *   volta e a tela reabre; o anel da câmera fica onde estava;
 * - depois do "stop", a voz do celular dá bom dia, [PAUSA_DEPOIS_DA_SAUDACAO_MS] depois diz "Activity
 *   starting now", toca o bipe positivo e começa a câmera; a cada 10 min completos, a voz avisa;
 * - na metade, o anel para e vem a verificação: a voz fala as [Tempos.frases] frases do dia (0 a 10) e a pessoa repete
 *   cada uma; sem responder por [SILENCIO_APITO_MS], um apito alto, e em [SILENCIO_ZERA_MS] o anel zera e a
 *   música volta. Depois dela, a câmera fica mais exigente com os olhos;
 *   daí a música só fica calada enquanto o relógio da câmera ([tiqueDaCamera]) vê a pessoa
 *   olhando; sem aviso, a vigia bipa a cada [AVISO_SEM_OLHAR_MS], cada vez mais alto (o último, no
 *   volume máximo do celular; no teste, no máximo [Sirene.VOLUME_DO_BIPE_DE_TESTE]), e em [DESISTENCIA_MS] religa a música (o anel zera);
 * - a cada [RENOVAR_MS], empurra pra frente a retomada no AlarmManager: se o app cair, travar ou
 *   for encerrado, ou se o celular desligar, o alarme volta sozinho;
 * - se o app caiu [LIMITE_QUEDAS] vezes neste alarme, é defeito, não truque: ele volta com o
 *   DESLIGAR, pra um defeito não prender ninguém num alarme que nunca desliga. Numa queda só,
 *   o desafio continua valendo;
 * - no despertador "conversa + caminhada" ([Alarme.caminhada]), depois do bom dia vêm a conversa com a IA
 *   ([MotorDaConversa], no celular) e a caminhada pelo GPS, chova ou faça sol, com ou sem internet (sem a IA,
 *   direto pra caminhada). Quem decide tudo são os relógios daqui: a IA só produz o texto das falas.
 *
 * Comandos pela action do Intent, cada um com uma função no companion: tocar, encerrar sem missão (defeito ou cabo),
 * desligar, reexibir, tela, silenciar, volume, dar tempo, ondas, fase e cancelar teste. Na
 * conversa, quem ouve é a [Escuta], daqui mesmo; na caminhada, o GPS e os passos ([SentidosDaCaminhada]) e o relógio
 * deles ([ContadorDaCaminhada]) também são daqui: a conversa e a caminhada seguem com a tela fechada.
 * O comando reexibir vem da notificação arrastada pro lado.
 */
/** Um passo da verificação do meio: a [frase] da vez ([numero] de [total]; vazia na fala de abertura e no agradecimento), se é a [suaVez] de repetir e o que o reconhecedor do serviço já [ouvido]. */
data class Verificacao(val frase: String, val numero: Int, val total: Int, val suaVez: Boolean, val ouvido: String = "")

/**
 * A conversa da manhã com a IA: a [falaDaIa] mostrada, se é a [suaVez] de a pessoa falar, o que ela disse nesta vez
 * ([ouvido]), se o app está [pensando] (esperando a IA ou a voz gravar), o que a IA está procurando na internet
 * ([procurando], vazio fora de uma busca), o tempo de conversa pura ([conversadoMs]) e a meta, e se a pergunta agora
 * é se ela quer continuar ([perguntandoSeContinua]).
 */
data class Conversa(
    val falaDaIa: String,
    val suaVez: Boolean,
    val pensando: Boolean,
    val conversadoMs: Long,
    val metaMs: Long,
    val perguntandoSeContinua: Boolean = false,
    val ouvido: String = "",
    val procurando: String = "",
)

/**
 * A caminhada da manhã: o tempo já [andadoMs] (somado pelo serviço, [ContadorDaCaminhada]), o que ainda está
 * [conferindoMs], a meta e o fim do prazo pra sair de casa ([prazoAte], SystemClock.elapsedRealtime).
 */
data class Caminhada(val andadoMs: Long, val metaMs: Long, val prazoAte: Long, val conferindoMs: Long = 0)

/**
 * A mini tarefa do prazo pra sair ([ofereceMiniTarefa]): a [frase] pra repetir e ganhar mais prazo, se já é a
 * [suaVez] de repetir (depois de a voz terminar de oferecer: antes, o reconhecedor ouviria a própria voz dizendo a
 * frase) e o que o reconhecedor do serviço já [ouvido].
 */
data class MiniTarefa(val frase: String, val suaVez: Boolean, val ouvido: String = "")

/** As etapas do despertador "conversa + caminhada", depois do STOP e do bom dia. */
private enum class EtapaDaManha { CONVERSA, CAMINHADA }

/** Um pedido pra IA: chama o motor com o [aoResponder] (que recebe a fala ou null). Guardado pra pedir de novo numa falha. */
private typealias PedidoDaIa = (aoResponder: (String?) -> Unit) -> Unit

class AlarmeService : Service() {

    companion object {
        private const val ACAO_TOCAR = "tocar"
        private const val ACAO_ENCERRAR_SEM_MISSAO = "encerrar_sem_missao"
        private const val ACAO_DESLIGAR = "desligar"
        private const val ACAO_REEXIBIR = "reexibir"
        private const val ACAO_TELA = "tela"
        private const val ACAO_SILENCIAR = "silenciar"
        private const val ACAO_VOLUME = "volume"
        private const val ACAO_DAR_TEMPO = "dar_tempo"
        private const val ACAO_ONDAS = "ondas"
        private const val ACAO_FASE = "fase"
        private const val ACAO_CANCELAR_TESTE = "cancelar_teste"
        private const val EXTRA_VOLUME = "volume"
        private const val EXTRA_ABERTA = "aberta"
        private const val EXTRA_LIGADAS = "ligadas"
        private const val EXTRA_FASE = "fase"

        /**
         * Tela do alarme fechada no meio: tempo até a música voltar e a tela reabrir. O Android ainda leva
         * de 0,5 a 3 s pra mostrar a tela cheia; com 1 s aqui, o total fica abaixo de 5 s.
         */
        private const val TELA_FECHADA_MS = 1_000L

        /** De quanto em quanto tempo a retomada é empurrada pra frente ([Agendador.RETOMADA_MS]). */
        private const val RENOVAR_MS = 5_000L

        /** Tempo sem a tela do alarme voltar, pra desbloquear o celular ou liberar a câmera. A música continua. */
        private const val TEMPO_FORA_MS = 60_000L

        /** De quanto em quanto tempo o serviço relê a permissão de localização da caminhada (liberada no meio, os sentidos ligam). */
        private const val RELER_LOCALIZACAO_MS = 1_000L

        /** Entre dois pedidos dos tipos do serviço ([atualizarTipos]): o Android recusou, não adianta pedir a cada segundo. */
        private const val REPEDIR_TIPOS_MS = 15_000L

        private const val LIMITE_QUEDAS = 2

        /** Entre a saudação falada e o "Activity starting now". */
        private const val PAUSA_DEPOIS_DA_SAUDACAO_MS = 1_500L

        /** Entre o "Let's verify..." e a primeira frase da verificação. */
        private const val PAUSA_ANTES_DAS_FRASES_MS = 1_000L

        /** Se os sons do fim travarem, o alarme termina assim mesmo depois disso. */
        private const val COMEMORACAO_MAX_MS = 20_000L

        /** Se a voz travar, a câmera começa assim mesmo depois disso. */
        private const val SAUDACAO_MAX_MS = 20_000L

        /**
         * Despertador "conversa + caminhada": depois do bom dia, o máximo de espera pela abertura da IA (as notícias
         * vêm antes dela). Sem a abertura até lá, direto pra caminhada.
         */
        private const val PREPARO_MAX_MS = 20_000L

        /** Na conversa, se a voz travar gravando a fala da IA, ela vai só na tela depois disso e a vez passa pra pessoa. */
        private const val VOZ_DA_IA_MAX_MS = 10_000L

        /** No teste, "Back" pra conversa carrega o modelo de novo, lê a instrução e abre: cabe tudo nisso, mesmo com as anotações ainda em curso. */
        private const val VOLTA_MAX_MS = 60_000L

        /** O bipe do silêncio na conversa demora isso pra começar a sair (o MediaPlayer): a escuta fica surda também. */
        private const val ATRASO_DO_BIPE_MS = 300L

        /** O reconhecedor fecha a frase até isso depois de a IA começar a pensar, às vezes com uma palavra a mais: não é fala nova. */
        private const val FRASE_ATRASADA_MS = 1_000L

        /** Falas fixas, gravadas assim que a voz fica pronta: o início e os avisos de 10 em 10 min (a câmera vai até 60 min: avisos de 10 a 50). */
        private const val FALA_DO_INICIO = "inicio"
        private const val FALA_DO_FIM = "fim"
        private const val FALA_DE_VERIFICAR = "verificar"
        private const val FALA_DA_METADE = "metade"
        private val FALAS_FIXAS = mapOf(FALA_DO_INICIO to FALA_INICIO, FALA_DO_FIM to FALA_FIM, FALA_DE_VERIFICAR to FALA_VERIFICAR, FALA_DA_METADE to FALA_METADE) +
            (1 until MINUTOS_DA_CAMERA.last * 2 / 10).associate { "minutos_${it * 10}" to falaDosMinutos(it * 10) }

        /** As falas fixas da conversa e da caminhada: gravadas só no despertador "conversa + caminhada" (o outro fica igual). */
        private const val FALA_DE_CONTINUAR = "continuar"
        private const val FALA_DE_SAIR = "sair"
        private const val FALA_DE_NAO_NEGOCIAR = "nao_negocia"
        private const val FALA_DE_PROCURAR = "procurar"
        private const val FALA_DA_MINI_TAREFA = "mini_tarefa"
        private const val FALA_DE_MAIS_TEMPO = "mais_tempo"
        private const val FALA_DE_NAO_ENTENDER = "nao_entendi"

        private val _tocando = MutableStateFlow<Alarme?>(null)

        /** Alarme tocando agora (null = nenhum). A AlarmeActivity e a tela principal observam isso. */
        val tocando: StateFlow<Alarme?> = _tocando.asStateFlow()

        private val _silenciado = MutableStateFlow(false)

        /** true = a pessoa já disse "stop" e a música está calada: a tela do alarme mostra a etapa da câmera. */
        val silenciado: StateFlow<Boolean> = _silenciado.asStateFlow()

        private val _comCaminhada = MutableStateFlow(false)

        /** Esta manhã é a do "conversa + caminhada" (a tela do bom dia diz "Preparando a manhã…", não "A câmera já vai começar"). */
        val comCaminhada: StateFlow<Boolean> = _comCaminhada.asStateFlow()

        private val _saudando = MutableStateFlow(false)

        /** true = acabou de dizer "stop" e a voz está dando bom dia: a tela mostra a saudação, ainda sem câmera. */
        val saudando: StateFlow<Boolean> = _saudando.asStateFlow()

        private val _comemorando = MutableStateFlow(false)

        /** true = a missão foi cumprida e o app comemora (bipe leve, parabéns e o estouro) antes de fechar. */
        val comemorando: StateFlow<Boolean> = _comemorando.asStateFlow()

        private val _verificacao = MutableStateFlow<Verificacao?>(null)

        /** A verificação do meio em andamento (null = fora dela): a tela mostra a frase e, na vez da pessoa, o que o reconhecedor do serviço ouve. */
        val verificacao: StateFlow<Verificacao?> = _verificacao.asStateFlow()

        private val _metadeVerificada = MutableStateFlow(false)

        /** A verificação desta rodada já foi feita: o anel passa da metade e a câmera fica mais exigente. */
        val metadeVerificada: StateFlow<Boolean> = _metadeVerificada.asStateFlow()

        private val _ultimaResposta = MutableStateFlow(0L)

        /** Quando (SystemClock.elapsedRealtime) chegou a vez ou a última fala da pessoa na verificação: a tela conta daqui o tempo pra zerar. */
        val ultimaResposta: StateFlow<Long> = _ultimaResposta.asStateFlow()

        private val _ultimoOlhar = MutableStateFlow(0L)

        /**
         * Quando (SystemClock.elapsedRealtime) chegou o último sinal de "olhando", ou o "stop". A vigia
         * conta daqui os bipes e o tempo pra zerar, e a tela do alarme mostra daqui a contagem.
         */
        val ultimoOlhar: StateFlow<Long> = _ultimoOlhar.asStateFlow()

        private val _olhado = MutableStateFlow(0L)

        /**
         * Tempo de olhos abertos já somado no anel da câmera, em ms. Só sobe com a música calada e só
         * zera nos [DESISTENCIA_MS] sem olhar (e no fim do alarme). Sair da tela traz a música de
         * volta, mas o anel fica: depois do "stop", a câmera continua daqui, e a tela recriada no meio também.
         */
        val olhado: StateFlow<Long> = _olhado.asStateFlow()

        private val _conversa = MutableStateFlow<Conversa?>(null)

        /** A conversa da manhã com a IA em andamento (null = fora dela): a tela mostra a fala da IA e, na vez da pessoa, o que a [Escuta] do serviço ouviu. */
        val conversa: StateFlow<Conversa?> = _conversa.asStateFlow()

        private val _ultimaFala = MutableStateFlow(0L)

        /** Na conversa: quando (SystemClock.elapsedRealtime) começou a vez da pessoa ou ela falou por último. O silêncio e os bipes contam daqui. */
        val ultimaFala: StateFlow<Long> = _ultimaFala.asStateFlow()

        private val _caminhada = MutableStateFlow<Caminhada?>(null)

        /** A caminhada da manhã em andamento, com o prazo pra sair de casa (null = fora dela): o [ContadorDaCaminhada] do serviço soma o tempo andando; a tela só mostra. */
        val caminhada: StateFlow<Caminhada?> = _caminhada.asStateFlow()

        private val _ultimoPasso = MutableStateFlow(0L)

        /** Na caminhada: quando (SystemClock.elapsedRealtime) chegou o último sinal de "andando", ou a etapa começou. A vigia e a contagem pro bipe contam daqui. */
        val ultimoPasso: StateFlow<Long> = _ultimoPasso.asStateFlow()

        private val _ondasMudas = MutableStateFlow(false)

        /** As ondas do prazo pra sair estão mudas (o botão na tela da caminhada). Toda manhã começa ligada: o dono desliga se quiser. */
        val ondasMudas: StateFlow<Boolean> = _ondasMudas.asStateFlow()

        private val _camera = MutableStateFlow<CameraDaMissao?>(null)

        /** A câmera frontal da missão, do serviço (aberta depois do bom dia no "câmera + frases"; null fora disso): a tela liga o visor nela. */
        val camera: StateFlow<CameraDaMissao?> = _camera.asStateFlow()

        private val _leituraDaCamera = MutableStateFlow(LeituraDaCamera(Leitura.SEM_ROSTO, null, 0L))

        /** O que a câmera do serviço viu por último: a tela pinta o círculo e o aviso com isto. */
        val leituraDaCamera: StateFlow<LeituraDaCamera> = _leituraDaCamera.asStateFlow()

        private val _sentidos = MutableStateFlow<SentidosDaCaminhada?>(null)

        /**
         * O GPS, os satélites e o acelerômetro da manhã com caminhada, do serviço (ligados no STOP, pra o GPS acertar a
         * mira durante a conversa; null fora dela): a tela da caminhada desenha a leitura e a trilha daqui. No serviço, e
         * não na tela, pra caminhada seguir com a tela fechada ou o celular no bolso (serviço com o tipo "location").
         */
        val sentidos: StateFlow<SentidosDaCaminhada?> = _sentidos.asStateFlow()

        private val _miniTarefa = MutableStateFlow<MiniTarefa?>(null)

        /** A mini tarefa do prazo pra sair oferecida agora (null = nenhuma): a tela mostra a frase e, na vez da pessoa, o que o reconhecedor do serviço ouve. */
        val miniTarefa: StateFlow<MiniTarefa?> = _miniTarefa.asStateFlow()

        private val _temposDoAlarme = MutableStateFlow(TEMPOS_PADRAO)

        /**
         * Os tempos do alarme tocando: a foto dos ajustes na hora em que ele tocou ([EmAndamento.tempos]), não os ajustes
         * de agora. A tela do alarme mede a câmera por aqui; mexer nos ajustes com o alarme tocando não muda a missão.
         */
        val temposDoAlarme: StateFlow<Tempos> = _temposDoAlarme.asStateFlow()

        /** Toca o alarme em andamento (ou atualiza o que já toca). */
        fun tocar(ctx: Context) {
            // Só falha se o disparo veio sem alarme exato (Android 12 sem a permissão): aí o sistema
            // não deixa iniciar serviço em segundo plano e não há o que fazer
            runCatching { ContextCompat.startForegroundService(ctx, comando(ctx, ACAO_TOCAR)) }
                .onFailure { Log.e(TAG, "Serviço: o Android não deixou começar a tocar", it) }
        }

        /**
         * Defeito da câmera, ou a saída de emergência pelo cabo: fim do alarme, sem comemoração. O anel completo e a
         * caminhada completa terminam aqui dentro mesmo ([concluir]), sem passar por este comando.
         */
        fun encerrarSemMissao(ctx: Context) { ctx.startService(comando(ctx, ACAO_ENCERRAR_SEM_MISSAO)) }

        /** Botão DESLIGAR. Só vale pra alarme sem missão (ou liberado por defeito). */
        fun desligar(ctx: Context) { ctx.startService(comando(ctx, ACAO_DESLIGAR)) }

        /** A tela do alarme apareceu ou sumiu (a AlarmeActivity avisa em onStart e onStop). */
        fun tela(ctx: Context, aberta: Boolean) {
            if (_tocando.value != null) ctx.startService(comando(ctx, ACAO_TELA).putExtra(EXTRA_ABERTA, aberta))
        }

        /** A pessoa disse "stop": cala a música sem encerrar o alarme e liga a vigia. */
        fun silenciar(ctx: Context) { ctx.startService(comando(ctx, ACAO_SILENCIAR)) }

        /** Volume da música, de 0 a 1, relativo ao volume de alarme. */
        fun volume(ctx: Context, fator: Float) { ctx.startService(comando(ctx, ACAO_VOLUME).putExtra(EXTRA_VOLUME, fator)) }

        /** A pessoa vai desbloquear o celular ou liberar a câmera: por [TEMPO_FORA_MS] a tela do alarme não reabre. */
        fun darTempo(ctx: Context) { ctx.startService(comando(ctx, ACAO_DAR_TEMPO)) }


        /** O botão das ondas na tela da caminhada: [ligadas] ou mudas, nesta manhã. */
        fun ondas(ctx: Context, ligadas: Boolean) { ctx.startService(comando(ctx, ACAO_ONDAS).putExtra(EXTRA_LIGADAS, ligadas)) }

        /** Os botões "Back" e "Forward" de um alarme de teste: a manhã pula pra [fase] ([FaseDoTeste]). Num alarme de verdade, nada. */
        fun irPraFase(ctx: Context, fase: FaseDoTeste) { ctx.startService(comando(ctx, ACAO_FASE).putExtra(EXTRA_FASE, fase.name)) }

        /** O botão "Cancel test" (depois da confirmação): o alarme de teste acaba agora, sem comemoração. Num alarme de verdade, nada. */
        fun cancelarTeste(ctx: Context) { ctx.startService(comando(ctx, ACAO_CANCELAR_TESTE)) }

        private fun comando(ctx: Context, acao: String) = Intent(ctx, AlarmeService::class.java).setAction(acao)
    }

    private lateinit var sirene: Sirene
    private var voz: Voz? = null // só depois do primeiro desbloqueio ([prepararVoz])
    private var rodadas = 0 // saudações pedidas: cada uma grava num arquivo seu
    private var esperaDesbloqueio: BroadcastReceiver? = null
    private lateinit var processador: PowerManager.WakeLock
    private var telaAberta = true
    private var foraAte = 0L // SystemClock.elapsedRealtime
    private var bipes = 0 // da vigia, desde o último sinal de "olhando"
    private var cumprido = false // depois disso, só a comemoração: nada traz a música de volta
    private val frasesDeHoje by lazy { frasesDoDia(LocalDate.now(), tempos().frases) } // lido na primeira vez, já com os tempos do alarme
    private var apitos = 0 // da verificação, desde a última fala da pessoa
    private var vezComecou = 0L // SystemClock.elapsedRealtime em que começou a vez da pessoa na frase atual
    private var rodadaVerificacao = 0 // muda ao começar ou cancelar: sons atrasados de uma rodada velha não avançam a nova
    private var volumeAoFim: Int? = null // o volume de antes do alarme cumprido, devolvido ao fechar
    private val handler = Handler(Looper.getMainLooper())

    // Despertador "conversa + caminhada" ([manhaNova]). O caminho, a etapa e o progresso ficam quando a música
    // volta, como o anel da câmera; só um alarme novo começa do zero ([esquecerManha])
    private var caminho: CaminhoDaManha? = null // decidido depois do primeiro bom dia
    private var etapa: EtapaDaManha? = null
    private var motor: MotorDaConversa? = null // a IA da conversa, com o histórico
    private var iaBloqueada = false // o app já caiu neste alarme: a IA do celular (que pesa) fica de fora
    private var abertura: String? = null // a primeira fala da IA (null = não veio)
    private var aberturaChegou = false
    private var saudacaoTerminou = false // o bom dia deste STOP já acabou
    private var rodadaConversa = 0 // muda ao pausar ou acabar: notícias, IA e voz atrasados não avançam a manhã
    private var conversadoMs = 0L // tempo de conversa pura
    private var extras = 0 // vezes que a pessoa quis conversar mais (só uma)
    private var falhas = 0 // vezes seguidas sem resposta da IA
    private var esperaDesde = 0L // SystemClock.elapsedRealtime em que começou a espera pela fala da IA desta vez
    private var ultimoPedido: PedidoDaIa? = null // pra pedir de novo numa falha
    private var pedidosDaIa = 0 // muda a cada pedido e quando ele responde ou vence o prazo: o segundo dos dois não vale
    private var respostaSemRetorno = "" // a fala da pessoa que veio antes do "quer continuar?"
    private var iaFalando = false // a voz da IA está tocando: o relógio da conversa anda
    private var vezDaConversa = 0L // SystemClock.elapsedRealtime em que começou a vez da pessoa
    private var ouvidoEm = 0L // a última fala ouvida na vez da pessoa
    private var escuta: Escuta? = null // o microfone e o reconhecedor da conversa, do começo ao fim dela
    private var textoDaVez = "" // o que a pessoa disse desde a última fala da IA (a [escuta] manda tudo junto)
    private var fazendoSom = false // ela está fazendo som agora (o detector da [escuta]), e o som é dela ([somDaPessoa])
    private var nivelDaPessoa: Float? = null // o volume da voz dela (dB), dos sons que viraram palavra ([nivelDaVoz])
    private var provisoria: Provisoria? = null // a resposta da IA pro que ela disse, antes de a voz começar
    private var vezDaIa = 0 // muda a cada resposta jogada fora: a voz dela, se ainda estiver gravando, não toca
    private var bipesDaConversa = 0 // do silêncio, desde a última fala
    private var falasDaIa = 0 // cada fala da IA grava num arquivo seu
    private var ultimoTique = 0L
    private var andado = 0L // tempo andando na caminhada
    private var prazoAte = 0L // fim do prazo pra sair de casa (SystemClock.elapsedRealtime; 0 = ainda não começou)
    private var bipesParado = 0 // da vigia da caminhada, desde o último sinal de "andando"
    private var cameraDoServico: CameraDaMissao? = null // a câmera frontal da missão (o mesmo objeto que [camera] mostra)
    private var ouvinteDaFrase: Ouvinte? = null // o reconhecedor da verificação do meio, na vez da pessoa
    private var ultimoTiqueDaCamera = 0L // do relógio da câmera ([tiqueDaCamera])
    private var ultimoAvisoDaCamera = 0L
    private var olhadoNoUltimoAviso = 0L
    private var olhavaAntes = false
    private var sentidosDoServico: SentidosDaCaminhada? = null // o GPS e os passos da caminhada (o mesmo objeto que [sentidos] mostra)
    private var contador: ContadorDaCaminhada? = null // o relógio da caminhada, sobre os sentidos do serviço
    private var ouvinteDaMiniTarefa: Ouvinte? = null // o reconhecedor da mini tarefa do prazo, na vez da pessoa
    private var ultimoTextoDaNotificacao = "" // só manda a notificação de novo quando o texto muda
    private var tiposDoServico = 0 // os tipos do serviço que o Android deu ([mostrarNotificacao])
    private var tiposPedidos = 0 // os do último pedido ao Android, e quando foi ([atualizarTipos]: o mesmo pedido, recusado, espera)
    private var tiposPedidosEm = 0L
    private var naoEntendidos = 0 // falas seguidas da pessoa que a IA não entendeu (ruído, português)
    private var ultimaFalaEntendida = "" // a última fala de verdade da IA: a que ela repete depois do bipe do não entendi
    private var preparoExtraUsado = false // a mini tarefa do prazo já deu o extra desta manhã
    private var miniTarefaRetirada = false // ele andou depois do primeiro bipe: a mini tarefa não aparece mais
    private val fraseDaMiniTarefa by lazy { fraseDaMiniTarefa(LocalDate.now()) }
    private val transcricao = mutableListOf<Turno>() // a conversa de hoje, pras anotações da IA no fim ([MotorDaConversa.anotar])

    /** Os tempos deste alarme ([Tempos]): a foto dos ajustes de quando ele tocou ([EmAndamento.tempos]), lida em [onCreate] e [iniciar]. */
    private fun tempos(): Tempos = _temposDoAlarme.value

    /** O motor da IA e o modelo fora da memória (a manhã não vai mais ter conversa). */
    private fun soltarIa() {
        motor?.desligar()
        motor = null
        MotorDaConversa.liberar()
    }

    /**
     * Enquanto toca: empurra a retomada pra frente (se o app morrer, ela dispara e o alarme volta),
     * mantém o processador acordado (quem acende a tela é a AlarmeActivity) e põe a vigia do desligar de volta na
     * lista, se alguém a tirou à mão nas Configurações.
     */
    private val renovar = object : Runnable {
        override fun run() {
            Agendador.agendarRetomada(this@AlarmeService)
            VigiaDoDesligar.ligar(this@AlarmeService) // já ligada: só lê a lista, não escreve nada
            processador.acquire(RENOVAR_MS * 12)
            salvarProgresso()
            atualizarNotificacao()
            atualizarTipos()
            handler.postDelayed(this, RENOVAR_MS)
        }
    }

    /** Até quando o diagnóstico já foi gravado no arquivo (epoch, ms): o próximo serviço começa daí ([gravarDiagnostico]). */
    private var ultimaGravacao = 0L

    /** O último [Progresso] gravado: só grava de novo quando muda. */
    private var progressoGravado: Progresso? = null

    /** Grava onde a missão está ([Ajustes.progresso]): se o app morrer ou o celular desligar, o alarme volta daqui. */
    private fun salvarProgresso() {
        val em = Ajustes.emAndamento ?: return
        val p = Progresso(
            desde = em.desde,
            olhadoMs = _olhado.value,
            metadeVerificada = _metadeVerificada.value,
            caminho = caminho?.name,
            etapa = etapa?.name,
            conversadoMs = conversadoMs,
            extras = extras,
            andadoMs = andado,
            prazoRestanteMs = if (prazoAte == 0L) null else (prazoAte - SystemClock.elapsedRealtime()).coerceAtLeast(0),
            preparoExtraUsado = preparoExtraUsado,
            miniTarefaRetirada = miniTarefaRetirada,
        )
        if (p != progressoGravado) {
            Ajustes.progresso = p
            progressoGravado = p
        }
    }

    /**
     * O app voltou no meio deste alarme (morreu, ou o celular desligou): a missão continua de onde estava, depois do
     * STOP de novo. A conversa com a IA não volta (o modelo pode ter sido o motivo): vai direto pra caminhada.
     */
    private fun retomarProgresso(p: Progresso) {
        Log.i(TAG, "Serviço: continuando de onde parou (anel ${p.olhadoMs / 1000} s, etapa ${p.etapa}, ${p.conversadoMs / 1000} s de conversa, ${p.andadoMs / 1000} s andados)")
        _olhado.value = p.olhadoMs
        _metadeVerificada.value = p.metadeVerificada
        caminho = p.caminho?.let { nome -> CaminhoDaManha.entries.firstOrNull { it.name == nome } }
        etapa = p.etapa?.let { nome -> EtapaDaManha.entries.firstOrNull { it.name == nome } }
        conversadoMs = p.conversadoMs
        extras = p.extras
        andado = p.andadoMs
        prazoAte = p.prazoRestanteMs?.let { SystemClock.elapsedRealtime() + it } ?: 0L
        preparoExtraUsado = p.preparoExtraUsado
        miniTarefaRetirada = p.miniTarefaRetirada
        progressoGravado = p
    }

    /**
     * Vigia da missão, com a música calada: a cada [AVISO_SEM_OLHAR_MS] sem sinal de que a pessoa
     * olha, um bipe mais alto que o anterior; em [DESISTENCIA_MS], o anel zera e a música volta.
     */
    private val vigia = object : Runnable {
        override fun run() {
            if (bipes < BIPES_ATE_ZERAR) {
                bipes++
                Log.i(TAG, "Missão: ${bipes * AVISO_SEM_OLHAR_MS / 1000} s sem olhar pra câmera, bipe $bipes")
                sirene.bipe(bipes)
                // O próximo bipe; depois do último, o resto do tempo até zerar
                handler.postDelayed(this, if (bipes < BIPES_ATE_ZERAR) AVISO_SEM_OLHAR_MS else DESISTENCIA_MS - bipes * AVISO_SEM_OLHAR_MS)
                return
            }
            Log.i(TAG, "Missão: ${DESISTENCIA_MS / 1000} s sem olhar pra câmera, anel zera (estava em ${_olhado.value / 1000} s) e a música volta")
            zerarAnel()
            religarMusica()
            _tocando.value?.let { mostrarNotificacao(it) } // alerta de novo
        }
    }

    /**
     * A tela do alarme sumiu e não voltou: música de volta e tela reaberta por uma nova notificação em
     * tela cheia. O anel da câmera fica onde estava (com 40 min, uma ligação custaria tudo).
     */
    private val chamarDeVolta = Runnable {
        val alarme = _tocando.value ?: return@Runnable
        Log.i(TAG, "Tela do alarme fechada: música volta e a tela reabre (anel guardado em ${_olhado.value / 1000} s)")
        religarMusica()
        notificacoes().notify(Notificacoes.ID_CHAMADA, criarNotificacao(alarme))
    }

    /** O log do app gravado num arquivo enquanto o serviço vive ([gravarDiagnostico]). */
    private var gravadorDoLog: Process? = null

    override fun onCreate() {
        super.onCreate()
        // Os tempos do alarme antes de tudo: as frases de hoje (gravadas pela voz logo abaixo) seguem a foto dele
        Ajustes.emAndamento?.let { _temposDoAlarme.value = it.tempos }
        sirene = Sirene(this)
        prepararVoz()
        processador = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlarmeImplacavel:tocando")
            .apply { setReferenceCounted(false) }
    }

    /**
     * A voz (falas do alarme) abre só com o celular desbloqueado desde que ligou: antes disso o
     * sintetizador não abre e os arquivos não podem ser gravados. Alarme tocando antes do primeiro
     * desbloqueio: a voz abre no desbloqueio, que a missão pede antes do STOP (EtapaDesbloquear).
     */
    private fun prepararVoz() {
        if (getSystemService(UserManager::class.java).isUserUnlocked) {
            voz = Voz(this, falasFixas())
            return
        }
        esperaDesbloqueio = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                unregisterReceiver(this)
                esperaDesbloqueio = null
                if (voz == null) voz = Voz(this@AlarmeService, falasFixas())
                // A pasta do app só existe depois do desbloqueio: o diagnóstico começa agora, com o que já houve
                if (_tocando.value != null) gravarDiagnostico()
            }
        }.also { ContextCompat.registerReceiver(this, it, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    /**
     * As falas fixas mais as frases de hoje da verificação; no despertador "conversa + caminhada", também as da
     * conversa e da caminhada (a de sair diz o prazo dos ajustes). Se um desses juntar depois, as falas dela não
     * estão gravadas: vão só na tela.
     */
    private fun falasFixas(): Map<String, String> {
        val alarme = Ajustes.emAndamento?.alarme
        val daConversa = if (alarme != null && comConversaECaminhada(alarme)) {
            mapOf(
                FALA_DE_CONTINUAR to FALA_CONTINUAR,
                FALA_DE_SAIR to falaDeSair(prazoSair(alarme, tempos())),
                FALA_DE_NAO_NEGOCIAR to FALA_NAO_NEGOCIA,
                FALA_DE_PROCURAR to FALA_PROCURAR,
                FALA_DA_MINI_TAREFA to falaDaMiniTarefa(fraseDaMiniTarefa, preparoExtra(alarme)),
                FALA_DE_MAIS_TEMPO to falaMaisTempo(preparoExtra(alarme)),
                FALA_DE_NAO_ENTENDER to FALA_NAO_ENTENDI,
            )
        } else {
            emptyMap()
        }
        return FALAS_FIXAS + daConversa + frasesDeHoje.mapIndexed { i, frase -> "frase_$i" to frase }
    }

    /**
     * Enquanto um alarme toca, o log do app (só o dele, tag [TAG]) também vai pra um arquivo na pasta do app
     * (Android/data/com.implacavel.alarme/files/diagnostico.log, mais os antigos .1 a .4, de 1 MB cada). O log do
     * Android some quando o celular reinicia; o arquivo fica, e dá pra ler depois pelo cabo (veja o README). Começa
     * com o que o app logou desde [ultimaGravacao] (no primeiro, o último minuto), pra não repetir linhas. Antes do
     * primeiro desbloqueio a pasta não existe: começa no desbloqueio ([prepararVoz]). Sem as falas da conversa nem
     * coordenadas: o log nunca teve isso.
     */
    private fun gravarDiagnostico() {
        if (gravadorDoLog?.isAlive == true) return
        val pasta = getExternalFilesDir(null) ?: return
        val desde = maxOf(ultimaGravacao, System.currentTimeMillis() - 60_000)
        val inicio = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(desde))
        gravadorDoLog = runCatching {
            ProcessBuilder("logcat", "-v", "time", "-s", "$TAG:V", "-T", inicio, "-f", File(pasta, "diagnostico.log").path, "-r", "1024", "-n", "4").start()
        }.onFailure { Log.w(TAG, "Serviço: não deu pra gravar o diagnóstico", it) }.getOrNull()
        ultimaGravacao = System.currentTimeMillis()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val acao = intent?.action
        if (acao in setOf(ACAO_TOCAR, ACAO_ENCERRAR_SEM_MISSAO, ACAO_DESLIGAR, ACAO_SILENCIAR, ACAO_DAR_TEMPO)) Log.i(TAG, "Serviço: comando $acao")
        val alarme = _tocando.value
        when {
            acao == ACAO_TOCAR -> iniciar()
            // Comando atrasado, de quando o alarme já tinha parado: só fecha o serviço
            alarme == null -> pararServico()
            acao == ACAO_ENCERRAR_SEM_MISSAO -> concluir(comemorar = false)
            // Nem repetindo o DESLIGAR de outro alarme (a notificação sempre traz o mesmo) dá pra fugir da missão
            acao == ACAO_DESLIGAR -> if (!alarme.missao) concluir()
            // Desde o Android 14 dá pra arrastar a notificação pro lado; ela volta enquanto o alarme durar
            acao == ACAO_REEXIBIR -> mostrarNotificacao(alarme)
            acao == ACAO_TELA -> telaMudou(intent.getBooleanExtra(EXTRA_ABERTA, true))
            acao == ACAO_SILENCIAR -> silenciarMusica()
            // Com a tela fechada não há janela de escuta: a música fica no volume cheio
            acao == ACAO_VOLUME -> if (telaAberta) sirene.volume(intent.getFloatExtra(EXTRA_VOLUME, 1f))
            acao == ACAO_DAR_TEMPO -> foraAte = SystemClock.elapsedRealtime() + TEMPO_FORA_MS
            acao == ACAO_ONDAS -> ondas(intent.getBooleanExtra(EXTRA_LIGADAS, true))
            // Os botões de fase e de cancelar só existem na tela de um alarme de teste; um comando atrasado não mexe num alarme de verdade
            acao == ACAO_FASE -> if (alarme.deTeste) intent.getStringExtra(EXTRA_FASE)?.let { nome -> FaseDoTeste.entries.firstOrNull { it.name == nome } }?.let(::irPraFase)
            acao == ACAO_CANCELAR_TESTE -> if (alarme.deTeste && !cumprido) {
                Log.i(TAG, "Teste: cancelado pelo botão")
                concluir(comemorar = false)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Sem a missão cumprida, a retomada continua armada no AlarmManager e o alarme volta
        Log.i(TAG, "Serviço: parou")
        handler.removeCallbacksAndMessages(null)
        notificacoes().cancel(Notificacoes.ID_CHAMADA)
        // Fechado no meio da comemoração (ex.: o Android encerrou o serviço): o volume de antes volta igual
        sirene.desligar(volumeAntes = volumeAoFim.takeIf { cumprido })
        if (cumprido) Ajustes.volumeParaDevolver = null
        esperaDesbloqueio?.let { unregisterReceiver(it) }
        voz?.desligar()
        rodadaConversa++ // notícias e IA atrasadas não chegam em ninguém
        pararEscuta()
        desligarSentidos()
        desligarCamera()
        pararOuvinteDaFrase()
        motor?.desligar()
        MotorDaConversa.liberar()
        _silenciado.value = false
        _saudando.value = false
        _comemorando.value = false
        _verificacao.value = null
        _metadeVerificada.value = false
        _olhado.value = 0
        _conversa.value = null
        _caminhada.value = null
        _miniTarefa.value = null
        _tocando.value = null
        VigiaDoDesligar.desligar(this)
        _comCaminhada.value = false
        if (processador.isHeld) processador.release()
        // Um instante pras últimas linhas chegarem no arquivo
        gravadorDoLog?.let { p -> Handler(Looper.getMainLooper()).postDelayed({ p.destroy() }, 1_000) }
        super.onDestroy()
    }

    private fun iniciar() {
        val em = Ajustes.emAndamento
        // Comemorando um alarme cumprido: um comando atrasado ou outro alarme que disparou agora não
        // corta a comemoração; o outro alarme toca logo depois dela ([encerrar])
        if (cumprido) {
            mostrarNotificacao(_tocando.value ?: Alarme.teste())
            return
        }
        if (em == null) {
            // Missão cumprida enquanto o comando chegava. Depois de startForegroundService o Android
            // exige mostrar a notificação, mesmo pra parar em seguida
            mostrarNotificacao(Alarme.teste())
            pararServico()
            return
        }
        val quedas = quedasDesde(em.desde)
        val alarme = if (quedas >= LIMITE_QUEDAS) em.alarme.copy(missao = false) else em.alarme
        val jaTocava = _tocando.value != null
        // Alarme novo: nada da conversa e da caminhada de antes
        if (!jaTocava) esquecerManha()
        _tocando.value = alarme
        _temposDoAlarme.value = em.tempos
        VigiaDoDesligar.ligar(this) // só enquanto o alarme toca: o dia inteiro, os bancos estranham (veja VigiaDoDesligar.kt)
        mostrarNotificacao(alarme)
        sirene.preparar(em, comeco = !jaTocava)
        // Retomada à toa, ou outro alarme disparou no meio (a foto já juntou os dois): segue de onde está.
        // Se a meta subiu (alarme de verdade no lugar do teste), a verificação da metade vale de novo
        if (jaTocava) {
            ajustarManha(alarme)
            // O alarme que juntou é o "câmera + frases": a IA preparada durante a música (motor e modelo) não vai servir
            if (!comConversaECaminhada(alarme)) soltarIa() else if (caminho == null) prepararIa(alarme, em)
            if (_olhado.value < verificacaoEm(alarme, tempos())) _metadeVerificada.value = false
            return
        }
        gravarDiagnostico()
        Log.i(TAG, "Serviço: tocando \"${alarme.nome}\" (missão=${alarme.missao}, remédio=${alarme.remedio}, caminhada=${alarme.caminhada}, quedas=$quedas)")
        // Voltando no meio deste mesmo alarme: de onde parou. Alarme novo: progresso nenhum
        val salvo = Ajustes.progresso
        if (salvo?.desde == em.desde) retomarProgresso(salvo) else if (salvo != null) Ajustes.progresso = null
        // O modelo começa a carregar já, com a música, pra estar pronto no fim do bom dia (só se a manhã ainda não
        // decidiu o caminho). Se o app já saiu sozinho neste alarme (queda, travamento ou falta de memória), pode ter
        // sido ele: a manhã vai sem a conversa
        iaBloqueada = saiuSozinhoDesde(em.desde) || Ajustes.iaTentadaEm == em.desde
        if (caminho == null) prepararIa(alarme, em)
        telaAberta = true
        _silenciado.value = false
        _saudando.value = false
        _verificacao.value = null
        _metadeVerificada.value = false
        _conversa.value = null
        _caminhada.value = null
        _miniTarefa.value = null
        // O anel e a verificação do progresso retomado (os de cima zeraram a tela, não o progresso)
        if (salvo?.desde == em.desde) {
            _olhado.value = salvo.olhadoMs
            _metadeVerificada.value = salvo.metadeVerificada
        }
        sirene.tocar()
        handler.post(renovar)
    }

    /**
     * Começa a carregar o modelo da IA (o despertador "conversa + caminhada", sem a IA bloqueada) e grava que
     * ela foi tentada neste alarme ([Ajustes.iaTentadaEm]). Já carregado ou carregando, não faz nada de novo. Ainda
     * com a música, também busca as notícias e deixa a IA lendo a instrução ([MotorDaConversa.aquecer], uns 9 s): no
     * fim do bom dia, só falta a abertura (uns 3 s). Sem o motor pronto no STOP, [prepararManha] faz tudo de novo.
     */
    private fun prepararIa(alarme: Alarme, em: EmAndamento) {
        if (!comConversaECaminhada(alarme) || iaBloqueada || !MotorDaConversa.disponivel(this)) return
        Ajustes.iaTentadaEm = em.desde
        MotorDaConversa.preparar(this)
        if (motor != null) return
        val rodada = rodadaConversa
        Noticias.buscar { manchetes, metas ->
            if (rodada != rodadaConversa || caminho != null || motor != null || cumprido || _silenciado.value) return@buscar
            criarMotor(manchetes, metas)?.aquecer()
        }
    }

    /**
     * O app saiu sozinho desde [desde]: as quedas de [quedasDesde] ou o Android matando por falta de memória (o
     * modelo da IA usa uns 1,5 GB). Do Android 11 em diante.
     */
    private fun saiuSozinhoDesde(desde: Long): Boolean = saidasDesde(desde, faltaDeMemoria = true) > 0

    /** Quedas do app (erro, erro nativo ou travamento) desde [desde]. O Android só conta isso do 11 em diante. */
    private fun quedasDesde(desde: Long): Int = saidasDesde(desde, faltaDeMemoria = false)

    /** Saídas do app desde [desde] por queda (erro, erro nativo, travamento) e, com [faltaDeMemoria], também por ela. */
    private fun saidasDesde(desde: Long, faltaDeMemoria: Boolean): Int {
        if (Build.VERSION.SDK_INT < 30) return 0
        val quedas = setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)
        val motivos = if (faltaDeMemoria) quedas + ApplicationExitInfo.REASON_LOW_MEMORY else quedas
        return getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(packageName, 0, 0)
            .count { it.timestamp > desde && it.reason in motivos }
    }

    private fun telaMudou(aberta: Boolean) {
        if (cumprido) return
        Log.i(TAG, "Tela do alarme: ${if (aberta) "aberta" else "fechada"}")
        telaAberta = aberta
        handler.removeCallbacks(chamarDeVolta)
        if (aberta) {
            notificacoes().cancel(Notificacoes.ID_CHAMADA)
            atualizarTipos() // uma permissão dada nas Configurações, com a tela de volta na frente
        } else if (emSegundoPlano()) {
            // A conversa e a caminhada são do serviço (microfone, GPS e passos): seguem com a tela fechada, e a
            // pessoa pode usar outro app. A notificação mostra onde está e traz a tela de volta num toque
            Log.i(TAG, "Tela do alarme fechada: a ${if (_conversa.value != null) "conversa" else if (_caminhada.value != null) "caminhada" else "câmera"} segue em segundo plano")
            atualizarNotificacao()
        } else {
            sirene.volume(1f)
            // Quem foi desbloquear o celular ou liberar a câmera ganha um tempo (a música não para por isso)
            val espera = maxOf(TELA_FECHADA_MS, foraAte - SystemClock.elapsedRealtime())
            handler.postDelayed(chamarDeVolta, espera)
        }
    }

    /**
     * A etapa de agora vive no serviço e segue com a tela fechada: depois do bom dia, a câmera e a verificação do
     * "câmera + frases", e a conversa e a caminhada do "conversa + caminhada". A música e o bom dia ainda chamam a tela.
     */
    private fun emSegundoPlano(): Boolean =
        _silenciado.value && !_saudando.value && !cumprido && (!manhaNova() || _conversa.value != null || _caminhada.value != null)

    /**
     * A pessoa disse "stop": a música para e a voz dá bom dia ([textoDaSaudacao]); [PAUSA_DEPOIS_DA_SAUDACAO_MS]
     * depois que ela termina, diz [FALA_INICIO], toca o bipe positivo e aí começa a câmera e a vigia.
     * Sem voz, só a pausa e o bipe. No despertador "conversa + caminhada", o fim do bom dia vai pra
     * [saudacaoFeita], e enquanto a voz fala chegam as notícias e a abertura da IA ([prepararManha]).
     */
    private fun silenciarMusica() {
        if (cumprido) return
        if (_silenciado.value) return // toque repetido no botão, ou o mesmo STOP chegando de novo
        sirene.calar()
        _silenciado.value = true
        _saudando.value = true
        // Missão de 40 min já começada: um "conversa + caminhada" que juntar depois não muda o caminho
        if (manhaNova()) {
            prepararManha()
            ligarSentidos() // o GPS acerta a mira durante a conversa
        } else if (caminho == null) {
            caminho = CaminhoDaManha.ANTIGO
            soltarIa() // uma IA preparada durante a música não vai servir
        }
        _comCaminhada.value = manhaNova()
        // Com a tela do alarme na frente, o serviço ganha os tipos "location" e "microphone": é agora que o Android
        // deixa, e com eles a conversa e a caminhada seguem com a tela fechada
        _tocando.value?.let { mostrarNotificacao(it) }
        // Do bom dia ao fim da conversa, o alto-falante não dorme: a fala depois de um silêncio começaria baixinha
        if (manhaNova() && etapa != EtapaDaManha.CAMINHADA) sirene.manterAcordado(true)
        handler.postDelayed(comecarAtividade, SAUDACAO_MAX_MS)
        val falar: (File?) -> Unit = { arquivo ->
            if (_saudando.value) sirene.falar(arquivo) { handler.postDelayed(anunciarInicio, PAUSA_DEPOIS_DA_SAUDACAO_MS) }
        }
        voz?.gravar("saudacao_${++rodadas}", textoDaSaudacao(LocalTime.now()), falar) ?: falar(null)
    }

    /** "Activity starting now", o bipe positivo e a câmera. No despertador "conversa + caminhada", [saudacaoFeita] no lugar. */
    private val anunciarInicio: Runnable = Runnable {
        if (!_saudando.value) return@Runnable
        if (manhaNova()) {
            saudacaoFeita()
            return@Runnable
        }
        sirene.falar(voz?.fixa(FALA_DO_INICIO)) {
            if (_saudando.value) sirene.positivo { comecarAtividade.run() }
        }
    }

    /** Fim da saudação: a tela passa pra câmera e a vigia começa a contar. No despertador "conversa + caminhada", [saudacaoFeita] no lugar. */
    private val comecarAtividade: Runnable = Runnable {
        if (!_saudando.value) return@Runnable
        if (manhaNova()) {
            saudacaoFeita() // a voz travou: o prazo vale como o fim do bom dia
            return@Runnable
        }
        Log.i(TAG, "Missão: saudação feita, começa a câmera")
        sirene.manterAcordado(false) // a manhã virou a missão de 40 min antes de decidir (outro alarme juntou no bom dia)
        _saudando.value = false
        handler.removeCallbacks(comecarAtividade)
        handler.removeCallbacks(anunciarInicio)
        adiarVigia()
        comecarCamera()
    }

    /**
     * O relógio da câmera, a cada [PASSO_DA_CAMERA_MS], com a música calada e fora da verificação: vale o quadro atual
     * da câmera do serviço ([leituraDaCamera]; um quadro velho, câmera travada, é sem rosto); o anel ([olhado]) sobe só
     * olhando e, antes da verificação, para no fim do modo fácil; a cada [AVISO_OLHANDO_MS] olhando, e no instante em
     * que parou, [olhou] (a vigia recomeça, os avisos de 10 min, a verificação); com a meta, o alarme desliga. Era o
     * laço da tela da câmera: foi pro serviço pra câmera seguir com a tela fechada (10-22).
     */
    private val tiqueDaCamera = object : Runnable {
        override fun run() {
            handler.postDelayed(this, PASSO_DA_CAMERA_MS)
            val alarme = _tocando.value ?: return
            val agora = SystemClock.elapsedRealtime()
            val passo = agora - ultimoTiqueDaCamera
            ultimoTiqueDaCamera = agora
            if (!_silenciado.value || _saudando.value || _verificacao.value != null || manhaNova() || cumprido) return
            val l = _leituraDaCamera.value
            val velha = agora - l.quando > QUADRO_VELHO_MS
            val leitura = if (velha) Leitura.SEM_ROSTO else l.leitura
            // Vale o quadro atual: tirou o rosto ou fechou os olhos, o anel para na hora
            val olhando = leitura == Leitura.OLHANDO
            val meta = metaOlhar(alarme, tempos())
            val verificacao = verificacaoEm(alarme, tempos())
            val antes = _olhado.value
            if (olhando != olhavaAntes) {
                val olho = if (velha) "" else l.olhoAberto?.let { ", olho ${porcento(it)}%" }.orEmpty()
                Log.i(TAG, "Câmera: ${if (olhando) "olhando" else "parou de olhar ($leitura)"}$olho, anel ${antes * 100 / meta}%")
            }
            // Antes da verificação do meio, o anel para no fim do modo fácil
            val limite = if (_metadeVerificada.value) meta else verificacao
            val depois = minOf(avancarOlhar(antes, olhando, passo), maxOf(limite, antes))
            _olhado.value = depois
            // A cada segundo olhando, e no instante em que parou: a contagem pra zerar começa daí
            if (olhando && agora - ultimoAvisoDaCamera >= AVISO_OLHANDO_MS || olhavaAntes && !olhando) {
                olhou(olhadoNoUltimoAviso, depois)
                olhadoNoUltimoAviso = depois
                ultimoAvisoDaCamera = agora
            }
            olhavaAntes = olhando
            if (depois >= meta && _verificacao.value == null) {
                Log.i(TAG, "Missão: anel completo, alarme desligado")
                concluir(comemorar = true)
            }
        }
    }

    /**
     * Sinal de "olhando" do relógio da câmera, com o anel de [antes] pra [depois]: a contagem da vigia recomeça; a cada
     * 10 min completos ([marcoCompletado]), a voz avisa ("10 minutes passed"); no fim do modo fácil, a verificação.
     */
    private fun olhou(antes: Long, depois: Long) {
        if (!_silenciado.value || _saudando.value || _verificacao.value != null || _conversa.value != null || _caminhada.value != null || cumprido) return
        val alarme = _tocando.value ?: return
        val meta = metaOlhar(alarme, tempos())
        val verificacao = verificacaoEm(alarme, tempos())
        if (!_metadeVerificada.value && depois >= verificacao) return iniciarVerificacao()
        marcoCompletado(antes, depois, meta, verificacao)?.let { minutos ->
            Log.i(TAG, "Missão: $minutos min de câmera")
            sirene.falarDepoisDeAcordar(voz?.fixa("minutos_$minutos"))
        }
        adiarVigia()
    }

    /** A câmera da missão e o relógio dela começam (o bom dia acabou, no "câmera + frases"). */
    private fun comecarCamera() {
        handler.removeCallbacks(tiqueDaCamera)
        ultimoTiqueDaCamera = SystemClock.elapsedRealtime()
        olhadoNoUltimoAviso = _olhado.value
        olhavaAntes = false
        handler.post(tiqueDaCamera)
        ligarCamera()
    }

    /**
     * Abre a câmera do serviço, se a permissão está dada; sem ela, tenta de novo a cada segundo (a tela pede). Dada no
     * meio, o tipo "camera" do serviço vem primeiro ([atualizarTipos]); com a tela fechada e sem ele, espera a tela.
     */
    private fun ligarCamera() {
        if (cameraDoServico != null || cumprido) return
        handler.removeCallbacks(relerCamera)
        if (Poder.CAMERA.liberado(this)) atualizarTipos()
        if (!Poder.CAMERA.liberado(this) || !telaAberta && !cameraComTelaFechada()) {
            handler.postDelayed(relerCamera, RELER_LOCALIZACAO_MS)
            return
        }
        val c = CameraDaMissao(this, exigente = { _metadeVerificada.value }) { leitura, aberto ->
            _leituraDaCamera.value = LeituraDaCamera(leitura, aberto, SystemClock.elapsedRealtime())
        }
        cameraDoServico = c
        _camera.value = c
        c.ligar()
    }

    private val relerCamera = Runnable { if (_silenciado.value && !manhaNova()) ligarCamera() }

    /** Fecha a câmera do serviço e para o relógio dela (a música voltou, ou o alarme acabou). */
    private fun desligarCamera() {
        handler.removeCallbacks(relerCamera)
        handler.removeCallbacks(tiqueDaCamera)
        cameraDoServico?.desligar()
        cameraDoServico = null
        _camera.value = null
        _leituraDaCamera.value = LeituraDaCamera(Leitura.SEM_ROSTO, null, 0L)
    }

    /** O reconhecedor da verificação, se estava ouvindo, para. */
    private fun pararOuvinteDaFrase() {
        ouvinteDaFrase?.parar()
        ouvinteDaFrase = null
    }

    /** O anel zerou (sem olhar, ou sem responder na verificação): a verificação da metade vale de novo. */
    private fun zerarAnel() {
        _olhado.value = 0
        olhadoNoUltimoAviso = 0
        _metadeVerificada.value = false
    }

    /**
     * Fim do modo fácil da câmera: o anel para (a tela da câmera sai), toca o bipe leve, a voz diz [FALA_VERIFICAR]
     * e, [PAUSA_ANTES_DAS_FRASES_MS] depois, começam as frases. Com zero frases nos ajustes, só o agradecimento e o
     * modo exigente ([agradecer]).
     */
    private fun iniciarVerificacao() {
        Log.i(TAG, "Missão: fim do modo fácil da câmera, verificação com ${frasesDeHoje.size} frases")
        handler.removeCallbacks(vigia)
        val rodada = ++rodadaVerificacao
        if (frasesDeHoje.isEmpty()) return agradecer()
        _verificacao.value = Verificacao(frase = "", numero = 0, total = frasesDeHoje.size, suaVez = false)
        sirene.leve {
            sirene.falar(voz?.fixa(FALA_DE_VERIFICAR)) {
                if (rodada == rodadaVerificacao) handler.postDelayed({ if (rodada == rodadaVerificacao) falarFrase(0) }, PAUSA_ANTES_DAS_FRASES_MS)
            }
        }
    }

    /** A voz fala a frase [i] (a tela também mostra); quando termina, é a vez da pessoa repetir. */
    private fun falarFrase(i: Int) {
        val rodada = rodadaVerificacao
        _verificacao.value = Verificacao(frasesDeHoje[i], numero = i + 1, total = frasesDeHoje.size, suaVez = false)
        sirene.falar(voz?.fixa("frase_$i")) {
            if (rodada != rodadaVerificacao) return@falar
            _verificacao.value = _verificacao.value?.copy(suaVez = true)
            vezComecou = SystemClock.elapsedRealtime()
            esperarResposta()
            ouvirFrase(frasesDeHoje[i], rodada)
        }
    }

    /**
     * A vez da pessoa repetir a [frase]: o reconhecedor do serviço escuta em inglês até ela repetir ([repetiu]); a tela
     * mostra o que ele ouve ([Verificacao.ouvido]). Fala que não acerta a frase só segura a contagem do silêncio até o
     * prazo da frase. Sem reconhecimento de voz (ex.: inglês sem internet), a verificação é pulada. Era da tela: foi pro
     * serviço pra verificação seguir com a tela fechada (10-22).
     */
    private fun ouvirFrase(frase: String, rodada: Int) {
        pararOuvinteDaFrase()
        ouvinteDaFrase = Ouvinte(
            this, "frase", aceita = { repetiu(frase, it) },
            onOuviu = { ouvido ->
                if (rodada != rodadaVerificacao) return@Ouvinte
                _verificacao.value = _verificacao.value?.copy(ouvido = ouvido)
                if (_verificacao.value?.suaVez == true && SystemClock.elapsedRealtime() - vezComecou < PRAZO_FRASE_MS) esperarResposta()
            },
            onAceito = { if (rodada == rodadaVerificacao) repetiuFrase() },
            onIndisponivel = {
                if (rodada == rodadaVerificacao && _verificacao.value != null && !cumprido) {
                    Log.w(TAG, "Missão: sem reconhecimento de voz, a verificação é pulada")
                    handler.removeCallbacks(silencio)
                    pararOuvinteDaFrase()
                    agradecer()
                }
            },
            idioma = "en-US",
            juntar = true, // com pausa no meio da frase, as palavras já ditas continuam valendo
        ).also { it.comecar() }
    }

    /** A vez da pessoa (ou ela acabou de falar): a contagem sem resposta recomeça do zero. */
    private fun esperarResposta() {
        _ultimaResposta.value = SystemClock.elapsedRealtime()
        apitos = 0
        handler.removeCallbacks(silencio)
        handler.postDelayed(silencio, SILENCIO_APITO_MS)
    }

    /** Sem resposta na verificação: um apito alto a cada [SILENCIO_APITO_MS] e, em [SILENCIO_ZERA_MS], zera como a vigia. */
    private val silencio = object : Runnable {
        override fun run() {
            if (apitos < (SILENCIO_ZERA_MS / SILENCIO_APITO_MS).toInt() - 1) {
                apitos++
                Log.i(TAG, "Missão: ${apitos * SILENCIO_APITO_MS / 1000} s sem responder na verificação, apito $apitos")
                sirene.apito(apitos)
                handler.postDelayed(this, SILENCIO_APITO_MS)
                return
            }
            Log.i(TAG, "Missão: ${SILENCIO_ZERA_MS / 1000} s sem responder na verificação, anel zera e a música volta")
            zerarAnel()
            religarMusica()
            _tocando.value?.let { mostrarNotificacao(it) }
        }
    }

    /** A pessoa repetiu a frase da vez: o "plim" e a próxima frase, ou o agradecimento depois da última. */
    private fun repetiuFrase() {
        val v = _verificacao.value?.takeIf { it.suaVez } ?: return
        Log.i(TAG, "Missão: frase ${v.numero} de ${frasesDeHoje.size} repetida")
        pararOuvinteDaFrase()
        handler.removeCallbacks(silencio)
        _verificacao.value = v.copy(suaVez = false)
        val rodada = rodadaVerificacao
        sirene.acerto {
            if (rodada != rodadaVerificacao) return@acerto
            if (v.numero < frasesDeHoje.size) falarFrase(v.numero) else agradecer()
        }
    }

    /** Fim da verificação: a voz agradece ([FALA_METADE]) e a câmera volta, agora mais exigente. */
    private fun agradecer() {
        val rodada = rodadaVerificacao
        _verificacao.value = Verificacao(frase = "", numero = frasesDeHoje.size, total = frasesDeHoje.size, suaVez = false)
        sirene.falar(voz?.fixa(FALA_DA_METADE)) {
            if (rodada != rodadaVerificacao) return@falar
            Log.i(TAG, "Missão: verificação feita, a câmera agora é mais exigente com os olhos")
            _verificacao.value = null
            // A meta pode ter subido no meio (alarme de verdade tomou o lugar do teste): aí o modo fácil ainda não acabou
            _metadeVerificada.value = _olhado.value >= (_tocando.value?.let { verificacaoEm(it, tempos()) } ?: 0L)
            adiarVigia()
        }
    }

    /** Sinal de "olhando" (ou o "stop"): a contagem sem olhar recomeça do zero, sem bipes. */
    private fun adiarVigia() {
        _ultimoOlhar.value = SystemClock.elapsedRealtime()
        bipes = 0
        handler.removeCallbacks(vigia)
        handler.postDelayed(vigia, AVISO_SEM_OLHAR_MS)
    }

    /** Música de volta no volume cheio; se estava calada, a missão volta pra etapa de falar. O anel não muda aqui. */
    private fun religarMusica() {
        if (cumprido) return
        handler.removeCallbacks(vigia)
        // No meio da saudação: ela para (a música cala a fala) e a câmera não começa
        handler.removeCallbacks(comecarAtividade)
        handler.removeCallbacks(anunciarInicio)
        _saudando.value = false
        voz?.cancelar()
        // No meio da verificação: ela para; volta do começo na próxima vez que o anel chegar na metade
        rodadaVerificacao++
        pararOuvinteDaFrase()
        handler.removeCallbacks(silencio)
        _verificacao.value = null
        desligarCamera()
        // Na conversa ou na caminhada: a etapa para; o caminho e o progresso ficam pra depois do STOP
        pausarManha()
        if (_silenciado.value) {
            _silenciado.value = false
            sirene.tocar()
        } else {
            sirene.volume(1f)
        }
        // Com a tela fechada (a etapa seguia em segundo plano), a música de volta pede a tela: só nela se diz STOP
        if (!telaAberta) _tocando.value?.let { notificacoes().notify(Notificacoes.ID_CHAMADA, criarNotificacao(it)) }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Despertador "conversa + caminhada": depois do bom dia, a conversa com a IA ([Tempos.conversaMin] de conversa
    // pura, 10 por padrão) e a caminhada ([Tempos.prepararMin] pra sair e [Tempos.caminhadaMin] andando: 4 e 10 por
    // padrão), chova ou faça sol. Tudo pelos relógios daqui: a IA só produz o
    // texto das falas e nunca encurta, pausa ou acaba nada.

    /** O [alarme] é o despertador "conversa + caminhada" (só vale com missão). */
    private fun comConversaECaminhada(alarme: Alarme) = alarme.missao && alarme.caminhada

    /** O alarme tocando é o despertador "conversa + caminhada", e esta manhã não virou a missão de 40 min. */
    private fun manhaNova(): Boolean = _tocando.value?.let { comConversaECaminhada(it) } == true && caminho != CaminhoDaManha.ANTIGO

    /**
     * No STOP, enquanto a voz dá bom dia: na primeira vez, pede a abertura da IA (o motor já pronto desde a música,
     * [prepararIa], ou, sem ele, busca as notícias e as metas e cria um). Sem a IA ([MotorDaConversa.disponivel]),
     * não busca nada: a manhã vai direto pra caminhada. Com o caminho já decidido (a música voltou no meio), também não.
     */
    private fun prepararManha() {
        saudacaoTerminou = false
        if (caminho != null) return
        abertura = null
        aberturaChegou = false
        if (iaBloqueada || !MotorDaConversa.disponivel(this)) {
            Log.i(TAG, "Conversa: ${if (iaBloqueada) "o app já caiu neste alarme" else "sem a IA no celular"}, direto pra caminhada")
            motor?.desligar()
            motor = null
            MotorDaConversa.liberar()
            aberturaChegou = true // sem abertura: o [decidir] escolhe só a caminhada
            return
        }
        Ajustes.emAndamento?.let { Ajustes.iaTentadaEm = it.desde }
        val rodada = rodadaConversa
        val pedirAbertura = { ia: MotorDaConversa ->
            ia.abrir { fala ->
                if (rodada != rodadaConversa) return@abrir
                abertura = fala
                aberturaChegou = true
                decidir()
            }
        }
        motor?.let { return pedirAbertura(it) }
        Noticias.buscar { manchetes, metas ->
            if (rodada != rodadaConversa) return@buscar
            val ia = criarMotor(manchetes, metas)
            if (ia == null) { // o motor não abriu: sem abertura, só a caminhada
                aberturaChegou = true
                return@buscar decidir()
            }
            pedirAbertura(ia)
        }
    }

    /**
     * O motor da IA desta manhã, com a instrução feita das [manchetes], das [metas], do que o dono contou de si e das
     * anotações da última conversa, se ainda valem ([Ajustes.memoriaDaIaValida]); null sem o modelo. Quando ela pede uma busca, a tela
     * mostra o que ela procura e a voz avisa ([FALA_PROCURAR]), pra espera não parecer travada.
     */
    private fun criarMotor(manchetes: List<String>, metas: String): MotorDaConversa? {
        val memoria = Ajustes.memoriaDaIaValida() // só a última conversa, e só por um dia
        val instrucao = instrucaoDoTreinador(manchetes, metas, LocalTime.now(), Ajustes.sobreMim.value, memoria)
        val ia = MotorDaConversa.criar(this, instrucao, pedidoDeAbertura(manchetes, memoria), FALA_NAO_NEGOCIA) ?: return null
        // O mesmo motor segue depois de a música voltar (a rodada muda): basta a conversa estar esperando a IA
        ia.aoProcurar = { consulta ->
            val c = _conversa.value
            if (motor === ia && c != null && c.pensando) {
                Log.i(TAG, "Conversa: a IA está procurando na internet")
                _conversa.value = c.copy(procurando = consulta)
                falarNaConversa(voz?.fixa(FALA_DE_PROCURAR)) {}
            }
        }
        motor = ia
        return ia
    }

    /**
     * Fim do bom dia (e da pausa depois dele). Na primeira vez, decide o caminho assim que a abertura da IA chegar
     * (até [PREPARO_MAX_MS]; a tela segue no bom dia); depois de a música voltar, a manhã continua de onde estava.
     */
    private fun saudacaoFeita() {
        if (saudacaoTerminou) return // o fim do bom dia e o prazo dele chegando os dois
        saudacaoTerminou = true
        if (caminho != null) return continuarManha()
        handler.postDelayed(esperaDaDecisao, PREPARO_MAX_MS)
        decidir()
    }

    /** A abertura da IA não chegou a tempo: a manhã vai sem a conversa, direto pra caminhada. */
    private val esperaDaDecisao = Runnable {
        if (caminho != null) return@Runnable
        Log.w(TAG, "Conversa: a abertura da IA não chegou em ${PREPARO_MAX_MS / 1000} s")
        rodadaConversa++ // o que chegar atrasado não vale mais
        aberturaChegou = true
        abertura = null
        decidir()
    }

    /**
     * Bom dia feito e a abertura da IA na mão (ou a certeza de que ela não vem): o caminho da manhã. Com a abertura,
     * a conversa e depois a caminhada; sem, direto pra caminhada. Um "câmera + frases" que tomou o lugar do teste
     * (EmAndamento.juntar) vira a missão dele, de 40 min.
     */
    private fun decidir() {
        if (!saudacaoTerminou || caminho != null || !aberturaChegou) return
        val alarme = _tocando.value ?: return
        handler.removeCallbacks(esperaDaDecisao)
        val primeira = abertura
        val escolhido = when {
            !comConversaECaminhada(alarme) -> CaminhoDaManha.ANTIGO
            primeira != null -> CaminhoDaManha.CONVERSA_E_CAMINHADA
            else -> CaminhoDaManha.SO_CAMINHADA
        }
        caminho = escolhido
        _comCaminhada.value = manhaNova()
        Log.i(TAG, "Conversa: caminho da manhã $escolhido")
        if (escolhido == CaminhoDaManha.CONVERSA_E_CAMINHADA && primeira != null) {
            etapa = EtapaDaManha.CONVERSA
            return continuarManha(primeira)
        }
        motor?.desligar()
        motor = null
        MotorDaConversa.liberar()
        if (escolhido == CaminhoDaManha.SO_CAMINHADA) return irPraCaminhada()
        // A missão de 40 min, com o mesmo prazo se a voz travar (e o alto-falante como no alarme de sempre)
        sirene.manterAcordado(false)
        handler.removeCallbacks(comecarAtividade)
        handler.postDelayed(comecarAtividade, SAUDACAO_MAX_MS)
        anunciarInicio.run()
    }

    /** A etapa da manhã começa, ou volta depois do STOP e do bom dia: a conversa (com a [primeira] fala da IA, ou a IA retomando) ou a caminhada. */
    private fun continuarManha(primeira: String? = null) {
        handler.removeCallbacks(comecarAtividade)
        handler.removeCallbacks(anunciarInicio)
        _saudando.value = false
        when (etapa) {
            EtapaDaManha.CONVERSA -> abrirConversa(primeira)
            EtapaDaManha.CAMINHADA, null -> comecarCaminhada()
        }
    }

    /** Quanto de conversa a manhã pede agora: a meta do [alarme] (pelos ajustes), mais o tempo extra se a pessoa quis continuar. */
    private fun metaDaConversa(alarme: Alarme) = metaConversa(alarme, tempos()) + extras * extraDaConversa(alarme)

    /**
     * A conversa começa com a [primeira] fala da IA ou, sem ela, volta depois da música, com a IA retomando
     * ([MotorDaConversa.retomar]). A [escuta] liga já, e fica ligada até a conversa acabar ou a música voltar.
     */
    private fun abrirConversa(primeira: String?) {
        val alarme = _tocando.value ?: return
        Log.i(TAG, "Conversa: ${if (primeira != null) "começa" else "volta"}, ${conversadoMs / 1000} s de ${metaDaConversa(alarme) / 1000}")
        falhas = 0
        _conversa.value = Conversa(falaDaIa = "", suaVez = false, pensando = true, conversadoMs = conversadoMs, metaMs = metaDaConversa(alarme))
        if (Build.VERSION.SDK_INT < 33) return terminarConversa("o reconhecimento de voz da conversa pede o Android 13")
        val rodada = rodadaConversa
        escuta?.parar()
        escuta = Escuta(
            this,
            onSom = { comecou, db -> if (rodada == rodadaConversa) somNaConversa(comecou, db) },
            onTexto = { if (rodada == rodadaConversa) textoNaConversa(it) },
            onIndisponivel = { if (rodada == rodadaConversa) terminarConversa("sem reconhecimento de voz") },
        ).also { if (!it.comecar()) return }
        ultimoTique = SystemClock.elapsedRealtime()
        handler.removeCallbacks(tiqueDaConversa)
        handler.postDelayed(tiqueDaConversa, TIQUE_CONVERSA_MS)
        if (primeira != null) {
            // No 3º "não entendi" seguido, é ela que volta (a pergunta de abertura), e não outro "não entendi"
            ultimaFalaEntendida = primeira
            naoEntendidos = 0
            return falarDaIa(primeira)
        }
        val ia = motor ?: return terminarConversa("sem a IA")
        pedirAIa { ia.retomar(it) }
    }

    /**
     * O relógio da conversa, a cada [TIQUE_CONVERSA_MS]: soma o tempo de conversa pura ([conversaAnda]: a voz da
     * IA tocando, ou a pessoa falando) e, na vez dela, toca os bipes do silêncio ([bipesDoSilencio]), sem zerar
     * nada nem trazer a música. Na pergunta "quer continuar?", nem relógio nem bipe: calado por
     * [SILENCIO_CONVERSA_MS], vale como não.
     */
    private val tiqueDaConversa = object : Runnable {
        override fun run() {
            val c = _conversa.value ?: return
            val agora = SystemClock.elapsedRealtime()
            val passo = agora - ultimoTique
            ultimoTique = agora
            handler.postDelayed(this, TIQUE_CONVERSA_MS)
            val calado = agora - _ultimaFala.value
            if (c.perguntandoSeContinua) {
                if (c.suaVez && calado >= SILENCIO_CONVERSA_MS) terminarConversa("${SILENCIO_CONVERSA_MS / 1000} s sem responder se quer continuar")
                return
            }
            conversadoMs = avancarConversa(conversadoMs, conversaAnda(iaFalando, c.suaVez, agora - vezDaConversa, agora - ouvidoEm), passo)
            if (conversadoMs != c.conversadoMs) _conversa.value = c.copy(conversadoMs = conversadoMs)
            if (!c.suaVez) return
            val n = bipesDoSilencio(calado)
            if (n > bipesDaConversa) {
                bipesDaConversa = n
                Log.i(TAG, "Conversa: ${calado / 1000} s calado na sua vez, bipe $n")
                escuta?.surdoPor(sirene.bipe(n) + ATRASO_DO_BIPE_MS)
            }
        }
    }

    /** Pede a próxima fala da IA ([pedido] chama o motor); enquanto ela não vem, a tela mostra "pensando" e o relógio para. */
    private fun pedirAIa(pedido: PedidoDaIa) {
        if (falhas == 0) esperaDesde = SystemClock.elapsedRealtime() // fala nova: a espera conta daqui (pedir de novo não recomeça)
        ultimoPedido = pedido
        // Com a resposta segurada (ele fez um som), pedir de novo não tira a vez dele. A busca da fala anterior não vale mais
        if (provisoria?.segurada != true) _conversa.value = _conversa.value?.copy(suaVez = false, pensando = true, procurando = "")
        val rodada = rodadaConversa
        val este = ++pedidosDaIa
        val vale = { rodada == rodadaConversa && este == pedidosDaIa } // só a primeira de resposta e prazo conta
        // O motor promete responder em [RESPOSTA_IA_MAX_MS]; se não cumprir, o serviço não fica preso em "Pensando…"
        handler.postDelayed({
            if (vale()) {
                pedidosDaIa++
                motor?.cancelar()
                iaRespondeu(null)
            }
        }, RESPOSTA_IA_MAX_MS)
        pedido { fala ->
            if (vale()) {
                pedidosDaIa++
                iaRespondeu(fala)
            }
        }
    }

    /** A pessoa disse [texto]: vai pra IA. */
    private fun responderNaIa(texto: String) {
        val ia = motor ?: return terminarConversa("sem a IA")
        pedirAIa { ia.responder(texto, it) }
    }

    /**
     * A resposta da IA: a [fala] vai pra voz. Sem ela (o motor pode já ter tentado de novo), pede o mesmo outra
     * vez; na [FALHAS_DA_IA_ATE_DESISTIR]ª seguida, ou com [RESPOSTA_IA_MAX_MS] de espera por esta fala (somando
     * os pedidos de novo), a conversa acaba antes e a manhã segue.
     */
    private fun iaRespondeu(fala: String?) {
        if (fala != null) {
            falhas = 0
            if (naoEntendeu(fala)) return naoEntendido()
            naoEntendidos = 0
            ultimaFalaEntendida = fala
            return falarDaIa(fala)
        }
        falhas++
        val esperou = SystemClock.elapsedRealtime() - esperaDesde
        if (falhas >= FALHAS_DA_IA_ATE_DESISTIR || esperou >= RESPOSTA_IA_MAX_MS) {
            return terminarConversa("a IA não respondeu ($falhas vezes seguidas, ${esperou / 1000} s)")
        }
        Log.w(TAG, "Conversa: a IA não respondeu ($falhas de $FALHAS_DA_IA_ATE_DESISTIR), pede de novo")
        ultimoPedido?.let { pedirAIa(it) }
    }

    /**
     * A IA não entendeu o que a pessoa disse ([naoEntendeu]: ruído, português, o reconhecedor errou feio): sai a frase
     * fixa [FALA_NAO_ENTENDI], e a fala dela some das anotações. Na [NAO_ENTENDIDOS_ATE_O_BIPE]ª seguida, um bipe leve
     * pra médio ([BIPE_DO_NAO_ENTENDI]) pra acordar, e a IA repete a última fala que ela entendeu: a pergunta volta.
     */
    private fun naoEntendido() {
        naoEntendidos++
        if (transcricao.lastOrNull()?.daIa == false) transcricao.removeAt(transcricao.lastIndex)
        if (naoEntendidos < NAO_ENTENDIDOS_ATE_O_BIPE) {
            Log.i(TAG, "Conversa: a IA não entendeu ($naoEntendidos de $NAO_ENTENDIDOS_ATE_O_BIPE)")
            return falarDaIa(FALA_NAO_ENTENDI)
        }
        Log.i(TAG, "Conversa: $naoEntendidos falas sem entender, bipe e a IA repete a última pergunta")
        naoEntendidos = 0
        val repete = ultimaFalaEntendida.ifEmpty { FALA_NAO_ENTENDI }
        val rodada = rodadaConversa
        val bipe = sirene.bipe(BIPE_DO_NAO_ENTENDI)
        escuta?.surdoPor(bipe + ATRASO_DO_BIPE_MS)
        handler.postDelayed({ if (rodada == rodadaConversa && _conversa.value != null) falarDaIa(repete) }, bipe + ATRASO_DO_BIPE_MS)
    }

    /**
     * A resposta da IA pro que a pessoa disse ([texto]), antes de a voz começar: ainda pode ir fora. Um som dela sem
     * palavra a [segurada] (a voz espera); se ela só fez barulho, a mesma resposta vale na próxima pausa, já pronta.
     */
    private class Provisoria(val texto: String) {
        val desde = SystemClock.elapsedRealtime()
        var segurada = false
        var chegou = false // a IA já respondeu (a resposta está na memória dela)
        var gravada = false // a voz da resposta está pronta ([fala], [arquivo]; sem arquivo, só na tela)
        var fala = ""
        var arquivo: File? = null
    }

    /**
     * A voz fala o [texto] da IA (gravado agora, num arquivo novo; o [FALA_NAO_NEGOCIA] já está gravado) e a
     * tela mostra; o relógio anda enquanto ela toca. Quando acaba, é a vez da pessoa. Se a voz travar gravando,
     * em [VOZ_DA_IA_MAX_MS] a fala vai só na tela e a vez passa pra pessoa assim mesmo. Uma resposta jogada fora
     * enquanto a voz gravava ([descartarResposta]) não toca; uma [Provisoria.segurada] espera a próxima pausa.
     */
    private fun falarDaIa(texto: String) {
        val rodada = rodadaConversa
        val esta = vezDaIa
        var foi = false // a gravação e o prazo dela chegando os dois: só o primeiro vale
        val pronta: (File?) -> Unit = pronta@{ arquivo ->
            if (rodada != rodadaConversa || esta != vezDaIa || foi) return@pronta
            foi = true
            val p = provisoria ?: return@pronta tocarFalaDaIa(texto, arquivo)
            p.gravada = true
            p.fala = texto
            p.arquivo = arquivo
            if (!p.segurada) tocarFalaDaIa(texto, arquivo)
        }
        provisoria?.chegou = true
        if (texto == FALA_NAO_NEGOCIA) return pronta(voz?.fixa(FALA_DE_NAO_NEGOCIAR))
        if (texto == FALA_NAO_ENTENDI) return pronta(voz?.fixa(FALA_DE_NAO_ENTENDER))
        val v = voz ?: return pronta(null)
        handler.postDelayed({
            if (!foi && rodada == rodadaConversa && esta == vezDaIa) {
                Log.w(TAG, "Conversa: a voz não gravou a fala da IA em ${VOZ_DA_IA_MAX_MS / 1000} s, vai só na tela")
                pronta(null)
            }
        }, VOZ_DA_IA_MAX_MS)
        v.gravar("ia_${++falasDaIa}", texto, pronta)
    }

    /** Daqui em diante a vez é da IA: a voz vai até o fim, e depois é a vez da pessoa. */
    private fun tocarFalaDaIa(texto: String, arquivo: File?) {
        val rodada = rodadaConversa
        provisoria = null
        if (texto != FALA_NAO_ENTENDI) transcricao += Turno(daIa = true, texto = texto)
        _conversa.value = _conversa.value?.copy(falaDaIa = texto, suaVez = false, pensando = false, procurando = "")
        iaFalando = arquivo != null
        falarNaConversa(arquivo) {
            if (rodada != rodadaConversa) return@falarNaConversa
            iaFalando = false
            suaVezNaConversa()
        }
    }

    /** Uma fala da voz na conversa: enquanto ela toca, a [escuta] não ouve (o eco da voz não é a pessoa). */
    private fun falarNaConversa(arquivo: File?, depois: () -> Unit) {
        escuta?.surdo(true)
        sirene.falar(arquivo) {
            escuta?.surdo(false)
            depois()
        }
    }

    /** A voz da IA acabou: é a vez da pessoa falar. O silêncio conta daqui, e o que ela disse antes não vale mais. */
    private fun suaVezNaConversa() {
        val c = _conversa.value ?: return
        val agora = SystemClock.elapsedRealtime()
        vezDaConversa = agora
        bipesDaConversa = 0
        textoDaVez = ""
        provisoria = null
        escuta?.novaVez()
        handler.removeCallbacks(fimDaVez)
        _ultimaFala.value = agora
        _conversa.value = c.copy(suaVez = true, pensando = false, ouvido = "")
    }

    /**
     * O detector da [escuta]: a pessoa começou ([comecou], com o volume [db]) ou parou de fazer som. Um som bem mais
     * baixo que a voz dela é de longe e não conta ([somDaPessoa]): nem segura a IA, nem atrasa a vez dela. Começou:
     * se a IA já pensava na resposta, a voz espera ([Provisoria.segurada]). Parou: conta a pausa ([agendarFimDaVez]).
     */
    private fun somNaConversa(comecou: Boolean, db: Float) {
        if (!comecou) {
            if (!fazendoSom) return // o fim de um som de longe
            fazendoSom = false
            return agendarFimDaVez()
        }
        if (!somDaPessoa(db, nivelDaPessoa)) return
        fazendoSom = true
        val p = provisoria
        if (p != null) {
            if (!p.segurada) Log.i(TAG, "Conversa: um som dele (${db.toInt()} dB) antes da voz, a resposta da IA espera")
            p.segurada = true
            _conversa.value = _conversa.value?.copy(suaVez = true, pensando = false)
        }
        vozDaPessoa()
    }

    /**
     * O reconhecedor escreveu mais do que a pessoa disse nesta vez ([texto], tudo junto): a tela mostra, e vale como
     * fala dela. Com a IA já pensando, a resposta vai fora: ela não tinha acabado.
     */
    private fun textoNaConversa(texto: String) {
        val c = _conversa.value ?: return
        if (texto == textoDaVez || (!c.suaVez && provisoria == null)) return
        textoDaVez = texto
        escuta?.let { nivelDaPessoa = nivelDaVoz(nivelDaPessoa, it.nivelDoUltimoSom()) }
        _conversa.value = c.copy(ouvido = texto)
        // Mais palavras com a IA já pensando: ele continuou falando, e a resposta vai fora. Menos de 1 s depois do
        // pedido e sem som novo dele, é só o reconhecedor fechando a frase (às vezes com uma palavra a mais)
        val p = provisoria
        val continuou = p != null && quantasPalavras(texto) > quantasPalavras(p.texto) &&
            (p.segurada || SystemClock.elapsedRealtime() - p.desde > FRASE_ATRASADA_MS)
        if (continuou) {
            Log.i(TAG, "Conversa: ele continuou falando antes da voz, a resposta da IA vai fora (${escuta?.resumo()})")
            descartarResposta()
        }
        vozDaPessoa()
        if (!fazendoSom) agendarFimDaVez() // a palavra chegou depois do som: a pausa conta daqui
    }

    /** A pessoa está falando (ou fez algum som): o relógio anda e o silêncio (e os bipes) recomeça. */
    private fun vozDaPessoa() {
        if (_conversa.value?.suaVez != true) return
        val agora = SystemClock.elapsedRealtime()
        ouvidoEm = agora
        _ultimaFala.value = agora
        bipesDaConversa = 0
        handler.removeCallbacks(fimDaVez)
    }

    /** A resposta que a IA pensava (ou a voz gravava, ou estava segurada) não vale mais: a vez é da pessoa, com o que ela já disse. */
    private fun descartarResposta() {
        val p = provisoria ?: return
        provisoria = null
        pedidosDaIa++ // a resposta e o prazo dela não chegam mais
        vezDaIa++ // a voz, se já estiver gravando, não toca
        falhas = 0 // uma nova tentativa que estava pra sair não conta contra a próxima resposta
        if (p.chegou) motor?.descartar() else motor?.cancelar()
        _conversa.value = _conversa.value?.copy(suaVez = true, pensando = false)
    }

    /** A pessoa parou de fazer som: depois da pausa ([pausaPraPensar]), a vez passa pra IA ([fimDaVez]). Só barulho, sem palavra, não. */
    private fun agendarFimDaVez() {
        handler.removeCallbacks(fimDaVez)
        val c = _conversa.value ?: return
        if ((!c.suaVez && provisoria?.segurada != true) || textoDaVez.isBlank()) return
        // Na pergunta "quer continuar?", a resposta decide na hora (não tem volta): pausa maior
        handler.postDelayed(fimDaVez, if (c.perguntandoSeContinua) PAUSA_NO_MEIO_MS else pausaPraPensar(textoDaVez))
    }

    /**
     * A pessoa parou de falar ([pausaPraPensar] sem som): o que ela disse vai pra IA, que já começa a pensar; se ela
     * voltar a falar antes de a voz começar, a resposta vai fora ([textoNaConversa]). Se ela só fez barulho, sem
     * palavra nova, vale a resposta segurada. Com a meta da conversa cumprida, em vez da IA vem a pergunta se ela
     * quer continuar (uma vez só); na pergunta, a resposta decide; depois do tempo extra, acaba.
     */
    private val fimDaVez = Runnable {
        val c = _conversa.value?.takeIf { (it.suaVez || provisoria?.segurada == true) && etapa == EtapaDaManha.CONVERSA } ?: return@Runnable
        val texto = textoDaVez
        if (fazendoSom || texto.isBlank()) return@Runnable
        _conversa.value = c.copy(suaVez = false, pensando = true)
        provisoria?.takeIf { quantasPalavras(texto) <= quantasPalavras(it.texto) }?.let { p ->
            Log.i(TAG, "Conversa: ele só fez barulho, vale a resposta que a IA já ${if (p.gravada) "tinha" else "está pensando"}")
            p.segurada = false
            if (p.gravada) tocarFalaDaIa(p.fala, p.arquivo)
            return@Runnable
        }
        descartarResposta()
        _conversa.value = c.copy(suaVez = false, pensando = true)
        Log.i(TAG, "Conversa: ele parou (${texto.length} letras, ${escuta?.resumo()}), ${conversadoMs / 1000} s de ${c.metaMs / 1000}")
        transcricao += Turno(daIa = false, texto = texto)
        when {
            c.perguntandoSeContinua -> respondeuSeContinua(texto)
            conversadoMs < c.metaMs -> {
                provisoria = Provisoria(texto)
                responderNaIa(falaComAlternativas(texto, escuta?.alternativas().orEmpty()))
            }
            extras == 0 -> perguntarSeContinua(texto)
            else -> terminarConversa("tempo de conversa completo, com o extra")
        }
    }

    /** Meta da conversa cumprida: a voz pergunta se a pessoa quer continuar ([FALA_CONTINUAR]). A [resposta] dela, ainda sem retorno da IA, fica guardada. */
    private fun perguntarSeContinua(resposta: String) {
        Log.i(TAG, "Conversa: meta cumprida, pergunta se quer continuar")
        respostaSemRetorno = resposta
        _conversa.value = _conversa.value?.copy(falaDaIa = FALA_CONTINUAR, pensando = false, perguntandoSeContinua = true)
        val rodada = rodadaConversa
        falarNaConversa(voz?.fixa(FALA_DE_CONTINUAR)) { if (rodada == rodadaConversa) suaVezNaConversa() }
    }

    /** A resposta ao "quer continuar?": sim ([querContinuar]), a meta sobe e a IA responde as duas falas; não, ou sem entender, acaba. */
    private fun respondeuSeContinua(resposta: String) {
        val alarme = _tocando.value ?: return
        if (querContinuar(resposta) != true) return terminarConversa("ele não quis continuar")
        extras++
        Log.i(TAG, "Conversa: ele quis continuar, mais ${extraDaConversa(alarme) / 1000} s")
        _conversa.value = _conversa.value?.copy(perguntandoSeContinua = false, metaMs = metaDaConversa(alarme))
        responderNaIa(pedidoDeContinuar(respostaSemRetorno, resposta))
    }

    /**
     * Fim da conversa (meta cumprida, a pessoa não quis mais, ou a IA ou o reconhecimento de voz falharam): a IA
     * anota o que soube dele pra manhã seguinte ([MotorDaConversa.anotar], se ele falou alguma coisa), o modelo sai da
     * memória e vem a caminhada ([irPraCaminhada]). As anotações ficam prontas em segundo plano, já na caminhada.
     */
    private fun terminarConversa(motivo: String) {
        if (etapa != EtapaDaManha.CONVERSA) return
        Log.i(TAG, "Conversa: fim ($motivo), ${conversadoMs / 1000} s de conversa")
        handler.removeCallbacks(tiqueDaConversa)
        handler.removeCallbacks(fimDaVez)
        pararEscuta()
        val ia = motor
        if (ia != null && transcricao.any { !it.daIa }) {
            ia.anotar(transcricao.toList()) { novas ->
                if (novas.isNullOrBlank()) Log.w(TAG, "Conversa: a IA não deixou anotações") else Ajustes.guardarMemoriaDaIa(novas)
            }
        }
        ia?.desligar()
        motor = null
        MotorDaConversa.liberar() // antes da caminhada: menos memória e menos calor (depois das anotações, na fila do modelo)
        iaFalando = false
        _conversa.value = _conversa.value?.copy(falaDaIa = falaDeSair(prazoSairDaquiAte()), suaVez = false, pensando = false, perguntandoSeContinua = false, procurando = "")
        irPraCaminhada()
    }

    /** O prazo pra sair que vem agora: o que já estava marcado (a música voltou, ou os botões do teste mexeram), ou o do alarme pelos ajustes. */
    private fun prazoSairDaquiAte(): Long {
        val alarme = _tocando.value ?: return 0L
        return if (prazoAte == 0L) prazoSair(alarme, tempos()) else (prazoAte - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    /**
     * A caminhada vem agora (fim da conversa ou, sem a IA, logo depois do bom dia): a voz fala a de sair ([falaDeSair]) e ela começa.
     * Um bom dia que ainda estava gravando (voz lenta, e o prazo dele já passou) não toca mais: por cima da fala, ela
     * nunca terminaria e a manhã ficaria parada. E se a fala travar, a caminhada começa assim mesmo em [SAUDACAO_MAX_MS].
     */
    private fun irPraCaminhada() {
        val alarme = _tocando.value ?: return
        val rodada = ++rodadaConversa // nada que estava pendente (IA, voz, fala da pessoa) vale mais
        etapa = EtapaDaManha.CAMINHADA
        voz?.cancelar()
        // O bom dia acabou (sem a IA, a fala de sair vem logo depois dele): a tela mostra a fala no cartão da conversa e
        // pode fechar nesse meio tempo, como na conversa (emSegundoPlano)
        handler.removeCallbacks(comecarAtividade)
        handler.removeCallbacks(anunciarInicio)
        _saudando.value = false
        if (_conversa.value == null) {
            _conversa.value = Conversa(falaDaIa = falaDeSair(prazoSairDaquiAte()), suaVez = false, pensando = false, conversadoMs = conversadoMs, metaMs = metaDaConversa(alarme))
        }
        var foi = false
        val seguir = {
            if (rodada == rodadaConversa && !foi) {
                foi = true
                _conversa.value = null
                continuarManha()
            }
        }
        handler.postDelayed({ seguir() }, SAUDACAO_MAX_MS)
        sirene.falar(voz?.fixa(FALA_DE_SAIR)) { seguir() }
    }

    /**
     * A caminhada, com o prazo pra sair de casa (marcado no relógio na primeira vez; a música voltar não dá mais
     * prazo): o [ContadorDaCaminhada] soma o tempo andando pelo GPS e pelos passos, e a vigia bipa quem fica parado depois do prazo.
     */
    private fun comecarCaminhada() {
        val alarme = _tocando.value ?: return
        // Uma mini tarefa oferecida antes (o prazo recomeçou pelos botões do teste) não vale mais: o cartão e o ouvido saem
        pararMiniTarefa()
        _miniTarefa.value = null
        val agora = SystemClock.elapsedRealtime()
        if (prazoAte == 0L) prazoAte = agora + prazoSair(alarme, tempos())
        Log.i(TAG, "Caminhada: ${andado / 1000} s andados de ${metaCaminhada(alarme, tempos()) / 1000}, ${maxOf(0L, prazoAte - agora) / 1000} s de prazo pra sair")
        _caminhada.value = Caminhada(andadoMs = andado, metaMs = metaCaminhada(alarme, tempos()), prazoAte = prazoAte)
        // No prazo pra se vestir e sair, o som de ondas (se o dono não emudeceu); ele mesmo deixa o alto-falante ligado
        ondasNoPrazo()
        sirene.manterAcordado(false)
        adiarVigiaDaCaminhada()
        // O relógio da caminhada, sobre os sentidos do serviço: soma, dá o sinal de vida (os bipes contam dele) e conclui
        contador?.desligar()
        val s = ligarSentidos()
        contador = ContadorDaCaminhada(
            this, s, andado,
            metaMs = { _tocando.value?.let { metaCaminhada(it, tempos()) } ?: metaCaminhada(alarme, tempos()) },
            aoSomar = { somado, conferindo ->
                andado = maxOf(andado, somado)
                _caminhada.value = _caminhada.value?.copy(andadoMs = andado, conferindoMs = conferindo)
            },
            aoViver = ::andou,
            aoConcluir = { concluir(comemorar = true) },
            aoDefeito = {
                Log.w(TAG, "Caminhada: o celular não tem GPS, defeito: o alarme desliga")
                concluir(comemorar = false)
            },
        ).also { it.ligar() }
    }

    /** Os sentidos da caminhada do serviço, ligados (ou religados, se a localização foi liberada no meio). */
    private fun ligarSentidos(): SentidosDaCaminhada {
        val s = sentidosDoServico ?: SentidosDaCaminhada().also {
            sentidosDoServico = it
            _sentidos.value = it
            handler.removeCallbacks(relerLocalizacao)
            handler.postDelayed(relerLocalizacao, RELER_LOCALIZACAO_MS)
        }
        s.ligar(this)
        return s
    }

    /**
     * Liberada nas Configurações no meio (ou tirada): os sentidos religam, ou a tela mostra o botão. Liberada, o tipo
     * "location" do serviço vem junto ([atualizarTipos]), pro GPS seguir com a tela fechada.
     */
    private val relerLocalizacao = object : Runnable {
        override fun run() {
            val s = sentidosDoServico ?: return
            atualizarTipos()
            s.ligar(this@AlarmeService)
            handler.postDelayed(this, RELER_LOCALIZACAO_MS)
        }
    }

    /** O relógio e os sentidos da caminhada desligados (a música voltou, ou o alarme acabou). */
    private fun desligarSentidos() {
        pararMiniTarefa()
        contador?.desligar()
        contador = null
        handler.removeCallbacks(relerLocalizacao)
        sentidosDoServico?.desligar()
        sentidosDoServico = null
        _sentidos.value = null
    }

    /** O reconhecedor da mini tarefa, se estava ouvindo, para. */
    private fun pararMiniTarefa() {
        ouvinteDaMiniTarefa?.parar()
        ouvinteDaMiniTarefa = null
    }

    /** As ondas enquanto durar o prazo pra sair (ligadas, se o dono não emudeceu nesta manhã) e o fim delas marcado no relógio. */
    private fun ondasNoPrazo() {
        val agora = SystemClock.elapsedRealtime()
        handler.removeCallbacks(fimDasOndas)
        if (agora < prazoAte) {
            sirene.ondas(!_ondasMudas.value)
            handler.postDelayed(fimDasOndas, prazoAte - agora)
        } else {
            sirene.ondas(false)
        }
    }

    /** Fim do prazo pra sair: as ondas param (daí em diante, parado, só os bipes). */
    private val fimDasOndas = Runnable { sirene.ondas(false) }

    /** O botão das ondas na tela da caminhada: mudas ou [ligadas], nesta manhã (a próxima começa ligada de novo). */
    private fun ondas(ligadas: Boolean) {
        _ondasMudas.value = !ligadas
        if (_caminhada.value != null && SystemClock.elapsedRealtime() < prazoAte) sirene.ondas(ligadas)
    }

    /**
     * A mini tarefa do prazo pra sair, no primeiro bipe de parado depois dele ([ofereceMiniTarefa]): a voz oferece a
     * frase e, quando termina, é a vez dela de repetir (o serviço escuta). Os bipes de parado esperam a voz acabar (um
     * bipe no meio abaixa a voz e a frase não se entende) e seguem subindo enquanto ela repete: a mini tarefa não é
     * um jeito de calar os bipes. Sem reconhecimento de voz, a oferta sai e os bipes seguem.
     */
    private fun oferecerMiniTarefa() {
        Log.i(TAG, "Caminhada: mini tarefa oferecida (mais prazo se repetir a frase)")
        val frase = fraseDaMiniTarefa
        _miniTarefa.value = MiniTarefa(frase, suaVez = false)
        handler.removeCallbacks(vigiaDaCaminhada)
        sirene.falar(voz?.fixa(FALA_DA_MINI_TAREFA)) {
            if (_miniTarefa.value?.frase != frase) return@falar // já sumiu (ele andou) ou a música voltou
            _miniTarefa.value = _miniTarefa.value?.copy(suaVez = true)
            handler.postDelayed(vigiaDaCaminhada, ENTRE_BIPES_PARADO_MS)
            // O reconhecedor é do serviço: a mini tarefa vale com a tela fechada também
            pararMiniTarefa()
            ouvinteDaMiniTarefa = Ouvinte(
                this, "mini tarefa", aceita = { repetiu(frase, it) },
                onOuviu = { ouvido -> _miniTarefa.value = _miniTarefa.value?.copy(ouvido = ouvido) },
                onAceito = { miniTarefaFeita() },
                onIndisponivel = {
                    // Depois de [comecar] (pode avisar de dentro dele): o cartão "sua vez" sem ninguém ouvindo enganaria
                    handler.post {
                        if (_miniTarefa.value?.frase != frase) return@post
                        Log.w(TAG, "Caminhada: sem reconhecimento de voz pra mini tarefa, a oferta sai")
                        pararMiniTarefa()
                        _miniTarefa.value = null
                    }
                },
                idioma = "en-US",
                juntar = true,
            ).also { it.comecar() }
        }
    }

    /** A pessoa repetiu a frase da mini tarefa: o prazo pra sair ganha o extra, com as ondas, e os bipes param. Uma vez por manhã. */
    private fun miniTarefaFeita() {
        val alarme = _tocando.value ?: return
        if (_miniTarefa.value?.suaVez != true || _caminhada.value == null || preparoExtraUsado || cumprido) return
        Log.i(TAG, "Caminhada: mini tarefa feita, mais ${preparoExtra(alarme) / 1000} s de prazo pra sair")
        preparoExtraUsado = true
        pararMiniTarefa()
        _miniTarefa.value = null
        prazoAte = SystemClock.elapsedRealtime() + preparoExtra(alarme)
        _caminhada.value = _caminhada.value?.copy(prazoAte = prazoAte)
        ondasNoPrazo()
        adiarVigiaDaCaminhada()
        sirene.acerto { sirene.falar(voz?.fixa(FALA_DE_MAIS_TEMPO)) }
    }

    /**
     * Os botões "Back" e "Forward" de um alarme de teste: a manhã (ou a câmera) pula pra [fase]. Só muda o que a fase
     * pede; o resto (tempo andado, anel) fica. Voltar pra conversa zera o tempo de conversa (senão a IA só perguntaria
     * se ele quer continuar) e, se o modelo já saiu da memória, carrega de novo ([voltarPraConversa]).
     */
    private fun irPraFase(fase: FaseDoTeste) {
        val alarme = _tocando.value ?: return
        if (!_silenciado.value || _saudando.value || cumprido) return
        val agora = SystemClock.elapsedRealtime()
        val atual = faseDoTeste(
            conversa = _conversa.value != null, caminhada = _caminhada.value != null, noPrazo = agora < prazoAte,
            verificacao = _verificacao.value != null, metadeVerificada = _metadeVerificada.value, naCamera = caminho == CaminhoDaManha.ANTIGO || !comConversaECaminhada(alarme),
        )
        if (atual == null || atual == fase || fase !in atual.fases(comFrases = frasesDeHoje.isNotEmpty())) return
        Log.i(TAG, "Teste: de $atual pra $fase pelos botões")
        when (fase) {
            FaseDoTeste.CONVERSA -> voltarPraConversa()
            FaseDoTeste.PREPARO -> {
                prazoAte = 0L // o prazo começa de novo, inteiro
                if (atual == FaseDoTeste.CONVERSA) saltarConversa() else comecarCaminhada()
            }
            FaseDoTeste.CAMINHADA -> {
                prazoAte = agora // prazo já vencido: sem ondas, e os bipes de parado contam daqui
                if (atual == FaseDoTeste.CONVERSA) saltarConversa() else comecarCaminhada()
            }
            FaseDoTeste.CAMERA_FACIL -> {
                cancelarVerificacao()
                zerarAnel()
                adiarVigia()
            }
            FaseDoTeste.FRASES -> {
                cancelarVerificacao()
                _olhado.value = verificacaoEm(alarme, tempos())
                _metadeVerificada.value = false
                iniciarVerificacao()
            }
            FaseDoTeste.CAMERA_EXIGENTE -> {
                cancelarVerificacao()
                _olhado.value = maxOf(_olhado.value, verificacaoEm(alarme, tempos()))
                _metadeVerificada.value = true
                adiarVigia()
            }
        }
    }

    /**
     * Dos botões do teste, da conversa pra caminhada: no meio da conversa, ela termina ([terminarConversa]). Com a fala
     * de sair já tocando (a conversa acabou; a tela ainda mostra o cartão dela), a caminhada vem já, sem esperar a fala.
     */
    private fun saltarConversa() {
        if (etapa == EtapaDaManha.CONVERSA) return terminarConversa("avançou no teste")
        rodadaConversa++ // o "seguir" da fala de sair não vale mais: a caminhada começa aqui
        _conversa.value = null
        continuarManha()
    }

    /** A verificação em curso para (sem zerar nada): os sons e as frases atrasados não valem mais. */
    private fun cancelarVerificacao() {
        rodadaVerificacao++
        pararOuvinteDaFrase()
        handler.removeCallbacks(silencio)
        handler.removeCallbacks(vigia)
        _verificacao.value = null
    }

    /**
     * Do prazo ou da caminhada de volta pra conversa (só nos testes): a caminhada para, o tempo de conversa zera e a
     * IA recomeça. O modelo já saiu da memória no fim da conversa (e pode ainda estar anotando, uns 10 s): carrega de
     * novo, lê a instrução de novo (uns 9 s) e abre; isso passa dos 20 s de uma fala comum ([RESPOSTA_IA_MAX_MS]),
     * então a abertura tem o prazo dela, [VOLTA_MAX_MS]. Enquanto isso, a tela mostra "Thinking…"; sem a abertura no
     * prazo, a caminhada de novo.
     */
    private fun voltarPraConversa() {
        val alarme = _tocando.value ?: return
        handler.removeCallbacks(fimDasOndas)
        handler.removeCallbacks(vigiaDaCaminhada)
        contador?.desligar()
        contador = null
        pararMiniTarefa()
        sirene.ondas(false)
        _caminhada.value = null
        _miniTarefa.value = null
        prazoAte = 0L
        conversadoMs = 0
        extras = 0
        falhas = 0
        transcricao.clear()
        etapa = EtapaDaManha.CONVERSA
        sirene.manterAcordado(true)
        if (motor != null) return abrirConversa(null)
        _conversa.value = Conversa(falaDaIa = "", suaVez = false, pensando = true, conversadoMs = 0, metaMs = metaDaConversa(alarme))
        if (iaBloqueada || !MotorDaConversa.disponivel(this)) return terminarConversa("sem a IA pra voltar")
        MotorDaConversa.preparar(this)
        val rodada = rodadaConversa
        var foi = false // a abertura e o prazo dela chegando os dois: só o primeiro vale
        val chegou: (String?) -> Unit = { fala ->
            if (rodada == rodadaConversa && etapa == EtapaDaManha.CONVERSA && !foi) {
                foi = true
                if (fala == null) terminarConversa("a IA não voltou em ${VOLTA_MAX_MS / 1000} s") else abrirConversa(fala)
            }
        }
        handler.postDelayed({ chegou(null) }, VOLTA_MAX_MS)
        Noticias.buscar { manchetes, metas ->
            if (rodada != rodadaConversa || etapa != EtapaDaManha.CONVERSA || foi) return@buscar
            val ia = criarMotor(manchetes, metas) ?: return@buscar chegou(null)
            ia.abrir(chegou)
        }
    }

    /** Sinal de vida do relógio da caminhada ([ContadorDaCaminhada]): guarda o tempo andado e a contagem pro bipe recomeça. */
    private fun andou(andadoMs: Long) {
        // Sinal atrasado, de quando a música voltou
        if (_caminhada.value == null || !_silenciado.value || _saudando.value || cumprido) return
        andado = maxOf(andado, andadoMs)
        _caminhada.value = _caminhada.value?.copy(andadoMs = andado) // a tela recriada no meio continua daqui
        // Andou depois do primeiro bipe: já acordou, a mini tarefa some e não volta
        if (_miniTarefa.value != null) {
            Log.i(TAG, "Caminhada: andou, a mini tarefa some")
            pararMiniTarefa()
            _miniTarefa.value = null
            miniTarefaRetirada = true
        }
        adiarVigiaDaCaminhada()
    }

    /** O 1º bipe de parado fica pra [PARADO_MS] depois de agora ou do fim do prazo pra sair ([primeiroBipeParado]): no prazo, nenhum. */
    private fun adiarVigiaDaCaminhada() {
        val agora = SystemClock.elapsedRealtime()
        _ultimoPasso.value = agora
        bipesParado = 0
        handler.removeCallbacks(vigiaDaCaminhada)
        handler.postDelayed(vigiaDaCaminhada, primeiroBipeParado(agora, prazoAte) - agora)
    }

    /**
     * Vigia da caminhada: parado depois do prazo pra sair (ou sem GPS, dentro de casa, com a localização desligada),
     * um bipe a cada [ENTRE_BIPES_PARADO_MS], um degrau mais alto, até o máximo no bipe [BIPES_PARADO_ATE_O_MAXIMO]
     * (41 s parado), até voltar a andar. Não zera nada.
     */
    private val vigiaDaCaminhada = object : Runnable {
        override fun run() {
            bipesParado++
            Log.i(TAG, "Caminhada: parado depois do prazo pra sair, bipe $bipesParado")
            sirene.bipe(bipesParado, BIPES_PARADO_ATE_O_MAXIMO)
            handler.postDelayed(this, ENTRE_BIPES_PARADO_MS)
            if (ofereceMiniTarefa(bipesParado, preparoExtraUsado, miniTarefaRetirada)) oferecerMiniTarefa()
        }
    }

    /** A música voltou: a etapa da manhã para (o que chegar depois de antes não vale), e o caminho e o progresso ficam. */
    private fun pausarManha() {
        rodadaConversa++
        desligarSentidos()
        handler.removeCallbacks(fimDasOndas)
        sirene.ondas(false)
        sirene.manterAcordado(false)
        handler.removeCallbacks(tiqueDaConversa)
        handler.removeCallbacks(fimDaVez)
        handler.removeCallbacks(vigiaDaCaminhada)
        handler.removeCallbacks(esperaDaDecisao)
        descartarResposta() // uma resposta que já tinha chegado e não foi dita: a IA fica sabendo
        pararEscuta()
        iaFalando = false
        saudacaoTerminou = false
        _conversa.value = null
        _caminhada.value = null
        _miniTarefa.value = null
        if (caminho == null) {
            // Ainda decidindo: no próximo STOP, tudo de novo
            motor?.desligar()
            motor = null
        } else {
            motor?.cancelar() // a conversa fica, com o histórico
        }
    }

    /** A [escuta] desliga (fim da conversa, a música voltou ou o alarme acabou): microfone e reconhecedor soltos. */
    private fun pararEscuta() {
        escuta?.parar()
        escuta = null
        textoDaVez = ""
        fazendoSom = false
        provisoria = null
    }

    /** Alarme novo (ou um que não é mais "conversa + caminhada"): a manhã começa do zero. */
    private fun esquecerManha() {
        rodadaConversa++
        motor?.desligar()
        motor = null
        MotorDaConversa.liberar()
        caminho = null
        etapa = null
        conversadoMs = 0
        extras = 0
        falhas = 0
        andado = 0
        prazoAte = 0
        transcricao.clear()
        naoEntendidos = 0
        ultimaFalaEntendida = ""
        preparoExtraUsado = false
        miniTarefaRetirada = false
        _miniTarefa.value = null
        _ondasMudas.value = false
    }

    /**
     * Outro alarme juntou no meio ([EmAndamento.juntar]): os tempos seguem o alarme novo (o teste pede bem
     * menos). Se ele é o "câmera + frases", a manhã nova para e a música volta: depois do STOP, a missão de 40 min.
     */
    private fun ajustarManha(alarme: Alarme) {
        if (caminho == null || caminho == CaminhoDaManha.ANTIGO) return
        if (!comConversaECaminhada(alarme)) {
            Log.i(TAG, "Serviço: o alarme novo é o câmera + frases, a música volta e depois vem a missão de 40 min")
            religarMusica()
            esquecerManha()
        }
        _conversa.value = _conversa.value?.copy(metaMs = metaDaConversa(alarme))
        _caminhada.value = _caminhada.value?.copy(metaMs = metaCaminhada(alarme, tempos()))
    }

    /** Missão cumprida (ou DESLIGAR, em alarme sem missão): o único fim do alarme. A limpeza acontece em [onDestroy]. */
    private fun concluir(comemorar: Boolean = false) {
        if (cumprido) return
        cumprido = true
        Log.i(TAG, "Serviço: alarme cumprido")
        handler.removeCallbacksAndMessages(null) // nenhuma renovação atrasada pode rearmar a retomada
        rodadaConversa++ // nem a IA nem uma voz atrasada da conversa falam mais
        pararEscuta()
        desligarSentidos()
        desligarCamera()
        pararOuvinteDaFrase()
        motor?.desligar()
        motor = null
        MotorDaConversa.liberar()
        sirene.ondas(false)
        sirene.manterAcordado(false)
        // Cumprido já fica gravado: se o app cair na comemoração, o alarme não volta
        volumeAoFim = Ajustes.emAndamento?.volume
        Ajustes.volumeParaDevolver = volumeAoFim
        Ajustes.emAndamento = null
        Ajustes.progresso = null
        Agendador.cancelarRetomada(this)
        if (!comemorar) return encerrar()
        // Missão cumprida: bipe leve, a fala de parabéns e o estouro, aí fecha
        _comemorando.value = true
        sirene.calar()
        handler.postDelayed(::encerrar, COMEMORACAO_MAX_MS)
        sirene.leve { sirene.falar(voz?.fixa(FALA_DO_FIM)) { sirene.comemoracao(::encerrar) } }
    }

    /**
     * O fim de verdade: devolve o volume de antes do alarme e fecha o serviço (a limpeza acontece em
     * [onDestroy]). Se outro alarme disparou na comemoração, ele toca em instantes, pela retomada.
     */
    private fun encerrar() {
        handler.removeCallbacksAndMessages(null)
        sirene.desligar(volumeAntes = volumeAoFim)
        Ajustes.volumeParaDevolver = null
        Ajustes.emAndamento?.let {
            Log.i(TAG, "Serviço: outro alarme disparou na comemoração, toca em seguida")
            Agendador.agendarRetomada(this, 0)
        }
        pararServico()
    }

    /** Fecha só este serviço, sem mexer no alarme em andamento. */
    private fun pararServico() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Vira serviço em primeiro plano exibindo a notificação do alarme. */
    private fun mostrarNotificacao(alarme: Alarme) {
        val comum = tipoComum()
        val tipo = tipoDoServico(comum)
        ultimoTextoDaNotificacao = textoDaNotificacao(alarme)
        tiposPedidos = tipo
        tiposPedidosEm = SystemClock.elapsedRealtime()
        tiposDoServico = tipo
        runCatching { ServiceCompat.startForeground(this, Notificacoes.ID_TOCANDO, criarNotificacao(alarme), tipo) }
            .onFailure {
                // O Android não deixou os tipos de localização ou microfone (sem permissão, ou a tela não está na frente):
                // segue só com o de sempre; a etapa então precisa da tela
                Log.w(TAG, "Serviço: sem os tipos location e microphone (${it.javaClass.simpleName}: ${it.message?.take(100)})")
                tiposDoServico = comum
                ServiceCompat.startForeground(this, Notificacoes.ID_TOCANDO, criarNotificacao(alarme), comum)
            }
    }

    private fun tipoComum() = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

    /**
     * Uma permissão dada no meio do alarme (os botões ALLOW LOCATION e ALLOW CAMERA): com a tela na frente, que é quando
     * o Android deixa, os tipos do serviço vão de novo, agora com o dela. Sem isso, com a tela fechada o GPS para de
     * chegar e a câmera é fechada. Chamada a cada 5 s ([renovar]), quando a tela abre e quando a câmera ou o GPS ligam;
     * só faz algo se os tipos que cabem diferem dos que o Android deu ([tiposDoServico]). Um pedido novo (permissão
     * nova) vai na hora; o mesmo pedido de antes, recusado (o diálogo da permissão ainda fechando, por exemplo), é
     * repetido só a cada [REPEDIR_TIPOS_MS], sem martelar.
     */
    private fun atualizarTipos() {
        val alarme = _tocando.value ?: return
        if (!_silenciado.value || !telaAberta || cumprido) return
        val cabem = tipoDoServico(tipoComum())
        if (cabem == tiposDoServico) return
        if (cabem == tiposPedidos && SystemClock.elapsedRealtime() - tiposPedidosEm < REPEDIR_TIPOS_MS) return
        Log.i(TAG, "Serviço: os tipos que cabem mudaram no meio do alarme, vão de novo")
        mostrarNotificacao(alarme)
    }

    /** O serviço tem o tipo "camera" (ou não precisa: antes do Android 11): a câmera segue com a tela fechada. */
    private fun cameraComTelaFechada() =
        Build.VERSION.SDK_INT < 30 || tiposDoServico and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA != 0

    /**
     * Os tipos do serviço em primeiro plano: o de sempre ([comum]) e, depois do STOP, os de localização (o GPS da
     * caminhada), câmera (a missão de olhar) e microfone (a conversa, as frases e a mini tarefa), que no Android 14+
     * são o que deixa o app seguir usando GPS, câmera e microfone com a tela fechada. Só com as permissões dadas: sem
     * elas, o Android recusa.
     */
    private fun tipoDoServico(comum: Int): Int {
        if (Build.VERSION.SDK_INT < 29 || !_silenciado.value) return comum
        var tipo = comum
        if (manhaNova() && Poder.LOCALIZACAO.liberado(this)) tipo = tipo or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (!manhaNova() && Build.VERSION.SDK_INT >= 30 && Poder.CAMERA.liberado(this)) tipo = tipo or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (Build.VERSION.SDK_INT >= 30 && Poder.MICROFONE.liberado(this)) tipo = tipo or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return tipo
    }

    /** O texto da notificação com o que está acontecendo; se mudou, a notificação vai de novo (a mesma, só o texto). */
    private fun atualizarNotificacao() {
        val alarme = _tocando.value ?: return
        if (cumprido) return
        val texto = textoDaNotificacao(alarme)
        if (texto == ultimoTextoDaNotificacao) return
        ultimoTextoDaNotificacao = texto
        notificacoes().notify(Notificacoes.ID_TOCANDO, criarNotificacao(alarme))
    }

    /** Onde o alarme está, pra notificação (a conversa e a caminhada seguem com a tela fechada: é por aqui que a pessoa acompanha). */
    private fun textoDaNotificacao(alarme: Alarme): String {
        val agora = hhmm(LocalTime.now())
        val c = _conversa.value
        val k = _caminhada.value
        val relogio = SystemClock.elapsedRealtime()
        return when {
            !alarme.missao -> "$agora · ringing"
            c != null -> "Talking with the AI · ${tempoAndado(c.conversadoMs)} of ${tempoAndado(c.metaMs)} · tap to come back"
            k != null && relogio < k.prazoAte -> "Time to go out: ${tempoRestante(k.prazoAte - relogio)} · walked ${tempoAndado(k.andadoMs)} of ${tempoAndado(k.metaMs)}"
            k != null -> "Walking · ${tempoAndado(k.andadoMs)} of ${tempoAndado(k.metaMs)} · tap to come back"
            _verificacao.value != null -> "Repeat the phrases · tap to come back"
            _silenciado.value -> "Look at the camera · ${tempoRestante(metaOlhar(alarme, tempos()) - _olhado.value)} left · tap to come back"
            alarme.caminhada -> "$agora · say STOP: talk + walk"
            else -> "$agora · say STOP and look at the camera"
        }
    }

    private fun criarNotificacao(alarme: Alarme): Notification {
        val telaCheia = PendingIntent.getActivity(
            this, 0,
            Intent(this, AlarmeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Com a missão ligada, "Desligar" abre a tela da missão em vez de desligar direto
        val desligar = if (alarme.missao) telaCheia else comandoPendente(1, ACAO_DESLIGAR)
        return NotificationCompat.Builder(this, Notificacoes.CANAL_ALARME)
            .setSmallIcon(R.drawable.ic_alarme)
            .setContentTitle(alarme.nome)
            .setContentText(textoDaNotificacao(alarme))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            // O texto muda a cada 5 s (atualizarNotificacao): sem isto, o aviso desceria por cima de tudo a cada vez
            .setOnlyAlertOnce(true)
            .setColor(getColor(R.color.vermelho_alarme))
            .setColorized(true)
            .setContentIntent(telaCheia)
            .setFullScreenIntent(telaCheia, true)
            .setDeleteIntent(comandoPendente(2, ACAO_REEXIBIR))
            .addAction(0, "Turn off", desligar)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun comandoPendente(codigo: Int, acao: String): PendingIntent =
        PendingIntent.getService(this, codigo, comando(this, acao), PendingIntent.FLAG_IMMUTABLE)

    private fun notificacoes() = getSystemService(NotificationManager::class.java)
}
