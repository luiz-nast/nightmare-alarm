package com.implacavel.alarme

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.util.Log

/** Tag dos logs do app. Pra acompanhar um alarme: `adb logcat -s Implacavel`. */
const val TAG = "Implacavel"

/**
 * SharedPreferences no armazenamento protegido pelo dispositivo, que pode ser lido logo depois de o
 * celular reiniciar, antes do primeiro desbloqueio. É isso que deixa o alarme tocar mesmo se o
 * celular reiniciar de madrugada.
 */
fun Context.prefsProtegidas(nome: String): SharedPreferences =
    createDeviceProtectedStorageContext().getSharedPreferences(nome, Context.MODE_PRIVATE)

/**
 * Início do processo. Roda antes de qualquer tela, receiver ou serviço, inclusive logo após o
 * celular reiniciar, antes do primeiro desbloqueio.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Alarmes.carregar(this)
        Ajustes.carregar(this)
        Sons.carregar(this)
        Notificacoes.criarCanal(this)
        // O processo morreu com um alarme tocando (celular desligado, app encerrado ou caído): ele volta em instantes, e
        // a missão continua de onde estava ([Ajustes.progresso]). Um teste não volta: o dono desligou o celular pra
        // fugir dos bipes de um teste (10-17), e ele voltou depois do reinício tocando o toque do sistema. Pra encerrar
        // um alarme de verdade no meio, só pelo cabo ([PararPeloCabo])
        Ajustes.emAndamento?.let {
            if (it.alarme.deTeste) {
                Log.i(TAG, "Teste ${it.alarme.id} foi interrompido no meio: fica por isso mesmo")
                Ajustes.emAndamento = null
            } else {
                Log.i(TAG, "Alarme ${it.alarme.id} foi interrompido no meio: voltando a tocar")
                Agendador.agendarRetomada(this, 2_000)
            }
        }
        // O processo morreu na comemoração de um alarme cumprido: devolve o volume de antes dele
        if (Ajustes.emAndamento == null) Ajustes.volumeParaDevolver?.let {
            runCatching { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, it, 0) }
            Ajustes.volumeParaDevolver = null
        }
    }
}
