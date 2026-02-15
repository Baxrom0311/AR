package com.huji.couchmirage.catalog

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.storage.FirebaseStorage
import com.huji.couchmirage.OpenCameraActivity
import com.huji.couchmirage.R
import com.huji.couchmirage.utils.ModelCacheHelper
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import java.io.File

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ItemDetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ITEM_ID = "ITEM_ID"
        const val EXTRA_ITEM = "ITEM_DATA"
        const val EXTRA_MODEL_FILENAME = "model_filename"
    }

    private val TAG = "ItemDetailsActivity"
    @Inject
    lateinit var repository: FirebaseRepository
    private var selectedItem: CelestialBody? = null
    
    private lateinit var loadingLayout: ConstraintLayout
    private lateinit var arButton: FloatingActionButton
    private lateinit var view3DButton: FloatingActionButton
    private lateinit var arLabel: TextView
    private lateinit var gotoStoreButton: Button
    
    // 3D Preview
    private lateinit var sceneView: SceneView
    private lateinit var modelLoading: ProgressBar
    private var modelNode: ModelNode? = null
    private val rotationHandler = Handler(Looper.getMainLooper())
    private var rotationAngle = 0f
    private var isRotationRunning = false
    
    private val rotationRunnable = object : Runnable {
        override fun run() {
            modelNode?.let { node ->
                rotationAngle += 1f
                if (rotationAngle >= 360f) rotationAngle = 0f
                // Rotate around Y axis
                node.rotation = Rotation(0f, rotationAngle, 0f)
            }
            rotationHandler.postDelayed(this, 16) // ~60fps
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.item_details_activity)
        
        loadingLayout = findViewById(R.id.load_animation) 
        arButton = findViewById(R.id.ar_camera_button)
        view3DButton = findViewById(R.id.view_3d_button)
        arLabel = findViewById(R.id.ar_label) 
        gotoStoreButton = findViewById(R.id.goto_store_button)
        
        findViewById<View>(R.id.back_button).setOnClickListener {
            finish()
        }
        
        // 3D Preview setup
        sceneView = findViewById(R.id.model_scene_view)
        modelLoading = findViewById(R.id.model_loading)
        
        val preloadedItem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_ITEM, CelestialBody::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_ITEM)
        }

        if (preloadedItem != null) {
            selectedItem = preloadedItem
            updateUI(preloadedItem)
            if (!preloadedItem.modelUrl.isNullOrEmpty()) {
                load3DPreview(preloadedItem.modelUrl!!)
            }
        } else {
            val itemId = intent.getStringExtra(EXTRA_ITEM_ID)
            if (itemId != null) {
                loadItemDetails(itemId)
            } else {
                showError("Item ID not found")
                finish()
            }
        }
        
        setupButtons()
    }
    
    private fun loadItemDetails(itemId: String) {
        repository.getCelestialBodyById(
            itemId,
            onSuccess = { item ->
                selectedItem = item
                updateUI(item)
                // Load 3D model for preview
                if (!item.modelUrl.isNullOrEmpty()) {
                    load3DPreview(item.modelUrl!!)
                }
            },
            onError = { e ->
                showError("Error loading details: ${e.message}")
            }
        )
    }
    
    private fun load3DPreview(modelUrl: String) {
        modelLoading.visibility = View.VISIBLE
        
        val cacheSubDir = File(cacheDir, "models").apply { mkdirs() }
        val localFile = File(cacheSubDir, ModelCacheHelper.buildCacheFileName(modelUrl))

        // Check cache
        if (localFile.exists() && localFile.length() > 0) {
            Log.d(TAG, "Using cached model for preview: ${localFile.absolutePath}")
            loadModelIntoScene(localFile)
            return
        }

        // Download model
        val storage = FirebaseStorage.getInstance()
        val modelRef = try {
            when {
                modelUrl.startsWith("gs://") || modelUrl.startsWith("https://firebasestorage.googleapis.com/") ->
                    storage.getReferenceFromUrl(modelUrl)
                modelUrl.startsWith("http") -> {
                    modelLoading.visibility = View.GONE
                    return
                }
                else -> storage.reference.child(modelUrl.trimStart('/'))
            }
        } catch (e: Exception) {
            modelLoading.visibility = View.GONE
            Log.e(TAG, "Invalid model URL: $modelUrl", e)
            return
        }
        
        modelRef.getFile(localFile).addOnSuccessListener {
            loadModelIntoScene(localFile)
        }.addOnFailureListener { e ->
            modelLoading.visibility = View.GONE
            Log.e(TAG, "Download error for preview", e)
            if (localFile.exists()) localFile.delete()
        }
    }
    
    private fun loadModelIntoScene(file: File) {
        try {
            if (modelNode != null) {
                sceneView.removeChildNode(modelNode!!)
                modelNode = null
            }

            modelNode = ModelNode(
                modelInstance = sceneView.modelLoader.createModelInstance(file = file),
                scaleToUnits = 0.8f // Fit to 0.8 meters
            ).apply {
                position = Position(0f, -0.4f, -1.0f) // Push back and center
                rotation = Rotation(0f, 0f, 0f)
            }
            
            sceneView.addChildNode(modelNode!!)
            modelLoading.visibility = View.GONE
            startPreviewRotation()
            
        } catch (e: Exception) {
            modelLoading.visibility = View.GONE
            Log.e(TAG, "Unable to load model for preview", e)
        }
    }
    
    private fun updateUI(item: CelestialBody) {
        findViewById<TextView>(R.id.item_name_text).text = item.name
        findViewById<TextView>(R.id.item_description_text).text = item.description
        
        val factsText = buildString {
            append("Radius: ${item.radius} km\n")
            append("Mass: ${item.mass ?: "N/A"}\n")
            append("Temperature: ${item.temperature ?: "N/A"}\n")
            if (item.moons > 0) append("Moons: ${item.moons}\n")
            if (item.facts.isNotEmpty()) {
                append("\n")
                item.facts.forEach { fact ->
                    append("• $fact\n")
                }
            }
        }
        findViewById<TextView>(R.id.item_facts_text).text = factsText
        
        gotoStoreButton.text = getString(R.string.more_info_wiki)
        gotoStoreButton.setOnClickListener {
             val url = "https://en.wikipedia.org/wiki/${item.name}"
             startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
        
        setupGenericButtons()
    }
    
    private fun setupButtons() {
        arButton.setOnClickListener {
            selectedItem?.let { item ->
                if (!item.modelUrl.isNullOrEmpty()) {
                    downloadAndOpen(item.modelUrl!!, isAR = true)
                } else {
                    Toast.makeText(this, R.string.no_3d_model, Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        view3DButton.setOnClickListener {
            selectedItem?.let { item ->
                if (!item.modelUrl.isNullOrEmpty()) {
                    downloadAndOpen(item.modelUrl!!, isAR = false)
                } else {
                    Toast.makeText(this, R.string.no_3d_model, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun setupGenericButtons() {
        val favButton = findViewById<FloatingActionButton>(R.id.favorite_button)
        
        // Check initial state
        selectedItem?.let { item ->
            val isFav = com.huji.couchmirage.utils.FavoritesManager.isFavorite(this, item.id)
            updateFavoriteIcon(favButton, isFav)
            
            favButton.setOnClickListener {
                val newState = com.huji.couchmirage.utils.FavoritesManager.toggleFavorite(this, item.id)
                updateFavoriteIcon(favButton, newState)
                
                val msg = if (newState) "Sevimlilarga qo'shildi" else "Sevimlilardan olib tashlandi"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun updateFavoriteIcon(button: FloatingActionButton, isFavorite: Boolean) {
        if (isFavorite) {
            button.setImageResource(R.drawable.ic_favorite_filled)
            button.setColorFilter(android.graphics.Color.parseColor("#FF4081")) // Pink tint
        } else {
            button.setImageResource(R.drawable.ic_favorite_border)
            button.setColorFilter(android.graphics.Color.WHITE) // White tint
        }
    }
    
    private fun downloadAndOpen(url: String, isAR: Boolean) {
        if (::loadingLayout.isInitialized) loadingLayout.visibility = View.VISIBLE
        
        val cacheSubDir = File(cacheDir, "models").apply { mkdirs() }
        val localFile = File(cacheSubDir, ModelCacheHelper.buildCacheFileName(url))

        // Check cache
        if (localFile.exists() && localFile.length() > 0) {
            Log.d(TAG, "Using cached model: ${localFile.absolutePath}")
            if (::loadingLayout.isInitialized) loadingLayout.visibility = View.GONE
            if (isAR) openAR(localFile) else open3D(localFile)
            return
        }

        val storage = FirebaseStorage.getInstance()
        val modelRef = try {
            when {
                url.startsWith("gs://") || url.startsWith("https://firebasestorage.googleapis.com/") ->
                    storage.getReferenceFromUrl(url)
                url.startsWith("http") -> {
                    if (::loadingLayout.isInitialized) loadingLayout.visibility = View.GONE
                    Toast.makeText(this, "Only Firebase Storage URLs are supported", Toast.LENGTH_SHORT).show()
                    return
                }
                else -> storage.reference.child(url.trimStart('/'))
            }
        } catch (e: Exception) {
            if (::loadingLayout.isInitialized) loadingLayout.visibility = View.GONE
            Toast.makeText(this, "Invalid model URL: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Invalid model URL: $url", e)
            return
        }
        
        try {
            modelRef.getFile(localFile).addOnSuccessListener {
                if (::loadingLayout.isInitialized) loadingLayout.visibility = View.GONE
                if (isAR) openAR(localFile) else open3D(localFile)
            }.addOnFailureListener { e ->
                if (::loadingLayout.isInitialized) loadingLayout.visibility = View.GONE
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Download error", e)
                if (localFile.exists()) localFile.delete()
            }
        } catch (e: Exception) {
            if (::loadingLayout.isInitialized) loadingLayout.visibility = View.GONE
            Log.e(TAG, "File creation error", e)
        }
    }
    
    private fun openAR(file: File) {
        val intent = Intent(this, OpenCameraActivity::class.java).apply {
            putExtra(EXTRA_MODEL_FILENAME, file.name)
             putExtra("model_length", 0.5f)
             putExtra("model_width", 0.5f)
             putExtra("model_height", 0.5f)
             putExtra("auto_place", true)
        }
        startActivity(intent)
    }
    
    private fun open3D(file: File) {
        val intent = Intent(this, ModelViewActivity::class.java).apply {
            putExtra(EXTRA_MODEL_FILENAME, file.name)
        }
        startActivity(intent)
    }

    private fun showError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun startPreviewRotation() {
        if (isRotationRunning || modelNode == null) return
        isRotationRunning = true
        rotationHandler.post(rotationRunnable)
    }

    private fun stopPreviewRotation() {
        isRotationRunning = false
        rotationHandler.removeCallbacks(rotationRunnable)
    }
    
    override fun onResume() {
        super.onResume()
        if (::sceneView.isInitialized) {
            // sceneView.resume()
            startPreviewRotation()
        }
    }
    
    override fun onPause() {
        super.onPause()
        stopPreviewRotation()
        if (::sceneView.isInitialized) {
            // sceneView.pause()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopPreviewRotation()
        if (::sceneView.isInitialized) {
            try {
                sceneView.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error in onDestroy", e)
            }
        }
    }
}
