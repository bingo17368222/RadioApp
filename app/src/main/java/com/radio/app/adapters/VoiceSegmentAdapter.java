package com.radio.app.adapters;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
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
    private Context context;

    public interface OnSegmentClickListener {
        void onSegmentClick(int position, VoiceSegment segment);
        void onSegmentLongClick(int position, VoiceSegment segment);
        void onMarkAsDry(int position);
        void onMarkAsWater(int position);
        void onSkipThisTime(int position);
    }

    public VoiceSegmentAdapter(Context context) {
        this.context = context;
    }

    public void setSegments(List<VoiceSegment> segments) {
        this.segments = segments != null ? segments : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void setCurrentSegmentIndex(int index) {
        int oldIndex = this.currentSegmentIndex;
        this.currentSegmentIndex = index;
        if (oldIndex >= 0) notifyItemChanged(oldIndex);
        if (index >= 0 && index < segments.size()) notifyItemChanged(index);
    }

    public void setOnSegmentClickListener(OnSegmentClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_voice_segment, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        VoiceSegment segment = segments.get(position);
        boolean isCurrent = (position == currentSegmentIndex);
        boolean isDry = segment.isEffectiveDry();
        boolean isManuallyMarked = segment.isManuallyMarked();

        // 时间范围
        holder.tvTimeRange.setText(formatTimeRange(segment.getStart(), segment.getEnd()));

        // 标签
        holder.tvLabel.setText(segment.getLabel() != null ? segment.getLabel() : "");

        // 类型标签
        if (isDry) {
            holder.tvType.setText("干货");
            holder.tvType.setTextColor(ContextCompat.getColor(context, R.color.success));
        } else {
            holder.tvType.setText("水分");
            holder.tvType.setTextColor(ContextCompat.getColor(context, R.color.accent));
        }

        // 背景颜色
        if (isCurrent) {
            holder.container.setBackgroundColor(ContextCompat.getColor(context, R.color.primary_dark));
        } else if (isDry) {
            holder.container.setBackgroundColor(Color.parseColor("#1a3a2a"));
        } else {
            holder.container.setBackgroundColor(Color.parseColor("#3a1a1a"));
        }

        // 手动标记图标
        if (isManuallyMarked) {
            holder.ivManualMark.setVisibility(View.VISIBLE);
            holder.ivManualMark.setImageResource(isDry ? R.drawable.ic_manual_dry : R.drawable.ic_manual_water);
        } else {
            holder.ivManualMark.setVisibility(View.GONE);
        }

        // 本次不跳过标记
        if (!isDry && segment.isSkipThisTime()) {
            holder.tvSkipNotice.setVisibility(View.VISIBLE);
            holder.tvSkipNotice.setText("本次不跳过");
        } else {
            holder.tvSkipNotice.setVisibility(View.GONE);
        }

        // 点击事件
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onSegmentClick(position, segment);
        });

        // 长按弹出菜单
        holder.itemView.setOnLongClickListener(v -> {
            showPopupMenu(v, position, segment);
            return true;
        });
    }

    private void showPopupMenu(View anchor, int position, VoiceSegment segment) {
        PopupMenu popup = new PopupMenu(context, anchor);
        Menu menu = popup.getMenu();

        if (segment.isEffectiveDry()) {
            menu.add(Menu.NONE, 1, Menu.NONE, "标记为水分");
        } else {
            menu.add(Menu.NONE, 1, Menu.NONE, "标记为干货");
        }

        if (!segment.isEffectiveDry()) {
            if (segment.isSkipThisTime()) {
                menu.add(Menu.NONE, 2, Menu.NONE, "取消本次不跳过");
            } else {
                menu.add(Menu.NONE, 2, Menu.NONE, "本次不跳过");
            }
        }

        popup.setOnMenuItemClickListener(item -> {
            if (listener == null) return false;
            switch (item.getItemId()) {
                case 1:
                    if (segment.isEffectiveDry()) {
                        listener.onMarkAsWater(position);
                    } else {
                        listener.onMarkAsDry(position);
                    }
                    return true;
                case 2:
                    listener.onSkipThisTime(position);
                    return true;
            }
            return false;
        });
        popup.show();
    }

    @Override
    public int getItemCount() {
        return segments.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        LinearLayout container;
        TextView tvTimeRange;
        TextView tvLabel;
        TextView tvType;
        ImageView ivManualMark;
        TextView tvSkipNotice;

        ViewHolder(View itemView) {
            super(itemView);
            container = itemView.findViewById(R.id.segment_container);
            tvTimeRange = itemView.findViewById(R.id.tv_segment_time);
            tvLabel = itemView.findViewById(R.id.tv_segment_label);
            tvType = itemView.findViewById(R.id.tv_segment_type);
            ivManualMark = itemView.findViewById(R.id.iv_manual_mark);
            tvSkipNotice = itemView.findViewById(R.id.tv_skip_notice);
        }
    }

    private String formatTimeRange(long startMs, long endMs) {
        long startSec = startMs / 1000;
        long endSec = endMs / 1000;
        return String.format("%02d:%02d - %02d:%02d",
                startSec / 60, startSec % 60,
                endSec / 60, endSec % 60);
    }
}
