package cn.enaium.mahonoto

import cn.enaium.sdl.SDLAudioData
import cn.enaium.sdl.SDLAudioDeviceID
import cn.enaium.sdl.SDLAudioStream
import cn.enaium.sdl.SDL

/**
 * Audio: short SFX are played on a dedicated stream (cleared before each
 * play, like the original's single sound channel); BGM is queued on a
 * separate looping stream.
 *
 * Loads:
 *   sounds_wav dir  -> SFX keyed by file base name
 *   bgms_wav dir    -> BGM keyed by file base name
 */
class Audio(private val root: String) {

    private val sfx = HashMap<String, ByteArray>()
    private val bgm = HashMap<String, ByteArray>()

    private var sfxStream: SDLAudioStream? = null
    private var bgmStream: SDLAudioStream? = null
    private var bgmKey: String? = null
    private var enabled = true

    fun load() {
        Fio.listDir("$root/sounds_wav")?.forEach { name ->
            if (!name.endsWith(".wav")) return@forEach
            val key = name.removeSuffix(".wav")
            val wav = SDL.loadWAV("$root/sounds_wav/$name") ?: return@forEach
            sfx[key] = wav.data
        }
        Fio.listDir("$root/bgms_wav")?.forEach { name ->
            if (!name.endsWith(".wav")) return@forEach
            val key = name.removeSuffix(".wav")
            val wav = SDL.loadWAV("$root/bgms_wav/$name") ?: return@forEach
            bgm[key] = wav.data
        }
    }

    fun initStreams() {
        try {
            val spec = cn.enaium.sdl.SDLAudioSpec(
                format = cn.enaium.sdl.SDLAudioFormat.S16,
                channels = 2,
                freq = 44100,
            )
            sfxStream = SDL.openAudioDeviceStream(SDLAudioDeviceID.DEFAULT_PLAYBACK, spec).also {
                it.devicePaused = false
                it.gain = 1.0f
            }
            SDL.resumeAudioDevice(SDLAudioDeviceID.DEFAULT_PLAYBACK)
        } catch (t: Throwable) {
            sfxStream = null
        }
    }

    fun playSfx(key: String) {
        if (!enabled) return
        val data = sfx[key] ?: return
        val s = sfxStream ?: return
        try {
            s.clear()
            s.putData(data)
        } catch (t: Throwable) {
        }
    }

    /** Plays a BGM track (key like "bgm_720"); null stops music. */
    fun playBgm(key: String?) {
        if (key == bgmKey && bgmKey != null) return
        bgmKey = key
        val s = bgmStream ?: run {
            if (key == null) return
            try {
                val spec = cn.enaium.sdl.SDLAudioSpec(
                    format = cn.enaium.sdl.SDLAudioFormat.S16,
                    channels = 2,
                    freq = 44100,
                )
                bgmStream = SDL.openAudioDeviceStream(SDLAudioDeviceID.DEFAULT_PLAYBACK, spec).also {
                    it.devicePaused = false
                    it.gain = 0.7f
                }
                SDL.resumeAudioDevice(SDLAudioDeviceID.DEFAULT_PLAYBACK)
                bgmStream!!
            } catch (t: Throwable) {
                return
            }
        }
        val data = key?.let { bgm[it] }
        if (data == null) {
            s.clear()
        } else {
            s.clear()
            s.putData(data)
        }
    }

    /** Re-queues BGM data when the stream is running low (loop support). */
    fun update() {
        val s = bgmStream ?: return
        val key = bgmKey ?: return
        val data = bgm[key] ?: return
        if (s.queued < data.size / 2 && s.queued < 512_000) {
            s.putData(data)
        }
    }

    fun stopAll() {
        bgmStream?.clear()
        sfxStream?.clear()
    }

    fun close() {
        sfxStream?.close()
        bgmStream?.close()
        sfxStream = null
        bgmStream = null
    }
}
