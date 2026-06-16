package com.radio.app.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.radio.app.R;
import com.radio.app.activities.PlayerActivity;
import com.radio.app.adapters.StationAdapter;
import com.radio.app.models.RadioStation;
import com.radio.app.network.RadioApiService;

import java.util.ArrayList;
import java.util.List;

public class HomeFragment extends Fragment implements StationAdapter.OnStationClickListener {
    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private StationAdapter adapter;
    private final List<RadioStation> stations = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_home, container, false);
        recyclerView = v.findViewById(R.id.recycler_view);
        progressBar = v.findViewById(R.id.progress_bar);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new StationAdapter(getContext(), stations, this);
        recyclerView.setAdapter(adapter);
        loadStations();
        return v;
    }

    private void loadStations() {
        progressBar.setVisibility(View.VISIBLE);
        RadioApiService.getInstance().getAllStations(new RadioApiService.ApiCallback<List<RadioStation>>() {
            @Override
            public void onSuccess(List<RadioStation> result) {
                // 切回主线程更新UI
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    stations.clear();
                    stations.addAll(result);
                    adapter.notifyDataSetChanged();
                });
            }
            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(getContext(), error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    @Override
    public void onStationClick(RadioStation s) {
        Intent intent = new Intent(getContext(), PlayerActivity.class);
        intent.putExtra("station_id", s.getId());
        intent.putExtra("station_name", s.getName());
        intent.putExtra("stream_url", s.getStreamUrl());
        intent.putExtra("is_live", true);
        startActivity(intent);
    }

    @Override
    public void onStationLongClick(RadioStation s) {
        // 长按可添加收藏等操作
    }
}
