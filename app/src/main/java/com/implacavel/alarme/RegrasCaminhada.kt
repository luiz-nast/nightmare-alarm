// Regras da caminhada da manhã (despertador "conversa + caminhada"): só conta o tempo andando a pé, na rua,
// com GPS de verdade. Chova ou faça sol, com ou sem internet (o GPS ouve os satélites direto), não tem plano B:
// parado, os bipes não param. Funções e classes puras (sem o Android), testadas em CaminhadaTest.
//
// Como a anti-trapaça do Uber e dos apps de passos: sentidos que não se enganam juntos têm que concordar. O
// acelerômetro sente o passo ([DetectorDePassos]); o GPS mede a velocidade pelo Doppler (o desvio da frequência dos
// satélites, que não escorrega como a posição) e a direção; e a posição do GPS tem que andar junto com essa
// velocidade ([andou]). Caminhada é andar, não correr: em volta da piscina ou indo e voltando num corredor também
// conta. O que não conta é ficar no lugar (balançando o celular na cama, o GPS pode até medir velocidade, mas o ponto
// não sai dali). Medido no S24 FE (10-17): parado, a posição crua escorregou 15 m em 70 s dizendo ±1 m de precisão,
// e a velocidade ficou em 0,00; andando em volta da piscina, a velocidade dava ~1,1 m/s e a posição andava junto (8°
// de diferença na direção, no meio).
//
// Duas contas separadas: o que SOMA (só a caminhada que o GPS confirma, ou os passos logo depois dela) e o que CALA
// OS BIPES (qualquer sinal de vida: passo no ritmo, ou o GPS vendo velocidade de andar). Num corredor coberto o GPS
// não vê a velocidade, e o dono andava (até correndo) ouvindo "parado" e bipes cada vez mais altos (10-17, à noite,
// com vizinho e família ouvindo): isso não pode. Quem balança o celular na cama não soma, mas também não ouve bipe:
// está acordado, e o alarme só desliga andando de verdade.
package com.implacavel.alarme

import java.util.Locale
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Tempo andando pra desligar o alarme com caminhada: parado, o relógio não anda. */
const val CAMINHADA_MS = 16 * 60_000L

/** No botão "Test talk + walk", 3 min: dá pra sentir a regra do GPS andando de verdade (8 min era insuportável). */
const val CAMINHADA_TESTE_MS = 3 * 60_000L

/** Depois da conversa, o prazo pra se vestir e sair de casa: andar nele já conta, e parado não bipa. */
const val PRAZO_SAIR_MS = 4 * 60_000L

const val PRAZO_SAIR_TESTE_MS = 30_000L

/**
 * Depois do prazo pra sair, parado (sem sinal de vida: nem passo, nem velocidade no GPS, [Avaliacao.valeProBipe]) por
 * esse tempo: um bipe fraco ([primeiroBipeParado]); depois, um a cada [ENTRE_BIPES_PARADO_MS], um degrau de volume
 * mais alto, até o máximo no bipe [BIPES_PARADO_ATE_O_MAXIMO] (aos [PARADO_MAXIMO_MS]: 41 s), com o som forte, até
 * voltar a andar. Não zera nada. Pedido do dono (10-17): "20 segundos tranquilo sem beep, beep fraco em 23 s
 * crescendo de 3 em 3 s até 40 s virar o insuportável", tudo na tela ([bipeDoParado], [textoDoParado]).
 */
const val PARADO_MS = 23_000L

const val ENTRE_BIPES_PARADO_MS = 3_000L

const val BIPES_PARADO_ATE_O_MAXIMO = 7

const val PARADO_MAXIMO_MS = PARADO_MS + (BIPES_PARADO_ATE_O_MAXIMO - 1) * ENTRE_BIPES_PARADO_MS

/** Fixo com incerteza maior que isso (dentro de casa, entre prédios) não conta. Era 25 m: na cama, perto da janela, passava. */
const val PRECISAO_MAX_M = 20f

/** Fixo que chega mais velho que isso (o GPS engasgou) não conta. */
private const val FIXO_VELHO_MS = 2_500L

/** O máximo que um fixo soma: um buraco no sinal não vira minutos andados. */
private const val PASSO_MAX_MS = 3_000L

/**
 * Andando ou correndo a pé, pela velocidade do GPS (a mediana dos últimos [FIXOS_DA_VELOCIDADE] fixos): de 0,6 m/s
 * (passo lento) a 4 m/s (corrida leve). Mais rápido é carro, ônibus ou bicicleta. Com incerteza (a mediana) maior que
 * [PRECISAO_VELOCIDADE_MAX], a velocidade não vale.
 */
private const val VELOCIDADE_MIN = 0.6f
private const val VELOCIDADE_MAX = 4.0f
private const val PRECISAO_VELOCIDADE_MAX = 1.0f
private const val FIXOS_DA_VELOCIDADE = 5

