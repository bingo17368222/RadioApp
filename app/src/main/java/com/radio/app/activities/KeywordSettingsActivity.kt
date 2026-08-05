package com.radio.app.activities

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.radio.app.R
import com.radio.app.models.AppSettings
import com.radio.app.utils.PreferenceManager

/**
 * 水货分段开头/结尾组合管理页面。
 * 管理用于字幕提取时判断水货分段的开头/结尾关键词组合。
 */
class KeywordSettingsActivity : AppCompatActivity() {

    private lateinit var prefMgr: PreferenceManager
    private lateinit var settings: AppSettings
    private lateinit var tvTitle: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var etCombinationStart: EditText
    private lateinit var etCombinationEnd: EditText
    private lateinit var chipGroupCombinations: ChipGroup
    private lateinit var tvCombinationsEmpty: TextView
    private val waterCombinationList: MutableList<Pair<String, String>> = mutableListOf()

    private val uiHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "KeywordSettingsActivity"

        /**
         * v3.0.2: 格式化毫秒为 mm:ss.S 格式（供 FingerprintGroupActivity 等外部使用）。
         */
        @JvmStatic
        fun formatMsStatic(ms: Long): String {
            val totalSec = ms / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            val tenths = (ms % 1000) / 100
            return "${String.format("%02d", min)}:${String.format("%02d", sec)}.${tenths}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyword_settings)

        tvTitle = findViewById(R.id.tv_title)
        btnBack = findViewById(R.id.btn_back)
        tvTitle.text = "水货分段组合管理"
        btnBack.setOnClickListener { finish() }

        prefMgr = PreferenceManager(this)
        settings = prefMgr.loadSettings()

        // v3.1.44: 恢复原始水分指纹音频素材列表UI入口，跳转到指纹管理页面
        findViewById<TextView>(R.id.tv_fingerprint_entry).setOnClickListener {
            startActivity(Intent(this, FingerprintManagementActivity::class.java))
        }

        initWaterCombinationManagement()
    }

    private fun initWaterCombinationManagement() {
        etCombinationStart = findViewById(R.id.et_combination_start)
        etCombinationEnd = findViewById(R.id.et_combination_end)
        chipGroupCombinations = findViewById(R.id.chip_group_combinations)
        tvCombinationsEmpty = findViewById(R.id.tv_combinations_empty)

        waterCombinationList.clear()
        waterCombinationList.addAll(settings.getWaterCombinations())

        findViewById<Button>(R.id.btn_add_combination).setOnClickListener { addWaterCombination() }
        etCombinationEnd.setOnEditorActionListener { _, _, _ -> addWaterCombination(); true }

        refreshCombinationChips()
    }

    private fun addWaterCombination() {
        val start = etCombinationStart.text.toString().trim()
        val end = etCombinationEnd.text.toString().trim()
        if (start.isEmpty() || end.isEmpty()) {
            Toast.makeText(this, "开头和结尾都不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        val combo = start to end
        if (waterCombinationList.contains(combo)) {
            Toast.makeText(this, "该组合已存在", Toast.LENGTH_SHORT).show()
            return
        }
        waterCombinationList.add(combo)
        settings.setWaterCombinations(this, waterCombinationList)
        etCombinationStart.text.clear()
        etCombinationEnd.text.clear()
        refreshCombinationChips()
        Toast.makeText(this, "已添加组合：$start ... $end", Toast.LENGTH_SHORT).show()
    }

    private fun removeWaterCombination(combo: Pair<String, String>) {
        waterCombinationList.remove(combo)
        settings.setWaterCombinations(this, waterCombinationList)
        refreshCombinationChips()
        Toast.makeText(this, "已删除组合：${combo.first} ... ${combo.second}", Toast.LENGTH_SHORT).show()
    }

    private fun refreshCombinationChips() {
        chipGroupCombinations.removeAllViews()
        for (combo in waterCombinationList) {
            val label = "${combo.first} ... ${combo.second}"
            chipGroupCombinations.addView(createKeywordChip(chipGroupCombinations, label) {
                removeWaterCombination(combo)
            })
        }
        tvCombinationsEmpty.visibility =
            if (waterCombinationList.isEmpty()) TextView.VISIBLE else TextView.GONE
    }

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
}