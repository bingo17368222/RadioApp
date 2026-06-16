package com.radio.app.fragments;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
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
import com.radio.app.models.AppSettings;
import com.radio.app.models.Episode;
import com.radio.app.network.EpisodeApiService;
import com.radio.app.utils.PreferenceManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class EpisodesFragment extends Fragment implements EpisodeAdapter.OnEpisodeClickListener {
    private TextView tvDate;
    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private EpisodeAdapter adapter;
    private List<Episode> list = new ArrayList<>();
    private Calendar cal = Calendar.getInstance();
    private Set<String> dislikedIds = new HashSet<>();
    private PreferenceManager prefMgr;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_episodes, container, false);
        tvDate = view.findViewById(R.id.tv_selected_date);
        recyclerView = view.findViewById(R.id.recycler_view);
        progressBar = view.findViewById(R.id.progress_bar);
        prefMgr = new PreferenceManager(requireContext());

        // 加载不喜欢列表
        AppSettings settings = prefMgr.loadSettings();
        dislikedIds.clear();
        dislikedIds.addAll(settings.getDislikedEpisodes());

        adapter = new EpisodeAdapter(getContext(), list, this);
        adapter.setDislikedIds(dislikedIds);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(adapter);

        view.findViewById(R.id.btn_select_date).setOnClickListener(v -> showDatePicker());
        updateDate();
        loadEpisodes();
        return view;
    }

    private void showDatePicker() {
        // minDate: 10年前, maxDate: 今天
        Calendar minDate = Calendar.getInstance();
        minDate.add(Calendar.YEAR, -10);

        DatePickerDialog dialog = new DatePickerDialog(
                requireContext(),
                (view, year, month, dayOfMonth) -> {
                    cal.set(year, month, dayOfMonth);
                    updateDate();
                    loadEpisodes();
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
        );
        dialog.getDatePicker().setMinDate(minDate.getTimeInMillis());
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        dialog.show();
    }

    private void updateDate() {
        tvDate.setText(String.format("%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)));
    }

    private void loadEpisodes() {
        progressBar.setVisibility(View.VISIBLE);
        String dateStr = String.format("%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH));

        EpisodeApiService.getInstance().getEpisodesByDate("henan-1", dateStr, new EpisodeApiService.ApiCallback<List<Episode>>() {
            @Override
            public void onSuccess(List<Episode> episodes) {
                if (getActivity() == null) return;
                progressBar.setVisibility(View.GONE);
                list.clear();
                list.addAll(episodes);
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onError(String error) {
                if (getActivity() == null) return;
                progressBar.setVisibility(View.GONE);
                Toast.makeText(getContext(), error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onEpisodeClick(Episode e) {
        if (getActivity() instanceof com.radio.app.activities.MainActivity) {
            com.radio.app.activities.MainActivity a = (com.radio.app.activities.MainActivity) getActivity();
            if (a.isServiceBound() && a.getPlaybackService() != null) {
                a.getPlaybackService().playEpisode(e, false);
            }
        }
        startActivity(new Intent(getContext(), PlayerActivity.class));
    }

    @Override
    public void onEpisodeLongClick(Episode e) {
        boolean isDisliked = dislikedIds.contains(e.getId());
        String[] items;
        if (isDisliked) {
            items = new String[]{"取消不喜欢"};
        } else {
            items = new String[]{"标记为不喜欢"};
        }

        new android.app.AlertDialog.Builder(requireContext())
                .setTitle(e.getTitle())
                .setItems(items, (dialog, which) -> {
                    AppSettings settings = prefMgr.loadSettings();
                    List<String> disliked = settings.getDislikedEpisodes();
                    if (isDisliked) {
                        disliked.remove(e.getId());
                        dislikedIds.remove(e.getId());
                        adapter.removeDislikedId(e.getId());
                        Toast.makeText(getContext(), "已取消不喜欢标记", Toast.LENGTH_SHORT).show();
                    } else {
                        disliked.add(e.getId());
                        dislikedIds.add(e.getId());
                        adapter.addDislikedId(e.getId());
                        Toast.makeText(getContext(), "已标记为不喜欢", Toast.LENGTH_SHORT).show();
                    }
                    settings.setDislikedEpisodes(disliked);
                    prefMgr.saveSettings(settings);
                })
                .show();
    }
}
