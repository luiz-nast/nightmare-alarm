package com.implacavel.alarme

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * Cada permissão que o alarme precisa, com a checagem ([liberado]) e o jeito de liberar.
 * [permissao] é a permissão pedida com o diálogo do sistema, quando existe (o pedido vai com [pedido]); as
 * demais, e as já negadas no diálogo, são liberadas numa tela das Configurações ([abrirConfiguracao]), menos o
 * menu de desligar, que não tem tela no celular: [abrirConfiguracao] abre os passos no README (a permissão vem pelo cabo).
 */
enum class Poder(val titulo: String, val explicacao: String, val permissao: String? = null) {
    NOTIFICACOES(
        "Notifications",
        "Without it the alarm cannot show up.",
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null,
    ),
    MICROFONE("Microphone", "To hear you say STOP.", Manifest.permission.RECORD_AUDIO),
    CAMERA("Camera", "To check that your eyes are open.", Manifest.permission.CAMERA),
    LOCALIZACAO("Location", "To count the morning walk, by GPS.", Manifest.permission.ACCESS_FINE_LOCATION),
    ALARME_EXATO("Alarm on the exact minute", "Rings on time even with the phone asleep."),
    TELA_CHEIA("Full screen", "Lets the alarm take over the screen, even when locked."),
    CANAL("Prominent alert", "The \"Alarm ringing\" channel needs high importance."),
    BATERIA("Unrestricted battery", "Keeps the phone from putting the app to sleep."),
    MENU_DE_DESLIGAR(
        "Block the power menu",
        "While the alarm rings, the power off / restart menu closes by itself. The app turns it on only during the alarm, " +
            "so bank apps don't flag it. Needs a one-time setup from a computer: tap Allow for the steps.",
    );

    fun liberado(ctx: Context): Boolean = when (this) {
        NOTIFICACOES -> NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        MICROFONE, CAMERA, LOCALIZACAO -> permissaoFaltando(ctx) == null
        ALARME_EXATO ->
            Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        TELA_CHEIA ->
            Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        CANAL -> {
            val canal = ctx.getSystemService(NotificationManager::class.java).getNotificationChannel(Notificacoes.CANAL_ALARME)
            canal == null || canal.importance >= NotificationManager.IMPORTANCE_HIGH
        }
        BATERIA -> ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        MENU_DE_DESLIGAR -> VigiaDoDesligar.podeLigar(ctx)
    }

    /** A permissão do diálogo do sistema, se ainda falta; null se já foi dada ou se este poder não tem diálogo. */
    fun permissaoFaltando(ctx: Context): String? =
        permissao?.takeUnless { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    /**
     * O que vai no diálogo do sistema pra liberar [permissao]. A localização precisa (FINE) vai junto com a
     * aproximada (COARSE): no Android 12+, pedida sozinha, o sistema ignora o pedido.
     */
    val pedido: Array<String>
        get() = when (this) {
            LOCALIZACAO -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            else -> listOfNotNull(permissao).toTypedArray()
        }

    /**
     * Abre onde este poder é liberado: a tela das Configurações ou, no menu de desligar, os passos no README; se não
     * abrir, a tela do app. Numa tarefa separada: assim a tela do alarme, ao voltar, não fecha as Configurações junto.
     */
    fun abrirConfiguracao(ctx: Context) {
        val novaTarefa = Intent.FLAG_ACTIVITY_NEW_TASK
        runCatching { ctx.startActivity(telaParaLiberar(ctx).addFlags(novaTarefa)) }
            .onFailure { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pacote(ctx)).addFlags(novaTarefa)) }
    }

    @SuppressLint("BatteryLife", "InlinedApi")
    private fun telaParaLiberar(ctx: Context): Intent = when (this) {
        NOTIFICACOES -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        MICROFONE, CAMERA, LOCALIZACAO -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pacote(ctx))
        ALARME_EXATO -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pacote(ctx))
        TELA_CHEIA -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pacote(ctx))
        CANAL -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, Notificacoes.CANAL_ALARME)
        BATERIA -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pacote(ctx))
        // Não há tela no celular que dê a permissão: abre os passos no README (o comando pelo cabo)
        MENU_DE_DESLIGAR -> Intent(Intent.ACTION_VIEW, PASSOS_DO_MENU_DE_DESLIGAR.toUri())
    }

    private fun pacote(ctx: Context) = "package:${ctx.packageName}".toUri()
}

/** Os passos pra liberar a vigia do menu de desligar (a permissão vem pelo cabo), no README do projeto. */
private const val PASSOS_DO_MENU_DE_DESLIGAR = "https://github.com/luiz-nast/nightmare-alarm#block-the-power-menu"
