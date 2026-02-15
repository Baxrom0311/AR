package com.huji.couchmirage

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.huji.couchmirage.catalog.ItemDetailsActivity
import com.huji.couchmirage.utils.ARScaleHelper
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode 
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import java.util.concurrent.atomic.AtomicBoolean
import io.github.sceneview.math.toFloat3

import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class OpenCameraActivity : AppCompatActivity() {
    
    private val TAG = "OpenCameraActivity"
    private val CAMERA_PERMISSION_REQUEST_CODE = 1001

    private lateinit var sceneView: ARSceneView
    var file: File? = null
    private var isModelLoaded = false
    
    // Parameters
    private val TARGET_MODEL_DIAMETER_M = 0.8f
    private val MAX_ADAPTIVE_TARGET_DIAMETER_M = 1.1f
    private val MIN_NODE_SCALE = 0.0005f
    private val MAX_NODE_SCALE = 0.8f
    private val FALLBACK_NODE_SCALE = 0.03f
    private val INVALID_EXTENT_THRESHOLD = 0.02f
    private val AIR_PLACEMENT_FORWARD_M = 1.5f
    private val CAMERA_MODEL_SAFETY_MARGIN_M = 0.25f
    private val MIN_ALLOWED_MODEL_RADIUS_M = 0.08f
    private val INSIDE_WARNING_BUFFER_M = 0.05f
    private val INSIDE_WARNING_COOLDOWN_MS = 4500L

    private var placedModelNode: ModelNode? = null
    private var anchorNode: AnchorNode? = null
    
    private var placedModelRadiusM = 0f
    private var lastInsideWarningMs = 0L
    private var autoPlaceRequested = false
    private var autoPlaceDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.open_camera)

        if (checkCameraPermission()) {
            initializeAR()
        } else {
            requestCameraPermission()
        }
        
        setupClearButton() 
        setupPlaceButton()
        setupScreenshotButton()
        
        checkIntentForModel()
    }
    
    private fun checkCameraPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeAR()
            } else {
                Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
    
    private fun initializeAR() {
        try {
            sceneView = findViewById(R.id.fragment)
            
            sceneView.configureSession { _, config ->
                 config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                 config.depthMode = Config.DepthMode.AUTOMATIC
                 config.focusMode = Config.FocusMode.AUTO
                 config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            }
            
            sceneView.setOnTouchListener { _, event ->
                // ... (existing touch listener) ...
                if (event.action == MotionEvent.ACTION_UP) {
                    val frame = sceneView.frame
                    val hitResult = frame?.hitTest(event)?.firstOrNull()
                    
                    if (hitResult != null) {
                        val anchor = hitResult.createAnchor()
                        placeModel(anchor, isAir = false)
                    }
                    return@setOnTouchListener true
                }
                return@setOnTouchListener true
            }

            // Instructions logic
            val instructionView = findViewById<View>(R.id.instruction_container)
            instructionView?.visibility = View.VISIBLE
            
            // Instructions visibility loop
            lifecycleScope.launch {
                while (isActive) {
                    delay(500)
                    if (placedModelNode != null) {
                        if (instructionView?.visibility == View.VISIBLE) {
                            instructionView.visibility = View.GONE
                        }
                        continue
                    }

                    val frame = sceneView.frame ?: continue
                    val planes = frame.getUpdatedTrackables(Plane::class.java)
                    // Check if any plane is TRACKING
                    val allPlanes = sceneView.session?.getAllTrackables(Plane::class.java)
                    val isTracking = allPlanes?.any { it.trackingState == com.google.ar.core.TrackingState.TRACKING } == true
                    
                    if (isTracking) {
                        if (instructionView?.visibility == View.VISIBLE) {
                             instructionView.visibility = View.GONE
                        }
                    } else {
                        if (instructionView?.visibility != View.VISIBLE) {
                             instructionView?.visibility = View.VISIBLE
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AR SceneView", e)
            Toast.makeText(this, "AR initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        placedModelNode = null
        anchorNode = null
    }

    private fun checkIntentForModel() {
        val resolvedFile = resolveModelFileFromIntent()
        if (resolvedFile != null) {
            file = resolvedFile
            isModelLoaded = true
            Toast.makeText(this, "Model valid", Toast.LENGTH_SHORT).show()
            
            if (intent.getBooleanExtra("auto_place", false)) {
                autoPlaceRequested = true
                autoPlaceDone = false
                tryAutoPlaceModel()
            }
        }
    }

    private fun resolveModelFileFromIntent(): File? {
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
        val simpleName = File(modelFilename).name // safer than substringAfterLast
        val cacheSubDir = File(cacheDir, "models")
        val candidate = File(cacheSubDir, simpleName)
        
        if (candidate.exists() && candidate.isFile) {
            return candidate
        }
        
        Log.e(TAG, "Could not resolve model file: $modelFilename")
        return null
    }

    private fun placeModelInAir() {
         val frame = sceneView.frame ?: return
         val session = sceneView.session ?: return

         // 1. Try to hit a plane or feature point in the center of the screen for stability
         val center = io.github.sceneview.math.Position(sceneView.width / 2f, sceneView.height / 2f)
         // Note: hitTest uses check for Planes and Feature Points.
         val hits = frame.hitTest(center.x, center.y)
         
         // Filter for Plane or Point with orientation (more stable than raw point)
         val bestHit = hits.firstOrNull { hit ->
             val trackable = hit.trackable
             trackable is Plane || (trackable is com.google.ar.core.Point && trackable.orientationMode == com.google.ar.core.Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)
         }

         if (bestHit != null) {
             // We hit a stable surface! Use it.
             val anchor = bestHit.createAnchor()
             val newAnchorNode = AnchorNode(sceneView.engine, anchor)
             sceneView.addChildNode(newAnchorNode)
             
             // If we hit a plane, we can treat it as surface placement (shadows ON)
             // But the function is called "Air". Let's assume stability is key.
             // If the user hit a plane, show shadows.
             val hitIsPlane = bestHit.trackable is Plane
             placeModel(anchor, isAir = !hitIsPlane, existingAnchorNode = newAnchorNode)
             Toast.makeText(this, if (hitIsPlane) "Yuzaga joylashtirildi (Barqaror)" else "Nuqtaga joylashtirildi", Toast.LENGTH_SHORT).show()
             return
         }

         // 2. Fallback: Place in front of camera (Floating) if no surface found
         if (session.getAllTrackables(Plane::class.java).isEmpty()) {
             Toast.makeText(this, "Yaxshiroq joylashtirish uchun telefonni harakatlantiring", Toast.LENGTH_SHORT).show()
         }
         
         val camera = frame.camera
         val pose = camera.pose.compose(Pose.makeTranslation(0f, 0f, -AIR_PLACEMENT_FORWARD_M))
         val anchor = session.createAnchor(pose)
         
         val newAnchorNode = AnchorNode(sceneView.engine, anchor)
         sceneView.addChildNode(newAnchorNode)
         
         placeModel(anchor, isAir = true, existingAnchorNode = newAnchorNode)
         Toast.makeText(this, "Fazoga joylashtirildi", Toast.LENGTH_SHORT).show()
    }

    private fun placeModel(anchor: com.google.ar.core.Anchor?, isAir: Boolean, existingAnchorNode: AnchorNode? = null) {
        val modelFile = file ?: return
        if (!isModelLoaded) return

        onClear()

        val currentAnchorNode = existingAnchorNode ?: if (anchor != null) {
             val node = AnchorNode(sceneView.engine, anchor)
             sceneView.addChildNode(node)
             node
        } else {
             return
        }
        anchorNode = currentAnchorNode
        
        lifecycleScope.launch {
            try {
                // Use named argument 'file' to ensure Sceneview treats it as a file, not asset
                val modelInstance = sceneView.modelLoader.createModelInstance(file = modelFile)
                if (modelInstance != null) {
                    runOnUiThread {
                        findViewById<View>(R.id.instruction_container)?.visibility = View.GONE
                    }
                    val modelNode = ModelNode(
                        modelInstance = modelInstance,
                        scaleToUnits = null
                    )
                     modelNode.isShadowCaster = !isAir
                     modelNode.isShadowReceiver = !isAir
                     modelNode.isTouchable = true
                     modelNode.isPositionEditable = true
                     modelNode.isRotationEditable = true
                     modelNode.isScaleEditable = true
                     
                     currentAnchorNode.addChildNode(modelNode)
                     placedModelNode = modelNode
                     
                     val maxExtent = ARScaleHelper.extractModelExtent(modelNode)
                     val targetDiameter = ARScaleHelper.adaptiveTargetSize(
                        baseSize = TARGET_MODEL_DIAMETER_M,
                        maxAdaptiveSize = MAX_ADAPTIVE_TARGET_DIAMETER_M,
                        smallestScreenWidthDp = resources.configuration.smallestScreenWidthDp
                     )
                     
                     var finalScale = ARScaleHelper.computeSafeScale(
                        modelExtent = maxExtent,
                        targetDiameter = targetDiameter,
                        minScale = MIN_NODE_SCALE,
                        maxScale = MAX_NODE_SCALE,
                        fallbackScale = FALLBACK_NODE_SCALE,
                        invalidExtentThreshold = INVALID_EXTENT_THRESHOLD
                    )
                    
                    val cameraDist = cameraDistanceToNode(currentAnchorNode)
                    if (cameraDist != null) {
                         val maxScaleByDistance = ARScaleHelper.maxScaleAllowedByCameraDistance(
                            cameraDistance = cameraDist,
                            modelExtent = maxExtent,
                            cameraSafetyMargin = CAMERA_MODEL_SAFETY_MARGIN_M,
                            minAllowedRadius = MIN_ALLOWED_MODEL_RADIUS_M,
                            minScale = MIN_NODE_SCALE,
                            maxScale = MAX_NODE_SCALE,
                            invalidExtentThreshold = INVALID_EXTENT_THRESHOLD
                        )
                        finalScale = minOf(finalScale, maxScaleByDistance)
                    }
                    
                    modelNode.scale = Scale(finalScale)
                    modelNode.editableScaleRange = MIN_NODE_SCALE..MAX_NODE_SCALE
                    
                    placedModelRadiusM = ARScaleHelper.estimateRenderedRadius(
                        modelExtent = maxExtent,
                        scale = finalScale,
                        fallbackDiameter = targetDiameter,
                        minAllowedRadius = MIN_ALLOWED_MODEL_RADIUS_M,
                        invalidExtentThreshold = INVALID_EXTENT_THRESHOLD
                    )

                    findViewById<View>(R.id.clear)?.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model", e)
                Toast.makeText(this@OpenCameraActivity, "Model yuklanmadi", Toast.LENGTH_SHORT).show()
                onClear()
            }
        }
    }

    private fun cameraDistanceToNode(node: Node): Float? {
        val cameraPosition = sceneView.cameraNode.worldPosition
        val nodePosition = node.worldPosition
        return ARScaleHelper.distance(
            cameraPosition.x, cameraPosition.y, cameraPosition.z,
            nodePosition.x, nodePosition.y, nodePosition.z
        )
    }

    private fun monitorInsideModel() {
        val modelNode = placedModelNode ?: return
        val cameraDist = cameraDistanceToNode(modelNode) ?: return
        
        val insideThreshold = maxOf(placedModelRadiusM - INSIDE_WARNING_BUFFER_M, MIN_ALLOWED_MODEL_RADIUS_M)
        if (cameraDist >= insideThreshold) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastInsideWarningMs < INSIDE_WARNING_COOLDOWN_MS) return
        lastInsideWarningMs = now
        Toast.makeText(this, "Siz model ichida qolib ketdingiz, orqaga yuring", Toast.LENGTH_SHORT).show()
    }

    private fun onClear() {
        anchorNode?.let {
            sceneView.removeChildNode(it)
            it.destroy()
        }
        anchorNode = null
        placedModelNode = null
        placedModelRadiusM = 0f
        findViewById<View>(R.id.clear)?.visibility = View.GONE
    }
    
    private fun setupPlaceButton() {
        val placeBtn = findViewById<FloatingActionButton>(R.id.fab_place)
        placeBtn.setOnClickListener {
             placeModelInAir()
        }
    }

    private fun setupClearButton() {
        val clear: View = findViewById(R.id.clear)
        clear.setOnClickListener {
            onClear()
        }
        clear.visibility = View.GONE
    }
    
    private fun setupScreenshotButton() {
        val cameraBtn = findViewById<FloatingActionButton>(R.id.fab_camera)
        cameraBtn.setOnClickListener {
            takeScreenshot()
        }
    }
    
    private fun takeScreenshot() {
        val view = sceneView
        val bitmap = android.graphics.Bitmap.createBitmap(
            view.width,
            view.height,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        val handlerThread = android.os.HandlerThread("PixelCopier")
        handlerThread.start()
        val threadClosed = AtomicBoolean(false)
        val closeThread = {
            if (threadClosed.compareAndSet(false, true)) {
                handlerThread.quitSafely()
            }
        }
        try {
            android.view.PixelCopy.request(view, bitmap, { copyResult ->
                try {
                    if (copyResult == android.view.PixelCopy.SUCCESS) {
                        runOnUiThread {
                            saveScreenshot(bitmap)
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this, "Screenshot olinmadi", Toast.LENGTH_SHORT).show()
                        }
                    }
                } finally {
                    closeThread()
                }
            }, android.os.Handler(handlerThread.looper))
        } catch (e: Exception) {
            closeThread()
            Log.e(TAG, "PixelCopy request failed", e)
             Toast.makeText(this, "Screenshot olinmadi", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun saveScreenshot(bitmap: android.graphics.Bitmap) {
        try {
            val filename = "AstronomyAR_${System.currentTimeMillis()}.jpg"
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AstronomyAR")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { outputStream ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, outputStream)
                }
                Toast.makeText(this, "Rasm saqlandi: Galereya/AstronomyAR", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving screenshot", e)
            Toast.makeText(this, "Saqlashda xato", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tryAutoPlaceModel() {
        if (!autoPlaceRequested || autoPlaceDone || file == null) return
        val session = sceneView.session ?: return
        if (session.getAllTrackables(Plane::class.java).isNotEmpty()) {
             autoPlaceDone = true
             placeModelInAir()
        }
    }
}
