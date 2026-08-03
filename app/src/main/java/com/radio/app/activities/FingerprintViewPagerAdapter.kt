package com.radio.app.activities

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * v3.2.3: ViewPager2 的 FragmentStateAdapter。
 * 管理三个指纹列表页签：人工指纹、候选指纹、自动指纹。
 */
class FingerprintViewPagerAdapter(fragmentActivity: FragmentActivity) :
    FragmentStateAdapter(fragmentActivity) {

    companion object {
        private const val TOTAL_PAGES = 3
    }

    override fun getItemCount(): Int = TOTAL_PAGES

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> FingerprintListFragment.newInstance("manual")
            1 -> FingerprintListFragment.newInstance("candidate")
            2 -> FingerprintListFragment.newInstance("automatic")
            else -> FingerprintListFragment.newInstance("manual")
        }
    }
}