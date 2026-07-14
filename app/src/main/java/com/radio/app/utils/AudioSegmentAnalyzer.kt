package com.radio.app.utils

import android.content.Context
import android.util.Log
import com.radio.app.models.VoiceSegment
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/**
 * v2.4.95: Audio-based AI segment analyzer using dual models.
 *
 * Silero VAD (ONNX, ~2.3MB): High-precision voice activity detection
 * YAMNet (TFLite, ~4.1MB): Audio classification (521 categories: Speech, Music, Silence, etc.)
 *
 * Processing:
 * 1. Read 16kHz mono PCM data
 * 2. Slide window (YAMNet: 0.975s, Silero VAD: 0.5s step)
 * 3. For each window:
 *    - YAMNet: classify into Speech/Music/Silence probabilities
 *    - Silero VAD: get speech probability
 * 4. Fuse results: Speech+VAD → 干货, Music → 水货, Silence → boundary
 * 5. Merge consecutive same-type frames into segments
 *
 * Requires runtime libraries (downloaded from offline engine management):
 * - libonnxruntime.so, libonnxruntime4j_jni.so (for Silero VAD)
 * - libtensorflowlite_jni.so (for YAMNet)
 * - silero_vad.onnx (model file)
 * - yamnet.tflite (model file)
 */
object AudioSegmentAnalyzer {
    private const val TAG = "AudioSegmentAnalyzer"

    // YAMNet: 16kHz, 0.975s window = 15600 samples
    private const val YAMNET_SAMPLE_RATE = 16000
    private const val YAMNET_WINDOW_SAMPLES = 15600
    private const val YAMNET_NUM_CLASSES = 521

    // YAMNet class indices (from AudioSet ontology)
    private const val YAMNET_IDX_SPEECH = 0       // Speech
    private const val YAMNET_IDX_SILENCE = 78     // Silence
    private const val YAMNET_IDX_MUSIC = 137      // Music
    private const val YAMNET_IDX_SONG = 138       // Singing

    // Frame step: 0.5s (8000 samples at 16kHz)
    private const val FRAME_STEP_SAMPLES = 8000

    // Silero VAD: 512 samples per chunk
    private const val VAD_FRAME_SIZE = 512
    private const val VAD_DRY_THRESHOLD = 0.45f
    private const val VAD_WATER_THRESHOLD = 0.15f

    // Energy thresholds (relative to max energy)
    private const val ENERGY_SILENCE_RATIO = 0.05f
    private const val ENERGY_MUSIC_RATIO = 0.3f

    // Classification results
    private enum class FrameType { DRY, WATER, SILENCE }

    /**
     * Check if YAMNet model file exists.
     */
    fun isYamnetInstalled(modelDir: File): Boolean {
        val f = File(modelDir, "yamnet.tflite")
        return f.exists() && f.length() > 1_000_000
    }

    /**
     * Check if Silero VAD model file exists.
     */
    fun isSileroVadInstalled(modelDir: File): Boolean {
        val f = File(modelDir, "silero_vad.onnx")
        return f.exists() && f.length() > 50_000
    }

    /**
     * Check if native libraries are downloaded.
     */
    fun areNativeLibsDownloaded(modelDir: File): Boolean {
        return NativeLibLoader.areLibsDownloaded(modelDir)
    }

    /**
     * Check if all required models are installed.
     * v2.4.95: Requires both YAMNet and Silero VAD + native libs.
     */
    fun isModelInstalled(modelDir: File): Boolean {
        return isYamnetInstalled(modelDir) && isSileroVadInstalled(modelDir) && areNativeLibsDownloaded(modelDir)
    }

    /**
     * Get the model directory.
     * v2.4.95: Uses same path as OfflineEngineActivity (models/audio-models).
     */
    fun getModelDir(context: Context): File {
        val modelsDir = context.getExternalFilesDir("models") ?: context.getExternalFilesDir(null)
        val modelDir = File(modelsDir, "audio-models")
        if (!modelDir.exists()) modelDir.mkdirs()
        // v2.4.95: Migrate from old path if needed
        val oldDir = File(context.getExternalFilesDir(null), "audio-models")
        if (oldDir.exists() && oldDir.listFiles()?.isNotEmpty() == true && modelDir.listFiles()?.isEmpty() == true) {
            oldDir.copyRecursively(modelDir, overwrite = true)
            Log.i(TAG, "Migrated audio models from ${oldDir.absolutePath} to ${modelDir.absolutePath}")
        }
        return modelDir
    }

