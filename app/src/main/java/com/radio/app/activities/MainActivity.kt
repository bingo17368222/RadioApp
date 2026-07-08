package com.radio.app.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.radio.app.R
import com.radio.app.fragments.HomeFragment
import com.radio.app.fragments.EpisodesFragment
import com.radio.app.fragments.SearchFragment
import com.radio.app.fragments.SettingsFragment
import com.radio.app.models.AppSettings
import com.radio.app.services.RadioPlaybackService
import com.radio.app.utils.ThemeManager
class MainActivity : AppCompatActivity() {

    var playbackService: RadioPlaybackService? = null
        private set
    var isServiceBound: Boolean = false
        private set

    private val navIds = intArrayOf(R.id.nav_home, R.id.nav_episodes, R.id.nav_search, R.id.nav_settings)
    private var currentNavId: Int = R.id.nav_episodes  // [v2.4.10] Default to 回放(Episodes) tab

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            playbackService = (service as? RadioPlaybackService.LocalBinder)?.getService()
            isServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 使用 ThemeManager 应用主题
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 请求存储权限（日志写入需要）
        requestStoragePermissionIfNeeded()

        // 自定义底部导航栏点击处理
        for (navId in navIds) {
            findViewById<View>(navId)?.setOnClickListener { view ->
                selectNav(view.id)
            }
        }

        if (savedInstanceState == null) {
            // [v2.4.10] Default to 回放(Episodes) tab on app launch
            loadFragment(EpisodesFragment())
            selectNav(R.id.nav_episodes)
        }

        bindService(
            Intent(this, RadioPlaybackService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        // 检查是否提示继续播放上次节目
        if (savedInstanceState == null) {
            checkLastEpisode()
        }
    }

    private fun checkLastEpisode() {
        val settings = AppSettings.getInstance(this)
        if (!settings.rememberLastEpisode) return
        val lastEpisode = RadioPlaybackService.getLastEpisode(this) ?: return
        val title = lastEpisode.title ?: "未知节目"
        AlertDialog.Builder(this)
            .setTitle("继续播放")
            .setMessage("是否继续播放上次的节目《${title}》？")
            .setPositiveButton("继续播放") { _, _ ->
                val intent = Intent(this, PlayerActivity::class.java).apply {
                    putExtra("episode", lastEpisode)
                    putExtra("is_live", false)
                }
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun selectNav(navId: Int) {
        currentNavId = navId
        val fragment: Fragment? = when (navId) {
            R.id.nav_home -> HomeFragment()
            R.id.nav_episodes -> EpisodesFragment()
            R.id.nav_search -> SearchFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> null
        }
        loadFragment(fragment)

        // 更新导航栏选中状态的颜色
        val activeColor = try {
            val tv = android.util.TypedValue()
            theme.resolveAttribute(R.attr.appNavActive, tv, true)
            tv.data
        } catch (e: Exception) {
            0xFF7ED321.toInt()
        }
        val inactiveColor = try {
            val tv = android.util.TypedValue()
            theme.resolveAttribute(R.attr.appNavInactive, tv, true)
            tv.data
        } catch (e: Exception) {
            0xFF999999.toInt()
        }

        for (id in navIds) {
            val navView = findViewById<View>(id)
            if (navView is LinearLayout) {
                val textView = navView.getChildAt(1) as? TextView
                if (id == navId) {
                    textView?.setTextColor(activeColor)
                } else {
                    textView?.setTextColor(inactiveColor)
                }
            }
        }
    }

    private fun loadFragment(fragment: Fragment?): Boolean {
        fragment ?: return false
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
        return true
    }

    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: 需要 MANAGE_EXTERNAL_STORAGE
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("存储权限")
                    .setMessage("为方便访问日志文件，需要授予\"所有文件访问\"权限。\n\n日志位置: /sdcard/RadioApp/logs/\n\n点击确定后，请在设置中开启权限。")
                    .setPositiveButton("去设置") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.parse("package:$packageName")
                            startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            } catch (_: Exception) {}
                        }
                    }
                    .setNegativeButton("稍后", null)
                    .show()
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-10: 请求 WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    100
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
