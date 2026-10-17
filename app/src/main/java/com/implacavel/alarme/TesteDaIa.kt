// Teste da IA do celular sem som e sem tela: carrega o modelo, conversa umas falas de mentira (pra ver se ela fica no
// assunto) e escreve no log as respostas, o tempo de carregar, o tempo até a primeira palavra, a velocidade, a memória
// e o calor. Só dá pra chamar pelo cabo
// (o adb tem a permissão DUMP, que nenhum app comum tem):
//   adb shell am broadcast -n com.implacavel.alarme/.TesteDaIa
//   adb logcat -s Implacavel
// Pra comparar outro tamanho de conversa (em tokens), sem recompilar: --ei tokens 4096
// Com um alarme tocando, não roda, e um alarme que começa no meio dele fica sem a IA ([ModeloLocal.emTeste]): o
// modelo do alarme e o do teste não cabem juntos na memória.
package com.implacavel.alarme

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.time.LocalTime
import kotlin.concurrent.thread

/** O teste para antes disso: o Android dá uns 60 s pra um aviso de fundo terminar. */
private const val TESTE_MAX_MS = 50_000L

private val MANCHETES_DE_TESTE = listOf(
    "Preço do café sobe 30% no ano e pesa no bolso do brasileiro - g1",
    "Dólar fecha em queda e Bolsa sobe com expectativa de corte de juros - Folha",
    "Frente fria derruba temperaturas em São Paulo nesta semana - CNN Brasil",
    "Seleção brasileira vence o Chile e garante vaga na final - ge",
    "Nova linha de metrô em São Paulo começa a operar em fase de testes - Estadão",
)

// Respostas de quem acabou de acordar: duas com assunto, duas curtas (o assunto morreu: aí ela pode trocar) e o pedido de dormir
private val FALAS_DE_TESTE = listOf(
    pedidoDeAbertura(MANCHETES_DE_TESTE),
    "Really? I didn't know that. Why is that happening?",
    "I think it's bad for normal people, everything is getting more expensive.",
    "Yeah.",
    "I don't know.",
    "I want to finish the project at work and go to the gym.",
    "Can I sleep five more minutes?",
)

