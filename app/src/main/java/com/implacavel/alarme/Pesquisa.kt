// A busca na internet que a IA da conversa pode pedir ("SEARCH: o que procurar", [consultaDaBusca]): a Wikipédia em
// inglês (o começo dos dois artigos que mais batem) e o Google Notícias (as notícias mais recentes sobre o assunto),
// as duas ao mesmo tempo, em até [BUSCA_MAX_MS]. O modelo continua no celular, sem internet: só o que ele pede pra
// procurar vai pela rede, como as manchetes do dia ([Noticias]). Sem internet, vem null e a IA responde com o que sabe.
// A leitura das respostas é pura (testada em ConversaTest); a busca em si roda na thread do modelo.
package com.implacavel.alarme

import android.util.Log
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.concurrent.thread

/** O máximo que uma busca espera (as duas fontes juntas): a pessoa está esperando a resposta, com a tela em "Looking up…". */
const val BUSCA_MAX_MS = 4_000L

/** Notícias por busca. */
const val NOTICIAS_DA_BUSCA = 3

/** A Wikimedia pede que cada app se identifique; sem isso, a Wikipédia recusa. */
private const val AGENTE = "NightmareAlarm/1.0 (https://github.com/luiz-nast/nightmare-alarm)"

object Pesquisa {
    /** Procura [consulta] nas duas fontes e junta ([resultadoDaBusca]); null sem nada (ou sem internet). Bloqueia a thread de quem chama, até [BUSCA_MAX_MS]. */
    fun buscar(consulta: String): String? {
        var wikipedia: List<String> = emptyList()
        var noticias: List<String> = emptyList()
        val inicio = System.currentTimeMillis()
        // Numa thread de fundo, um erro sem tratar derrubaria o app (e o alarme): todo erro vira "nada"
        val w = thread(isDaemon = true, name = "wikipedia") {
            wikipedia = runCatching { baixarTexto(enderecoDaWikipedia(consulta), AGENTE)?.let(::trechosDaWikipedia) }.getOrNull().orEmpty()
        }
        val n = thread(isDaemon = true, name = "noticias-busca") {
            noticias = runCatching { baixarTexto(enderecoDasNoticias(consulta))?.let { manchetesDoRss(it, NOTICIAS_DA_BUSCA) } }.getOrNull().orEmpty()
        }
        for (t in listOf(w, n)) t.join((BUSCA_MAX_MS - (System.currentTimeMillis() - inicio)).coerceAtLeast(1))
        val resultado = resultadoDaBusca(wikipedia, noticias)
        Log.i(TAG, "Busca: \"$consulta\": ${wikipedia.size} da Wikipédia, ${noticias.size} notícias, em ${System.currentTimeMillis() - inicio} ms")
        return resultado
    }
}

/**
 * A API da Wikipédia em inglês, num pedido só: os 2 artigos que mais batem com [consulta], cada um com as 2 primeiras
 * frases (o que é) e com o trecho do artigo onde as palavras da busca aparecem (o `snippet` da busca: numa pergunta
 * "quem inventou o transistor", o começo do artigo diz o que é um transistor, e o trecho diz quem inventou).
 */
fun enderecoDaWikipedia(consulta: String): String {
    val busca = URLEncoder.encode(consulta, "UTF-8")
    return "https://en.wikipedia.org/w/api.php?action=query&format=json&formatversion=2" +
        "&generator=search&gsrlimit=2&gsrsearch=$busca&prop=extracts&exintro=1&explaintext=1&exsentences=2" +
        "&list=search&srlimit=2&srsearch=$busca&srprop=snippet"
}

/** O RSS de busca do Google Notícias, em inglês (as mais recentes sobre o assunto). */
fun enderecoDasNoticias(consulta: String): String =
    "https://news.google.com/rss/search?q=${URLEncoder.encode(consulta, "UTF-8")}&hl=en-US&gl=US&ceid=US:en"

/** Cada artigo vai pra IA até esse tanto de letras: o trecho que bateu com a busca e um pedaço do começo. */
const val TRECHO_MAX_LETRAS = 280

/**
 * Os trechos da resposta da Wikipédia ([json]): "Título: …trecho onde a busca bateu… começo do artigo", na ordem em
 * que a busca os pôs, sem os vazios, cada um até [TRECHO_MAX_LETRAS]. O trecho vem primeiro: é ele que costuma ter a
 * resposta (quem inventou, quando); o começo só diz o que é. Quando o trecho é o próprio começo, só o começo. O
 * trecho vem em HTML (marcas e entidades): vira texto simples.
 */
fun trechosDaWikipedia(json: String): List<String> {
    val consulta = runCatching { JSONObject(json).getJSONObject("query") }.getOrNull() ?: return emptyList()
    val trechos = consulta.optJSONArray("search")?.let { busca ->
        (0 until busca.length()).map { busca.getJSONObject(it) }.associate { it.optString("title") to textoDoXml(it.optString("snippet").replace(Regex("<[^>]*>"), "")) }
    }.orEmpty()
    val paginas = consulta.optJSONArray("pages") ?: return emptyList()
    return (0 until paginas.length())
        .map { paginas.getJSONObject(it) }
        .sortedBy { it.optInt("index", Int.MAX_VALUE) }
        .mapNotNull { p ->
            val titulo = p.optString("title")
            val comeco = p.optString("extract").replace(Regex("\\s+"), " ").trim()
            val trecho = trechos[titulo].orEmpty().replace(Regex("\\s+"), " ").trim()
            val texto = when {
                comeco.isEmpty() && trecho.isEmpty() -> return@mapNotNull null
                trecho.isEmpty() || comeco.contains(trecho.take(40)) -> comeco
                comeco.isEmpty() -> "…$trecho…"
                else -> "…$trecho… $comeco"
            }
            "$titulo: ${texto.take(TRECHO_MAX_LETRAS)}"
        }
}

/**
 * O que vai pra IA: os trechos da [wikipedia] e as [noticias] (sem o nome do jornal, no fim de cada título), até
 * [RESULTADO_MAX_LETRAS] no total; null sem nada.
 */
fun resultadoDaBusca(wikipedia: List<String>, noticias: List<String>): String? {
    if (wikipedia.isEmpty() && noticias.isEmpty()) return null
    val texto = buildString {
        wikipedia.forEach { appendLine(it) }
        if (noticias.isNotEmpty()) {
            appendLine("Recent news:")
            noticias.forEach { appendLine("- ${it.substringBeforeLast(" - ").trim()}") }
        }
    }.trim()
    return texto.take(RESULTADO_MAX_LETRAS)
}
