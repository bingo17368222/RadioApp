package com.radio.app.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.radio.app.R;
import com.radio.app.models.VoiceSegment;

import java.util.ArrayList;
import java.util.List;

public class VoiceSegmentAdapter extends RecyclerView.Adapter<VoiceSegmentAdapter.ViewHolder> {
    private List<VoiceSegment> segments = new ArrayList<>();
    private OnSegmentClickListener listener;
    private int currentSegmentIndex = -1;

    public interface OnSegmentClickListener {
        void onSegmentClick(int position, VoiceSegment segment);
        void onSegmentLongClick(int position, VoiceSegment segment);
    }

    public void setSegments(List<VoiceSegment> segments) {
        this.segments = segments != null ? segments : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void setCurrentSegmentIndex(int index) {
        int old = this.currentSegmentIndex;
        this.currentSegmentIndex = index;
        if (old >= 0 && old < segments.size()) notifyItemChanged(old);
        if (index >= 0 && index < segments.size()) notifyItemChanged(index);
    }

    public void setOnSegmentClickListener(OnSegmentClickListener listener) {
        this.listener = listener;
    }

    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_voice_segment, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        VoiceSegment segment = segments.get(position);
        holder.tvTimeRange.setText(formatTime(segment.getStart()) + " - " + formatTime(segment.getEnd()));
        holder.tvLabel.setText(segment.getLabel() != null ? segment.getLabel() : "");

        boolean isDry = segment.isEffectiveDry();
        holder.tvType.setText(isDry ? "干货" : "水分");
        holder.tvType.setTextColor(isDry ?
            ContextCompat.getColor(holder.itemView.getContext(), R.color.success) :
            ContextCompat.getColor(holder.itemView.getContext(), R.color.warning));

        if (segment.isManuallyMarked()) {
            holder.ivManualMark.setVisibility(View.VISIBLE);
            holder.ivManualMark.setImageResource(isDry ? R.drawable.ic_manual_dry : R.drawable.ic_manual_water);
        } else {
            holder.ivManualMark.setVisibility(View.GONE);
        }

        if (position == currentSegmentIndex) {
            holder.itemView.setAlpha(1.0f);
            holder.itemView.setBackgroundColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.primary_dark));
        } else {
            holder.itemView.setAlpha(0.8f);
            holder.itemView.setBackgroundColor(0);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onSegmentClick(position, segment);
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onSegmentLongClick(position, segment);
            return true;
        });
    }

    @Override
    public int getItemCount() { return segments.size(); }

    private String formatTime(long ms) {
        int s = (int)(ms / 1000);
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvTimeRange, tvLabel, tvType;
        ImageView ivManualMark;
        ViewHolder(@NonNull View view) {
            super(view);
            tvTimeRange = view.findViewById(R.id.tv_time_range);
            tvLabel = view.findViewById(R.id.tv_label);
            tvType = view.findViewById(R.id.tv_type);
            ivManualMark = view.findViewById(R.id.iv_manual_mark);
        }
    }
}
