package com.huji.couchmirage.catalog

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.huji.couchmirage.R
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.launch
import java.io.File
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager

import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ModelViewActivity : AppCompatActivity() {

    private val TAG = "ModelViewActivity"
    private lateinit var sceneView: SceneView
    private var modelNode: ModelNode? = null
    
    // UI Controls
    private lateinit var scaleSeekBar: SeekBar
    private lateinit var scaleValueText: TextView
    
    // Touch tracking for rotation
    private var previousX = 0f
    private var previousY = 0f
    
    // Base scale calculated from model bounds (Smart Scale)
    private var baseScale = 1.0f
    
    // Setup multiplier (1.0 = baseScale)
    private var scaleMultiplier = 1.0f
    
    // Rotation State (Euler angles)
    private var currentAzimuth = 0f // Horizontal angle (degrees)
    private var currentElevation = 0f // Vertical angle (degrees)
    
    // Manual Gesture Detectors
    private lateinit var scaleGestureDetector: android.view.ScaleGestureDetector

    // Conservative auto-fit values for mixed-origin assets (NASA and others)
    private val TARGET_MODEL_SIZE_M = 0.5f // Reduced from 0.8f to prevent "inside camera" issue
    private val MAX_ADAPTIVE_TARGET_MODEL_SIZE_M = 0.8f // Reduced cap
    private val MIN_BASE_SCALE = 0.0005f
    private val MAX_BASE_SCALE = 0.8f
    private val FALLBACK_BASE_SCALE = 0.03f
    private val INVALID_EXTENT_THRESHOLD = 0.02f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_view)

        sceneView = findViewById(R.id.sceneView)
        
        // Try to register lifecycle observer safely
        try {
            if (sceneView is androidx.lifecycle.LifecycleObserver) {
                lifecycle.addObserver(sceneView as androidx.lifecycle.LifecycleObserver)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register lifecycle observer", e)
        }

        // Configure Lighting for Natural Look
        lifecycleScope.launch {
            try {
                // Remove previous lights if any
                sceneView.childNodes.filterIsInstance<io.github.sceneview.node.LightNode>().forEach {
                    sceneView.removeChildNode(it)
                }

                // --- STUDIO LIGHTING SETUP (Key, Fill, Back) ---
                // Since we lack a reliable HDR environment, we use multiple directional lights 
                // to ensure the model is visible from all angles.

                // 1. Key Light (Main Sun) - Bright, from top-right-front
                val keyLightEntity = EntityManager.get().create()
                LightManager.Builder(LightManager.Type.DIRECTIONAL)
                    .color(1.0f, 1.0f, 1.0f)
                    .intensity(110000.0f) // Very bright
                    .direction(-1.0f, -1.0f, -1.0f) // Down and forward-right
                    .castShadows(true)
                    .build(sceneView.engine, keyLightEntity)
                sceneView.addChildNode(io.github.sceneview.node.Node(sceneView.engine, keyLightEntity))

                // 2. Fill Light (Softener) - Softer, from left
                val fillLightEntity = EntityManager.get().create()
                LightManager.Builder(LightManager.Type.DIRECTIONAL)
                    .color(0.9f, 0.9f, 1.0f) // Slightly cool
                    .intensity(50000.0f) 
                    .direction(1.0f, -0.5f, -0.5f) // From left-side
                    .castShadows(false)
                    .build(sceneView.engine, fillLightEntity)
                sceneView.addChildNode(io.github.sceneview.node.Node(sceneView.engine, fillLightEntity))

                // 3. Back Light (Rim) - Separates model from background
                val backLightEntity = EntityManager.get().create()
                LightManager.Builder(LightManager.Type.DIRECTIONAL)
                    .color(1.0f, 0.9f, 0.8f) // Slightly warm
                    .intensity(60000.0f)
                    .direction(0.0f, -1.0f, 1.0f) // From behind/top
                    .castShadows(false)
                    .build(sceneView.engine, backLightEntity)
                sceneView.addChildNode(io.github.sceneview.node.Node(sceneView.engine, backLightEntity))


                // Add an environment light (IndirectLight)
                // Since we don't have an HDR file easily accessible, we use a simple fallback
                // or rely on SceneView's default if available.
                // For now, we just ensure the main light is strong enough.
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to configure lighting", e)
            }
        }
        
        // Setup UI controls
        setupScaleControls()
        setupBackButton()
        
        // Prevent touch events on controls panel from passing to SceneView
        findViewById<android.view.View>(R.id.controls_panel).setOnTouchListener { _, _ -> true }

        val file = getModelFileExtra()
        if (file != null && file.exists()) {
            Log.d(TAG, "Loading model from: ${file.absolutePath}")
            loadModel(file)
        } else {
            val errorMsg = if (file == null) "Model file not provided" else "Model file not found: ${file.absolutePath}"
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
            Log.e(TAG, errorMsg)
            finish()
        }
        
        setupCamera()
        setupGestureDetectors()
        setupTouchListener()
    }

    private fun getModelFileExtra(): File? {
        val modelFilename = intent.getStringExtra(ItemDetailsActivity.EXTRA_MODEL_FILENAME)
        
        if (modelFilename.isNullOrBlank()) {
             Log.w(TAG, "No model filename provided in intent")
             return null
        }

        // 1. Try treating it as a full absolute path
        val asFile = File(modelFilename)
        if (asFile.exists() && asFile.isFile) {
             return asFile
        }

        // 2. Try simple filename in cache (legacy behavior)
        val simpleName = File(modelFilename).name
        val cacheSubDir = File(cacheDir, "models")
        val candidate = File(cacheSubDir, simpleName)
        
        if (candidate.exists() && candidate.isFile) {
            return candidate
        }
        
        Log.e(TAG, "Could not resolve model file: $modelFilename")
        return null
    }
    
    private fun setupGestureDetectors() {
        scaleGestureDetector = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                Log.d(TAG, "onScale: factor=$scaleFactor")
                scaleMultiplier *= scaleFactor
                // Clamp multiplier
                scaleMultiplier = scaleMultiplier.coerceIn(0.1f, 5.0f)
                
                applyScale()
                updateUIFromMultiplier()
                return true
            }
        })
    }
    
    private fun updateUIFromMultiplier() {
        val progress = if (scaleMultiplier <= 1.0f) {
            ((scaleMultiplier - 0.1f) / 0.9f * 100).toInt()
        } else {
            100 + ((scaleMultiplier - 1.0f) / 4.0f * 100).toInt()
        }
        
        scaleSeekBar.progress = progress.coerceIn(0, 200)
        updateScaleText(scaleSeekBar.progress)
    }
    
    private fun setupBackButton() {
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
            finish()
        }
    }
    
    private fun setupScaleControls() {
        scaleSeekBar = findViewById(R.id.scale_seekbar)
        scaleValueText = findViewById(R.id.scale_value_text)
        
        // SeekBar: 0-200. Center (100) = 1.0x multiplier
        scaleSeekBar.max = 200
        scaleSeekBar.progress = 100 // Default 1.0x
        updateScaleText(100)
        
        scaleSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    Log.d(TAG, "SeekBar onProgressChanged: $progress (User)")
                    val multiplier = if (progress <= 100) {
                        0.1f + (progress / 100f) * 0.9f 
                    } else {
                        1.0f + ((progress - 100f) / 100f) * 4.0f
                    }
                    
                    scaleMultiplier = multiplier
                    updateScaleText(progress)
                    applyScale()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        findViewById<Button>(R.id.btn_scale_small).setOnClickListener {
            setMultiplier(0.5f)
        }
        findViewById<Button>(R.id.btn_scale_medium).setOnClickListener {
            setMultiplier(1.0f) 
        }
        findViewById<Button>(R.id.btn_scale_large).setOnClickListener {
            setMultiplier(2.0f)
        }
    }
    
    private fun updateScaleText(progress: Int) {
        val multiplier = if (progress <= 100) {
            0.1f + (progress / 100f) * 0.9f
        } else {
            1.0f + ((progress - 100f) / 100f) * 4.0f
        }
        scaleValueText.text = String.format("%.2fx", multiplier)
    }
    
    private fun setMultiplier(multiplier: Float) {
        Log.d(TAG, "setMultiplier: $multiplier")
        scaleMultiplier = multiplier
        
        val progress = if (multiplier <= 1.0f) {
            ((multiplier - 0.1f) / 0.9f * 100).toInt()
        } else {
            100 + ((multiplier - 1.0f) / 4.0f * 100).toInt()
        }
        
        scaleSeekBar.progress = progress.coerceIn(0, 200)
        applyScale()
    }
    
    private fun applyScale() {
        if (modelNode != null) {
            val finalScale = baseScale * scaleMultiplier
            // Update scale using SceneView's Scale (Vector3 alias)
            modelNode?.scale = Scale(finalScale, finalScale, finalScale)
            Log.d(TAG, "Applied Scale: $finalScale")
        }
    }
    
    private fun setupCamera() {
        // Move camera closer (1.5m) to make 1m object look large
        sceneView.cameraNode.position = Position(0f, 0f, 2.0f)
        sceneView.cameraNode.rotation = Rotation(0f, 0f, 0f)
    }
    
    private fun setupTouchListener() {
        // SceneView handles touches differently, but we can attach a listener to the view
        sceneView.setOnTouchListener { _, motionEvent ->
             // Pass event to ScaleGestureDetector (Pinch Zoom)
            scaleGestureDetector.onTouchEvent(motionEvent)
            
            // Manual rotation handling for single touch (Scroll/Drag)
            if (motionEvent.pointerCount == 1 && !scaleGestureDetector.isInProgress) {
                when (motionEvent.action) {
                    MotionEvent.ACTION_DOWN -> {
                        previousX = motionEvent.x
                        previousY = motionEvent.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = motionEvent.x - previousX
                        val deltaY = motionEvent.y - previousY
                        
                        // Threshold to avoid jitter
                        if (Math.abs(deltaX) > 1f || Math.abs(deltaY) > 1f) {
                            modelNode?.let { node ->
                                val rotationSpeed = 0.5f // Adjust sensitivity
                                
                                // Update angles
                                currentAzimuth -= deltaX * rotationSpeed
                                currentElevation -= deltaY * rotationSpeed
                                
                                // Clamp vertical rotation to prevent flipping upside down
                                currentElevation = currentElevation.coerceIn(-85f, 85f)
                                
                                // Apply rotation (Euler angles in degrees)
                                node.rotation = Rotation(currentElevation, currentAzimuth, 0f)
                            }
                            
                            previousX = motionEvent.x
                            previousY = motionEvent.y
                        }
                    }
                }
            }
            true // Consume event
        }
    }

    private fun loadModel(file: File) {
          lifecycleScope.launch {
              try {
                  if (modelNode != null) {
                      sceneView.removeChildNode(modelNode!!)
                      modelNode = null
                  }

                  // Auto-scale to fit roughly 0.8m visual size
                  val instance = sceneView.modelLoader.createModelInstance(file)
                  if (instance != null) {
                      modelNode = ModelNode(
                          modelInstance = instance,
                          scaleToUnits = TARGET_MODEL_SIZE_M,
                          centerOrigin = Position(0.0f, 0.0f, 0.0f)
                      ).apply {
                          // Center the model explicitly if needed (though centerOrigin handles mesh offset)
                          position = Position(0f, 0f, 0f)
                          rotation = Rotation(0f, 0f, 0f)
                      }
                      
                      // Critical Fix: Capture the auto-calculated scale as our base!
                      // If model was huge (1000m), scaleToUnits made it tiny (0.0005).
                      // We must use THAT as base, not 1.0f.
                      baseScale = modelNode?.scale?.x ?: 1.0f
                      
                      // Apply initial multiplier (1.0x relative to auto-fit size)
                      applyScale()
                      
                      sceneView.addChildNode(modelNode!!)
                      Toast.makeText(this@ModelViewActivity, "Model yuklandi ✓", Toast.LENGTH_SHORT).show()
                  } else {
                       Toast.makeText(this@ModelViewActivity, "Model yuklanmadi (null)", Toast.LENGTH_SHORT).show()
                  }
                  
              } catch (e: Exception) {
                  Log.e(TAG, "Unable to load model", e)
                  Toast.makeText(this@ModelViewActivity, "Xato: ${e.message}", Toast.LENGTH_LONG).show()
              }
          }
    }

    override fun onResume() {
        super.onResume()
        // sceneView.resume() // Not available in SceneView 2.x
    }

    override fun onPause() {
        super.onPause()
        // sceneView.pause() // Not available in SceneView 2.x
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::sceneView.isInitialized) {
            try {
                sceneView.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error in onDestroy", e)
            }
        }
    }
}
