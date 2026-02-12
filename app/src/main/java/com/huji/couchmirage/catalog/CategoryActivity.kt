package com.huji.couchmirage.catalog

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.huji.couchmirage.R

class CategoryActivity : AppCompatActivity() {

    private val TAG = "CategoryActivity"
    private val repository = FirebaseRepository.instance
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorTextView: TextView
    private lateinit var titleTextView: TextView
    private lateinit var adapter: ItemRecyclerAdapter
    
    private var categoryId: String? = null
    private var categoryName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category)

        // Get Intent data
        categoryId = intent.getStringExtra("CATEGORY_ID")
        categoryName = intent.getStringExtra("CATEGORY_NAME")
        
        // Setup UI
        recyclerView = findViewById(R.id.recycler_view)
        progressBar = findViewById(R.id.progressBar)
        errorTextView = findViewById(R.id.errorTextView)
        titleTextView = findViewById(R.id.category_title)
        
        titleTextView.text = categoryName ?: "Kategoriya"
        
        // RecyclerView
        recyclerView.layoutManager = GridLayoutManager(this, 2)
        
        // Load Data
        if (categoryId != null) {
            loadCelestialBodies(categoryId!!)
        } else {
            showError("Kategoriya ID topilmadi")
        }
    }
    
    private fun loadCelestialBodies(categoryId: String) {
        progressBar.visibility = View.VISIBLE
        
        repository.getCelestialBodiesByCategory(
            categoryId,
            onSuccess = { items ->
                progressBar.visibility = View.GONE
                if (items.isEmpty()) {
                    showError("Bu kategoriyada hech narsa yo'q")
                } else {
                    setupRecyclerView(items)
                }
            },
            onError = { e ->
                progressBar.visibility = View.GONE
                showError("Xatolik: ${e.message}")
            }
        )
    }
    
    private fun setupRecyclerView(items: List<CelestialBody>) {
        adapter = ItemRecyclerAdapter(items) { item ->
            openItemDetails(item)
        }
        recyclerView.adapter = adapter
    }
    
    private fun openItemDetails(item: CelestialBody) {
        val intent = Intent(this, ItemDetailsActivity::class.java).apply {
            putExtra(ItemDetailsActivity.EXTRA_ITEM_ID, item.id)
            putExtra(ItemDetailsActivity.EXTRA_ITEM, item)
        }
        startActivity(intent)
    }
    
    private fun showError(message: String) {
        errorTextView.visibility = View.VISIBLE
        errorTextView.text = message
        recyclerView.visibility = View.GONE
    }
}