    /**
     * v2.4.96: Analyze an episode by finding its PCM file and running dual-model segmentation.
     * v2.4.99: Added audioUrl parameter for finding cached audio file.
     *
     * PCM file search order:
     * 1. Pre-decoded full PCM: /sdcard/RadioApp/pcm_cache/{episodeId}_full.pcm
     * 2. Pre-decoded 5-min PCM: /sdcard/RadioApp/pcm_cache/{episodeId}_5min.pcm
     * 3. Whisper chunk PCM: /sdcard/RadioApp/pcm_cache/{episodeId}_chunk_*.pcm
     * 4. Decode from cached audio file (mp4/m4a) to PCM on-the-fly
     *
     * @param context Application context
     * @param episodeId Episode ID
     * @param durationMs Duration in milliseconds
     * @param audioUrl Audio URL (for finding cached audio file)
     * @return List of VoiceSegments
     */
    fun analyzeEpisode(context: Context, episodeId: String, durationMs: Long, audioUrl: String? = null): List<VoiceSegment> {
        // v2.4.95: Load native libraries before any ONNX/TFLite usage
        if (!NativeLibLoader.ensureLoaded(context)) {
            Log.e(TAG, "Native libraries not loaded. Please download audio segmentation runtime.")
            throw RuntimeException("音频分段运行库未安装，请在离线引擎管理中下载")
        }

        val modelDir = getModelDir(context)
        if (!isModelInstalled(modelDir)) {
            Log.e(TAG, "Models not installed. YAMNet=${isYamnetInstalled(modelDir)}, VAD=${isSileroVadInstalled(modelDir)}")
            throw RuntimeException("音频分段模型未安装，请在离线引擎管理中下载 Silero VAD 和 YAMNet")
        }

        // v2.4.96: Find PCM file - prioritize pre-decoded files from preprocessing
        val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(context)
        Log.i(TAG, "analyzeEpisode: searching for PCM in ${pcmCacheDir.absolutePath}")

        // 1. Full PCM (from subtitle preprocessing)
        var pcmFile: File? = File(pcmCacheDir, "${episodeId}_full.pcm")
        if (pcmFile!!.exists() && pcmFile.length() > 16000) {
            Log.i(TAG, "analyzeEpisode: found full PCM: ${pcmFile.name} (${pcmFile.length()} bytes)")
        } else {
            // 2. 5-min PCM (from PCM pre-decode)
            pcmFile = File(pcmCacheDir, "${episodeId}_5min.pcm")
            if (pcmFile!!.exists() && pcmFile.length() > 16000) {
                Log.i(TAG, "analyzeEpisode: found 5min PCM: ${pcmFile.name} (${pcmFile.length()} bytes)")
            } else {
                // 3. Plain PCM (without suffix)
                pcmFile = File(pcmCacheDir, "${episodeId}.pcm")
                if (pcmFile!!.exists() && pcmFile.length() > 16000) {
                    Log.i(TAG, "analyzeEpisode: found plain PCM: ${pcmFile.name} (${pcmFile.length()} bytes)")
                } else {
                    // 4. Decode from cached audio file
                    pcmFile = decodeAudioToPcm(context, episodeId, pcmCacheDir, audioUrl)
                    if (pcmFile == null) {
                        Log.e(TAG, "analyzeEpisode: no PCM file found for $episodeId (audioUrl=$audioUrl)")
                        throw RuntimeException("无法获取音频数据: 未找到PCM缓存文件，本地无缓存音频，URL解码失败(可能需要联网)")
                    }
                }
            }
        }

        return analyzePcmFile(context, pcmFile!!, durationMs)
    }

