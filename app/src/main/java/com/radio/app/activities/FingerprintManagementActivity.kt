package com.radio.app.activities

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.radio.app.R
import com.radio.app.database.RadioDatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v3.2.3: 指纹管理页面（Tab 布局）。
 * 使用 TabLayout + ViewPager2 展示三类指纹：
 * - 人工指纹（金标准）
 * - 候选指纹（观察池）
 * - 自动指纹（自动晋升，非金标准）
 * v3.1.42: 增加指纹分组管理入口按钮。
 * v3.1.129: 增加一键清理过期指纹功能。
 */
class FingerprintManagementActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fingerprint_management)

        val tvTitle = findViewById<TextView>(R.id.tv_title)
        tvTitle.text = "指纹管理"

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        // v3.1.42: 指纹分组管理入口
        findViewById<TextView>(R.id.tv_group_management).setOnClickListener {
            startActivity(Intent(this, FingerprintGroupActivity::class.java))
        }

        // v3.1.129: 一键清理过期指纹
        findViewById<TextView>(R.id.tv_cleanup_expired).setOnClickListener {
            showCleanupExpiredDialog()
        }

        val tabLayout = findViewById<TabLayout>(R.id.tab_layout)
        val viewPager = findViewById<ViewPager2>(R.id.view_pager)

        val adapter = FingerprintViewPagerAdapter(this)
        viewPager.adapter = adapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "人工指纹"
                1 -> "候选指纹"
                2 -> "自动指纹"
                else -> ""
            }
        }.attach()
    }

    /**
     * v3.1.129: 显示过期指纹清理确认对话框。
     * 先统计过期指纹数量，再确认是否清理。
     * v3.1.133: 将数据库查询移至后台线程，消除主线程卡顿。
     */
    private fun showCleanupExpiredDialog() {
        val dbHelper = RadioDatabaseHelper.getInstance(applicationContext)
        lifecycleScope.launch {
            try {
                // 统计过期指纹数量 - 后台线程
                val expiredCount = withContext(Dispatchers.IO) {
                    val twoMonthsAgo = System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000
                    val allFps = dbHelper.getAllAudioFingerprints()
                    allFps.count { it.isGoldStandard && it.lastMatchedAt > 0 && it.lastMatchedAt < twoMonthsAgo }
                }

                if (expiredCount == 0) {
                    Toast.makeText(this@FingerprintManagementActivity, "没有过期指纹需要清理", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                AlertDialog.Builder(this@FingerprintManagementActivity)
                    .setTitle("清理过期指纹")
                    .setMessage("确定删除 $expiredCount 条过期指纹吗？\n（超过2个月未匹配的人工指纹）")
                    .setPositiveButton("删除") { _, _ ->
                        lifecycleScope.launch {
                            val deleted = withContext(Dispatchers.IO) {
                                dbHelper.cleanupExpiredFingerprints()
                            }
                            if (deleted > 0) {
                                Toast.makeText(this@FingerprintManagementActivity, "已删除 $deleted 条过期指纹", Toast.LENGTH_SHORT).show()
                                // 刷新当前显示的Fragment
                                supportFragmentManager.fragments.forEach { fragment ->
                                    if (fragment is FingerprintListFragment) {
                                        fragment.refreshData()
                                    }
                                }
                            } else {
                                Toast.makeText(this@FingerprintManagementActivity, "清理失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@FingerprintManagementActivity, "统计失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}