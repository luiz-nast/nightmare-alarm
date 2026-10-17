// GUARDADO, FORA DO APP (não compila daqui): veja reserva/gemini/README.md pra religar.
// A IA da conversa da manhã: o Gemini, do Google, pela internet (REST generateContent). Guarda o histórico da
// conversa e manda tudo a cada vez (a API não lembra nada). A IA só produz texto: a vez de cada um, quanto
// dura e o fim são do AlarmeService. A chave vem só do local.properties (BuildConfig.GEMINI_API_KEY) e nunca
// vai pro log; do celular só sai texto (manchetes, metas e o que o reconhecedor de voz ouviu).
package com.implacavel.alarme

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Tamanho máximo da resposta da IA, em tokens: as falas pedidas têm até 45 palavras. */
private const val MAX_TOKENS = 200

/**
 * O pedido pro Gemini, em JSON: a [instrucao] do treinador ([instrucaoDoTreinador]), a conversa até aqui
 * ([turnos], o último é da pessoa), respostas curtas e o mínimo de "pensar" (responde mais rápido). Sem
 * temperatura: nos Gemini 3, o Google pede pra deixar a padrão.
 */
fun corpoDoPedido(instrucao: String, turnos: List<Turno>): String = JSONObject()
    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instrucao))))
    .put("contents", JSONArray().apply {
        turnos.forEach { turno ->
            put(JSONObject().put("role", if (turno.daIa) "model" else "user").put("parts", JSONArray().put(JSONObject().put("text", turno.texto))))
        }
    })
    .put("generationConfig", JSONObject().put("maxOutputTokens", MAX_TOKENS).put("thinkingConfig", JSONObject().put("thinkingLevel", "MINIMAL")))
    .toString()

/**
 * O texto da resposta do Gemini: as partes da primeira resposta, menos as de "pensamento". Null se veio sem
 * texto (bloqueada, vazia ou num formato inesperado).
 */
fun textoDaResposta(json: String): String? = runCatching {
    val partes = JSONObject(json).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
    (0 until partes.length()).map { partes.getJSONObject(it) }.filterNot { it.optBoolean("thought") }.joinToString("") { it.optString("text") }
}.getOrNull()?.trim()?.ifEmpty { null }

/** Por que a resposta veio sem texto, pro log: o finishReason (SAFETY, MAX_TOKENS...) ou o blockReason do pedido. */
fun motivoSemTexto(json: String): String = runCatching {
    val tudo = JSONObject(json)
    tudo.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason")?.ifEmpty { null }
        ?: tudo.optJSONObject("promptFeedback")?.optString("blockReason")?.ifEmpty { null }
}.getOrNull() ?: "sem motivo"

/** O erro do Gemini, curto pro log ("429 RESOURCE_EXHAUSTED: You exceeded..."). Nunca tem a chave. */
fun resumoDoErro(codigo: Int, json: String): String {
    val erro = runCatching { JSONObject(json).getJSONObject("error") }.getOrNull() ?: return "$codigo"
    val tipo = erro.optString("status").ifEmpty { erro.optString("code") }
    return "$codigo $tipo: ${erro.optString("message").take(100)}"
}

/**
 * Vale tentar de novo depois de [PAUSA_NOVA_TENTATIVA_MS]? Sim no que passa sozinho: demora (408), limite
 * por minuto (429) e servidor com problema (5xx). Não na cota do dia acabada (só volta à meia-noite do
 * Pacífico), nem em pedido errado, chave ruim ou modelo que não existe (400, 401, 403, 404).
 */
fun falhaPassageira(codigo: Int, json: String): Boolean {
    val cotaDoDia = listOf("PerDay", "per day", "daily").any { json.contains(it, ignoreCase = true) }
    return codigo == 408 || (codigo == 429 && !cotaDoDia) || codigo >= 500
}

/**
 * Uma conversa com a IA: as [regras][instrucao] e o histórico. [abrir] pede a primeira fala, [responder]
 * manda o que a pessoa disse, [retomar] continua depois de uma interrupção. Cada pedido chama o
 * `aoResponder` uma vez só, na thread principal, em até [RESPOSTA_IA_MAX_MS], com a fala pronta pra voz
 * ([limparFala]) ou null (sem resposta, já com a nova tentativa). Se a IA sugeriu dormir ou disse que o
 * alarme acabou ([respostaSegura]), vem o [falaNaoNegocia] no lugar, e é ele que fica no histórico. Um
 * pedido novo toma o lugar do que ainda não respondeu; depois de [cancelar] ou [desligar], resposta
 * atrasada não chega em ninguém. No histórico só entra fala que teve resposta (a da pessoa junto com a da
 * IA): sem resposta, dá pra mandar a mesma fala de novo. Tudo é chamado na thread principal; só o pedido
 * pela internet roda em outra.
 */
class Gemini(private val instrucao: String, private val abertura: String, private val falaNaoNegocia: String) : MotorDaConversa {

