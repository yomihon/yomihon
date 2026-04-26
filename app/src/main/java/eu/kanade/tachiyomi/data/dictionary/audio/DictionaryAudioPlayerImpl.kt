package eu.kanade.tachiyomi.data.dictionary.audio

import android.media.MediaPlayer
import mihon.domain.dictionary.audio.DictionaryAudio
import mihon.domain.dictionary.audio.DictionaryAudioPlayer

class DictionaryAudioPlayerImpl : DictionaryAudioPlayer {

    private var mediaPlayer: MediaPlayer? = null

    override fun play(audio: DictionaryAudio) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(audio.file.absolutePath)
            setOnCompletionListener {
                it.release()
                if (mediaPlayer === it) {
                    mediaPlayer = null
                }
            }
            setOnErrorListener { player, _, _ ->
                player.release()
                if (mediaPlayer === player) {
                    mediaPlayer = null
                }
                true
            }
            prepare()
            start()
        }
    }
}
