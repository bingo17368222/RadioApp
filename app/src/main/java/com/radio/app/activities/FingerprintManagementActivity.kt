package com.radio.app.activities

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.radio.app.R

/**
 * v3.2.3: 指纹管理页面（Tab 布局）。
 * 使用 TabLayout + ViewPager2 展示三类指纹：
 * - 人工指纹（金标准）
 * - 候选指纹（观察池）
 * - 自动指纹（自动晋升，非金标准）
 * v3.1.42: 增加指纹分组管理入口按钮。
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
}