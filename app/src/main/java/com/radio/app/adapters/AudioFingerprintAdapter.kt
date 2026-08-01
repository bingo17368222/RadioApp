package com.radio.app.adapters

import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.radio.app.R
import com.radio.app.database.AudioFingerprint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v3.0.2: 音频指纹列表适配器。
 * v3.0.8: 支持播放/停止状态切换。
 * v3.0.9: 支持条目选中高亮与点击选中。
 * v3.1.0: 增加绑定日志便于排查文字不显示问题。
 * v3.1.3: 支持备注显示与编辑，失焦自动保存。
 */
class AudioFingerprintAdapter : RecyclerView.Adapter<AudioFingerprintAdapter.ViewHolder>() {

    private val TAG = "AudioFingerprintAdapter"
    private var items: List<AudioFingerprint> = emptyList()
    private var playingPosition: Int = -1
    private var selectedPosition: Int = -1
    private var onDeleteListener: ((AudioFingerprint) -> Unit)? = null
    private var onRefreshListener: ((AudioFingerprint) -> Unit)? = null
    private var onPlayListener: ((AudioFingerprint) -> Unit)? = null
    private var onStopListener: (() -> Unit)? = null
    private var onTestListener: ((AudioFingerprint) -> Unit)? = null
    private var onItemClickListener: ((AudioFingerprint, Int) -> Unit)? = null
    private var onNoteUpdateListener: ((AudioFingerprint, String) -> Unit)? = null

    fun setItems(items: List<AudioFingerprint>) {
        this.items = items
        playingPosition = -1
        selectedPosition = -1
        notifyDataSetChanged()
    }

    fun setPlayingPosition(position: Int) {
        val old = playingPosition
        playingPosition = position
        if (old >= 0 && old < itemCount) notifyItemChanged(old)
        if (position >= 0 && position < itemCount) notifyItemChanged(position)
    }

    fun stopPlaying() {
        setPlayingPosition(-1)
    }

    fun setSelectedPosition(position: Int) {
        val old = selectedPosition
        selectedPosition = position
        if (old >= 0 && old < itemCount) notifyItemChanged(old)
        if (position >= 0 && position < itemCount) notifyItemChanged(position)
    }

    fun setOnDeleteListener(listener: (AudioFingerprint) -> Unit) {
        onDeleteListener = listener
    }

    fun setOnRefreshListener(listener: (AudioFingerprint) -> Unit) {
        onRefreshListener = listener
    }

    fun setOnPlayListener(listener: (AudioFingerprint) -> Unit) {
        onPlayListener = listener
    }

    fun setOnStopListener(listener: () -> Unit) {
        onStopListener = listener
    }

    fun setOnTestListener(listener: (AudioFingerprint) -> Unit) {
        onTestListener = listener
    }

    fun setOnItemClickListener(listener: (AudioFingerprint, Int) -> Unit) {
        onItemClickListener = listener
    }

    // v3.1.3: 设置备注更新回调
    fun setOnNoteUpdateListener(listener: (AudioFingerprint, String) -> Unit) {
        onNoteUpdateListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio_fingerprint, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val fp = items[position]
        val timeText = "${formatTime(fp.startMs)} - ${formatTime(fp.endMs)}"
        val episodeText = fp.episodeId
        val durationSec = fp.durationMs / 1000
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(fp.createdAt))
        val metaText = "时长 ${durationSec}s · $dateStr"
        holder.tvTime.text = timeText
        holder.tvEpisode.text = episodeText
        holder.tvMeta.text = metaText
        Log.d(TAG, "onBindViewHolder pos=$position time='$timeText' episode='$episodeText' meta='$metaText'")

        // v3.1.3: 绑定备注，避免滚动时 TextWatcher 触发
        holder.bindNote(fp)
        // 将备注回调通过 tag 传递给 ViewHolder 的失焦监听器
        holder.itemView.tag = onNoteUpdateListener

        val isPlaying = position == playingPosition
        holder.btnPlay.text = if (isPlaying) "停止" else "播放"
        holder.btnPlay.setOnClickListener {
            if (isPlaying) {
                onStopListener?.invoke()
            } else {
                setPlayingPosition(position)
                onPlayListener?.invoke(fp)
            }
        }
        holder.btnTest.setOnClickListener { onTestListener?.invoke(fp) }
        holder.btnRefresh.setOnClickListener { onRefreshListener?.invoke(fp) }
        holder.btnDelete.setOnClickListener { onDeleteListener?.invoke(fp) }

        // v3.0.9: 点击条目选中/取消选中
        holder.itemView.isSelected = (position == selectedPosition)
        holder.itemView.setOnClickListener {
            setSelectedPosition(if (selectedPosition == position) -1 else position)
            onItemClickListener?.invoke(fp, position)
        }
    }

    override fun getItemCount(): Int = items.size

    private fun formatTime(ms: Long): String {
        val s = (ms / 1000).toInt()
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.tv_fingerprint_time)
        val tvEpisode: TextView = view.findViewById(R.id.tv_fingerprint_episode)
        val tvMeta: TextView = view.findViewById(R.id.tv_fingerprint_meta)
        val btnPlay: Button = view.findViewById(R.id.btn_play_fingerprint)
        val btnTest: Button = view.findViewById(R.id.btn_test_fingerprint)
        val btnRefresh: Button = view.findViewById(R.id.btn_refresh_fingerprint)
        val btnDelete: Button = view.findViewById(R.id.btn_delete_fingerprint)
        val etNote: EditText = view.findViewById(R.id.et_fingerprint_note)

        // v3.1.3: 当前绑定的指纹 ID，用于失焦时回调
        private var currentFp: AudioFingerprint? = null
        private var isBinding = false

        private val noteWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // 仅在非绑定阶段记录变更，失焦时统一保存
            }
        }

        init {
            etNote.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus && !isBinding) {
                    val fp = currentFp
                    val newText = etNote.text?.toString()?.trim() ?: ""
                    if (fp != null && newText != fp.note) {
                        Log.d("AudioFingerprintAdapter", "备注失焦保存: fpId=${fp.id} note='$newText'")
                        // 通过 tag 传递回调
                        val callback = itemView.tag as? ((AudioFingerprint, String) -> Unit)
                        callback?.invoke(fp, newText)
                    }
                }
            }
            etNote.setOnEditorActionListener { _, _, _ ->
                etNote.clearFocus()
                true
            }
        }

        fun bindNote(fp: AudioFingerprint) {
            isBinding = true
            currentFp = fp
            etNote.setText(fp.note)
            etNote.setSelection(fp.note.length)
            isBinding = false
        }
    }
}
