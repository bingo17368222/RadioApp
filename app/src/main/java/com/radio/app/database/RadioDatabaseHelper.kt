package com.radio.app.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.radio.app.models.PlayProgress
import com.radio.app.models.Transcript
import com.radio.app.models.VoiceSegment

class RadioDatabaseHelper private constructor(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION
) {

    companion object {
        private const val DATABASE_NAME = "radio_app.db"
        private const val DATABASE_VERSION = 3
        private const val TABLE_PLAY_PROGRESS = "play_progress"
        private const val TABLE_TRANSCRIPTS = "transcripts"
        private const val TABLE_DISLIKED_EPISODES = "disliked_episodes"
        private const val TABLE_VOICE_SEGMENTS_MANUAL = "voice_segments_manual"
        private const val TABLE_VOICE_SEGMENTS_AI = "voice_segments_ai"

        private var instance: RadioDatabaseHelper? = null

        @Synchronized
        fun getInstance(context: Context): RadioDatabaseHelper {
            return instance ?: RadioDatabaseHelper(context.applicationContext).also { instance = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE $TABLE_PLAY_PROGRESS (episode_id TEXT PRIMARY KEY, progress INTEGER NOT NULL, recorded_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE $TABLE_TRANSCRIPTS (id INTEGER PRIMARY KEY AUTOINCREMENT, episode_id TEXT NOT NULL, segment_start INTEGER NOT NULL, segment_end INTEGER NOT NULL, text TEXT NOT NULL)")
        db.execSQL("CREATE INDEX idx_transcripts_episode ON $TABLE_TRANSCRIPTS(episode_id)")
        db.execSQL("CREATE TABLE $TABLE_DISLIKED_EPISODES (episode_id TEXT PRIMARY KEY, title TEXT, station_name TEXT, created_at INTEGER)")
        db.execSQL("CREATE TABLE $TABLE_VOICE_SEGMENTS_MANUAL (episode_id TEXT, segment_start INTEGER, segment_end INTEGER, has_voice INTEGER, PRIMARY KEY(episode_id, segment_start))")
        db.execSQL("CREATE TABLE $TABLE_VOICE_SEGMENTS_AI (episode_id TEXT, segment_start INTEGER, segment_end INTEGER, has_voice INTEGER, label TEXT, is_simulated INTEGER, PRIMARY KEY(episode_id, segment_start))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_DISLIKED_EPISODES (episode_id TEXT PRIMARY KEY, title TEXT, station_name TEXT, created_at INTEGER)")
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_VOICE_SEGMENTS_MANUAL (episode_id TEXT, segment_start INTEGER, segment_end INTEGER, has_voice INTEGER, PRIMARY KEY(episode_id, segment_start))")
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_VOICE_SEGMENTS_AI (episode_id TEXT, segment_start INTEGER, segment_end INTEGER, has_voice INTEGER, label TEXT, is_simulated INTEGER, PRIMARY KEY(episode_id, segment_start))")
        }
    }

    // ===== Play Progress =====

    fun savePlayProgress(progress: PlayProgress) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("episode_id", progress.episodeId)
            put("progress", progress.progress)
            put("recorded_at", progress.recordedAt)
        }
        db.replace(TABLE_PLAY_PROGRESS, null, values)
    }

    fun getPlayProgress(episodeId: String): PlayProgress? {
        val db = readableDatabase
        val cursor = db.query(TABLE_PLAY_PROGRESS, null, "episode_id = ?", arrayOf(episodeId), null, null, null)
        var progress: PlayProgress? = null
        if (cursor.moveToFirst()) {
            progress = PlayProgress().apply {
                this.episodeId = cursor.getString(0)
                this.progress = cursor.getLong(1)
                this.recordedAt = cursor.getLong(2)
            }
        }
        cursor.close()
        return progress
    }

    // ===== Transcripts =====

    fun saveTranscript(transcript: Transcript) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("episode_id", transcript.episodeId)
            put("segment_start", transcript.segmentStart)
            put("segment_end", transcript.segmentEnd)
            put("text", transcript.text)
        }
        db.insert(TABLE_TRANSCRIPTS, null, values)
    }

    fun getTranscripts(episodeId: String): List<Transcript> {
        val transcripts = mutableListOf<Transcript>()
        val db = readableDatabase
        val cursor = db.query(TABLE_TRANSCRIPTS, null, "episode_id = ?", arrayOf(episodeId), null, null, "segment_start ASC")
        while (cursor.moveToNext()) {
            val t = Transcript().apply {
                this.episodeId = cursor.getString(1)
                segmentStart = cursor.getLong(2)
                segmentEnd = cursor.getLong(3)
                text = cursor.getString(4)
            }
            transcripts.add(t)
        }
        cursor.close()
        return transcripts
    }

    fun clearAllTranscripts() {
        val db = writableDatabase
        db.delete(TABLE_TRANSCRIPTS, null, null)
    }

    // [v2.1.3] Delete transcripts for a specific episode
    fun deleteTranscriptsByEpisode(episodeId: String) {
        val db = writableDatabase
        db.delete(TABLE_TRANSCRIPTS, "episode_id = ?", arrayOf(episodeId))
    }

    // ===== Disliked Episodes =====

    fun addDislikedEpisode(episodeId: String, title: String, stationName: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("episode_id", episodeId)
            put("title", title)
            put("station_name", stationName)
            put("created_at", System.currentTimeMillis())
        }
        db.replace(TABLE_DISLIKED_EPISODES, null, values)
    }

    fun removeDislikedEpisode(episodeId: String) {
        val db = writableDatabase
        db.delete(TABLE_DISLIKED_EPISODES, "episode_id = ?", arrayOf(episodeId))
    }

    fun getAllDislikedEpisodes(): List<PlayProgress> {
        val list = mutableListOf<PlayProgress>()
        val db = readableDatabase
        val cursor = db.query(TABLE_DISLIKED_EPISODES, null, null, null, null, null, "created_at DESC")
        while (cursor.moveToNext()) {
            val p = PlayProgress().apply {
                this.episodeId = cursor.getString(0)
                recordedAt = cursor.getLong(3)
            }
            list.add(p)
        }
        cursor.close()
        return list
    }

    fun isEpisodeDisliked(episodeId: String): Boolean {
        val db = readableDatabase
        val cursor = db.query(TABLE_DISLIKED_EPISODES, null, "episode_id = ?", arrayOf(episodeId), null, null, null)
        val exists = cursor.moveToFirst()
        cursor.close()
        return exists
    }

    // ===== Manual Segment Marks =====

    fun saveManualSegmentMark(episodeId: String, segmentStart: Long, segmentEnd: Long, hasVoice: Boolean) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("episode_id", episodeId)
            put("segment_start", segmentStart)
            put("segment_end", segmentEnd)
            put("has_voice", if (hasVoice) 1 else 0)
        }
        db.replace(TABLE_VOICE_SEGMENTS_MANUAL, null, values)
    }

    fun getManualSegmentMarks(episodeId: String): Cursor {
        val db = readableDatabase
        return db.query(TABLE_VOICE_SEGMENTS_MANUAL, null, "episode_id = ?", arrayOf(episodeId), null, null, "segment_start ASC")
    }

    fun removeManualSegmentMarks(episodeId: String) {
        val db = writableDatabase
        db.delete(TABLE_VOICE_SEGMENTS_MANUAL, "episode_id = ?", arrayOf(episodeId))
    }

    // ===== AI Voice Segments =====

    fun saveVoiceSegment(episodeId: String, segment: VoiceSegment) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("episode_id", episodeId)
            put("segment_start", segment.start)
            put("segment_end", segment.end)
            put("has_voice", if (segment.hasVoice) 1 else 0)
            put("label", segment.label ?: "")
            put("is_simulated", if (segment.isSimulated) 1 else 0)
        }
        db.replace(TABLE_VOICE_SEGMENTS_AI, null, values)
    }

    fun saveVoiceSegments(episodeId: String, segments: List<VoiceSegment>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 先清除旧数据
            db.delete(TABLE_VOICE_SEGMENTS_AI, "episode_id = ?", arrayOf(episodeId))
            for (segment in segments) {
                val values = ContentValues().apply {
                    put("episode_id", episodeId)
                    put("segment_start", segment.start)
                    put("segment_end", segment.end)
                    put("has_voice", if (segment.hasVoice) 1 else 0)
                    put("label", segment.label ?: "")
                    put("is_simulated", if (segment.isSimulated) 1 else 0)
                }
                db.insert(TABLE_VOICE_SEGMENTS_AI, null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getVoiceSegments(episodeId: String): List<VoiceSegment> {
        val segments = mutableListOf<VoiceSegment>()
        val db = readableDatabase
        val cursor = db.query(TABLE_VOICE_SEGMENTS_AI, null, "episode_id = ?", arrayOf(episodeId), null, null, "segment_start ASC")
        while (cursor.moveToNext()) {
            val seg = VoiceSegment(
                start = cursor.getLong(cursor.getColumnIndexOrThrow("segment_start")),
                end = cursor.getLong(cursor.getColumnIndexOrThrow("segment_end")),
                hasVoice = cursor.getInt(cursor.getColumnIndexOrThrow("has_voice")) == 1,
                label = cursor.getString(cursor.getColumnIndexOrThrow("label")),
                isSimulated = cursor.getInt(cursor.getColumnIndexOrThrow("is_simulated")) == 1
            )
            segments.add(seg)
        }
        cursor.close()
        return segments
    }

    fun clearVoiceSegments(episodeId: String) {
        val db = writableDatabase
        db.delete(TABLE_VOICE_SEGMENTS_AI, "episode_id = ?", arrayOf(episodeId))
    }
}
