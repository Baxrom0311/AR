package com.huji.couchmirage.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.huji.couchmirage.R
import com.huji.couchmirage.catalog.CelestialBody
import com.huji.couchmirage.catalog.FirebaseRepository
import com.huji.couchmirage.catalog.ItemDetailsActivity
import com.huji.couchmirage.catalog.ItemRecyclerAdapter
import com.huji.couchmirage.utils.FavoritesManager

class FavoritesFragment : Fragment() {

    private val repository = FirebaseRepository.instance
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_favorites, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        recyclerView = view.findViewById(R.id.recycler_view)
        progressBar = view.findViewById(R.id.progress_bar)
        emptyContainer = view.findViewById(R.id.empty_container)
        
        recyclerView.layoutManager = GridLayoutManager(requireContext(), 2)
        
        loadFavorites()
    }

    override fun onResume() {
        super.onResume()
        if (isAdded) {
            loadFavorites()
        }
    }

    private fun loadFavorites() {
        val ctx = context ?: return
        val favoriteIds = FavoritesManager.getFavorites(ctx)

        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyContainer.visibility = View.GONE

        if (favoriteIds.isEmpty()) {
            showEmptyState()
            return
        }

        repository.getCelestialBodiesByIds(
            ids = favoriteIds.toList(),
            onSuccess = { favoriteBodies ->
                if (!isAdded) return@getCelestialBodiesByIds
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    progressBar.visibility = View.GONE
                    if (favoriteBodies.isEmpty()) {
                        showEmptyState()
                    } else {
                        emptyContainer.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                        setupRecyclerView(favoriteBodies)
                    }
                }
            },
            onError = { e ->
                if (!isAdded) return@getCelestialBodiesByIds
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    showEmptyState()
                    Toast.makeText(requireContext(), "Xatolik: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun showEmptyState() {
        progressBar.visibility = View.GONE
        recyclerView.visibility = View.GONE
        emptyContainer.visibility = View.VISIBLE
    }

    private fun setupRecyclerView(items: List<CelestialBody>) {
        val adapter = ItemRecyclerAdapter(items) { item ->
            val intent = Intent(requireContext(), ItemDetailsActivity::class.java).apply {
                putExtra(ItemDetailsActivity.EXTRA_ITEM_ID, item.id)
                putExtra(ItemDetailsActivity.EXTRA_ITEM, item)
            }
            startActivity(intent)
        }
        recyclerView.adapter = adapter
    }
}
