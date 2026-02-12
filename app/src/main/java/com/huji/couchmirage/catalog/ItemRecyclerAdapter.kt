package com.huji.couchmirage.catalog

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.huji.couchmirage.R

class ItemRecyclerAdapter(
    private val items: List<CelestialBody>,
    private val onItemClick: (CelestialBody) -> Unit
) : RecyclerView.Adapter<ItemRecyclerAdapter.ItemViewHolder>() {

    class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val itemImg: ImageView = itemView.findViewById(R.id.item_img)
        val itemName: TextView = itemView.findViewById(R.id.item_name)
        val itemPrice: TextView = itemView.findViewById(R.id.item_price) // Reuse for description/info
        // val itemColor: TextView = itemView.findViewById(R.id.item_color) // Hidden for now
        // val itemSizes: TextView = itemView.findViewById(R.id.item_sizes) // Hidden for now
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.layout_single_list_item, parent, false)
        return ItemViewHolder(view)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        val item = items[position]
        
        holder.itemName.text = item.name
        holder.itemPrice.text = item.description // Using price view for short description
        
        // Hide unused fields if necessary, or repurpose them
        // holder.itemColor.visibility = View.GONE
        // holder.itemSizes.visibility = View.GONE

        // Load image
        if (item.images.isNotEmpty()) {
            Glide.with(holder.itemView.context)
                .load(item.images[0])
                .apply(RequestOptions().placeholder(R.drawable.ic_planet).error(R.drawable.ic_planet))
                .into(holder.itemImg)
        } else {
             holder.itemImg.setImageResource(R.drawable.ic_planet)
        }
        
        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = items.size
}
