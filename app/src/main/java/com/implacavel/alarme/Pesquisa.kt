// A busca na internet que a IA da conversa pode pedir ("SEARCH: o que procurar", [consultaDaBusca]). Quatro pedidos ao
// mesmo tempo, em até [BUSCA_MAX_MS]: o artigo inteiro da Wikipédia em inglês que mais bate com a busca, o começo dos
// três primeiros (em inglês) e dos dois primeiros em português (o que é do Brasil está melhor lá), e o Google Notícias
// (as mais recentes). Antes de ir pra IA, o texto é tratado ([fatosRelevantes]): vira frases, cada uma ganha nota pelas
// palavras da busca, e só as melhores vão. Antes (até 10-28), iam as duas primeiras frases dos artigos e os títulos das
// notícias, sem tratar nada, e o dono achou raso: "parece que nada trata as informações antes de alimentar o modelo".
// O modelo continua no celular, sem internet: só o que ele pede pra procurar vai pela rede. Sem internet, vem null e a
// IA responde com o que sabe. A leitura das respostas é pura (testada em ConversaTest); a busca roda na thread do modelo.
package com.implacavel.alarme

import android.util.Log
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.concurrent.thread

/** O máximo que uma busca espera (os quatro pedidos juntos): a pessoa está esperando, com a tela em "Looking up…". */
const val BUSCA_MAX_MS = 6_000L

/** Notícias por busca. */
const val NOTICIAS_DA_BUSCA = 3

/** Frases que vão pra IA, no máximo (as de nota maior). */
const val FATOS_MAX = 6

/** Do artigo inteiro, só até aqui (os artigos grandes passam de 100 mil letras; a resposta quase sempre está antes). */
const val ARTIGO_MAX_LETRAS = 40_000

/** A Wikimedia pede que cada app se identifique; sem isso, a Wikipédia recusa. */
private const val AGENTE = "TourbillonAlarm/1.0 (https://github.com/luiz-nast/tourbillon-alarm)"

object Pesquisa {
    /** Procura [consulta] nas fontes, trata ([fatosRelevantes]) e junta ([resultadoDaBusca]); null sem nada (ou sem internet). Bloqueia a thread de quem chama, até [BUSCA_MAX_MS]. */
    fun buscar(consulta: String): String? {
        val inicio = System.currentTimeMillis()
        val pedidos = listOf(
            enderecoDaWikipedia(consulta, "en", artigos = 3, inteiro = false),
            enderecoDaWikipedia(consulta, "en", artigos = 1, inteiro = true),
            enderecoDaWikipedia(consulta, "pt", artigos = 2, inteiro = false),
        )
        val textos = arrayOfNulls<List<Pair<String, String>>>(pedidos.size)
        var noticias: List<String> = emptyList()
        // Numa thread de fundo, um erro sem tratar derrubaria o app (e o alarme): todo erro vira "nada"
        val threads = pedidos.mapIndexed { i, endereco ->
            thread(isDaemon = true, name = "busca-$i") {
                textos[i] = runCatching { baixarTexto(endereco, AGENTE)?.let(::paginasDaWikipedia) }.getOrNull()
            }
        } + thread(isDaemon = true, name = "noticias-busca") {
            noticias = runCatching { baixarTexto(enderecoDasNoticias(consulta))?.let { manchetesDoRss(it, NOTICIAS_DA_BUSCA) } }.getOrNull().orEmpty()
        }
        for (t in threads) t.join((BUSCA_MAX_MS - (System.currentTimeMillis() - inicio)).coerceAtLeast(1))
        val fontes = textos.filterNotNull().flatten()
        val fatos = fatosRelevantes(fontes, consulta)
        Log.i(TAG, "Busca: \"$consulta\": ${fontes.size} textos da Wikipédia, ${fatos.size} frases boas, ${noticias.size} notícias, em ${System.currentTimeMillis() - inicio} ms")
        return resultadoDaBusca(fatos, noticias)
    }
}

/**
 * A API da Wikipédia no [idioma], num pedido só: os [artigos] que mais batem com [consulta], com o começo de cada um
 * (ou, [inteiro], o artigo todo: a API só dá o artigo inteiro de um por vez) e com o trecho de cada um onde as
 * palavras da busca aparecem (o `snippet`).
 */
fun enderecoDaWikipedia(consulta: String, idioma: String = "en", artigos: Int = 3, inteiro: Boolean = false): String {
    val busca = URLEncoder.encode(consulta, "UTF-8")
    val texto = if (inteiro) "&explaintext=1&exlimit=1" else "&exintro=1&explaintext=1&exlimit=$artigos"
    return "https://$idioma.wikipedia.org/w/api.php?action=query&format=json&formatversion=2" +
        "&generator=search&gsrlimit=$artigos&gsrsearch=$busca&prop=extracts$texto" +
        "&list=search&srlimit=$artigos&srsearch=$busca&srprop=snippet"
}

/** O RSS de busca do Google Notícias, em inglês (as mais recentes sobre o assunto). */
fun enderecoDasNoticias(consulta: String): String =
    "https://news.google.com/rss/search?q=${URLEncoder.encode(consulta, "UTF-8")}&hl=en-US&gl=US&ceid=US:en"

