// A biblioteca de sons do app (pedido do dono, 10-17): todo som escolhido, pela tela principal ou pelo editor de um
// alarme, é COPIADO pra dentro do app, e fica: o dia em que o arquivo sair da pasta Downloads, o app já tem a versão
// dele. Cada alarme escolhe o seu ([Alarme.som], o nome do arquivo na biblioteca); sem escolha, vale o padrão
// ([Sons.padrao]); sem padrão, o toque de alarme do celular (Sirene). A pasta é protegida (como os alarmes): dá pra
// ler antes do primeiro desbloqueio, então depois de um reinício a música toca, e não o toque do sistema.
// O app não traz música nenhuma: a biblioteca começa vazia.
//
// Pelo cabo, no app de depuração (veja o README):
//   adb push som.mp3 /data/local/tmp/ && adb shell run-as com.implacavel.alarme cp /data/local/tmp/som.mp3 /data/user_de/0/com.implacavel.alarme/app_sons/
package com.implacavel.alarme

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object Sons {
    /** Um som da biblioteca: o [nome] do arquivo (o que a tela mostra e o alarme guarda) e o [arquivo]. */
    data class Som(val nome: String, val arquivo: File)

    private lateinit var pasta: File
    private lateinit var prefs: SharedPreferences
    private val _lista = MutableStateFlow<List<Som>>(emptyList())
    private val _padrao = MutableStateFlow<String?>(null)

    /** Os sons da biblioteca, em ordem de nome. */
    val lista: StateFlow<List<Som>> = _lista.asStateFlow()

    /** O nome do som que toca nos alarmes sem som próprio; null = o toque de alarme do celular. */
    val padrao: StateFlow<String?> = _padrao.asStateFlow()

    /** Chamado uma vez em [App.onCreate]. */
    fun carregar(ctx: Context) {
        pasta = ctx.createDeviceProtectedStorageContext().getDir("sons", Context.MODE_PRIVATE)
        prefs = ctx.prefsProtegidas("sons")
        _padrao.value = prefs.getString("padrao", null)
        reler()
        migrarMusicaAntiga(ctx)
    }

    /** Relê a pasta (um som pode ter chegado pelo cabo); um padrão que sumiu deixa de ser padrão. */
    fun reler() {
        _lista.value = (pasta.listFiles() ?: emptyArray())
            .filter { it.isFile && it.length() > 0 }
            .sortedBy { it.name.lowercase() }
            .map { Som(it.name, it) }
        if (_padrao.value != null && existente(_padrao.value!!) == null) escolherPadrao(null)
    }

    /** Copia o áudio em [uri] pra biblioteca (com o nome do arquivo, sem repetir) e devolve o som; null se não deu pra ler. */
    fun adicionar(ctx: Context, uri: Uri): Som? {
        val nome = nomeLivre(nomeSeguro(nomeDoArquivo(ctx, uri)), _lista.value.map { it.nome })
        val destino = File(pasta, nome)
        val copiou = runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { entrada -> destino.outputStream().use { saida -> entrada.copyTo(saida) } }
            destino.length() > 0
        }.onFailure { Log.w(TAG, "Sons: não deu pra copiar $uri", it) }.getOrDefault(false)
        if (!copiou) {
            destino.delete()
            return null
        }
        Log.i(TAG, "Sons: copiado pra biblioteca: $nome (${destino.length() / 1024} KB)")
        reler()
        return Som(nome, destino)
    }

    /** Apaga o som [nome] da biblioteca: os alarmes que o usavam passam a tocar o padrão. */
    fun remover(nome: String) {
        File(pasta, nome).delete()
        Log.i(TAG, "Sons: removido da biblioteca: $nome")
        reler()
    }

    fun escolherPadrao(nome: String?) {
        prefs.edit(commit = true) { if (nome == null) remove("padrao") else putString("padrao", nome) }
        _padrao.value = nome
    }

    /**
     * O som que o [alarme] toca, como URI (file://, pra foto do alarme e pro MediaPlayer): o dele, se ainda está na
     * biblioteca; senão o padrão; senão null, e a Sirene toca o toque de alarme do celular.
     */
    fun uriDoAlarme(alarme: Alarme): String? = somDoAlarme(alarme)?.let { Uri.fromFile(it.arquivo).toString() }

    /** O som que o [alarme] toca ([uriDoAlarme]), ou null (o toque do celular). */
    fun somDoAlarme(alarme: Alarme): Som? = alarme.som?.let(::existente) ?: _padrao.value?.let(::existente)

    private fun existente(nome: String): Som? = _lista.value.firstOrNull { it.nome == nome }

    private fun nomeDoArquivo(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    /**
     * A música das versões anteriores (uma URI do seletor do sistema, em Ajustes) vira o padrão da biblioteca: copiada
     * uma vez e esquecida. Se não der pra ler agora (antes do primeiro desbloqueio, por exemplo), fica pra próxima vez.
     */
    private fun migrarMusicaAntiga(ctx: Context) {
        val antigos = ctx.prefsProtegidas("ajustes")
        val uri = antigos.getString("musicaUri", null) ?: return
        val som = adicionar(ctx, uri.toUri()) ?: return
        escolherPadrao(som.nome)
        runCatching { ctx.contentResolver.releasePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        antigos.edit(commit = true) {
            remove("musicaUri")
            remove("musicaNome")
        }
        Log.i(TAG, "Sons: a música de antes virou o padrão da biblioteca: ${som.nome}")
    }
}

/** Um nome de arquivo seguro pra biblioteca: sem barras, sem caracteres de controle, sem "." ou ".." e sem ficar vazio. */
fun nomeSeguro(nome: String?): String {
    val limpo = nome.orEmpty().replace(Regex("[/\\\\\\p{Cntrl}]"), "_").trim()
    return if (limpo.isEmpty() || limpo == "." || limpo == "..") "Sound" else limpo.take(120)
}

/** [nome] sem bater com nenhum dos [existentes]: "Raya.mp3", depois "Raya (2).mp3", "Raya (3).mp3"… */
fun nomeLivre(nome: String, existentes: Collection<String>): String {
    if (nome !in existentes) return nome
    val ponto = nome.lastIndexOf('.').takeIf { it > 0 } ?: nome.length
    val base = nome.substring(0, ponto)
    val extensao = nome.substring(ponto)
    return generateSequence(2) { it + 1 }.map { "$base ($it)$extensao" }.first { it !in existentes }
}
