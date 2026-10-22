// A vigia do menu de desligar: um serviço de acessibilidade que, enquanto um alarme toca, fecha o menu de desligar e
// reiniciar do celular assim que ele aparece (como o Alarmy faz). O Android não deixa um app proibir o desligamento;
// deixa um serviço de acessibilidade ver as janelas e apertar "voltar" por ele. Segurar o botão por uns 7 s reinicia o
// celular à força, por hardware: isso ninguém segura, mas o alarme volta depois do reinício (BootReceiver).
//
// Como no Alarmy: o dono liga uma vez nas Configurações › Acessibilidade (o Android exige que seja à mão), pelo cartão
// de permissões, e fica ligado. Fora de um alarme tocando, o serviço não olha nada: cada evento volta na primeira linha.
// O preço, escolhido pelo dono em 2026-10-22: um app de fora da Play Store com acessibilidade ligada faz o Nubank avisar
// (o "App access risk" do Android); com o Alarmy, que vem da Play Store, o aviso parou. Ligar só durante o alarme pede a
// permissão WRITE_SECURE_SETTINGS por um comando no cabo, e ele não quis (veja o README).
package com.implacavel.alarme

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class VigiaDoDesligar : AccessibilityService() {

    companion object {
        /** O serviço está ligado nas Configurações de acessibilidade do celular. */
        fun ligado(ctx: Context): Boolean {
            val ligados = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val nome = ComponentName(ctx, VigiaDoDesligar::class.java)
            return ligados.split(':').any { it == nome.flattenToString() || it == nome.flattenToShortString() }
        }

        /** Duas aparições do menu em menos disto: o "voltar" não fechou; a segunda vez vai o "início". */
        private const val INSISTIU_MS = 3_000L

        /** Quantos nós da janela olhar, no máximo, procurando os textos do menu. */
        private const val NOS_MAX = 150
    }

    private var ultimaVez = 0L

    override fun onServiceConnected() {
        Log.i(TAG, "Vigia do desligar: ligada")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (AlarmeService.tocando.value == null) return // sem alarme tocando, o serviço não olha nada
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val textos = event.text.map { it.toString() } + textosDaJanela(event)
        if (!ehMenuDeDesligar(event.packageName?.toString(), event.className?.toString(), textos)) return
        val agora = SystemClock.elapsedRealtime()
        val insistiu = agora - ultimaVez < INSISTIU_MS
        ultimaVez = agora
        Log.i(TAG, "Vigia do desligar: menu de desligar com o alarme tocando, ${if (insistiu) "início" else "voltar"}")
        performGlobalAction(if (insistiu) GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK)
    }

    /**
     * Os textos dos primeiros nós da janela de que o evento fala (o menu é pequeno: "Power off", "Restart" ficam logo
     * no começo). Não a janela ativa: com a barra de notificações aberta por cima de um app, seria a do app.
     */
    private fun textosDaJanela(event: AccessibilityEvent): List<String> {
        val raiz = runCatching { event.source }.getOrNull() ?: return emptyList()
        val textos = mutableListOf<String>()
        val fila = ArrayDeque<AccessibilityNodeInfo>().apply { add(raiz) }
        var vistos = 0
        while (fila.isNotEmpty() && vistos < NOS_MAX) {
            val no = fila.removeFirst()
            vistos++
            no.text?.let { textos += it.toString() }
            no.contentDescription?.let { textos += it.toString() }
            for (i in 0 until no.childCount) no.getChild(i)?.let(fila::add)
        }
        return textos
    }

    override fun onInterrupt() {}
}
