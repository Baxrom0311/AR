package com.huji.couchmirage

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.huji.couchmirage.catalog.CelestialBody
import com.huji.couchmirage.catalog.FirebaseRepository

class ARSelectionActivity : AppCompatActivity() {

    private val repository = FirebaseRepository.instance
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCompare: Button
    private lateinit var selectedCountText: TextView

    private val selectedItemIds = mutableSetOf<String>()
    private var allItems = listOf<CelestialBody>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            setContentView(R.layout.activity_ar_selection)
            
            recyclerView = findViewById(R.id.recycler_view)
            progressBar = findViewById(R.id.progress_bar)
            btnCompare = findViewById(R.id.btn_compare)
            selectedCountText = findViewById(R.id.selected_count)

            recyclerView.layoutManager = LinearLayoutManager(this)

            btnCompare.setOnClickListener {
                if (selectedItemIds.size < 2) {
                    Toast.makeText(this, "Kamida 2 ta element tanlang", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                openARCompare()
            }

            findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }

            updateSelectedCount()
            loadAllItems()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Xatolik yuz berdi: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun loadAllItems() {
        progressBar.visibility = View.VISIBLE
        
        // Load all celestial bodies (minimal data - name, radius only needed for display)
        repository.getAllCelestialBodies(
            onSuccess = { items ->
                progressBar.visibility = View.GONE
                allItems = items.filter { it.radius > 0 } // Only items with valid radius
                setupAdapter()
            },
            onError = { e ->
                progressBar.visibility = View.GONE
                Toast.makeText(this, "Xatolik: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun setupAdapter() {
        recyclerView.adapter = SelectionAdapter(allItems) { itemId, isChecked ->
            updateSelection(itemId, isChecked)
        }
    }

    private fun updateSelection(itemId: String, isChecked: Boolean) {
        if (isChecked) {
            selectedItemIds.add(itemId)
        } else {
            selectedItemIds.remove(itemId)
        }
        updateSelectedCount()
    }

    private fun updateSelectedCount() {
        selectedCountText.text = "${selectedItemIds.size} ta tanlandi"
        btnCompare.isEnabled = selectedItemIds.size >= 2
    }

    private fun openARCompare() {
        val selectedBodies = allItems.filter { selectedItemIds.contains(it.id) }
        if (selectedBodies.size < 2) {
            Toast.makeText(this, "Kamida 2 ta element tanlang", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, ARCompareActivity::class.java).apply {
            putStringArrayListExtra("item_ids", ArrayList(selectedBodies.map { it.id }))
            putStringArrayListExtra("item_names", ArrayList(selectedBodies.map { it.name }))
        }
        startActivity(intent)
    }

    // Adapter
    inner class SelectionAdapter(
        private val items: List<CelestialBody>,
        private val onItemChecked: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<SelectionAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkbox: CheckBox = view.findViewById(R.id.checkbox)
            val name: TextView = view.findViewById(R.id.item_name)
            val radius: TextView = view.findViewById(R.id.item_radius)
            val category: TextView = view.findViewById(R.id.item_category)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_ar_selectable, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            
            holder.name.text = item.name
            holder.radius.text = formatRadius(item.radius)
            holder.category.text = item.category

            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = selectedItemIds.contains(item.id)
            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                onItemChecked(item.id, isChecked)
            }

            holder.itemView.setOnClickListener {
                holder.checkbox.toggle()
            }
        }

        override fun getItemCount() = items.size

        private fun formatRadius(radius: Double): String {
            return when {
                radius >= 1_000_000 -> String.format("%.1f mln km", radius / 1_000_000)
                radius >= 1000 -> String.format("%.0f km", radius)
                else -> String.format("%.1f km", radius)
            }
        }
    }
}
