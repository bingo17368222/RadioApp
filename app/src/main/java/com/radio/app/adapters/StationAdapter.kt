package com.radio.app.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.radio.app.R
import com.radio.app.models.RadioStation

class StationAdapter(
    private val ctx: Context,
    private val stations: List<RadioStation>,
    private val listener: OnStationClickListener?
) : RecyclerView.Adapter<StationAdapter.ViewHolder>() {

    fun interface OnStationClickListener {
        fun onStationClick(s: RadioStation)
        fun onStationLongClick(s: RadioStation)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(ctx).inflate(R.layout.item_station, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val station = stations[position]
        holder.tvName.text = station.name
        holder.tvProgram.text = station.currentProgram
        holder.tvCountry.text = station.country
        holder.tvBitrate.text = if (station.bitrate > 0) "${station.bitrate} kbps" else ""
        holder.ivCover.setImageResource(R.drawable.ic_radio)
        holder.card.setOnClickListener { listener?.onStationClick(station) }
        holder.card.setOnLongClickListener {
            listener?.onStationLongClick(station)
            true
        }
    }

    override fun getItemCount(): Int = stations.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: CardView = view.findViewById(R.id.card_view)
        val ivCover: ImageView = view.findViewById(R.id.iv_cover)
        val tvName: TextView = view.findViewById(R.id.tv_name)
        val tvProgram: TextView = view.findViewById(R.id.tv_program)
        val tvCountry: TextView = view.findViewById(R.id.tv_country)
        val tvBitrate: TextView = view.findViewById(R.id.tv_bitrate)
    }
}
