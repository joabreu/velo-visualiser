package com.lowlatency.visualizer.hue

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lowlatency.visualizer.AudioSyncBus
import com.lowlatency.visualizer.BeatBus
import com.lowlatency.visualizer.BeatSettings
import com.lowlatency.visualizer.CaptureHealth
import com.lowlatency.visualizer.LightingSettings
import com.lowlatency.visualizer.LinkSync
import com.lowlatency.visualizer.NativeBridge
import com.lowlatency.visualizer.ScreenSyncBus
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Ties the Hue pipeline to the audio: takes a lightweight band snapshot from the
 * render loop ([onBands], called on the GL thread — cheap volatile writes) and
 * drives a dedicated ~50 Hz sender thread that maps the spectrum to per-channel
 * colors and pushes them over [HueStreamClient].
 *
 * Networking never touches the GL or main thread: the render loop only updates
 * three volatile floats; the sender thread does the DTLS work.
 */
class HueLightController(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val store = HueCredentialStore(context)
    val setup = HueSetupManager(context)

    // Latest bands, written by the GL thread, read by the sender thread.
    @Volatile private var low = 0f
    @Volatile private var mid = 0f
    @Volatile private var high = 0f
    private val screenRgb = FloatArray(ScreenSyncBus.COMPONENTS)

    // Smooth the spectrum before turning it into Hue colours. Raw 20 ms audio
    // blocks are too twitchy for room lighting; the lights should follow the
    // musical envelope rather than every FFT-sized fluctuation.
    private var smoothLow = 0f
    private var smoothMid = 0f
    private var smoothHigh = 0f

    // Each Hue channel keeps its own slowly changing RGB state.
    private var smoothedRgb = FloatArray(0)

    @Volatile private var running = false
    @Volatile var paused = false        // true while app is backgrounded; sender drops to 1 Hz keepalive
    private var senderThread: Thread? = null
    private var client: HueStreamClient? = null

    val isEnabled: Boolean get() = running
    val huePacketsSent: Long get() = client?.packetsSent ?: 0L
    val huePacketsFailed: Long get() = client?.packetsFailed ?: 0L

    // Incremented on the sender thread each time a beat flash is actually pushed to
    // the bulbs. The Advanced panel polls this to flash a "Lights" diagnostic dot.
    @Volatile var lightBeatCount = 0
        private set

    /** Called every render frame from the GL thread. Must stay allocation-free. */
    fun onBands(low: Float, mid: Float, high: Float) {
        this.low = low; this.mid = mid; this.high = high
    }

    // Incremented on each Ableton Link beat (GL thread). When Link sync is on the
    // sender loop flashes on these (with lookahead) instead of audio onset; the
    // gate/intensity/colour come from the shared BeatBus. Single writer (GL
    // thread); the sender thread only reads it.
    @Volatile private var linkBeatCount = 0
    fun onLinkBeat() { linkBeatCount++ }

    // Audio presence (loudness, bass balance) and the gated beat now live in the
    // shared BeatBus — the same gate the visuals and haptics use. Live values for
    // the Advanced panel's meter (read on the UI thread) read straight off it.
    val currentMicLevel: Float get() = BeatBus.level
    val currentBassRatio: Float get() = BeatBus.bassRatio

    /**
     * Start streaming to [area]: persist the choice, activate the stream over
     * REST, open DTLS, and spin up the sender loop. [onResult] is posted on the
     * main thread with success + an optional error message.
     */
    fun enable(area: HueEntertainmentArea, onResult: (Boolean, String?) -> Unit) {
        if (running) { onResult(true, null); return }
        val creds = store.loadCredentials()
        if (creds == null) { onResult(false, "Bridge not paired."); return }
        if (area.channels.isEmpty()) { onResult(false, "Area has no light channels."); return }

        store.selectedAreaId = area.id
        activeLightIds = area.lightIds
        setup.setStreamActive(creds, area.id, active = true) { ok ->
            if (!ok) { onResult(false, "Could not start the entertainment stream."); return@setStreamActive }
            startSender(creds, area, onResult)
        }
    }

    // Light IDs of the active area, captured on enable so [disable] can power them
    // off via REST (LIFX/Nanoleaf do the same on a user-initiated stop).
    private var activeLightIds: List<String> = emptyList()

    /**
     * Tear down and re-establish the stream. A DTLS entertainment session dies
     * while the app is backgrounded (Wi-Fi sleeps, the bridge times the session
     * out after ~10s), but UDP sends keep "succeeding" locally — so neither the
     * UI nor [packetsFailed] can tell it's dead. On return to the foreground we
     * rebuild it unconditionally. No-op if nothing was running. [onResult] is
     * posted on the main thread.
     */
    fun restart(area: HueEntertainmentArea, onResult: (Boolean, String?) -> Unit) {
        if (!running && senderThread == null) { main.post { onResult(false, null) }; return }
        thread(name = "hue-restart") {
            disable()                                  // joins sender, closes DTLS, deactivates
            try { Thread.sleep(250) } catch (_: InterruptedException) {}  // let deactivate land first
            main.post { enable(area, onResult) }       // reactivate + fresh DTLS handshake
        }
    }

    /**
     * Stop the sender loop, close DTLS, and deactivate the stream. When [turnOff]
     * is true (a user-initiated stop — sync toggle, system-audio switch, forget),
     * also power the bulbs off once the stream is released, matching LIFX/Nanoleaf.
     * The internal [restart] passes false so a foreground rebuild never blinks the
     * lights off.
     */
    fun disable(turnOff: Boolean = false) {
        if (!running && senderThread == null) return
        running = false
        senderThread?.let { runCatching { it.join(500) } }
        senderThread = null
        client?.close()
        client = null
        store.syncEnabled = false

        val creds = store.loadCredentials()
        val areaId = store.selectedAreaId
        val ids = activeLightIds
        if (creds != null && areaId != null) {
            // Power-off must follow stream release — REST control is ignored while the
            // entertainment session owns the lights.
            setup.setStreamActive(creds, areaId, active = false) {
                if (turnOff && ids.isNotEmpty()) setup.controlLights(creds, ids, on = false)
            }
        }
    }

    private fun startSender(
        creds: HueCredentials,
        area: HueEntertainmentArea,
        onResult: (Boolean, String?) -> Unit,
    ) {
        val channelIds = IntArray(area.channels.size) { area.channels[it].channelId }
        val rgb = FloatArray(channelIds.size * 3)
        smoothedRgb = FloatArray(channelIds.size * 3)

        senderThread = thread(name = "hue-sender", priority = Thread.NORM_PRIORITY + 1) {
            val c = HueStreamClient(
                bridgeIp = creds.bridgeIp,
                identity = creds.username.toByteArray(Charsets.US_ASCII),
                psk = HueStreamClient.hexToBytes(creds.clientKey),
                areaId = area.id,
            )
            try {
                c.connect()
            } catch (t: Throwable) {
                Log.e(TAG, "DTLS connect failed", t)
                main.post { onResult(false, "DTLS handshake failed: ${t.message}") }
                runCatching { c.close() }
                return@thread
            }
            client = c
            running = true
            store.syncEnabled = true
            main.post { onResult(true, null) }

            var flash = 0f
            var lastLinkBeat = linkBeatCount
            var lastBeat = BeatBus.beatCount
            // Held beat colour for Link mode, recomputed from bass presence each beat.
            var beatR = 1f; var beatG = 1f; var beatB = 1f
            val frameNs = 1_000_000_000L / SEND_HZ
            var linkBeatFired = false
            var lastWatchdogLogNs = 0L

            while (running) {
                val t0 = System.nanoTime()

                val nowNs = System.nanoTime()
                val screenFresh = CaptureHealth.screenFresh(nowNs)
                val rawAudioFresh = CaptureHealth.rawAudioFresh(nowNs)
                val analysisFresh = CaptureHealth.audioAnalysisFresh(nowNs)

                // Watchdog: do not let a dead capture path masquerade as live data.
                // We retain the last good RGB frame rather than sending black, but
                // record the stale source so the condition is diagnosable.
                if (nowNs - lastWatchdogLogNs > 2_000_000_000L &&
                    (!screenFresh || !rawAudioFresh || !analysisFresh)
                ) {
                    Log.w(
                        TAG,
                        "Capture freshness: screen=${CaptureHealth.screenAgeMs(nowNs)}ms " +
                            "rawAudio=${CaptureHealth.rawAudioAgeMs(nowNs)}ms " +
                            "analysis=${CaptureHealth.audioAnalysisAgeMs(nowNs)}ms"
                    )
                    lastWatchdogLogNs = nowNs
                }

                if (paused) {
                    // The Activity can be gone while the foreground service keeps
                    // capturing. In background we deliberately use only data that
                    // is known to be fresh; never reuse stale audio analysis.
                    if (screenFresh && ScreenSyncBus.active &&
                        ScreenSyncBus.snapshotInto(screenRgb)
                    ) {
                        val useSystem = BeatSettings.systemAudio && analysisFresh
                        val bal0 = if (useSystem) AudioSyncBus.band0 else if (analysisFresh) low * 1.10f else 0f
                        val bal1 = if (useSystem) AudioSyncBus.band1 else if (analysisFresh) low else 0f
                        val bal2 = if (useSystem) AudioSyncBus.band2 else if (analysisFresh) mid * 1.15f else 0f
                        val bal3 = if (useSystem) AudioSyncBus.band3 else if (analysisFresh) mid else 0f
                        val bal4 = if (useSystem) AudioSyncBus.band4 else if (analysisFresh) high * 1.15f else 0f
                        val bal5 = if (useSystem) AudioSyncBus.band5 else if (analysisFresh) high else 0f
                        val balLoudness = if (useSystem) AudioSyncBus.loudness else if (analysisFresh) BeatBus.loudness else 0f
                        val balFlux = if (useSystem) AudioSyncBus.spectralFlux else 0f
                        val balFlash = if (analysisFresh) flash else 0f
                        mapScreenColors(
                            channelIds.size,
                            screenRgb,
                            bal0, bal1, bal2, bal3, bal4, bal5,
                            balLoudness, balFlux, balFlash, rgb
                        )
                    }
                    c.send(channelIds, rgb)
                    try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                    continue
                }

                val systemAudio = BeatSettings.systemAudio
                val rawL = if (systemAudio) AudioSyncBus.low else low
                val rawM = if (systemAudio) AudioSyncBus.mid else mid
                val rawH = if (systemAudio) AudioSyncBus.high else high
                // ~120 ms attack/release smoothing at 50 Hz. This makes colour
                // changes musical instead of flickering with individual samples.
                smoothLow += 0.035f * (rawL - smoothLow)
                smoothMid += 0.035f * (rawM - smoothMid)
                smoothHigh += 0.035f * (rawH - smoothHigh)

                val l = smoothLow
                val m = smoothMid
                val h = smoothHigh
                val audioLoudness = if (systemAudio) AudioSyncBus.loudness else BeatBus.loudness
                val audioBassRatio = if (systemAudio) AudioSyncBus.bassRatio else BeatBus.bassRatio
                val audioBeatCount = if (systemAudio) AudioSyncBus.beatCount else BeatBus.beatCount
                val band0 = if (systemAudio) AudioSyncBus.band0 else l * 1.10f
                val band1 = if (systemAudio) AudioSyncBus.band1 else l
                val band2 = if (systemAudio) AudioSyncBus.band2 else m * 1.15f
                val band3 = if (systemAudio) AudioSyncBus.band3 else m
                val band4 = if (systemAudio) AudioSyncBus.band4 else h * 1.15f
                val band5 = if (systemAudio) AudioSyncBus.band5 else h
                val spectralFlux = if (systemAudio) AudioSyncBus.spectralFlux else
                    ((h - l).coerceAtLeast(0f) * 0.7f + audioLoudness * 0.3f).coerceIn(0f, 1f)

                // Ableton Link's beat polling belongs to the GL thread. For system
                // playback we instead use the service-owned audio analysis so the
                // Hue stream remains fully live after the Activity is backgrounded.
                if (LinkSync.enabled && !systemAudio) {
                    // Beat-strobe: dark between Link beats; on each beat flash a
                    // colour chosen by bass presence. The gate, intensity and
                    // colour all come from the shared BeatBus — the same gate the
                    // visuals use — so the lights and screen stay in lock-step.
                    val cfg = LightingSettings
                    val lookaheadMs = cfg.hueLookaheadMs
                    val bc = linkBeatCount
                    var shouldFlash = false

                    if (lookaheadMs > 0f) {
                        if (bc != lastLinkBeat) {
                            if (!linkBeatFired) shouldFlash = true
                            lastLinkBeat = bc
                            linkBeatFired = false
                        }
                        if (!shouldFlash && !linkBeatFired) {
                            val phase = NativeBridge.nativeLinkBeatPhase()
                            val bpm = NativeBridge.nativeLinkTempo()
                            if (bpm > 0.0) {
                                val msUntilBeat = (1.0 - phase) * 60000.0 / bpm
                                if (msUntilBeat <= lookaheadMs) {
                                    linkBeatFired = true
                                    shouldFlash = true
                                }
                            }
                        }
                    } else {
                        if (bc != lastLinkBeat) {
                            shouldFlash = true
                            lastLinkBeat = bc
                        }
                    }

                    // Honour the gate and the user's "disable light beat" toggle.
                    if (shouldFlash && cfg.linkBeatFlashEnabled && BeatBus.gateOpen) {
                        flash = cfg.beatFlashAmp(audioLoudness)
                        lightBeatCount++

                        val ct = ((audioBassRatio - cfg.bassLo) / (cfg.bassHi - cfg.bassLo)).coerceIn(0f, 1f)
                        val cs = ct * ct * (3f - 2f * ct)
                        val hue = RED_HUE + (PURPLE_HUE - RED_HUE) * cs
                        val sat = SAT_TREBLE + (SAT_BASS - SAT_TREBLE) * cs
                        hsvToRgb(hue, sat, 1f)
                        beatR = hsvOut[0]; beatG = hsvOut[1]; beatB = hsvOut[2]
                    }
                    flash *= FLASH_DECAY
                    // Beat punches on top of the shared resting glow/floor (lights
                    // rest low instead of going dark in quiet parts). The brightness
                    // curve is shared across all brands — presets shape it.
                    val v = cfg.linkBrightnessValue(flash)
                    val r = (beatR * v).coerceIn(0f, 1f)
                    val g = (beatG * v).coerceIn(0f, 1f)
                    val b = (beatB * v).coerceIn(0f, 1f)
                    for (i in channelIds.indices) {
                        rgb[i * 3] = r; rgb[i * 3 + 1] = g; rgb[i * 3 + 2] = b
                    }
                } else {
                    // Audio mode: flash on the very same gated beat the visuals
                    // fire on (shared BeatBus), scaled by loudness; colour follows
                    // the spectrum bands.
                    val bc = audioBeatCount
                    if (bc != lastBeat) {
                        // Beat controls a slower, visible colour/brightness pulse.
                        // Keep the strongest pulse when beats arrive close together.
                        flash = maxOf(flash, (audioLoudness * MAX_BEAT_PULSE).coerceIn(0f, MAX_BEAT_PULSE))
                        lastBeat = bc
                        lightBeatCount++
                    }
                    flash *= FLASH_DECAY
                    if (ScreenSyncBus.active &&
                        ScreenSyncBus.snapshotInto(screenRgb)
                    ) {
                        mapScreenColors(
                            channelIds.size,
                            screenRgb,
                            band0, band1, band2, band3, band4, band5,
                            audioLoudness,
                            spectralFlux,
                            flash,
                            rgb
                        )
                    } else {
                        mapColors(
                            channelIds.size,
                            band0, band1, band2, band3, band4, band5,
                            audioLoudness,
                            spectralFlux,
                            flash,
                            rgb
                        )
                    }
                }

                c.send(channelIds, rgb)

                val deadlineNs = t0 + frameNs
                val sleepNs = deadlineNs - System.nanoTime() - SPIN_MARGIN_NS
                if (sleepNs > 0) {
                    try { Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt()) }
                    catch (_: InterruptedException) { break }
                }
                // Spin-wait the final margin for precise timing
                while (System.nanoTime() < deadlineNs) Thread.yield()
            }
        }
    }

    /**
     * Mixes the colour of each full-screen zone with the instantaneous
     * six-band musical spectrum. Screen colour remains spatially dominant,
     * while spectrum changes hue/saturation and flux adds a short-lived
     * creative accent. Each light has its own zone and therefore its own
     * target colour.
     */
    private fun mapScreenColors(
        count: Int,
        screen: FloatArray,
        band0: Float,
        band1: Float,
        band2: Float,
        band3: Float,
        band4: Float,
        band5: Float,
        loudness: Float,
        flux: Float,
        flash: Float,
        out: FloatArray,
    ) {
        if (smoothedRgb.size != count * 3) {
            smoothedRgb = FloatArray(count * 3)
        }

        // Keep the ImageReader/full-screen spatial capture untouched.
        // Screen RGB is the primary source; audio only modulates hue/saturation.
        val low = (band0 + band1).coerceAtLeast(0f)
        val mid = (band2 + band3).coerceAtLeast(0f)
        val high = (band4 + band5).coerceAtLeast(0f)
        val total = low + mid + high + 0.0001f

        val bass = low / total
        val mids = mid / total
        val treble = high / total
        val energy = loudness.coerceIn(0f, 1f)
        val beat = flash.coerceIn(0f, MAX_BEAT_PULSE)

        // Deliberately use clearly separated musical hue regions.
        val audioHue = normalizeHue(
            8f * bass +
                120f * mids +
                220f * treble
        )

        for (i in 0 until count) {
            val zone = SCREEN_ZONE_ORDER[i % SCREEN_ZONE_ORDER.size]
            val src = zone * 3
            val dst = i * 3

            rgbToHsv(
                screen[src].coerceIn(0f, 1f),
                screen[src + 1].coerceIn(0f, 1f),
                screen[src + 2].coerceIn(0f, 1f)
            )

            val screenHue = hsvIn[0]
            val screenSat = hsvIn[1].coerceIn(0f, 1f)
            val screenValue = hsvIn[2].coerceIn(0f, 1f)

            // Never let neutral screen pixels collapse to HSV saturation=0.
            // This is the key fix for the all-white output.
            val audioMix = if (screenSat >= 0.12f) {
                // Preserve strong video colours.
                (0.10f + flux * 0.18f + energy * 0.08f)
                    .coerceIn(0.10f, 0.34f)
             } else {
                // White/grey video gets a strong musical colour.
                (0.68f + flux * 0.10f + energy * 0.08f)
                    .coerceIn(0.68f, 0.86f)
             }
 
            var hue = normalizeHue(
                screenHue +
                    shortestHueDelta(screenHue, audioHue) * audioMix
            )

            // Spatial variation: preserve screen zones while allowing
            // neighbouring Hue lights to have visibly different tones.
            if (count > 1) {
                val position = i.toFloat() / (count - 1)
                hue = normalizeHue(hue + (position - 0.5f) * 22f)
            }

            // Beat changes colour/brightness gently rather than strobing.
            hue = normalizeHue(hue - beat * 10f)

            // Crucially, neutral screen pixels get real saturation.
            val audioSaturation = (
                0.62f +
                    energy * 0.18f +
                    flux * 0.16f +
                    beat * 0.08f
                ).coerceIn(0.58f, 0.96f)

            val saturation = if (screenSat >= 0.12f) {
                // Vividify actual screen colours instead of washing them out.
                (screenSat * 1.22f + audioSaturation * 0.08f)
                    .coerceIn(0.48f, 1f)
             } else {
                audioSaturation
             }
 
            // Keep video brightness dominant; audio supplies only a controlled
            // lift so the lights remain responsive without flashing.
             val audioValue = LightingSettings.audioBrightnessValue(
                low,
                mid,
                high,
                 flash
             )

            val value = (
                screenValue * 0.84f +
                    audioValue * 0.16f
                ).coerceIn(0.06f, 1f)

            val beatValue = (
                value * (1f + beat * 0.16f)
            ).coerceIn(0f, 1f)

            hsvToRgb(hue, saturation, beatValue)

            // Existing smoothing remains in place to avoid dizziness/strobing.
            smoothRgb(
                dst,
                hsvOut[0],
                hsvOut[1],
                hsvOut[2],
                out
             )
        }
    }

    private fun mapColors(
        count: Int,
        band0: Float,
        band1: Float,
        band2: Float,
        band3: Float,
        band4: Float,
        band5: Float,
        loudness: Float,
        flux: Float,
        flash: Float,
        out: FloatArray,
    ) {
        if (count <= 0) return
        if (smoothedRgb.size != count * 3) smoothedRgb = FloatArray(count * 3)

        val bands = floatArrayOf(band0, band1, band2, band3, band4, band5)
        val hue = spectralHue(bands)
        val energy = (bands.sum() / bands.size).coerceIn(0f, 1f)
        val value = LightingSettings.audioBrightnessValue(
            band0 + band1,
            band2 + band3,
            band4 + band5,
            flash
        )
        for (i in 0 until count) {
            val phase = ZONE_HUE_PHASES[i % ZONE_HUE_PHASES.size]
            val alternating = if ((i and 1) == 0) flux * 18f else -flux * 14f
            hsvToRgb(
                normalizeHue(hue + phase * (0.55f + energy * 0.45f) + alternating),
                (0.62f + energy * 0.30f + flux * 0.18f).coerceIn(0f, 1f),
                value.coerceIn(0f, 1f)
            )
            smoothRgb(i * 3, hsvOut[0], hsvOut[1], hsvOut[2], out)
        }
    }

    private fun smoothRgb(dst: Int, r: Float, g: Float, b: Float, out: FloatArray) {
        smoothedRgb[dst] += COLOR_SMOOTH * (r.coerceIn(0f, 1f) - smoothedRgb[dst])
        smoothedRgb[dst + 1] += COLOR_SMOOTH * (g.coerceIn(0f, 1f) - smoothedRgb[dst + 1])
        smoothedRgb[dst + 2] += COLOR_SMOOTH * (b.coerceIn(0f, 1f) - smoothedRgb[dst + 2])
        out[dst] = smoothedRgb[dst]
        out[dst + 1] = smoothedRgb[dst + 1]
        out[dst + 2] = smoothedRgb[dst + 2]
    }

    /**
     * Circularly blends six spectral bands into a hue. The palette deliberately
     * spans warm, green/cyan and blue/magenta regions, so the soundtrack can
     * escape the old red->blue-only tonal range.
     */
    private fun spectralHue(bands: FloatArray): Float {
        val hues = floatArrayOf(8f, 42f, 105f, 180f, 245f, 315f)
        var x = 0.0
        var y = 0.0
        for (i in bands.indices) {
            val radians = Math.toRadians(hues[i].toDouble())
            val w = bands[i].coerceAtLeast(0f).toDouble()
            x += kotlin.math.cos(radians) * w
            y += kotlin.math.sin(radians) * w
        }
        if (x * x + y * y < 1e-8) return 240f
        var h = Math.toDegrees(kotlin.math.atan2(y, x)).toFloat()
        if (h < 0f) h += 360f
        return h
    }

    // Reused HSV->RGB scratch (written on the sender thread only). h in degrees,
    // s/v in 0..1. Result lands in [hsvOut].
    private val hsvOut = FloatArray(3)
    private val hsvIn = FloatArray(3)

    private fun rgbToHsv(r: Float, g: Float, b: Float) {
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        var h = 0f
        if (d > 1e-5f) {
            h = when (max) {
                r -> 60f * (((g - b) / d) % 6f)
                g -> 60f * (((b - r) / d) + 2f)
                else -> 60f * (((r - g) / d) + 4f)
            }; if (h < 0f) h += 360f
        }
        hsvIn[0] = h; hsvIn[1] = if (max <= 1e-5f) 0f else d / max; hsvIn[2] = max
    }
    private fun normalizeHue(h: Float): Float = ((h % 360f) + 360f) % 360f
    private fun shortestHueDelta(from: Float, to: Float): Float {
        var d = normalizeHue(to) - normalizeHue(from)
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }
    private fun hsvToRgb(h: Float, s: Float, v: Float) {
        val hh = (((h % 360f) + 360f) % 360f) / 60f
        val c = v * s
        val x = c * (1f - abs(hh % 2f - 1f))
        val m = v - c
        var r = 0f; var g = 0f; var b = 0f
        when (hh.toInt()) {
            0 -> { r = c; g = x }
            1 -> { r = x; g = c }
            2 -> { g = c; b = x }
            3 -> { g = x; b = c }
            4 -> { r = x; b = c }
            else -> { r = c; b = x }
        }
        hsvOut[0] = r + m; hsvOut[1] = g + m; hsvOut[2] = b + m
    }

    companion object {
        private const val TAG = "HueLightController"
        private const val SEND_HZ = 50L          // Hue Entertainment caps ~50–60 Hz
        private const val SPIN_MARGIN_NS = 2_000_000L  // spin-wait the last 2ms for precise timing
        private const val FLASH_DECAY = 0.995f

        // Brightness floor, beat-flash amplitude and the resting glow are now shared
        // across all brands in LightingSettings (so presets behave identically).

        // Colour endpoints: little bass (breakdown) => light red, enough bass =>
        // blue/purple, blended through pink/magenta over the bass ratio.
        private const val RED_HUE = 360f          // little-bass colour (light red)
        private const val PURPLE_HUE = 265f       // bass-heavy colour (blue/purple)
        private const val SAT_BASS = 1.0f         // vivid blue/purple
        private const val SAT_TREBLE = 0.70f      // lower sat => lighter red

        // Audio-reactive light show (Link off): a continuous, saturated sweep
        // driven by the spectral balance. Stays on the warm→cool club side of the
        // wheel (red → magenta/purple → blue), skipping the murky greens/yellows.
        private const val AUDIO_HUE_BASS = 360f     // bass-heavy => red
        private const val AUDIO_HUE_TREBLE = 220f   // treble-heavy => blue
        private const val AUDIO_SAT = 0.92f
        private const val COLOR_SMOOTH = 0.065f     // ~0.31 s response at 50 Hz
        private const val MAX_BEAT_PULSE = 0.16f

        // Physical-light order around a TV-like arrangement: top row, right side,
        // bottom row, left side, then centre cells. Each light therefore samples
        // a genuinely different part of the complete frame.
        private val SCREEN_ZONE_ORDER = intArrayOf(0, 1, 2, 3, 7, 11, 10, 9, 8, 4, 5, 6)
        private val ZONE_HUE_PHASES = floatArrayOf(-22f, 14f, -12f, 24f, -18f, 18f, -28f, 12f, -15f, 20f, -10f, 16f)
    }
}
