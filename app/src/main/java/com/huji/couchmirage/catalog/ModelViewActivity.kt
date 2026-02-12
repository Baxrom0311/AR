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
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.SceneView
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.Light
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.ux.FootprintSelectionVisualizer
import com.google.ar.sceneform.ux.TransformationSystem
import com.google.ar.sceneform.ux.TransformableNode
import com.huji.couchmirage.R
import java.io.File

class ModelViewActivity : AppCompatActivity() {

    private val TAG = "ModelViewActivity"
    private lateinit var sceneView: SceneView
    private var modelRenderable: ModelRenderable? = null
    private lateinit var transformationSystem: TransformationSystem
    private var modelNode: TransformableNode? = null
    
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
    private val TARGET_MODEL_SIZE_M = 0.8f
    private val MAX_ADAPTIVE_TARGET_MODEL_SIZE_M = 1.1f
    private val MIN_BASE_SCALE = 0.0005f
    private val MAX_BASE_SCALE = 0.8f
    private val FALLBACK_BASE_SCALE = 0.03f
    private val INVALID_EXTENT_THRESHOLD = 0.02f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_view)

        sceneView = findViewById(R.id.sceneView)
        
        // Setup UI controls
        setupScaleControls()
        setupBackButton()
        
        // Prevent touch events on controls panel from passing to SceneView
        findViewById<android.view.View>(R.id.controls_panel).setOnTouchListener { _, _ -> true }

        // Setup TransformationSystem for gestures (Drag, Pinch, Rotate)
        transformationSystem = TransformationSystem(resources.displayMetrics, FootprintSelectionVisualizer())

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
        setupLighting()
        setupGestureDetectors()
        setupTouchListener()
    }

    private fun getModelFileExtra(): File? {
        val cacheSubDir = File(cacheDir, "models")
        val modelFilename = intent.getStringExtra(ItemDetailsActivity.EXTRA_MODEL_FILENAME)
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')

        if (!modelFilename.isNullOrBlank()) {
            val candidate = File(cacheSubDir, modelFilename)
            if (candidate.exists() && candidate.isFile) {
                return candidate
            }
        }
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
                // Only update multiplier if change comes from user touch
                // Otherwise (from code), the multiplier is already correct
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
        
        // Quick scale buttons - set Multipliers
        findViewById<Button>(R.id.btn_scale_small).setOnClickListener {
            Log.d(TAG, "Button: Small Clicked")
            setMultiplier(0.5f)
        }
        findViewById<Button>(R.id.btn_scale_medium).setOnClickListener {
            Log.d(TAG, "Button: Medium Clicked")
            setMultiplier(1.0f) // Reset to smart scale
        }
        findViewById<Button>(R.id.btn_scale_large).setOnClickListener {
            Log.d(TAG, "Button: Large Clicked")
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
        
        // Reverse map multiplier to progress
        val progress = if (multiplier <= 1.0f) {
            ((multiplier - 0.1f) / 0.9f * 100).toInt()
        } else {
            100 + ((multiplier - 1.0f) / 4.0f * 100).toInt()
        }
        
        scaleSeekBar.progress = progress.coerceIn(0, 200)
        applyScale()
    }
    
    private fun applyScale() {
        Log.d(TAG, "applyScale: base=$baseScale, mult=$scaleMultiplier, node=${modelNode!=null}")
        if (modelNode != null) {
            val finalScale = baseScale * scaleMultiplier
            modelNode?.localScale = Vector3(finalScale, finalScale, finalScale)
            Log.d(TAG, "Applied Scale: $finalScale")
        }
    }
    
    private fun setupCamera() {
        // Position camera to look at the center
        val camera = sceneView.scene.camera
        camera.localPosition = Vector3(0f, 0f, 1.5f) // Move camera closer (1.5m) to make 1m object look large
        camera.localRotation = Quaternion.identity() // Look straight ahead
    }
    
    private fun setupLighting() {
        try {
            // Add a directional light
            val lightNode = Node()
            lightNode.localPosition = Vector3(1f, 2f, 1f)
            
            val directionalLight = Light.builder(Light.Type.DIRECTIONAL)
                .setColor(Color(1f, 1f, 1f))
                .setIntensity(1000f)
                .setShadowCastingEnabled(true)
                .build()
            lightNode.light = directionalLight
            sceneView.scene.addChild(lightNode)
            
            // Add ambient/point light
            val ambientNode = Node()
            ambientNode.localPosition = Vector3(-1f, 1f, 1f)
            val pointLight = Light.builder(Light.Type.POINT)
                .setColor(Color(1f, 1f, 1f))
                .setIntensity(500f)
                .build()
            ambientNode.light = pointLight
            sceneView.scene.addChild(ambientNode)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up lighting", e)
        }
    }
    
    private fun setupTouchListener() {
        sceneView.scene.addOnPeekTouchListener { _, motionEvent ->
            // Pass event to ScaleGestureDetector (Pinch Zoom)
            scaleGestureDetector.onTouchEvent(motionEvent)
            
            // Manual rotation handling for single touch (Scroll/Drag)
            // Only rotate if NOT scaling using gestures and pointer count is 1
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
                                
                                // Create rotation from angles: Y (Azimuth) first, then X (Elevation)
                                val rotY = Quaternion.axisAngle(Vector3.up(), currentAzimuth)
                                val rotX = Quaternion.axisAngle(Vector3.right(), currentElevation)
                                
                                // Apply rotation: Order matters! RotY * RotX applies X rotation in Y's frame? 
                                // We want intrinsic rotation or extrinsic?
                                // Standard Orbit: Rotate Y (global), then Rotate X (local)
                                node.localRotation = Quaternion.multiply(rotY, rotX)
                            }
                            
                            previousX = motionEvent.x
                            previousY = motionEvent.y
                        }
                    }
                }
            }
        }
    }

    private fun loadModel(file: File) {
        // Determine source type based on file extension
        val sourceType = if (file.extension.equals("gltf", ignoreCase = true)) {
            com.google.ar.sceneform.assets.RenderableSource.SourceType.GLTF2
        } else {
            com.google.ar.sceneform.assets.RenderableSource.SourceType.GLB
        }
        
        val renderableSource = com.google.ar.sceneform.assets.RenderableSource.builder()
            .setSource(this, Uri.fromFile(file), sourceType)
            .setScale(1.0f) // Load at original scale for accurate measurement
            .setRecenterMode(com.google.ar.sceneform.assets.RenderableSource.RecenterMode.ROOT)
            .build()

        ModelRenderable.builder()
            .setSource(this, renderableSource)
            .setRegistryId(file.absolutePath)
            .build()
            .thenAccept { renderable ->
                modelRenderable = renderable
                addNodeToScene(renderable)
                Toast.makeText(this, "Model yuklandi ✓", Toast.LENGTH_SHORT).show()
            }
            .exceptionally { throwable ->
                Log.e(TAG, "Unable to load model", throwable)
                runOnUiThread {
                    Toast.makeText(this, "Xato: ${throwable.message}", Toast.LENGTH_LONG).show()
                }
                null
            }
    }

    private fun addNodeToScene(renderable: ModelRenderable) {
        val scene = sceneView.scene
        
        // Root Pivot Node (The one we rotate/scale)
        // Positioned at World (0,0,0)
        val pivotNode = TransformableNode(transformationSystem)
        pivotNode.localPosition = Vector3(0f, 0f, 0f)
        pivotNode.scaleController.isEnabled = false
        pivotNode.getTranslationController().isEnabled = false
        pivotNode.rotationController.isEnabled = false
        
        // Geometry Node (Holds the model)
        // Offset to align model center with Pivot
        val geometryNode = Node()
        geometryNode.setParent(pivotNode)
        geometryNode.renderable = renderable
        
        // Assign Pivot globally for interactions
        modelNode = pivotNode
        
        // SMART SCALING: Fit model comfortably into view
        // 1. Get model's actual size (bounding box)
        val collisionShape = renderable.collisionShape
        var maxExtent = 0f
        
        if (collisionShape is com.google.ar.sceneform.collision.Box) {
            val extents = collisionShape.extents
            maxExtent = maxOf(extents.x, extents.y, extents.z)
        } else if (collisionShape is com.google.ar.sceneform.collision.Sphere) {
            maxExtent = collisionShape.radius * 2.0f
        }
        
        // 2. Calculate scale factor & Center offset
        val targetSize = adaptiveTargetModelSize()
        var centerOffset = Vector3.zero()
        
        if (collisionShape is com.google.ar.sceneform.collision.Box) {
            centerOffset = collisionShape.center
        } else if (collisionShape is com.google.ar.sceneform.collision.Sphere) {
            centerOffset = collisionShape.center
        }
        
        // Store as baseScale (safe autoscale)
        baseScale = computeSafeBaseScale(maxExtent, targetSize)
        
        // Reset multiplier
        scaleMultiplier = 1.0f
        
        Log.d(TAG, "Smart Scaling: MaxExtent=$maxExtent, BaseScale=$baseScale, Center=$centerOffset")
        
        // Offset geometry so its center is at Pivot (0,0,0)
        // Note: We do NOT scale the offset here, because it's local to the child node.
        // The parent (Pivot) will be scaled, which scales the child's position automatically.
        geometryNode.localPosition = Vector3(
            -centerOffset.x,
            -centerOffset.y,
            -centerOffset.z
        )
        
        // Apply calculated scale to Pivot
        applyScale()
        
        // Update seekbar
        scaleSeekBar.progress = 100
        updateScaleText(100)
        
        // Add Pivot to scene
        scene.addChild(pivotNode)
        
        // Select node for transformation (optional)
        transformationSystem.selectNode(pivotNode)
    }

    private fun computeSafeBaseScale(maxExtent: Float, targetSize: Float): Float {
        if (!maxExtent.isFinite() || maxExtent <= INVALID_EXTENT_THRESHOLD) {
            return FALLBACK_BASE_SCALE
        }
        return (targetSize / maxExtent).coerceIn(MIN_BASE_SCALE, MAX_BASE_SCALE)
    }

    private fun adaptiveTargetModelSize(): Float {
        val factor = screenScaleFactor()
        return (TARGET_MODEL_SIZE_M * factor).coerceIn(
            TARGET_MODEL_SIZE_M,
            MAX_ADAPTIVE_TARGET_MODEL_SIZE_M
        )
    }

    private fun screenScaleFactor(): Float {
        return when (resources.configuration.smallestScreenWidthDp) {
            in 960..Int.MAX_VALUE -> 1.35f
            in 840..959 -> 1.25f
            in 720..839 -> 1.18f
            in 600..719 -> 1.12f
            else -> 1.0f
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::sceneView.isInitialized) return
        try {
            sceneView.resume()
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming SceneView", e)
            Toast.makeText(this, "Error starting 3D view: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::sceneView.isInitialized) {
            sceneView.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::sceneView.isInitialized) {
            try {
                sceneView.pause()
            } catch (e: Exception) {
                Log.e(TAG, "Error in onDestroy", e)
            }
        }
    }
}
