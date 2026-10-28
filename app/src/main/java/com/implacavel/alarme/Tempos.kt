// Os tempos e o volume dos despertadores, escolhidos pelo dono na tela principal ("More" › Settings) e guardados em
// [Ajustes.tempos]. Quando um alarme toca, a foto dele ([EmAndamento.tempos]) leva uma cópia: mexer nos ajustes com o
// alarme tocando não encurta nem alonga a missão em andamento (senão seria uma saída). Os botões de teste não seguem
// isto: cada etapa deles tem o tempo curto fixo (META_OLHAR_TESTE_MS, CONVERSA_TESTE_MS, PRAZO_SAIR_TESTE_MS,
// CAMINHADA_TESTE_MS) e a subida de volume de sempre. Sem o Android (fora o JSON), testado em TemposTest.
package com.implacavel.alarme

import org.json.JSONObject

/**
 * O que o dono ajusta, com os valores de começo. A música do alarme sobe de [volumeInicio] a [volumeFim] do volume de
 * alarme do celular ([Subida], em 30 s). No "câmera + frases": [cameraFacilMin] de câmera no modo fácil (olhos abertos
 * acima de 40%), as [frases] da verificação do meio (0 = sem verificação, só a troca pro modo exigente) e
 * [cameraDificilMin] no modo exigente (55%). No "conversa + caminhada": [conversaMin] de conversa pura com a IA,
 * [prepararMin] pra se vestir e sair (com o som de ondas; 0 = os bipes de parado começam já) e [caminhadaMin] andando.
 */
data class Tempos(
    val volumeInicio: Float = SUBIDA_ALARME.inicio,
    val volumeFim: Float = SUBIDA_ALARME.fim,
    val cameraFacilMin: Int = 20,
    val frases: Int = 5,
    val cameraDificilMin: Int = 20,
    val conversaMin: Int = 10,
    val prepararMin: Int = 5, // o dono, 10-28: 4 era pouco
    val caminhadaMin: Int = 10,
) {
    /** A subida da música do alarme com este volume (o remédio tem a dele, [SUBIDA_REMEDIO]). */
    val subida: Subida get() = Subida(volumeInicio, volumeFim, SUBIDA_ALARME.ms)

    /** A câmera inteira, fácil mais exigente, em minutos. */
    val cameraMin: Int get() = cameraFacilMin + cameraDificilMin

    /** A manhã inteira do "conversa + caminhada", em minutos (sem o tempo extra de conversa). */
    val manhaMin: Int get() = conversaMin + prepararMin + caminhadaMin

    fun paraJson(): JSONObject = JSONObject()
        .put("volumeInicio", volumeInicio.toDouble())
        .put("volumeFim", volumeFim.toDouble())
        .put("cameraFacilMin", cameraFacilMin)
        .put("frases", frases)
        .put("cameraDificilMin", cameraDificilMin)
        .put("conversaMin", conversaMin)
        .put("prepararMin", prepararMin)
        .put("caminhadaMin", caminhadaMin)

    companion object {
        /** De um JSON gravado; o que faltar vem do padrão, e tudo entra nas faixas ([ajustado]). */
        fun deJson(o: JSONObject): Tempos {
            val p = TEMPOS_PADRAO
            return Tempos(
                volumeInicio = o.optDouble("volumeInicio", p.volumeInicio.toDouble()).toFloat(),
                volumeFim = o.optDouble("volumeFim", p.volumeFim.toDouble()).toFloat(),
                cameraFacilMin = o.optInt("cameraFacilMin", p.cameraFacilMin),
                frases = o.optInt("frases", p.frases),
                cameraDificilMin = o.optInt("cameraDificilMin", p.cameraDificilMin),
                conversaMin = o.optInt("conversaMin", p.conversaMin),
                prepararMin = o.optInt("prepararMin", p.prepararMin),
                caminhadaMin = o.optInt("caminhadaMin", p.caminhadaMin),
            ).ajustado()
        }
    }

    /** Tudo dentro das faixas dos ajustes (uma gravação velha ou torta não quebra nada); o fim do volume nunca abaixo do início. */
    fun ajustado(): Tempos {
        val inicio = volumeInicio.coerceIn(VOLUME_DA_MUSICA.start, VOLUME_DA_MUSICA.endInclusive)
        return Tempos(
            volumeInicio = inicio,
            volumeFim = volumeFim.coerceIn(inicio, VOLUME_DA_MUSICA.endInclusive),
            cameraFacilMin = cameraFacilMin.coerceIn(MINUTOS_DA_CAMERA),
            frases = frases.coerceIn(FRASES_DA_VERIFICACAO),
            cameraDificilMin = cameraDificilMin.coerceIn(MINUTOS_DA_CAMERA),
            conversaMin = conversaMin.coerceIn(MINUTOS_DA_CONVERSA),
            prepararMin = prepararMin.coerceIn(MINUTOS_PRA_SAIR),
            caminhadaMin = caminhadaMin.coerceIn(MINUTOS_DA_CAMINHADA),
        )
    }
}

/** Os tempos de quem nunca mexeu nos ajustes. */
val TEMPOS_PADRAO = Tempos()

/** A faixa de cada ajuste (as pontas dos controles da tela). */
val VOLUME_DA_MUSICA = 0.05f..1f
val MINUTOS_DA_CAMERA = 5..30
val FRASES_DA_VERIFICACAO = 0..10
val MINUTOS_DA_CONVERSA = 5..30
val MINUTOS_PRA_SAIR = 0..10
val MINUTOS_DA_CAMINHADA = 5..30

/** O controle de volume anda de 5 em 5%. */
const val PASSO_DO_VOLUME = 0.05f

fun minutos(min: Int): Long = min * 60_000L
