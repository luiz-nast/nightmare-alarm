// A vigia do menu de desligar: um serviço de acessibilidade que, enquanto um alarme toca, fecha o menu de desligar e
// reiniciar do celular assim que ele aparece (como o Alarmy faz). O Android não deixa um app proibir o desligamento;
// deixa um serviço de acessibilidade ver as janelas e apertar "voltar" por ele. Segurar o botão por uns 7 s reinicia o
// celular à força, por hardware: isso ninguém segura, mas o alarme volta depois do reinício (BootReceiver).
//
// Ligada SÓ enquanto um alarme toca ([ligar] no começo, [desligar] no fim), pelo próprio app: um app de fora da Play
// Store com acessibilidade ligada o dia inteiro é o retrato do "golpe da mão fantasma", e os bancos (o Nubank, pelo
// "App access risk" do Android) avisam ou travam. O Android não deixa app nenhum ligar a própria acessibilidade: pra
// ligar e desligar sozinho, o app precisa da permissão WRITE_SECURE_SETTINGS, dada uma vez pelo cabo (o dono roda o
// comando; o botão "Allow" do cartão abre os passos no README):
//   adb shell pm grant com.implacavel.alarme android.permission.WRITE_SECURE_SETTINGS
// Sem ela, não há vigia. Se o serviço se vê ligado sem alarme tocando (ligado à mão, ou sobrou de um alarme que caiu),
// ele se desliga sozinho ([SOBRA_MS]); e no fim do alarme, se a lista não mudou, ele mesmo sai ([desligar]).
// Ligar e desligar a peça (o componente) não serve: o Android apaga o serviço da lista (testado).
// Ligada à mão e o dia inteiro, como no Alarmy, foi tentado de novo na mesma noite (o dono não queria o comando), e o
// Nubank acusou "comportamento suspeito" em poucas horas: sem a permissão, o app fica sem a vigia.
package com.implacavel.alarme

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class VigiaDoDesligar : AccessibilityService() {

    companion object {
        /** O serviço está na lista dos ligados nas Configurações de acessibilidade do celular. */
        fun ligado(ctx: Context): Boolean {
            val ligados = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return ligados.split(':').any { it == nome(ctx) || it == nomeCurto(ctx) }
        }

        /** O app pode ligar e desligar a vigia sozinho: tem a WRITE_SECURE_SETTINGS (dada pelo cabo, uma vez). */
        fun podeLigar(ctx: Context): Boolean =
            ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        /** Um alarme começou a tocar: a vigia entra na lista (os outros serviços ficam). Sem a permissão, nada. */
        fun ligar(ctx: Context) = trocar(ctx, ligada = true)

        /**
         * O alarme acabou: a vigia sai da lista, e o celular fica sem nenhum serviço nosso de acessibilidade. Com a
         * permissão, pelas Configurações; sem ela (ligada à mão no meio do alarme), o próprio serviço se desliga.
         */
        fun desligar(ctx: Context) {
            if (podeLigar(ctx)) return trocar(ctx, ligada = false)
            atual?.let {
                Log.i(TAG, "Vigia do desligar: o alarme acabou, se desliga")
                it.disableSelf()
            }
        }

        private fun trocar(ctx: Context, ligada: Boolean) {
            if (!podeLigar(ctx)) return
            val resolver = ctx.contentResolver
            val atual = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val nova = listaDeAcessibilidade(atual, nome(ctx), nomeCurto(ctx), ligada)
            if (nova == atual.orEmpty()) return
            runCatching {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, nova)
                if (ligada) Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }.onSuccess { Log.i(TAG, "Vigia do desligar: ${if (ligada) "ligada" else "desligada"} pelo app") }
                .onFailure { Log.w(TAG, "Vigia do desligar: o Android não deixou ${if (ligada) "ligar" else "desligar"}", it) }
        }

        private fun nome(ctx: Context) = ComponentName(ctx, VigiaDoDesligar::class.java).flattenToString()
        private fun nomeCurto(ctx: Context) = ComponentName(ctx, VigiaDoDesligar::class.java).flattenToShortString()

        /** Ligada sem alarme tocando por mais que isto, ela se desliga (dá tempo do alarme voltar depois de um reinício). */
        private const val SOBRA_MS = 15_000L

        /** Duas aparições do menu em menos disto: o "voltar" não fechou; a segunda vez vai o "início". */
        private const val INSISTIU_MS = 3_000L

        /** Quantos nós da janela olhar, no máximo, procurando os textos do menu. */
        private const val NOS_MAX = 150

        private val _noAr = MutableStateFlow(false)

        /** Ligada agora: a tela do alarme mostra o cadeado, e o [TesteDaVigia] sabe se o Android ligou de fato. */
        val noAr: StateFlow<Boolean> = _noAr

        /** O serviço no ar (pra ele se desligar no fim do alarme, sem a permissão); null desligado. */
        @Volatile
        private var atual: VigiaDoDesligar? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var conectadaEm = 0L
    private var ultimaVez = 0L
    private val sobrou = Runnable { soltarSeSobrou() }

    override fun onServiceConnected() {
        atual = this
        _noAr.value = true
        conectadaEm = SystemClock.elapsedRealtime()
        Log.i(TAG, "Vigia do desligar: no ar (${if (AlarmeService.tocando.value != null) "com" else "sem"} alarme tocando)")
        handler.postDelayed(sobrou, SOBRA_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (AlarmeService.tocando.value == null) return soltarSeSobrou() // sem alarme tocando, o serviço não olha nada
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val textos = event.text.map { it.toString() } + textosDaJanela(event)
        if (!ehMenuDeDesligar(event.packageName?.toString(), event.className?.toString(), textos)) return
        val agora = SystemClock.elapsedRealtime()
        val insistiu = agora - ultimaVez < INSISTIU_MS
        ultimaVez = agora
        Log.i(TAG, "Vigia do desligar: menu de desligar com o alarme tocando, ${if (insistiu) "início" else "voltar"}")
        performGlobalAction(if (insistiu) GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK)
    }

    /** Sem alarme tocando há mais de [SOBRA_MS] desde que ligou: a vigia se desliga (sai da lista do Android). */
    private fun soltarSeSobrou() {
        if (AlarmeService.tocando.value != null) return
        if (SystemClock.elapsedRealtime() - conectadaEm < SOBRA_MS) return
        Log.i(TAG, "Vigia do desligar: ligada sem alarme tocando, se desliga")
        disableSelf()
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

    override fun onUnbind(intent: Intent?): Boolean {
        if (atual === this) atual = null
        _noAr.value = false
        handler.removeCallbacks(sobrou)
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}
}
