package com.radio.app.activities;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;
import com.radio.app.R;
import com.radio.app.fragments.HomeFragment;
import com.radio.app.fragments.EpisodesFragment;
import com.radio.app.fragments.SearchFragment;
import com.radio.app.fragments.SettingsFragment;
import com.radio.app.services.RadioPlaybackService;

public class MainActivity extends AppCompatActivity {
    private RadioPlaybackService playbackService;
    private boolean serviceBound = false;

    private ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder s) {
            playbackService = ((RadioPlaybackService.LocalBinder) s).getService();
            serviceBound = true;
        }
        @Override
        public void onServiceDisconnected(ComponentName name) { playbackService = null; serviceBound = false; }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        BottomNavigationView nav = findViewById(R.id.bottom_navigation);
        nav.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                Fragment f = null;
                int id = item.getItemId();
                if (id == R.id.nav_home) f = new HomeFragment();
                else if (id == R.id.nav_episodes) f = new EpisodesFragment();
                else if (id == R.id.nav_search) f = new SearchFragment();
                else if (id == R.id.nav_settings) f = new SettingsFragment();
                return loadFragment(f);
            }
        });
        if (savedInstanceState == null) loadFragment(new HomeFragment());
        bindService(new Intent(this, RadioPlaybackService.class), conn, Context.BIND_AUTO_CREATE);
    }

    private boolean loadFragment(Fragment f) {
        if (f != null) { getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container, f).commit(); return true; }
        return false;
    }

    public RadioPlaybackService getPlaybackService() { return playbackService; }
    public boolean isServiceBound() { return serviceBound; }

    @Override
    protected void onDestroy() { super.onDestroy(); if (serviceBound) { unbindService(conn); serviceBound = false; } }
}