/**
 * Sem passo reconhecido (o celular parado na mão, o acelerômetro perdeu o ritmo: na caminhada de 10-17 à noite, 15
 * de 30 s com o GPS a 0,8 m/s), o GPS sozinho ainda conta, se confirmar ([andou]) e até esta velocidade: a de quem
 * anda apressado. Mais rápido sem passo é bicicleta.
 */
private const val SEM_PASSO_VELOCIDADE_MAX = 2.0f

/** Mais rápido que [VELOCIDADE_MAX] por esse tempo: de carro, e nada conta por [RAPIDO_SEM_CONTAR_MS]. */
private const val RAPIDO_POR_MS = 3_000L
private const val RAPIDO_SEM_CONTAR_MS = 30_000L

/** Passos por minuto de quem anda ou corre (o passo do [DetectorDePassos]). */
private const val CADENCIA_MIN = 70f
private const val CADENCIA_MAX = 200f

/**
 * A passada (metros por passo: a velocidade do GPS dividida pelos passos do acelerômetro) de quem anda a pé: andando
 * normal, uns 0,7 m; correndo, até 1,3 m. Mais longa, o celular anda mais que as pernas (bicicleta, carro devagar).
 */
private const val PASSADA_MAX_M = 1.40f

/**
 * A janela dos últimos fixos válidos em que a direção ([coerencia]) e o deslocamento ([andou]) são medidos. Era 8 s:
 * andando devagar (abaixo de 0,83 m/s), o ponto não chegava a andar os 5 m nela e nunca confirmava.
 */
private const val JANELA_MS = 12_000L

/**
 * A direção, só pro log: na janela (até [RUMOS_USADOS] fixos com velocidade de pelo menos [RUMO_VELOCIDADE_MIN] e
 * rumo com incerteza até [RUMO_PRECISAO_MAX]), quanto a soma dos rumos pesados pela velocidade aponta pra algum lado
 * ([coerencia]). Andando reto dá ~0,95; nas curvas da piscina passou de 0,5 em 87 a 93% dos segundos; o braço
 * balançando dá rumo pra todo lado, perto de 0. Com menos de [RUMOS_MIN] rumos, null. Quem decide é [andou], que já
 * exige que a velocidade e o rumo somados cheguem a algum lugar.
 */
private const val RUMOS_USADOS = 6
private const val RUMOS_MIN = 5
private const val RUMO_VELOCIDADE_MIN = 0.3f
private const val RUMO_PRECISAO_MAX = 45f

/**
 * Andou de verdade ([andou]): na janela (cobrindo pelo menos [ANDOU_JANELA_MIN_MS]), o ponto do GPS (da média dos
 * [PONTAS] primeiros fixos pra média dos [PONTAS] últimos: o tremido some) andou pelo menos [ANDOU_MIN_M] e metade
 * ([ANDOU_FRACAO]) do que a velocidade e o rumo medidos dizem que andou (que tem que passar de
 * [ANDOU_PELA_VELOCIDADE_MIN_M] e ser um trajeto, não um vai e vem pra todo lado: pelo menos [ANDOU_RETO_MIN] do
 * caminho percorrido, como andando com uma esquina no meio; o celular balançando dá rumo pra todo lado), pro mesmo
 * lado (até [ANDOU_ANGULO_MAX]), sem salto no meio, e por um trajeto liso: de ponta a ponta, pelo menos
 * [ANDOU_LISO_MIN] do que os fixos percorreram um a um (andando em volta da piscina, passou em 81 a 97% das janelas;
 * o ponto só tremendo no lugar, ±3 m, nunca passou de 0,33). Com os segundos que ficam conferindo, nas três
 * gravações em volta da piscina somou de 93% a 99% do tempo andando na área aberta; parado, nada.
 */
private const val ANDOU_JANELA_MIN_MS = 10_000L
private const val PONTAS = 3
private const val ANDOU_MIN_M = 5.0
private const val ANDOU_FRACAO = 0.5
private const val ANDOU_PELA_VELOCIDADE_MIN_M = 4.0
private const val ANDOU_ANGULO_MAX = 60.0
private const val ANDOU_RETO_MIN = 0.5
private const val ANDOU_LISO_MIN = 0.4

/** Salto do ponto: de um fixo pro seguinte, mais do que andar a [VELOCIDADE_MAX] mais isto. É o GPS errando. */
private const val SALTO_FOLGA_M = 10.0

/**
 * Andando (passos no ritmo, ou o GPS vendo velocidade), mas o GPS ainda sem confirmar que saiu do lugar ([andou]), os
 * segundos ficam "conferindo" (pendentes) e somam todos de uma vez na próxima confirmação. Por até [CONFERINDO_MAX_MS]:
 * sem confirmar nesse tempo, o GPS não vê a pessoa sair do lugar (o celular balançando, ou dentro de casa), e não
 * somam ("não saiu do lugar", em vermelho na tela; os bipes só voltam sem passo nenhum). Parado de verdade por
 * [CONFERINDO_RECOMECA_MS], a conferência e a confiança recomeçam do zero.
 */
private const val CONFERINDO_MAX_MS = 60_000L
private const val CONFERINDO_RECOMECA_MS = 30_000L

