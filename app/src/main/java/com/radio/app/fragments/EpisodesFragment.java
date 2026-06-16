package com.radio.app.fragments;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.radio.app.R;
import com.radio.app.activities.PlayerActivity;
import com.radio.app.adapters.EpisodeAdapter;
import com.radio.app.adapters.StationAdapter;
import com.radio.app.models.AppSettings;
import com.radio.app.models.Episode;
import com.radio.app.models.RadioStation;
import com.radio.app.network.EpisodeApiService;
import com.radio.app.network.RadioApiService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class EpisodesFragment extends Fragment implements EpisodeAdapter.OnEpisodeClickListener {
    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private TextView tvSelectedDate;
    private Button btnSelectDate;
    private EpisodeAdapter adapter;
    private final List<Episode> episodes = new ArrayList<>();
    private final List<RadioStation> stations = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private Calendar selectedDate;
    private String selectedStationId = null;
    private String selectedStationName = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_episodes, container, false);
        recyclerView = v.findViewById(R.id.recycler_view);
        progressBar = v.findViewById(R.id.progress_bar);
        tvSelectedDate = v.findViewById(R.id.tv_selected_date);
        btnSelectDate = v.findViewById(R.id.btn_select_date);

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        selectedDate = Calendar.getInstance();
        tvSelectedDate.setText(dateFormat.format(selectedDate.getTime()));

        btnSelectDate.setOnClickListener(v1 -> showDatePicker());

        // 先显示电台列表让用户选择
        loadStations();

        return v;
    }

    private void showDatePicker() {
        Calendar now = Calendar.getInstance();
        Calendar tenYearsAgo = Calendar.getInstance();
        tenYearsAgo.add(Calendar.YEAR, -10);

        DatePickerDialog dialog = new DatePickerDialog(
                requireContext(),
                (view, year, month, dayOfMonth) -> {
                    selectedDate.set(year, month, dayOfMonth);
                    tvSelectedDate.setText(dateFormat.format(selectedDate.getTime()));
                    if (selectedStationId != null) {
                        loadEpisodes(selectedStationId, dateFormat.format(selectedDate.getTime()));
                    }
                },
                selectedDate.get(Calendar.YEAR),
                selectedDate.get(Calendar.MONTH),
                selectedDate.get(Calendar.DAY_OF_MONTH)
        );

        // 设置日期范围：10年前到今天
        dialog.getDatePicker().setMinDate(tenYearsAgo.getTimeInMillis());
        dialog.getDatePicker().setMaxDate(now.getTimeInMillis());

        // 尝试显示年份选择器（Android 5.0+）
        try {
            dialog.getDatePicker().setCalendarViewShown(false);
            dialog.getDatePicker().setSpinnersShown(true);
        } catch (Exception e) {
            e.printStackTrace();
        }

        dialog.show();
    }

    private void loadStations() {
        progressBar.setVisibility(View.VISIBLE);
        tvSelectedDate.setText("请选择电台");
        btnSelectDate.setEnabled(false);

        // 使用内置电台数据
        stations.clear();
        stations.addAll(getBuiltinStations());

        StationAdapter stationAdapter = new StationAdapter(getContext(), stations, new StationAdapter.OnStationClickListener() {
            @Override
            public void onStationClick(RadioStation s) {
                selectedStationId = s.getId();
                selectedStationName = s.getName();
                tvSelectedDate.setText(s.getName() + " | " + dateFormat.format(selectedDate.getTime()));
                btnSelectDate.setEnabled(true);
                loadEpisodes(s.getId(), dateFormat.format(selectedDate.getTime()));
            }

            @Override
            public void onStationLongClick(RadioStation s) {}
        });

        recyclerView.setAdapter(stationAdapter);
        progressBar.setVisibility(View.GONE);

        // 同时从网络加载更多电台
        RadioApiService.getInstance().getAllStations(new RadioApiService.ApiCallback<List<RadioStation>>() {
            @Override
            public void onSuccess(List<RadioStation> result) {
                mainHandler.post(() -> {
                    stations.clear();
                    stations.addAll(result);
                    stationAdapter.notifyDataSetChanged();
                });
            }
            @Override
            public void onError(String error) {
                // 忽略网络错误，使用内置电台
            }
        });
    }

    private List<RadioStation> getBuiltinStations() {
        List<RadioStation> list = new ArrayList<>();
        String[][] data = {
            {"henan-1", "河南新闻广播"}, {"henan-2", "河南音乐广播"},
            {"henan-3", "河南交通广播"}, {"henan-4", "河南经济广播"},
            {"henan-5", "郑州新闻广播"}, {"henan-6", "洛阳交通广播"},
            {"cnr-1", "中国之声"}, {"cnr-2", "经济之声"}, {"cnr-3", "音乐之声"}
        };
        for (String[] d : data) {
            RadioStation s = new RadioStation();
            s.setId(d[0]);
            s.setName(d[1]);
            s.setCurrentProgram(d[1]);
            list.add(s);
        }
        return list;
    }

    private void loadEpisodes(String stationId, String dateStr) {
        progressBar.setVisibility(View.VISIBLE);
        adapter = new EpisodeAdapter(getContext(), episodes, this);
        recyclerView.setAdapter(adapter);

        EpisodeApiService.getInstance().getEpisodesByDate(stationId, dateStr, new EpisodeApiService.ApiCallback<List<Episode>>() {
            @Override
            public void onSuccess(List<Episode> result) {
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    episodes.clear();
                    episodes.addAll(result);
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
    public void onEpisodeClick(Episode episode) {
        Intent intent = new Intent(getContext(), PlayerActivity.class);
        intent.putExtra("episode_id", episode.getId());
        intent.putExtra("title", episode.getTitle());
        intent.putExtra("audio_url", episode.getAudioUrl());
        intent.putExtra("is_live", false);
        intent.putExtra("station_name", selectedStationName != null ? selectedStationName : episode.getStationName());
        intent.putExtra("duration", episode.getDuration());
        intent.putExtra("voice_segments", new ArrayList<>(episode.getVoiceSegments()));
        intent.putExtra("transcripts", new ArrayList<>(episode.getTranscripts()));
        startActivity(intent);
    }

    @Override
    public void onEpisodeLongClick(Episode episode) {
        new AlertDialog.Builder(requireContext())
                .setTitle(episode.getTitle())
                .setItems(new String[]{"标记为不喜欢", "取消不喜欢"}, (dialog, which) -> {
                    if (which == 0) {
                        AppSettings.getInstance(requireContext()).addDislikedEpisode(requireContext(), episode.getId());
                        Toast.makeText(getContext(), "已标记为不喜欢", Toast.LENGTH_SHORT).show();
                    } else {
                        AppSettings.getInstance(requireContext()).removeDislikedEpisode(requireContext(), episode.getId());
                        Toast.makeText(getContext(), "已取消不喜欢", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }
}
