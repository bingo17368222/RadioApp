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

        val asrProviderLabels = mutableListOf("百度语音", "FunASR", "Whisper在线", "本地Vosk")
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

            val voskModels = arrayOf(
                "vosk-small-cn" to "本地Vosk 中文",
                "vosk-small-en" to "本地Vosk 英文"
            )
            for ((dir, label) in voskModels) {
                val modelDir = File(modelsDir, dir)
                if (modelDir.exists()) {
                    val totalSize = calculateDirSize(modelDir)
                    if (totalSize >= 1024 * 1024) {
                        adapter.add(label)
                    }
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
                val providerId = when {
                    selected.startsWith("本地Whisper") -> "whisper-local"
                    selected.startsWith("本地Vosk") -> "vosk-local"
                    else -> {
                        val providers = arrayOf(AppSettings.ASR_BAIDU, AppSettings.ASR_FUNASR, AppSettings.ASR_WHISPER, AppSettings.ASR_VOSK)
                        if (position < providers.size) providers[position] else selected
                    }
                }
                settings.asrProvider = providerId
                save()
                Toast.makeText(requireContext(), "ASR方案已切换: $selected", Toast.LENGTH_SHORT).show()
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
        val index = asrProviders.indexOfFirst { it == savedProvider }
        val adapter = binding.spinnerAsrProvider.adapter as? ArrayAdapter<*>
        if (index >= 0) {
            binding.spinnerAsrProvider.setSelection(index)
        } else if (savedProvider == "whisper-local") {
            val whisperIndex = (0 until (adapter?.count ?: 0)).indexOfFirst {
                adapter?.getItem(it)?.toString()?.startsWith("本地Whisper") == true
            }
            if (whisperIndex >= 0) binding.spinnerAsrProvider.setSelection(whisperIndex)
        } else if (savedProvider == "vosk-local") {
            val voskIndex = (0 until (adapter?.count ?: 0)).indexOfFirst {
                adapter?.getItem(it)?.toString()?.startsWith("本地Vosk") == true
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
        ".mp3", ".mp4", ".m4a", ".aac", ".wav", ".ogg", ".flac", ".wma",
        ".m3u8", ".ts", ".m3u", ".opus", ".amr", ".mid", ".midi"
    )

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
            adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_list_item_multiple_choice, fileNames)
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
            val settings = AppSettings.getInstance(requireContext())
            // First deselect all
            for (i in checked.indices) { checked[i] = false; listView.setItemChecked(i, false) }

            // Build set of disliked episodes' cache file names from ALL available lists
            // (episode_list 仅包含当天节目，precache_list 包含跨天预缓存节目，两者合并才能覆盖所有缓存文件)
            val dislikedFileNames = mutableSetOf<String>()

            // 从 audioUrl 提取缓存文件名（与 RadioPlaybackService.extractCacheFileName 逻辑一致）
            fun extractFileName(audioUrl: String): String {
                return try {
                    val path = java.net.URL(audioUrl).path
                    val name = path.substringAfterLast("/")
                    if (name.isBlank()) "unknown.mp4" else name
                } catch (e: Exception) {
                    val name = audioUrl.substringAfterLast("/")
                    if (name.isBlank()) "unknown.mp4" else name
                }
            }

            // 处理单个 Episode：判断是否不喜欢，若是则记录其缓存文件名
            fun processEpisode(id: String, stationId: String, title: String, audioUrl: String) {
                if (audioUrl.isBlank()) return
                val isDisliked = (id.isNotBlank() && settings.isDisliked(id)) ||
                        settings.isDislikedByTitle(stationId, title)
                if (isDisliked) {
                    dislikedFileNames.add(extractFileName(audioUrl))
                }
            }

            // 1) 读取 episode_list（当天节目列表，Gson 序列化，key="list"）
            try {
                val episodePrefs = requireContext().getSharedPreferences("episode_list", android.content.Context.MODE_PRIVATE)
                episodePrefs.getString("list", null)?.let { json ->
                    val gson = com.google.gson.Gson()
                    val type = object : com.google.gson.reflect.TypeToken<List<com.radio.app.models.Episode>>() {}.type
                    val episodes: List<com.radio.app.models.Episode> = gson.fromJson(json, type)
                    for (ep in episodes) {
                        processEpisode(ep.id ?: "", ep.stationId ?: "", ep.title ?: "", ep.audioUrl ?: "")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SettingsFragment", "Failed to parse episode_list", e)
            }

            // 2) 读取 precache_list（跨天预缓存列表，org.json.JSONArray 序列化，key="episodes"）
            // 注意：precache_list 使用 snake_case 字段名(audio_url/station_id 等)，不能用 Gson 直接解析
            try {
                val preCachePrefs = requireContext().getSharedPreferences("precache_list", android.content.Context.MODE_PRIVATE)
                preCachePrefs.getString("episodes", null)?.let { json ->
                    val arr = org.json.JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        processEpisode(
                            obj.optString("id", ""),
                            obj.optString("station_id", ""),
                            obj.optString("title", ""),
                            obj.optString("audio_url", "")
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SettingsFragment", "Failed to parse precache_list", e)
            }

            // 3) 读取 episode_list_cache（PlayerActivity 缓存的节目列表，org.json.JSONArray，key="episodes"）
            //    可能包含与 episode_list/precache_list 不同日期的节目
            try {
                val episodeCachePrefs = requireContext().getSharedPreferences("episode_list_cache", android.content.Context.MODE_PRIVATE)
                episodeCachePrefs.getString("episodes", null)?.let { json ->
                    val arr = org.json.JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        processEpisode(
                            obj.optString("id", ""),
                            obj.optString("station_id", ""),
                            obj.optString("title", ""),
                            obj.optString("audio_url", "")
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SettingsFragment", "Failed to parse episode_list_cache", e)
            }

            // 4) 读取 last_episode（最近播放的节目，可能来自任意日期）
            try {
                val lastEpPrefs = requireContext().getSharedPreferences("last_episode", android.content.Context.MODE_PRIVATE)
                val lastAudioUrl = lastEpPrefs.getString("audio_url", null)
                if (!lastAudioUrl.isNullOrBlank()) {
                    processEpisode(
                        lastEpPrefs.getString("episode_id", "") ?: "",
                        lastEpPrefs.getString("station_id", "") ?: "",
                        lastEpPrefs.getString("title", "") ?: "",
                        lastAudioUrl
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("SettingsFragment", "Failed to parse last_episode", e)
            }

            // 5) 读取 playback_positions 的所有键（stationId::title），识别按标题不喜欢的节目
            //    playback_positions 记录了所有播放过的节目（跨天），可用于发现不在任何列表中的不喜欢节目
            try {
                val posPrefs = requireContext().getSharedPreferences("playback_positions", android.content.Context.MODE_PRIVATE)
                val allKeys = posPrefs.all.keys
                var dislikedByKeyCount = 0
                for (key in allKeys) {
                    // 键格式为 "stationId::title"
                    val parts = key.split("::", limit = 2)
                    if (parts.size == 2) {
                        val stationId = parts[0]
                        val title = parts[1]
                        if (settings.isDislikedByTitle(stationId, title)) {
                            dislikedByKeyCount++
                            // Search all_episodes for this episode to get its audioUrl
                            val allEpPrefs = requireContext().getSharedPreferences("all_episodes", android.content.Context.MODE_PRIVATE)
                            for ((_, value) in allEpPrefs.all) {
                                if (value !is String) continue
                                try {
                                    val ep = com.google.gson.Gson().fromJson(value, com.radio.app.models.Episode::class.java) ?: continue
                                    if (ep.stationId == stationId && ep.title == title && !ep.audioUrl.isNullOrBlank()) {
                                        val fileName = try {
                                            java.net.URL(ep.audioUrl).path.substringAfterLast("/")
                                        } catch (e: Exception) {
                                            ep.audioUrl.substringAfterLast("/")
                                        }
                                        if (fileName.isNotBlank()) {
                                            dislikedFileNames.add(fileName)
                                        }
                                        break
                                    }
                                } catch (e: Exception) { /* skip */ }
                            }
                        }
                    }
                }
                android.util.Log.d("SettingsFragment", "Dislike filter: playback_positions has ${allKeys.size} keys, $dislikedByKeyCount are disliked by title, added to dislikedFileNames")
            } catch (e: Exception) {
                android.util.Log.e("SettingsFragment", "Failed to scan playback_positions", e)
            }

            // 6) 读取 all_episodes 持久化存储（跨天累积的所有节目）
            try {
                val allEpPrefs = requireContext().getSharedPreferences("all_episodes", android.content.Context.MODE_PRIVATE)
                val allEntries = allEpPrefs.all
                var allEpCount = 0
                for ((_, value) in allEntries) {
                    if (value !is String) continue
                    try {
                        val ep = com.google.gson.Gson().fromJson(value, com.radio.app.models.Episode::class.java)
                        if (ep != null && ep.audioUrl != null) {
                            processEpisode(ep.id ?: "", ep.stationId ?: "", ep.title ?: "", ep.audioUrl ?: "")
                            allEpCount++
                        }
                    } catch (e: Exception) { /* skip malformed entries */ }
                }
                android.util.Log.d("SettingsFragment", "Dislike filter: loaded $allEpCount episodes from all_episodes persistent store")
            } catch (e: Exception) {
                android.util.Log.e("SettingsFragment", "Failed to read all_episodes", e)
            }

            // 7) 终极方案：直接遍历 all_episodes 找所有 disliked episode，
            //    然后对每个 cache file 反向查找是否匹配任何 disliked episode 的 audioUrl
            try {
                val allEpPrefs = requireContext().getSharedPreferences("all_episodes", android.content.Context.MODE_PRIVATE)
                val allEntries = allEpPrefs.all
                // Build a map from cache file name → disliked status
                for ((_, value) in allEntries) {
                    if (value !is String) continue
                    try {
                        val ep = com.google.gson.Gson().fromJson(value, com.radio.app.models.Episode::class.java) ?: continue
                        if (ep.audioUrl.isNullOrBlank()) continue
                        // Check if this episode is disliked
                        val isDisliked = (ep.id != null && ep.id.isNotBlank() && settings.isDisliked(ep.id)) ||
                                settings.isDislikedByTitle(ep.stationId ?: "", ep.title ?: "")
                        if (isDisliked) {
                            // Extract the file name part of the URL
                            val audioFileName = try {
                                val path = java.net.URL(ep.audioUrl).path
                                path.substringAfterLast("/")
                            } catch (e: Exception) {
                                ep.audioUrl.substringAfterLast("/")
                            }
                            if (audioFileName.isNotBlank()) {
                                dislikedFileNames.add(audioFileName)
                            }
                        }
                    } catch (e: Exception) { /* skip */ }
                }
                android.util.Log.d("SettingsFragment", "Dislike filter: after reverse scan, total disliked file names=${dislikedFileNames.size}")
            } catch (e: Exception) {
                android.util.Log.e("SettingsFragment", "Failed reverse scan", e)
            }

            // 8) 读取 cache_episode_mapping（缓存文件→节目映射，最可靠的数据源）
            //    下载时直接保存了 cacheFileName → episode JSON 的映射，不受跨天限制
            try {
                val mappingPrefs = requireContext().getSharedPreferences("cache_episode_mapping", android.content.Context.MODE_PRIVATE)
                var mappingCount = 0
                for ((cacheFileName, value) in mappingPrefs.all) {
                    if (value !is String) continue
                    try {
                        val ep = com.google.gson.Gson().fromJson(value, com.radio.app.models.Episode::class.java) ?: continue
                        val isDisliked = (ep.id != null && ep.id.isNotBlank() && settings.isDisliked(ep.id)) ||
                                settings.isDislikedByTitle(ep.stationId ?: "", ep.title ?: "")
                        if (isDisliked) {
                            dislikedFileNames.add(cacheFileName)
                            mappingCount++
                        }
                    } catch (e: Exception) { /* skip */ }
                }
                android.util.Log.d("SettingsFragment", "Dislike filter: cache_episode_mapping has ${mappingPrefs.all.size} entries, $mappingCount are disliked")
            } catch (e: Exception) {
                android.util.Log.e("SettingsFragment", "Failed to read cache_episode_mapping", e)
            }

            android.util.Log.d("SettingsFragment", "Dislike filter: found ${dislikedFileNames.size} disliked file names: $dislikedFileNames")
            android.util.Log.d("SettingsFragment", "Cache files count: ${files.size}, names: ${files.map { it.name }}")
            android.util.Log.d("SettingsFragment", "Dislike filter: total disliked file names=${dislikedFileNames.size}, cache files=${files.size}")

            // Select files that match disliked episodes
            for (i in files.indices) {
                if (files[i].name in dislikedFileNames) {
                    checked[i] = true
                    listView.setItemChecked(i, true)
                }
            }

            val selectedCount = checked.count { it }
            Toast.makeText(requireContext(), "已选中${selectedCount}个不喜欢节目的缓存", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
        dialog.window?.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun playPcmFile(pcmFile: File) {
        pcmPlaybackActive = false
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null

        try {
            val pcmData = pcmFile.readBytes()

            // Read sample rate and channels from .info file
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
            val bytesPerFrame = channels * 2 // 16-bit
            val expectedDurationSec = pcmData.size / (sampleRate * bytesPerFrame)
            android.util.Log.d("SettingsFragment", "playPcmFile: ${pcmFile.name}, size=${pcmData.size}, sampleRate=$sampleRate, channels=$channels, expected=${expectedDurationSec}s")
            
            val bufferSize = maxOf(
                android.media.AudioTrack.getMinBufferSize(sampleRate, channelMask, android.media.AudioFormat.ENCODING_PCM_16BIT),
                4096
            )
            
            // Use MODE_STREAM for files larger than 10MB to avoid OOM
            val useStream = pcmData.size > 10 * 1024 * 1024
            
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
                if (useStream) bufferSize else maxOf(bufferSize, pcmData.size),
                if (useStream) android.media.AudioTrack.MODE_STREAM else android.media.AudioTrack.MODE_STATIC,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            
            pcmPlaybackActive = true
            if (useStream) {
                audioTrack?.play()
                Thread {
                    var offset = 0
                    while (offset < pcmData.size && pcmPlaybackActive && audioTrack != null) {
                        try {
                            val track = audioTrack ?: break
                            val written = track.write(pcmData, offset, minOf(bufferSize, pcmData.size - offset))
                            if (written <= 0) break
                            offset += written
                        } catch (e: Exception) { break }
                    }
                }.start()
            } else {
                audioTrack?.write(pcmData, 0, pcmData.size)
                audioTrack?.play()
            }
            
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
            adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_list_item_multiple_choice, fileNames)
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
                btnPlay.text = "播放选中"
            } else {
                if (selectedFiles.isNotEmpty()) {
                    playPcmFile(selectedFiles.first())
                    btnPlay.text = "停止播放"
                } else {
                    Toast.makeText(requireContext(), "请先选择一个文件", Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnStop.setOnClickListener {
            stopPcmPlayback()
            btnPlay.text = "播放选中"
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
                .apply()
            Log.d("SettingsFragment", "Synced settings to radio_app_settings for hot-switch")
        } catch (e: Exception) {
            Log.e("SettingsFragment", "Failed to sync to radio_app_settings", e)
        }
    }
}
