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
 * v3.2.2: 自动指纹列表适配器。
 * 展示 is_gold_standard = false 的自动晋升指纹，带有"自动"标签标识。
 * 复用 R.layout.item_audio_fingerprint 布局。
 */
class AutomaticFingerprintAdapter : RecyclerView.Adapter<AutomaticFingerprintAdapter.ViewHolder>() {

    private val TAG = "AutomaticFingerprintAdapter"
    private var items: List<AudioFingerprint> = emptyList()
    private var playingPosition: Int = -1
    private var selectedPosition: Int = -1
    private var onDeleteListener: ((AudioFingerprint) -> Unit)? = null
    private var onPlayListener: ((AudioFingerprint) -> Unit)? = null
    private var onStopListener: (() -> Unit)? = null
    private var onNoteUpdateListener: ((AudioFingerprint, String) -> Unit)? = null
    private var onItemClickListener: ((AudioFingerprint, Int) -> Unit)? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

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

    fun setOnPlayListener(listener: (AudioFingerprint) -> Unit) {
        onPlayListener = listener
    }

    fun setOnStopListener(listener: () -> Unit) {
        onStopListener = listener
    }

    fun setOnItemClickListener(listener: (AudioFingerprint, Int) -> Unit) {
        onItemClickListener = listener
    }

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

        // 时间范围
        val timeText = "${formatTime(fp.startMs)} - ${formatTime(fp.endMs)}"
        holder.tvTime.text = timeText

        // 节目ID
        holder.tvEpisode.text = fp.episodeId

        // 自动标签 + 时长 + 日期
        val durationSec = fp.durationMs / 1000
        val dateStr = dateFormat.format(Date(fp.createdAt))
        holder.tvMeta.text = "[自动] 时长 ${durationSec}s · $dateStr"

        Log.d(TAG, "onBindViewHolder pos=$position time='$timeText' episode='${fp.episodeId}' meta='${holder.tvMeta.text}'")

        // 绑定备注
        holder.bindNote(fp)
        holder.itemView.tag = onNoteUpdateListener

        // 播放/停止按钮
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

        // 测试按钮 - 自动指纹适配器隐藏测试按钮
        holder.btnTest.visibility = View.GONE

        // 修正按钮 - 自动指纹适配器隐藏修正按钮
        holder.btnRefresh.visibility = View.GONE

        // 删除按钮
        holder.btnDelete.setOnClickListener { onDeleteListener?.invoke(fp) }

        // 选中状态
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

        private var currentFp: AudioFingerprint? = null
        private var isBinding = false

        private val noteWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {}
        }

        init {
            etNote.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus && !isBinding) {
                    val fp = currentFp
                    val newText = etNote.text?.toString()?.trim() ?: ""
                    if (fp != null && newText != fp.note) {
                        Log.d("AutomaticFingerprintAdapter", "备注失焦保存: fpId=${fp.id} note='$newText'")
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