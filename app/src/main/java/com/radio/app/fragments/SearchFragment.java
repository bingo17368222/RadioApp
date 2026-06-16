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
    private SearchResultAdapter adapter;
    private List<SearchResult> results = new ArrayList<>();
    private Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable debounceRunnable;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_search, container, false);
        etSearch = view.findViewById(R.id.et_search);
        recyclerView = view.findViewById(R.id.recycler_view);
        adapter = new SearchResultAdapter(getContext(), results, this);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(adapter);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { debounceSearch(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        return view;
    }

    private void debounceSearch(String query) {
        if (debounceRunnable != null) debounceHandler.removeCallbacks(debounceRunnable);
        debounceRunnable = () -> search(query);
        debounceHandler.postDelayed(debounceRunnable, 300);
    }

    private void search(String q) {
        if (q.isEmpty()) {
            results.clear();
            adapter.notifyDataSetChanged();
            return;
        }
        EpisodeApiService.getInstance().search(q, new EpisodeApiService.ApiCallback<List<SearchResult>>() {
            @Override
            public void onSuccess(List<SearchResult> searchResults) {
                if (getActivity() == null) return;
                results.clear();
                results.addAll(searchResults);
                adapter.notifyDataSetChanged();
            }
            @Override
            public void onError(String error) {
                if (getActivity() == null) return;
                results.clear();
                adapter.notifyDataSetChanged();
            }
        });
    }

    @Override public void onSearchResultClick(SearchResult r) {
        // Create a dummy episode from search result for playback
        Episode e = new Episode();
        e.setId(r.getId());
        e.setTitle(r.getTitle());
        e.setStationName(r.getStationName());
        e.setAudioUrl("https://example.com/audio/" + r.getId() + ".mp3");
        e.setLive(false);

        if (getActivity() instanceof com.radio.app.activities.MainActivity) {
            com.radio.app.activities.MainActivity a = (com.radio.app.activities.MainActivity) getActivity();
            if (a.isServiceBound() && a.getPlaybackService() != null)
                a.getPlaybackService().playEpisode(e, false);
        }
        startActivity(new Intent(getContext(), PlayerActivity.class));
    }
}
