package com.radio.app.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import com.radio.app.R;
import com.radio.app.models.SearchResult;

import java.util.List;

public class SearchResultAdapter extends RecyclerView.Adapter<SearchResultAdapter.ViewHolder> {
    private final Context ctx;
    private final List<SearchResult> results;
    private final OnSearchResultClickListener listener;

    public interface OnSearchResultClickListener { void onSearchResultClick(SearchResult r); }

    public SearchResultAdapter(Context ctx, List<SearchResult> results, OnSearchResultClickListener listener) { this.ctx = ctx; this.results = results; this.listener = listener; }

    @NonNull @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) { return new ViewHolder(LayoutInflater.from(ctx).inflate(R.layout.item_search_result, parent, false)); }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
        SearchResult r = results.get(pos);
        h.tvType.setText(r.getType() == SearchResult.Type.EPISODE ? "节目" : "字幕");
        h.tvTitle.setText(r.getEpisode() != null ? r.getEpisode().getTitle() : "");
        h.tvStation.setText(r.getEpisode() != null ? r.getEpisode().getStationName() : "");
        if (r.getMatchedText() != null) { h.tvMatch.setText(r.getMatchedText()); h.tvMatch.setVisibility(View.VISIBLE); } else h.tvMatch.setVisibility(View.GONE);
        if (r.getTranscript() != null) { h.tvTime.setText("时间: " + String.format("%02d:%02d", r.getTranscript().getSegmentStart() / 60, r.getTranscript().getSegmentStart() % 60)); h.tvTime.setVisibility(View.VISIBLE); } else h.tvTime.setVisibility(View.GONE);
        h.card.setOnClickListener(v -> { if (listener != null) listener.onSearchResultClick(r); });
    }

    @Override public int getItemCount() { return results.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        CardView card; TextView tvType, tvTitle, tvStation, tvMatch, tvTime;
        ViewHolder(@NonNull View v) { super(v); card = v.findViewById(R.id.card_view); tvType = v.findViewById(R.id.tv_type); tvTitle = v.findViewById(R.id.tv_title); tvStation = v.findViewById(R.id.tv_station); tvMatch = v.findViewById(R.id.tv_match); tvTime = v.findViewById(R.id.tv_time); }
    }
}
