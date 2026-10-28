package com.implacavel.alarme

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** A voz do app nunca fica abaixo disso: muda, a missão perderia as falas. */
const val VOLUME_DA_VOZ_MIN = 0.10f

/** O que o dono escreve sobre si pra IA ([Ajustes.sobreMim]) vai até esse tanto de letras (a IA lê tudo antes de falar). */
const val SOBRE_MIM_MAX_LETRAS = 600

/**
 * Ajustes e estado que precisam sobreviver ao processo: o volume da voz, os tempos dos despertadores ([Tempos]), o
 * que o dono contou de si e o que a IA anotou dele na última conversa ([memoriaDaIa]), e o alarme em andamento (e onde a
 * missão dele está). Gravado em [prefsProtegidas], como os alarmes. Os sons ficam em [Sons] (a música única de antes,
 * "musicaUri", é migrada pra lá).
 */
object Ajustes {
    private lateinit var prefs: SharedPreferences

    /**
     * O alarme que tocou e ainda não foi cumprido. Só [AlarmeService] apaga, quando a missão é
     * cumprida (e a saída de emergência pelo cabo, [PararPeloCabo], que só o adb chama). Se o processo morrer no meio
     * (celular desligado, app encerrado ou caído), o [App] vê isto ao voltar e religa o alarme; um teste, ele só apaga.
     * Gravação ilegível conta como nenhum, pra não derrubar o app.
     */
    var emAndamento: EmAndamento?
        get() = runCatching { prefs.getString("alarmeEmAndamento", null)?.let { EmAndamento.deJson(JSONObject(it)) } }.getOrNull()
        set(valor) = prefs.edit(commit = true) {
            if (valor == null) remove("alarmeEmAndamento") else putString("alarmeEmAndamento", valor.paraJson().toString())
        }

    /**
     * Volume de alarme de antes de um alarme que já foi cumprido mas ainda está comemorando (os sons do
     * fim tocam no fim da subida). Gravado antes de apagar o [emAndamento]: se o app morrer na comemoração, o [App]
     * devolve o volume ao voltar, e um alarme que dispare nesse meio guarda este como o "de antes".
     */
    var volumeParaDevolver: Int?
        get() = prefs.getInt("volumeParaDevolver", -1).takeIf { it >= 0 }
        set(valor) = prefs.edit(commit = true) { if (valor == null) remove("volumeParaDevolver") else putInt("volumeParaDevolver", valor) }

    /**
     * O alarme (pelo [EmAndamento.desde]) em que a IA do celular já foi carregada. Se o app recomeça o mesmo
     * alarme do zero (morreu no meio, por qualquer motivo), a IA fica de fora: pode ter sido ela. Gravado na hora
     * (commit), pra valer mesmo se o app cair carregando o modelo.
     */
    var iaTentadaEm: Long
        get() = prefs.getLong("iaTentadaEm", 0L)
        set(valor) = prefs.edit(commit = true) { putLong("iaTentadaEm", valor) }

    /**
     * Onde a missão do alarme em andamento estava ([Progresso]), gravado pelo serviço a cada poucos segundos. Só vale
     * pro alarme do mesmo [Progresso.desde]; gravação ilegível conta como nenhuma.
     */
    var progresso: Progresso?
        get() = runCatching { prefs.getString("progresso", null)?.let { Progresso.deJson(JSONObject(it)) } }.getOrNull()
        set(valor) = prefs.edit { if (valor == null) remove("progresso") else putString("progresso", valor.paraJson().toString()) }

    private val _volumeDaVoz = MutableStateFlow(VOLUME_DA_VOZ)

    /** Volume da voz do app, de 0,1 a 1 do volume de alarme, escolhido na tela principal (Settings). De começo, [VOLUME_DA_VOZ]. */
    val volumeDaVozAgora: StateFlow<Float> = _volumeDaVoz.asStateFlow()
    val volumeDaVoz: Float get() = _volumeDaVoz.value

    fun escolherVolumeDaVoz(fracao: Float) {
        val valor = fracao.coerceIn(VOLUME_DA_VOZ_MIN, 1f)
        prefs.edit { putFloat("volumeDaVoz", valor) }
        _volumeDaVoz.value = valor
    }

    private val _tempos = MutableStateFlow(TEMPOS_PADRAO)

    /** Os tempos e o volume da música dos despertadores ([Tempos]), escolhidos na tela principal (Settings). */
    val tempos: StateFlow<Tempos> = _tempos.asStateFlow()

