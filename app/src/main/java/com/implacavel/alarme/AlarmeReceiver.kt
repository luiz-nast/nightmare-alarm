package com.implacavel.alarme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log

/**
 * Recebe os disparos do [Agendador]:
 * - DISPARAR: atualiza o alarme salvo, grava a foto do alarme em andamento, arma a retomada e põe
 *   o [AlarmeService] pra tocar;
 * - RETOMAR: se há alarme em andamento (o app morreu no meio dele), põe ele pra tocar de novo.
 */
class AlarmeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            // Sem id, nenhum alarme (o -1 é o teste da caminhada)
            Agendador.ACAO_DISPARAR -> disparar(ctx, intent.getIntExtra(Agendador.EXTRA_ID, Int.MIN_VALUE))
            Agendador.ACAO_RETOMAR -> {
                val em = Ajustes.emAndamento ?: return // missão já cumprida: retomada velha
                Log.i(TAG, "Retomada: alarme ${em.alarme.id} estava em andamento")
                AlarmeService.tocar(ctx)
            }
        }
    }

    private fun disparar(ctx: Context, id: Int) {
        Log.i(TAG, "Disparo recebido: alarme $id")
        val alarme = when (id) {
            Alarme.ID_TESTE -> Alarme.teste()
            Alarme.ID_TESTE_CAMINHADA -> Alarme.teste(caminhada = true)
            else -> atualizarSalvo(ctx, id) ?: return
        }
        // Foto e rede de segurança antes do serviço: se o app cair daqui pra frente, o alarme volta.
        // Se outro alarme já está em andamento, os dois viram um (EmAndamento.juntar).
        // No meio da comemoração de outro alarme o volume está nos 40% dela: o de antes é o guardado
        val volume = Ajustes.volumeParaDevolver ?: ctx.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM)
        val agora = System.currentTimeMillis()
        val musica = Sons.uriDoAlarme(alarme)
        Ajustes.emAndamento = Ajustes.emAndamento?.juntar(alarme, agora, musica) ?: EmAndamento(alarme, agora, musica, volume)
        Agendador.agendarRetomada(ctx)
        AlarmeService.tocar(ctx)
    }

    /** Alarme de uma vez só se desliga; repetido já agenda a próxima vez. Null se ele não deve tocar. */
    private fun atualizarSalvo(ctx: Context, id: Int): Alarme? {
        val alarme = Alarmes.buscar(id) ?: return null // excluído depois de agendado
        when {
            !alarme.ativo -> {
                Log.i(TAG, "Disparo ignorado: alarme $id foi desligado depois de agendado")
                return null
            }
            alarme.dias.isEmpty() -> Alarmes.salvar(alarme.copy(ativo = false))
            else -> Agendador.agendar(ctx, alarme)
        }
        return alarme
    }
}