    /**
     * v2.4.96: Decode cached audio file to PCM using MediaExtractor + MediaCodec.
     * v2.4.99: Find audio file by URL-based filename (not episode ID).
     * This is a fallback when no pre-decoded PCM file exists.
     */
    private fun decodeAudioToPcm(context: Context, episodeId: String, outputDir: File, audioUrl: String? = null): File? {
        try {
            val episodesDir = com.radio.app.RadioApplication.getEpisodesCacheDir(context)
            val cachedFiles = episodesDir.listFiles()?.filter {
                it.isFile && it.length() > 1024 && (it.name.endsWith(".mp4") || it.name.endsWith(".m4a") || it.name.endsWith(".aac"))
            } ?: emptyList()

            // v2.4.99: Find audio file by URL-based filename first
            var audioFile: File? = null
            if (audioUrl != null) {
                val urlFileName = try {
                    val path = java.net.URL(audioUrl).path
                    path.substringAfterLast("/")
                } catch (e: Exception) {
                    audioUrl.substringAfterLast("/")
                }
                if (urlFileName.isNotBlank()) {
                    audioFile = cachedFiles.find { it.name == urlFileName || it.name.startsWith(urlFileName.substringBeforeLast(".")) }
                    Log.i(TAG, "decodeAudioToPcm: searching by URL filename '$urlFileName', found=${audioFile?.name}")
                }
            }

            // Fallback: search by episode ID prefix
            if (audioFile == null) {
                audioFile = cachedFiles.find {
                    it.name.startsWith(episodeId) || it.name.startsWith(episodeId.substringBefore("-"))
                }
                Log.i(TAG, "decodeAudioToPcm: searching by episodeId '$episodeId', found=${audioFile?.name}")
            }

            // Last resort: use the most recently modified audio file
            if (audioFile == null && cachedFiles.isNotEmpty()) {
                audioFile = cachedFiles.maxByOrNull { it.lastModified() }
                Log.i(TAG, "decodeAudioToPcm: using most recent audio file: ${audioFile?.name}")
            }

            if (audioFile == null) {
                // v2.4.101: No cached file — try streaming from URL directly via MediaExtractor
                if (audioUrl != null && audioUrl.startsWith("http")) {
                    Log.i(TAG, "decodeAudioToPcm: no cached file, trying URL: $audioUrl")
                    return decodeUrlToPcm(audioUrl, File(outputDir, "${episodeId}_full.pcm"))
                }
                Log.e(TAG, "decodeAudioToPcm: no cached audio file found for $episodeId, files in episodes dir: ${cachedFiles.map { it.name }}")
                return null
            }
            Log.i(TAG, "decodeAudioToPcm: decoding ${audioFile.name} to PCM")

            val outputFile = File(outputDir, "${episodeId}_full.pcm")
            // Use Android MediaExtractor + MediaCodec to decode
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(audioFile.absolutePath)
            val trackCount = extractor.trackCount
            var audioTrackIndex = -1
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    break
                }
            }
            if (audioTrackIndex < 0) {
                Log.e(TAG, "decodeAudioToPcm: no audio track found")
                extractor.release()
                return null
            }
            extractor.selectTrack(audioTrackIndex)
            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val sampleRate = inputFormat.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = inputFormat.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            val mime = inputFormat.getString(android.media.MediaFormat.KEY_MIME) ?: ""

            // Create decoder
            val decoder = android.media.MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val bufferInfo = android.media.MediaCodec.BufferInfo()
            val outputDirFile = outputFile
            var totalPcmBytes = 0
            val maxPcmBytes = 50 * 1024 * 1024  // 50MB max (~26 min at 16kHz mono)

            // Resample to 16kHz mono if needed
            val needResample = sampleRate != 16000 || channelCount != 1
            val fos = java.io.FileOutputStream(outputDirFile)

            try {
                while (true) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }

