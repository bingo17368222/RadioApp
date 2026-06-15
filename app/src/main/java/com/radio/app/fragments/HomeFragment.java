package com.radio.app.fragments;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
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
    private StationAdapter adapter;
    private ProgressBar progressBar;
    private List<RadioStation> stationList = new ArrayList<>();

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        recyclerView = view.findViewById(R.id.recycler_view);
        progressBar = view.findViewById(R.id.progress_bar);
        adapter = new StationAdapter(getContext(), stationList, this);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(adapter);
        loadStations();
        return view;
    }

    private void loadStations() {
        progressBar.setVisibility(View.VISIBLE);
        RadioApiService.getInstance().getChineseStations(50, 0, new RadioApiService.ApiCallback<List<RadioStation>>() {
            @Override public void onSuccess(List<RadioStation> stations) {
                if (getActivity() == null) return;
                progressBar.setVisibility(View.GONE);
                stationList.clear();
                for (RadioStation s : stations) { if (s.isLastCheckOk()) { s.setCurrentProgram("Live Stream"); stationList.add(s); } }
                adapter.notifyDataSetChanged();
            }
            @Override public void onError(String error) {
                if (getActivity() == null) return;
                progressBar.setVisibility(View.GONE);
                Toast.makeText(getContext(), error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override public void onStationClick(RadioStation station) {
        if (getActivity() instanceof com.radio.app.activities.MainActivity) {
            com.radio.app.activities.MainActivity a = (com.radio.app.activities.MainActivity) getActivity();
            if (a.isServiceBound() && a.getPlaybackService() != null) a.getPlaybackService().playStation(station);
        }
        startActivity(new Intent(getContext(), PlayerActivity.class));
    }

    @Override public void onStationLongClick(RadioStation station) {}
}
