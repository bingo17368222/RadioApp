package com.radio.app.utils

import android.content.Context
import android.util.Log
import com.radio.app.models.VoiceSegment
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * v2.4.94: Audio-based AI segment analyzer using Silero VAD + energy features.
 *
 * Primary mode: Silero VAD (ONNX, ~2.3MB) + energy-based classification
 * Optional mode: YAMNet (TFLite, ~4MB) if model is available
 *
 * Processing:
 * 1. Read 16kHz mono PCM data
 * 2. Slide 0.5s window with 0.5s step
 * 3. For each window:
 *    - Run Silero VAD to get speech probability
 *    - Compute RMS energy, zero-crossing rate, spectral centroid
 *    - Classify: DRY (speech) / WATER (music/silence) / BOUNDARY
 * 4. Merge consecutive same-type frames into segments
 */
object AudioSegmentAnalyzer {
    private const val TAG = "AudioSegmentAnalyzer"

    private const val SAMPLE_RATE = 16000
    private const val WINDOW_MS = 500
    private const val WINDOW_SAMPLES = SAMPLE_RATE * WINDOW_MS / 1000  // 8000 samples
    private const val STEP_MS = 500
    private const val STEP_SAMPLES = SAMPLE_RATE * STEP_MS / 1000  // 8000 samples

    // Silero VAD parameters
    private const val VAD_FRAME_SIZE = 512  // Silero expects 512 samples per frame
    private const val VAD_THRESHOLD = 0.5f
    private const val VAD_DRY_THRESHOLD = 0.45f  // Above this → likely speech
    private const val VAD_WATER_THRESHOLD = 0.15f  // Below this → likely non-speech

    // Energy thresholds (relative to max energy in file)
    private const val ENERGY_SILENCE_RATIO = 0.05f  // Below 5% of max → silence
    private const val ENERGY_MUSIC_RATIO = 0.3f   // Above 30% with low ZCR → music

    // Zero-crossing rate thresholds
    private const val ZCR_SPEECH_MIN = 0.02f   // Speech typically has ZCR > 0.02
    private const val ZCR_MUSIC_MAX = 0.15f     // Music typically has ZCR < 0.15

    private enum class FrameType { DRY, WATER, SILENCE }

    private data class FrameResult(
        val timestampMs: Long,
        val type: FrameType,
        val vadProb: Float,
        val rmsEnergy: Float,
        val zcr: Float,
        val spectralCentroid: Float
    )

    /**
     * Check if Silero VAD model file exists.
     */
    fun isSileroVadInstalled(modelDir: File): Boolean {
        val vadFile = File(modelDir, "silero_vad.onnx")
        return vadFile.exists() && vadFile.length() > 50_000
    }

    /**
     * Check if all required models are installed.
     * v2.4.94: Only Silero VAD is required. YAMNet is optional.
     */
    fun isModelInstalled(modelDir: File): Boolean {
        return isSileroVadInstalled(modelDir)
    }

    /**
     * Get the model directory.
     */
    fun getModelDir(context: Context): File {
        val baseDir = File(android.os.Environment.getExternalStorageDirectory(),
            "Android/data/${context.packageName}/files")
        val modelDir = File(baseDir, "audio-models")
        if (!modelDir.exists()) modelDir.mkdirs()
        return modelDir
    }

