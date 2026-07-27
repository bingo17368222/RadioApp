package com.radio.app.activities

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.radio.app.R
import com.radio.app.adapters.AudioFingerprintAdapter
import com.radio.app.database.RadioDatabaseHelper
import com.radio.app.models.AppSettings
import com.radio.app.services.AudioFingerprintService
import com.radio.app.utils.PreferenceManager

/**
 * v3.0.2: 音频指纹管理页。
 * 展示用户通过“添加为水分指纹”保存的音频指纹素材，支持删除和修正（重新提取）。
 */
class KeywordSettingsActivity : AppCompatActivity() {

    private lateinit var prefMgr: PreferenceManager
    private lateinit var settings: AppSettings
    private lateinit var tvTitle: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var recyclerFingerprints: RecyclerView
    private lateinit var fingerprintAdapter: AudioFingerprintAdapter
    private lateinit var tvFingerprintCount: TextView
    private lateinit var tvFingerprintsEmpty: TextView

    // 水货分段开头/结尾组合管理（保留，供字幕提取水货组合使用）
    private lateinit var etCombinationStart: EditText
    private lateinit var etCombinationEnd: EditText
    private lateinit var chipGroupCombinations: ChipGroup
    private lateinit var tvCombinationsEmpty: TextView
    private val waterCombinationList: MutableList<Pair<String, String>> = mutableListOf()

    private val uiHandler = Handler(Looper.getMainLooper())
    private var reloadRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyword_settings)

        tvTitle = findViewById(R.id.tv_title)
        btnBack = findViewById(R.id.btn_back)
        tvTitle.text = "音频指纹管理"
        btnBack.setOnClickListener { finish() }

        prefMgr = PreferenceManager(this)
        settings = prefMgr.loadSettings()

        initFingerprintList()
        initWaterCombinationManagement()
    }

    override fun onResume() {
        super.onResume()
        loadFingerprints()
    }

    override fun onDestroy() {
        super.onDestroy()
        reloadRunnable?.let { uiHandler.removeCallbacks(it) }
    }

    // ==================== 音频指纹管理 ====================

    private fun initFingerprintList() {
        recyclerFingerprints = findViewById(R.id.recycler_audio_fingerprints)
        tvFingerprintCount = findViewById(R.id.tv_fingerprint_count)
        tvFingerprintsEmpty = findViewById(R.id.tv_fingerprints_empty)

        fingerprintAdapter = AudioFingerprintAdapter()
        recyclerFingerprints.layoutManager = LinearLayoutManager(this)
        recyclerFingerprints.adapter = fingerprintAdapter

        fingerprintAdapter.setOnDeleteListener { fp ->
            AlertDialog.Builder(this)
                .setTitle("删除音频指纹")
                .setMessage("确定删除该水分音频指纹素材吗？")
                .setPositiveButton("删除") { _, _ ->
                    try {
                        RadioDatabaseHelper.getInstance(this).deleteAudioFingerprint(fp.id)
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                        loadFingerprints()
                    } catch (e: Exception) {
                        Toast.makeText(this, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        fingerprintAdapter.setOnRefreshListener { fp ->
            // 修正：以原起止时间重新截取片段、提取指纹并更新数据库
            AudioFingerprintService.startAddFingerprint(
                this,
                episodeId = fp.episodeId,
                startMs = fp.startMs,
                endMs = fp.endMs,
                episodeTitle = null
            )
            Toast.makeText(this, "已开始修正指纹，请查看通知栏进度", Toast.LENGTH_SHORT).show()
            // 延迟刷新列表，等待后台服务完成
            reloadRunnable?.let { uiHandler.removeCallbacks(it) }
            reloadRunnable = Runnable { loadFingerprints() }
            uiHandler.postDelayed(reloadRunnable!!, 3000)
        }
    }

    private fun loadFingerprints() {
        try {
            val fingerprints = RadioDatabaseHelper.getInstance(this).getAllAudioFingerprints()
            fingerprintAdapter.setItems(fingerprints)
            tvFingerprintCount.text = "已保存 ${fingerprints.size} 条指纹"
            if (fingerprints.isEmpty()) {
                tvFingerprintsEmpty.visibility = TextView.VISIBLE
                recyclerFingerprints.visibility = RecyclerView.GONE
            } else {
                tvFingerprintsEmpty.visibility = TextView.GONE
                recyclerFingerprints.visibility = RecyclerView.VISIBLE
            }
        } catch (e: Exception) {
            Toast.makeText(this, "加载指纹失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 水货分段开头/结尾组合管理 ====================

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
