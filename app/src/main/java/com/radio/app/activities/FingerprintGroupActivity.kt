package com.radio.app.activities

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.radio.app.R
import com.radio.app.adapters.FingerprintGroupAdapter
import com.radio.app.database.AudioFingerprint
import com.radio.app.database.RadioDatabaseHelper
import com.radio.app.utils.ChromaprintExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v3.1.7: 指纹分组管理页面。
 * 展示所有指纹分组，支持展开成员、移除成员、删除分组、重新计算分组、编辑备注。
 */
class FingerprintGroupActivity : AppCompatActivity() {

    private lateinit var dbHelper: RadioDatabaseHelper
    private lateinit var recyclerGroups: RecyclerView
    private lateinit var tvGroupCount: TextView
    private lateinit var tvGroupsEmpty: TextView
    private lateinit var btnRecompute: Button
    private lateinit var groupAdapter: FingerprintGroupAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fingerprint_group)

        val tvTitle = findViewById<TextView>(R.id.tv_title)
        tvTitle.text = "指纹分组管理"
        findViewById<android.widget.ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        dbHelper = RadioDatabaseHelper.getInstance(this)

        recyclerGroups = findViewById(R.id.recycler_fingerprint_groups)
        tvGroupCount = findViewById(R.id.tv_group_count)
        tvGroupsEmpty = findViewById(R.id.tv_groups_empty)
        btnRecompute = findViewById(R.id.btn_recompute_groups)

        groupAdapter = FingerprintGroupAdapter(dbHelper)
        recyclerGroups.layoutManager = LinearLayoutManager(this)
        recyclerGroups.adapter = groupAdapter

        groupAdapter.setOnDeleteGroupListener { group ->
            AlertDialog.Builder(this)
                .setTitle("删除分组")
                .setMessage("确定删除分组「${group.name}」吗？删除后组内指纹不会被删除。")
                .setPositiveButton("删除") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            dbHelper.deleteFingerprintGroup(group.groupId)
                        }
                        loadGroups()
                        Toast.makeText(this@FingerprintGroupActivity, "已删除分组", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // v3.1.7: 编辑分组备注
        groupAdapter.setOnEditNoteListener { group ->
            showEditGroupNoteDialog(group)
        }

        groupAdapter.setOnRemoveMemberListener { group, fingerprint ->
            AlertDialog.Builder(this)
                .setTitle("移除成员")
                .setMessage("确定从分组「${group.name}」中移除该指纹吗？")
                .setPositiveButton("移除") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            // 从数据库中查找 member 记录并标记为移除
                            val members = dbHelper.getGroupMembers(group.groupId)
                            val member = members.find { it.fingerprintId == fingerprint.id }
                            if (member != null) {
                                dbHelper.removeGroupMember(member.id)
                            }
                        }
                        loadGroups()
                        Toast.makeText(this@FingerprintGroupActivity, "已移除成员", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // v3.1.11: 成员备注编辑
        groupAdapter.setOnEditMemberNoteListener { group, fingerprint ->
            showEditMemberNoteDialog(group, fingerprint)
        }

        btnRecompute.setOnClickListener {
            recomputeGroups()
        }

        loadGroups()
    }

    /**
     * v3.1.7: 显示编辑分组备注对话框。
     */
    private fun showEditGroupNoteDialog(group: FingerprintGroupAdapter.GroupItem) {
        val editText = EditText(this).apply {
            setText(group.note)
            hint = "输入分组备注..."
        }
        AlertDialog.Builder(this)
            .setTitle("编辑分组备注")
            .setMessage("分组: ${group.name}")
            .setView(editText, 32, 16, 32, 16)
            .setPositiveButton("保存") { _, _ ->
                val newNote = editText.text.toString().trim()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        dbHelper.updateGroupNote(group.groupId, newNote)
                    }
                    loadGroups()
                    Toast.makeText(this@FingerprintGroupActivity, "备注已保存", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 加载已保存的分组并显示。
     */
    private fun loadGroups() {
        lifecycleScope.launch {
            val groupItems = withContext(Dispatchers.IO) {
                buildGroupItemsFromDb()
            }
            if (groupItems.isEmpty()) {
                tvGroupCount.text = "暂无分组"
                tvGroupsEmpty.visibility = TextView.VISIBLE
                recyclerGroups.visibility = RecyclerView.GONE
            } else {
                tvGroupCount.text = "共 ${groupItems.size} 个分组，${groupItems.sumOf { it.members.size }} 个成员"
                tvGroupsEmpty.visibility = TextView.GONE
                recyclerGroups.visibility = RecyclerView.VISIBLE
            }
            groupAdapter.setGroups(groupItems)
        }
    }

    /**
     * v3.1.7: 从数据库读取已保存的分组数据，构造 GroupItem 列表（支持备注）。
     */
    private fun buildGroupItemsFromDb(): List<FingerprintGroupAdapter.GroupItem> {
        val allFps = dbHelper.getAllAudioFingerprints()
        val fpMap = allFps.associateBy { it.id }
        val dbGroups = dbHelper.getAllFingerprintGroups()

        return dbGroups.mapNotNull { group ->
            val members = dbHelper.getGroupMembers(group.id)
            val memberFps = members.mapNotNull { fpMap[it.fingerprintId] }
            if (memberFps.isEmpty()) return@mapNotNull null

            // 找代表指纹
            val repMember = members.find { it.isRepresentative }
            val representative = if (repMember != null) {
                fpMap[repMember.fingerprintId]
            } else {
                memberFps.firstOrNull()
            } ?: return@mapNotNull null

            // 计算相似度
            val parsedRep = ChromaprintExtractor.parseFingerprint(representative.fingerprint)
            val similarities = mutableMapOf<Long, Float>()
            val memberDbIds = mutableMapOf<Long, Long>()
            for (member in members) {
                val fp = fpMap[member.fingerprintId] ?: continue
                memberDbIds[fp.id] = member.id
                if (fp.id != representative.id) {
                    val parsedFp = ChromaprintExtractor.parseFingerprint(fp.fingerprint)
                    if (parsedRep.isNotEmpty() && parsedFp.isNotEmpty()) {
                        val result = ChromaprintExtractor.compareFingerprintArrays(parsedRep, parsedFp)
                        similarities[fp.id] = result.similarity
                    }
                }
            }

            FingerprintGroupAdapter.GroupItem(
                groupId = group.id,
                name = group.name,
                note = group.note,
                representative = representative,
                members = memberFps,
                memberSimilarities = similarities,
                memberDbIds = memberDbIds
            )
        }
    }

    /**
     * v3.1.11: 显示编辑成员备注对话框。
     */
    private fun showEditMemberNoteDialog(group: FingerprintGroupAdapter.GroupItem, fingerprint: AudioFingerprint) {
        val editText = EditText(this).apply {
            setText(fingerprint.note)
            hint = "输入该指纹备注..."
        }
        val timeStr = "${com.radio.app.activities.KeywordSettingsActivity.Companion.formatMsStatic(fingerprint.startMs)}-${com.radio.app.activities.KeywordSettingsActivity.Companion.formatMsStatic(fingerprint.endMs)}"
        AlertDialog.Builder(this)
            .setTitle("编辑指纹备注")
            .setMessage("分组: ${group.name}\n指纹: ${fingerprint.episodeId} [$timeStr]")
            .setView(editText, 32, 16, 32, 16)
            .setPositiveButton("保存") { _, _ ->
                val newNote = editText.text.toString().trim()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        dbHelper.updateFingerprintNote(fingerprint.id, newNote)
                    }
                    loadGroups()
                    Toast.makeText(this@FingerprintGroupActivity, "指纹备注已保存", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 重新计算指纹分组。
     * 使用 ChromaprintExtractor.buildFingerprintGroups 对现有指纹进行聚类，
     * 将结果保存到数据库并刷新显示。
     * v3.1.11: 保留旧分组备注（代表指纹不变则保留原备注），新分组自动复制代表指纹备注。
     */
    private fun recomputeGroups() {
        lifecycleScope.launch {
            val allFps = withContext(Dispatchers.IO) {
                dbHelper.getAllAudioFingerprints()
            }
            if (allFps.size < 2) {
                Toast.makeText(this@FingerprintGroupActivity, "至少需要2条指纹才能计算分组", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val progressDialog = android.app.ProgressDialog(this@FingerprintGroupActivity).apply {
                setMessage("正在计算指纹分组（${allFps.size}条指纹）...")
                setCancelable(false)
                show()
            }

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val parsedFps = allFps.map { ChromaprintExtractor.parseFingerprint(it.fingerprint) }
                    val groups = ChromaprintExtractor.buildFingerprintGroups(parsedFps)

                    // v3.1.11: 先保存旧分组代表指纹→备注的映射，用于保留备注
                    val oldGroups = dbHelper.getAllFingerprintGroups()
                    val oldRepToNote = mutableMapOf<Long, String>()  // rep_fingerprint_id -> note
                    for (oldGroup in oldGroups) {
                        val members = dbHelper.getGroupMembers(oldGroup.id)
                        val repMember = members.find { it.isRepresentative }
                        if (repMember != null) {
                            oldRepToNote[repMember.fingerprintId] = oldGroup.note
                        }
                    }
                    // 清除旧分组
                    dbHelper.clearAllGroups()

                    var savedCount = 0
                    for (group in groups) {
                        if (group.memberIndices.size < 2) continue // 只保存有2个以上成员的组
                        val rep = allFps[group.representativeIndex]
                        val groupStart = com.radio.app.activities.KeywordSettingsActivity.Companion.formatMsStatic(rep.startMs)
                        val groupEnd = com.radio.app.activities.KeywordSettingsActivity.Companion.formatMsStatic(rep.endMs)
                        val groupName = "分组 ${rep.episodeId} [$groupStart-$groupEnd]"

                        // v3.1.11: 确定分组备注
                        val groupNote = if (oldRepToNote.containsKey(rep.id)) {
                            // 代表指纹不变，保留旧分组备注
                            oldRepToNote[rep.id] ?: ""
                        } else if (rep.note.isNotEmpty()) {
                            // 新分组，代表指纹有备注，自动复制
                            rep.note
                        } else {
                            ""
                        }

                        val groupId = dbHelper.saveFingerprintGroup(
                            com.radio.app.database.FingerprintGroupInfo(
                                name = groupName.take(100),
                                note = groupNote
                            )
                        )
                        for (memberIdx in group.memberIndices) {
                            val fp = allFps[memberIdx]
                            dbHelper.addGroupMember(
                                groupId = groupId,
                                fingerprintId = fp.id,
                                isRepresentative = memberIdx == group.representativeIndex
                            )
                        }
                        savedCount++
                    }
                    savedCount
                }.getOrElse {
                    -1
                }
            }

            progressDialog.dismiss()

            if (result > 0) {
                Toast.makeText(this@FingerprintGroupActivity, "分组计算完成，共 $result 个分组", Toast.LENGTH_SHORT).show()
            } else if (result == 0) {
                Toast.makeText(this@FingerprintGroupActivity, "未找到可分组指纹（相似度≥95%的指纹对）", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@FingerprintGroupActivity, "分组计算失败", Toast.LENGTH_SHORT).show()
            }
            loadGroups()
        }
    }
}