// A IA da conversa no próprio celular: o Gemma 4 E2B (Google, licença Apache 2.0) rodando no processador pelo
// LiteRT-LM, sem internet. O arquivo do modelo (2,6 GB) não vai no APK: fica na pasta do app no celular
// (Android/data/com.implacavel.alarme/files/), copiado uma vez pelo cabo (adb push). Direto nela, sem subpasta: uma
// subpasta criada pelo cabo fica do usuário do adb, e o app não entra. Carregar leva uns segundos e
// uns 1,5 GB de memória, então o serviço começa a carregar assim que o alarme toca ([preparar]) e tira da memória
// no fim da conversa ([liberar]). Tudo do modelo roda numa thread só, a dele; as respostas chegam na principal.
package com.implacavel.alarme

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors

/** O modelo: o arquivo do Hugging Face (litert-community/gemma-4-E2B-it-litert-lm), com o tamanho exato dele. */
const val ARQUIVO_DO_MODELO = "gemma-4-E2B-it.litertlm"
const val TAMANHO_DO_MODELO = 2_588_147_712L

/** Memória livre pra carregar o modelo com folga (carregado, o app fica com ~520 MB: medido no S24 FE) sem o Android matar o app. */
private const val MEMORIA_MINIMA = 1_500L * 1024 * 1024

/**
 * Quanto de conversa (instrução, falas e respostas, em tokens) cabe: a instrução (~1000, com as anotações e o que o
 * dono contou de si) e umas 25 trocas de ~100 tokens (uma busca gasta uns 250), os 10 min de conversa pura e quase
 * todo o extra. Medido no S24 FE: com 6144 ou 8192, ler a instrução e responder fica ~40% mais lento (a primeira fala
 * demora o dobro), sem mudar a memória. Se encher, a IA para de responder e a conversa acaba uns minutos antes (a
 * caminhada vem igual).
 */
const val CONTEXTO_TOKENS = 4096

/**
 * Tamanho máximo de uma resposta, em tokens: duas a quatro frases curtas, 40 palavras (uns 60 tokens), com folga; passou,
 * [ateAUltimaFrase] tira a frase pela metade. No S24 FE o modelo escreve uns 17 a 19 tokens por segundo: uma fala cheia
 * leva uns 4 s pra nascer (e uns 15 s pra voz dizer). O dono prefere isso a perguntas curtas de script.
 */
const val RESPOSTA_MAX_TOKENS = 84

/** A amostragem do Gemma 4 (a recomendada pelo Google, com a temperatura um pouco abaixo de 1: varia as falas sem perder o fio). */
private val AMOSTRAGEM = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.9)

/** Quem anota é mais certinho: temperatura baixa. */
private val AMOSTRAGEM_DAS_ANOTACOES = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3)

/**
 * Uma conversa com o Gemma no celular ([MotorDaConversa]): a [instrucao] do treinador vira a instrução do
 * sistema, a [abertura] é o primeiro pedido, e o histórico fica no próprio modelo (não precisa mandar tudo de novo). Se a IA sugerir descansar ou
 * disser que o alarme acabou ([respostaSegura]), vem o [falaNaoNegocia] no lugar. Se ela pedir uma busca
 * ([consultaDaBusca]), a [troca] busca ([Pesquisa]) e manda o resultado antes de devolver a fala.
 *
 * Um pedido cancelado não para o modelo: ele termina de escrever e a resposta é jogada fora. Parar no meio
 * (`cancelProcess`) estraga a conversa pra sempre: medido no S24 FE (`TesteDaIa --ez cancelar true`), todo pedido
 * depois dele falha com "CANCELLED", cancelando escrevendo ou ainda lendo, na hora ou meio segundo depois. E a
 * resposta jogada fora fica na memória da IA: o pedido seguinte avisa que ele não ouviu ([pedidoDepoisDeInterrompida]).
 */
class ModeloLocal(ctx: Context, private val instrucao: String, private val abertura: String, private val falaNaoNegocia: String) : MotorDaConversa {

