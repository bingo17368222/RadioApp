package com.radio.app.fragments

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.radio.app.R
import com.radio.app.activities.DislikedEpisodesActivity
import com.radio.app.activities.KeywordSettingsActivity
import com.radio.app.activities.OfflineEngineActivity
import com.radio.app.databinding.FragmentSettingsBinding
import com.radio.app.models.AppSettings
import com.radio.app.utils.PreferenceManager
import com.radio.app.utils.ThemeManager
import java.io.File

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefManager: PreferenceManager
    private lateinit var settings: AppSettings
    private var previousTheme: String? = null
    private var suppressListeners = true
    // [v2.0.61] Issue 6 Fix: Track Vosk model directory names for spinner label → dir mapping
    private val voskModelDirs = mutableMapOf<String, String>()  // label → directory name
    private var audioTrack: android.media.AudioTrack? = null
    @Volatile private var pcmPlaybackActive = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        prefManager = PreferenceManager(requireContext())
        settings = prefManager.loadSettings()
        previousTheme = settings.uiTheme

        initViews()
        setupListeners()
        updateUI()
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Release any active PCM playback
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        _binding = null
    }

    private fun initViews() {
        val themeLabels = arrayOf("深色", "清新", "经典", "极简", "自定义")
        val themeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, themeLabels)
        themeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerTheme.adapter = themeAdapter

        val sizeLabels = arrayOf("小", "中", "大")
        val sizeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, sizeLabels)
        sizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSubtitleSize.adapter = sizeAdapter

        val langLabels = arrayOf("中文", "英文")
        val langAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, langLabels)
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSubtitleLang.adapter = langAdapter
        binding.spinnerVoiceLang.adapter = langAdapter

        val aiModelLabels = arrayOf("文心一言", "DeepSeek", "通义千问", "FunASR", "Whisper", "就AI听", "阿里MNN-LLM")
        val aiModelAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, aiModelLabels)
        aiModelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerAiModel.adapter = aiModelAdapter

        val asrProviderLabels = mutableListOf("百度语音", "FunASR", "Whisper在线")
        val asrProviderAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, asrProviderLabels)
        asrProviderAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerAsrProvider.adapter = asrProviderAdapter

        try {
            addInstalledEnginesToAsrList(asrProviderAdapter)
        } catch (e: Exception) {
            Log.e("SettingsFragment", "addInstalledEngines failed", e)
        }
    }

    private fun addInstalledEnginesToAsrList(adapter: ArrayAdapter<String>) {
        try {
            val modelsDir = requireContext().getExternalFilesDir("models")
            if (modelsDir == null || !modelsDir.exists()) return

            val whisperModels = arrayOf(
                "whisper-tiny" to "本地Whisper Tiny",
                "whisper-base" to "本地Whisper Base",
                "whisper-small" to "本地Whisper Small",
                "whisper-medium" to "本地Whisper Medium",
                "whisper-large" to "本地Whisper Large"
            )
            for ((dir, label) in whisperModels) {
                val modelDir = File(modelsDir, dir)
                if (modelDir.exists()) {
                    val totalSize = calculateDirSize(modelDir)
                    if (totalSize >= 1024 * 1024) {
                        adapter.add(label)
                    }
                }
            }

            // [v2.0.57] Issue 6 Fix: Clear descriptions for Vosk models
            val allDirs = modelsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            val voskDirs = allDirs.filter {
                it.name.contains("vosk-model", ignoreCase = true)
            }.sortedWith(compareByDescending<File> {
                // Chinese models first, then by size (large before small)
                when {
                    it.name.contains("cn", ignoreCase = true) -> 2
                    it.name.contains("small", ignoreCase = true) -> 0
                    else -> 1
                }
            }.thenBy { it.name })

            // Track used labels to avoid duplicates
            val usedLabels = mutableSetOf<String>()
            for (dir in voskDirs) {
                // [v2.0.56] Validate model directory has am/ and graph/ subdirs
                val amDir = File(dir, "am")
                val graphDir = File(dir, "graph")
                if (!amDir.exists() || !graphDir.exists()) {
                    Log.d("SettingsFragment", "Skipping incomplete Vosk model: ${dir.name} (missing am/ or graph/)")
                    continue
                }
                val totalSize = calculateDirSize(dir)
                if (totalSize >= 1024 * 1024) {
                    // [v2.0.64] Issue 6 Fix: Labels by size as user requested.
                    // "Vosk大模型" / "Vosk小模型" for all models.
                    // Models without "small" in name are large models (vosk-model-cn-0.22 = 2GB).
                    val isSmall = dir.name.contains("small", ignoreCase = true)
                    val isCn = dir.name.contains("cn", ignoreCase = true)
                    val baseLabel = when {
                        isSmall && isCn -> "Vosk中文小模型"
                        isSmall -> "Vosk小模型"
                        isCn -> "Vosk中文大模型"
                        dir.name.contains("en", ignoreCase = true) -> "Vosk英文大模型"
                        dir.name.contains("large", ignoreCase = true) -> "Vosk大模型"
                        else -> "Vosk大模型"
                    }
                    // Make label unique if duplicate exists
                    var label = baseLabel
                    var suffix = 2
                    while (usedLabels.contains(label)) {
                        label = if (isCn) "${baseLabel}(${suffix})" else "${baseLabel}(${dir.name.take(20)})"
                        suffix++
                    }
                    usedLabels.add(label)
                    Log.d("SettingsFragment", "Adding Vosk model: $label (dir=${dir.name})")
                    voskModelDirs[label] = dir.name
                    adapter.add(label)
                }
            }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "addInstalledEngines failed", e)
        }
    }

    private fun setupListeners() {
        binding.switchAutoSkip.setOnCheckedChangeListener { _, isChecked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            settings.autoSkipWater = isChecked
            save()
        }
        binding.switchContinuousPlay.setOnCheckedChangeListener { _, isChecked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            settings.continuousPlay = isChecked
            save()
        }
        binding.switchAutoDownload.setOnCheckedChangeListener { _, isChecked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            settings.autoDownload = isChecked
            save()
        }
        binding.switchAutoCache.setOnCheckedChangeListener { _, isChecked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            settings.autoCache = isChecked
            save()
        }
        binding.etPreloadCacheCount.setOnFocusChangeListener { _, hasFocus ->
            if (suppressListeners || hasFocus) return@setOnFocusChangeListener
            val count = binding.etPreloadCacheCount.text.toString().toIntOrNull()
            if (count != null && count > 0 && count <= 100) {
                settings.preloadCacheCount = count
                save()
            } else {
                binding.etPreloadCacheCount.setText(settings.preloadCacheCount.toString())
            }
        }
        binding.switchAudioFocus.setOnCheckedChangeListener { _, isChecked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            settings.audioFocus = isChecked
            save()
        }
        binding.switchSavePosition.setOnCheckedChangeListener { _, isChecked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            settings.savePlaybackPosition = isChecked
            save()
        }
        binding.switchRememberEpisode.setOnCheckedChangeListener { _, isChecked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            settings.rememberLastEpisode = isChecked
            save()
        }
        binding.switchWifiPrecache.setOnCheckedChangeListener { _, isChecked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            settings.wifiOnlyPreCache = isChecked
            save()
        }
        binding.switchPreprocessing.setOnCheckedChangeListener { _, isChecked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            settings.enablePreprocessing = isChecked
            save()
        }
        binding.spinnerNotificationStyle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressListeners) return
                settings.notificationStyle = when (position) {
                    1 -> "compact"
                    2 -> "minimal"
                    else -> "full"
                }
                save()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerSkipSeconds.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressListeners) return
                settings.skipSeconds = when (position) {
                    0 -> 5
                    1 -> 10
                    2 -> 15
                    3 -> 30
                    4 -> 60
                    else -> 15
                }
                save()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressListeners) return
                val themes = arrayOf(
                    AppSettings.THEME_DARK, AppSettings.THEME_FRESH,
                    AppSettings.THEME_CLASSIC, AppSettings.THEME_MINIMAL, AppSettings.THEME_CUSTOM
                )
                val selectedTheme = themes[position]
                if (selectedTheme != settings.uiTheme) {
                    settings.uiTheme = selectedTheme
                    save()
                    applyTheme()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerSubtitleSize.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressListeners) return
                val sizes = arrayOf(AppSettings.SUBTITLE_SMALL, AppSettings.SUBTITLE_MEDIUM, AppSettings.SUBTITLE_LARGE)
                settings.subtitleSize = sizes[position]
                save()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerSubtitleLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressListeners) return
                val langs = arrayOf(AppSettings.LANG_CN, AppSettings.LANG_EN)
                settings.subtitleLanguage = langs[position]
                save()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerVoiceLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressListeners) return
                val langs = arrayOf(AppSettings.LANG_CN, AppSettings.LANG_EN)
                settings.voiceLanguage = langs[position]
                save()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerAiModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressListeners) return
                val models = arrayOf(
                    AppSettings.AI_MODEL_WENXIN, AppSettings.AI_MODEL_DEEPSEEK,
                    AppSettings.AI_MODEL_QWEN, AppSettings.AI_MODEL_FUNASR,
                    AppSettings.AI_MODEL_WHISPER, AppSettings.AI_MODEL_JIU_AI_TING,
                    AppSettings.AI_MODEL_MNN_LLM
                )
                settings.aiModel = models[position]
                save()
                Toast.makeText(requireContext(), "AI模型已切换: " + parent?.getItemAtPosition(position), Toast.LENGTH_SHORT).show()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerAsrProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressListeners) return
                val selected = binding.spinnerAsrProvider.selectedItem.toString()
                // [v2.0.61] Issue 6 Fix: Correct label → provider mapping
                // IMPORTANT: Check "Whisper在线" BEFORE "本地Whisper" (both start with "Whisper")
                val providerId = when {
                    selected.startsWith("Whisper在线") -> AppSettings.ASR_WHISPER  // Online Whisper = "whisper"
                    selected.startsWith("本地Whisper") -> "whisper-local"  // Local Whisper
                    selected.startsWith("Vosk") -> {
                        // [v2.0.61] Issue 6: Save the specific Vosk model directory
                        val dirName = voskModelDirs[selected] ?: ""
                        settings.voskModelDir = dirName
                        "vosk-local"
                    }
                    selected.startsWith("百度") -> AppSettings.ASR_BAIDU
                    selected.startsWith("FunASR") -> AppSettings.ASR_FUNASR
                    else -> {
                        val providers = arrayOf(AppSettings.ASR_BAIDU, AppSettings.ASR_FUNASR, AppSettings.ASR_WHISPER, AppSettings.ASR_VOSK)
                        if (position < providers.size) providers[position] else selected
                    }
                }
                settings.asrProvider = providerId
                save()
                val dirInfo = if (providerId == "vosk-local") " (dir=${settings.voskModelDir})" else ""
                Toast.makeText(requireContext(), "ASR方案已切换: $selected → $providerId$dirInfo", Toast.LENGTH_SHORT).show()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.btnClearCache.setOnClickListener { showClearCacheDialog() }
        binding.btnManagePcmCache.setOnClickListener { showPcmCacheDialog() }
        binding.btnManageOfflineEngine.setOnClickListener {
            startActivity(Intent(requireContext(), OfflineEngineActivity::class.java))
        }
        binding.btnCustomizeColors.setOnClickListener { showColorPickerDialog() }
        binding.tvDislikedEpisodes.setOnClickListener {
            startActivity(Intent(requireContext(), DislikedEpisodesActivity::class.java))
        }
        binding.tvKeywordSettings.setOnClickListener {
            startActivity(Intent(requireContext(), KeywordSettingsActivity::class.java))
        }
        binding.tvAbout.setOnClickListener {
            showAboutDialog()
        }
    }

    /** Issue 14: app version shown in the About dialog */
    private fun getAppVersion(): String {
        return try {
            @Suppress("DEPRECATION")
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            pInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getAppVersionCode(): Long {
        return try {
            @Suppress("DEPRECATION")
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("关于")
            .setMessage("电台回放助手\n版本: ${getAppVersion()}\n版本号: ${getAppVersionCode()}")
            .setPositiveButton("确定", null)
            .show()
    }

    private fun updateUI() {
        binding.switchAutoSkip.isChecked = settings.autoSkipWater
        binding.switchContinuousPlay.isChecked = settings.continuousPlay
        binding.switchAutoDownload.isChecked = settings.autoDownload
        binding.switchAutoCache.isChecked = settings.autoCache
        binding.switchAudioFocus.isChecked = settings.audioFocus
        binding.switchSavePosition.isChecked = settings.savePlaybackPosition
        binding.switchRememberEpisode.isChecked = settings.rememberLastEpisode
        binding.switchWifiPrecache.isChecked = settings.wifiOnlyPreCache
        binding.switchPreprocessing.isChecked = settings.enablePreprocessing
        val notificationStyle = settings.notificationStyle
        val notificationIndex = when (notificationStyle) {
            "compact" -> 1
            "minimal" -> 2
            else -> 0
        }
        binding.spinnerNotificationStyle.setSelection(notificationIndex)
        binding.etPreloadCacheCount.setText(settings.preloadCacheCount.toString())

        // 快进快退时间
        val skipSecondsIndex = when (settings.skipSeconds) {
            5 -> 0; 10 -> 1; 15 -> 2; 30 -> 3; 60 -> 4
            else -> 2
        }
        binding.spinnerSkipSeconds.setSelection(skipSecondsIndex)

        suppressListeners = true

        val themeListener = binding.spinnerTheme.onItemSelectedListener
        binding.spinnerTheme.onItemSelectedListener = null
        val themes = arrayOf(AppSettings.THEME_DARK, AppSettings.THEME_FRESH, AppSettings.THEME_CLASSIC, AppSettings.THEME_MINIMAL, AppSettings.THEME_CUSTOM)
        themes.indexOfFirst { it == settings.uiTheme }.takeIf { it >= 0 }?.let { binding.spinnerTheme.setSelection(it) }
        binding.spinnerTheme.onItemSelectedListener = themeListener

        val sizeListener = binding.spinnerSubtitleSize.onItemSelectedListener
        binding.spinnerSubtitleSize.onItemSelectedListener = null
        val sizes = arrayOf(AppSettings.SUBTITLE_SMALL, AppSettings.SUBTITLE_MEDIUM, AppSettings.SUBTITLE_LARGE)
        sizes.indexOfFirst { it == settings.subtitleSize }.takeIf { it >= 0 }?.let { binding.spinnerSubtitleSize.setSelection(it) }
        binding.spinnerSubtitleSize.onItemSelectedListener = sizeListener

        val langListener = binding.spinnerSubtitleLang.onItemSelectedListener
        binding.spinnerSubtitleLang.onItemSelectedListener = null
        val langs = arrayOf(AppSettings.LANG_CN, AppSettings.LANG_EN)
        langs.indexOfFirst { it == settings.subtitleLanguage }.takeIf { it >= 0 }?.let { binding.spinnerSubtitleLang.setSelection(it) }
        binding.spinnerSubtitleLang.onItemSelectedListener = langListener

        val voiceLangListener = binding.spinnerVoiceLang.onItemSelectedListener
        binding.spinnerVoiceLang.onItemSelectedListener = null
        langs.indexOfFirst { it == settings.voiceLanguage }.takeIf { it >= 0 }?.let { binding.spinnerVoiceLang.setSelection(it) }
        binding.spinnerVoiceLang.onItemSelectedListener = voiceLangListener

        val aiModelListener = binding.spinnerAiModel.onItemSelectedListener
        binding.spinnerAiModel.onItemSelectedListener = null
        val aiModels = arrayOf(AppSettings.AI_MODEL_WENXIN, AppSettings.AI_MODEL_DEEPSEEK, AppSettings.AI_MODEL_QWEN, AppSettings.AI_MODEL_FUNASR, AppSettings.AI_MODEL_WHISPER, AppSettings.AI_MODEL_JIU_AI_TING, AppSettings.AI_MODEL_MNN_LLM)
        aiModels.indexOfFirst { it == settings.aiModel }.takeIf { it >= 0 }?.let { binding.spinnerAiModel.setSelection(it) }
        binding.spinnerAiModel.onItemSelectedListener = aiModelListener

        val asrProviderListener = binding.spinnerAsrProvider.onItemSelectedListener
        binding.spinnerAsrProvider.onItemSelectedListener = null
        val asrProviders = arrayOf(AppSettings.ASR_BAIDU, AppSettings.ASR_FUNASR, AppSettings.ASR_WHISPER, AppSettings.ASR_VOSK)
        val savedProvider = settings.asrProvider
        val savedVoskDir = settings.voskModelDir  // [v2.0.61] Issue 6: Use saved Vosk model dir
        val index = asrProviders.indexOfFirst { it == savedProvider }
        val adapter = binding.spinnerAsrProvider.adapter as? ArrayAdapter<*>
        if (index >= 0) {
            binding.spinnerAsrProvider.setSelection(index)
        } else if (savedProvider == "whisper-local") {
            val whisperIndex = (0 until (adapter?.count ?: 0)).indexOfFirst {
                val item = adapter?.getItem(it)?.toString() ?: ""
                item.startsWith("本地Whisper")
            }
            if (whisperIndex >= 0) binding.spinnerAsrProvider.setSelection(whisperIndex)
        } else if (savedProvider == "vosk-local") {
            // [v2.0.61] Issue 6 Fix: Match the SPECIFIC Vosk model by saved directory name
            // Find the label that maps to the saved directory, not just the first Vosk label
            val voskLabel = voskModelDirs.entries.find { it.value == savedVoskDir }?.key
            val voskIndex = if (voskLabel != null) {
                (0 until (adapter?.count ?: 0)).indexOfFirst {
                    adapter?.getItem(it)?.toString() == voskLabel
                }
            } else {
                // Fallback: find first Vosk label
                (0 until (adapter?.count ?: 0)).indexOfFirst {
                    val item = adapter?.getItem(it)?.toString() ?: ""
                    item.startsWith("Vosk")
                }
            }
            if (voskIndex >= 0) binding.spinnerAsrProvider.setSelection(voskIndex)
        }
        binding.spinnerAsrProvider.onItemSelectedListener = asrProviderListener

        val cacheSize = calculateCacheSize()
        binding.tvCacheSize.text = "缓存大小: " + formatSize(cacheSize)

        binding.root.postDelayed({
            suppressListeners = false
        }, 500)
    }

    private val AUDIO_EXTENSIONS = setOf(
        ".mp3", ".mp4", ".m4a", ".aac", ".ogg", ".flac", ".wma",
        ".m3u8", ".ts", ".m3u", ".opus", ".amr", ".mid", ".midi"
    )
    // .wav and .pcm files are excluded from cache clear - they are managed in PCM cache dialog

    private fun calculateCacheSize(): Long {
        var size = 0L
        size += calculateAudioDirSize(requireContext().cacheDir)
        requireContext().externalCacheDir?.let { size += calculateAudioDirSize(it) }
        size += calculateAudioDirSize(requireContext().filesDir)
        requireContext().getExternalFilesDir(null)?.let { size += calculateAudioDirSize(it) }
        return size
    }

    private fun calculateAudioDirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0
        var size = 0L
        val files = dir.listFiles()
        if (files != null) {
            for (file in files) {
                size += if (file.isDirectory) calculateAudioDirSize(file)
                else if (isAudioFile(file.name)) file.length()
                else 0
            }
        }
        return size
    }

    private fun isAudioFile(name: String): Boolean {
        val lower = name.lowercase()
        return AUDIO_EXTENSIONS.any { lower.endsWith(it) }
    }

    private fun calculateDirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0
        var size = 0L
        val files = dir.listFiles()
        if (files != null) {
            for (file in files) {
                size += if (file.isDirectory) calculateDirSize(file) else file.length()
            }
        }
        return size
    }

    private fun showClearCacheDialog() {
        val allFiles = mutableListOf<File>()
        // 仅扫描音频缓存文件
        scanAudioFilesRecursive(requireContext().cacheDir, allFiles)
        requireContext().externalCacheDir?.let { scanAudioFilesRecursive(it, allFiles) }
        scanAudioFilesRecursive(requireContext().filesDir, allFiles)
        requireContext().getExternalFilesDir(null)?.let { scanAudioFilesRecursive(it, allFiles) }

        if (allFiles.isEmpty()) {
            Toast.makeText(requireContext(), "暂无缓存文件", Toast.LENGTH_SHORT).show()
            return
        }

        val files = allFiles.toTypedArray()
        val cachePath = requireContext().cacheDir.absolutePath
        val extCachePath = requireContext().externalCacheDir?.absolutePath ?: ""
        val filesPath = requireContext().filesDir.absolutePath
        val extFilesPath = requireContext().getExternalFilesDir(null)?.absolutePath ?: ""
        val fileNames = Array(files.size) { i ->
            val path = files[i].absolutePath
            val shortPath = when {
                extCachePath.isNotEmpty() && path.startsWith(extCachePath) -> "[外缓存]" + path.replace(extCachePath, "...")
                path.startsWith(cachePath) -> "[内缓存]" + path.replace(cachePath, "...")
                extFilesPath.isNotEmpty() && path.startsWith(extFilesPath) -> "[外文件]" + path.replace(extFilesPath, "...")
                path.startsWith(filesPath) -> "[内文件]" + path.replace(filesPath, "...")
                else -> path
            }
            shortPath + " (" + formatSize(files[i].length()) + ")"
        }
        val checked = BooleanArray(files.size) { true }
        showClearCacheDialogWithButtons(files, fileNames, checked)
    }

    // Issue 10: 自定义多选列表适配器，允许文件名换行显示两行并省略结尾，
    // 避免长缓存文件名被系统默认布局单行截断而看不全。
    private fun createMultiChoiceAdapter(fileNames: Array<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_multiple_choice, fileNames) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                view.findViewById<TextView>(android.R.id.text1)?.apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    textSize = 12f
                }
                return view
            }
        }
    }

    private fun showClearCacheDialogWithButtons(files: Array<File>, fileNames: Array<String>, checked: BooleanArray) {
        // 创建垂直布局：按钮在顶部（固定可见），列表在下方（可滚动）
        val contentView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 按钮行（始终固定在顶部）
        val btnContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 8, 10, 8)
        }
        val btnClearAll = Button(requireContext()).apply {
            text = "清空全部(${files.size}个)"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xFFE53935.toInt())
            textSize = 13f
        }
        val btnSelectAll = Button(requireContext()).apply { text = "全选"; textSize = 13f }
        val btnSelectNone = Button(requireContext()).apply { text = "全不选"; textSize = 13f }
        val btnInvert = Button(requireContext()).apply { text = "反选"; textSize = 13f }
        val btnDislikeFilter = Button(requireContext()).apply {
            text = "不喜欢"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xFFFF9800.toInt())
            textSize = 13f
        }
        btnContainer.addView(btnSelectAll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnContainer.addView(btnSelectNone, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnContainer.addView(btnInvert, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnContainer.addView(btnDislikeFilter, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnContainer.addView(btnClearAll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        contentView.addView(btnContainer)

        // ListView（可滚动，位于按钮下方）
        val listView = android.widget.ListView(requireContext()).apply {
            choiceMode = android.widget.AbsListView.CHOICE_MODE_MULTIPLE
            adapter = createMultiChoiceAdapter(fileNames)
            for (i in checked.indices) { setItemChecked(i, checked[i]) }
        }
        contentView.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("选择要删除的缓存文件 (${files.size}个)")
            .setView(contentView)
            .setPositiveButton("删除选中") { _, _ ->
                var deletedSize = 0L
                for (i in files.indices) {
                    if (checked[i] && files[i].delete()) deletedSize += files[i].length()
                }
                Toast.makeText(requireContext(), "已删除 " + formatSize(deletedSize), Toast.LENGTH_SHORT).show()
                updateUI()
            }
            .setNegativeButton("取消", null)
            .create()

        // 点击列表项时同步 checked 数组
        listView.setOnItemClickListener { _, _, position, _ ->
            checked[position] = listView.isItemChecked(position)
        }

        // 清空全部按钮：直接删除所有文件
        btnClearAll.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("确认清空全部缓存")
                .setMessage("将删除全部${files.size}个缓存文件，此操作不可撤销。")
                .setPositiveButton("确认清空") { _, _ ->
                    var deletedSize = 0L
                    for (f in files) { if (f.delete()) deletedSize += f.length() }
                    Toast.makeText(requireContext(), "已清空全部缓存 " + formatSize(deletedSize), Toast.LENGTH_SHORT).show()
                    updateUI()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        btnSelectAll.setOnClickListener {
            for (i in checked.indices) { checked[i] = true; listView.setItemChecked(i, true) }
        }
        btnSelectNone.setOnClickListener {
            for (i in checked.indices) { checked[i] = false; listView.setItemChecked(i, false) }
        }
        btnInvert.setOnClickListener {
            for (i in checked.indices) { checked[i] = !checked[i]; listView.setItemChecked(i, checked[i]) }
        }
        btnDislikeFilter.setOnClickListener {
            // First deselect all
            for (i in checked.indices) { checked[i] = false; listView.setItemChecked(i, false) }

            val context = requireContext()
            Thread {
                val startTime = System.currentTimeMillis()
                val gson = com.google.gson.Gson()

                val cacheMappingPrefs = context.getSharedPreferences("cache_episode_mapping", Context.MODE_PRIVATE)
                val allEpPrefs = context.getSharedPreferences("all_episodes", Context.MODE_PRIVATE)
                val precacheListPrefs = context.getSharedPreferences("precache_list", Context.MODE_PRIVATE)
                val epListCachePrefs = context.getSharedPreferences("episode_list_cache", Context.MODE_PRIVATE)

                // Pre-compile regexes
                val suffixRegex = Regex("(_30min|_16k)?\\.(mp4|pcm|wav|m4a|aac|mp3)$")
                val minRegex = Regex("_\\d+min$")
                val hourRegex = Regex("_(\\d{2})\\d{2}_")
                val hourRangeRegex = Regex("_(\\d{2})\\d{2}_(\\d{2})\\d{2}")
                val dateRangeRegex = Regex("(\\d{8})_(\\d{4})_(\\d{4})")
                val stationPrefixRegex = Regex("^([a-zA-Z\\-]+)_")
                val urlSuffixRegex = Regex("\\.(mp4|m4a|aac|mp3|ts|m3u8)$")
                val keywordRegex = Regex("[\\s\\-·]")
                val bracketRegex = Regex("^《|》$")
                val normalizeRegex = Regex("[《》·\\-—（）()\\[】【]")

                // 1) cache_episode_mapping -> Map<String, Episode>
                val cacheEpisodeMap = mutableMapOf<String, com.radio.app.models.Episode>()
                for ((k, v) in cacheMappingPrefs.all) {
                    if (v !is String) continue
                    try {
                        val ep = gson.fromJson(v, com.radio.app.models.Episode::class.java)
                        if (ep != null) cacheEpisodeMap[k] = ep
                    } catch (_: Exception) { /* skip */ }
                }

                // episode_list_cache: timeSlotToEpisode + indexes by audioUrl/id
                val timeSlotToEpisode = mutableMapOf<String, Pair<String, String>>()
                val episodeListByAudioUrl = mutableMapOf<String, org.json.JSONObject>()
                val episodeListById = mutableMapOf<String, org.json.JSONObject>()
                val episodeListObjects = mutableListOf<org.json.JSONObject>()
                try {
                    val episodesJson = epListCachePrefs.getString("episodes", null)
                    if (episodesJson != null) {
                        val arr = org.json.JSONArray(episodesJson)
                        for (i in 0 until arr.length()) {
                            try {
                                val obj = arr.getJSONObject(i)
                                episodeListObjects.add(obj)
                                val id = obj.optString("id", "")
                                val title = obj.optString("title", "")
                                val stationId = obj.optString("station_id", "")
                                val broadcastAt = obj.optString("broadcast_at", "")
                                val audioUrl = obj.optString("audio_url", "")
                                if (id.isNotBlank()) episodeListById[id] = obj
                                if (audioUrl.isNotBlank()) episodeListByAudioUrl[audioUrl] = obj
                                if (title.isNotBlank() && stationId.isNotBlank() && broadcastAt.length >= 13) {
                                    val hour = broadcastAt.substring(11, 13)
                                    val urlFileName = audioUrl.substringAfterLast("/").substringBefore("_")
                                    if (urlFileName.isNotBlank()) {
                                        timeSlotToEpisode["$urlFileName:$hour"] = Pair(title, stationId)
                                    }
                                }
                            } catch (_: Exception) { /* skip */ }
                        }
                    }
                } catch (_: Exception) { /* skip */ }

                // all_episodes -> Map<String, Episode>
                val allEpMap = mutableMapOf<String, com.radio.app.models.Episode>()
                for ((k, v) in allEpPrefs.all) {
                    if (v !is String) continue
                    try {
                        val ep = gson.fromJson(v, com.radio.app.models.Episode::class.java)
                        if (ep != null) allEpMap[k] = ep
                    } catch (_: Exception) { /* skip */ }
                }

                // precache_list -> Map<String, Episode>
                val precacheMap = mutableMapOf<String, com.radio.app.models.Episode>()
                for ((k, v) in precacheListPrefs.all) {
                    if (v !is String) continue
                    try {
                        val ep = gson.fromJson(v, com.radio.app.models.Episode::class.java)
                        if (ep != null) precacheMap[k] = ep
                    } catch (_: Exception) { /* skip */ }
                }

                val settings = com.radio.app.models.AppSettings.getInstance(context)

                // Pre-compute dislike sets
                val dislikedIdSet = settings.dislikedEpisodes.toHashSet()
                val dislikedTitleKeySet = settings.dislikedEpisodes.toHashSet()
                val dislikedNormMap = mutableMapOf<String, MutableSet<String>>()
                for (key in settings.dislikedEpisodes) {
                    if (!key.contains("::")) continue
                    val station = key.substringBefore("::")
                    val rawTitle = key.substringAfter("::")
                    val stripped = rawTitle.replace(bracketRegex, "")
                    val norm = stripped.replace(normalizeRegex, "")
                    if (norm.isNotBlank()) {
                        dislikedNormMap.getOrPut(station) { mutableSetOf() }.add(norm)
                    }
                }

                fun isDislikedFast(episodeId: String?, stationId: String?, title: String?): Boolean {
                    if (!episodeId.isNullOrBlank() && episodeId in dislikedIdSet) return true
                    if (stationId.isNullOrBlank() || title.isNullOrBlank()) return false

                    val key = "$stationId::$title"
                    if (key in dislikedTitleKeySet) return true

                    val strippedTitle = title.replace(bracketRegex, "")
                    if (strippedTitle != title && "$stationId::$strippedTitle" in dislikedTitleKeySet) return true

                    val wrappedTitle = "《$title》"
                    if ("$stationId::$wrappedTitle" in dislikedTitleKeySet) return true

                    val normTitle = title.replace(normalizeRegex, "")
                    val normStripped = strippedTitle.replace(normalizeRegex, "")
                    val norms = dislikedNormMap[stationId] ?: return false
                    if (normTitle in norms || normStripped in norms) return true
                    for (n in norms) {
                        if (n + "重播" == normTitle || n + "重播" == normStripped) return true
                    }
                    return false
                }

                val dislikedFileNames = mutableSetOf<String>()
                // [v2.0.96] Track files whose episodes were resolved by direct ID matching.
                // Steps 5 and 6 must skip these files to prevent false positives.
                val resolvedFileNames = mutableSetOf<String>()

                for (file in files) {
                    val fileName = file.name
                    val episodeId = fileName.replace(suffixRegex, "").replace(minRegex, "")
                    var found = false
                    // [v2.0.95] Track if file's episode was resolved by direct ID matching.
                    // If resolved (even if NOT disliked), skip time-slot matching to prevent
                    // false positives where a disliked show at the same hour on a different date
                    // causes liked episodes to be selected.
                    var episodeResolved = false

                    // 1) cache_episode_mapping (direct mapping)
                    for (key in listOf(fileName, "$episodeId.mp4", "$episodeId.m4a", "$episodeId.aac")) {
                        val ep = cacheEpisodeMap[key]
                        if (ep != null) {
                            episodeResolved = true
                            if (isDislikedFast(ep.id, ep.stationId, ep.title)) {
                                dislikedFileNames.add(fileName)
                                found = true
                                break
                            }
                        }
                    }
                    if (found) continue

                    // [v2.0.95] MOVED: Time-slot matching moved to AFTER direct ID matching (step 4).
                    // Previously at step 1.5, it matched by hour only, ignoring the date.
                    // This caused false positives: a disliked show at 7:00 on date A
                    // would select ALL files at 7:00 on ANY date, even liked episodes.

                    // 2) all_episodes (by episodeId in audioUrl)
                    for ((audioUrl, ep) in allEpMap) {
                        if (audioUrl.contains(episodeId)) {
                            episodeResolved = true
                            if (isDislikedFast(ep.id, ep.stationId, ep.title)) {
                                dislikedFileNames.add(fileName)
                                found = true
                                break
                            }
                        }
                    }
                    if (found) continue

                    // 3) precache_list (by episodeId)
                    for ((key, ep) in precacheMap) {
                        if (key.contains(episodeId)) {
                            episodeResolved = true
                            if (isDislikedFast(ep.id, ep.stationId, ep.title)) {
                                dislikedFileNames.add(fileName)
                                found = true
                                break
                            }
                        }
                    }
                    if (found) continue

                    // 4) episode_list_cache (by episodeId in JSON)
                    val idObj = episodeListById[episodeId]
                    if (idObj != null) {
                        episodeResolved = true
                        if (isDislikedFast(
                                idObj.optString("id", ""),
                                idObj.optString("station_id", ""),
                                idObj.optString("title", "")
                        )) {
                            dislikedFileNames.add(fileName)
                            continue
                        }
                    }
                    for ((audioUrl, obj) in episodeListByAudioUrl) {
                        if (audioUrl.contains(episodeId)) {
                            episodeResolved = true
                            if (isDislikedFast(
                                    obj.optString("id", ""),
                                    obj.optString("station_id", ""),
                                    obj.optString("title", "")
                            )) {
                                dislikedFileNames.add(fileName)
                                found = true
                                break
                            }
                        }
                    }
                    if (found) continue

                    // [v2.0.95] Time-slot matching — only if episode was NOT resolved by direct ID matching.
                    // This prevents false positives where a disliked show at the same hour on a different date
                    // causes liked episodes to be selected.
                    if (!episodeResolved) {
                        val hourMatch = hourRegex.find(fileName)
                        val stationPrefix = fileName.substringBefore("_")
                        if (hourMatch != null && stationPrefix.isNotBlank()) {
                            val hourStr = hourMatch.groupValues[1]
                            val ep = timeSlotToEpisode["$stationPrefix:$hourStr"]
                            if (ep != null && isDislikedFast(null, ep.second, ep.first)) {
                                dislikedFileNames.add(fileName)
                                found = true
                            }
                        }
                        if (!found) {
                            val timeRangeMatch = hourRangeRegex.find(fileName)
                            if (timeRangeMatch != null) {
                                val startHour = timeRangeMatch.groupValues[1].toIntOrNull() ?: -1
                                val endHour = timeRangeMatch.groupValues[2].toIntOrNull() ?: -1
                                for (h in startHour until endHour) {
                                    val slotKey = "$stationPrefix:${String.format("%02d", h)}"
                                    val ep = timeSlotToEpisode[slotKey]
                                    if (ep != null && isDislikedFast(null, ep.second, ep.first)) {
                                        dislikedFileNames.add(fileName)
                                        found = true
                                        break
                                    }
                                }
                            }
                        }
                    }
                    // [v2.0.96] Track resolved files for steps 5/6
                    if (episodeResolved) {
                        resolvedFileNames.add(fileName)
                    }
                }

                // 5) episode_list_cache: scan ALL episodes (not just matching by episodeId)
                // [v2.0.96] Skip files whose episodes were already resolved by direct ID matching.
                try {
                    for (file in files) {
                        if (file.name in dislikedFileNames) continue
                        if (file.name in resolvedFileNames) continue  // [v2.0.96] Skip resolved files
                        val fileName = file.name
                        val timeRangeMatch = dateRangeRegex.find(fileName)
                        val stationPrefix = stationPrefixRegex.find(fileName)?.groupValues?.get(1) ?: ""

                        for (obj in episodeListObjects) {
                            val title = obj.optString("title", "")
                            val stationId = obj.optString("station_id", "")
                            val broadcastAt = obj.optString("broadcast_at", "")
                            val audioUrl = obj.optString("audio_url", "")
                            val epId = obj.optString("id", "")

                            if (!isDislikedFast(epId, stationId, title)) continue

                            if (timeRangeMatch != null) {
                                val dateStr = timeRangeMatch.groupValues[1]
                                val startTimeStr = timeRangeMatch.groupValues[2]
                                val formattedDate = "${dateStr.substring(0, 4)}-${dateStr.substring(4, 6)}-${dateStr.substring(6, 8)}"
                                if (broadcastAt.contains(formattedDate) && broadcastAt.contains(startTimeStr.substring(0, 2))) {
                                    dislikedFileNames.add(fileName)
                                    break
                                }
                            }

                            if (stationPrefix.isNotBlank() && stationId.isNotBlank() &&
                                (stationId.lowercase().contains(stationPrefix.lowercase()) || stationPrefix.lowercase().contains(stationId.lowercase()))) {
                                val keywords = title.split(keywordRegex).filter { it.length >= 2 }
                                if (keywords.any { fileName.lowercase().contains(it.lowercase()) }) {
                                    dislikedFileNames.add(fileName)
                                    break
                                }
                            }

                            if (audioUrl.isNotBlank()) {
                                val urlFileName = audioUrl.substringAfterLast("/").substringBefore("?")
                                val urlBase = urlFileName.replace(urlSuffixRegex, "")
                                if (urlBase.isNotBlank() && fileName.contains(urlBase)) {
                                    dislikedFileNames.add(fileName)
                                    break
                                }
                            }

                            val keywords = title.split(keywordRegex).filter { it.length >= 2 }
                            if (keywords.isNotEmpty() && keywords.any { fileName.lowercase().contains(it.lowercase()) } &&
                                (stationPrefix.isBlank() || stationId.isBlank() ||
                                    stationId.lowercase().contains(stationPrefix.lowercase()) || stationPrefix.lowercase().contains(stationId.lowercase()))) {
                                dislikedFileNames.add(fileName)
                                break
                            }
                        }
                    }
                } catch (_: Exception) { /* skip */ }

                // Final fallback: scan all_episodes for any disliked title, match by stationId + file name keywords
                // [v2.0.94] Fix cross-station false positive: previously used || (OR) which matched
                // files from ANY station if they contained title keywords. Now uses && (AND) to
                // require BOTH stationId AND keyword match, preventing liked episodes on other
                // stations from being selected for deletion.
                // [v2.0.96] Also skip files whose episodes were already resolved by direct ID matching.
                try {
                    for ((_, ep) in allEpMap) {
                        if (!isDislikedFast(ep.id, ep.stationId, ep.title)) continue
                        val title = ep.title
                        val keywords = title.split(keywordRegex).filter { it.length >= 2 }
                        if (keywords.isEmpty()) continue
                        for (file in files) {
                            if (file.name in dislikedFileNames) continue
                            if (file.name in resolvedFileNames) continue  // [v2.0.96] Skip resolved files
                            val fname = file.name.lowercase()
                            // [v2.0.94] Require BOTH stationId match AND keyword match
                            if (fname.contains(ep.stationId.lowercase()) &&
                                keywords.any { fname.contains(it.lowercase()) }) {
                                dislikedFileNames.add(file.name)
                            }
                        }
                    }
                } catch (_: Exception) { /* skip */ }

                // [v2.0.95] Detailed per-file analysis log for debugging dislike filter
                try {
                    val logDir = java.io.File(com.radio.app.RadioApplication.getLogDir(context), "dislike")
                    if (!logDir.exists()) logDir.mkdirs()
                    val logFile = java.io.File(logDir, "dislike.log")
                    val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
                    val elapsed = System.currentTimeMillis() - startTime
                    val sb = StringBuilder()
                    sb.append("[$ts] === DISLIKE FILTER REPORT [v2.0.95] ===\n")
                    sb.append("  Total files: ${files.size}, Selected: ${dislikedFileNames.size}, elapsedMs=$elapsed\n")
                    sb.append("  Disliked episodes: ${settings.dislikedEpisodes}\n")
                    sb.append("--- PER-FILE ANALYSIS ---\n")
                    for (file in files) {
                        val fname = file.name
                        val isSelected = fname in dislikedFileNames
                        val episodeId = fname.replace(suffixRegex, "").replace(minRegex, "")
                        var titleFound = ""
                        var stationIdFound = ""
                        var epIdFound = ""
                        var isDislikedResult = false
                        var matchSource = ""

                        // Check cache_episode_mapping
                        for (key in listOf(fname, "$episodeId.mp4", "$episodeId.m4a", "$episodeId.aac")) {
                            val ep = cacheEpisodeMap[key]
                            if (ep != null) {
                                titleFound = ep.title ?: ""
                                stationIdFound = ep.stationId ?: ""
                                epIdFound = ep.id ?: ""
                                isDislikedResult = isDislikedFast(ep.id, ep.stationId, ep.title)
                                matchSource = "cache_episode_mapping"
                                break
                            }
                        }
                        // Check all_episodes
                        if (matchSource.isEmpty()) {
                            for ((audioUrl, ep) in allEpMap) {
                                if (audioUrl.contains(episodeId)) {
                                    titleFound = ep.title ?: ""
                                    stationIdFound = ep.stationId ?: ""
                                    epIdFound = ep.id ?: ""
                                    isDislikedResult = isDislikedFast(ep.id, ep.stationId, ep.title)
                                    matchSource = "all_episodes"
                                    break
                                }
                            }
                        }
                        // Check episode_list_cache by id
                        if (matchSource.isEmpty()) {
                            val idObj = episodeListById[episodeId]
                            if (idObj != null) {
                                titleFound = idObj.optString("title", "")
                                stationIdFound = idObj.optString("station_id", "")
                                epIdFound = idObj.optString("id", "")
                                isDislikedResult = isDislikedFast(epIdFound, stationIdFound, titleFound)
                                matchSource = "episode_list_cache"
                            }
                        }

                        sb.append("  FILE: $fname (${if (isSelected) "SELECTED" else "NOT SELECTED"})\n")
                        if (matchSource.isNotEmpty()) {
                            sb.append("    Source: $matchSource, Title: '$titleFound', StationId: $stationIdFound, ID: $epIdFound\n")
                            sb.append("    isDislikedFast: $isDislikedResult\n")
                        } else {
                            sb.append("    Title: NOT FOUND in any source\n")
                        }
                        if (isSelected && !isDislikedResult && matchSource.isNotEmpty()) {
                            sb.append("    WARNING: SELECTED but isDislikedFast=false! Matched by Final fallback or keyword\n")
                        }
                        sb.append("\n")
                    }
                    sb.append("--- END REPORT: ${dislikedFileNames.size}/${files.size} files selected ---\n")
                    java.io.FileWriter(logFile, true).use { it.append(sb.toString()) }
                } catch (_: Exception) { /* skip */ }

                // One-line summary log
                try {
                    val logDir = java.io.File(com.radio.app.RadioApplication.getLogDir(context), "dislike")
                    if (!logDir.exists()) logDir.mkdirs()
                    val logFile = java.io.File(logDir, "dislike.log")
                    val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
                    val elapsed = System.currentTimeMillis() - startTime
                    val summary = "[$ts] files=${files.size}, selected=${dislikedFileNames.size}, elapsedMs=$elapsed, " +
                            "cacheMapping=${cacheEpisodeMap.size}, timeSlots=${timeSlotToEpisode.size}, " +
                            "allEpisodes=${allEpMap.size}, precache=${precacheMap.size}, " +
                            "epListByAudioUrl=${episodeListByAudioUrl.size}, epListById=${episodeListById.size}\n"
                    java.io.FileWriter(logFile, true).use { it.append(summary) }
                } catch (_: Exception) { /* skip */ }

                val selectedCount = dislikedFileNames.size
                activity?.runOnUiThread {
                    for (i in files.indices) {
                        if (files[i].name in dislikedFileNames) {
                            checked[i] = true
                            listView.setItemChecked(i, true)
                        }
                    }
                    Toast.makeText(context, "已选中${selectedCount}个不喜欢节目的缓存", Toast.LENGTH_SHORT).show()
                } ?: return@Thread
            }.start()
        }

        dialog.show()
        dialog.window?.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private var mediaPlayer: android.media.MediaPlayer? = null

    private fun playPcmFile(pcmFile: File) {
        pcmPlaybackActive = false
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null

        try {
            // Prefer WAV file for reliable playback (correct sample rate handled by MediaPlayer)
            val wavFile = File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".wav")
            if (wavFile.exists() && wavFile.length() > 44) {
                android.util.Log.d("SettingsFragment", "playPcmFile: playing WAV: ${wavFile.name}")
                mediaPlayer = android.media.MediaPlayer().apply {
                    setDataSource(wavFile.absolutePath)
                    setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    setOnCompletionListener { pcmPlaybackActive = false }
                    setOnPreparedListener { start() }
                    prepareAsync()
                }
                pcmPlaybackActive = true
                Toast.makeText(requireContext(), "播放WAV: ${wavFile.name}", Toast.LENGTH_SHORT).show()
                return
            }

            // Fallback to AudioTrack if WAV not available
            // Use streaming to avoid OOM on large PCM files
            val infoFile = File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".info")
            var sampleRate = 16000
            var channels = 1
            if (infoFile.exists()) {
                val info = infoFile.readText()
                val srMatch = Regex("sampleRate=(\\d+)").find(info)
                if (srMatch != null) sampleRate = srMatch.groupValues[1].toInt()
                val chMatch = Regex("channels=(\\d+)").find(info)
                if (chMatch != null) channels = chMatch.groupValues[1].toInt()
            }
            val channelMask = if (channels >= 2) android.media.AudioFormat.CHANNEL_OUT_STEREO else android.media.AudioFormat.CHANNEL_OUT_MONO
            val bytesPerFrame = channels * 2
            val expectedDurationSec = pcmFile.length() / (sampleRate * bytesPerFrame)
            android.util.Log.d("SettingsFragment", "playPcmFile: streaming AudioTrack ${pcmFile.name}, size=${pcmFile.length()}, sampleRate=$sampleRate, channels=$channels, expected=${expectedDurationSec}s")

            val bufferSize = maxOf(
                android.media.AudioTrack.getMinBufferSize(sampleRate, channelMask, android.media.AudioFormat.ENCODING_PCM_16BIT),
                4096
            )

            audioTrack = android.media.AudioTrack(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                android.media.AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(channelMask)
                    .build(),
                bufferSize,
                android.media.AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            pcmPlaybackActive = true
            audioTrack?.play()
            Thread {
                val buf = ByteArray(bufferSize)
                val pcmIn = pcmFile.inputStream()
                try {
                    var read: Int
                    while (pcmIn.read(buf).also { read = it } > 0 && pcmPlaybackActive && audioTrack != null) {
                        try {
                            val track = audioTrack ?: break
                            val written = track.write(buf, 0, read)
                            if (written <= 0) break
                        } catch (e: Exception) { break }
                    }
                } finally {
                    pcmIn.close()
                }
            }.start()

            Toast.makeText(requireContext(), "播放PCM: ${pcmFile.name} (${expectedDurationSec}秒, ${sampleRate}Hz)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            pcmPlaybackActive = false
            Toast.makeText(requireContext(), "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPcmPlayback() {
        pcmPlaybackActive = false
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        android.widget.Toast.makeText(requireContext(), "已停止播放", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showPcmCacheDialog() {
        val allFiles = mutableListOf<File>()
        val pcmCacheDir = requireContext().getExternalFilesDir(null)?.let { File(it, "pcm_cache") }
        scanFilesRecursive(pcmCacheDir, allFiles)

        if (allFiles.isEmpty()) {
            Toast.makeText(requireContext(), "暂无PCM解码缓存文件", Toast.LENGTH_SHORT).show()
            return
        }

        val files = allFiles.toTypedArray()
        val pcmPath = pcmCacheDir?.absolutePath ?: ""
        val fileNames = Array(files.size) { i ->
            val path = files[i].absolutePath
            val shortPath = path.replace(pcmPath, "...")
            shortPath + " (" + formatSize(files[i].length()) + ")"
        }
        val checked = BooleanArray(files.size) { true }

        showPcmCacheDialogWithButtons(files, fileNames, checked, pcmCacheDir)
    }

    private fun showPcmCacheDialogWithButtons(files: Array<File>, fileNames: Array<String>, checked: BooleanArray, pcmCacheDir: File?) {
        val totalSize = files.sumOf { it.length() }
        // 创建垂直布局：按钮在顶部（固定可见），列表在下方（可滚动）
        val contentView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 按钮行1（始终固定在顶部）：清空全部
        val btnRow1 = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 8, 10, 8)
        }
        val btnClearAll = Button(requireContext()).apply {
            text = "清空全部(${files.size}个, ${formatSize(totalSize)})"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xFFE53935.toInt())
            textSize = 13f
        }
        btnRow1.addView(btnClearAll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        contentView.addView(btnRow1)

        // 按钮行2（始终固定在顶部）：全选/全不选/反选/播放/停止
        val btnContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 0, 10, 8)
        }
        val btnSelectAll = Button(requireContext()).apply { text = "全选"; textSize = 13f }
        val btnSelectNone = Button(requireContext()).apply { text = "全不选"; textSize = 13f }
        val btnInvert = Button(requireContext()).apply { text = "反选"; textSize = 13f }
        val btnPlay = Button(requireContext()).apply { text = "播放选中"; textSize = 13f }
        val btnStop = Button(requireContext()).apply { text = "停止播放"; textSize = 13f }
        btnContainer.addView(btnSelectAll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnContainer.addView(btnSelectNone, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnContainer.addView(btnInvert, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnContainer.addView(btnPlay, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnContainer.addView(btnStop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        contentView.addView(btnContainer)

        // ListView（可滚动，位于按钮下方）
        val listView = android.widget.ListView(requireContext()).apply {
            choiceMode = android.widget.AbsListView.CHOICE_MODE_MULTIPLE
            adapter = createMultiChoiceAdapter(fileNames)
            for (i in checked.indices) { setItemChecked(i, checked[i]) }
        }
        contentView.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val titleText = "选择要删除的PCM缓存文件 (${files.size}个, 共${formatSize(totalSize)})"
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(titleText)
            .setView(contentView)
            .setPositiveButton("删除选中") { _, _ ->
                var deletedSize = 0L
                for (i in files.indices) {
                    if (checked[i] && files[i].delete()) deletedSize += files[i].length()
                }
                Toast.makeText(requireContext(), "已删除 " + formatSize(deletedSize), Toast.LENGTH_SHORT).show()
                updateUI()
            }
            .setNegativeButton("取消", null)
            .create()

        // 点击列表项时同步 checked 数组
        listView.setOnItemClickListener { _, _, position, _ ->
            checked[position] = listView.isItemChecked(position)
        }

        btnClearAll.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("确认清空全部PCM缓存")
                .setMessage("将删除全部${files.size}个PCM缓存文件(共${formatSize(totalSize)})，此操作不可撤销。")
                .setPositiveButton("确认清空") { _, _ ->
                    var deletedSize = 0L
                    for (f in files) { if (f.delete()) deletedSize += f.length() }
                    Toast.makeText(requireContext(), "已清空全部PCM缓存 " + formatSize(deletedSize), Toast.LENGTH_SHORT).show()
                    updateUI()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        btnSelectAll.setOnClickListener {
            for (i in checked.indices) { checked[i] = true; listView.setItemChecked(i, true) }
        }
        btnSelectNone.setOnClickListener {
            for (i in checked.indices) { checked[i] = false; listView.setItemChecked(i, false) }
        }
        btnInvert.setOnClickListener {
            for (i in checked.indices) { checked[i] = !checked[i]; listView.setItemChecked(i, checked[i]) }
        }
        btnPlay.setOnClickListener {
            val selectedFiles = files.filterIndexed { i, _ -> checked[i] }
            if (audioTrack != null && audioTrack?.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                stopPcmPlayback()
            } else {
                if (selectedFiles.isNotEmpty()) {
                    playPcmFile(selectedFiles.first())
                } else {
                    Toast.makeText(requireContext(), "请先选择一个文件", Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnStop.setOnClickListener {
            stopPcmPlayback()
        }

        dialog.show()
        dialog.window?.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun scanFilesRecursive(dir: File?, result: MutableList<File>) {
        val files = dir?.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) scanFilesRecursive(f, result)
            else result.add(f)
        }
    }

    private fun scanAudioFilesRecursive(dir: File?, result: MutableList<File>) {
        val files = dir?.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) scanAudioFilesRecursive(f, result)
            else if (isAudioFile(f.name)) result.add(f)
        }
    }

    private fun formatSize(size: Long): String {
        if (size < 1024) return "$size B"
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0)
        return String.format("%.1f MB", size / (1024.0 * 1024))
    }

    private fun showColorPickerDialog() {
        var colors = settings.customColors
        if (colors == null) colors = AppSettings.CustomColors()

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val labels = arrayOf("主色", "强调色", "背景色", "文字色", "卡片色", "边框色", "成功色", "警告色")
        val values = arrayOf(colors.primary, colors.accent, colors.background, colors.text, colors.card, colors.border, colors.success, colors.warning)
        val edits = Array<EditText>(labels.size) { EditText(requireContext()) }

        // 获取主题文字颜色
        val typedValue = android.util.TypedValue()
        val theme = requireContext().theme
        val textPrimaryColor = if (theme.resolveAttribute(R.attr.appTextPrimary, typedValue, true)) typedValue.data else 0xFF000000.toInt()

        for (i in labels.indices) {
            val tv = TextView(requireContext()).apply {
                text = labels[i]
                setTextColor(textPrimaryColor)
            }
            layout.addView(tv)

            edits[i] = EditText(requireContext()).apply {
                setText(values[i])
                setTextColor(textPrimaryColor)
            }
            layout.addView(edits[i])

            val presetRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            val presets = arrayOf(
                arrayOf("#FF5722", "#E91E63", "#9C27B0", "#673AB7"),
                arrayOf("#3F51B5", "#2196F3", "#03A9F4", "#00BCD4"),
                arrayOf("#009688", "#4CAF50", "#8BC34A", "#CDDC39"),
                arrayOf("#FFEB3B", "#FFC107", "#FF9800", "#795548")
            )
            for (row in presets) {
                for (color in row) {
                    val btn = Button(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(80, 80)
                        setBackgroundColor(Color.parseColor(color))
                        setOnClickListener { edits[i].setText(color) }
                    }
                    presetRow.addView(btn)
                }
            }
            layout.addView(presetRow)
        }

        val finalColors = colors
        AlertDialog.Builder(requireContext())
            .setTitle("自定义颜色 - 点击色块快速选择")
            .setView(layout)
            .setPositiveButton("应用") { _, _ ->
                finalColors.primary = edits[0].text.toString()
                finalColors.accent = edits[1].text.toString()
                finalColors.background = edits[2].text.toString()
                finalColors.text = edits[3].text.toString()
                finalColors.card = edits[4].text.toString()
                finalColors.border = edits[5].text.toString()
                finalColors.success = edits[6].text.toString()
                finalColors.warning = edits[7].text.toString()
                settings.customColors = finalColors
                settings.uiTheme = AppSettings.THEME_CUSTOM
                save()
                previousTheme = "_force_"
                applyTheme()
                Toast.makeText(requireContext(), "颜色已应用", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyTheme() {
        prefManager.saveSettings(settings)
        activity?.let { act ->
            val themeValue = settings.uiTheme
            ThemeManager.setTheme(act, themeValue)
            val intent = android.content.Intent(act, com.radio.app.activities.MainActivity::class.java)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            act.startActivity(intent)
            act.finish()
            act.overridePendingTransition(0, 0)
        }
    }

    private fun save() {
        prefManager.saveSettings(settings)
        // 同步关键字段到 radio_app_settings，让 RadioPlaybackService 的热切换监听器生效
        // （PreferenceManager 使用 radio_app_prefs 文件，而服务监听器注册在 radio_app_settings 上）
        try {
            requireContext().getSharedPreferences("radio_app_settings", Context.MODE_PRIVATE)
                .edit()
                .putString("notification_style", settings.notificationStyle)
                .putInt("skip_seconds", settings.skipSeconds)
                .putBoolean("preload_cache", settings.autoCache)
                .putBoolean("auto_cache", settings.autoCache)
                .putBoolean("enable_preprocessing", settings.enablePreprocessing)
                .putInt("preload_cache_count", settings.preloadCacheCount)
                .putBoolean("continuous_play", settings.continuousPlay)
                .putBoolean("wifi_only_precache", settings.wifiOnlyPreCache)
                // [v2.0.89] Issue 4 Fix: Sync asr_provider and vosk_model_dir to radio_app_settings.
                // Without this, AppSettings.load() reads the OLD asr_provider from radio_app_settings
                // (e.g., "whisper-local") even after user changed to "vosk-local" in Settings UI.
                // This was the root cause of "user selected Vosk but got Whisper prompts".
                .putString("asr_provider", settings.asrProvider)
                .putString("vosk_model_dir", settings.voskModelDir)
                .apply()
            Log.d("SettingsFragment", "Synced settings to radio_app_settings for hot-switch (incl. asr_provider=${settings.asrProvider})")
        } catch (e: Exception) {
            Log.e("SettingsFragment", "Failed to sync to radio_app_settings", e)
        }
    }
}
