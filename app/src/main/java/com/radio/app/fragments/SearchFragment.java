package com.radio.app.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
import com.radio.app.adapters.SearchResultAdapter;
import com.radio.app.models.Episode;
import com.radio.app.models.SearchResult;
import com.radio.app.network.EpisodeApiService;

import java.util.ArrayList;
import java.util.List;

public class SearchFragment extends Fragment implements SearchResultAdapter.OnSearchResultClickListener {
    private EditText etSearch;
    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private TextView tvEmpty;
    private SearchResultAdapter adapter;
    private List<SearchResult> results = new ArrayList<>();
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_search, container, false);
        etSearch = view.findViewById(R.id.et_search);
        recyclerView = view.findViewById(R.id.recycler_view);
        progressBar = view.findViewById(R.id.progress_bar);
        tvEmpty = view.findViewById(R.id.tv_empty);
        adapter = new SearchResultAdapter(getContext(), results, this);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // 防抖：300ms后执行搜索
                if (searchRunnable != null) handler.removeCallbacks(searchRunnable);
                searchRunnable = () -> performSearch(s.toString());
                handler.postDelayed(searchRunnable, 300);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        return view;
    }

    private void performSearch(String query) {
        results.clear();
        if (query.trim().isEmpty()) {
            adapter.notifyDataSetChanged();
            tvEmpty.setVisibility(View.GONE);
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);

        EpisodeApiService.getInstance().search(query.trim(), new EpisodeApiService.ApiCallback<List<Episode>>() {
            @Override
            public void onSuccess(List<Episode> episodes) {
                if (getActivity() == null) return;
                progressBar.setVisibility(View.GONE);
                results.clear();

                for (Episode e : episodes) {
                    SearchResult r = new SearchResult();
                    r.setType(SearchResult.Type.EPISODE);
                    r.setEpisode(e);
                    r.setMatchedText(e.getDescription());
                    results.add(r);
                }

                adapter.notifyDataSetChanged();
                if (results.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    tvEmpty.setText("未找到相关节目");
                } else {
                    tvEmpty.setVisibility(View.GONE);
                }
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
    public void onSearchResultClick(SearchResult r) {
        if (r.getEpisode() != null) {
            if (getActivity() instanceof com.radio.app.activities.MainActivity) {
                com.radio.app.activities.MainActivity a = (com.radio.app.activities.MainActivity) getActivity();
                if (a.isServiceBound() && a.getPlaybackService() != null) {
                    a.getPlaybackService().playEpisode(r.getEpisode(), false);
                }
            }
            startActivity(new Intent(getContext(), PlayerActivity.class));
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (searchRunnable != null) handler.removeCallbacks(searchRunnable);
    }
}
