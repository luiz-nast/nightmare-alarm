// Teste do GPS da caminhada pelo cabo (permissão DUMP, como o TesteDoSom), sem alarme e sem som nenhum:
//   adb shell am start -n com.implacavel.alarme/.TesteDoGps            (15 min; --ei minutos N pra outro tempo)
//   adb logcat -s Implacavel
// Também grava o log num arquivo (dá pra soltar o cabo e andar na rua), lido depois pelo cabo:
//   adb pull /sdcard/Android/data/com.implacavel.alarme/files/teste_gps.log
// A tela da caminhada de verdade ([EtapaCaminhada], com os mesmos sentidos e a mesma regra), mas sem prazo, sem bipe
// e sem contar pro alarme; cada fixo do GPS no log ([detalheDoFixo]: metros desde o primeiro, sem as coordenadas). Junto, a cada
// segundo: os satélites usados e o sinal deles (todos e só a banda L1), o quanto o celular balança e o tamanho da
// aceleração em cada amostra (o acelerômetro, ~50 por segundo: andando, o passo sacode no ritmo). Cada aperto num
// botão de volume escreve "MARCA n" (a troca de etapa do teste, pra comparar com o que a regra viu). O voltar fecha.
// (O "fused" do Android já foi comparado: perto de casa ele repete o GPS, igualzinho.)
package com.implacavel.alarme

import android.annotation.SuppressLint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/** O teste fecha sozinho depois disso, se ninguém fechar antes. */
private const val TESTE_GPS_MIN = 15

class TesteDoGps : ComponentActivity() {
    private val principal = Handler(Looper.getMainLooper())
    private val gps by lazy { getSystemService(LocationManager::class.java) }
    private val sensores by lazy { getSystemService(SensorManager::class.java) }

    // Os satélites do último aviso do GPS: usados no fixo, vistos, e o sinal médio dos 4 mais fortes usados (dB-Hz)
    private var satelites = "sat -"

    // O acelerômetro no último segundo: soma e soma dos quadrados do tamanho da aceleração
    private var amostras = 0
    private var soma = 0.0
    private var somaDosQuadrados = 0.0
    private val tamanhos = StringBuilder() // o tamanho de cada amostra no último segundo, em décimos de m/s²

    private var marcas = 0
    private var gravador: Process? = null

    private val satelitesOuvinte = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val usados = (0 until status.satelliteCount).filter(status::usedInFix)
            val l1 = usados.filter { status.hasCarrierFrequencyHz(it) && status.getCarrierFrequencyHz(it) in 1_559e6f..1_610e6f }
            fun forca(quais: List<Int>) = quais.map(status::getCn0DbHz).sortedDescending().take(4).let { if (it.isEmpty()) 0f else it.average().toFloat() }
            satelites = String.format(Locale.ROOT, "sat %d/%d sinal %.0f L1 %.0f (%d)", usados.size, status.satelliteCount, forca(usados), forca(l1), l1.size)
        }
    }

    private val acelerometro = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val tamanho = sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
            amostras++
            soma += tamanho
            somaDosQuadrados += tamanho * tamanho
            tamanhos.append(' ').append(Math.round(tamanho * 10))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // A cada segundo: os satélites e o quanto o celular balançou (desvio do tamanho da aceleração, m/s²)
    private val porSegundo = object : Runnable {
        override fun run() {
            val media = if (amostras > 0) soma / amostras else 0.0
            val desvio = if (amostras > 1) sqrt((somaDosQuadrados / amostras - media * media).coerceAtLeast(0.0)) else 0.0
            Log.i(TAG, String.format(Locale.ROOT, "Teste GPS: %s, balanço %.2f (%d amostras)", satelites, desvio, amostras))
            Log.i(TAG, "Acel:$tamanhos")
            tamanhos.setLength(0)
            amostras = 0
            soma = 0.0
            somaDosQuadrados = 0.0
            principal.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Ajustes.emAndamento != null) {
            Log.w(TAG, "Teste GPS: tem alarme tocando, o teste não roda")
            return finish()
        }
        if (!Poder.LOCALIZACAO.liberado(this)) {
            Log.w(TAG, "Teste GPS: sem permissão de localização")
            return finish()
        }
        val minutos = intent.getIntExtra("minutos", TESTE_GPS_MIN)
        gravarNoArquivo()
        Log.i(TAG, "Teste GPS: começou, $minutos min")
        setContent {
            TemaImplacavel {
                val sentidos = rememberSentidosDaCaminhada(ligado = true)
                Column(
                    Modifier.fillMaxSize().background(Color(0xFFFDECEA)).verticalScroll(rememberScrollState()).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    EtapaCaminhada(
                        sentidos = sentidos,
                        andadoInicialMs = 0,
                        metaMs = minutos * 60_000L,
                        prazoAte = 0,
                        ultimoPasso = 0,
                        onAndando = {},
                        onDefeito = { Log.w(TAG, "Teste GPS: o celular não tem GPS") },
                        onConcluiu = {},
                        detalhar = true,
                    )
                }
            }
        }
        principal.postDelayed({ finish() }, minutos * 60_000L)
    }

    @SuppressLint("MissingPermission") // conferida no onCreate
    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        gps.registerGnssStatusCallback(satelitesOuvinte, principal)
        sensores.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensores.registerListener(acelerometro, it, SensorManager.SENSOR_DELAY_GAME) }
        principal.post(porSegundo)
    }

    override fun onStop() {
        super.onStop()
        gps.unregisterGnssStatusCallback(satelitesOuvinte)
        sensores.unregisterListener(acelerometro)
        principal.removeCallbacks(porSegundo)
    }

    // Os botões de volume marcam a troca de etapa do teste (e não mexem no volume)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyDown(keyCode, event)
        if (event?.repeatCount == 0) Log.i(TAG, "Teste GPS: MARCA ${++marcas}")
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        principal.removeCallbacksAndMessages(null)
        Log.i(TAG, "Teste GPS: fechou")
        // Um instante pro gravador pegar a última linha
        gravador?.let { g -> principal.postDelayed({ g.destroy() }, 1_000) }
    }

    /** O log do app daqui pra frente também num arquivo (teste_gps.log, mais um antigo de 4 MB), como o diagnóstico do alarme. */
    private fun gravarNoArquivo() {
        val pasta = getExternalFilesDir(null) ?: return
        val agora = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        gravador = runCatching {
            ProcessBuilder("logcat", "-v", "time", "-s", "$TAG:V", "-T", agora, "-f", File(pasta, "teste_gps.log").path, "-r", "4096", "-n", "1").start()
        }.onFailure { Log.w(TAG, "Teste GPS: não deu pra gravar o arquivo", it) }.getOrNull()
    }
}
