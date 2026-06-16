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
import com.radio.app.database.RadioDatabaseHelper;
import com.radio.app.models.Episode;
import com.radio.app.network.EpisodeApiService;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class EpisodesFragment extends Fragment implements EpisodeAdapter.OnEpisodeClickListener {
    private TextView tvDate;
    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private EpisodeAdapter adapter;
    private List<Episode> list = new ArrayList<>();
    private Calendar cal = Calendar.getInstance();
    private RadioDatabaseHelper dbHelper;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_episodes, container, false);
        tvDate = view.findViewById(R.id.tv_selected_date);
        recyclerView = view.findViewById(R.id.recycler_view);
        progressBar = view.findViewById(R.id.progress_bar);
        dbHelper = RadioDatabaseHelper.getInstance(requireContext());
        adapter = new EpisodeAdapter(getContext(), list, this);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(adapter);

        Calendar tenYearsAgo = Calendar.getInstance();
        tenYearsAgo.add(Calendar.YEAR, -10);

        view.findViewById(R.id.btn_select_date).setOnClickListener(v -> {
            DatePickerDialog dialog = new DatePickerDialog(requireContext(),
                (d, y, m, day) -> { cal.set(y, m, day); updateDate(); loadEpisodes(); },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
            dialog.getDatePicker().setMinDate(tenYearsAgo.getTimeInMillis());
            dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
            dialog.show();
        });

        updateDate();
        loadEpisodes();
        return view;
    }

    private void updateDate() {
        tvDate.setText(String.format("%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)));
    }

    private void loadEpisodes() {
        progressBar.setVisibility(View.VISIBLE);
        String d = String.format("%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
        String stationId = "henan-1";

        EpisodeApiService.getInstance().getEpisodesByDate(stationId, d,
            new EpisodeApiService.ApiCallback<List<Episode>>() {
                @Override
                public void onSuccess(List<Episode> episodes) {
                    if (getActivity() == null) return;
                    progressBar.setVisibility(View.GONE);
                    list.clear();
                    // Check disliked status from database
                    for (Episode ep : episodes) {
                        ep.setDisliked(dbHelper.isEpisodeDisliked(ep.getId()));
                        list.add(ep);
                    }
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

    @Override public void onEpisodeClick(Episode e) {
        if (getActivity() instanceof com.radio.app.activities.MainActivity) {
            com.radio.app.activities.MainActivity a = (com.radio.app.activities.MainActivity) getActivity();
            if (a.isServiceBound() && a.getPlaybackService() != null)
                a.getPlaybackService().playEpisode(e, false);
        }
        startActivity(new Intent(getContext(), PlayerActivity.class));
    }

    @Override public void onEpisodeLongClick(Episode e) {
        String[] items;
        if (e.isDisliked()) {
            items = new String[]{"取消不喜欢"};
        } else {
            items = new String[]{"标记不喜欢"};
        }
        new android.app.AlertDialog.Builder(requireContext())
            .setTitle(e.getTitle())
            .setItems(items, (dialog, which) -> {
                if (which == 0) {
                    if (e.isDisliked()) {
                        dbHelper.removeDislikedEpisode(e.getId());
                        e.setDisliked(false);
                        Toast.makeText(getContext(), "已取消不喜欢", Toast.LENGTH_SHORT).show();
                    } else {
                        dbHelper.addDislikedEpisode(e.getId(), e.getTitle(), e.getStationName());
                        e.setDisliked(true);
                        Toast.makeText(getContext(), "已标记不喜欢", Toast.LENGTH_SHORT).show();
                    }
                    adapter.notifyDataSetChanged();
                }
            })
            .show();
    }
}
