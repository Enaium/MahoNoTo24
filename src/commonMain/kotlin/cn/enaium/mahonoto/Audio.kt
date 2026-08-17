package cn.enaium.mahonoto

import cn.enaium.sdl.SDLAudioDeviceID
import cn.enaium.sdl.SDLAudioFormat
import cn.enaium.sdl.SDLAudioSpec
import cn.enaium.sdl.SDLProperties
import cn.enaium.sdl.mixer.SDLAudio
import cn.enaium.sdl.mixer.SDLMixer
import cn.enaium.sdl.mixer.SDLMixerDevice
import cn.enaium.sdl.mixer.SDLMixerPlayProp
import cn.enaium.sdl.mixer.SDLTrack

/**
 * Audio via sdl-mixer-kmp (SDL_mixer 3).
 *
 * Short SFX are played fire-and-forget on the mixer (each play restarts);
 * BGM loops forever on a dedicated track.
 *
 * Loads:
 *   sounds_wav dir  -> SFX keyed by file base name
 *   bgms_wav dir    -> BGM keyed by file base name
 */
class Audio(private val root: String) {

    private val sfx = HashMap<String, SDLAudio>()
    private val bgm = HashMap<String, SDLAudio>()

    private var device: SDLMixerDevice? = null
    private var bgmTrack: SDLTrack? = null
    private var bgmKey: String? = null
    private var enabled = true

    private fun spec() = SDLAudioSpec(
        format = SDLAudioFormat.S16,
        channels = 2,
        freq = 44100,
    )

    fun initStreams() {
        try {
            if (!SDLMixer.init()) return
            device = SDLMixer.createMixerDevice(SDLAudioDeviceID.DEFAULT_PLAYBACK, spec())
        } catch (t: Throwable) {
            device = null
        }
    }

    fun load() {
        val d = device ?: return
        Fio.listDir("$root/sounds_wav")?.forEach { name ->
            if (!name.endsWith(".wav")) return@forEach
            val key = name.removeSuffix(".wav")
            sfx[key] = try { d.loadAudio("$root/sounds_wav/$name") } catch (t: Throwable) { return@forEach }
        }
        Fio.listDir("$root/bgms_wav")?.forEach { name ->
            if (!name.endsWith(".wav")) return@forEach
            val key = name.removeSuffix(".wav")
            // predecode BGM so the looping track can seek/restart reliably
            bgm[key] = try { d.loadAudio("$root/bgms_wav/$name", predecode = true) } catch (t: Throwable) { return@forEach }
        }
    }

    fun playSfx(key: String) {
        if (!enabled) return
        val d = device ?: return
        val audio = sfx[key] ?: return
        try {
            d.playAudio(audio)
        } catch (t: Throwable) {
        }
    }

    /** Plays a BGM track (key like "bgm_720"); null stops music. */
    fun playBgm(key: String?) {
        if (key == bgmKey && bgmKey != null) return
        bgmKey = key
        val d = device ?: return
        if (key == null) {
            d.stopAllTracks()
            return
        }
        val audio = bgm[key]
        if (audio == null) {
            d.stopAllTracks()
            return
        }
        val track = bgmTrack ?: d.createTrack().also {
            it.tag("bgm")
            bgmTrack = it
        }
        track.setAudio(audio)
        val options = SDLProperties.create()
        SDLProperties.setProperty(options, SDLMixerPlayProp.LOOPS_NUMBER, -1L)
        try {
            track.play(options)
        } catch (t: Throwable) {
        }
    }

    fun update() {
        val d = device ?: return
        val key = bgmKey ?: return
        val audio = bgm[key] ?: return
        val track = bgmTrack ?: return
        // if the mixer's loop stalls/ends, restart the track
        if (!track.playing && !track.paused) {
            val options = SDLProperties.create()
            SDLProperties.setProperty(options, SDLMixerPlayProp.LOOPS_NUMBER, -1L)
            try {
                track.play(options)
            } catch (t: Throwable) {
            }
        }
    }

    fun stopAll() {
        try {
            device?.stopAllTracks()
        } catch (t: Throwable) {
        }
    }

    fun close() {
        try {
            device?.close()
        } catch (t: Throwable) {
        }
        device = null
        bgmTrack = null
        SDLMixer.quit()
    }
}