/**
 * Os textos da resposta da Wikipédia ([json]): pra cada artigo, (título, texto), na ordem em que a busca os pôs. O
 * texto é o trecho onde a busca bateu (em HTML, vira texto simples), seguido do que a API mandou do artigo (o começo
 * ou ele inteiro, até [ARTIGO_MAX_LETRAS]). Sem nada, lista vazia.
 */
fun paginasDaWikipedia(json: String): List<Pair<String, String>> {
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
            val artigo = p.optString("extract").take(ARTIGO_MAX_LETRAS)
            val trecho = trechos[titulo].orEmpty()
            val texto = listOf(trecho, artigo).filter { it.isNotBlank() }.joinToString("\n").trim()
            if (texto.isEmpty()) null else titulo to texto
        }
}

/** Palavras que não dizem do que a busca trata (em inglês e em português). */
private val PALAVRAS_VAZIAS = setOf(
    "the", "and", "for", "with", "what", "who", "whom", "whose", "when", "where", "why", "how", "which", "does", "did",
    "was", "were", "are", "is", "has", "have", "had", "can", "could", "should", "would", "will", "about", "from", "into",
    "that", "this", "there", "their", "them", "they", "than", "then", "more", "most", "much", "many", "best", "get",
    "getting", "make", "made", "way", "ways", "some", "any", "all", "one", "you", "your", "its", "his", "her", "not",
    "que", "qual", "quais", "quem", "quando", "onde", "como", "porque", "por", "para", "com", "sem", "uma", "uns", "das",
    "dos", "nas", "nos", "pelo", "pela", "mais", "muito", "isso", "esse", "essa", "ele", "ela", "seu", "sua",
)

/** As palavras de [texto] que dizem do que ele trata: sem acento, minúsculas, de 3 letras ou mais, sem as [PALAVRAS_VAZIAS]. */
fun palavrasDaBusca(texto: String): Set<String> =
    semAcento(texto).split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in PALAVRAS_VAZIAS }.toSet()

/** Uma frase vai pra IA a partir desse tamanho (menos que isso é título ou pedaço) e até o outro (mais é tabela ou lista colada). */
private const val FRASE_MIN_LETRAS = 30
private const val FRASE_MAX_LETRAS = 400

/**
 * O tratamento antes da IA: os textos das [fontes] ((título, texto)) viram frases, e cada frase ganha nota pelas
 * palavras da [consulta] que tem (contando o começo da palavra: "invented" bate com "invention"), mais um pouco se
 * tiver número (data, preço, medida: costuma ser a resposta) e se for a primeira do artigo (diz o que a coisa é).
 * Vão as [max] de nota maior, sem repetir, cada uma com o título do artigo: "Transistor: The first working transistor
 * was invented in 1947…". Frase sem nenhuma palavra da busca não vai.
 */
fun fatosRelevantes(fontes: List<Pair<String, String>>, consulta: String, max: Int = FATOS_MAX): List<String> {
    val procuradas = palavrasDaBusca(consulta).map { it.take(5) }
    if (procuradas.isEmpty()) return emptyList()
    data class Frase(val texto: String, val titulo: String, val nota: Double, val ordem: Int)
    var ordem = 0
    val frases = fontes.flatMap { (titulo, texto) ->
        texto.replace(Regex("\\s+"), " ")
            .split(Regex("(?<=[.!?])\\s+(?=[A-Z0-9\"“(])"))
            .map { it.trim() }
            .filter { it.length in FRASE_MIN_LETRAS..FRASE_MAX_LETRAS }
            .mapIndexed { i, frase ->
                val delas = palavrasDaBusca(frase).map { it.take(5) }.toSet()
                val batem = procuradas.count { it in delas }
                val nota = batem + (if (frase.any(Char::isDigit)) 0.5 else 0.0) + (if (i == 0) 0.3 else 0.0)
                Frase(frase, titulo, if (batem == 0) 0.0 else nota, ordem++)
            }
    }
    return frases.filter { it.nota > 0 }
        .sortedWith(compareByDescending<Frase> { it.nota }.thenBy { it.ordem })
        .distinctBy { semAcento(it.texto).take(60) }
        .take(max)
        .map { "${it.titulo}: ${it.texto}" }
}

/**
 * O que vai pra IA: os [fatos] tratados e as [noticias] (sem o nome do jornal, no fim de cada título), até
 * [RESULTADO_MAX_LETRAS] no total; null sem nada.
 */
fun resultadoDaBusca(fatos: List<String>, noticias: List<String>): String? {
    if (fatos.isEmpty() && noticias.isEmpty()) return null
    val texto = buildString {
        if (fatos.isNotEmpty()) {
            appendLine("Best facts found:")
            fatos.forEach { appendLine("- $it") }
        }
        if (noticias.isNotEmpty()) {
            appendLine("Recent news:")
            noticias.forEach { appendLine("- ${it.substringBeforeLast(" - ").trim()}") }
        }
    }.trim()
    return texto.take(RESULTADO_MAX_LETRAS)
}
