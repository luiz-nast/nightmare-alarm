// Página do YouTube da etapa da câmera: deslizando pra direita, a pessoa vê vídeos enquanto a câmera,
// numa bolinha no canto (Missao.kt), continua conferindo os olhos. O navegador (WebView) fica guardado
// pela tela do alarme inteira: a vigia voltar pra etapa de falar não perde o vídeo, só pausa.
package com.implacavel.alarme

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private const val ENDERECO_YOUTUBE = "https://m.youtube.com"

/** Pausa todo vídeo e áudio da página (o WebView.onPause sozinho não para o som). */
private const val PAUSAR_MIDIAS = "document.querySelectorAll('video,audio').forEach(function(m){m.pause()})"

/** O navegador do YouTube, criado só quando a pessoa abre a página pela primeira vez. */
class YoutubeDaMissao(private val ctx: Context) {
    var web by mutableStateOf<WebView?>(null)
        private set
    var caiu by mutableStateOf(false)
        private set

    /** Sem WebView no celular (desligado ou atualizando), mostra o aviso em vez de derrubar o app. */
    fun abrir() {
        if (web == null) web = runCatching { criar() }.onFailure { Log.w(TAG, "YouTube: não abriu", it) }.getOrNull()
        caiu = web == null
    }

    /** Pausa os vídeos: na etapa de falar o microfone precisa ouvir o STOP, e fora da tela a música volta. */
    fun pausar() {
        web?.run {
            evaluateJavascript(PAUSAR_MIDIAS, null)
            onPause()
        }
    }

    fun retomar() = web?.onResume()

    /** Volta uma página no histórico. False se não há pra onde voltar. */
    fun voltar(): Boolean = web?.takeIf { it.canGoBack() }?.run { goBack(); true } ?: false

    fun destruir() {
        web?.run {
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        web = null
    }

    @SuppressLint("SetJavaScriptEnabled") // o YouTube não funciona sem JavaScript
    private fun criar() = WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            // Link pra outro app (app do YouTube, Play Store...) seria uma saída da tela do alarme, e outro
            // site, um som que a pausa da etapa de falar não alcança: ficam sem efeito
            override fun shouldOverrideUrlLoading(view: WebView, pedido: WebResourceRequest) =
                !linkFicaNaPagina(pedido.url.toString(), pedido.isForMainFrame)

            // O processo da página caiu: sem isto, o app inteiro cairia junto (e contaria como queda do alarme)
            override fun onRenderProcessGone(view: WebView, detalhe: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "YouTube: a página caiu")
                destruir()
                caiu = true
                return true
            }
        }
        loadUrl(ENDERECO_YOUTUBE)
        Log.i(TAG, "YouTube: aberto")
    }
}

/** O navegador da tela do alarme: pausa quando a tela sai da frente e é destruído quando ela fecha. */
@Composable
fun rememberYoutubeDaMissao(): YoutubeDaMissao {
    val ctx = LocalContext.current
    val dono = LocalLifecycleOwner.current
    val youtube = remember { YoutubeDaMissao(ctx) }
    DisposableEffect(dono) {
        val observador = LifecycleEventObserver { _, evento ->
            when (evento) {
                Lifecycle.Event.ON_START -> youtube.retomar()
                Lifecycle.Event.ON_STOP -> youtube.pausar()
                else -> {}
            }
        }
        dono.lifecycle.addObserver(observador)
        onDispose {
            dono.lifecycle.removeObserver(observador)
            youtube.destruir()
        }
    }
    return youtube
}

/** A página em si: o YouTube na tela inteira (fora das barras do sistema), ou um aviso enquanto ele não abre. */
@Composable
fun PaginaYoutube(youtube: YoutubeDaMissao) {
    // Branco como o YouTube claro, também atrás da barra de cima (ícones escuros na etapa da câmera)
    Box(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding(), contentAlignment = Alignment.Center) {
        val web = youtube.web
        if (web == null) {
            val aviso = if (youtube.caiu) "YouTube froze. Swipe to the camera and back to open it again." else "Opening YouTube…"
            Text(aviso, color = VinhoAlarme, fontSize = 18.sp, modifier = Modifier.padding(32.dp))
        } else {
            // Um navegador novo (depois de cair) precisa de um AndroidView novo
            key(web) {
                AndroidView(
                    // O mesmo navegador volta pra tela depois da etapa de falar: sai antes de onde estava
                    factory = { web.also { (it.parent as? ViewGroup)?.removeView(it) } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
