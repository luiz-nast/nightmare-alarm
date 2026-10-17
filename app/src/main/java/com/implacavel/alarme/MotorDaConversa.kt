// O "cérebro" da conversa da manhã: recebe o que a pessoa disse e devolve a próxima fala da IA, só texto. A voz,
// a vez de cada um, o relógio e o fim são do AlarmeService. O motor é o Gemma no próprio celular ([ModeloLocal]),
// sem internet. Sem o modelo no celular, o despertador "conversa + caminhada" vai direto pra caminhada. O Gemini,
// pela internet, fica guardado em reserva/gemini (fora do app), pronto pra voltar se o modelo do celular não servir.
package com.implacavel.alarme

import android.content.Context

/** Uma fala da conversa, da IA ([daIa]) ou da pessoa. */
data class Turno(val daIa: Boolean, val texto: String)

/**
 * Uma conversa com a IA: [abrir] pede a primeira fala, [responder] manda o que a pessoa disse, [retomar]
 * continua depois de uma interrupção. Cada pedido chama o `aoResponder` uma vez só, na thread principal, com a
 * fala pronta pra voz ([limparFala]; se não for [respostaSegura], a frase
 * fixa no lugar) ou null (sem resposta). Um pedido novo toma o lugar do que ainda não respondeu; depois de
 * [cancelar] ou [desligar], resposta atrasada não chega em ninguém. [descartar]: a última resposta, que já chegou,
 * não vai ser dita (a pessoa voltou a falar). [criar] e os pedidos voltam na hora: carregar
 * o modelo e gerar a fala é em outra thread. O motor pode demorar ou nem responder: quem corta a espera é o
 * AlarmeService ([RESPOSTA_IA_MAX_MS] por fala, e o prazo da decisão depois do bom dia pra abertura).
 */
interface MotorDaConversa {
    fun abrir(aoResponder: (String?) -> Unit)
    fun responder(fala: String, aoResponder: (String?) -> Unit)
    fun retomar(aoResponder: (String?) -> Unit)
    fun cancelar()
    fun descartar()
    fun desligar()

    companion object {
        /** Dá pra conversar nesta manhã: o modelo está no celular e cabe na memória ([ModeloLocal.disponivel]). */
        fun disponivel(ctx: Context): Boolean = ModeloLocal.disponivel(ctx)

        /** Começa a carregar o modelo em segundo plano, pra ele estar pronto quando a conversa começar. */
        fun preparar(ctx: Context) = ModeloLocal.preparar(ctx)

        /**
         * Um motor novo, com a [instrucao] do treinador ([instrucaoDoTreinador]), o pedido que abre a conversa
         * ([pedidoDeAbertura]) e a [falaNaoNegocia]; null sem modelo.
         */
        fun criar(ctx: Context, instrucao: String, abertura: String, falaNaoNegocia: String): MotorDaConversa? =
            if (disponivel(ctx)) ModeloLocal(ctx, instrucao, abertura, falaNaoNegocia) else null

        /** Tira o modelo da memória (fim da conversa ou do alarme). */
        fun liberar() = ModeloLocal.liberar()
    }
}
