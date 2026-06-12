package dev.mimir.app

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import dev.mimir.theme.HapticLevel
import dev.mimir.theme.ThemeConfig

/** Fire-and-forget UI sounds from the active theme's sound slot (bundled default set for now). */
class SoundEngine(context: Context) {
    enum class Cue { NAV, SELECT, LAUNCH, BACK }

    @Volatile var config: ThemeConfig = ThemeConfig()

    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = mapOf(
        Cue.NAV to pool.load(context, R.raw.nav_tick, 1),
        Cue.SELECT to pool.load(context, R.raw.select, 1),
        Cue.LAUNCH to pool.load(context, R.raw.launch, 1),
        Cue.BACK to pool.load(context, R.raw.back, 1),
    )

    fun play(cue: Cue) {
        val volume = config.soundVolume.coerceIn(0f, 1f)
        if (volume <= 0f) return
        ids[cue]?.let { pool.play(it, volume, volume, 1, 0, 1f) }
    }

    /** Whether haptics should fire, per the active theme. */
    fun hapticsEnabled(): Boolean = config.haptics != HapticLevel.OFF
}
