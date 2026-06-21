package com.radio.app.fragments

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import android.util.Log
import com.radio.app.R
import com.radio.app.activities.OfflineEngineActivity
import com.radio.app.models.AppSettings
import com.radio.app.utils.PreferenceManager
import com.radio.app.utils.ThemeManager
import java.io.File

class SettingsFragment : Fragment() {

    private lateinit var prefManager: PreferenceManager
    private lateinit var settings: AppSettings
    private var previousTheme: String? = null

    // Views
    private var rgTheme: RadioGroup? = null
    private var rbThemeDark: RadioButton? = null
    private var rbThemeFresh: RadioButton? = null
    private var rbThemeClassic: RadioButton? = null
    private var rbThemeMinimal: RadioButton? = null
    private var rbThemeCustom: RadioButton? = null
    private var customColorContainer: LinearLayout? = null
    private var btnPickColor: Button? = null
    private var cbAutoPlay: CheckBox? = null
    private var cbShowLyrics: CheckBox? = null
    private var tvCacheSize: TextView? = null
    private var btnClearCache: Button? = null
    private var btnManageOffline: Button? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)
        prefManager = PreferenceManager(requireContext())
        settings = prefManager.loadSettings()
        previousTheme = settings.uiTheme

        initViews(view)
        setupListeners()
        updateUI()
        return view
    }

    private fun initViews(view: View) {
        rgTheme = view.findViewById(R.id.rg_theme)
        rbThemeDark = view.findViewById(R.id.rb_theme_dark)
        rbThemeFresh = view.findViewById(R.id.rb_theme_fresh)
        rbThemeClassic = view.findViewById(R.id.rb_theme_classic)
        rbThemeMinimal = view.findViewById(R.id.rb_theme_minimal)
        rbThemeCustom = view.findViewById(R.id.rb_theme_custom)
        customColorContainer = view.findViewById(R.id.custom_color_container)
        btnPickColor = view.findViewById(R.id.btn_pick_color)
        cbAutoPlay = view.findViewById(R.id.cb_auto_play)
        cbShowLyrics = view.findViewById(R.id.cb_show_lyrics)
        tvCacheSize = view.findViewById(R.id.tv_cache_size)
        btnClearCache = view.findViewById(R.id.btn_clear_cache)
        btnManageOffline = view.findViewById(R.id.btn_manage_offline)
    }

    private fun setupListeners() {
        rgTheme?.setOnCheckedChangeListener { _, checkedId ->
            val selectedTheme = when (checkedId) {
                R.id.rb_theme_dark -> AppSettings.THEME_DARK
                R.id.rb_theme_fresh -> AppSettings.THEME_FRESH
                R.id.rb_theme_classic -> AppSettings.THEME_CLASSIC
                R.id.rb_theme_minimal -> AppSettings.THEME_MINIMAL
                R.id.rb_theme_custom -> AppSettings.THEME_CUSTOM
                else -> AppSettings.THEME_FRESH
            }
            if (selectedTheme != settings.uiTheme) {
                settings.uiTheme = selectedTheme
                save()
                applyTheme()
            }
        }

        cbAutoPlay?.setOnCheckedChangeListener { _, isChecked ->
            settings.autoSkipWater = isChecked
            save()
        }

        cbShowLyrics?.setOnCheckedChangeListener { _, isChecked ->
            settings.continuousPlay = isChecked
            save()
        }

        btnPickColor?.setOnClickListener { showColorPickerDialog() }
        btnClearCache?.setOnClickListener { showClearCacheDialog() }
        btnManageOffline?.setOnClickListener {
            startActivity(Intent(requireContext(), OfflineEngineActivity::class.java))
        }
    }

    private fun updateUI() {
        cbAutoPlay?.isChecked = settings.autoSkipWater
        cbShowLyrics?.isChecked = settings.continuousPlay

        // 设置主题选中
        val themeId = when (settings.uiTheme) {
            AppSettings.THEME_DARK -> R.id.rb_theme_dark
            AppSettings.THEME_FRESH -> R.id.rb_theme_fresh
            AppSettings.THEME_CLASSIC -> R.id.rb_theme_classic
            AppSettings.THEME_MINIMAL -> R.id.rb_theme_minimal
            AppSettings.THEME_CUSTOM -> R.id.rb_theme_custom
            else -> R.id.rb_theme_fresh
        }
        rgTheme?.check(themeId)

        // 显示/隐藏自定义颜色容器
        customColorContainer?.visibility = if (settings.uiTheme == AppSettings.THEME_CUSTOM) View.VISIBLE else View.GONE

        // 更新缓存大小
        val cacheSize = calculateCacheSize()
        tvCacheSize?.text = "缓存大小: " + formatSize(cacheSize)
    }

    private fun calculateCacheSize(): Long {
        val cacheDir = requireContext().cacheDir
        return calculateDirSize(cacheDir)
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
        val cacheDir = requireContext().cacheDir
        val allFiles = mutableListOf<File>()
        scanFilesRecursive(cacheDir, allFiles)

        if (allFiles.isEmpty()) {
            Toast.makeText(requireContext(), "暂无缓存文件", Toast.LENGTH_SHORT).show()
            return
        }

        val files = allFiles.toTypedArray()
        val fileNames = Array(files.size) { i ->
            files[i].absolutePath.replace(cacheDir.absolutePath, "...") + " (" + formatSize(files[i].length()) + ")"
        }
        val checked = BooleanArray(files.size) { true }

        showClearCacheDialogWithButtons(files, fileNames, checked)
    }

    private fun showClearCacheDialogWithButtons(files: Array<File>, fileNames: Array<String>, checked: BooleanArray) {
        val btnContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 10, 20, 10)
        }

        val btnSelectAll = Button(requireContext()).apply { text = "全选" }
        val btnSelectNone = Button(requireContext()).apply { text = "全不选" }
        val btnInvert = Button(requireContext()).apply { text = "反选" }

        btnContainer.addView(btnSelectAll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnContainer.addView(btnSelectNone, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnContainer.addView(btnInvert, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("选择要删除的缓存文件 (" + files.size + "个)")
            .setMultiChoiceItems(fileNames, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
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

        btnSelectAll.setOnClickListener {
            for (i in checked.indices) {
                checked[i] = true
                dialog.listView.setItemChecked(i, true)
            }
        }
        btnSelectNone.setOnClickListener {
            for (i in checked.indices) {
                checked[i] = false
                dialog.listView.setItemChecked(i, false)
            }
        }
        btnInvert.setOnClickListener {
            for (i in checked.indices) {
                checked[i] = !checked[i]
                dialog.listView.setItemChecked(i, checked[i])
            }
        }

        dialog.setView(btnContainer)
        dialog.show()
    }

    private fun scanFilesRecursive(dir: File?, result: MutableList<File>) {
        val files = dir?.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) scanFilesRecursive(f, result)
            else result.add(f)
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

        val typedValue = android.util.TypedValue()
        val theme = requireContext().theme
        val textPrimaryColor = if (theme.resolveAttribute(R.attr.appTextPrimary, typedValue, true)) typedValue.data else 0

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
    }
}
