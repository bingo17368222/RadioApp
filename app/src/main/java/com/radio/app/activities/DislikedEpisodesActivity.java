package com.radio.app.activities;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.radio.app.R;
import com.radio.app.models.AppSettings;
import com.radio.app.models.Episode;
import com.radio.app.network.EpisodeApiService;
import com.radio.app.utils.PreferenceManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;

public class DislikedEpisodesActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private DislikedEpisodeAdapter adapter;
    private List<Episode> dislikedEpisodes = new ArrayList<>();
    private Set<String> dislikedIds = new HashSet<>();
    private PreferenceManager prefMgr;
    private AppSettings settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_disliked_episodes);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle("不喜欢的节目");

        prefMgr = new PreferenceManager(this);
        settings = prefMgr.loadSettings();
        dislikedIds.addAll(settings.getDislikedEpisodes());

        recyclerView = findViewById(R.id.recycler_view);
        tvEmpty = findViewById(R.id.layout_empty);
        adapter = new DislikedEpisodeAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        loadDislikedEpisodes();
    }

    private void loadDislikedEpisodes() {
        // 从API加载所有节目，筛选出不喜欢的
        // 这里使用模拟数据
        dislikedEpisodes.clear();

        // 根据dislikedIds生成模拟的Episode对象
        for (String id : dislikedIds) {
            Episode e = new Episode();
            e.setId(id);
            // 从id中解析日期部分
            if (id.startsWith("ep")) {
                String[] parts = id.split("-");
                if (parts.length >= 3) {
                    String date = parts[1] + "-" + parts[2];
                    e.setTitle("节目 " + parts[parts.length - 1]);
                    e.setBroadcastAt(date.replace("-", "-") + "T08:00:00");
                    e.setStationName("新闻综合广播");
                } else {
                    e.setTitle("未知节目");
                    e.setBroadcastAt("2024-01-01T08:00:00");
                    e.setStationName("未知电台");
                }
            } else {
                e.setTitle("节目 " + id);
                e.setBroadcastAt("2024-01-01T08:00:00");
                e.setStationName("新闻综合广播");
            }
            dislikedEpisodes.add(e);
        }

        updateEmptyState();
        adapter.notifyDataSetChanged();
    }

    private void updateEmptyState() {
        if (dislikedEpisodes.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
        }
    }

    private void removeDisliked(String episodeId) {
        dislikedIds.remove(episodeId);
        settings.setDislikedEpisodes(new ArrayList<>(dislikedIds));
        prefMgr.saveSettings(settings);

        // 从列表中移除
        for (int i = 0; i < dislikedEpisodes.size(); i++) {
            if (dislikedEpisodes.get(i).getId().equals(episodeId)) {
                dislikedEpisodes.remove(i);
                break;
            }
        }
        adapter.notifyDataSetChanged();
        updateEmptyState();
        Toast.makeText(this, "已取消标记", Toast.LENGTH_SHORT).show();
    }

    class DislikedEpisodeAdapter extends RecyclerView.Adapter<DislikedEpisodeAdapter.ViewHolder> {
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(DislikedEpisodesActivity.this)
                    .inflate(R.layout.item_disliked_episode, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Episode e = dislikedEpisodes.get(position);
            holder.tvTitle.setText(e.getTitle());
            holder.tvStation.setText(e.getStationName());

            try {
                SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault());
                SimpleDateFormat out = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
                Date d = in.parse(e.getBroadcastAt());
                holder.tvTime.setText(out.format(d));
            } catch (Exception ex) {
                holder.tvTime.setText(e.getBroadcastAt());
            }

            holder.btnUndislike.setOnClickListener(v -> removeDisliked(e.getId()));
        }

        @Override
        public int getItemCount() {
            return dislikedEpisodes.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle, tvStation, tvTime;
            Button btnUndislike;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvTitle = itemView.findViewById(R.id.tv_title);
                tvStation = itemView.findViewById(R.id.tv_station);
                tvTime = itemView.findViewById(R.id.tv_time);
                btnUndislike = itemView.findViewById(R.id.btn_undislike);
            }
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
