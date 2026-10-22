// A câmera frontal da missão com a detecção de rosto (ML Kit, no aparelho, sem internet), do serviço
// ([CameraDaMissao]): aberta depois do bom dia, ela lê os olhos com a tela aberta ou fechada (serviço com o tipo
// "camera", ganho no STOP com a tela na frente). A tela só mostra a imagem ([CameraOlhos]): liga o visor dela no
// mesmo fluxo da câmera do serviço quando aparece, e solta quando some. Pedido do dono (10-22): fazer um post no
// Instagram no meio da atividade sem a missão recomeçar; a câmera frontal continua vendo o rosto por baixo.
package com.implacavel.alarme

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

/**
 * O detector de rosto (modo preciso) olha um quadro a cada tanto, não todos: 2 por segundo bastam pro
 * anel parar em meio segundo e pra contagem. Em todo quadro, o celular esquentava em 5 min; com 4 por
 * segundo, ainda esquentava nos 40 min da atividade.
 */
private const val ANALISE_A_CADA_MS = 500L

/** Quadros por segundo da câmera: a bolinha não precisa de 30, e menos quadros esquentam menos. */
private val QUADROS_POR_SEGUNDO = Range(15, 15)

/**
 * Tamanho da imagem do círculo: perto do tamanho dele na tela, em vez do Full HD que a câmera manda
 * sozinha (menos trabalho pro celular, menos calor). A análise já usa 640x480 por padrão.
 */
private val IMAGEM_DO_CIRCULO = ResolutionSelector.Builder()
    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
    .build()

/** Detector de rosto falhando em todo quadro analisado por uns 3 s é defeito. */
private const val FALHAS_SEGUIDAS_MAX = (3_000 / ANALISE_A_CADA_MS).toInt()

/** A última leitura da câmera do serviço: o que viu, a certeza de olho aberto (null sem rosto) e [quando] (SystemClock.elapsedRealtime). */
data class LeituraDaCamera(val leitura: Leitura, val olhoAberto: Float?, val quando: Long)

/**
 * A câmera frontal com a detecção de rosto, fora da tela: vive no serviço, com um ciclo de vida só dela (o CameraX
 * pede um; [ligar] e [desligar]). A cada quadro analisado ([ANALISE_A_CADA_MS]) informa [onLeitura] com uma [Leitura] e
 * o [olhoAberto] (null sem rosto), lendo [exigente] na hora (depois da verificação do meio, os olhos têm que estar
 * mais abertos). Com defeito (não abre, erro grave da câmera ou o detector falhando em todo quadro), informa
 * [Leitura.SEM_CAMERA]. A imagem vai pra tela pelo [preview]: [CameraOlhos] liga o visor nele quando aparece.
 */
class CameraDaMissao(private val ctx: Context, private val exigente: () -> Boolean, private val onLeitura: (Leitura, olhoAberto: Float?) -> Unit) : LifecycleOwner {
    private val registro = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registro

    val preview: Preview = Preview.Builder().setTargetFrameRate(QUADROS_POR_SEGUNDO).setResolutionSelector(IMAGEM_DO_CIRCULO).build()
    private val analise = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
    private val executor = Executors.newSingleThreadExecutor()
    private val detector: FaceDetector = FaceDetection.getClient(OPCOES_ROSTO)
    private var futuro: ListenableFuture<ProcessCameraProvider>? = null
    private var camera: Camera? = null
    private var falhasSeguidas = 0 // só mexida na thread principal, onde o ML Kit entrega o resultado
    private var ultimaAnalise = 0L // só mexida na thread da análise
    private var desligada = false