                    val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)

                            if (needResample) {
                                // Simple downsample: take every Nth sample + mix channels
                                val pcmShort = java.nio.ByteBuffer.wrap(chunk).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val ratio = sampleRate / 16000
                                val outSamples = pcmShort.remaining() / channelCount / ratio
                                val outBuf = java.nio.ByteBuffer.allocate(outSamples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                var i = 0
                                while (i + channelCount <= pcmShort.remaining()) {
                                    if (i % (ratio * channelCount) == 0) {
                                        var sum = 0
                                        for (c in 0 until channelCount) {
                                            sum += pcmShort.get(i + c)
                                        }
                                        outBuf.putShort((sum / channelCount).toShort())
                                    }
                                    i += channelCount
                                }
                                val outBytes = ByteArray(outBuf.position())
                                outBuf.rewind()
                                outBuf.get(outBytes)
                                fos.write(outBytes)
                                totalPcmBytes += outBytes.size
                            } else {
                                fos.write(chunk)
                                totalPcmBytes += chunk.size
                            }

                            if (totalPcmBytes >= maxPcmBytes) {
                                Log.i(TAG, "decodeAudioToPcm: reached max size limit ($maxPcmBytes bytes)")
                                break
                            }
                        }
                        decoder.releaseOutputBuffer(outputBufferIndex, false)
                        if (bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    }
                }
            } finally {
                fos.close()
                decoder.stop()
                decoder.release()
                extractor.release()
            }

            Log.i(TAG, "decodeAudioToPcm: decoded $totalPcmBytes bytes to ${outputFile.name}")
            return if (totalPcmBytes > 16000) outputFile else null
        } catch (e: Exception) {
            Log.e(TAG, "decodeAudioToPcm failed: ${e.message}")
            return null
        }
    }

    /**
     * v2.4.101: Download and decode audio from URL directly using MediaExtractor.
     * MediaExtractor supports HTTP URLs natively. Decodes to 16kHz mono PCM.
     */
    private fun decodeUrlToPcm(audioUrl: String, outputFile: File): File? {
        try {
            Log.i(TAG, "decodeUrlToPcm: downloading and decoding from $audioUrl")
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(audioUrl)
            val trackCount = extractor.trackCount
            var audioTrackIndex = -1
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    break
                }
            }
            if (audioTrackIndex < 0) {
                Log.e(TAG, "decodeUrlToPcm: no audio track found")
                extractor.release()
                return null
            }
            extractor.selectTrack(audioTrackIndex)
            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val sampleRate = inputFormat.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = inputFormat.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            val mime = inputFormat.getString(android.media.MediaFormat.KEY_MIME) ?: ""

            val decoder = android.media.MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val bufferInfo = android.media.MediaCodec.BufferInfo()
            var totalPcmBytes = 0
            val maxPcmBytes = 50 * 1024 * 1024  // 50MB max (~26 min at 16kHz mono)
            val needResample = sampleRate != 16000 || channelCount != 1
            val fos = java.io.FileOutputStream(outputFile)

            try {
                while (true) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }

                    val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)

                            if (needResample) {
                                val pcmShort = java.nio.ByteBuffer.wrap(chunk).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val ratio = sampleRate / 16000
                                val outSamples = pcmShort.remaining() / channelCount / ratio
                                val outBuf = java.nio.ByteBuffer.allocate(outSamples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                var i = 0
                                while (i + channelCount <= pcmShort.remaining()) {
                                    if (i % (ratio * channelCount) == 0) {
                                        var sum = 0
                                        for (c in 0 until channelCount) {
                                            sum += pcmShort.get(i + c)
                                        }
                                        outBuf.putShort((sum / channelCount).toShort())
                                    }
                                    i += channelCount
                                }
                                val outBytes = ByteArray(outBuf.position())
                                outBuf.rewind()
                                outBuf.get(outBytes)
                                fos.write(outBytes)
                                totalPcmBytes += outBytes.size
                            } else {
                                fos.write(chunk)
                                totalPcmBytes += chunk.size
                            }

                            if (totalPcmBytes >= maxPcmBytes) {
                                Log.i(TAG, "decodeUrlToPcm: reached max size limit ($maxPcmBytes bytes)")
                                break
                            }
                        }
                        decoder.releaseOutputBuffer(outputBufferIndex, false)
                        if (bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    }
                }
            } finally {
                fos.close()
                decoder.stop()
                decoder.release()
                extractor.release()
            }

            Log.i(TAG, "decodeUrlToPcm: decoded $totalPcmBytes bytes to ${outputFile.name}")
            return if (totalPcmBytes > 16000) outputFile else null
        } catch (e: Exception) {
            Log.e(TAG, "decodeUrlToPcm failed: ${e.message}")
            return null
        }
    }

    /**
     * Analyze PCM audio file and generate segments using dual models.
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
            throw RuntimeException("PCM文件太小或不存在: ${pcmFile.name} (${pcmFile.length()} bytes)")
        }

        // v2.4.95: Load native libraries before any ONNX/TFLite usage
        if (!NativeLibLoader.ensureLoaded(context)) {
            Log.e(TAG, "Native libraries not loaded.")
            throw RuntimeException("音频分段运行库未加载，请重新下载运行库")
        }

        val modelDir = getModelDir(context)
        if (!isYamnetInstalled(modelDir) || !isSileroVadInstalled(modelDir)) {
            Log.w(TAG, "Models not installed. YAMNet=${isYamnetInstalled(modelDir)}, VAD=${isSileroVadInstalled(modelDir)}")
            throw RuntimeException("模型未安装: YAMNet=${isYamnetInstalled(modelDir)}, VAD=${isSileroVadInstalled(modelDir)}")
        }

        // Load YAMNet (TFLite) - throws exception on failure
        val yamnetInterpreter = loadYamnetModel(File(modelDir, "yamnet.tflite"))

        // Load Silero VAD (ONNX Runtime via reflection) - throws exception on failure
        val vadSession = loadSileroVad(File(modelDir, "silero_vad.onnx"))

        try {
            val samples = readPcmAsFloats(pcmFile)
            if (samples.size < YAMNET_WINDOW_SAMPLES) {
                Log.w(TAG, "PCM too short: ${samples.size} samples")
                throw RuntimeException("PCM数据太短: ${samples.size} 样本 (需要至少 $YAMNET_WINDOW_SAMPLES)")
            }

            // Compute max energy for normalization
            var maxEnergy = 0f
            var pos = 0
            while (pos + FRAME_STEP_SAMPLES <= samples.size) {
                val winSize = minOf(YAMNET_WINDOW_SAMPLES, samples.size - pos)
                val energy = computeRmsEnergy(samples, pos, winSize)
                if (energy > maxEnergy) maxEnergy = energy
                pos += FRAME_STEP_SAMPLES
            }
            if (maxEnergy < 1e-6f) maxEnergy = 1e-6f
            Log.i(TAG, "PCM: ${samples.size} samples, maxEnergy=$maxEnergy")

            val frameResults = mutableListOf<FrameResult>()
            pos = 0

            // VAD state buffer
            var vadStateH = FloatBuffer.wrap(FloatArray(64))
            var vadStateC = FloatBuffer.wrap(FloatArray(64))

            while (pos + YAMNET_WINDOW_SAMPLES <= samples.size) {
                val window = samples.copyOfRange(pos, pos + YAMNET_WINDOW_SAMPLES)
                val timestampMs = (pos.toLong() * 1000 / YAMNET_SAMPLE_RATE)

                // YAMNet classification
                val (yamnetSpeech, yamnetMusic, yamnetSilence) = classifyWithYamnet(yamnetInterpreter, window)

                // Silero VAD
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

                // Energy features (supplementary)
                val rmsEnergy = computeRmsEnergy(window, 0, window.size)
                val zcr = computeZeroCrossingRate(window)

                // Fuse: dual-model classification
                val type = classifyFrameDualModel(
                    yamnetSpeech, yamnetMusic, yamnetSilence, vadProb, rmsEnergy, zcr, maxEnergy
                )
                frameResults.add(FrameResult(timestampMs, type, vadProb, yamnetSpeech, yamnetMusic, rmsEnergy))

                pos += FRAME_STEP_SAMPLES
            }

            Log.i(TAG, "Analyzed ${frameResults.size} frames")
            val segments = mergeFramesIntoSegments(frameResults, durationMs)
            Log.i(TAG, "Generated ${segments.size} segments (dry=${segments.count { it.label == "干货" }}, water=${segments.count { it.label == "水货" }})")
            return segments

        } finally {
            yamnetInterpreter.close()
            try { vadSession.close() } catch (_: Exception) {}
        }
    }

    // ===== YAMNet (TFLite) =====

    private fun loadYamnetModel(modelFile: File): Interpreter {
        try {
            val mappedBuffer = FileInputStream(modelFile).channel.map(
                FileChannel.MapMode.READ_ONLY, 0, modelFile.length()
            )
            val options = Interpreter.Options()
            options.setNumThreads(2)
            val interp = Interpreter(mappedBuffer, options)
            Log.i(TAG, "YAMNet loaded: input=${interp.getInputTensor(0).shape().contentToString()}, output=${interp.getOutputTensor(0).shape().contentToString()}")
            return interp
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load YAMNet TFLite model: ${e.message}")
            throw RuntimeException("YAMNet模型加载失败: ${e.message}", e)
        }
    }

    private fun classifyWithYamnet(
        interpreter: Interpreter,
        samples: FloatArray
    ): Triple<Float, Float, Float> {
        try {
            // YAMNet input: [1, 15600] float
            val inputBuffer = TensorBuffer.createFixedSize(
                intArrayOf(1, YAMNET_WINDOW_SAMPLES),
                org.tensorflow.lite.DataType.FLOAT32
            )
            inputBuffer.loadArray(samples)

            // Output: [1, 521] float
            val outputBuffer = TensorBuffer.createFixedSize(
                intArrayOf(1, YAMNET_NUM_CLASSES),
                org.tensorflow.lite.DataType.FLOAT32
            )

            interpreter.run(inputBuffer.buffer, outputBuffer.buffer)
            val scores = outputBuffer.floatArray

            // Extract key categories (apply sigmoid to raw scores)
            val speechProb = sigmoid(scores.getOrElse(YAMNET_IDX_SPEECH) { 0f })
            val silenceProb = sigmoid(scores.getOrElse(YAMNET_IDX_SILENCE) { 0f })
            val musicProb = maxOf(
                sigmoid(scores.getOrElse(YAMNET_IDX_MUSIC) { 0f }),
                sigmoid(scores.getOrElse(YAMNET_IDX_SONG) { 0f })
            )
            return Triple(speechProb, musicProb, silenceProb)
        } catch (e: Exception) {
            Log.e(TAG, "YAMNet classification failed: ${e.message}")
            return Triple(0f, 0f, 0f)
        }
    }

    private fun sigmoid(x: Float): Float {
        val exp = kotlin.math.exp(-x.toDouble())
        return (1.0 / (1.0 + exp)).toFloat()
    }

    // ===== Silero VAD (ONNX Runtime via reflection) =====

    private fun loadSileroVad(modelFile: File): AiSession {
        try {
            val envClass = Class.forName("ai.onnxruntime.OrtEnvironment")
            val env = envClass.getMethod("getEnvironment").invoke(null)
            val sessionOptionsClass = Class.forName("ai.onnxruntime.SessionOptions")
            val sessionOptions = sessionOptionsClass.getConstructor().newInstance()
            val ortSessionClass = Class.forName("ai.onnxruntime.OrtSession")
            val session = ortSessionClass
                .getConstructor(envClass, String::class.java, sessionOptionsClass)
                .newInstance(env, modelFile.absolutePath, sessionOptions)
            return AiSession(session)
        } catch (e: Exception) {
            Log.e(TAG, "ONNX Runtime not available: ${e.message}")
            throw RuntimeException("Silero VAD模型加载失败(ONNX Runtime): ${e.message}", e)
        }
    }

    private fun runSileroVad(
        session: AiSession,
        chunk: FloatArray,
        stateH: FloatBuffer,
        stateC: FloatBuffer
    ): Triple<Float, FloatBuffer, FloatBuffer> {

        try {
            val sessionObj = session.session
            val sessionClass = sessionObj.javaClass
            val envClass = Class.forName("ai.onnxruntime.OrtEnvironment")
            val env = envClass.getMethod("getEnvironment").invoke(null)
            val onnxTensorClass = Class.forName("ai.onnxruntime.OnnxTensor")

            val inputMap = HashMap<String, Any>()
            val inputData = Array(1) { chunk }
            val inputTensor = onnxTensorClass
                .getMethod("createTensor", envClass, FloatArray::class.java, LongArray::class.java)
                .invoke(null, env, inputData, longArrayOf(1, chunk.size.toLong()))
            inputMap["input"] = inputTensor!!

            val hData = stateH.array()
            val hTensor = onnxTensorClass
                .getMethod("createTensor", envClass, FloatArray::class.java, LongArray::class.java)
                .invoke(null, env, hData, longArrayOf(2, 1, 32))
            inputMap["h"] = hTensor!!

            val cData = stateC.array()
            val cTensor = onnxTensorClass
                .getMethod("createTensor", envClass, FloatArray::class.java, LongArray::class.java)
                .invoke(null, env, cData, longArrayOf(2, 1, 32))
            inputMap["c"] = cTensor!!

            val runMethod = sessionClass.getMethod("run", Map::class.java)
            val results = runMethod.invoke(sessionObj, inputMap)

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

    // ===== Feature computation =====

    private fun readPcmAsFloats(pcmFile: File): FloatArray {
        val bytes = pcmFile.readBytes()
        val samples = FloatArray(bytes.size / 2)
        val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in samples.indices) {
            samples[i] = byteBuffer.short.toFloat() / 32768.0f
        }
        return samples
    }

    private fun computeRmsEnergy(samples: FloatArray, offset: Int, length: Int): Float {
        var sumSquares = 0.0
        for (i in offset until offset + length) {
            if (i < samples.size) sumSquares += samples[i].toDouble() * samples[i]
        }
        return kotlin.math.sqrt(sumSquares / length).toFloat()
    }

    private fun computeZeroCrossingRate(samples: FloatArray): Float {
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] >= 0) != (samples[i - 1] >= 0)) crossings++
        }
        return crossings.toFloat() / (samples.size - 1)
    }

    // ===== Dual-model classification =====

    /**
     * Classify a frame using both YAMNet and Silero VAD outputs.
     *
     * Fusion rules:
     * 1. YAMNet Silence + low VAD → SILENCE (boundary)
     * 2. YAMNet Music high + VAD low → WATER (music/水货)
     * 3. YAMNet Speech high + VAD high → DRY (干货)
     * 4. Disagreement: trust VAD for speech, YAMNet for music
     */
    private fun classifyFrameDualModel(
        yamnetSpeech: Float,
        yamnetMusic: Float,
        yamnetSilence: Float,
        vadProb: Float,
        rmsEnergy: Float,
        zcr: Float,
        maxEnergy: Float
    ): FrameType {
        val energyRatio = rmsEnergy / maxEnergy

        // 1. Silence: both models agree on low energy
        if (energyRatio < ENERGY_SILENCE_RATIO && vadProb < VAD_WATER_THRESHOLD) {
            return FrameType.SILENCE
        }
        if (yamnetSilence > 0.6f && vadProb < 0.2f) {
            return FrameType.SILENCE
        }

        // 2. Music: YAMNet says music, VAD says no speech
        if (yamnetMusic > 0.5f && vadProb < VAD_DRY_THRESHOLD) {
            return FrameType.WATER
        }

        // 3. Speech: YAMNet says speech, VAD confirms
        if (yamnetSpeech > 0.3f && vadProb > VAD_DRY_THRESHOLD) {
            return FrameType.DRY
        }

        // 4. VAD says speech but YAMNet doesn't detect music → trust VAD
        if (vadProb > VAD_DRY_THRESHOLD && yamnetMusic < 0.3f) {
            return FrameType.DRY
        }

        // 5. YAMNet says music but VAD is moderate → trust YAMNet for music
        if (yamnetMusic > 0.4f) {
            return FrameType.WATER
        }

        // 6. Low VAD + high energy → water (non-speech content)
        if (vadProb < VAD_WATER_THRESHOLD && energyRatio > ENERGY_MUSIC_RATIO) {
            return FrameType.WATER
        }

        // 7. Default: use VAD as tiebreaker
        return if (vadProb > 0.4f) FrameType.DRY else FrameType.WATER
    }

    // ===== Segment merging =====

    private fun mergeFramesIntoSegments(
        frames: List<FrameResult>,
        durationMs: Long
    ): List<VoiceSegment> {
        if (frames.isEmpty()) return emptyList()

        val minSegmentMs = 30_000L
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
                if (merged.isNotEmpty()) merged.last().end = seg.end
            } else {
                merged.add(seg)
            }
        }
        if (merged.isEmpty()) {
            merged.add(VoiceSegment().apply {
                start = 0L; end = durationMs; hasVoice = true; label = "干货"; isSimulated = false
            })
        }
        return merged
    }

    private fun createSegment(start: Long, end: Long, type: FrameType): VoiceSegment {
        return VoiceSegment().apply {
            this.start = start; this.end = end
            this.hasVoice = type == FrameType.DRY
            this.label = when (type) {
                FrameType.DRY -> "干货"; FrameType.WATER -> "水货"; FrameType.SILENCE -> "静音"
            }
            this.isSimulated = false
        }
    }

    // ===== Inner classes =====

    private data class FrameResult(
        val timestampMs: Long,
        val type: FrameType,
        val vadProb: Float,
        val yamnetSpeech: Float,
        val yamnetMusic: Float,
        val rmsEnergy: Float
    )

    private class AiSession(val session: Any) {
        fun close() {
            try { session.javaClass.getMethod("close").invoke(session) } catch (_: Exception) {}
        }
    }
}
