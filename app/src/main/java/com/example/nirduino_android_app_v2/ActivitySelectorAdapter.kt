package com.example.nirduino_android_app_v2

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView

class ActivitySelectorAdapter(
    private val cardList: List<ActivitySelectorCard>,
    private val onClick: (ActivitySelectorCard) -> Unit
) : RecyclerView.Adapter<ActivitySelectorAdapter.CardViewHolder>() {

    class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardView: CardView = itemView as CardView
        val title: TextView = itemView.findViewById(R.id.cardTitle)
        val image: ImageView = itemView.findViewById(R.id.cardImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.listitem_activity_selector, parent, false)
        return CardViewHolder(view)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        val item = cardList[position]
        holder.title.text = item.title
        holder.image.setImageResource(item.imageResId)
        holder.cardView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = cardList.size
}