/**
 * A confiança: cada segundo que o GPS confirma andando guarda um segundo (até este tanto), e os passos no ritmo
 * sem o GPS confirmar (um corredor coberto, o GPS perdendo a velocidade por um trecho) gastam dela e somam direto,
 * sem esperar. Quem só balança o celular na cama não tem confiança (nunca saiu do lugar) e não soma nada; quem andou
 * na rua 1 min e deitou pra balançar ganha no máximo esse 1 min, e o anel para: só andando de verdade o alarme desliga.
 */
private const val CONFIANCA_MAX_MS = 90_000L

/**
 * Nos primeiros segundos andando, o GPS ainda está percebendo (a velocidade sobe em 2 a 4 s, o rumo vem logo
 * depois): por esse tempo, sem velocidade de andar é "começando", não "no lugar" (só muda a mensagem da tela).
 */
private const val COMECANDO_MS = 8_000L

/** Parado de verdade: sem passo nenhum por esse tempo e o GPS abaixo de [PARADO_VELOCIDADE] (o caminho congela). */
private const val PARADO_SEM_PASSO_MS = 3_000L
private const val PARADO_VELOCIDADE = 0.4f

private const val RAIO_DA_TERRA_M = 6_371_000.0

/** Quanto tempo andando a caminhada do [alarme] pede. */
fun metaCaminhada(alarme: Alarme): Long = if (alarme.deTeste) CAMINHADA_TESTE_MS else CAMINHADA_MS

/** O prazo pra sair de casa do [alarme], depois da conversa. */
fun prazoSair(alarme: Alarme): Long = if (alarme.deTeste) PRAZO_SAIR_TESTE_MS else PRAZO_SAIR_MS

/**
 * Um fixo do GPS, sem o Location do Android (pra testar): [tempoMs] no relógio que não pula
 * (SystemClock.elapsedRealtime), [precisaoM] o raio de incerteza em metros (null = o GPS não disse),
 * [velocidadeMs] a velocidade medida pelo GPS (null = não mediu), [precisaoVelocidade] a incerteza
 * dela (null = não disse), se veio de um app de localização [falso], o [rumo] medido (pra onde o celular vai, em
 * graus do norte, no sentido do relógio; null = não mediu) e a incerteza dele, [precisaoRumo] (null = não disse).
 */
data class Fixo(
    val tempoMs: Long,
    val latitude: Double,
    val longitude: Double,
    val precisaoM: Float?,
    val velocidadeMs: Float? = null,
    val precisaoVelocidade: Float? = null,
    val falso: Boolean = false,
    val rumo: Float? = null,
    val precisaoRumo: Float? = null,
)

/** O que um fixo do GPS diz da caminhada. CONFERINDO: andando, mas ainda sem ter saído do lugar (não somou ainda). */
enum class Movimento { FALSO, IMPRECISO, PARADO, CONFERINDO, ANDANDO, RAPIDO_DEMAIS }

/** O fixo vale pra conta: não é localização falsa e tem precisão de rua ([PRECISAO_MAX_M]). */
fun fixoValido(fixo: Fixo): Boolean = !fixo.falso && fixo.precisaoM != null && fixo.precisaoM <= PRECISAO_MAX_M

