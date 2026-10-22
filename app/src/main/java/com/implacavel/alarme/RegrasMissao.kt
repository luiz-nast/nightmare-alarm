// Regras da missão pra desligar o alarme (dizer STOP e olhar pra câmera). Funções puras, testadas em MissaoTest.
package com.implacavel.alarme

import java.net.URI
import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.roundToInt

/** No botão "Testar câmera + frases" a câmera pede só isto (metade fácil, metade exigente): teste tem que ser rápido. */
const val META_OLHAR_TESTE_MS = 30_000L

/**
 * Sem olhar pra câmera por esse tempo, aparece na tela quanto falta pra zerar e toca um bipe; depois,
 * a cada mais esse tempo, um bipe mais alto ([volumeDoBipe]).
 */
const val AVISO_SEM_OLHAR_MS = 3_000L

/**
 * Sem sinal de que a pessoa está olhando pra câmera por esse tempo, a vigia do AlarmeService zera o
 * anel e volta a tocar a música: a missão recomeça. Fechar a tela traz a música de volta, mas não zera.
 */
const val DESISTENCIA_MS = 20_000L

/** Bipes antes de zerar: um a cada [AVISO_SEM_OLHAR_MS], todos antes de [DESISTENCIA_MS] (aos 3, 6 … 18 s). */
val BIPES_ATE_ZERAR = ((DESISTENCIA_MS - 1) / AVISO_SEM_OLHAR_MS).toInt()

/** O que a câmera está vendo agora. */
enum class Leitura { SEM_CAMERA, SEM_ROSTO, OLHOS_FECHADOS, OLHANDO }

/** Quanto tempo de olhos abertos a missão do [alarme] pede: o modo fácil mais o exigente dos [tempos] (de começo, 20 + 20 min). */
fun metaOlhar(alarme: Alarme, tempos: Tempos = TEMPOS_PADRAO): Long = if (alarme.deTeste) META_OLHAR_TESTE_MS else minutos(tempos.cameraMin)

/**
 * Com quanto tempo de olhos abertos vem a verificação do meio (e, depois dela, o modo exigente): o modo fácil dos
 * [tempos] (de começo, 20 min); no teste, a metade.
 */
fun verificacaoEm(alarme: Alarme, tempos: Tempos = TEMPOS_PADRAO): Long = if (alarme.deTeste) META_OLHAR_TESTE_MS / 2 else minutos(tempos.cameraFacilMin)

