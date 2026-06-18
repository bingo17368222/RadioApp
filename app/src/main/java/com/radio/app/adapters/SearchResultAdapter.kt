package com.radio.app.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.radio.app.R
import com.radio.app.models.SearchResult

class SearchResultAdapter(
    private val ctx: Context,
    private val results: List<SearchResult>,
    private val listener: OnSearchResultClickListener
) : RecyclerView.Adapter<SearchResultAdapter.ViewHolder>() {

    interface OnSearchResultClickListener {
        fun onSearchResultClick(r: SearchResult)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(ctx).inflate(R.layout.item_search_result, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val r = results[position]
        holder.tvType.text = r.type ?: "节目"
        holder.tvTitle.text = r.title ?: ""
        holder.tvStation.text = r.stationName ?: ""
        if (r.matchedText != null) {
            holder.tvMatch.text = r.matchedText
            holder.tvMatch.visibility = View.VISIBLE
        } else {
            holder.tvMatch.visibility = View.GONE
        }
        if (r.transcript != null) {
            holder.tvTime.text = "时间: ${String.format("%02d:%02d",
                r.transcript!!.segmentStart / 60, r.transcript!!.segmentStart % 60)}"
            holder.tvTime.visibility = View.VISIBLE
        } else {
            holder.tvTime.visibility = View.GONE
        }
        holder.card.setOnClickListener { listener.onSearchResultClick(r) }
    }

    override fun getItemCount(): Int = results.size

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val card: CardView = v.findViewById(R.id.card_view)
        val tvType: TextView = v.findViewById(R.id.tv_type)
        val tvTitle: TextView = v.findViewById(R.id.tv_title)
        val tvStation: TextView = v.findViewById(R.id.tv_station)
        val tvMatch: TextView = v.findViewById(R.id.tv_match)
        val tvTime: TextView = v.findViewById(R.id.tv_time)
    }
}
