package com.radio.app.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.radio.app.R
import com.radio.app.fragments.HomeFragment
import com.radio.app.fragments.EpisodesFragment
import com.radio.app.fragments.SearchFragment
import com.radio.app.fragments.SettingsFragment
import com.radio.app.services.RadioPlaybackService
import com.radio.app.utils.ThemeManager

class MainActivity : AppCompatActivity() {

    var playbackService: RadioPlaybackService? = null
        private set
    var isServiceBound: Boolean = false
        private set

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
        setTheme(R.style.Theme_RadioApp)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        nav.setOnItemSelectedListener { item ->
            val fragment: Fragment? = when (item.itemId) {
                R.id.nav_home -> HomeFragment()
                R.id.nav_episodes -> EpisodesFragment()
                R.id.nav_search -> SearchFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> null
            }
            loadFragment(fragment)
        }

        if (savedInstanceState == null) {
            loadFragment(HomeFragment())
        }

        bindService(
            Intent(this, RadioPlaybackService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun loadFragment(fragment: Fragment?): Boolean {
        fragment ?: return false
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
