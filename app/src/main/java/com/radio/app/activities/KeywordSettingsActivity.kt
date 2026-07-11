package com.radio.app.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.radio.app.R
import com.radio.app.models.AppSettings
import com.radio.app.utils.PreferenceManager

class KeywordSettingsActivity : AppCompatActivity() {

    private lateinit var etDryKeywords: EditText
    private lateinit var etWaterKeywords: EditText
    private lateinit var etContentDryKeywords: EditText
    private lateinit var etContentWaterKeywords: EditText
    private lateinit var spinnerDryLogic: Spinner
    private lateinit var spinnerWaterLogic: Spinner
    private lateinit var spinnerContentDryLogic: Spinner
    private lateinit var spinnerContentWaterLogic: Spinner
    private lateinit var prefMgr: PreferenceManager
    private lateinit var settings: AppSettings
    private lateinit var tvTitle: TextView
    private lateinit var btnBack: ImageButton

    // [就AI听] 关键词管理（Chip 风格，可添加/删除）
    private lateinit var etDryKeywordInput: EditText
    private lateinit var etWaterKeywordInput: EditText
    private lateinit var chipGroupDry: ChipGroup
    private lateinit var chipGroupWater: ChipGroup
    private lateinit var tvDryKeywordsEmpty: TextView
    private lateinit var tvWaterKeywordsEmpty: TextView
    private val dryKeywordList: MutableList<String> = mutableListOf()
    private val waterKeywordList: MutableList<String> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyword_settings)

        tvTitle = findViewById(R.id.tv_title)
        btnBack = findViewById(R.id.btn_back)
        tvTitle.text = "关键词设置"
        btnBack.setOnClickListener { finish() }

        prefMgr = PreferenceManager(this)
        settings = prefMgr.loadSettings()

        etDryKeywords = findViewById(R.id.et_dry_keywords)
        etWaterKeywords = findViewById(R.id.et_water_keywords)
        etContentDryKeywords = findViewById(R.id.et_content_dry_keywords)
        etContentWaterKeywords = findViewById(R.id.et_content_water_keywords)
        spinnerDryLogic = findViewById(R.id.spinner_dry_logic)
        spinnerWaterLogic = findViewById(R.id.spinner_water_logic)
        spinnerContentDryLogic = findViewById(R.id.spinner_content_dry_logic)
        spinnerContentWaterLogic = findViewById(R.id.spinner_content_water_logic)

        val logicAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.logic_options,
            android.R.layout.simple_spinner_item
        )
        logicAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDryLogic.adapter = logicAdapter
        spinnerWaterLogic.adapter = logicAdapter
        spinnerContentDryLogic.adapter = logicAdapter
        spinnerContentWaterLogic.adapter = logicAdapter

        loadCurrentSettings()

        findViewById<Button>(R.id.btn_save).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.btn_ai_extract_dry).setOnClickListener { simulateAiExtract("dry") }
        findViewById<Button>(R.id.btn_ai_extract_water).setOnClickListener { simulateAiExtract("water") }
        findViewById<Button>(R.id.btn_ai_extract_content_dry).setOnClickListener { simulateAiExtract("content_dry") }
        findViewById<Button>(R.id.btn_ai_extract_content_water).setOnClickListener { simulateAiExtract("content_water") }

        // [就AI听] 关键词管理初始化
        initKeywordManagement()
    }

    // ==================== [就AI听] 关键词管理 ====================

    private fun initKeywordManagement() {
        etDryKeywordInput = findViewById(R.id.et_dry_keyword_input)
        etWaterKeywordInput = findViewById(R.id.et_water_keyword_input)
        chipGroupDry = findViewById(R.id.chip_group_dry_keywords)
        chipGroupWater = findViewById(R.id.chip_group_water_keywords)
        tvDryKeywordsEmpty = findViewById(R.id.tv_dry_keywords_empty)
        tvWaterKeywordsEmpty = findViewById(R.id.tv_water_keywords_empty)

        // 从 AppSettings（keyword_prefs）加载当前关键词
        dryKeywordList.clear()
        dryKeywordList.addAll(settings.getDryKeywords())
        waterKeywordList.clear()
        waterKeywordList.addAll(settings.getWaterKeywords())

        // 添加按钮
        findViewById<Button>(R.id.btn_add_dry_keyword).setOnClickListener { addDryKeyword() }
        findViewById<Button>(R.id.btn_add_water_keyword).setOnClickListener { addWaterKeyword() }

        // 回车即添加
        etDryKeywordInput.setOnEditorActionListener { _, _, _ -> addDryKeyword(); true }
        etWaterKeywordInput.setOnEditorActionListener { _, _, _ -> addWaterKeyword(); true }

        refreshDryChips()
        refreshWaterChips()
    }

    private fun addDryKeyword() {
        val kw = etDryKeywordInput.text.toString().trim()
        if (kw.isEmpty()) {
            Toast.makeText(this, "请输入关键词", Toast.LENGTH_SHORT).show()
            return
        }
        if (dryKeywordList.contains(kw)) {
            Toast.makeText(this, "该干货关键词已存在", Toast.LENGTH_SHORT).show()
            return
        }
        dryKeywordList.add(kw)
        settings.setDryKeywords(this, dryKeywordList)
        etDryKeywordInput.text.clear()
        refreshDryChips()
        Toast.makeText(this, "已添加干货关键词：$kw", Toast.LENGTH_SHORT).show()
    }

    private fun addWaterKeyword() {
        val kw = etWaterKeywordInput.text.toString().trim()
        if (kw.isEmpty()) {
            Toast.makeText(this, "请输入关键词", Toast.LENGTH_SHORT).show()
            return
        }
        if (waterKeywordList.contains(kw)) {
            Toast.makeText(this, "该水货关键词已存在", Toast.LENGTH_SHORT).show()
            return
        }
        waterKeywordList.add(kw)
        settings.setWaterKeywords(this, waterKeywordList)
        etWaterKeywordInput.text.clear()
        refreshWaterChips()
        Toast.makeText(this, "已添加水货关键词：$kw", Toast.LENGTH_SHORT).show()
    }

    private fun removeDryKeyword(kw: String) {
        dryKeywordList.remove(kw)
        settings.setDryKeywords(this, dryKeywordList)
        refreshDryChips()
        Toast.makeText(this, "已删除干货关键词：$kw", Toast.LENGTH_SHORT).show()
    }

    private fun removeWaterKeyword(kw: String) {
        waterKeywordList.remove(kw)
        settings.setWaterKeywords(this, waterKeywordList)
        refreshWaterChips()
        Toast.makeText(this, "已删除水货关键词：$kw", Toast.LENGTH_SHORT).show()
    }

    private fun refreshDryChips() {
        chipGroupDry.removeAllViews()
        for (kw in dryKeywordList) {
            chipGroupDry.addView(createKeywordChip(chipGroupDry, kw) { removeDryKeyword(kw) })
        }
        tvDryKeywordsEmpty.visibility =
            if (dryKeywordList.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun refreshWaterChips() {
        chipGroupWater.removeAllViews()
        for (kw in waterKeywordList) {
            chipGroupWater.addView(createKeywordChip(chipGroupWater, kw) { removeWaterKeyword(kw) })
        }
        tvWaterKeywordsEmpty.visibility =
            if (waterKeywordList.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    /**
     * 创建一个带关闭图标的可删除关键词 Chip（Entry 风格）。
     * @param parent 用于生成 LayoutParams 的 ChipGroup
     */
    private fun createKeywordChip(
        parent: android.view.ViewGroup,
        keyword: String,
        onClose: () -> Unit
    ): Chip {
        val chip = LayoutInflater.from(this)
            .inflate(R.layout.item_keyword_chip, parent, false) as? Chip
            ?: Chip(this).apply {
                setEnsureMinTouchTargetSize(false)
            }
        chip.text = keyword
        chip.isCloseIconVisible = true
        chip.setOnClickListener { /* 点击仅高亮，不删除 */ }
        chip.setOnCloseIconClickListener { onClose() }
        return chip
    }

    private fun loadCurrentSettings() {
        val config = settings.keywordConfig
        etDryKeywords.setText(config.dryKeywords.joinToString(","))
        etWaterKeywords.setText(config.waterKeywords.joinToString(","))
        etContentDryKeywords.setText(config.contentDryKeywords.joinToString(","))
        etContentWaterKeywords.setText(config.contentWaterKeywords.joinToString(","))

        setLogicSpinner(spinnerDryLogic, config.dryLogic)
        setLogicSpinner(spinnerWaterLogic, config.waterLogic)
        setLogicSpinner(spinnerContentDryLogic, config.contentDryLogic)
        setLogicSpinner(spinnerContentWaterLogic, config.contentWaterLogic)
    }

    private fun setLogicSpinner(spinner: Spinner, logic: String) {
        spinner.setSelection(if (logic == "and") 1 else 0)
    }

    private fun getLogicFromSpinner(spinner: Spinner): String {
        return if (spinner.selectedItemPosition == 1) "and" else "or"
    }

    private fun saveSettings() {
        val config = settings.keywordConfig
        config.dryKeywords = parseKeywords(etDryKeywords.text.toString())
        config.waterKeywords = parseKeywords(etWaterKeywords.text.toString())
        config.contentDryKeywords = parseKeywords(etContentDryKeywords.text.toString())
        config.contentWaterKeywords = parseKeywords(etContentWaterKeywords.text.toString())
        config.dryLogic = getLogicFromSpinner(spinnerDryLogic)
        config.waterLogic = getLogicFromSpinner(spinnerWaterLogic)
        config.contentDryLogic = getLogicFromSpinner(spinnerContentDryLogic)
        config.contentWaterLogic = getLogicFromSpinner(spinnerContentWaterLogic)
        settings.keywordConfig = config
        prefMgr.saveSettings(settings)
        Toast.makeText(this, "关键词设置已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun parseKeywords(text: String?): MutableList<String> {
        val list = mutableListOf<String>()
        if (text.isNullOrBlank()) return list
        val parts = text.split("[,，]".toRegex())
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) list.add(trimmed)
        }
        return list
    }

    private fun simulateAiExtract(type: String) {
        Toast.makeText(this, "AI提取功能（模拟）- $type", Toast.LENGTH_SHORT).show()
    }
}