/** Aceita "stop" e jeitos de falar ou transcrever com sotaque ("estop", "istópi", "stopi"). */
fun disseStop(texto: String): Boolean {
    val semAcento = Normalizer.normalize(texto.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
    return Regex("\\b[ei]?stop").containsMatchIn(semAcento)
}

/**
 * Olho aberto pro ML Kit: a média dos dois olhos (probabilidade de aberto) acima disto. Olho fechado
 * dá perto de 0. Cada olho acima de 60% obrigava a arregalar; a média acima de 30% deixava passar
 * quem ainda estava dormindo (o dono voltou a dormir depois da missão); 45% ficou rude demais na
 * primeira metade, ainda acordando. 40% fica no meio.
 */
private const val OLHO_ABERTO = 0.40f

/** Na segunda metade da atividade, depois da verificação, um pouco mais: ainda sem arregalar (60% em cada olho). */
private const val OLHO_ABERTO_EXIGENTE = 0.55f

/** O mínimo de [olhoAberto] pra contar como olhando: [OLHO_ABERTO], ou [OLHO_ABERTO_EXIGENTE] se [exigente]. */
fun minimoOlhoAberto(exigente: Boolean): Float = if (exigente) OLHO_ABERTO_EXIGENTE else OLHO_ABERTO

/**
 * A certeza do ML Kit de que os olhos estão abertos, de 0 (fechados) a 1 (abertos): a média dos olhos
 * que ele vê. Null sem nenhum olho.
 */
fun olhoAberto(olhoEsquerdo: Float?, olhoDireito: Float?): Float? =
    listOfNotNull(olhoEsquerdo, olhoDireito).takeIf { it.isNotEmpty() }?.average()?.toFloat()

/**
 * Olhando = os olhos abertos acima do [minimoOlhoAberto], com o rosto em qualquer ângulo (não precisa
 * estar de frente: quem está acordando não fica reto). Sem informação de nenhum olho, conta como fechado.
 */
fun classificarRosto(olhoEsquerdo: Float?, olhoDireito: Float?, exigente: Boolean = false): Leitura {
    val aberto = olhoAberto(olhoEsquerdo, olhoDireito)
    return if (aberto != null && aberto > minimoOlhoAberto(exigente)) Leitura.OLHANDO else Leitura.OLHOS_FECHADOS
}

/**
 * Tempo de olhos abertos acumulado no anel da câmera, depois de [passoMs] (o tempo real desde a
 * última conferência): só sobe se a pessoa está [olhando] agora. Sem isso, para na hora e nunca desce.
 */
fun avancarOlhar(olhadoMs: Long, olhando: Boolean, passoMs: Long): Long = if (olhando) olhadoMs + passoMs else olhadoMs

/** A cada tanto de câmera completa, a voz avisa ("10 minutes passed"). */
const val MARCO_MS = 10 * 60_000L

/**
 * Marco de [MARCO_MS] completado quando o anel passou de [antesMs] pra [depoisMs], em minutos (10, 30),
 * ou null. O ponto da verificação ([verificacaoMs]) não conta (aí vem ela), nem o fim (o alarme já desliga).
 */
fun marcoCompletado(antesMs: Long, depoisMs: Long, metaMs: Long, verificacaoMs: Long = metaMs / 2): Int? {
    val marco = depoisMs / MARCO_MS * MARCO_MS
    return if (marco > antesMs && marco in 1 until metaMs && marco != verificacaoMs) (marco / 60_000).toInt() else null
}

/**
 * As fases que os botões "Back", "Cancel test" e "Next" da tela do alarme percorrem, só nos alarmes dos botões
 * de teste: no "câmera + frases", câmera fácil, frases (se os ajustes pedem alguma) e câmera exigente; no "conversa +
 * caminhada", conversa, prazo pra sair e caminhada. O serviço muda de fase ([AlarmeService.irPraFase]); a tela só diz
 * em qual está ([faseDoTeste]).
 */
enum class FaseDoTeste(val rotulo: String) {
    CAMERA_FACIL("Camera"), FRASES("Phrases"), CAMERA_EXIGENTE("Camera 2"),
    CONVERSA("Talk"), PREPARO("Wait"), CAMINHADA("Walk");

    /** As fases do teste a que esta pertence, na ordem; sem frases nos ajustes ([comFrases] false), a câmera vai direto da fácil pra exigente. */
    fun fases(comFrases: Boolean = true): List<FaseDoTeste> = when {
        this !in FASES_DA_CAMERA -> FASES_DA_CAMINHADA
        comFrases -> FASES_DA_CAMERA
        else -> FASES_DA_CAMERA - FRASES
    }

    fun anterior(comFrases: Boolean = true): FaseDoTeste? = fases(comFrases).let { it.getOrNull(it.indexOf(this) - 1) }
    fun proxima(comFrases: Boolean = true): FaseDoTeste? = fases(comFrases).let { it.getOrNull(it.indexOf(this) + 1) }
}

val FASES_DA_CAMERA = listOf(FaseDoTeste.CAMERA_FACIL, FaseDoTeste.FRASES, FaseDoTeste.CAMERA_EXIGENTE)
val FASES_DA_CAMINHADA = listOf(FaseDoTeste.CONVERSA, FaseDoTeste.PREPARO, FaseDoTeste.CAMINHADA)

/**
 * Em que fase do teste a tela do alarme está, pelo que o serviço mostra: [conversa] e [caminhada] são as etapas da
 * manhã ([noPrazo] = ainda no prazo pra sair); [verificacao] e [metadeVerificada], as da câmera. Null fora das fases
 * (a música, o bom dia, a comemoração).
 */
fun faseDoTeste(conversa: Boolean, caminhada: Boolean, noPrazo: Boolean, verificacao: Boolean, metadeVerificada: Boolean, naCamera: Boolean): FaseDoTeste? = when {
    conversa -> FaseDoTeste.CONVERSA
    caminhada && noPrazo -> FaseDoTeste.PREPARO
    caminhada -> FaseDoTeste.CAMINHADA
    verificacao -> FaseDoTeste.FRASES
    naCamera && metadeVerificada -> FaseDoTeste.CAMERA_EXIGENTE
    naCamera -> FaseDoTeste.CAMERA_FACIL
    else -> null
}

/** Na verificação do meio, sem responder nada por esse tempo, um apito alto; a cada mais esse tempo, outro. */
const val SILENCIO_APITO_MS = 5_000L

/** Sem responder nada na verificação por esse tempo, a missão zera (como os 20 s sem olhar). */
const val SILENCIO_ZERA_MS = 20_000L

/**
 * Prazo de cada frase da verificação: depois dele, falar sem acertar a frase não segura mais a contagem
 * do silêncio (senão uma TV ligada, ou resmungar da cama, prenderia a verificação pra sempre).
 */
const val PRAZO_FRASE_MS = 30_000L

/**
 * A pessoa repetiu a [frase] no que o reconhecedor [ouviu]: pelo menos 70% das palavras dela, aceitando
 * uma letra trocada nas palavras grandes (o reconhecedor erra sotaque) e as contrações ("I'm" = "I am").
 */
fun repetiu(frase: String, ouviu: String): Boolean {
    val acertos = acertos(palavras(frase), ouviu)
    return acertos.isNotEmpty() && acertos.count { it } >= ceil(acertos.size * 0.7)
}

/**
 * Cada palavra da [frase], como aparece na tela, e se ela já está no que o reconhecedor [ouviu] (a tela
 * pinta de verde enquanto a pessoa fala), pela mesma conta de [repetiu].
 */
fun palavrasDitas(frase: String, ouviu: String): List<Pair<String, Boolean>> {
    val pedacos = frase.split(" ").filter { it.isNotBlank() }
    val porPedaco = pedacos.map(::palavras) // "I'm" são duas: "i" e "am"
    val acertos = acertos(porPedaco.flatten(), ouviu).iterator()
    return pedacos.zip(porPedaco) { pedaco, ps -> pedaco to ps.map { acertos.next() }.all { it } }
}

/**
 * Pra cada palavra do [alvo], se ela está no que foi [ouviu]. Cada palavra ouvida vale pra uma só do
 * alvo ("I rise early" não conta o "I" e o "rise" duas vezes); primeiro as iguais, depois as parecidas.
 */
private fun acertos(alvo: List<String>, ouviu: String): List<Boolean> {
    val sobra = palavras(ouviu).toMutableList()
    val iguais = alvo.map { sobra.remove(it) }
    return alvo.zip(iguais) { p, igual ->
        if (igual) return@zip true
        val i = if (p.length >= 4) sobra.indexOfFirst { distancia(it, p) <= 1 } else -1
        if (i >= 0) sobra.removeAt(i)
        i >= 0
    }
}

private fun palavras(texto: String): List<String> =
    Normalizer.normalize(texto.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        .replace("’", "'").replace("i'm", "i am").replace("n't", " not").replace("'re", " are").replace("'ll", " will")
        .replace("'ve", " have").replace("it's", "it is").replace("'", "")
        .split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }

/** Quantas letras trocar, pôr ou tirar pra uma palavra virar a outra. */
private fun distancia(a: String, b: String): Int {
    var anterior = IntArray(b.length + 1) { it }
    for (i in a.indices) {
        val atual = IntArray(b.length + 1)
        atual[0] = i + 1
        for (j in b.indices) atual[j + 1] = minOf(anterior[j + 1] + 1, atual[j] + 1, anterior[j] + if (a[i] == b[j]) 0 else 1)
        anterior = atual
    }
    return anterior[b.length]
}

/** Contagem na tela: depois de [AVISO_SEM_OLHAR_MS] sem olhar, os segundos que faltam pro anel zerar; antes, null. */
fun segundosParaZerar(semOlharMs: Long): Int? =
    if (semOlharMs < AVISO_SEM_OLHAR_MS) null else ceil((DESISTENCIA_MS - semOlharMs) / 1000.0).toInt().coerceAtLeast(0)

/**
 * Volume de alarme do celular no bipe número [n] (1 = o primeiro), num celular com [maximo] degraus:
 * sobe por igual a cada bipe, do levinho (1/[degraus] do máximo) ao bipe [degraus], no máximo (na câmera e na
 * conversa, [BIPES_ATE_ZERAR]; na caminhada, [BIPES_PARADO_ATE_O_MAXIMO]). Os degraus do Android já seguem o ouvido
 * (cada um, uns dB a mais), e o bipe passa por cima da trava do volume do alarme.
 */
fun volumeDoBipe(n: Int, maximo: Int, degraus: Int = BIPES_ATE_ZERAR): Int =
    (maximo * n.coerceIn(1, degraus).toFloat() / degraus).roundToInt().coerceAtLeast(1)

/**
 * A janela que apareceu é o menu de desligar do celular ([VigiaDoDesligar])? Pelo pacote do menu da Samsung, pela classe
 * (a do Android puro tem "GlobalActions" no nome) ou pelos [textos] dela: "Power off" E "Restart" juntos (em inglês ou
 * português), que só esse menu tem. Uma palavra só não basta: a barra de notificações tem "Restart to finish updating" ou
 * "Toque para desligar", e fechá-la seria um susto à toa. Sem o pacote do sistema, não é.
 */
fun ehMenuDeDesligar(pacote: String?, classe: String?, textos: List<String>): Boolean {
    if (pacote == null || !PACOTES_DO_SISTEMA.any { pacote == it || pacote.startsWith("$it.") }) return false
    if (pacote.startsWith(PACOTE_DO_MENU_DA_SAMSUNG)) return true
    if (classe?.contains("GlobalActions", ignoreCase = true) == true) return true
    val tudo = textos.joinToString(" ").lowercase()
    return TEXTOS_DE_DESLIGAR.any { it in tudo } && TEXTOS_DE_REINICIAR.any { it in tudo }
}

/** O atalho de acessibilidade do celular que liga a vigia do menu de desligar ([VigiaDoDesligar.atalho]). */
enum class AtalhoDaVigia { VOLUME, BOTAO }

/**
 * O aviso da vigia do menu de desligar na tela do alarme: ligada ([noAr]), o cadeado; desligada, com o [atalho] de
 * acessibilidade configurado e sem a permissão do cabo ([automatica], que liga sozinha), o pedido pra ligar; sem jeito
 * de ligar, nada (o alarme segue igual).
 */
fun avisoDaVigia(noAr: Boolean, automatica: Boolean, atalho: AtalhoDaVigia?): String? = when {
    noAr -> "🔒 Power menu blocked"
    automatica -> null
    atalho == AtalhoDaVigia.VOLUME -> "Hold both volume keys for 3 s to block the power menu"
    atalho == AtalhoDaVigia.BOTAO -> "Tap the accessibility button to block the power menu"
    else -> null
}

private const val PACOTE_DO_MENU_DA_SAMSUNG = "com.samsung.android.globalactions"
private val PACOTES_DO_SISTEMA = listOf("com.android.systemui", PACOTE_DO_MENU_DA_SAMSUNG, "android")
private val TEXTOS_DE_DESLIGAR = listOf("power off", "desligar")
private val TEXTOS_DE_REINICIAR = listOf("restart", "reiniciar")

/**
 * A lista dos serviços de acessibilidade ligados no celular ([atual], separados por ":", como o Android guarda), com a
 * vigia ([nosso], "pacote/classe"; [curto] é a mesma em "pacote/.Classe") ligada ou não. Os outros (o do Alarmy, por
 * exemplo) ficam como estão, na mesma ordem.
 */
fun listaDeAcessibilidade(atual: String?, nosso: String, curto: String, ligada: Boolean): String {
    val outros = atual.orEmpty().split(':').filter { it.isNotBlank() && it != nosso && it != curto }
    return (if (ligada) outros + nosso else outros).joinToString(":")
}

/** Sites que a página do YouTube abre: o próprio YouTube e o Google (aviso de cookies e login). */
private val SITES_DA_PAGINA = listOf("youtube.com", "youtu.be", "google.com", "google.com.br")

/**
 * Na página do YouTube da etapa da câmera, só abre (ali mesmo) link de site. Os outros ("intent:",
 * "vnd.youtube:", "market:"...) abririam outro app por cima do alarme. Na página [principal], só o
 * YouTube e o Google: o resto também fica sem efeito. Partes de dentro da página (anúncios, vídeos
 * embutidos) vêm de qualquer site.
 */
fun linkFicaNaPagina(url: String, principal: Boolean = true): Boolean {
    val endereco = runCatching { URI(url) }.getOrNull() ?: return false
    if (endereco.scheme?.lowercase() !in setOf("https", "http")) return false
    val site = endereco.host?.lowercase() ?: return false
    return !principal || SITES_DA_PAGINA.any { site == it || site.endsWith(".$it") }
}