/** Distância em metros entre dois fixos, pela fórmula de haversine (a Terra como uma esfera: de sobra pra passos). */
fun distanciaM(a: Fixo, b: Fixo): Double {
    val latA = Math.toRadians(a.latitude)
    val latB = Math.toRadians(b.latitude)
    val meioLat = sin((latB - latA) / 2)
    val meioLon = sin(Math.toRadians(b.longitude - a.longitude) / 2)
    val h = meioLat * meioLat + cos(latA) * cos(latB) * meioLon * meioLon
    return 2 * RAIO_DA_TERRA_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** Onde [fixo] fica, em metros, a leste e ao norte da [origem] (o chão como um plano: de sobra pra uma caminhada). */
fun emMetros(origem: Fixo, fixo: Fixo): Pair<Double, Double> {
    val leste = Math.toRadians(fixo.longitude - origem.longitude) * RAIO_DA_TERRA_M * cos(Math.toRadians(origem.latitude))
    val norte = Math.toRadians(fixo.latitude - origem.latitude) * RAIO_DA_TERRA_M
    return leste to norte
}

/**
 * Os passos, pelo acelerômetro (~50 amostras por segundo, o tamanho da aceleração em m/s²; o jeito de "Bangle.js"
 * e dos contadores de passo do Android): tira a gravidade (a média lenta), suaviza, e cada pico forte o bastante
 * é um passo. Andando ([andando]) depois de [PASSOS_PRA_ANDAR] passos seguidos no ritmo de quem anda (um a cada 0,3
 * a 1 s); para depois de [SEM_PASSO_PRA_PARAR_MS] sem passo. Dois picos colados (menos de [COLADO_MS]) são um passo
 * só: o segundo é ignorado (era um "para", e correndo, ou com o celular na mão, derrubava o ritmo e pedia 6 passos
 * novos: segundos de "parado" andando). Na gravação de 10-17: andando, reconheceu 97% dos segundos; parado, nenhum.
 * Sacudir o celular, sentado, também passa: quem barra isso é o GPS.
 */
class DetectorDePassos {
    private var gravidade = Float.NaN
    private var ultimaAmostra = 0L
    private val semGravidade = ArrayDeque<Float>() // as últimas [AMOSTRAS_2S], sem a gravidade
    private val suave = ArrayDeque<Pair<Long, Float>>() // a média das últimas [SUAVIZAR] de cima, com o tempo
    private val intervalos = ArrayDeque<Long>() // os últimos [INTERVALOS_DA_CADENCIA] intervalos entre passos no ritmo
    private var seguidos = 0

    /** Andando: passos seguidos no ritmo. */
    var andando = false
        private set

    /** Desde quando está [andando] (o relógio das amostras), ou null parado. */
    var andandoDesde: Long? = null
        private set

    /** Quando foi o último passo (o relógio das amostras), ou null antes do primeiro. */
    var ultimoPasso: Long? = null
        private set

    /** Passos por minuto (pelos últimos intervalos no ritmo), ou null sem eles. */
    val cadencia: Float?
        get() = if (intervalos.isEmpty()) null else 60_000f / mediana(intervalos.map { it.toFloat() })!!

    /** Uma amostra do acelerômetro: [tamanho] da aceleração (m/s², com a gravidade) no instante [tempoMs]. */
    fun amostra(tempoMs: Long, tamanho: Float) {
        val dt = if (ultimaAmostra == 0L) 0f else ((tempoMs - ultimaAmostra) / 1000f).coerceIn(0f, 0.1f)
        ultimaAmostra = tempoMs
        gravidade = if (gravidade.isNaN()) tamanho else gravidade + (tamanho - gravidade) * dt / GRAVIDADE_S
        semGravidade.addLast(tamanho - gravidade)
        if (semGravidade.size > AMOSTRAS_2S) semGravidade.removeFirst()
        suave.addLast(tempoMs to semGravidade.takeLast(SUAVIZAR).average().toFloat())
        if (suave.size > PICO_JANELA) suave.removeFirst()
        // O pico é o do meio da janela de suave (0,15 s pra cada lado): o passo chega com esse atraso
        if (suave.size == PICO_JANELA) {
            val (quando, altura) = suave[PICO_JANELA / 2]
            val forte = maxOf(PICO_MIN, PICO_FATOR * desvio(semGravidade))
            if (altura == suave.maxOf { it.second } && desvio(semGravidade.takeLast(AMOSTRAS_1S)) >= ATIVIDADE_MIN && altura >= forte) passo(quando)
        }
        // Sem passo por um tempo: para (e esquece o ritmo, também o de uns passos soltos que não chegaram a andar)
        ultimoPasso?.let { if ((andando || seguidos > 0) && tempoMs - it > SEM_PASSO_PRA_PARAR_MS) parar() }
    }

    private fun passo(quando: Long) {
        val anterior = ultimoPasso
        // Colado no passo anterior: é o mesmo passo (o calcanhar e a ponta do pé), não dois
        if (anterior != null && quando - anterior < COLADO_MS) return
        if (anterior != null && quando - anterior <= INTERVALO_MAX_MS) {
            seguidos++
            intervalos.addLast(quando - anterior)
            if (intervalos.size > INTERVALOS_DA_CADENCIA) intervalos.removeFirst()
        } else if (!andando) {
            seguidos = 1
            intervalos.clear()
        }
        // Andando, um intervalo longo é um passo que o pico não pegou (aconteceu 35 vezes em 7 min): o ritmo segue
        ultimoPasso = quando
        if (seguidos >= PASSOS_PRA_ANDAR && !andando) {
            andando = true
            andandoDesde = quando
        }
    }

    private fun parar() {
        andando = false
        andandoDesde = null
        seguidos = 0
        intervalos.clear()
    }

    private companion object {
        const val GRAVIDADE_S = 1f // a média lenta que separa a gravidade do passo
        const val AMOSTRAS_1S = 50
        const val AMOSTRAS_2S = 100
        const val SUAVIZAR = 10 // 0,2 s
        const val PICO_JANELA = 15 // o pico tem que ser o maior de 0,15 s pra cada lado
        const val ATIVIDADE_MIN = 0.5f // desvio em 1 s: parado dá 0,01; andando, 1 a 2,5
        const val PICO_MIN = 0.5f
        const val PICO_FATOR = 0.6f // do desvio em 2 s
        const val COLADO_MS = 300L // dois picos mais perto que isso são um passo só
        const val INTERVALO_MAX_MS = 1_000L // mais longe, o ritmo recomeça
        const val PASSOS_PRA_ANDAR = 6 // uns 3 s
        const val SEM_PASSO_PRA_PARAR_MS = 2_000L
        const val INTERVALOS_DA_CADENCIA = 6
    }
}

/**
 * O caminho filtrado, em metros a leste e ao norte do primeiro fixo ([origem]): o que a tela desenha. Como o filtro
 * do Uber, o fixo cru é só uma pista: andando, o ponto segue a velocidade e o rumo
 * do GPS e é puxado devagar ([GANHO]) pro fixo; parado ([fixo] com parado = true), congela (o GPS parado escorrega
 * dizendo ±1 m). Fixo longe demais ([portao]) é um salto do GPS e não conta; [FORA_PRA_ALCANCAR] seguidos e o ponto
 * vai atrás, com mais força, até alcançar; parado, ou se nem assim alcança, pula pra lá (sem travar nunca).
 */
class Trilho {
    var origem: Fixo? = null
        private set

    /** O ponto agora (leste, norte), ou null antes do primeiro fixo. */
    var ponto: Pair<Double, Double>? = null
        private set

    private var foraSeguidos = 0
    private var alcancando = false
    private var ultimoTempo = 0L

    /** O [atual] (válido) entra no caminho; [parado] congela o ponto. */
    fun fixo(atual: Fixo, parado: Boolean) {
        val origem = origem ?: atual.also { origem = it }
        val (zl, zn) = emMetros(origem, atual)
        var (pl, pn) = ponto ?: (zl to zn)
        val dt = if (ultimoTempo == 0L) 0.0 else ((atual.tempoMs - ultimoTempo).coerceIn(0, PASSO_MAX_MS)) / 1000.0
        ultimoTempo = atual.tempoMs
        // Andando: o ponto vai pra onde a velocidade e o rumo do GPS dizem
        val v = atual.velocidadeMs
        val rumo = rumoBom(atual)
        if (!parado && v != null && rumo != null && (atual.precisaoVelocidade ?: 0f) <= PRECISAO_VELOCIDADE_MAX) {
            pl += v * sin(Math.toRadians(rumo.toDouble())) * dt
            pn += v * cos(Math.toRadians(rumo.toDouble())) * dt
        }
        val (rl, rn) = (zl - pl) to (zn - pn)
        val longe = hypot(rl, rn)
        if (longe <= portao(atual)) {
            foraSeguidos = 0
            if (!parado) {
                val ganho = if (alcancando) GANHO_ALCANCANDO else GANHO
                pl += ganho * rl
                pn += ganho * rn
                if (alcancando && longe <= portaoNormal(atual)) alcancando = false
            }
        } else if (++foraSeguidos >= FORA_PRA_ALCANCAR) {
            foraSeguidos = 0
            if (parado || alcancando) {
                pl = zl
                pn = zn
                alcancando = false
            } else {
                alcancando = true
            }
        }
        ponto = pl to pn
    }

    private fun portaoNormal(atual: Fixo) = maxOf(PORTAO_MIN_M, PORTAO_FATOR * maxOf(atual.precisaoM ?: PRECISAO_MAX_M, PRECISAO_DO_PORTAO_MIN))

    private fun portao(atual: Fixo) = if (alcancando) maxOf(PORTAO_ALCANCANDO_M, portaoNormal(atual)) else portaoNormal(atual)

    private companion object {
        const val GANHO = 0.15
        const val GANHO_ALCANCANDO = 0.3
        const val PORTAO_MIN_M = 15.0
        const val PORTAO_FATOR = 3.0
        const val PRECISAO_DO_PORTAO_MIN = 4f
        const val PORTAO_ALCANCANDO_M = 30.0
        const val FORA_PRA_ALCANCAR = 5
    }
}

/**
 * O deslocamento na janela ([andou]): quanto o ponto do GPS andou ([pelaPosicao], m), quanto a velocidade e o rumo
 * medidos dizem que andou ([pelaVelocidade], m), e se isso confirma que a pessoa saiu do lugar ([confirma]).
 */
data class Deslocamento(val pelaPosicao: Double, val pelaVelocidade: Double, val confirma: Boolean)

/**
 * O que um fixo diz da caminhada: o [movimento] e por quê ([motivo], curto, pro log e pra tela: "parado", "no
 * lugar"…); quanto ele [soma] no tempo andado (ms: o tempo desde o fixo anterior, mais os segundos que estavam
 * conferindo, de uma vez); quanto está [conferindo] (ms, ainda sem somar); se [valeProBipe] (sinal de vida: passo no
 * ritmo, ou o GPS vendo velocidade de andar; os bipes de parado calam, somando ou não); quanto há de [confianca] (ms,
 * veja [CONFIANCA_MAX_MS]); e, pro log e pra tela, a [velocidade] do GPS (a mediana, m/s), a [cadencia] (passos por
 * minuto), a [passada] (m), a [coerencia] do rumo, o [deslocamento] na janela e o [ponto] do caminho.
 */
data class Avaliacao(
    val movimento: Movimento,
    val motivo: String,
    val soma: Long = 0,
    val conferindo: Long = 0,
    val valeProBipe: Boolean = false,
    val velocidade: Float? = null,
    val cadencia: Float? = null,
    val passada: Float? = null,
    val coerencia: Double? = null,
    val deslocamento: Deslocamento? = null,
    val ponto: Pair<Double, Double>? = null,
    val confianca: Long = 0,
)

/**
 * A caminhada: recebe o acelerômetro ([acelerometro]) e os fixos do GPS ([fixo]) e diz, a cada fixo, se a pessoa
 * está andando a pé e quanto somar. Um segundo é "andando agora" (candidato) se tem passo no ritmo (e a passada
 * fecha: velocidade ÷ passos, senão é bicicleta) ou, sem passo, o GPS vê velocidade de andar (até
 * [SEM_PASSO_VELOCIDADE_MAX]). Soma quando o ponto do GPS andou junto ([andou]), ou, com passo, gastando a confiança
 * de quem acabou de andar na área aberta ([CONFIANCA_MAX_MS]); enquanto não confirma, fica conferindo. Os bipes de
 * parado calam em todo candidato ([Avaliacao.valeProBipe]), somando ou não. Nada que somou volta atrás, e nada zera:
 * um zero do GPS, um salto ou um farol só pausam. [contando] = false (o GPS esquentando antes da caminhada, na
 * conversa): avalia e anda o caminho, mas não soma nem confere.
 */
class DetectorDeCaminhada {
    val passos = DetectorDePassos()
    val trilho = Trilho()
    private val recentes = ArrayDeque<Fixo>() // os fixos válidos da janela ([JANELA_MS])
    private var ultimoTempo: Long? = null
    private var rapidoDesde: Long? = null
    private var semContarAte = 0L
    private var conferindo = 0L
    private var conferindoDesde: Long? = null
    private var ultimoCandidato: Long? = null
    private var confianca = 0L

    fun acelerometro(tempoMs: Long, tamanho: Float) = passos.amostra(tempoMs, tamanho)

    /** O [atual], que chegou em [chegouMs] (o mesmo relógio do fixo). */
    fun fixo(atual: Fixo, chegouMs: Long, contando: Boolean): Avaliacao {
        if (atual.falso) return Avaliacao(Movimento.FALSO, "falso")
        if (!fixoValido(atual)) return Avaliacao(Movimento.IMPRECISO, "impreciso")
        if (chegouMs - atual.tempoMs > FIXO_VELHO_MS) return Avaliacao(Movimento.IMPRECISO, "atrasado")
        val t = atual.tempoMs
        val dt = ultimoTempo?.let { (t - it).coerceIn(0, PASSO_MAX_MS) } ?: 0L
        ultimoTempo = t
        recentes.addLast(atual)
        while (t - recentes.first().tempoMs > JANELA_MS) recentes.removeFirst()

        val ultimos = recentes.toList().takeLast(FIXOS_DA_VELOCIDADE)
        val velocidade = mediana(ultimos.mapNotNull { it.velocidadeMs })
        val incerteza = mediana(ultimos.mapNotNull { it.precisaoVelocidade })
        val semPasso = passos.ultimoPasso?.let { t - it > PARADO_SEM_PASSO_MS } ?: true
        trilho.fixo(atual, parado = semPasso && (velocidade ?: 0f) < PARADO_VELOCIDADE)
        val deslocamento = andou(recentes.toList())
        val cadencia = passos.cadencia
        val passada = if (velocidade != null && cadencia != null) velocidade / (cadencia / 60f) else null
        val comRumo = recentes.filter { (it.velocidadeMs ?: 0f) >= RUMO_VELOCIDADE_MIN && rumoBom(it) != null }.takeLast(RUMOS_USADOS)
        val coerencia = if (comRumo.size >= RUMOS_MIN) coerencia(comRumo) else null

        // De carro: mais rápido que a pé por uns segundos, e nada conta por um tempo
        if (velocidade != null && velocidade > VELOCIDADE_MAX) {
            val desde = rapidoDesde ?: t.also { rapidoDesde = it }
            if (t - desde >= RAPIDO_POR_MS) semContarAte = t + RAPIDO_SEM_CONTAR_MS
        } else {
            rapidoDesde = null
        }
        val comecando = passos.andandoDesde?.let { t - it < COMECANDO_MS } == true
        val comPasso = passos.andando && cadencia != null && cadencia in CADENCIA_MIN..CADENCIA_MAX
        val gpsAnda = velocidade != null && velocidade >= VELOCIDADE_MIN && (incerteza ?: 0f) <= PRECISAO_VELOCIDADE_MAX
        val rapido = t < semContarAte || velocidade != null && velocidade > VELOCIDADE_MAX
        val porque = when {
            rapido -> "rápido"
            // A perna dá ritmo, mas o celular anda mais que um passo: bicicleta, carro devagar
            comPasso && passada != null && passada > PASSADA_MAX_M -> "passada longa"
            comPasso -> null
            // Sem passo reconhecido, o GPS sozinho: até o passo apressado de quem anda; mais rápido é bicicleta
            gpsAnda && velocidade!! <= SEM_PASSO_VELOCIDADE_MAX -> null
            gpsAnda -> "sem passo"
            else -> "parado"
        }
        // Sinal de vida (passo no ritmo, ou o GPS vendo velocidade de andar): os bipes calam, somando ou não
        val vivo = !rapido && (comPasso || gpsAnda)
        fun avaliacao(movimento: Movimento, motivo: String, soma: Long = 0) = Avaliacao(
            movimento, motivo, soma, conferindo, vivo, velocidade, cadencia, passada, coerencia, deslocamento, trilho.ponto, confianca,
        )
        if (porque != null) {
            // Parado de verdade tempo bastante, a conferência e a confiança recomeçam do zero (o que estava conferindo se perde)
            if (ultimoCandidato?.let { t - it >= CONFERINDO_RECOMECA_MS } != false) {
                conferindo = 0
                conferindoDesde = null
                confianca = 0
            }
            return avaliacao(if (porque == "rápido") Movimento.RAPIDO_DEMAIS else Movimento.PARADO, porque)
        }
        ultimoCandidato = t
        if (!contando) return avaliacao(Movimento.CONFERINDO, "esquentando")
        if (deslocamento?.confirma == true) {
            val soma = dt + conferindo
            conferindo = 0
            conferindoDesde = null
            confianca = minOf(confianca + dt, CONFIANCA_MAX_MS)
            return avaliacao(Movimento.ANDANDO, if (comPasso) "andando" else "pelo GPS", soma)
        }
        // Passos no ritmo e o GPS sem confirmar (um trecho coberto): a confiança de quem acabou de andar na área aberta paga
        if (comPasso && confianca > 0) {
            val soma = minOf(dt, confianca)
            confianca -= soma
            return avaliacao(Movimento.ANDANDO, "pelos passos", soma)
        }
        // Andando, mas o GPS ainda não confirmou que saiu do lugar: confere por até um minuto
        val desde = conferindoDesde ?: t.also { conferindoDesde = it }
        if (t - desde > CONFERINDO_MAX_MS) {
            conferindo = 0
            return avaliacao(Movimento.PARADO, "não saiu do lugar")
        }
        conferindo += dt
        val motivo = when {
            !comPasso -> "sem passo" // o GPS vê velocidade, o acelerômetro não vê passo
            !gpsAnda && comecando -> "começando"
            !gpsAnda -> "no lugar" // passos sem velocidade no GPS: dentro de casa, num corredor coberto
            else -> "conferindo"
        }
        return avaliacao(Movimento.CONFERINDO, motivo)
    }
}

/**
 * O deslocamento nos [fixos] da janela (os válidos, do mais velho ao mais novo); null se eles ainda não cobrem
 * [ANDOU_JANELA_MIN_MS] (o GPS acabou de ligar, ou voltou de um buraco). Veja [ANDOU_MIN_M].
 */
internal fun andou(fixos: List<Fixo>): Deslocamento? {
    if (fixos.size < 2 * PONTAS || fixos.last().tempoMs - fixos.first().tempoMs < ANDOU_JANELA_MIN_MS) return null
    val origem = fixos.first()
    fun centro(quais: List<Fixo>) = quais.map { emMetros(origem, it) }.let { m -> m.sumOf { it.first } / m.size to m.sumOf { it.second } / m.size }
    val (al, an) = centro(fixos.take(PONTAS))
    val (bl, bn) = centro(fixos.takeLast(PONTAS))
    val pelaPosicao = hypot(bl - al, bn - an)
    // A velocidade e o rumo somados entre os mesmos dois centros (o fixo do meio de cada ponta)
    var vl = 0.0
    var vn = 0.0
    var caminho = 0.0
    val trecho = fixos.subList(PONTAS / 2, fixos.size - PONTAS / 2)
    for ((a, b) in trecho.zipWithNext()) {
        val v = b.velocidadeMs ?: continue
        val rumo = rumoBom(b) ?: continue
        if (v < RUMO_VELOCIDADE_MIN) continue
        val dt = (b.tempoMs - a.tempoMs) / 1000.0
        vl += v * sin(Math.toRadians(rumo.toDouble())) * dt
        vn += v * cos(Math.toRadians(rumo.toDouble())) * dt
        caminho += v * dt
    }
    val pelaVelocidade = hypot(vl, vn)
    val passos = fixos.zipWithNext { a, b -> distanciaM(a, b) to (b.tempoMs - a.tempoMs) / 1000.0 }
    val saltou = passos.any { (d, s) -> d > VELOCIDADE_MAX * s + SALTO_FOLGA_M }
    val liso = distanciaM(fixos.first(), fixos.last()) >= ANDOU_LISO_MIN * passos.sumOf { it.first }
    val angulo = if (pelaPosicao == 0.0 || pelaVelocidade == 0.0) 180.0 else
        Math.toDegrees(acos((((bl - al) * vl + (bn - an) * vn) / (pelaPosicao * pelaVelocidade)).coerceIn(-1.0, 1.0)))
    val confirma = !saltou && liso && pelaVelocidade >= ANDOU_PELA_VELOCIDADE_MIN_M && pelaVelocidade >= ANDOU_RETO_MIN * caminho &&
        pelaPosicao >= maxOf(ANDOU_MIN_M, ANDOU_FRACAO * pelaVelocidade) && angulo <= ANDOU_ANGULO_MAX
    return Deslocamento(pelaPosicao, pelaVelocidade, confirma)
}

/** O rumo do [fixo], se ele mediu e a incerteza é aceitável ([RUMO_PRECISAO_MAX]); senão null. */
private fun rumoBom(fixo: Fixo): Float? = fixo.rumo?.takeIf { fixo.precisaoRumo == null || fixo.precisaoRumo <= RUMO_PRECISAO_MAX }

/** A coerência dos rumos dos [fixos], pesados pela velocidade: 1 = todos pro mesmo lado, 0 = pra todo lado. */
private fun coerencia(fixos: List<Fixo>): Double {
    var leste = 0.0
    var norte = 0.0
    var total = 0.0
    for (f in fixos) {
        val v = f.velocidadeMs!!.toDouble()
        val rumo = Math.toRadians(rumoBom(f)!!.toDouble())
        leste += v * sin(rumo)
        norte += v * cos(rumo)
        total += v
    }
    return if (total == 0.0) 0.0 else hypot(leste, norte) / total
}

/** A mediana de [valores], ou null sem nenhum. */
private fun mediana(valores: List<Float>): Float? {
    if (valores.isEmpty()) return null
    val ordem = valores.sorted()
    val meio = ordem.size / 2
    return if (ordem.size % 2 == 1) ordem[meio] else (ordem[meio - 1] + ordem[meio]) / 2
}

/** O desvio padrão de [valores] (0 sem nenhum). */
private fun desvio(valores: Collection<Float>): Float {
    if (valores.isEmpty()) return 0f
    val media = valores.average()
    return sqrt(valores.sumOf { (it - media) * (it - media) } / valores.size).toFloat()
}

/**
 * Quando (SystemClock.elapsedRealtime) toca o 1º bipe de parado: [PARADO_MS] depois do último sinal de
 * "andando" ([ultimoPasso]) ou do fim do prazo pra sair ([prazoAte]), o que vier depois. Os seguintes, a
 * cada [ENTRE_BIPES_PARADO_MS]. A vigia do serviço e a contagem na tela ([segundosProBipe]) contam daqui.
 */
fun primeiroBipeParado(ultimoPasso: Long, prazoAte: Long): Long = maxOf(ultimoPasso, prazoAte) + PARADO_MS

/** Contagem na tela, parado depois do prazo pra sair: segundos até o 1º bipe (0 = já bipando). Null ainda no prazo. */
fun segundosProBipe(agora: Long, ultimoPasso: Long, prazoAte: Long): Int? =
    if (agora < prazoAte) null else ceil((primeiroBipeParado(ultimoPasso, prazoAte) - agora) / 1000.0).toInt().coerceAtLeast(0)

/** Há quanto tempo (ms) sem sinal de vida depois do prazo pra sair, pra barra do bipe na tela; null ainda no prazo. */
fun paradoHa(agora: Long, ultimoPasso: Long, prazoAte: Long): Long? =
    if (agora < prazoAte) null else (agora - maxOf(ultimoPasso, prazoAte)).coerceAtLeast(0)

/** O número do bipe de quem está parado há [paradoMs] (0 = nenhum ainda): o 1º aos [PARADO_MS], e um a cada [ENTRE_BIPES_PARADO_MS]. */
fun bipeDoParado(paradoMs: Long): Int = if (paradoMs < PARADO_MS) 0 else (1 + (paradoMs - PARADO_MS) / ENTRE_BIPES_PARADO_MS).toInt()

/**
 * A linha da barra do bipe, parado há [paradoMs]: "Stopped 12 s · beeps start in 11 s", "Beep 3 of 7 · maximum in
 * 12 s", "🔊 Maximum · walk and it stops".
 */
fun textoDoParado(paradoMs: Long): String {
    val bipe = bipeDoParado(paradoMs)
    val segundos = { ms: Long -> ceil(ms / 1000.0).toInt() }
    return when {
        bipe == 0 -> "Stopped ${paradoMs / 1000} s · beeps start in ${segundos(PARADO_MS - paradoMs)} s"
        bipe < BIPES_PARADO_ATE_O_MAXIMO -> "Beep $bipe of $BIPES_PARADO_ATE_O_MAXIMO · maximum in ${segundos(PARADO_MAXIMO_MS - paradoMs)} s"
        else -> "🔊 Maximum · walk and it stops"
    }
}

/** Aviso na tela durante o prazo pra sair: "You have 6:32 to start walking". Null depois dele. */
fun avisoDoPrazo(agora: Long, prazoAte: Long): String? =
    if (agora < prazoAte) "You have ${tempoRestante(prazoAte - agora)} to start walking" else null

/** Tempo já andado, sem arredondar pra cima (é o que já foi): "7:12", "0:28". */
fun tempoAndado(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

/** Velocidade na tela: "1.3 m/s". */
fun textoDaVelocidade(metrosPorSegundo: Float): String = String.format(Locale.ROOT, "%.1f m/s", metrosPorSegundo)
