package com.radio.app.activities;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.radio.app.R;
import com.radio.app.database.RadioDatabaseHelper;

import java.util.ArrayList;
import java.util.List;

public class DislikedEpisodesActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private DislikedAdapter adapter;
    private RadioDatabaseHelper dbHelper;
    private List<String[]> dislikedList = new ArrayList<>();
    private TextView tvTitle;
    private ImageButton btnBack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_disliked_episodes);

        tvTitle = findViewById(R.id.tv_title);
        btnBack = findViewById(R.id.btn_back);
        if (tvTitle != null) tvTitle.setText("不喜欢的节目");
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        dbHelper = RadioDatabaseHelper.getInstance(this);
        recyclerView = findViewById(R.id.recycler_view);
        adapter = new DislikedAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        loadDislikedEpisodes();
    }

    private void loadDislikedEpisodes() {
        dislikedList.clear();
        android.database.sqlite.SQLiteDatabase db = dbHelper.getReadableDatabase();
        android.database.Cursor cursor = db.query("disliked_episodes", null, null, null, null, null, "created_at DESC");
        while (cursor.moveToNext()) {
            String id = cursor.getString(0);
            String title = cursor.getString(1);
            String station = cursor.getString(2);
            dislikedList.add(new String[]{id, title != null ? title : "", station != null ? station : ""});
        }
        cursor.close();
        adapter.notifyDataSetChanged();

        View tvEmpty = findViewById(R.id.tv_empty);
        if (tvEmpty != null) {
            tvEmpty.setVisibility(dislikedList.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    class DislikedAdapter extends RecyclerView.Adapter<DislikedAdapter.ViewHolder> {
        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_disliked_episode, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
            String[] item = dislikedList.get(pos);
            h.tvTitle.setText(item[1]);
            h.tvStation.setText(item[2]);
            h.btnRemove.setOnClickListener(v -> {
                dbHelper.removeDislikedEpisode(item[0]);
                dislikedList.remove(pos);
                notifyDataSetChanged();
                Toast.makeText(DislikedEpisodesActivity.this, "已取消不喜欢", Toast.LENGTH_SHORT).show();
                if (dislikedList.isEmpty()) {
                    View tvEmpty = findViewById(R.id.tv_empty);
                    if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
                }
            });
        }

        @Override public int getItemCount() { return dislikedList.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle, tvStation;
            Button btnRemove;
            ViewHolder(@NonNull View v) {
                super(v);
                tvTitle = v.findViewById(R.id.tv_title);
                tvStation = v.findViewById(R.id.tv_station);
                btnRemove = v.findViewById(R.id.btn_remove);
            }
        }
    }
}
