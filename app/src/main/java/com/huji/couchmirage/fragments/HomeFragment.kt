package com.huji.couchmirage.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.huji.couchmirage.R
import com.huji.couchmirage.catalog.Category
import com.huji.couchmirage.catalog.CategoryActivity

import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private val viewModel: HomeViewModel by viewModels()
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        recyclerView = view.findViewById(R.id.categories_recycler)
        progressBar = view.findViewById(R.id.progress_bar)
        emptyContainer = view.findViewById(R.id.empty_container)
        
        recyclerView.layoutManager = GridLayoutManager(requireContext(), 2)
        observeUiState()
        viewModel.loadCategories()
    }

    private fun observeUiState() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            when (state) {
                HomeUiState.Loading -> {
                    progressBar.visibility = View.VISIBLE
                    emptyContainer.visibility = View.GONE
                    recyclerView.visibility = View.GONE
                }
                HomeUiState.Empty -> {
                    progressBar.visibility = View.GONE
                    emptyContainer.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                }
                is HomeUiState.Success -> {
                    progressBar.visibility = View.GONE
                    emptyContainer.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    setupRecyclerView(state.categories)
                }
                is HomeUiState.Error -> {
                    progressBar.visibility = View.GONE
                    if (recyclerView.adapter == null) {
                        emptyContainer.visibility = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    }
                    Toast.makeText(requireContext(), "Xatolik: ${state.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupRecyclerView(categories: List<Category>) {
        val adapter = CategoryAdapter(categories) { category ->
            val intent = Intent(requireContext(), CategoryActivity::class.java).apply {
                putExtra("CATEGORY_ID", category.id)
                putExtra("CATEGORY_NAME", category.name)
            }
            startActivity(intent)
        }
        recyclerView.adapter = adapter
    }

    // Inner Adapter class
    inner class CategoryAdapter(
        private val categories: List<Category>,
        private val onClick: (Category) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.category_icon)
            val title: TextView = view.findViewById(R.id.category_title)
            val subtitle: TextView = view.findViewById(R.id.category_subtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_category_card, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val category = categories[position]
            
            // Set icon based on category icon field or name
            holder.icon.setImageResource(getIconResource(category.icon, category.name))
            holder.title.text = category.name
            holder.subtitle.text = category.description
            
            holder.itemView.setOnClickListener { onClick(category) }
        }

        override fun getItemCount() = categories.size

        private fun getIconResource(icon: String, name: String): Int {
            // Map icon/name to drawable resource
            return when {
                icon.contains("planet", true) || name.contains("Sayyora", true) -> R.drawable.ic_planet
                icon.contains("star", true) || name.contains("Yulduz", true) -> R.drawable.ic_star
                icon.contains("moon", true) || name.contains("Yo'ldosh", true) -> R.drawable.ic_moon
                icon.contains("galaxy", true) || name.contains("Galaktika", true) -> R.drawable.ic_galaxy
                icon.contains("telescope", true) || name.contains("Teleskop", true) -> R.drawable.ic_3d_cube
                icon.contains("satellite", true) || name.contains("Sun'iy", true) -> R.drawable.ic_3d_cube
                icon.contains("comet", true) || name.contains("Kometa", true) -> R.drawable.ic_star
                icon.contains("asteroid", true) || name.contains("Asteroid", true) -> R.drawable.ic_planet
                icon.contains("nebula", true) || name.contains("Tumanlik", true) -> R.drawable.ic_galaxy
                else -> R.drawable.ic_star
            }
        }
    }
}