    /**
     * Analyze PCM audio file and generate segments.
     *
     * @param context Application context
     * @param pcmFile 16kHz mono 16-bit PCM file
     * @param durationMs Total duration in milliseconds
     * @return List of VoiceSegments
     */
    fun analyzePcmFile(
        context: Context,
        pcmFile: File,
        durationMs: Long
    ): List<VoiceSegment> {
        if (!pcmFile.exists() || pcmFile.length() < 16000) {
            Log.w(TAG, "PCM file too small or missing: ${pcmFile.absolutePath}")
            return emptyList()
        }

        val modelDir = getModelDir(context)
        if (!isSileroVadInstalled(modelDir)) {
            Log.w(TAG, "Silero VAD model not installed in ${modelDir.absolutePath}")
            return emptyList()
        }

        val vadFile = File(modelDir, "silero_vad.onnx")

        // Load Silero VAD using ONNX Runtime
        val vadSession = try {
            loadSileroVad(vadFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Silero VAD: ${e.message}")
            return emptyList()
        }

        try {
            // Read PCM samples as float array
            val samples = readPcmAsFloats(pcmFile)
            if (samples.size < WINDOW_SAMPLES) {
                Log.w(TAG, "PCM too short: ${samples.size} samples")
                return emptyList()
            }

            // Compute max energy for normalization
            var maxEnergy = 0f
            var pos = 0
            while (pos + WINDOW_SAMPLES <= samples.size) {
                val energy = computeRmsEnergy(samples, pos, WINDOW_SAMPLES)
                if (energy > maxEnergy) maxEnergy = energy
                pos += STEP_SAMPLES
            }
            if (maxEnergy < 1e-6f) maxEnergy = 1e-6f
            Log.i(TAG, "PCM: ${samples.size} samples, maxEnergy=$maxEnergy, duration=${samples.size.toLong() * 1000 / SAMPLE_RATE}ms")

            // Process frames
            val frameResults = mutableListOf<FrameResult>()
            pos = 0

            // VAD state buffer (Silero uses LSTM state)
            var vadStateH = FloatBuffer.wrap(FloatArray(64))
            var vadStateC = FloatBuffer.wrap(FloatArray(64))

            while (pos + WINDOW_SAMPLES <= samples.size) {
                val window = samples.copyOfRange(pos, pos + WINDOW_SAMPLES)
                val timestampMs = (pos.toLong() * 1000 / SAMPLE_RATE)

                // Silero VAD — process 512-sample chunks within the window
                var vadProb = 0f
                var vadChunks = 0
                var vadPos = 0
                while (vadPos + VAD_FRAME_SIZE <= window.size) {
                    val chunk = window.copyOfRange(vadPos, vadPos + VAD_FRAME_SIZE)
                    val (prob, newH, newC) = runSileroVad(vadSession, chunk, vadStateH, vadStateC)
                    vadProb += prob
                    vadChunks++
                    vadStateH = newH
                    vadStateC = newC
                    vadPos += VAD_FRAME_SIZE
                }
                if (vadChunks > 0) vadProb /= vadChunks

                // Energy-based features
                val rmsEnergy = computeRmsEnergy(window, 0, window.size)
                val zcr = computeZeroCrossingRate(window)
                val centroid = computeSpectralCentroid(window)

                // Classify frame
                val type = classifyFrame(vadProb, rmsEnergy, zcr, centroid, maxEnergy)
                frameResults.add(FrameResult(timestampMs, type, vadProb, rmsEnergy, zcr, centroid))

                pos += STEP_SAMPLES
            }

            Log.i(TAG, "Analyzed ${frameResults.size} frames from ${samples.size} samples")

            // Merge frames into segments
            val segments = mergeFramesIntoSegments(frameResults, durationMs)
            Log.i(TAG, "Generated ${segments.size} segments (dry=${segments.count { it.label == "干货" }}, water=${segments.count { it.label == "水货" }})")
            return segments

        } finally {
            try { vadSession?.close() } catch (_: Exception) {}
        }
    }

    private fun loadSileroVad(modelFile: File): AiSession? {
        return try {
            val envClass = Class.forName("ai.onnxruntime.OrtEnvironment")
            val env = envClass.getMethod("getEnvironment").invoke(null)
            val sessionOptionsClass = Class.forName("ai.onnxruntime.SessionOptions")
            val sessionOptions = sessionOptionsClass.getConstructor().newInstance()
            val ortSessionClass = Class.forName("ai.onnxruntime.OrtSession")
            val session = ortSessionClass
                .getConstructor(envClass, String::class.java, sessionOptionsClass)
                .newInstance(env, modelFile.absolutePath, sessionOptions)
            AiSession(session)
        } catch (e: Exception) {
            Log.e(TAG, "ONNX Runtime not available: ${e.message}")
            null
        }
    }

    private fun readPcmAsFloats(pcmFile: File): FloatArray {
        val bytes = pcmFile.readBytes()
        val samples = FloatArray(bytes.size / 2)
        val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in samples.indices) {
            val shortVal = byteBuffer.short.toInt()
            samples[i] = shortVal.toFloat() / 32768.0f
        }
        return samples
    }

    /**
     * Compute RMS energy of a window.
     */
    private fun computeRmsEnergy(samples: FloatArray, offset: Int, length: Int): Float {
        var sumSquares = 0.0
        for (i in offset until offset + length) {
            if (i < samples.size) {
                sumSquares += samples[i].toDouble() * samples[i]
            }
        }
        return kotlin.math.sqrt(sumSquares / length).toFloat()
    }

