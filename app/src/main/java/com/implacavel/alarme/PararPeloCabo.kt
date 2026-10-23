// Saída de emergência do alarme em andamento, só pelo cabo: o receiver pede a permissão DUMP, que o adb tem e app
// comum nenhum (como o TesteDaIa). Pra quem desenvolve encerrar um teste que deu errado sem desinstalar o app; pra
// quem está na cama, não existe:
//   adb shell am broadcast -n com.implacavel.alarme/.PararPeloCabo
package com.implacavel.alarme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log

class PararPeloCabo : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val em = Ajustes.emAndamento
        Log.w(TAG, "Pelo cabo: encerrando o alarme em andamento (${em?.alarme?.id ?: "nenhum"})")
        if (AlarmeService.tocando.value != null) return AlarmeService.encerrarSemMissao(ctx)
        // O serviço não está tocando (o app acabou de voltar, a retomada ainda não disparou): apaga tudo daqui
        Ajustes.emAndamento = null
        Ajustes.progresso = null
        Agendador.cancelarRetomada(ctx)
        em?.let { runCatching { ctx.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, it.volume, 0) } }
    }
}
