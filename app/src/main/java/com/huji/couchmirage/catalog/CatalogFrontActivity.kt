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

class CatalogFrontActivity : AppCompatActivity() {

    private val TAG = "CatalogFrontActivity"
    private val repository = FirebaseRepository.instance
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorTextView: TextView
    private lateinit var adapter: CategoryRecyclerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_catalog_front)
        
        // Title setup
        supportActionBar?.title = "Astronomiya AR"
        
        // View binding
        recyclerView = findViewById(R.id.recycler_view_departments)
        progressBar = findViewById(R.id.progressBar)
        errorTextView = findViewById(R.id.errorTextView)
        
        // RecyclerView setup
        recyclerView.layoutManager = GridLayoutManager(this, 2)
        
        // Load data
        loadCategories()
    }
    
    private fun loadCategories() {
        // Show loading
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        errorTextView.visibility = View.GONE
        
        Log.d(TAG, "Loading categories from Firebase...")
        
        repository.getCategories(
            onSuccess = { categories ->
                Log.d(TAG, "Categories loaded successfully: ${categories.size}")
                progressBar.visibility = View.GONE
                
                if (categories.isEmpty()) {
                    showError("Kategoriyalar topilmadi")
                } else {
                    recyclerView.visibility = View.VISIBLE
                    setupRecyclerView(categories)
                }
            },
            onError = { exception ->
                Log.e(TAG, "Error loading categories", exception)
                progressBar.visibility = View.GONE
                showError("Xatolik: ${exception.message}")
            }
        )
    }
    
    private fun setupRecyclerView(categories: List<Category>) {
        adapter = CategoryRecyclerAdapter(categories) { category ->
            onCategoryClicked(category)
        }
        recyclerView.adapter = adapter
    }
    
    private fun onCategoryClicked(category: Category) {
        Log.d(TAG, "Category clicked: ${category.name}")
        Toast.makeText(this, "${category.name} tanlandi", Toast.LENGTH_SHORT).show()
        
        // Navigate to DepartmentActivity
        val intent = Intent(this, CategoryActivity::class.java).apply {
            putExtra("CATEGORY_ID", category.id)
            putExtra("CATEGORY_NAME", category.name)
        }
        startActivity(intent)
    }
    
    private fun showError(message: String) {
        errorTextView.visibility = View.VISIBLE
        errorTextView.text = message
        recyclerView.visibility = View.GONE
        
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    override fun onResume() {
        super.onResume()
        // Optional refresh
    }
}
