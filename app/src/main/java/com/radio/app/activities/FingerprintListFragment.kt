package com.radio.app.activities

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.radio.app.R
import com.radio.app.adapters.AudioFingerprintAdapter
import com.radio.app.adapters.AutomaticFingerprintAdapter
import com.radio.app.adapters.CandidateFingerprintAdapter
import com.radio.app.database.AudioFingerprint
import com.radio.app.database.RadioDatabaseHelper
import com.radio.app.database.RadioDatabaseHelper.ObservationPoolCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v3.2.3: 指纹列表 Fragment。
 * 根据 type 参数展示三类指纹列表：
 * - "manual"：人工指纹（金标准，isGoldStandard=true）
 * - "candidate"：候选指纹（观察池）
 * - "automatic"：自动指纹（自动晋升，isGoldStandard=false）
 *
 * 支持删除操作，在 onResume 中自动刷新数据。
 */
class FingerprintListFragment : Fragment() {

    companion object {
        private const val ARG_TYPE = "fingerprint_type"
        private const val TAG = "FingerprintListFragment"

        fun newInstance(type: String): FingerprintListFragment {
            return FingerprintListFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TYPE, type)
                }
            }
        }
    }

    private var fingerprintType: String = "manual"
    private lateinit var dbHelper: RadioDatabaseHelper
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvCount: TextView

    // 三种适配器，按需初始化
    private var audioFingerprintAdapter: AudioFingerprintAdapter? = null
    private var candidateAdapter: CandidateFingerprintAdapter? = null
    private var automaticAdapter: AutomaticFingerprintAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fingerprintType = arguments?.getString(ARG_TYPE, "manual") ?: "manual"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_fingerprint_list, container, false)
        recyclerView = view.findViewById(R.id.recycler_fingerprints)
        tvEmpty = view.findViewById(R.id.tv_fingerprints_empty)
        tvCount = view.findViewById(R.id.tv_fingerprint_count)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dbHelper = RadioDatabaseHelper.getInstance(requireContext().applicationContext)
        setupRecyclerView()
        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    /**
     * 根据指纹类型初始化 RecyclerView 和适配器。
     */
    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        when (fingerprintType) {
            "manual" -> {
                audioFingerprintAdapter = AudioFingerprintAdapter().apply {
                    setOnDeleteListener { fp -> confirmDeleteFingerprint(fp) }
                }
                recyclerView.adapter = audioFingerprintAdapter
            }
            "candidate" -> {
                candidateAdapter = CandidateFingerprintAdapter().apply {
                    setOnDeleteListener { candidate -> confirmDeleteCandidate(candidate) }
                }
                recyclerView.adapter = candidateAdapter
            }
            "automatic" -> {
                automaticAdapter = AutomaticFingerprintAdapter().apply {
                    setOnDeleteListener { fp -> confirmDeleteFingerprint(fp) }
                }
                recyclerView.adapter = automaticAdapter
            }
        }
    }

    /**
     * 从数据库加载数据并刷新列表。
     */
    private fun loadData() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    loadDataSync()
                } catch (e: Exception) {
                    Log.e(TAG, "loadData failed for type=$fingerprintType: ${e.message}")
                    null
                }
            }

            if (result == null) {
                tvEmpty.visibility = TextView.VISIBLE
                tvEmpty.text = "加载失败"
                recyclerView.visibility = RecyclerView.GONE
                return@launch
            }

            when (result) {
                is ManualResult -> {
                    audioFingerprintAdapter?.setItems(result.fingerprints)
                    updateEmptyState(result.fingerprints.isEmpty())
                    tvCount.text = "共 ${result.fingerprints.size} 条人工指纹"
                }
                is CandidateResult -> {
                    candidateAdapter?.setItems(result.candidates)
                    updateEmptyState(result.candidates.isEmpty())
                    tvCount.text = "共 ${result.candidates.size} 条候选指纹"
                }
                is AutomaticResult -> {
                    automaticAdapter?.setItems(result.fingerprints)
                    updateEmptyState(result.fingerprints.isEmpty())
                    tvCount.text = "共 ${result.fingerprints.size} 条自动指纹"
                }
            }
        }
    }

    /**
     * 在 IO 线程同步加载数据。
     */
    private fun loadDataSync(): Any {
        return when (fingerprintType) {
            "manual" -> {
                val all = dbHelper.getAllAudioFingerprints()
                val gold = all.filter { it.isGoldStandard }
                ManualResult(gold)
            }
            "candidate" -> {
                val candidates = dbHelper.getAllObservationPoolCandidates()
                CandidateResult(candidates)
            }
            "automatic" -> {
                val all = dbHelper.getAllAudioFingerprints()
                val auto = all.filter { !it.isGoldStandard }
                AutomaticResult(auto)
            }
            else -> ManualResult(emptyList())
        }
    }

    /**
     * 更新空状态显示。
     */
    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            tvEmpty.visibility = TextView.VISIBLE
            tvEmpty.text = when (fingerprintType) {
                "manual" -> "暂无人工指纹"
                "candidate" -> "暂无候选指纹"
                "automatic" -> "暂无自动指纹"
                else -> "暂无数据"
            }
            recyclerView.visibility = RecyclerView.GONE
        } else {
            tvEmpty.visibility = RecyclerView.GONE
            recyclerView.visibility = RecyclerView.VISIBLE
        }
    }

    // ===== 删除操作 =====

    /**
     * 确认删除音频指纹（人工 / 自动）。
     */
    private fun confirmDeleteFingerprint(fp: AudioFingerprint) {
        val label = when (fingerprintType) {
            "manual" -> "人工指纹"
            "automatic" -> "自动指纹"
            else -> "指纹"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("删除$label")
            .setMessage("确定删除该指纹吗？\n${fp.episodeId} [${formatMs(fp.startMs)}-${formatMs(fp.endMs)}]")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        try {
                            dbHelper.deleteAudioFingerprint(fp.id)
                        } catch (e: Exception) {
                            Log.e(TAG, "deleteAudioFingerprint failed: ${e.message}")
                            0
                        }
                    }
                    if (deleted > 0) {
                        Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                        loadData()
                    } else {
                        Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 确认删除候选指纹。
     */
    private fun confirmDeleteCandidate(candidate: ObservationPoolCandidate) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除候选指纹")
            .setMessage("确定删除该候选指纹吗？\n${candidate.episodeId} (${candidate.fingerprintHash.take(8)}...)")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        try {
                            dbHelper.deleteObservationPoolCandidate(candidate.id)
                            1
                        } catch (e: Exception) {
                            Log.e(TAG, "deleteObservationPoolCandidate failed: ${e.message}")
                            0
                        }
                    }
                    if (deleted > 0) {
                        Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                        loadData()
                    } else {
                        Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formatMs(ms: Long): String {
        val s = (ms / 1000).toInt()
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    // ===== 内部数据封装 =====

    private class ManualResult(val fingerprints: List<AudioFingerprint>)
    private class CandidateResult(val candidates: List<ObservationPoolCandidate>)
    private class AutomaticResult(val fingerprints: List<AudioFingerprint>)
}