// Arquivos de som WAV (PCM de 16 bits): ler as amostras de uma fala gravada, tocar uma fala pelo AudioTrack e gravar
// amostras num arquivo (o teste do alto-falante).
package com.implacavel.alarme

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * As amostras e a taxa de um WAV PCM de 16 bits mono (como sai do sintetizador), ou null se ele for de outro formato
 * ou estiver estragado (nunca lança erro: a fala vai pelo MediaPlayer, ou não vai).
 */
fun lerPcm16(wav: File): Pair<ShortArray, Int>? = runCatching {
    val bytes = wav.readBytes()
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
    var p = 12L
    var formatoOk = false
    var taxa = 0
    while (p + 8 <= bytes.size) {
        val i = p.toInt()
        val id = String(bytes, i, 4)
        val tamanho = b.getInt(i + 4).toLong() and 0xFFFFFFFFL
        val corpo = i + 8
        if (id == "fmt " && corpo + 16 <= bytes.size) {
            formatoOk = b.getShort(corpo).toInt() == 1 && b.getShort(corpo + 2).toInt() == 1 && b.getShort(corpo + 14).toInt() == 16
            taxa = b.getInt(corpo + 4)
        }
        if (id == "data") {
            if (!formatoOk || taxa <= 0) return null
            // O sintetizador pode deixar o tamanho em branco (0 ou "desconhecido") se gravou aos poucos: vale até o fim do arquivo
            val fim = if (tamanho == 0L || corpo + tamanho > bytes.size) bytes.size else (corpo + tamanho).toInt()
            return ShortArray((fim - corpo) / 2) { b.getShort(corpo + it * 2) } to taxa
        }
        p = corpo + tamanho + (tamanho and 1)
    }
    null
}.getOrNull()

/**
 * Uma fala gravada ([wav], PCM de 16 bits mono) inteira num AudioTrack, pronta pro play(), e quantos quadros ela tem;
 * null se o arquivo for de outro formato. O AudioTrack, e não o MediaPlayer: no S24 FE, o MediaPlayer toca todo som
 * de 5 s ou mais começando baixinho e subindo por 3 s (medido na saída pro alto-falante, `dumpsys media.audio_flinger`,
 * "Signal power history": um tom de 6 s saía a -61 dB no 1º segundo e só chegava aos -38 dB no 3º; pelo AudioTrack,
 * -38 dB desde o começo). Era a voz da IA "começando baixo e depois aumentando": as falas dela passam de 5 s.
 */
fun faixaDaFala(wav: File, atributos: AudioAttributes): Pair<AudioTrack, Int>? {
    val (amostras, taxa) = lerPcm16(wav) ?: return null
    if (amostras.isEmpty()) return null
    val faixa = runCatching {
        AudioTrack.Builder()
            .setAudioAttributes(atributos)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(taxa).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(amostras.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
    }.getOrNull() ?: return null
    // No modo estático, o AudioTrack só fica pronto (STATE_INITIALIZED) depois de receber o som inteiro
    val escritas = if (faixa.state == AudioTrack.STATE_UNINITIALIZED) -1 else faixa.write(amostras, 0, amostras.size)
    if (escritas != amostras.size || faixa.state != AudioTrack.STATE_INITIALIZED) {
        faixa.release()
        return null
    }
    return faixa to amostras.size
}

/** Grava [amostras] (mono) num WAV PCM de 16 bits. */
fun gravarWav(arquivo: File, amostras: ShortArray, taxa: Int) {
    val dados = amostras.size * 2
    val b = ByteBuffer.allocate(44 + dados).order(ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()).putInt(36 + dados).put("WAVE".toByteArray())
    b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(taxa).putInt(taxa * 2).putShort(2).putShort(16)
    b.put("data".toByteArray()).putInt(dados)
    amostras.forEach { b.putShort(it) }
    arquivo.writeBytes(b.array())
}