class TesteDaIa : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Ajustes.emAndamento != null || ModeloLocal.emTeste) {
            Log.w(TAG, "IA teste: tem alarme tocando (ou outro teste), o teste não roda")
            return
        }
        val arquivo = ModeloLocal.arquivo(ctx)
        if (arquivo?.length() != TAMANHO_DO_MODELO) {
            Log.w(TAG, "IA teste: modelo não está no celular (${arquivo?.path}, ${arquivo?.length()} bytes)")
            return
        }
        ModeloLocal.emTeste = true
        val fim = goAsync()
        thread(name = "teste-da-ia") {
            try {
                if (intent.getBooleanExtra("cancelar", false)) testarCancelamento(ctx.applicationContext, arquivo.path)
                else testar(ctx.applicationContext, arquivo.path, intent.getIntExtra("tokens", CONTEXTO_TOKENS))
            } catch (e: Throwable) {
                Log.w(TAG, "IA teste: falhou (${e.javaClass.simpleName}: ${e.message?.take(160)})")
            } finally {
                semMedicao()
                ModeloLocal.emTeste = false
                fim.finish()
            }
        }
    }

    /** A medição de velocidade vale pro processo inteiro: desliga, pra não ir junto pro modelo de um alarme depois. */
    @OptIn(ExperimentalApi::class)
    private fun semMedicao() {
        ExperimentalFlags.enableBenchmark = false
    }

    @OptIn(ExperimentalApi::class) // a medição de velocidade (ExperimentalFlags.enableBenchmark)
    private fun testar(ctx: Context, caminho: String, tokens: Int) {
        val inicio = SystemClock.elapsedRealtime()
        Log.i(TAG, "IA teste: começando com $tokens tokens (memória livre ${memoriaLivreMb(ctx)} MB, calor ${calor(ctx)})")
        ExperimentalFlags.enableBenchmark = true
        Engine(EngineConfig(modelPath = caminho, backend = Backend.CPU(), maxNumTokens = tokens, cacheDir = ctx.cacheDir.path)).use { engine ->
            engine.initialize()
            Log.i(TAG, "IA teste: modelo carregado em ${SystemClock.elapsedRealtime() - inicio} ms (memória do app ${memoriaDoAppMb(ctx)} MB)")
            // Do tamanho de uma manhã de verdade: 5 manchetes (inventadas, do jeito do Google Notícias) e as metas cheias
            val manchetes = MANCHETES_DE_TESTE.take(MANCHETES_MAX)
            val metas = "Correr 5 km, estudar inglês e terminar o relatório do trabalho. ".repeat(20).take(METAS_MAX_LETRAS)
            val config = ConversationConfig(
                systemInstruction = Contents.of(instrucaoDoTreinador(manchetes, metas, LocalTime.now())),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
                prefillPrefaceOnInit = true,
                maxOutputToken = RESPOSTA_MAX_TOKENS,
                thinkingConfig = ThinkingConfig(enableThinking = false),
            )
            val antesDaInstrucao = SystemClock.elapsedRealtime()
            engine.createConversation(config).use { conversa ->
                Log.i(TAG, "IA teste: instrução lida em ${SystemClock.elapsedRealtime() - antesDaInstrucao} ms")
                for (fala in FALAS_DE_TESTE) {
                    if (SystemClock.elapsedRealtime() - inicio > TESTE_MAX_MS) {
                        Log.w(TAG, "IA teste: passou de ${TESTE_MAX_MS / 1000} s, parando")
                        return
                    }
                    if (Ajustes.emAndamento != null) {
                        Log.w(TAG, "IA teste: um alarme começou, parando")
                        return
                    }
                    val antes = SystemClock.elapsedRealtime()
                    val resposta = conversa.sendMessage(fala).contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    val b = conversa.getBenchmarkInfo()
                    Log.i(
                        TAG,
                        "IA teste: resposta em ${SystemClock.elapsedRealtime() - antes} ms (1ª palavra ${"%.1f".format(b.timeToFirstTokenInSecond)} s, " +
                            "leu ${b.lastPrefillTokenCount} a ${b.lastPrefillTokensPerSecond.toInt()}/s, escreveu ${b.lastDecodeTokenCount} a ${"%.1f".format(b.lastDecodeTokensPerSecond)}/s, " +
                            "segura=${respostaSegura(limparFala(resposta))})",
                    )
                    Log.i(TAG, "IA teste: \"${limparFala(resposta).take(300)}\"")
                }
                Log.i(TAG, "IA teste: memória do app depois da conversa ${memoriaDoAppMb(ctx)} MB")
            }
        }
        Log.i(TAG, "IA teste: fim em ${SystemClock.elapsedRealtime() - inicio} ms (calor ${calor(ctx)})")
    }

    /**
     * Cancelar uma resposta no meio estraga a conversa? Uma fala inteira, outra cancelada no meio (uma vez já
     * escrevendo, outra ainda lendo), e uma pergunta sobre o que só estava na cancelada: se a IA souber, o cancelado
     * ficou na memória dela. Escreve no log as respostas e o tamanho da conversa (tokens) a cada passo.
     */
    private fun testarCancelamento(ctx: Context, caminho: String) {
        Engine(EngineConfig(modelPath = caminho, backend = Backend.CPU(), maxNumTokens = CONTEXTO_TOKENS, cacheDir = ctx.cacheDir.path)).use { engine ->
            engine.initialize()
            val config = ConversationConfig(
                systemInstruction = Contents.of("You are a friendly assistant. Answer in one short sentence."),
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.1),
                prefillPrefaceOnInit = true,
                maxOutputToken = 60,
                thinkingConfig = ThinkingConfig(enableThinking = false),
            )
            fun texto(m: com.google.ai.edge.litertlm.Message) = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            // (nome, quanto espera pra cancelar, quanto espera depois do cancelamento antes do próximo pedido)
            for ((nome, cancelaMs, esperaMs) in listOf(Triple("escrevendo, espera 500 ms", 1_500L, 500L), Triple("escrevendo, pede na hora", 1_500L, 0L), Triple("lendo, espera 500 ms", 30L, 500L))) {
                engine.createConversation(config).use { c ->
                    c.sendMessage("Hi! I'm getting ready for work, I'm a nurse.")
                    val antesTokens = c.getTokenCount()
                    val acabou = java.util.concurrent.CountDownLatch(1)
                    val partes = StringBuilder()
                    var primeiraEm = 0L
                    val pediu = SystemClock.elapsedRealtime()
                    c.sendMessageAsync(
                        "My dog is called Max. Tell me a long story about Max.",
                        object : com.google.ai.edge.litertlm.MessageCallback {
                            override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                                if (primeiraEm == 0L) primeiraEm = SystemClock.elapsedRealtime() - pediu
                                partes.append(texto(message))
                            }
                            override fun onDone() { acabou.countDown() }
                            override fun onError(throwable: Throwable) { acabou.countDown() }
                        },
                    )
                    Thread.sleep(cancelaMs)
                    c.cancelProcess()
                    acabou.await(15, java.util.concurrent.TimeUnit.SECONDS)
                    Thread.sleep(esperaMs)
                    Log.i(TAG, "IA teste: cancelar ($nome): 1ª palavra em $primeiraEm ms, escreveu \"${partes.take(60)}\", tokens $antesTokens -> ${c.getTokenCount()}")
                    for (pergunta in listOf("What is my dog's name? If I never told you, say exactly: I don't know.", "What is my job?")) {
                        val antes = SystemClock.elapsedRealtime()
                        val resposta = runCatching { texto(c.sendMessage(pergunta)) }.getOrElse { "ERRO ${it.message?.take(80)}" }
                        Log.i(TAG, "IA teste: cancelar ($nome), \"$pergunta\" -> \"$resposta\" (${c.getTokenCount()} tokens, ${SystemClock.elapsedRealtime() - antes} ms)")
                    }
                }
            }
        }
    }

    private fun memoriaLivreMb(ctx: Context): Long =
        ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.availMem / (1024 * 1024)

    private fun memoriaDoAppMb(ctx: Context): Int =
        ctx.getSystemService(ActivityManager::class.java).getProcessMemoryInfo(intArrayOf(Process.myPid())).first().totalPss / 1024

    /** O estado térmico do Android (0 = frio ... 6 = desligando), do Android 10 em diante. */
    private fun calor(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= 29) "${ctx.getSystemService(PowerManager::class.java).currentThermalStatus}" else "?"
}
