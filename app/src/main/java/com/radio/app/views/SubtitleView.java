package com.radio.app.views;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;

import com.radio.app.R;
import com.radio.app.models.Transcript;

import java.util.List;

public class SubtitleView extends LinearLayout {
    private ScrollView scrollView;
    private LinearLayout container;
    private TextView tvCurrent;
    private int highlightIdx = -1;

    public interface OnSubtitleClickListener { void onSubtitleClick(long startTime); }

    public SubtitleView(Context ctx) { super(ctx); init(ctx); }
    public SubtitleView(Context ctx, @Nullable AttributeSet attrs) { super(ctx, attrs); init(ctx); }
    public SubtitleView(Context ctx, @Nullable AttributeSet attrs, int defStyleAttr) { super(ctx, attrs, defStyleAttr); init(ctx); }

    private void init(Context ctx) {
        setOrientation(VERTICAL);
        LayoutInflater.from(ctx).inflate(R.layout.view_subtitle, this, true);
        scrollView = findViewById(R.id.scroll_view);
        container = findViewById(R.id.subtitle_container);
        tvCurrent = findViewById(R.id.tv_current_subtitle);
    }

    public void setSubtitles(List<Transcript> transcripts) {
        container.removeAllViews();
        if (transcripts == null || transcripts.isEmpty()) { tvCurrent.setText("暂无字幕"); tvCurrent.setVisibility(VISIBLE); return; }
        tvCurrent.setVisibility(GONE);
        for (int i = 0; i < transcripts.size(); i++) container.addView(createItem(transcripts.get(i), i));
    }

    private View createItem(Transcript t, int idx) {
        CardView card = new CardView(getContext());
        card.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        card.setCardBackgroundColor(getResources().getColor(R.color.card_dark));
        card.setRadius(8);
        card.setContentPadding(16, 12, 16, 12);
        card.setTag(idx);
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(VERTICAL);
        TextView tvTime = new TextView(getContext()); tvTime.setText(fmtTime(t.getSegmentStart())); tvTime.setTextColor(getResources().getColor(R.color.text_secondary)); tvTime.setTextSize(12);
        TextView tvText = new TextView(getContext()); tvText.setText(t.getText()); tvText.setTextColor(getResources().getColor(R.color.text_primary)); tvText.setTextSize(14); tvText.setPadding(0, 4, 0, 0);
        layout.addView(tvTime); layout.addView(tvText); card.addView(layout);
        card.setOnClickListener(v -> { Object tag = getTag(); if (tag instanceof OnSubtitleClickListener) ((OnSubtitleClickListener) tag).onSubtitleClick(t.getSegmentStart()); });
        return card;
    }

    public void highlightSubtitle(long ms) {
        int idx = (int) (ms / 30);
        if (idx == highlightIdx || idx >= container.getChildCount()) return;
        if (highlightIdx >= 0 && highlightIdx < container.getChildCount()) ((CardView) container.getChildAt(highlightIdx)).setCardBackgroundColor(getResources().getColor(R.color.card_dark));
        if (idx >= 0 && idx < container.getChildCount()) { ((CardView) container.getChildAt(idx)).setCardBackgroundColor(getResources().getColor(R.color.primary_dark)); scrollView.post(() -> scrollView.smoothScrollTo(0, container.getChildAt(idx).getTop())); }
        highlightIdx = idx;
    }

    public void addRealtimeSubtitle(Transcript t) { container.addView(createItem(t, container.getChildCount())); scrollView.post(() -> scrollView.fullScroll(FOCUS_DOWN)); }
    public void setOnSubtitleClickListener(OnSubtitleClickListener l) { setTag(l); }
    public void clearSubtitles() { container.removeAllViews(); highlightIdx = -1; }
    private String fmtTime(long s) { return String.format("%02d:%02d", s / 60, s % 60); }
}
