package com.huji.couchmirage.catalog

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.huji.couchmirage.R
import com.huji.couchmirage.utils.FavoritesManager

class FavoritesActivity : AppCompatActivity() {

    private val repository = FirebaseRepository.instance
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var loadingProgress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)

        recyclerView = findViewById(R.id.item_recycler)
        emptyText = findViewById(R.id.empty_text)
        loadingProgress = findViewById(R.id.loading_progress)
        
        recyclerView.layoutManager = LinearLayoutManager(this)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        loadFavorites()
    }

    private fun loadFavorites() {
        // 1. Get IDs from local storage
        val favoriteIds = FavoritesManager.getFavorites(this)
        
        if (favoriteIds.isEmpty()) {
            showEmptyState()
            return
        }

        loadingProgress.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        recyclerView.visibility = View.GONE

        // 2. Fetch all bodies and filter 
        // (Optimization: In a larger app, we would query by IDs specifically)
        repository.getAllCelestialBodies(
            onSuccess = { allBodies ->
                val favoriteBodies = allBodies.filter { favoriteIds.contains(it.id) }
                
                runOnUiThread {
                    loadingProgress.visibility = View.GONE
                    
                    if (favoriteBodies.isEmpty()) {
                        showEmptyState()
                    } else {
                        showData(favoriteBodies)
                    }
                }
            },
            onError = { e ->
                runOnUiThread {
                    loadingProgress.visibility = View.GONE
                    Toast.makeText(this, "Xatolik: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun showEmptyState() {
        emptyText.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
    }

    private fun showData(bodies: List<CelestialBody>) {
        recyclerView.visibility = View.VISIBLE
        recyclerView.adapter = ItemRecyclerAdapter(bodies) { body ->
            // Open details
            val intent = Intent(this, ItemDetailsActivity::class.java)
            intent.putExtra(ItemDetailsActivity.EXTRA_ITEM_ID, body.id)
            intent.putExtra(ItemDetailsActivity.EXTRA_ITEM, body)
            startActivity(intent)
        }
    }
}
