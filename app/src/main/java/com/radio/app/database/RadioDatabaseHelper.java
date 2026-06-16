package com.radio.app.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.radio.app.models.PlayProgress;
import com.radio.app.models.Transcript;
import com.radio.app.models.VoiceSegment;

import java.util.ArrayList;
import java.util.List;

public class RadioDatabaseHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "radio_app.db";
    private static final int DATABASE_VERSION = 2;
    private static final String TABLE_PLAY_PROGRESS = "play_progress";
    private static final String TABLE_TRANSCRIPTS = "transcripts";
    private static final String TABLE_DISLIKED_EPISODES = "disliked_episodes";
    private static final String TABLE_VOICE_SEGMENTS_MANUAL = "voice_segments_manual";

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
        db.execSQL("CREATE TABLE " + TABLE_DISLIKED_EPISODES + " (episode_id TEXT PRIMARY KEY, title TEXT, station_name TEXT, created_at INTEGER)");
        db.execSQL("CREATE TABLE " + TABLE_VOICE_SEGMENTS_MANUAL + " (episode_id TEXT, segment_start INTEGER, segment_end INTEGER, has_voice INTEGER, PRIMARY KEY(episode_id, segment_start))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_DISLIKED_EPISODES + " (episode_id TEXT PRIMARY KEY, title TEXT, station_name TEXT, created_at INTEGER)");
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_VOICE_SEGMENTS_MANUAL + " (episode_id TEXT, segment_start INTEGER, segment_end INTEGER, has_voice INTEGER, PRIMARY KEY(episode_id, segment_start))");
        }
    }

    // ==================== 播放进度 ====================

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

    // ==================== 字幕 ====================

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

    // ==================== 不喜欢节目 ====================

    /**
     * 添加不喜欢节目
     */
    public void addDislikedEpisode(String episodeId, String title, String stationName) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("episode_id", episodeId);
        values.put("title", title);
        values.put("station_name", stationName);
        values.put("created_at", System.currentTimeMillis());
        db.replace(TABLE_DISLIKED_EPISODES, null, values);
    }

    /**
     * 移除不喜欢节目
     */
    public void removeDislikedEpisode(String episodeId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_DISLIKED_EPISODES, "episode_id = ?", new String[]{episodeId});
    }

    /**
     * 获取所有不喜欢节目ID列表
     */
    public List<String> getAllDislikedEpisodes() {
        List<String> episodeIds = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_DISLIKED_EPISODES, new String[]{"episode_id"}, null, null, null, null, "created_at DESC");
        while (cursor.moveToNext()) {
            episodeIds.add(cursor.getString(0));
        }
        cursor.close();
        return episodeIds;
    }

    /**
     * 判断节目是否已被标记为不喜欢
     */
    public boolean isEpisodeDisliked(String episodeId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_DISLIKED_EPISODES, new String[]{"episode_id"}, "episode_id = ?", new String[]{episodeId}, null, null, null);
        boolean exists = cursor.getCount() > 0;
        cursor.close();
        return exists;
    }

    // ==================== 手动语音分段标记 ====================

    /**
     * 保存手动语音分段标记
     */
    public void saveManualSegmentMark(String episodeId, long segmentStart, long segmentEnd, boolean hasVoice) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("episode_id", episodeId);
        values.put("segment_start", segmentStart);
        values.put("segment_end", segmentEnd);
        values.put("has_voice", hasVoice ? 1 : 0);
        db.replace(TABLE_VOICE_SEGMENTS_MANUAL, null, values);
    }

    /**
     * 获取某个节目的所有手动分段标记
     */
    public List<VoiceSegment> getManualSegmentMarks(String episodeId) {
        List<VoiceSegment> segments = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_VOICE_SEGMENTS_MANUAL, null, "episode_id = ?", new String[]{episodeId}, null, null, "segment_start ASC");
        while (cursor.moveToNext()) {
            VoiceSegment segment = new VoiceSegment();
            segment.setStart(cursor.getLong(1));
            segment.setEnd(cursor.getLong(2));
            segment.setHasVoice(cursor.getInt(3) == 1);
            segment.setManuallyMarked(true);
            segment.setLabel(segment.isHasVoice() ? "手动标记-干货" : "手动标记-水分");
            segments.add(segment);
        }
        cursor.close();
        return segments;
    }

    /**
     * 移除某个节目的所有手动分段标记
     */
    public void removeManualSegmentMarks(String episodeId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_VOICE_SEGMENTS_MANUAL, "episode_id = ?", new String[]{episodeId});
    }

    /**
     * 移除某个节目的特定手动分段标记
     */
    public void removeManualSegmentMark(String episodeId, long segmentStart) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_VOICE_SEGMENTS_MANUAL, "episode_id = ? AND segment_start = ?",
                new String[]{episodeId, String.valueOf(segmentStart)});
    }
}
