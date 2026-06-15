package com.radio.app.fragments;

import android.content.Intent;
import android.os.Bundle;
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

import java.util.ArrayList;
import java.util.List;

public class SearchFragment extends Fragment implements SearchResultAdapter.OnSearchResultClickListener {
    private EditText etSearch;
    private RecyclerView recyclerView;
    private SearchResultAdapter adapter;
    private List<SearchResult> results = new ArrayList<>();

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
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { search(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        return view;
    }

    private void search(String q) {
        results.clear();
        if (q.isEmpty()) { adapter.notifyDataSetChanged(); return; }
        Episode e = new Episode(); e.setId("s1"); e.setTitle("搜索: " + q); e.setDescription("关于" + q + "的节目"); e.setStationName("新闻综合广播"); e.setAudioUrl("https://example.com/a.mp3");
        SearchResult r1 = new SearchResult(); r1.setType(SearchResult.Type.EPISODE); r1.setEpisode(e); r1.setMatchedText(e.getTitle()); results.add(r1);
        adapter.notifyDataSetChanged();
    }

    @Override public void onSearchResultClick(SearchResult r) {
        if (r.getEpisode() != null) {
            if (getActivity() instanceof com.radio.app.activities.MainActivity) {
                com.radio.app.activities.MainActivity a = (com.radio.app.activities.MainActivity) getActivity();
                if (a.isServiceBound() && a.getPlaybackService() != null) a.getPlaybackService().playEpisode(r.getEpisode(), false);
            }
            startActivity(new Intent(getContext(), PlayerActivity.class));
        }
    }
}
