package com.radio.app.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.radio.app.models.PlayProgress;
import com.radio.app.models.Transcript;

import java.util.ArrayList;
import java.util.List;

public class RadioDatabaseHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "radio_app.db";
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE_PLAY_PROGRESS = "play_progress";
    private static final String TABLE_TRANSCRIPTS = "transcripts";

    private static RadioDatabaseHelper instance;

    private RadioDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    public static synchronized RadioDatabaseHelper getInstance(Context context) {
        if (instance == null) instance = new RadioDatabaseHelper(context.getApplicationContext());
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_PLAY_PROGRESS + " (episode_id TEXT PRIMARY KEY, progress INTEGER NOT NULL, recorded_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE " + TABLE_TRANSCRIPTS + " (id INTEGER PRIMARY KEY AUTOINCREMENT, episode_id TEXT NOT NULL, segment_start INTEGER NOT NULL, segment_end INTEGER NOT NULL, text TEXT NOT NULL)");
        db.execSQL("CREATE INDEX idx_transcripts_episode ON " + TABLE_TRANSCRIPTS + "(episode_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_PLAY_PROGRESS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_TRANSCRIPTS);
        onCreate(db);
    }

    public void savePlayProgress(PlayProgress progress) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("episode_id", progress.getEpisodeId());
        values.put("progress", progress.getProgress());
        values.put("recorded_at", progress.getRecordedAt());
        db.replace(TABLE_PLAY_PROGRESS, null, values);
    }

    public PlayProgress getPlayProgress(String episodeId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_PLAY_PROGRESS, null, "episode_id = ?", new String[]{episodeId}, null, null, null);
        PlayProgress progress = null;
        if (cursor.moveToFirst()) {
            progress = new PlayProgress();
            progress.setEpisodeId(cursor.getString(0));
            progress.setProgress(cursor.getLong(1));
            progress.setRecordedAt(cursor.getLong(2));
        }
        cursor.close();
        return progress;
    }

    public void saveTranscript(Transcript transcript) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("episode_id", transcript.getEpisodeId());
        values.put("segment_start", transcript.getSegmentStart());
        values.put("segment_end", transcript.getSegmentEnd());
        values.put("text", transcript.getText());
        db.insert(TABLE_TRANSCRIPTS, null, values);
    }

    public List<Transcript> getTranscripts(String episodeId) {
        List<Transcript> transcripts = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_TRANSCRIPTS, null, "episode_id = ?", new String[]{episodeId}, null, null, "segment_start ASC");
        while (cursor.moveToNext()) {
            Transcript t = new Transcript();
            t.setEpisodeId(cursor.getString(1));
            t.setSegmentStart(cursor.getLong(2));
            t.setSegmentEnd(cursor.getLong(3));
            t.setText(cursor.getString(4));
            transcripts.add(t);
        }
        cursor.close();
        return transcripts;
    }

    public void clearAllTranscripts() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_TRANSCRIPTS, null, null);
    }
}
