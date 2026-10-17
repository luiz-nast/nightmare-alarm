package com.implacavel.alarme

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Canal de notificação do alarme. A notificação em si é montada no [AlarmeService]. */
object Notificacoes {
    const val CANAL_ALARME = "alarme"
    const val ID_TOCANDO = 1

    /** Segunda notificação em tela cheia, postada pra reabrir a tela do alarme quando ela é fechada no meio. */
    const val ID_CHAMADA = 2

    fun criarCanal(ctx: Context) {
        val canal = NotificationChannel(CANAL_ALARME, "Alarm ringing", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Shows in full screen when an alarm goes off"
            // Canal mudo de propósito: som e vibração vêm da Sirene, no volume de alarme e sem parar sozinhos
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }
}
