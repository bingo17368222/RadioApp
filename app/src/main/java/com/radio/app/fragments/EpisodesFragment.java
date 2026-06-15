package com.radio.app.fragments;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.radio.app.R;
import com.radio.app.activities.PlayerActivity;
import com.radio.app.adapters.EpisodeAdapter;
import com.radio.app.models.Episode;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class EpisodesFragment extends Fragment implements EpisodeAdapter.OnEpisodeClickListener {
    private TextView tvDate;
    private RecyclerView recyclerView;
    private EpisodeAdapter adapter;
    private List<Episode> list = new ArrayList<>();
    private Calendar cal = Calendar.getInstance();

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_episodes, container, false);
        tvDate = view.findViewById(R.id.tv_selected_date);
        recyclerView = view.findViewById(R.id.recycler_view);
        adapter = new EpisodeAdapter(getContext(), list, this);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(adapter);
        view.findViewById(R.id.btn_select_date).setOnClickListener(v -> new DatePickerDialog(requireContext(), (d, y, m, day) -> { cal.set(y, m, day); updateDate(); loadEpisodes(); }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show());
        updateDate();
        loadEpisodes();
        return view;
    }

    private void updateDate() { tvDate.setText(String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))); }

    private void loadEpisodes() {
        list.clear();
        String d = String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
        Episode e1 = new Episode(); e1.setId("ep1-" + d); e1.setTitle("早间新闻播报"); e1.setBroadcastAt(d + "T07:00:00"); e1.setDuration(3600); e1.setDescription("最新国内外新闻资讯汇总"); e1.setStationName("新闻综合广播"); e1.setAudioUrl("https://example.com/audio1.mp3"); e1.setLive(false);
        list.add(e1);
        Episode e2 = new Episode(); e2.setId("ep2-" + d); e2.setTitle("财经观察"); e2.setBroadcastAt(d + "T09:00:00"); e2.setDuration(1800); e2.setDescription("深度解析财经热点"); e2.setStationName("新闻综合广播"); e2.setAudioUrl("https://example.com/audio2.mp3"); e2.setLive(false);
        list.add(e2);
        adapter.notifyDataSetChanged();
    }

    @Override public void onEpisodeClick(Episode e) {
        if (getActivity() instanceof com.radio.app.activities.MainActivity) {
            com.radio.app.activities.MainActivity a = (com.radio.app.activities.MainActivity) getActivity();
            if (a.isServiceBound() && a.getPlaybackService() != null) a.getPlaybackService().playEpisode(e, false);
        }
        startActivity(new Intent(getContext(), PlayerActivity.class));
    }

    @Override public void onEpisodeLongClick(Episode e) { Toast.makeText(getContext(), e.getTitle(), Toast.LENGTH_SHORT).show(); }
}
