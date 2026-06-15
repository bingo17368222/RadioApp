package com.radio.app.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import com.radio.app.R;
import com.radio.app.models.RadioStation;

import java.util.List;

public class StationAdapter extends RecyclerView.Adapter<StationAdapter.ViewHolder> {
    private final Context ctx;
    private final List<RadioStation> stations;
    private final OnStationClickListener listener;

    public interface OnStationClickListener { void onStationClick(RadioStation s); void onStationLongClick(RadioStation s); }

    public StationAdapter(Context ctx, List<RadioStation> stations, OnStationClickListener listener) { this.ctx = ctx; this.stations = stations; this.listener = listener; }

    @NonNull @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) { return new ViewHolder(LayoutInflater.from(ctx).inflate(R.layout.item_station, parent, false)); }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
        RadioStation s = stations.get(pos);
        h.tvName.setText(s.getName());
        h.tvProgram.setText(s.getCurrentProgram());
        h.tvCountry.setText(s.getCountry());
        h.tvBitrate.setText(s.getBitrate() > 0 ? s.getBitrate() + " kbps" : "");
        h.ivCover.setImageResource(R.drawable.ic_radio);
        h.card.setOnClickListener(v -> { if (listener != null) listener.onStationClick(s); });
        h.card.setOnLongClickListener(v -> { if (listener != null) listener.onStationLongClick(s); return true; });
    }

    @Override public int getItemCount() { return stations.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        CardView card; ImageView ivCover; TextView tvName, tvProgram, tvCountry, tvBitrate;
        ViewHolder(@NonNull View v) { super(v); card = v.findViewById(R.id.card_view); ivCover = v.findViewById(R.id.iv_cover); tvName = v.findViewById(R.id.tv_name); tvProgram = v.findViewById(R.id.tv_program); tvCountry = v.findViewById(R.id.tv_country); tvBitrate = v.findViewById(R.id.tv_bitrate); }
    }
}
