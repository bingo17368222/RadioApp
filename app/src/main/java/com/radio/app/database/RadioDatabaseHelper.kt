package com.radio.app.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.radio.app.models.PlayProgress
import com.radio.app.models.Transcript
import com.radio.app.models.VoiceSegment
import com.radio.app.models.Episode
import com.radio.app.utils.ChromaprintExtractor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// [v2.4.31] Engine info bundle returned to PlayerActivity for subtitle title display.
// Includes the engine name plus the total processing time and audio duration used to
// compute the speed ratio (倍率 = audio_duration / processing_time).
data class TranscriptEngineInfo(
    val engineName: String?,
    val processingTimeMs: Long,
    val audioDurationMs: Long
)

// v2.4.150: Persist audio segmentation engine and timing per episode.
data class SegmentAnalysisInfo(
    val episodeId: String,
    val engineName: String,
    val generatedAt: Long,
    val processingTimeMs: Long,
    val audioDurationMs: Long,
    val segmentCount: Int,
    val dryCount: Int,
    val waterCount: Int
)

class RadioDatabaseHelper private constructor(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION
) {

    companion object {
        private const val DATABASE_NAME = "radio_app.db"
        private const val DATABASE_VERSION = 16
        private const val TABLE_PLAY_PROGRESS = "play_progress"
        private const val TABLE_TRANSCRIPTS = "transcripts"
        private const val TABLE_DISLIKED_EPISODES = "disliked_episodes"
        private const val TABLE_VOICE_SEGMENTS_MANUAL = "voice_segments_manual"
        private const val TABLE_VOICE_SEGMENTS_AI = "voice_segments_ai"
        private const val TABLE_SEGMENT_ANALYSIS_INFO = "segment_analysis_info"
        private const val TABLE_EPISODE_INFO = "episode_info"
        // v3.0.2: 音频指纹表，用于存储用户标记的水分片段指纹素材
        private const val TABLE_AUDIO_FINGERPRINTS = "audio_fingerprints"
        // v3.1.6: 指纹分组管理表
        private const val TABLE_FINGERPRINT_GROUPS = "fingerprint_groups"
        private const val TABLE_FINGERPRINT_GROUP_MEMBERS = "fingerprint_group_members"
        // v3.2.2: 候选指纹观察池表
        private const val TABLE_FINGERPRINT_OBSERVATION_POOL = "fingerprint_observation_pool"

        // 观察池默认容量上限
        private const val OBSERVATION_POOL_MAX_CAPACITY = 1000

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
        // [v2.4.12] Store the engine used to generate subtitles for each episode
        // [v2.4.31] Added processing_time_ms & audio_duration_ms for speed ratio display
        db.execSQL("CREATE TABLE IF NOT EXISTS transcript_engine (episode_id TEXT PRIMARY KEY, engine_name TEXT NOT NULL, generated_at INTEGER NOT NULL, is_complete INTEGER DEFAULT 0, processing_time_ms INTEGER DEFAULT 0, audio_duration_ms INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE $TABLE_DISLIKED_EPISODES (episode_id TEXT PRIMARY KEY, title TEXT, station_name TEXT, created_at INTEGER)")
        db.execSQL("CREATE TABLE $TABLE_VOICE_SEGMENTS_MANUAL (episode_id TEXT, segment_start INTEGER, segment_end INTEGER, has_voice INTEGER, PRIMARY KEY(episode_id, segment_start))")
        db.execSQL("CREATE TABLE $TABLE_VOICE_SEGMENTS_AI (episode_id TEXT, segment_start INTEGER, segment_end INTEGER, has_voice INTEGER, label TEXT, is_simulated INTEGER, PRIMARY KEY(episode_id, segment_start))")
        // v2.4.150: Persist audio segmentation engine and timing per episode.
        db.execSQL("CREATE TABLE $TABLE_SEGMENT_ANALYSIS_INFO (episode_id TEXT PRIMARY KEY, engine_name TEXT NOT NULL, generated_at INTEGER NOT NULL, processing_time_ms INTEGER DEFAULT 0, audio_duration_ms INTEGER DEFAULT 0, segment_count INTEGER DEFAULT 0, dry_count INTEGER DEFAULT 0, water_count INTEGER DEFAULT 0)")
        // [v2.2.4] Episode metadata cache table
        // v2.4.148: Added start_time/end_time for offline notification time range display.
        db.execSQL("CREATE TABLE $TABLE_EPISODE_INFO (episode_id TEXT PRIMARY KEY, date TEXT NOT NULL, title TEXT, broadcast_at TEXT, duration INTEGER, start_time INTEGER DEFAULT 0, end_time INTEGER DEFAULT 0, audio_url TEXT, station_id TEXT, station_name TEXT, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_episode_info_date_station ON $TABLE_EPISODE_INFO(date, station_id)")
        // v3.0.2: 音频指纹表
        db.execSQL("CREATE TABLE $TABLE_AUDIO_FINGERPRINTS (id INTEGER PRIMARY KEY AUTOINCREMENT, episode_id TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, fingerprint TEXT NOT NULL, duration_ms INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, note TEXT DEFAULT '')")
        db.execSQL("CREATE INDEX idx_audio_fingerprints_episode ON $TABLE_AUDIO_FINGERPRINTS(episode_id)")

        // v3.1.7: 指纹分组管理表（增加note列）
        db.execSQL("CREATE TABLE $TABLE_FINGERPRINT_GROUPS (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, note TEXT DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE $TABLE_FINGERPRINT_GROUP_MEMBERS (id INTEGER PRIMARY KEY AUTOINCREMENT, group_id INTEGER NOT NULL, fingerprint_id INTEGER NOT NULL, is_representative INTEGER DEFAULT 0, manually_removed INTEGER DEFAULT 0, FOREIGN KEY(group_id) REFERENCES $TABLE_FINGERPRINT_GROUPS(id) ON DELETE CASCADE, FOREIGN KEY(fingerprint_id) REFERENCES $TABLE_AUDIO_FINGERPRINTS(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX idx_fp_group_members_group ON $TABLE_FINGERPRINT_GROUP_MEMBERS(group_id)")
        db.execSQL("CREATE INDEX idx_fp_group_members_fp ON $TABLE_FINGERPRINT_GROUP_MEMBERS(fingerprint_id)")
        // v3.2.2: 候选指纹观察池表
        db.execSQL("CREATE TABLE $TABLE_FINGERPRINT_OBSERVATION_POOL (id INTEGER PRIMARY KEY AUTOINCREMENT, fingerprint_hash TEXT NOT NULL, fingerprint TEXT NOT NULL, episode_id TEXT NOT NULL, duration_ms INTEGER NOT NULL, similarity REAL NOT NULL DEFAULT 0.82, hit_count INTEGER DEFAULT 1, last_hit_time INTEGER NOT NULL, expired_at INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_fp_obs_pool_hash ON $TABLE_FINGERPRINT_OBSERVATION_POOL(fingerprint_hash)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_DISLIKED_EPISODES (episode_id TEXT PRIMARY KEY, title TEXT, station_name TEXT, created_at INTEGER)")
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_VOICE_SEGMENTS_MANUAL (episode_id TEXT, segment_start INTEGER, segment_end INTEGER, has_voice INTEGER, PRIMARY KEY(episode_id, segment_start))")
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_VOICE_SEGMENTS_AI (episode_id TEXT, segment_start INTEGER, segment_end INTEGER, has_voice INTEGER, label TEXT, is_simulated INTEGER, PRIMARY KEY(episode_id, segment_start))")
        }
        if (oldVersion < 4) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_EPISODE_INFO (episode_id TEXT PRIMARY KEY, date TEXT NOT NULL, title TEXT, broadcast_at TEXT, duration INTEGER, audio_url TEXT, station_id TEXT, station_name TEXT, updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_episode_info_date_station ON $TABLE_EPISODE_INFO(date, station_id)")
        }
        // [v2.4.12] Add transcript_engine table for tracking subtitle generation engine
        if (oldVersion < 5) {
            db.execSQL("CREATE TABLE IF NOT EXISTS transcript_engine (episode_id TEXT PRIMARY KEY, engine_name TEXT NOT NULL, generated_at INTEGER NOT NULL, is_complete INTEGER DEFAULT 0)")
        }
        // [v2.4.18] Add is_complete column to existing transcript_engine table
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE transcript_engine ADD COLUMN is_complete INTEGER DEFAULT 0")
        }
        // [v2.4.31] Add processing_time_ms & audio_duration_ms columns for speed ratio display
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE transcript_engine ADD COLUMN processing_time_ms INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE transcript_engine ADD COLUMN audio_duration_ms INTEGER DEFAULT 0")
        }
        // v2.4.44: Add segment_count column to episode_info for episode list display
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE episode_info ADD COLUMN segment_count INTEGER DEFAULT 0")
        }
        // v2.4.148: Add start_time/end_time columns for offline notification time range display.
        if (oldVersion < 9) {
            try { db.execSQL("ALTER TABLE $TABLE_EPISODE_INFO ADD COLUMN start_time INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE_EPISODE_INFO ADD COLUMN end_time INTEGER DEFAULT 0") } catch (_: Exception) {}
        }
        // v2.4.150: Add segment_analysis_info table for audio segmentation engine & timing persistence.
        if (oldVersion < 10) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_SEGMENT_ANALYSIS_INFO (episode_id TEXT PRIMARY KEY, engine_name TEXT NOT NULL, generated_at INTEGER NOT NULL, processing_time_ms INTEGER DEFAULT 0, audio_duration_ms INTEGER DEFAULT 0, segment_count INTEGER DEFAULT 0, dry_count INTEGER DEFAULT 0, water_count INTEGER DEFAULT 0)")
        }
        // v3.0.2: Add audio_fingerprints table for water segment fingerprint management.
        if (oldVersion < 11) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_AUDIO_FINGERPRINTS (id INTEGER PRIMARY KEY AUTOINCREMENT, episode_id TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, fingerprint TEXT NOT NULL, duration_ms INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, note TEXT DEFAULT '')")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_audio_fingerprints_episode ON $TABLE_AUDIO_FINGERPRINTS(episode_id)")
        }
        // v3.1.3: Add note column to audio_fingerprints for user remarks.
        if (oldVersion < 12) {
            try { db.execSQL("ALTER TABLE $TABLE_AUDIO_FINGERPRINTS ADD COLUMN note TEXT DEFAULT ''") } catch (_: Exception) {}
        }
        // v3.1.6: Add fingerprint group management tables
        if (oldVersion < 13) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_FINGERPRINT_GROUPS (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_FINGERPRINT_GROUP_MEMBERS (id INTEGER PRIMARY KEY AUTOINCREMENT, group_id INTEGER NOT NULL, fingerprint_id INTEGER NOT NULL, is_representative INTEGER DEFAULT 0, manually_removed INTEGER DEFAULT 0, FOREIGN KEY(group_id) REFERENCES $TABLE_FINGERPRINT_GROUPS(id) ON DELETE CASCADE, FOREIGN KEY(fingerprint_id) REFERENCES $TABLE_AUDIO_FINGERPRINTS(id) ON DELETE CASCADE)")
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_fp_group_members_group ON $TABLE_FINGERPRINT_GROUP_MEMBERS(group_id)") } catch (_: Exception) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_fp_group_members_fp ON $TABLE_FINGERPRINT_GROUP_MEMBERS(fingerprint_id)") } catch (_: Exception) {}
        }
        // v3.1.7: Add note column to fingerprint_groups
        if (oldVersion < 14) {
            try { db.execSQL("ALTER TABLE $TABLE_FINGERPRINT_GROUPS ADD COLUMN note TEXT DEFAULT ''") } catch (_: Exception) {}
        }
        // v3.2.2: Add is_gold_standard column to audio_fingerprints and observation pool table
        if (oldVersion < 15) {
            try { db.execSQL("ALTER TABLE $TABLE_AUDIO_FINGERPRINTS ADD COLUMN is_gold_standard INTEGER DEFAULT 1") } catch (_: Exception) {}
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_FINGERPRINT_OBSERVATION_POOL (id INTEGER PRIMARY KEY AUTOINCREMENT, fingerprint_hash TEXT NOT NULL, fingerprint TEXT NOT NULL, episode_id TEXT NOT NULL, duration_ms INTEGER NOT NULL, similarity REAL NOT NULL DEFAULT 0.82, hit_count INTEGER DEFAULT 1, last_hit_time INTEGER NOT NULL, expired_at INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_fp_obs_pool_hash ON $TABLE_FINGERPRINT_OBSERVATION_POOL(fingerprint_hash)") } catch (_: Exception) {}
        }
        // v3.1.41: Add last_matched_at column to audio_fingerprints for expiration mechanism
        if (oldVersion < 16) {
            try { db.execSQL("ALTER TABLE $TABLE_AUDIO_FINGERPRINTS ADD COLUMN last_matched_at INTEGER DEFAULT 0") } catch (_: Exception) {}
        }
    }

    // v2.4.183: Enable WAL so readers (segment button navigation on the main thread) are not
    // blocked by writers (pre-segmentation saving segments in the background).
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        try { db.enableWriteAheadLogging() } catch (_: Exception) {}
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

    // v2.4.86: Delete play progress when episode completes so user can replay from beginning
    fun deletePlayProgress(episodeId: String) {
        try {
            val db = writableDatabase
            db.delete(TABLE_PLAY_PROGRESS, "episode_id = ?", arrayOf(episodeId))
        } catch (_: Exception) {}
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

    // [v2.4.12] Save the engine used to generate subtitles for an episode
    fun saveTranscriptEngine(episodeId: String, engineName: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("episode_id", episodeId)
            put("engine_name", engineName)
            put("generated_at", System.currentTimeMillis())
            put("is_complete", 0)  // [v2.4.18] Mark as incomplete when saving engine (pre-generation)
        }
        db.replace("transcript_engine", null, values)
    }

    // [v2.4.18] Mark subtitles as complete for an episode
    fun markSubtitlesComplete(episodeId: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("is_complete", 1)
            put("generated_at", System.currentTimeMillis())
        }
        db.update("transcript_engine", values, "episode_id = ?", arrayOf(episodeId))
    }

    // [v2.4.18] Check if subtitles are complete for an episode
    fun hasCompleteSubtitles(episodeId: String): Boolean {
        val db = readableDatabase
        val cursor = db.query("transcript_engine", arrayOf("is_complete"), "episode_id = ?", arrayOf(episodeId), null, null, null)
        var isComplete = false
        if (cursor.moveToFirst()) {
            isComplete = cursor.getInt(0) == 1
        }
        cursor.close()
        return isComplete
    }

    // v2.4.83: Get subtitle segment count for an episode (for diagnostic logging)
    fun getSubtitleSegmentCount(episodeId: String): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_TRANSCRIPTS WHERE episode_id = ?", arrayOf(episodeId))
        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }
        cursor.close()
        return count
    }

    // [v2.4.12] Get the engine used to generate subtitles for an episode
    fun getTranscriptEngine(episodeId: String): String? {
        val db = readableDatabase
        val cursor = db.query("transcript_engine", arrayOf("engine_name"), "episode_id = ?", arrayOf(episodeId), null, null, null)
        var engine: String? = null
        if (cursor.moveToFirst()) {
            engine = cursor.getString(0)
        }
        cursor.close()
        return engine
    }

    // [v2.4.31] Save total processing time & audio duration for an episode's subtitle generation.
    // Called by SubtitleGeneratorService.onComplete after saveTranscriptEngine so the row already exists.
    fun saveTranscriptTiming(episodeId: String, processingTimeMs: Long, audioDurationMs: Long) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("processing_time_ms", processingTimeMs)
                put("audio_duration_ms", audioDurationMs)
            }
            db.update("transcript_engine", values, "episode_id = ?", arrayOf(episodeId))
        } catch (_: Exception) {}
    }

    // [v2.4.31] Get engine name + timing info for subtitle title display.
    // Used by PlayerActivity when restoring subtitles from DB (cold start / background restore).
    fun getTranscriptEngineInfo(episodeId: String): TranscriptEngineInfo {
        val db = readableDatabase
        val cursor = db.query(
            "transcript_engine",
            arrayOf("engine_name", "processing_time_ms", "audio_duration_ms"),
            "episode_id = ?", arrayOf(episodeId), null, null, null
        )
        var info = TranscriptEngineInfo(null, 0L, 0L)
        if (cursor.moveToFirst()) {
            info = TranscriptEngineInfo(
                if (cursor.isNull(0)) null else cursor.getString(0),
                if (cursor.isNull(1)) 0L else cursor.getLong(1),
                if (cursor.isNull(2)) 0L else cursor.getLong(2)
            )
        }
        cursor.close()
        return info
    }

    // [v2.1.3] Delete transcripts for a specific episode
    fun deleteTranscriptsByEpisode(episodeId: String) {
        val db = writableDatabase
        db.delete(TABLE_TRANSCRIPTS, "episode_id = ?", arrayOf(episodeId))
        // [v2.4.18] Also delete engine record so patrol can regenerate
        db.delete("transcript_engine", "episode_id = ?", arrayOf(episodeId))
    }

    // v2.4.85: Reset subtitle complete status (for manual cache deletion)
    fun resetSubtitlesComplete(episodeId: String) {
        val db = writableDatabase
        db.delete("transcript_engine", "episode_id = ?", arrayOf(episodeId))
    }

    // [v2.1.5] Search transcripts by text content
    // [v2.1.8] Also return episode duration info
    fun searchTranscripts(query: String): List<Transcript> {
        val results = mutableListOf<Transcript>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_TRANSCRIPTS,
            null,
            "text LIKE ?",
            arrayOf("%$query%"),
            null, null,
            "segment_start ASC",
            "100"  // limit to 100 results
        )
        while (cursor.moveToNext()) {
            val t = Transcript().apply {
                this.episodeId = cursor.getString(1)
                segmentStart = cursor.getLong(2)
                segmentEnd = cursor.getLong(3)
                text = cursor.getString(4)
            }
            results.add(t)
        }
        cursor.close()
        return results
    }

    // [v2.1.8] Get episode info: first/last transcript timestamps for duration
    fun getEpisodeTranscriptInfo(episodeId: String): Pair<Long, Long>? {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT MIN(segment_start), MAX(segment_end) FROM $TABLE_TRANSCRIPTS WHERE episode_id = ?",
            arrayOf(episodeId)
        )
        var result: Pair<Long, Long>? = null
        if (cursor.moveToFirst()) {
            val first = cursor.getLong(0)
            val last = cursor.getLong(1)
            if (first > 0 || last > 0) result = Pair(first, last)
        }
        cursor.close()
        return result
    }

    // [v2.4.20] Get the last transcript end time (in ms) for resume support
    fun getMaxTranscriptEndMs(episodeId: String): Long {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT MAX(segment_end) FROM $TABLE_TRANSCRIPTS WHERE episode_id = ?",
            arrayOf(episodeId)
        )
        var maxEnd = 0L
        if (cursor.moveToFirst()) {
            maxEnd = cursor.getLong(0)
        }
        cursor.close()
        return maxEnd
    }

    // [v2.4.20] Get transcript count for an episode
    fun getTranscriptCount(episodeId: String): Int {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_TRANSCRIPTS WHERE episode_id = ?",
            arrayOf(episodeId)
        )
        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }
        cursor.close()
        return count
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
        // v2.4.183: Use non-exclusive transaction so concurrent reads on the main thread
        // (e.g. segment button navigation) are not blocked while segments are being saved.
        db.beginTransactionNonExclusive()
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

    // v2.4.150: Persist audio segmentation engine and timing per episode.
    fun saveSegmentAnalysisInfo(info: SegmentAnalysisInfo) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("episode_id", info.episodeId)
                put("engine_name", info.engineName)
                put("generated_at", info.generatedAt)
                put("processing_time_ms", info.processingTimeMs)
                put("audio_duration_ms", info.audioDurationMs)
                put("segment_count", info.segmentCount)
                put("dry_count", info.dryCount)
                put("water_count", info.waterCount)
            }
            db.replace(TABLE_SEGMENT_ANALYSIS_INFO, null, values)
        } catch (_: Exception) {}
    }

    fun getSegmentAnalysisInfo(episodeId: String): SegmentAnalysisInfo? {
        var info: SegmentAnalysisInfo? = null
        try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_SEGMENT_ANALYSIS_INFO,
                arrayOf("episode_id", "engine_name", "generated_at", "processing_time_ms", "audio_duration_ms", "segment_count", "dry_count", "water_count"),
                "episode_id = ?", arrayOf(episodeId), null, null, null
            )
            if (cursor.moveToFirst()) {
                info = SegmentAnalysisInfo(
                    episodeId = cursor.getString(0),
                    engineName = cursor.getString(1),
                    generatedAt = cursor.getLong(2),
                    processingTimeMs = cursor.getLong(3),
                    audioDurationMs = cursor.getLong(4),
                    segmentCount = cursor.getInt(5),
                    dryCount = cursor.getInt(6),
                    waterCount = cursor.getInt(7)
                )
            }
            cursor.close()
        } catch (_: Exception) {}
        return info
    }

    fun deleteSegmentAnalysisInfo(episodeId: String) {
        try {
            val db = writableDatabase
            db.delete(TABLE_SEGMENT_ANALYSIS_INFO, "episode_id = ?", arrayOf(episodeId))
        } catch (_: Exception) {}
    }

    // v2.4.155: Total duration of AI-detected dry segments for an episode.
    fun getDrySegmentsTotalDuration(episodeId: String): Long {
        var total = 0L
        try {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT segment_start, segment_end FROM $TABLE_VOICE_SEGMENTS_AI WHERE episode_id = ? AND has_voice = 1 ORDER BY segment_start ASC",
                arrayOf(episodeId)
            )
            cursor.use {
                while (it.moveToNext()) {
                    val start = it.getLong(0)
                    val end = it.getLong(1)
                    if (end > start) total += (end - start)
                }
            }
        } catch (_: Exception) {}
        return total
    }

    // v2.4.155: Dry segment duration as a percentage of total audio duration.
    fun getDryPercentage(episodeId: String): Float {
        val info = getSegmentAnalysisInfo(episodeId) ?: return 0f
        if (info.audioDurationMs <= 0) return 0f
        val dryMs = getDrySegmentsTotalDuration(episodeId)
        return dryMs.toFloat() / info.audioDurationMs.toFloat() * 100f
    }

    // v2.4.44: Update segment count for an episode (for episode list display)
    fun updateEpisodeSegmentCount(episodeId: String, count: Int) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("segment_count", count)
            }
            db.update(TABLE_EPISODE_INFO, values, "episode_id = ?", arrayOf(episodeId))
        } catch (_: Exception) {}
    }

    // v2.4.44: Get segment count for an episode
    fun getEpisodeSegmentCount(episodeId: String): Int {
        try {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT segment_count FROM $TABLE_EPISODE_INFO WHERE episode_id = ?",
                arrayOf(episodeId)
            )
            cursor.use {
                if (it.moveToFirst()) return it.getInt(0)
            }
        } catch (_: Exception) {}
        return 0
    }

    // ===== Episode Info (v2.2.4) =====

    // [Fix] Returns the effective broadcastAt and title for an episode, filling in the
    // current date and a default title ("广播节目录音_YYYY-MM-DD") when they are blank.
    // This guarantees episode_info rows always carry a non-empty date and title, even for
    // episodes auto-created from background/pre-cache recordings that arrived without them.
    // Uses isNullOrBlank() (rather than isBlank()) because Gson can assign null into a
    // non-nullable Kotlin String field when deserializing, which would NPE on isBlank().
    private fun normalizeEpisodeFields(episode: Episode): Pair<String, String> {
        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val effectiveBroadcastAt =
            if (episode.broadcastAt.isNullOrBlank()) currentDate else episode.broadcastAt
        val effectiveTitle =
            if (episode.title.isNullOrBlank()) "广播节目录音_$currentDate" else episode.title
        return Pair(effectiveBroadcastAt, effectiveTitle)
    }

    fun saveEpisodeInfo(episode: Episode) {
        try {
            val db = writableDatabase
            val (effectiveBroadcastAt, effectiveTitle) = normalizeEpisodeFields(episode)
            // v3.1.41-fix: 如果duration为0，尝试保留DB中已有的duration，避免覆盖正确数据
            var finalDuration = episode.duration
            if (finalDuration <= 0) {
                try {
                    val cursor = db.query(TABLE_EPISODE_INFO, arrayOf("duration"),
                        "episode_id = ?", arrayOf(episode.id), null, null, null)
                    if (cursor.moveToFirst()) {
                        val existingDuration = cursor.getLong(cursor.getColumnIndexOrThrow("duration"))
                        if (existingDuration > 0) {
                            finalDuration = existingDuration
                        }
                    }
                    cursor.close()
                } catch (_: Exception) {}
            }
            val values = ContentValues().apply {
                put("episode_id", episode.id)
                put("date", effectiveBroadcastAt.substringBefore("T").take(10))
                put("title", effectiveTitle)
                put("broadcast_at", effectiveBroadcastAt)
                put("duration", finalDuration)
                // v2.4.148: Persist start/end timestamps for offline notification display.
                put("start_time", episode.startTime)
                put("end_time", episode.endTime)
                put("audio_url", episode.audioUrl)
                put("station_id", episode.stationId)
                put("station_name", episode.stationName)
                put("updated_at", System.currentTimeMillis())
            }
            db.replace(TABLE_EPISODE_INFO, null, values)
        } catch (_: Exception) {}
    }

    fun saveEpisodeInfos(episodes: List<Episode>) {
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (episode in episodes) {
                    // v3.1.119: 不再跳过时长为0的节目。即使API返回duration=0也应入库，
                    // 后续可通过getEpisodeInfo从DB获取到节目信息（含startTime）。
                    // 如果duration为0，尝试保留DB中已有的duration（同saveEpisodeInfo单例版逻辑）。
                    if (episode.broadcastAt.isNullOrBlank()) {
                        continue
                    }
                    var finalDuration = episode.duration
                    if (finalDuration <= 0) {
                        try {
                            val cursor = db.query(TABLE_EPISODE_INFO, arrayOf("duration"),
                                "episode_id = ?", arrayOf(episode.id), null, null, null)
                            if (cursor.moveToFirst()) {
                                val existingDuration = cursor.getLong(cursor.getColumnIndexOrThrow("duration"))
                                if (existingDuration > 0) {
                                    finalDuration = existingDuration
                                }
                            }
                            cursor.close()
                        } catch (_: Exception) {}
                    }
                    val (effectiveBroadcastAt, effectiveTitle) = normalizeEpisodeFields(episode)
                    val values = ContentValues().apply {
                        put("episode_id", episode.id)
                        put("date", effectiveBroadcastAt.substringBefore("T").take(10))
                        put("title", effectiveTitle)
                        put("broadcast_at", effectiveBroadcastAt)
                        put("duration", finalDuration)
                        // v2.4.148: Persist start/end timestamps for offline notification display.
                        put("start_time", episode.startTime)
                        put("end_time", episode.endTime)
                        put("audio_url", episode.audioUrl)
                        put("station_id", episode.stationId)
                        put("station_name", episode.stationName)
                        put("updated_at", System.currentTimeMillis())
                    }
                    db.replace(TABLE_EPISODE_INFO, null, values)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (_: Exception) {}
    }

    fun getEpisodeInfo(episodeId: String): Episode? {
        try {
            val db = readableDatabase
            val cursor = db.query(TABLE_EPISODE_INFO, null, "episode_id = ?", arrayOf(episodeId), null, null, null)
            var ep: Episode? = null
            if (cursor.moveToFirst()) ep = cursorToEpisode(cursor)
            cursor.close()
            return ep
        } catch (_: Exception) {
            return null
        }
    }

    // v2.4.92: Find episode by audio filename (for orphaned cached files not in preCacheList)
    fun getEpisodeByAudioFileName(fileName: String): Episode? {
        try {
            val db = readableDatabase
            val cursor = db.query(TABLE_EPISODE_INFO, null, "audio_url LIKE ?", arrayOf("%$fileName%"), null, null, null)
            var ep: Episode? = null
            if (cursor.moveToFirst()) ep = cursorToEpisode(cursor)
            cursor.close()
            return ep
        } catch (_: Exception) {
            return null
        }
    }

    fun getEpisodesByDateAndStation(stationId: String, date: String): List<Episode> {
        val list = mutableListOf<Episode>()
        try {
            val db = readableDatabase
            val cursor = db.query(TABLE_EPISODE_INFO, null, "station_id = ? AND date = ?",
                arrayOf(stationId, date), null, null, "broadcast_at ASC")
            while (cursor.moveToNext()) list.add(cursorToEpisode(cursor))
            cursor.close()
        } catch (_: Exception) {}
        return list
    }

    private fun cursorToEpisode(c: Cursor): Episode = Episode().apply {
        id = c.getString(c.getColumnIndexOrThrow("episode_id"))
        title = c.getString(c.getColumnIndexOrThrow("title"))
        broadcastAt = c.getString(c.getColumnIndexOrThrow("broadcast_at"))
        duration = c.getLong(c.getColumnIndexOrThrow("duration"))
        // v2.4.148: Restore start/end timestamps from DB cache.
        startTime = c.getLong(c.getColumnIndexOrThrow("start_time"))
        endTime = c.getLong(c.getColumnIndexOrThrow("end_time"))
        audioUrl = c.getString(c.getColumnIndexOrThrow("audio_url"))
        stationId = c.getString(c.getColumnIndexOrThrow("station_id"))
        stationName = c.getString(c.getColumnIndexOrThrow("station_name"))
    }

    // ===== Audio Fingerprints (v3.0.2) =====

    fun saveAudioFingerprint(fingerprint: AudioFingerprint): Long {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("episode_id", fingerprint.episodeId)
            put("start_ms", fingerprint.startMs)
            put("end_ms", fingerprint.endMs)
            put("fingerprint", fingerprint.fingerprint)
            put("duration_ms", fingerprint.durationMs)
            put("created_at", fingerprint.createdAt.takeIf { it > 0 } ?: now)
            put("updated_at", now)
            put("note", fingerprint.note)
            put("is_gold_standard", if (fingerprint.isGoldStandard) 1 else 0)
            // v3.1.41: 保存最后匹配时间
            if (fingerprint.lastMatchedAt > 0) {
                put("last_matched_at", fingerprint.lastMatchedAt)
            }
        }
        return db.replace(TABLE_AUDIO_FINGERPRINTS, null, values)
    }

    // v3.1.3: 更新指纹备注
    fun updateFingerprintNote(id: Long, note: String): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("note", note)
            put("updated_at", System.currentTimeMillis())
        }
        return db.update(TABLE_AUDIO_FINGERPRINTS, values, "id = ?", arrayOf(id.toString()))
    }

    fun updateAudioFingerprint(id: Long, episodeId: String, startMs: Long, endMs: Long, fingerprint: String, durationMs: Long): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("episode_id", episodeId)
            put("start_ms", startMs)
            put("end_ms", endMs)
            put("fingerprint", fingerprint)
            put("duration_ms", durationMs)
            put("updated_at", System.currentTimeMillis())
        }
        return db.update(TABLE_AUDIO_FINGERPRINTS, values, "id = ?", arrayOf(id.toString()))
    }

    fun deleteAudioFingerprint(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_AUDIO_FINGERPRINTS, "id = ?", arrayOf(id.toString()))
    }

    fun deleteAudioFingerprintsByEpisode(episodeId: String): Int {
        val db = writableDatabase
        return db.delete(TABLE_AUDIO_FINGERPRINTS, "episode_id = ?", arrayOf(episodeId))
    }

    // v3.0.9: 删除指定 episode 在指定时间范围内的指纹，用于修正时避免重复入库
    fun deleteAudioFingerprintsByRange(episodeId: String, startMs: Long, endMs: Long): Int {
        val db = writableDatabase
        return db.delete(
            TABLE_AUDIO_FINGERPRINTS,
            "episode_id = ? AND start_ms = ? AND end_ms = ?",
            arrayOf(episodeId, startMs.toString(), endMs.toString())
        )
    }

    // v3.1.41: 更新指纹最后匹配时间（第一层指纹快筛命中时调用）
    fun updateFingerprintLastMatched(fingerprintId: Long) {
        try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("last_matched_at", now)
                put("updated_at", now)
            }
            db.update(TABLE_AUDIO_FINGERPRINTS, values, "id = ?", arrayOf(fingerprintId.toString()))
        } catch (_: Exception) {}
    }

    // v3.1.41: 根据指纹ID批量更新最后匹配时间（第一层滑动窗口批量命中时调用）
    fun batchUpdateFingerprintLastMatched(fingerprintIds: List<Long>) {
        if (fingerprintIds.isEmpty()) return
        try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("last_matched_at", now)
                put("updated_at", now)
            }
            val placeholders = fingerprintIds.joinToString(",") { "?" }
            db.update(TABLE_AUDIO_FINGERPRINTS, values, "id IN ($placeholders)", fingerprintIds.map { it.toString() }.toTypedArray())
        } catch (_: Exception) {}
    }

    // v3.1.41: 清理过期指纹（连续两个月零匹配的人工指纹，且last_matched_at > 0表示已记录过匹配）
    fun cleanupExpiredFingerprints(): Int {
        var deleted = 0
        try {
            val db = writableDatabase
            val twoMonthsAgo = System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000 // 约2个月
            // 删除条件：
            // 1. is_gold_standard = 1（人工指纹）
            // 2. last_matched_at > 0（已经有过匹配记录）
            // 3. last_matched_at < twoMonthsAgo（最后匹配时间在2个月前）
            deleted = db.delete(
                TABLE_AUDIO_FINGERPRINTS,
                "is_gold_standard = 1 AND last_matched_at > 0 AND last_matched_at < ?",
                arrayOf(twoMonthsAgo.toString())
            )
            if (deleted > 0) {
                Log.i("RadioDatabaseHelper", "cleanupExpiredFingerprints: 删除了$deleted 个过期人工指纹（2个月未匹配）")
            }
        } catch (e: Exception) {
            Log.e("RadioDatabaseHelper", "cleanupExpiredFingerprints failed: ${e.message}")
        }
        return deleted
    }

    fun getAudioFingerprintsByEpisode(episodeId: String): List<AudioFingerprint> {
        val list = mutableListOf<AudioFingerprint>()
        try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_AUDIO_FINGERPRINTS,
                arrayOf("id", "episode_id", "start_ms", "end_ms", "fingerprint", "duration_ms", "created_at", "updated_at", "note", "is_gold_standard", "last_matched_at"),
                "episode_id = ?",
                arrayOf(episodeId),
                null, null,
                "created_at DESC"
            )
            while (cursor.moveToNext()) {
                list.add(cursorToAudioFingerprint(cursor))
            }
            cursor.close()
        } catch (_: Exception) {}
        return list
    }

    fun getAllAudioFingerprints(): List<AudioFingerprint> {
        val list = mutableListOf<AudioFingerprint>()
        try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_AUDIO_FINGERPRINTS,
                arrayOf("id", "episode_id", "start_ms", "end_ms", "fingerprint", "duration_ms", "created_at", "updated_at", "note", "is_gold_standard", "last_matched_at"),
                null, null, null, null,
                "created_at DESC"
            )
            while (cursor.moveToNext()) {
                list.add(cursorToAudioFingerprint(cursor))
            }
            cursor.close()
        } catch (_: Exception) {}
        return list
    }

    fun getAudioFingerprintCount(): Int {
        var count = 0
        try {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_AUDIO_FINGERPRINTS", null)
            if (cursor.moveToFirst()) count = cursor.getInt(0)
            cursor.close()
        } catch (_: Exception) {}
        return count
    }

    // v3.1.3: 读取 note 列（兼容旧数据库无该列的情况）
    // v3.2.2: 读取 is_gold_standard 列（兼容旧数据库无该列的情况）
    // v3.1.41: 读取 last_matched_at 列
    private fun cursorToAudioFingerprint(c: Cursor): AudioFingerprint {
        val noteIdx = c.getColumnIndex("note")
        val goldIdx = c.getColumnIndex("is_gold_standard")
        val matchedIdx = c.getColumnIndex("last_matched_at")
        return AudioFingerprint(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            episodeId = c.getString(c.getColumnIndexOrThrow("episode_id")),
            startMs = c.getLong(c.getColumnIndexOrThrow("start_ms")),
            endMs = c.getLong(c.getColumnIndexOrThrow("end_ms")),
            fingerprint = c.getString(c.getColumnIndexOrThrow("fingerprint")),
            durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms")),
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
            updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
            note = if (noteIdx >= 0) c.getString(noteIdx) ?: "" else "",
            isGoldStandard = if (goldIdx >= 0) c.getInt(goldIdx) == 1 else true,
            lastMatchedAt = if (matchedIdx >= 0) c.getLong(matchedIdx) else 0
        )
    }

    // ===== Fingerprint Groups (v3.1.6) =====

    /**
     * v3.1.7: 保存或更新指纹分组（支持note字段）。
     */
    fun saveFingerprintGroup(group: FingerprintGroupInfo): Long {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("name", group.name)
            put("note", group.note)
            put("created_at", group.createdAt.takeIf { it > 0 } ?: now)
            put("updated_at", now)
        }
        if (group.id > 0) {
            db.update(TABLE_FINGERPRINT_GROUPS, values, "id = ?", arrayOf(group.id.toString()))
            return group.id
        }
        return db.insert(TABLE_FINGERPRINT_GROUPS, null, values)
    }

    /**
     * 删除指纹分组（级联删除成员）。
     */
    fun deleteFingerprintGroup(groupId: Long) {
        val db = writableDatabase
        db.delete(TABLE_FINGERPRINT_GROUP_MEMBERS, "group_id = ?", arrayOf(groupId.toString()))
        db.delete(TABLE_FINGERPRINT_GROUPS, "id = ?", arrayOf(groupId.toString()))
    }

    /**
     * v3.1.7: 获取所有指纹分组（支持note字段）。
     */
    fun getAllFingerprintGroups(): List<FingerprintGroupInfo> {
        val list = mutableListOf<FingerprintGroupInfo>()
        try {
            val db = readableDatabase
            val cursor = db.query(TABLE_FINGERPRINT_GROUPS, null, null, null, null, null, "created_at ASC")
            while (cursor.moveToNext()) {
                val noteIdx = cursor.getColumnIndex("note")
                list.add(FingerprintGroupInfo(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")) ?: "",
                    note = if (noteIdx >= 0) cursor.getString(noteIdx) ?: "" else "",
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                ))
            }
            cursor.close()
        } catch (_: Exception) {}
        return list
    }

    /**
     * 获取指定分组的成员列表。
     */
    fun getGroupMembers(groupId: Long): List<FingerprintGroupMember> {
        val list = mutableListOf<FingerprintGroupMember>()
        try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_FINGERPRINT_GROUP_MEMBERS, null,
                "group_id = ? AND manually_removed = 0",
                arrayOf(groupId.toString()), null, null, null
            )
            while (cursor.moveToNext()) {
                list.add(FingerprintGroupMember(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    groupId = cursor.getLong(cursor.getColumnIndexOrThrow("group_id")),
                    fingerprintId = cursor.getLong(cursor.getColumnIndexOrThrow("fingerprint_id")),
                    isRepresentative = cursor.getInt(cursor.getColumnIndexOrThrow("is_representative")) == 1,
                    manuallyRemoved = cursor.getInt(cursor.getColumnIndexOrThrow("manually_removed")) == 1
                ))
            }
            cursor.close()
        } catch (_: Exception) {}
        return list
    }

    /**
     * 添加成员到分组。
     */
    fun addGroupMember(groupId: Long, fingerprintId: Long, isRepresentative: Boolean = false): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("group_id", groupId)
            put("fingerprint_id", fingerprintId)
            put("is_representative", if (isRepresentative) 1 else 0)
            put("manually_removed", 0)
        }
        return db.insert(TABLE_FINGERPRINT_GROUP_MEMBERS, null, values)
    }

    /**
     * 从分组中移除成员（标记为手动移除）。
     */
    fun removeGroupMember(memberId: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("manually_removed", 1)
        }
        db.update(TABLE_FINGERPRINT_GROUP_MEMBERS, values, "id = ?", arrayOf(memberId.toString()))
    }

    /**
     * 清除所有分组数据（重新计算前使用）。
     */
    fun clearAllGroups() {
        val db = writableDatabase
        db.delete(TABLE_FINGERPRINT_GROUP_MEMBERS, null, null)
        db.delete(TABLE_FINGERPRINT_GROUPS, null, null)
    }

    /**
     * 根据指纹ID获取其所属分组信息。
     */
    fun getFingerprintGroupsByFingerprintId(fingerprintId: Long): List<FingerprintGroupInfo> {
        val list = mutableListOf<FingerprintGroupInfo>()
        try {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT g.* FROM $TABLE_FINGERPRINT_GROUPS g " +
                "INNER JOIN $TABLE_FINGERPRINT_GROUP_MEMBERS m ON g.id = m.group_id " +
                "WHERE m.fingerprint_id = ? AND m.manually_removed = 0",
                arrayOf(fingerprintId.toString())
            )
            while (cursor.moveToNext()) {
                list.add(FingerprintGroupInfo(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")) ?: "",
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                ))
            }
            cursor.close()
        } catch (_: Exception) {}
        return list
    }

    /**
     * v3.1.7: 更新分组名称。
     */
    fun updateGroupName(groupId: Long, name: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("name", name)
            put("updated_at", System.currentTimeMillis())
        }
        db.update(TABLE_FINGERPRINT_GROUPS, values, "id = ?", arrayOf(groupId.toString()))
    }

    /**
     * v3.1.7: 更新分组备注。
     */
    fun updateGroupNote(groupId: Long, note: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("note", note)
            put("updated_at", System.currentTimeMillis())
        }
        db.update(TABLE_FINGERPRINT_GROUPS, values, "id = ?", arrayOf(groupId.toString()))
    }

    // ===== 金标准指纹 & 正式指纹库 (v3.2.2) =====

    /**
     * v3.2.2: 获取金标准指纹（人工录入的，is_gold_standard=1）。
     * 仅用于第三层指纹漏判召回。
     */
    fun getGoldStandardFingerprints(): List<AudioFingerprint> {
        val list = mutableListOf<AudioFingerprint>()
        try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_AUDIO_FINGERPRINTS,
                arrayOf("id", "episode_id", "start_ms", "end_ms", "fingerprint", "duration_ms", "created_at", "updated_at", "note", "is_gold_standard", "last_matched_at"),
                "is_gold_standard = 1",
                null, null, null,
                "created_at DESC"
            )
            while (cursor.moveToNext()) {
                list.add(cursorToAudioFingerprint(cursor))
            }
            cursor.close()
        } catch (_: Exception) {}
        return list
    }

    /**
     * v3.2.2: 获取自动晋升指纹（is_gold_standard = 0）。
     * 用于查看和管理自动晋升的指纹。
     */
    fun getAutomaticFingerprints(): List<AudioFingerprint> {
        val list = mutableListOf<AudioFingerprint>()
        try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_AUDIO_FINGERPRINTS,
                arrayOf("id", "episode_id", "start_ms", "end_ms", "fingerprint", "duration_ms", "created_at", "updated_at", "note", "is_gold_standard", "last_matched_at"),
                "is_gold_standard = 0",
                null, null, null,
                "created_at DESC"
            )
            while (cursor.moveToNext()) {
                list.add(cursorToAudioFingerprint(cursor))
            }
            cursor.close()
        } catch (_: Exception) {}
        return list
    }

    /**
     * v3.2.2: 获取自动晋升指纹总数。
     */
    fun getAutomaticFingerprintsCount(): Int {
        var count = 0
        try {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_AUDIO_FINGERPRINTS WHERE is_gold_standard = 0", null)
            if (cursor.moveToFirst()) count = cursor.getInt(0)
            cursor.close()
        } catch (_: Exception) {}
        return count
    }

    /**
     * v3.2.2: 获取正式指纹库全部指纹（金标准 + 自动晋升）。
     * 用于第一层指纹快筛。
     */
    fun getFormalLibraryFingerprints(): List<AudioFingerprint> {
        // 正式库 = audio_fingerprints 表中所有记录（金标准 + 自动晋升）
        return getAllAudioFingerprints()
    }

    // ===== 候选指纹观察池 (v3.2.2) =====

    /**
     * v3.2.2: 观察池候选指纹数据类。
     */
    data class ObservationPoolCandidate(
        val id: Long = 0,
        val fingerprintHash: String = "",
        val fingerprint: String = "",
        val episodeId: String = "",
        val durationMs: Long = 0,
        val similarity: Float = 0.82f,
        val hitCount: Int = 1,
        val lastHitTime: Long = System.currentTimeMillis(),
        val expiredAt: Long = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis()
    )

    /**
     * v3.2.2: 保存候选指纹到观察池。
     * 前置条件已校验：时长15s~600s、相似度≥0.82、非重复。
     */
    fun saveObservationPoolCandidate(candidate: ObservationPoolCandidate): Long {
        val db = writableDatabase
        // 先检查是否已达容量上限
        val countCursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_FINGERPRINT_OBSERVATION_POOL", null)
        var currentCount = 0
        if (countCursor.moveToFirst()) currentCount = countCursor.getInt(0)
        countCursor.close()
        if (currentCount >= OBSERVATION_POOL_MAX_CAPACITY) {
            // 容量已满，删除最旧的候选
            db.execSQL("DELETE FROM $TABLE_FINGERPRINT_OBSERVATION_POOL WHERE id IN (SELECT id FROM $TABLE_FINGERPRINT_OBSERVATION_POOL ORDER BY last_hit_time ASC LIMIT ${currentCount - OBSERVATION_POOL_MAX_CAPACITY + 1})")
        }

        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("fingerprint_hash", candidate.fingerprintHash)
            put("fingerprint", candidate.fingerprint)
            put("episode_id", candidate.episodeId)
            put("duration_ms", candidate.durationMs)
            put("similarity", candidate.similarity)
            put("hit_count", candidate.hitCount)
            put("last_hit_time", candidate.lastHitTime)
            put("expired_at", candidate.expiredAt)
            put("created_at", now)
            put("updated_at", now)
        }
        return db.insert(TABLE_FINGERPRINT_OBSERVATION_POOL, null, values)
    }

    /**
     * v3.2.2: 获取观察池中所有候选指纹。
     */
    fun getAllObservationPoolCandidates(): List<ObservationPoolCandidate> {
        val list = mutableListOf<ObservationPoolCandidate>()
        try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_FINGERPRINT_OBSERVATION_POOL,
                null, null, null, null, null,
                "last_hit_time DESC"
            )
            while (cursor.moveToNext()) {
                list.add(cursorToObservationPoolCandidate(cursor))
            }
            cursor.close()
        } catch (_: Exception) {}
        return list
    }

    /**
     * v3.2.2: 检查候选指纹是否与正式库或观察池中的指纹重复（相似度 > 0.92）。
     * @return true 表示重复，不应新增
     */
    fun isDuplicateFingerprint(fingerprint: String, threshold: Float = 0.92f): Boolean {
        try {
            // 检查正式库
            val formalFps = getAllAudioFingerprints()
            for (fp in formalFps) {
                val sim = ChromaprintExtractor.compareFingerprints(fingerprint, fp.fingerprint)
                if (sim > threshold) return true
            }
            // 检查观察池
            val poolFps = getAllObservationPoolCandidates()
            for (fp in poolFps) {
                val sim = ChromaprintExtractor.compareFingerprints(fingerprint, fp.fingerprint)
                if (sim > threshold) return true
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * v3.2.2: 增加观察池候选的跨节目命中计数。
     * 同一节目ID不重复计数。
     * @return true 表示命中计数已增加，false 表示同一节目不增加
     */
    fun incrementObservationPoolHit(poolId: Long, episodeId: String, hitCountThreshold: Int = 3): Boolean {
        val db = writableDatabase
        try {
            // 查询当前候选信息
            val cursor = db.query(
                TABLE_FINGERPRINT_OBSERVATION_POOL,
                arrayOf("episode_id", "hit_count"),
                "id = ?", arrayOf(poolId.toString()),
                null, null, null
            )
            if (!cursor.moveToFirst()) { cursor.close(); return false }
            val firstEpisodeId = cursor.getString(0)
            val currentHitCount = cursor.getInt(1)
            cursor.close()

            // 同一节目不增加计数
            if (episodeId == firstEpisodeId) return false

            val now = System.currentTimeMillis()
            val newHitCount = currentHitCount + 1
            val expiredAt = now + 30L * 24 * 60 * 60 * 1000  // 重新计时30天

            val values = ContentValues().apply {
                put("hit_count", newHitCount)
                put("last_hit_time", now)
                put("expired_at", expiredAt)
                put("updated_at", now)
            }
            db.update(TABLE_FINGERPRINT_OBSERVATION_POOL, values, "id = ?", arrayOf(poolId.toString()))

            // 如果达到晋升阈值，自动晋升
            if (newHitCount >= hitCountThreshold) {
                promoteObservationPoolToFormalLibrary(poolId)
            }
            return true
        } catch (_: Exception) { return false }
    }

    /**
     * v3.2.2: 将观察池候选晋升到正式指纹库。
     * 设置 is_gold_standard = 0（自动晋升，非金标准）。
     */
    private fun promoteObservationPoolToFormalLibrary(poolId: Long) {
        try {
            val db = writableDatabase
            val cursor = db.query(
                TABLE_FINGERPRINT_OBSERVATION_POOL,
                arrayOf("fingerprint", "episode_id", "duration_ms"),
                "id = ?", arrayOf(poolId.toString()),
                null, null, null
            )
            if (!cursor.moveToFirst()) { cursor.close(); return }
            val fingerprint = cursor.getString(0)
            val episodeId = cursor.getString(1)
            val durationMs = cursor.getLong(2)
            cursor.close()

            // 添加到正式指纹库（is_gold_standard = 0）
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("episode_id", episodeId)
                put("start_ms", 0L)
                put("end_ms", durationMs)
                put("fingerprint", fingerprint)
                put("duration_ms", durationMs)
                put("created_at", now)
                put("updated_at", now)
                put("note", "自动晋升（观察池）")
                put("is_gold_standard", 0)
            }
            db.insert(TABLE_AUDIO_FINGERPRINTS, null, values)

            // 从观察池删除
            db.delete(TABLE_FINGERPRINT_OBSERVATION_POOL, "id = ?", arrayOf(poolId.toString()))

            Log.i("RadioDatabaseHelper", "promoteObservationPoolToFormalLibrary: 候选指纹#${poolId}晋升为正式指纹")
        } catch (e: Exception) {
            Log.e("RadioDatabaseHelper", "promoteObservationPoolToFormalLibrary failed: ${e.message}")
        }
    }

    /**
     * v3.2.2: 清理过期观察池候选（30天未命中）。
     */
    fun cleanupExpiredObservationPool() {
        try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            val deleted = db.delete(TABLE_FINGERPRINT_OBSERVATION_POOL, "expired_at < ?", arrayOf(now.toString()))
            if (deleted > 0) {
                Log.i("RadioDatabaseHelper", "cleanupExpiredObservationPool: 删除了$deleted 个过期候选指纹")
            }
        } catch (e: Exception) {
            Log.e("RadioDatabaseHelper", "cleanupExpiredObservationPool failed: ${e.message}")
        }
    }

    /**
     * v3.2.2: 获取达到晋升条件的观察池候选（hit_count >= threshold）。
     */
    fun getPromotableCandidates(threshold: Int = 3): List<ObservationPoolCandidate> {
        val list = mutableListOf<ObservationPoolCandidate>()
        try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_FINGERPRINT_OBSERVATION_POOL,
                null,
                "hit_count >= ?",
                arrayOf(threshold.toString()),
                null, null,
                "last_hit_time ASC"
            )
            while (cursor.moveToNext()) {
                list.add(cursorToObservationPoolCandidate(cursor))
            }
            cursor.close()
        } catch (_: Exception) {}
        return list
    }

    /**
     * v3.2.2: 根据指纹hash查找观察池候选。
     */
    fun findObservationPoolCandidateByHash(hash: String): ObservationPoolCandidate? {
        try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_FINGERPRINT_OBSERVATION_POOL,
                null,
                "fingerprint_hash = ?",
                arrayOf(hash),
                null, null, null
            )
            var candidate: ObservationPoolCandidate? = null
            if (cursor.moveToFirst()) {
                candidate = cursorToObservationPoolCandidate(cursor)
            }
            cursor.close()
            return candidate
        } catch (_: Exception) { return null }
    }

    /**
     * v3.2.2: 获取观察池候选总数。
     */
    fun getObservationPoolCount(): Int {
        var count = 0
        try {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_FINGERPRINT_OBSERVATION_POOL", null)
            if (cursor.moveToFirst()) count = cursor.getInt(0)
            cursor.close()
        } catch (_: Exception) {}
        return count
    }

    /**
     * v3.2.2: 按ID删除观察池候选指纹。
     */
    fun deleteObservationPoolCandidate(id: Long) {
        try {
            val db = writableDatabase
            db.delete(TABLE_FINGERPRINT_OBSERVATION_POOL, "id = ?", arrayOf(id.toString()))
        } catch (_: Exception) {}
    }

    /**
     * v3.2.2: 游标转ObservationPoolCandidate。
     */
    private fun cursorToObservationPoolCandidate(c: Cursor): ObservationPoolCandidate {
        return ObservationPoolCandidate(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            fingerprintHash = c.getString(c.getColumnIndexOrThrow("fingerprint_hash")) ?: "",
            fingerprint = c.getString(c.getColumnIndexOrThrow("fingerprint")) ?: "",
            episodeId = c.getString(c.getColumnIndexOrThrow("episode_id")) ?: "",
            durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms")),
            similarity = c.getFloat(c.getColumnIndexOrThrow("similarity")),
            hitCount = c.getInt(c.getColumnIndexOrThrow("hit_count")),
            lastHitTime = c.getLong(c.getColumnIndexOrThrow("last_hit_time")),
            expiredAt = c.getLong(c.getColumnIndexOrThrow("expired_at")),
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
            updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
        )
    }
}

// v3.1.7: 指纹分组数据类（增加note字段）
data class FingerprintGroupInfo(
    val id: Long = 0,
    val name: String = "",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class FingerprintGroupMember(
    val id: Long = 0,
    val groupId: Long = 0,
    val fingerprintId: Long = 0,
    val isRepresentative: Boolean = false,
    val manuallyRemoved: Boolean = false
)

// v3.0.2: 音频指纹数据类
// v3.1.3: 增加 note 字段，支持用户备注
// v3.2.2: 增加 isGoldStandard 字段，区分人工录入金标准与自动晋升指纹
data class AudioFingerprint(
    val id: Long = 0,
    val episodeId: String,
    val startMs: Long,
    val endMs: Long,
    val fingerprint: String,
    val durationMs: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val note: String = "",
    val isGoldStandard: Boolean = true,
    val lastMatchedAt: Long = 0  // v3.1.41: 最后匹配时间，用于过期删除
)
