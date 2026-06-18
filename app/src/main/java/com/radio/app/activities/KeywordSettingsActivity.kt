package com.radio.app.activities

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    private fun parseKeywords(text: String?): List<String> {
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
