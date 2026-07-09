package com.radio.app.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.radio.app.models.PlayProgress
import com.radio.app.models.Transcript
import com.radio.app.models.VoiceSegment
import com.radio.app.models.Episode

class RadioDatabaseHelper private constructor(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION
) {

    companion object {
        private const val DATABASE_NAME = "radio_app.db"
        private const val DATABASE_VERSION = 6
        private const val TABLE_PLAY_PROGRESS = "play_progress"
        private const val TABLE_TRANSCRIPTS = "transcripts"
        private const val TABLE_DISLIKED_EPISODES = "disliked_episodes"
        private const val TABLE_VOICE_SEGMENTS_MANUAL = "voice_segments_manual"
        private const val TABLE_VOICE_SEGMENTS_AI = "voice_segments_ai"
        private const val TABLE_EPISODE_INFO = "episode_info"

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
        db.execSQL("CREATE TABLE IF NOT EXISTS transcript_engine (episode_id TEXT PRIMARY KEY, engine_name TEXT NOT NULL, generated_at INTEGER NOT NULL, is_complete INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE $TABLE_DISLIKED_EPISODES (episode_id TEXT PRIMARY KEY, title TEXT, station_name TEXT, created_at INTEGER)")
        db.execSQL("CREATE TABLE $TABLE_VOICE_SEGMENTS_MANUAL (episode_id TEXT, segment_start INTEGER, segment_end INTEGER, has_voice INTEGER, PRIMARY KEY(episode_id, segment_start))")
        db.execSQL("CREATE TABLE $TABLE_VOICE_SEGMENTS_AI (episode_id TEXT, segment_start INTEGER, segment_end INTEGER, has_voice INTEGER, label TEXT, is_simulated INTEGER, PRIMARY KEY(episode_id, segment_start))")
        // [v2.2.4] Episode metadata cache table
        db.execSQL("CREATE TABLE $TABLE_EPISODE_INFO (episode_id TEXT PRIMARY KEY, date TEXT NOT NULL, title TEXT, broadcast_at TEXT, duration INTEGER, audio_url TEXT, station_id TEXT, station_name TEXT, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_episode_info_date_station ON $TABLE_EPISODE_INFO(date, station_id)")
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

    // [v2.1.3] Delete transcripts for a specific episode
    fun deleteTranscriptsByEpisode(episodeId: String) {
        val db = writableDatabase
        db.delete(TABLE_TRANSCRIPTS, "episode_id = ?", arrayOf(episodeId))
        // [v2.4.18] Also delete engine record so patrol can regenerate
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

    // ===== Episode Info (v2.2.4) =====

    fun saveEpisodeInfo(episode: Episode) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("episode_id", episode.id)
                put("date", episode.broadcastAt.substringBefore("T").take(10))
                put("title", episode.title)
                put("broadcast_at", episode.broadcastAt)
                put("duration", episode.duration)
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
                    val values = ContentValues().apply {
                        put("episode_id", episode.id)
                        put("date", episode.broadcastAt.substringBefore("T").take(10))
                        put("title", episode.title)
                        put("broadcast_at", episode.broadcastAt)
                        put("duration", episode.duration)
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
        audioUrl = c.getString(c.getColumnIndexOrThrow("audio_url"))
        stationId = c.getString(c.getColumnIndexOrThrow("station_id"))
        stationName = c.getString(c.getColumnIndexOrThrow("station_name"))
    }
}