    /** Abre a câmera (na thread principal); os erros chegam por [onLeitura] como SEM_CAMERA. Uma vez só por objeto. */
    fun ligar() {
        registro.currentState = Lifecycle.State.STARTED
        val f = ProcessCameraProvider.getInstance(ctx).also { futuro = it }
        f.addListener({
            if (desligada) return@addListener
            runCatching {
                analise.setAnalyzer(executor) { quadro ->
                    val agora = SystemClock.elapsedRealtime()
                    if (agora - ultimaAnalise < ANALISE_A_CADA_MS) {
                        quadro.close() // fora da vez: descarta sem passar pelo detector
                        return@setAnalyzer
                    }
                    ultimaAnalise = agora
                    analisar(
                        quadro, detector, exigente,
                        onLeitura = { leitura, aberto ->
                            falhasSeguidas = 0
                            onLeitura(leitura, aberto)
                        },
                        onFalha = {
                            if (++falhasSeguidas == FALHAS_SEGUIDAS_MAX) {
                                Log.w(TAG, "Câmera: o detector de rosto falhou $FALHAS_SEGUIDAS_MAX vezes seguidas")
                                onLeitura(Leitura.SEM_CAMERA, null)
                            }
                        },
                    )
                }
                val cameras = f.get()
                cameras.unbindAll()
                val aberta = cameras.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analise)
                aberta.cameraInfo.cameraState.observe(this) { estado -> estado.error?.let { tratarErro(it, onLeitura) } }
                camera = aberta
                Log.i(TAG, "Câmera: aberta (no serviço)")
            }.onFailure {
                Log.w(TAG, "Câmera: não abriu", it)
                onLeitura(Leitura.SEM_CAMERA, null)
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    /** Fecha a câmera e solta o detector. Na thread principal. */
    fun desligar() {
        if (desligada) return
        desligada = true
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        futuro?.let { if (it.isDone) runCatching { it.get().unbindAll() } }
        registro.currentState = Lifecycle.State.DESTROYED
        detector.close()
        executor.shutdown()
        Log.i(TAG, "Câmera: fechada")
    }

    /** A tela girou: a imagem e a análise acompanham (deitado, o detector veria o rosto de lado). */
    fun girar(rotacao: Int) {
        preview.targetRotation = rotacao
        analise.targetRotation = rotacao
    }
}

/**
 * O visor da [camera] do serviço, na tela: liga o visor ao fluxo da câmera quando aparece e solta quando some; a
 * câmera e a detecção seguem no serviço de qualquer jeito.
 */
@Composable
fun CameraOlhos(modifier: Modifier, camera: CameraDaMissao) {
    val ctx = LocalContext.current
    val visor = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE // permite recortar em círculo
        }
    }
    // A tela gira sem ser recriada (configChanges). Ler a configuração faz isto rodar de novo a cada giro
    val rotacao = LocalConfiguration.current.let { LocalView.current.display?.rotation }
    SideEffect { rotacao?.let(camera::girar) }
    DisposableEffect(camera) {
        camera.preview.setSurfaceProvider(visor.surfaceProvider)
        onDispose { camera.preview.setSurfaceProvider(null) }
    }
    AndroidView(factory = { visor }, modifier = modifier)
}

/**
 * Erro grave da câmera (não tem como voltar a funcionar) é defeito: [onLeitura] recebe SEM_CAMERA.
 * Câmera bloqueada no atalho de privacidade do Android é escolha, não defeito: liberando, ela volta.
 * Os erros passageiros (outro app usando a câmera) o CameraX resolve sozinho.
 */
private fun tratarErro(erro: CameraState.StateError, onLeitura: (Leitura, Float?) -> Unit) {
    Log.w(TAG, "Câmera: erro ${erro.code} (${erro.type})")
    if (erro.type == CameraState.ErrorType.CRITICAL && erro.code != CameraState.ERROR_CAMERA_DISABLED) onLeitura(Leitura.SEM_CAMERA, null)
}

@OptIn(ExperimentalGetImage::class)
private fun analisar(quadro: ImageProxy, detector: FaceDetector, exigente: () -> Boolean, onLeitura: (Leitura, Float?) -> Unit, onFalha: () -> Unit) {
    val imagem = quadro.image
    if (imagem == null) {
        quadro.close()
        return
    }
    detector.process(InputImage.fromMediaImage(imagem, quadro.imageInfo.rotationDegrees))
        .addOnSuccessListener { rostos -> lerRosto(rostos, exigente(), onLeitura) }
        .addOnFailureListener { onFalha() }
        .addOnCompleteListener { quadro.close() }
}

/** Considera só o maior rosto (o mais perto da câmera). */
private fun lerRosto(rostos: List<Face>, exigente: Boolean, onLeitura: (Leitura, Float?) -> Unit) {
    val rosto = rostos.maxByOrNull { it.boundingBox.width() } ?: return onLeitura(Leitura.SEM_ROSTO, null)
    val esquerdo = rosto.leftEyeOpenProbability
    val direito = rosto.rightEyeOpenProbability
    onLeitura(classificarRosto(esquerdo, direito, exigente), olhoAberto(esquerdo, direito))
}

/**
 * Modo preciso e rosto a partir de 10% da largura da imagem: no modo rápido, e com o mínimo de 20%,
 * o rosto sumia a cada 1 ou 2 s com o celular a um braço de distância e o anel não enchia.
 */
private val OPCOES_ROSTO = FaceDetectorOptions.Builder()
    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // liga a probabilidade de olho aberto
    .setMinFaceSize(0.1f)
    .build()
