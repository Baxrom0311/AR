package com.huji.couchmirage.catalog

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.huji.couchmirage.R
class CategoryRecyclerAdapter(
    private val categories: List<Category>,
    private val onItemClick: (Category) -> Unit
) : RecyclerView.Adapter<CategoryRecyclerAdapter.CategoryViewHolder>() {
    class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: CardView = itemView.findViewById(R.id.card_view)
        val icon: ImageView = itemView.findViewById(R.id.icon_image)
        val name: TextView = itemView.findViewById(R.id.name_text)
        val description: TextView = itemView.findViewById(R.id.description_text)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false)
        return CategoryViewHolder(view)
    }
    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        val category = categories[position]
        
        holder.name.text = category.name
        holder.description.text = category.description
        
        // Icon setup (placeholder)
        when (category.id) {
            "planets" -> holder.icon.setImageResource(R.drawable.ic_planet)
            "stars" -> holder.icon.setImageResource(R.drawable.ic_star)
            "moons" -> holder.icon.setImageResource(R.drawable.ic_moon)
            else -> holder.icon.setImageResource(R.drawable.ic_launcher_foreground)
        }
        
        // Click listener
        holder.card.setOnClickListener {
            onItemClick(category)
        }
    }

    override fun getItemCount(): Int = categories.size
}