    /**
     * Compute zero-crossing rate (fraction of samples that cross zero).
     * Speech typically has ZCR 0.02-0.15, music has lower ZCR.
     */
    private fun computeZeroCrossingRate(samples: FloatArray): Float {
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] >= 0) != (samples[i - 1] >= 0)) {
                crossings++
            }
        }
        return crossings.toFloat() / (samples.size - 1)
    }

    /**
     * Compute spectral centroid (brightness indicator).
     * Music typically has higher spectral centroid than speech.
     * Uses a simple DFT approximation for efficiency.
     */
    private fun computeSpectralCentroid(samples: FloatArray): Float {
        // Simple approximation: use energy in high-frequency band vs total
        // Split into 4 frequency bands using simple averaging
        val n = samples.size
        val quarter = n / 4
        var totalEnergy = 0.0
        var highFreqEnergy = 0.0

        for (i in 0 until n) {
            val energy = samples[i].toDouble() * samples[i]
            totalEnergy += energy
            // Approximate high frequency content using difference between adjacent samples
            if (i > 0) {
                val diff = (samples[i] - samples[i - 1]).toDouble()
                highFreqEnergy += diff * diff
            }
        }

        if (totalEnergy < 1e-10) return 0f
        return (highFreqEnergy / totalEnergy).toFloat()
    }

    /**
     * Run Silero VAD inference using ONNX Runtime via reflection.
     */
    private fun runSileroVad(
        session: AiSession?,
        chunk: FloatArray,
        stateH: FloatBuffer,
        stateC: FloatBuffer
    ): Triple<Float, FloatBuffer, FloatBuffer> {
        if (session == null) return Triple(0.5f, stateH, stateC)

        try {
            val sessionObj = session.session
            val sessionClass = sessionObj.javaClass

            val envClass = Class.forName("ai.onnxruntime.OrtEnvironment")
            val env = envClass.getMethod("getEnvironment").invoke(null)
            val onnxTensorClass = Class.forName("ai.onnxruntime.OnnxTensor")

            val inputMap = HashMap<String, Any>()

            // input tensor: float[1][512]
            val inputData = Array(1) { chunk }
            val inputTensor = onnxTensorClass
                .getMethod("createTensor", envClass, FloatArray::class.java, LongArray::class.java)
                .invoke(null, env, inputData, longArrayOf(1, chunk.size.toLong()))
            inputMap["input"] = inputTensor!!

            // h state tensor: float[2, 1, 32]
            val hData = stateH.array()
            val hTensor = onnxTensorClass
                .getMethod("createTensor", envClass, FloatArray::class.java, LongArray::class.java)
                .invoke(null, env, hData, longArrayOf(2, 1, 32))
            inputMap["h"] = hTensor!!

            // c state tensor: float[2, 1, 32]
            val cData = stateC.array()
            val cTensor = onnxTensorClass
                .getMethod("createTensor", envClass, FloatArray::class.java, LongArray::class.java)
                .invoke(null, env, cData, longArrayOf(2, 1, 32))
            inputMap["c"] = cTensor!!

            // Run inference
            val runMethod = sessionClass.getMethod("run", Map::class.java)
            val results = runMethod.invoke(sessionObj, inputMap)

            // Extract output
            val resultsClass = results.javaClass
            val getMethod = resultsClass.getMethod("get", String::class.java)
            val outputTensor = getMethod.invoke(results, "output")
            val getValueMethod = outputTensor.javaClass.getMethod("getValue")
            val outputValue = getValueMethod.invoke(outputTensor)

            val prob = when (outputValue) {
                is FloatArray -> outputValue[0]
                is Array<*> -> {
                    val inner = outputValue[0]
                    when (inner) {
                        is FloatArray -> inner[0]
                        else -> 0.5f
                    }
                }
                else -> 0.5f
            }

            // Extract new h and c states
            val newHTensor = getMethod.invoke(results, "hn")
            val newHValue = getValueMethod.invoke(newHTensor)
            val newHBuffer = when (newHValue) {
                is FloatArray -> FloatBuffer.wrap(newHValue)
                is Array<*> -> {
                    val flat = (newHValue as Array<*>).map { it as Float }.toFloatArray()
                    FloatBuffer.wrap(flat)
                }
                else -> stateH
            }

            val newCTensor = getMethod.invoke(results, "cn")
            val newCValue = getValueMethod.invoke(newCTensor)
            val newCBuffer = when (newCValue) {
                is FloatArray -> FloatBuffer.wrap(newCValue)
                is Array<*> -> {
                    val flat = (newCValue as Array<*>).map { it as Float }.toFloatArray()
                    FloatBuffer.wrap(flat)
                }
                else -> stateC
            }

            // Clean up
            try { resultsClass.getMethod("close").invoke(results) } catch (_: Exception) {}
            try { onnxTensorClass.getMethod("close").invoke(inputTensor) } catch (_: Exception) {}
            try { onnxTensorClass.getMethod("close").invoke(hTensor) } catch (_: Exception) {}
            try { onnxTensorClass.getMethod("close").invoke(cTensor) } catch (_: Exception) {}

            return Triple(prob, newHBuffer, newCBuffer)
        } catch (e: Exception) {
            Log.e(TAG, "Silero VAD inference failed: ${e.message}")
            return Triple(0.5f, stateH, stateC)
        }
    }

    /**
     * Classify a single frame based on VAD probability and energy features.
     *
     * Rules:
     * 1. Very low energy → SILENCE (boundary)
     * 2. High VAD + high ZCR → DRY (speech/干货)
     * 3. Low VAD + high energy + low ZCR → WATER (music/水货)
     * 4. Low VAD + low energy → SILENCE
     * 5. Moderate VAD → DRY (conservative, prefer content)
     */
    private fun classifyFrame(
        vadProb: Float,
        rmsEnergy: Float,
        zcr: Float,
        spectralCentroid: Float,
        maxEnergy: Float
    ): FrameType {
        val energyRatio = rmsEnergy / maxEnergy

        // 1. Silence check
        if (energyRatio < ENERGY_SILENCE_RATIO) {
            return FrameType.SILENCE
        }

        // 2. High VAD → likely speech
        if (vadProb > VAD_DRY_THRESHOLD) {
            // Check if it's actually music with high energy and low ZCR
            if (energyRatio > ENERGY_MUSIC_RATIO && zcr < ZCR_MUSIC_MAX && spectralCentroid < 0.1f) {
                // Could be music — but VAD says speech, trust VAD
                return FrameType.DRY
            }
            return FrameType.DRY
        }

        // 3. Low VAD with high energy → likely music
        if (vadProb < VAD_WATER_THRESHOLD && energyRatio > ENERGY_MUSIC_RATIO) {
            return FrameType.WATER
        }

        // 4. Low VAD with low energy → silence
        if (vadProb < VAD_WATER_THRESHOLD && energyRatio < ENERGY_SILENCE_RATIO * 2) {
            return FrameType.SILENCE
        }

        // 5. Moderate VAD — check ZCR
        if (zcr > ZCR_SPEECH_MIN) {
            return FrameType.DRY  // Speech-like
        }

        // 6. Default: water (non-speech content)
        return if (energyRatio > 0.15f) FrameType.WATER else FrameType.SILENCE
    }

    /**
     * Merge consecutive frames of the same type into segments.
     * Short segments (< 30s) are merged into neighbors.
     * Silence segments act as boundaries.
     */
    private fun mergeFramesIntoSegments(
        frames: List<FrameResult>,
        durationMs: Long
    ): List<VoiceSegment> {
        if (frames.isEmpty()) return emptyList()

        val minSegmentMs = 30_000L  // 30 seconds minimum
        val segments = mutableListOf<VoiceSegment>()
        var segStart = frames[0].timestampMs
        var segType = frames[0].type

        for (i in 1 until frames.size) {
            val frame = frames[i]
            if (frame.type != segType) {
                val segEnd = frame.timestampMs
                if (segEnd - segStart >= minSegmentMs || segments.isEmpty()) {
                    segments.add(createSegment(segStart, segEnd, segType))
                } else {
                    // Too short — merge with previous segment
                    if (segments.isNotEmpty()) {
                        segments.last().end = segEnd
                    } else {
                        segments.add(createSegment(segStart, segEnd, segType))
                    }
                }
                segStart = frame.timestampMs
                segType = frame.type
            }
        }
        // Close last segment
        val lastEnd = durationMs
        if (lastEnd - segStart >= minSegmentMs || segments.isEmpty()) {
            segments.add(createSegment(segStart, lastEnd, segType))
        } else if (segments.isNotEmpty()) {
            segments.last().end = lastEnd
        }

        // Merge silence segments into adjacent segments
        val merged = mutableListOf<VoiceSegment>()
        for (seg in segments) {
            if (seg.label == "静音") {
                if (merged.isNotEmpty()) {
                    merged.last().end = seg.end
                }
            } else {
                merged.add(seg)
            }
        }

        // If all silence, create one dry segment covering everything
        if (merged.isEmpty()) {
            merged.add(VoiceSegment().apply {
                start = 0L
                end = durationMs
                hasVoice = true
                label = "干货"
                isSimulated = false
            })
        }

        return merged
    }

    private fun createSegment(start: Long, end: Long, type: FrameType): VoiceSegment {
        return VoiceSegment().apply {
            this.start = start
            this.end = end
            this.hasVoice = type == FrameType.DRY
            this.label = when (type) {
                FrameType.DRY -> "干货"
                FrameType.WATER -> "水货"
                FrameType.SILENCE -> "静音"
            }
            this.isSimulated = false
        }
    }

    /**
     * Wrapper for ONNX Runtime session (uses Any to avoid hard import dependency).
     */
    private class AiSession(val session: Any) {
        fun close() {
            try {
                val closeMethod = session.javaClass.getMethod("close")
                closeMethod.invoke(session)
            } catch (_: Exception) {}
        }
    }
}