    companion object {
        private const val ENDERECO = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val CONEXAO_MS = 5_000
        private const val LEITURA_MS = 12_000

        /** Dá pra conversar agora: tem chave no local.properties e internet de verdade (validada pelo Android). */
        fun disponivel(ctx: Context): Boolean = BuildConfig.GEMINI_API_KEY.isNotBlank() && internetValidada(ctx)

        private fun internetValidada(ctx: Context): Boolean = runCatching {
            val rede = ctx.getSystemService(ConnectivityManager::class.java)
            rede.getNetworkCapabilities(rede.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }.getOrDefault(false)
    }

    private val historico = mutableListOf<Turno>()
    private val handler = Handler(Looper.getMainLooper())

    // Uma thread por pedido em curso: um pedido velho preso na internet não atrasa o novo
    private val executor = Executors.newCachedThreadPool { Thread(it, "gemini").apply { isDaemon = true } }
    private var pedidos = 0 // muda a cada pedido, cancelamento ou resposta: resposta de um pedido velho não chega
    private var emCurso: Future<*>? = null
    private var prazo: Runnable? = null
    private var desligado = false

    /** A primeira fala da IA, que abre a conversa ([abertura], de [pedidoDeAbertura]). */
    override fun abrir(aoResponder: (String?) -> Unit) = perguntar(abertura, aoResponder)

    /** A pessoa disse [fala] (o que o reconhecedor ouviu): a próxima fala da IA. */
    override fun responder(fala: String, aoResponder: (String?) -> Unit) = perguntar(fala, aoResponder)

    /** A conversa volta depois de a pessoa sair da tela ([PEDIDO_DE_RETOMADA]); sem histórico, abre do começo. */
    override fun retomar(aoResponder: (String?) -> Unit) =
        perguntar(if (historico.isEmpty()) abertura else PEDIDO_DE_RETOMADA, aoResponder)

    /** O pedido em curso fica sem resposta (o `aoResponder` dele nunca é chamado). O histórico fica. */
    override fun cancelar() {
        pedidos++
        emCurso?.cancel(true)
        emCurso = null
        prazo?.let { handler.removeCallbacks(it) }
        prazo = null
    }

    /** A resposta que já chegou não vai ser dita (a pessoa voltou a falar): ela e a pergunta saem do histórico. */
    override fun descartar() {
        cancelar()
        if (historico.size >= 2 && historico.last().daIa) repeat(2) { historico.removeAt(historico.lastIndex) }
    }

    /** Fim da conversa: nenhum pedido sai nem responde mais. */
    override fun desligar() {
        desligado = true
        cancelar()
        executor.shutdownNow()
    }

    private fun perguntar(fala: String, aoResponder: (String?) -> Unit) {
        if (desligado) return
        cancelar() // um pedido por vez
        val numero = pedidos
        val pergunta = Turno(daIa = false, texto = fala)
        val corpo = corpoDoPedido(instrucao, historico + pergunta)
        val inicio = SystemClock.elapsedRealtime()
        fun entregar(resposta: String?) {
            if (numero != pedidos) return // cancelado, ou já tem um pedido mais novo
            cancelar() // acabou: o prazo sai e a nova tentativa, se ainda estava esperando, não vai
            val limpa = resposta?.let(::limparFala)?.ifEmpty { null }
            val segura = limpa == null || respostaSegura(limpa)
            if (!segura) Log.i(TAG, "Conversa: a IA sugeriu descansar ou parar, vai a frase fixa no lugar")
            val texto = if (segura) limpa else falaNaoNegocia
            if (texto != null) historico += listOf(pergunta, Turno(daIa = true, texto = texto))
            val demora = SystemClock.elapsedRealtime() - inicio
            Log.i(TAG, "Conversa: ${if (texto != null) "IA respondeu (${texto.length} letras)" else "IA sem resposta"} em $demora ms")
            aoResponder(texto)
        }
        prazo = Runnable {
            Log.w(TAG, "Conversa: a IA não respondeu em ${RESPOSTA_IA_MAX_MS / 1000} s")
            entregar(null)
        }.also { handler.postDelayed(it, RESPOSTA_IA_MAX_MS) }
        emCurso = runCatching {
            executor.submit(Runnable {
                val resposta = runCatching { pedir(corpo) }.getOrNull() // cancelado no meio: InterruptedException
                handler.post { entregar(resposta) }
            })
        }.getOrNull() // sem thread (desligando): o prazo responde null
    }

    /** Na thread de fundo: manda o pedido e, numa falha passageira, tenta mais uma vez depois de [PAUSA_NOVA_TENTATIVA_MS]. */
    private fun pedir(corpo: String): String? {
        for (tentativa in 1..2) {
            val passageira = try {
                val (codigo, resposta) = postar(corpo)
                if (codigo == 200) {
                    val texto = textoDaResposta(resposta)
                    if (texto == null) Log.w(TAG, "Conversa: a IA respondeu sem texto (${motivoSemTexto(resposta)})")
                    return texto
                }
                Log.w(TAG, "Conversa: a IA deu erro ${resumoDoErro(codigo, resposta)}")
                falhaPassageira(codigo, resposta)
            } catch (e: IOException) {
                Log.w(TAG, "Conversa: sem conexão com a IA (${e.javaClass.simpleName})")
                true
            }
            if (!passageira || tentativa == 2) return null
            Thread.sleep(PAUSA_NOVA_TENTATIVA_MS)
        }
        return null
    }

    /** Um POST pro modelo do BuildConfig, com a chave no cabeçalho (não no endereço): o código HTTP e o corpo da resposta. */
    private fun postar(corpo: String): Pair<Int, String> {
        val modelo = BuildConfig.GEMINI_MODELO.trim().removePrefix("models/")
        val conexao = URL("$ENDERECO/$modelo:generateContent").openConnection() as HttpURLConnection
        try {
            conexao.connectTimeout = CONEXAO_MS
            conexao.readTimeout = LEITURA_MS
            conexao.requestMethod = "POST"
            conexao.doOutput = true
            conexao.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conexao.setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            conexao.outputStream.use { it.write(corpo.toByteArray()) }
            val codigo = conexao.responseCode
            val resposta = (if (codigo in 200..299) conexao.inputStream else conexao.errorStream)?.bufferedReader()?.use { it.readText() }
            return codigo to resposta.orEmpty()
        } finally {
            conexao.disconnect()
        }
    }
}
