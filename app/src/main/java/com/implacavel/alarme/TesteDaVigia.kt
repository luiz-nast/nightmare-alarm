// Teste da vigia do menu de desligar, sem som e sem tela: o app liga a vigia, espera o Android pôr o serviço no ar
// (até [ESPERA_MS]), escreve no log se deu certo e desliga de novo. Serve pra ver, depois de dar a permissão pelo cabo,
// se este celular deixa o app ligar a acessibilidade sozinho. Só pelo cabo (permissão DUMP, como o TesteDaIa):
//   adb shell am broadcast -n com.implacavel.alarme/.TesteDaVigia
//   adb logcat -s Implacavel
package com.implacavel.alarme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** Quanto esperar o Android ligar o serviço depois de entrar na lista. */
private const val ESPERA_MS = 5_000L

class TesteDaVigia : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (AlarmeService.tocando.value != null) {
            Log.w(TAG, "Teste da vigia: com um alarme tocando, não roda")
            return
        }
        if (!VigiaDoDesligar.podeLigar(ctx)) {
            Log.w(TAG, "Teste da vigia: falta a WRITE_SECURE_SETTINGS (pm grant pelo cabo)")
            return
        }
        val app = ctx.applicationContext
        val fim = goAsync()
        val inicio = SystemClock.elapsedRealtime()
        VigiaDoDesligar.ligar(app)
        val handler = Handler(Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                val passou = SystemClock.elapsedRealtime() - inicio
                if (!VigiaDoDesligar.noAr.value && passou < ESPERA_MS) {
                    handler.postDelayed(this, 250)
                    return
                }
                Log.i(
                    TAG,
                    if (VigiaDoDesligar.noAr.value) "Teste da vigia: OK, no ar em $passou ms; desligando"
                    else "Teste da vigia: FALHOU, o Android não pôs o serviço no ar em ${ESPERA_MS / 1000} s",
                )
                // Um alarme que começou a tocar no meio da espera já ligou a vigia pra ele: fica
                if (AlarmeService.tocando.value != null) Log.i(TAG, "Teste da vigia: um alarme começou no meio, a vigia fica")
                else VigiaDoDesligar.desligar(app)
                fim.finish()
            }
        })
    }
}