    fun escolherTempos(novos: Tempos) {
        val t = novos.ajustado()
        prefs.edit {
            putFloat("volumeInicio", t.volumeInicio)
            putFloat("volumeFim", t.volumeFim)
            putInt("cameraFacilMin", t.cameraFacilMin)
            putInt("frases", t.frases)
            putInt("cameraDificilMin", t.cameraDificilMin)
            putInt("conversaMin", t.conversaMin)
            putInt("prepararMin", t.prepararMin)
            putInt("caminhadaMin", t.caminhadaMin)
        }
        _tempos.value = t
    }

    private val _sobreMim = MutableStateFlow("")

    /** O que o dono contou de si pra IA da manhã (Settings › "About you"): quem é, o que quer, do que gosta. Vai inteiro na instrução dela. */
    val sobreMim: StateFlow<String> = _sobreMim.asStateFlow()

    fun escreverSobreMim(texto: String) {
        val valor = texto.take(SOBRE_MIM_MAX_LETRAS)
        prefs.edit { putString("sobreMim", valor) }
        _sobreMim.value = valor
    }

    private val _palavras = MutableStateFlow("")

    /**
     * As palavras que o dono usa e o reconhecedor de voz erra (Settings › "Your words": nomes, termos do trabalho), em
     * texto, separadas por vírgula. Vão pro reconhecedor (que tende a elas) e pra IA (que sabe que podem vir trocadas).
     */
    val palavras: StateFlow<String> = _palavras.asStateFlow()

    fun escreverPalavras(texto: String) {
        val valor = texto.take(PALAVRAS_MAX_LETRAS)
        prefs.edit { putString("palavras", valor) }
        _palavras.value = valor
    }

    private val _memoriaDaIa = MutableStateFlow("")
    private val _memoriaEm = MutableStateFlow(0L)

    /**
     * O que a IA anotou do dono na última conversa ([limparAnotacoes]): no fim de cada conversa ela escreve as notas
     * do zero (nada se acumula), e na manhã seguinte lê, se ainda valem ([memoriaVale], 36 h: a janela de um dia que
     * o dono pediu). O dono vê e apaga nos ajustes ("What the AI remembers").
     */
    val memoriaDaIa: StateFlow<String> = _memoriaDaIa.asStateFlow()

    /** Quando (epoch, ms) a IA escreveu [memoriaDaIa]; 0 = nunca. */
    val memoriaEm: StateFlow<Long> = _memoriaEm.asStateFlow()

    /** As anotações pra instrução desta manhã: as da última conversa, se foram escritas há menos de um dia e meio; senão, nada. */
    fun memoriaDaIaValida(agora: Long = System.currentTimeMillis()): String =
        if (memoriaVale(_memoriaEm.value, agora)) _memoriaDaIa.value else ""

    fun guardarMemoriaDaIa(texto: String, agora: Long = System.currentTimeMillis()) {
        val valor = limparAnotacoes(texto)
        val quando = if (valor.isEmpty()) 0L else agora
        prefs.edit {
            putString("memoriaDaIa", valor)
            putLong("memoriaEm", quando)
        }
        _memoriaDaIa.value = valor
        _memoriaEm.value = quando
    }

    /** Chamado uma vez em [App.onCreate]. */
    fun carregar(ctx: Context) {
        prefs = ctx.prefsProtegidas("ajustes")
        _volumeDaVoz.value = prefs.getFloat("volumeDaVoz", VOLUME_DA_VOZ).coerceIn(VOLUME_DA_VOZ_MIN, 1f)
        val p = TEMPOS_PADRAO
        _tempos.value = Tempos(
            volumeInicio = prefs.getFloat("volumeInicio", p.volumeInicio),
            volumeFim = prefs.getFloat("volumeFim", p.volumeFim),
            cameraFacilMin = prefs.getInt("cameraFacilMin", p.cameraFacilMin),
            frases = prefs.getInt("frases", p.frases),
            cameraDificilMin = prefs.getInt("cameraDificilMin", p.cameraDificilMin),
            conversaMin = prefs.getInt("conversaMin", p.conversaMin),
            prepararMin = prefs.getInt("prepararMin", p.prepararMin),
            caminhadaMin = prefs.getInt("caminhadaMin", p.caminhadaMin),
        ).ajustado()
        _sobreMim.value = prefs.getString("sobreMim", "").orEmpty().take(SOBRE_MIM_MAX_LETRAS)
        _palavras.value = prefs.getString("palavras", "").orEmpty().take(PALAVRAS_MAX_LETRAS)
        _memoriaDaIa.value = limparAnotacoes(prefs.getString("memoriaDaIa", "").orEmpty())
        _memoriaEm.value = prefs.getLong("memoriaEm", 0L)
    }
}
