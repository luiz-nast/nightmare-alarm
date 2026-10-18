// Assuntos da conversa da manhã com a IA: as manchetes do Google Notícias (Brasil) e as metas do dono, se ele
// tiver um endereço com elas (METAS_URL no local.properties). Buscadas pela internet numa thread de fundo
// enquanto a voz dá bom dia; sem internet, a lista vem vazia e as metas em branco. A leitura do RSS e a limpeza
// do texto são funções puras (testadas em ConversaTest).
package com.implacavel.alarme

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Principais notícias do Brasil, em português (RSS do Google Notícias). */
private const val ENDERECO_DAS_NOTICIAS = "https://news.google.com/rss?hl=pt-BR&gl=BR&ceid=BR:pt-419"

/** Tempo pra conectar e pra cada leitura nas buscas de notícias e metas. */
private const val ESPERA_BUSCA_MS = 5_000

/** O máximo que uma busca lê (o RSS tem uns 100 mil letras): um endereço errado não enche a memória. */
private const val TEXTO_MAX_LETRAS = 500_000

object Noticias {
    private val handler = Handler(Looper.getMainLooper())
    private var pedidos = 0 // muda a cada [cancelar]: resposta de uma busca velha não chega

    /**
     * Busca as manchetes do dia (até [MANCHETES_MAX]) e as metas, ao mesmo tempo, e entrega as duas juntas
     * na thread principal ([aoChegar]), em até uns 10 s. Falhou, vem vazio: a conversa segue sem elas.
     */
    fun buscar(aoChegar: (manchetes: List<String>, metas: String) -> Unit) {
        val numero = pedidos
        thread(isDaemon = true, name = "noticias") {
            // Numa thread de fundo, um erro sem tratar derrubaria o app (e o alarme): todo erro vira "vazio"
            var metas = ""
            val buscaDasMetas = thread(isDaemon = true, name = "metas") { metas = runCatching { baixarMetas() }.getOrDefault("") }
            val manchetes = runCatching { baixarTexto(ENDERECO_DAS_NOTICIAS)?.let { manchetesDoRss(it) } }.getOrNull().orEmpty()
            buscaDasMetas.join()
            Log.i(TAG, "Conversa: ${manchetes.size} manchetes e metas com ${metas.length} letras")
            handler.post { if (numero == pedidos) aoChegar(manchetes, metas) }
        }
    }

    /** A busca em curso não entrega mais nada. */
    fun cancelar() {
        pedidos++
    }

    /** As metas do endereço do local.properties (METAS_URL), em texto simples; sem endereço, em branco. */
    private fun baixarMetas(): String {
        val endereco = BuildConfig.METAS_URL.trim()
        if (endereco.isEmpty()) return ""
        return baixarTexto(endereco)?.let(::textoDasMetas).orEmpty()
    }
}

/**
 * GET em [endereco] (na thread de fundo), com [ESPERA_BUSCA_MS] pra conectar e pra ler: o corpo da resposta
 * (até [TEXTO_MAX_LETRAS]), ou null se deu erro ou veio código diferente de 2xx. [agente] é o User-Agent, pros sites
 * que exigem um (a Wikipédia).
 */
fun baixarTexto(endereco: String, agente: String? = null): String? {
    val conexao = try {
        URL(endereco).openConnection() as HttpURLConnection
    } catch (e: Exception) {
        Log.w(TAG, "Busca: endereço inválido (${e.javaClass.simpleName})")
        return null
    }
    return try {
        conexao.connectTimeout = ESPERA_BUSCA_MS
        conexao.readTimeout = ESPERA_BUSCA_MS
        agente?.let { conexao.setRequestProperty("User-Agent", it) }
        val codigo = conexao.responseCode
        if (codigo !in 200..299) {
            Log.w(TAG, "Busca: ${conexao.url.host} respondeu $codigo")
            null
        } else {
            conexao.inputStream.bufferedReader().use { it.lerAte(TEXTO_MAX_LETRAS) }
        }
    } catch (e: Exception) { // sem internet, demora, e qualquer outro erro: nunca derruba a thread
        Log.w(TAG, "Busca: sem resposta de ${conexao.url.host} (${e.javaClass.simpleName})")
        null
    } finally {
        conexao.disconnect()
    }
}

private fun Reader.lerAte(limite: Int): String {
    val texto = StringBuilder()
    val pedaco = CharArray(8_192)
    while (texto.length < limite) {
        val lidos = read(pedaco)
        if (lidos < 0) break
        texto.append(pedaco, 0, lidos)
    }
    return texto.take(limite).toString()
}

/**
 * As manchetes de um RSS: o título de cada `<item>` (o do canal fica de fora), até [maximo], em texto
 * simples ([textoDoXml]). No Google Notícias, cada título já termina com o jornal ("... - O GLOBO").
 * Expressão regular, e não um leitor de XML: o do Android não roda nos testes, e o RSS é simples.
 */
fun manchetesDoRss(xml: String, maximo: Int = MANCHETES_MAX): List<String> =
    ITEM.findAll(xml)
        .mapNotNull { TITULO.find(it.groupValues[1])?.groupValues?.get(1) }
        .map(::textoDoXml)
        .filter { it.isNotEmpty() }
        .take(maximo)
        .toList()

private val ITEM = Regex("<item\\b[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
private val TITULO = Regex("<title\\b[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
private val CDATA = Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)
private val ENTIDADE = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|amp|lt|gt|quot|apos|nbsp);")
private val ENTIDADES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

/**
 * O texto de um trecho de XML: o de dentro do CDATA fica como está; fora dele, as entidades viram letra
 * ("&amp;" → "&", "&#39;" → "'"). Numa linha só, sem espaço sobrando.
 */
fun textoDoXml(trecho: String): String {
    val texto = StringBuilder()
    var depois = 0
    for (cdata in CDATA.findAll(trecho)) {
        texto.append(semEntidades(trecho.substring(depois, cdata.range.first))).append(cdata.groupValues[1])
        depois = cdata.range.last + 1
    }
    texto.append(semEntidades(trecho.substring(depois)))
    return texto.toString().replace(Regex("\\s+"), " ").trim()
}

/**
 * As metas pra IA, em texto simples: sem as marcas de HTML (e sem o que há dentro de script e style), as
 * entidades viradas letra, numa linha só e com até [METAS_MAX_LETRAS].
 */
fun textoDasMetas(corpo: String): String = semEntidades(
    corpo.replace(Regex("<(script|style)\\b.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
        .replace(Regex("<[^>]*>"), " ")
).replace(Regex("\\s+"), " ").trim().take(METAS_MAX_LETRAS)

private fun semEntidades(texto: String): String = ENTIDADE.replace(texto) { achada ->
    val nome = achada.groupValues[1]
    val codigo = when {
        nome.startsWith("#x", ignoreCase = true) -> nome.drop(2).toIntOrNull(16)
        nome.startsWith("#") -> nome.drop(1).toIntOrNull()
        else -> null
    }
    val letra = if (codigo != null) runCatching { String(Character.toChars(codigo)) }.getOrNull() else ENTIDADES[nome]
    letra ?: achada.value
}
