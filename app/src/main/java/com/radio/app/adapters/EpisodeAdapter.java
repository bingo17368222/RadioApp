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
import com.radio.app.models.Episode;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.ViewHolder> {
    private final Context ctx;
    private final List<Episode> episodes;
    private final OnEpisodeClickListener listener;

    public interface OnEpisodeClickListener { void onEpisodeClick(Episode e); void onEpisodeLongClick(Episode e); }

    public EpisodeAdapter(Context ctx, List<Episode> episodes, OnEpisodeClickListener listener) { this.ctx = ctx; this.episodes = episodes; this.listener = listener; }

    @NonNull @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) { return new ViewHolder(LayoutInflater.from(ctx).inflate(R.layout.item_episode, parent, false)); }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
        Episode e = episodes.get(pos);
        h.tvTitle.setText(e.getTitle());
        h.tvDescription.setText(e.getDescription());
        h.tvStation.setText(e.getStationName());
        try { SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()); SimpleDateFormat out = new SimpleDateFormat("HH:mm", Locale.getDefault()); Date d = in.parse(e.getBroadcastAt()); h.tvTime.setText(out.format(d)); } catch (Exception ex) { h.tvTime.setText(e.getBroadcastAt()); }
        h.tvDuration.setText(String.format(Locale.getDefault(), "%02d:%02d", e.getDuration() / 60, e.getDuration() % 60));
        h.tvLive.setVisibility(View.GONE);
        h.card.setOnClickListener(v -> { if (listener != null) listener.onEpisodeClick(e); });
        h.card.setOnLongClickListener(v -> { if (listener != null) listener.onEpisodeLongClick(e); return true; });
    }

    @Override public int getItemCount() { return episodes.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        CardView card; TextView tvTitle, tvDescription, tvStation, tvTime, tvDuration, tvLive;
        ViewHolder(@NonNull View v) { super(v); card = v.findViewById(R.id.card_view); tvTitle = v.findViewById(R.id.tv_title); tvDescription = v.findViewById(R.id.tv_description); tvStation = v.findViewById(R.id.tv_station); tvTime = v.findViewById(R.id.tv_time); tvDuration = v.findViewById(R.id.tv_duration); tvLive = v.findViewById(R.id.tv_live); }
    }
}