    companion object {
        private val handler = Handler(Looper.getMainLooper())

        // Uma thread só pra tudo do modelo: carregar, conversar e fechar, um de cada vez e na ordem
        private val trabalho = Executors.newSingleThreadExecutor { Thread(it, "gemma").apply { isDaemon = true } }

        @Volatile private var engine: Engine? = null // carregado, pras conversas do mesmo alarme; só mexido na thread do modelo
        @Volatile private var carregando = false // o [preparar] está carregando: a memória que ele ocupa não conta contra

        /** O teste pelo cabo ([TesteDaIa]) está com o modelo dele: um alarme agora não carrega outro (não cabem os dois). */
        @Volatile var emTeste = false

        /** O arquivo do modelo na pasta do app (Android/data/com.implacavel.alarme/files/). */
        fun arquivo(ctx: Context): File? = ctx.getExternalFilesDir(null)?.let { File(it, ARQUIVO_DO_MODELO) }

        /**
         * Dá pra conversar: celular de 64 bits (a biblioteca só tem essa versão), o arquivo inteiro no lugar e
         * memória livre pra carregar ([MEMORIA_MINIMA]). Já carregado, sim.
         */
        fun disponivel(ctx: Context): Boolean {
            if (emTeste) return false
            if (engine != null || carregando) return true
            if ("arm64-v8a" !in Build.SUPPORTED_64_BIT_ABIS) return false
            if (arquivo(ctx)?.length() != TAMANHO_DO_MODELO) return false
            return memoriaLivre(ctx) >= MEMORIA_MINIMA
        }

        private fun memoriaLivre(ctx: Context): Long = runCatching {
            ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.availMem
        }.getOrDefault(0L)

        /** Começa a carregar o modelo em segundo plano (pode chamar de novo: não carrega duas vezes). */
        fun preparar(ctx: Context) {
            val app = ctx.applicationContext
            carregando = true
            trabalho.execute {
                try {
                    runCatching { carregado(app) }
                } finally {
                    carregando = false
                }
            }
        }

        /** Tira o modelo da memória, depois do que estiver em curso na thread dele. */
        fun liberar() {
            trabalho.execute {
                engine?.let {
                    Log.i(TAG, "IA do celular: modelo fora da memória")
                    runCatching { it.close() }
                }
                engine = null
            }
        }

        /** O modelo pronto, carregando agora se ainda não estava. Só na thread do modelo. Lança erro se não deu. */
        private fun carregado(ctx: Context): Engine {
            engine?.let { return it }
            val caminho = arquivo(ctx)?.path ?: error("sem a pasta do app")
            val inicio = SystemClock.elapsedRealtime()
            val novo = Engine(EngineConfig(modelPath = caminho, backend = Backend.CPU(), maxNumTokens = CONTEXTO_TOKENS, cacheDir = ctx.cacheDir.path))
            try {
                novo.initialize()
            } catch (e: Throwable) {
                runCatching { novo.close() }
                Log.w(TAG, "IA do celular: o modelo não carregou (${e.javaClass.simpleName}: ${e.message?.take(120)})")
                throw e
            }
            Log.i(TAG, "IA do celular: modelo carregado em ${SystemClock.elapsedRealtime() - inicio} ms")
            engine = novo
            return novo
        }

        /** O texto de uma resposta do modelo: as partes de texto juntas. */
        internal fun textoDe(resposta: Message): String = resposta.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

        /** A conversa da manhã, com a [instrucao] como instrução do sistema (o modelo já lê a instrução ao criar). Só na thread do modelo. */
        internal fun conversaDaManha(engine: Engine, instrucao: String): Conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(instrucao),
                samplerConfig = AMOSTRAGEM,
                prefillPrefaceOnInit = true,
                maxOutputToken = RESPOSTA_MAX_TOKENS,
                thinkingConfig = ThinkingConfig(enableThinking = false),
            ),
        )

        /** O que uma troca com o modelo devolveu: a [fala] final e, se a IA pediu uma busca no meio, o que ela [buscou]. */
        internal class Troca(val fala: String, val buscou: String?)

        /**
         * Uma troca com o modelo na [conversa] (só na thread do modelo): manda a [mensagem] e, se a IA responder
         * pedindo uma busca ([consultaDaBusca]), busca ([Pesquisa], avisando [aoProcurar]) e manda o resultado
         * ([pedidoComResultado]), até a fala de verdade. Sem [podeBuscar] (já buscou demais nesta conversa), ela
         * responde com o que sabe ([PEDIDO_SEM_BUSCA]); um segundo pedido de busca seguido também. Nunca para no meio:
         * a linha "SEARCH:" fica na memória da IA, e ela precisa ver a resposta pra conversa seguir fazendo sentido.
         */
        internal fun trocar(conversa: Conversation, mensagem: String, podeBuscar: Boolean, aoProcurar: (String) -> Unit): Troca {
            var texto = textoDe(conversa.sendMessage(mensagem))
            val consulta = consultaDaBusca(texto) ?: return Troca(texto, null)
            val pedido = if (podeBuscar) {
                aoProcurar(consulta)
                val resultado = Pesquisa.buscar(consulta)
                Log.i(TAG, "IA do celular: pediu busca (\"${consulta.take(60)}\"): ${resultado?.length ?: 0} letras de resultado")
                pedidoComResultado(consulta, resultado, pergunta = mensagem)
            } else {
                Log.i(TAG, "IA do celular: pediu busca demais, responde sem")
                PEDIDO_SEM_BUSCA
            }
            texto = textoDe(conversa.sendMessage(pedido))
            if (consultaDaBusca(texto) != null) texto = textoDe(conversa.sendMessage(PEDIDO_SEM_BUSCA))
            return Troca(texto, consulta)
        }

        /**
         * As anotações sobre a pessoa, numa conversa à parte com o modelo [engine] (só na thread do modelo), com um teto
         * de tokens maior que o das falas ([ANOTACOES_MAX_TOKENS]): as [antigas], a [transcricao] de hoje e a data de
         * [hoje] vão em [pedidoDeAnotacoes]; volta limpo e com as antigas que o modelo deixou cair ([juntarAnotacoes]).
         */
        internal fun anotarCom(engine: Engine, antigas: String, transcricao: List<Turno>, hoje: LocalDate): String =
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(INSTRUCAO_DAS_ANOTACOES),
                    samplerConfig = AMOSTRAGEM_DAS_ANOTACOES,
                    prefillPrefaceOnInit = true,
                    maxOutputToken = ANOTACOES_MAX_TOKENS,
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                ),
            ).use { c -> juntarAnotacoes(antigas, textoDe(c.sendMessage(pedidoDeAnotacoes(antigas, transcricao, hoje)))) }
    }

    private val app = ctx.applicationContext
    @Volatile private var conversa: Conversation? = null // criada no primeiro pedido, com a instrução, na thread do modelo
    @Volatile private var pedidos = 0 // muda a cada pedido e cancelamento: resposta de um pedido velho não chega
    @Volatile private var naoOuvida: String? = null // o modelo respondeu este pedido, e a pessoa não ouviu a resposta (está na memória dele)
    @Volatile private var ultimaFala = "" // a do último pedido
    @Volatile private var respondido = -1 // o último pedido que o modelo terminou de responder (thread do modelo)
    private var entregue = -1 // o último pedido cuja resposta chegou no serviço (thread principal)
    @Volatile private var desligado = false
    private var buscas = 0 // buscas feitas nesta conversa (thread do modelo), até [BUSCAS_MAX]

    override var aoProcurar: ((String) -> Unit)? = null

    /**
     * Lê a instrução já (uns 9 s no S24 FE, com as anotações e as manchetes: ~1200 tokens), ainda durante a música, pra
     * abertura sair em uns 3 s no fim do bom dia. Pode chamar de novo: não lê duas vezes.
     */
    override fun aquecer() {
        trabalho.execute { if (!desligado) runCatching { conversaPronta() }.onFailure { Log.w(TAG, "IA do celular: não leu a instrução antes (${it.javaClass.simpleName})") } }
    }

    override fun abrir(aoResponder: (String?) -> Unit) = perguntar(abertura, aoResponder)

    override fun responder(fala: String, aoResponder: (String?) -> Unit) = perguntar(fala, aoResponder)

    override fun retomar(aoResponder: (String?) -> Unit) = perguntar(if (conversa == null) abertura else PEDIDO_DE_RETOMADA, aoResponder)

    /** O pedido em curso fica sem resposta (o modelo termina de escrever, e a resposta vai fora). A conversa fica. */
    override fun cancelar() {
        // Já respondido e ainda não entregue: a resposta está na memória dele, e a pessoa não vai ouvir
        if (respondido == pedidos && entregue != pedidos) naoOuvida = ultimaFala
        pedidos++
    }

    /** A resposta que já chegou não vai ser dita: ela está na memória da IA, e o próximo pedido avisa que ele não ouviu. */
    override fun descartar() {
        naoOuvida = ultimaFala
        cancelar()
    }

    /** Fim desta conversa: nenhum pedido sai nem responde mais. O modelo fica carregado até o [liberar]. */
    override fun desligar() {
        if (desligado) return
        desligado = true
        cancelar()
        trabalho.execute {
            conversa?.let { runCatching { it.close() } }
            conversa = null
        }
    }

    /**
     * As anotações pra manhã seguinte, depois do que estiver em curso na thread do modelo (uma resposta jogada fora
     * termina de escrever antes). A conversa da manhã fecha antes de anotar: as duas não ficam na memória juntas.
     */
    override fun anotar(antigas: String, transcricao: List<Turno>, aoTerminar: (String?) -> Unit) {
        if (desligado) return aoTerminar(null)
        desligado = true
        cancelar()
        trabalho.execute {
            val inicio = SystemClock.elapsedRealtime()
            conversa?.let { runCatching { it.close() } }
            conversa = null
            val novas = runCatching { anotarCom(carregado(app), antigas, transcricao, LocalDate.now()) }
                .onFailure { Log.w(TAG, "IA do celular: não anotou (${it.javaClass.simpleName}: ${it.message?.take(120)})") }
                .getOrNull()
            if (novas != null) Log.i(TAG, "IA do celular: anotações da conversa (${novas.lines().size} linhas) em ${SystemClock.elapsedRealtime() - inicio} ms")
            handler.post { aoTerminar(novas) }
        }
    }

    private fun perguntar(fala: String, aoResponder: (String?) -> Unit) {
        if (desligado) return
        cancelar() // um pedido por vez
        ultimaFala = fala
        val numero = pedidos
        val inicio = SystemClock.elapsedRealtime()
        trabalho.execute {
            if (numero != pedidos || desligado) return@execute // cancelado antes de chegar a vez dele
            val resposta = try {
                val c = conversaPronta()
                if (numero != pedidos || desligado) return@execute // cancelado enquanto carregava ou lia a instrução
                val mensagem = naoOuvida?.let { pedidoDepoisDeInterrompida(it, fala) } ?: fala
                naoOuvida = null
                val troca = trocar(c, mensagem, podeBuscar = buscas < BUSCAS_MAX) { consulta ->
                    buscas++
                    handler.post { if (numero == pedidos && !desligado) aoProcurar?.invoke(consulta) }
                }
                troca.fala.also {
                    respondido = numero
                    if (numero != pedidos) naoOuvida = fala // cancelado enquanto escrevia
                }
            } catch (e: Throwable) {
                if (numero == pedidos) Log.w(TAG, "IA do celular: sem resposta (${e.javaClass.simpleName}: ${e.message?.take(120)})")
                null
            }
            handler.post { entregar(numero, resposta, SystemClock.elapsedRealtime() - inicio, aoResponder) }
        }
    }

    /** A conversa com a instrução do treinador, criada no primeiro pedido (já lendo a instrução). Só na thread do modelo. */
    private fun conversaPronta(): Conversation = conversa ?: conversaDaManha(carregado(app), instrucao).also { conversa = it }

    private fun entregar(numero: Int, resposta: String?, demora: Long, aoResponder: (String?) -> Unit) {
        if (numero != pedidos || desligado) return // cancelado, ou já tem um pedido mais novo
        entregue = numero
        val limpa = resposta?.let(::limparFala)?.ifEmpty { null }
        val segura = limpa == null || respostaSegura(limpa)
        if (!segura) Log.i(TAG, "Conversa: a IA sugeriu descansar ou parar, vai a frase fixa no lugar")
        val texto = if (segura) limpa else falaNaoNegocia
        Log.i(TAG, "Conversa: ${if (texto != null) "IA respondeu (${texto.length} letras)" else "IA sem resposta"} em $demora ms")
        aoResponder(texto)
    }
}
