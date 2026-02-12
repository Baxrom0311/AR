package com.huji.couchmirage.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.huji.couchmirage.R
import com.huji.couchmirage.catalog.CelestialBody
import com.huji.couchmirage.catalog.FirebaseRepository
import com.huji.couchmirage.catalog.ItemDetailsActivity
import com.huji.couchmirage.catalog.ItemRecyclerAdapter

class MoonsFragment : Fragment() {

    private val repository = FirebaseRepository.instance
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Set header
        view.findViewById<TextView>(R.id.header_title).text = "🌙 Yo'ldoshlar"
        view.findViewById<TextView>(R.id.header_subtitle).text = "Sayyoralar atrofidagi yo'ldoshlar"
        
        recyclerView = view.findViewById(R.id.recycler_view)
        progressBar = view.findViewById(R.id.progress_bar)
        emptyContainer = view.findViewById(R.id.empty_container)
        
        recyclerView.layoutManager = GridLayoutManager(requireContext(), 2)
        
        loadMoons()
    }

    private fun loadMoons() {
        progressBar.visibility = View.VISIBLE
        
        repository.getCelestialBodiesByType(
            "moon",
            onSuccess = { items ->
                progressBar.visibility = View.GONE
                if (items.isEmpty()) {
                    emptyContainer.visibility = View.VISIBLE
                } else {
                    setupRecyclerView(items)
                }
            },
            onError = { e ->
                progressBar.visibility = View.GONE
                Toast.makeText(requireContext(), "Xatolik: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        )
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
