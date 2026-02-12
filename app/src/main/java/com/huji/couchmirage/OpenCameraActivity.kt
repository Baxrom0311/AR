package com.huji.couchmirage

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.media.CamcorderProfile
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.Renderable
import com.google.ar.sceneform.ux.TransformableNode
import com.google.firebase.FirebaseApp
import com.huji.couchmirage.ar.MyArFragment
import es.dmoral.toasty.Toasty
import java.io.File
import java.util.*
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.Node
import com.huji.couchmirage.catalog.ItemDetailsActivity
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt


/**
 * Camera activity
 */
class OpenCameraActivity : AppCompatActivity() {
    //
    val TAG = OpenCameraActivity::class.simpleName
    
    // Permission request code
    private val CAMERA_PERMISSION_REQUEST_CODE = 1001

    /* Represents whenever a 3d model was found */
    private var isModelFound = false

    // ar fragment related
    private var arFragment: MyArFragment? = null

    // furniture module related (Renamed variables for astronomy context)
    private var modelRenderable: Renderable? = null
    var modelAnchor: Anchor? = null
    var modelLength: Float = 0.5f // Default 0.5m
    var modelWidth: Float = 0.5f
    var modelHeight: Float = 0.5f
    var file: File? = null
    
    // Reference cube size in meters (default 1m³)
    private val CUBE_SIZE = 1.0f
    private var cubeNode: Node? = null
    private var showCube = false // Kub ko'rinmaydi, faqat masshtab uchun

    // Conservative auto-scale tuning for mixed model sources (e.g., NASA assets)
    private val TARGET_MODEL_DIAMETER_M = 0.8f
    private val MAX_ADAPTIVE_TARGET_DIAMETER_M = 1.1f
    private val MIN_NODE_SCALE = 0.0005f
    private val MAX_NODE_SCALE = 0.8f
    private val FALLBACK_NODE_SCALE = 0.03f
    private val INVALID_EXTENT_THRESHOLD = 0.02f
    private val AIR_PLACEMENT_FORWARD_M = 1.5f
    private val AIR_PLACEMENT_DOWN_M = 0.7f
    private val CAMERA_MODEL_SAFETY_MARGIN_M = 0.25f
    private val MIN_ALLOWED_MODEL_RADIUS_M = 0.08f
    private val INSIDE_WARNING_BUFFER_M = 0.05f
    private val INSIDE_WARNING_COOLDOWN_MS = 4500L

    private var placedModelNode: TransformableNode? = null
    private var placedModelRadiusM = 0f
    private var lastInsideWarningMs = 0L
    private var autoPlaceRequested = false
    private var autoPlaceDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.open_camera)

        // setup
        setupToast()
        setupFireBase()
        
        // Check camera permission before setting up AR
        if (checkCameraPermission()) {
            initializeAR()
        } else {
            requestCameraPermission()
        }
        
        setupClearButton() 
        setupPlaceButton()
        setupScreenshotButton()
        // setupHelpButton() // Help page removed
        
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
                Toast.makeText(this, "Camera permission is required for AR", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
    
    private fun initializeAR() {
        try {
            setARFragment()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AR fragment", e)
            Toast.makeText(this, "AR initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    // ... (Keep lifecycle methods) ...

    private fun checkIntentForModel() {
        val resolvedFile = resolveModelFileFromIntent()
        if (resolvedFile != null) {
            file = resolvedFile

            // Assuming input is in METERS now (default 0.5f)
            modelLength = intent.getFloatExtra("model_length", 0.5f)

            isModelFound = true
            buildModel(resolvedFile)
        }
    }

    private fun resolveModelFileFromIntent(): File? {
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

    private fun buildModel(file: File) {
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
            .setRegistryId(file.path)
            .build()
            .thenAccept { renderable ->
                modelRenderable = renderable
                Toast.makeText(this, "Model loaded", Toast.LENGTH_SHORT).show()
                
                if (intent.getBooleanExtra("auto_place", false)) {
                    autoPlaceRequested = true
                    autoPlaceDone = false
                    tryAutoPlaceModel()
                }
            }
            .exceptionally { throwable ->
                Log.e(TAG, "Unable to load renderable", throwable)
                runOnUiThread {
                    Toast.makeText(this, "Model load error: ${throwable.message}", Toast.LENGTH_LONG).show()
                }
                null
            }
    }

    private fun setARFragmentAction() {
        // Updated logic: Support Air Placement
        arFragment?.setOnTapArPlaneListener { hitResult, _, _ ->
             // Plane Tap
             placeModel(hitResult.createAnchor())
        }
    }
    
    // New function to support placing in air (if creating anchor from camera pose)
    private fun placeModelInAir() {
         val fragment = arFragment ?: return
         val frame = fragment.arSceneView.arFrame ?: return
         
         if (frame.camera.trackingState != TrackingState.TRACKING) {
             Toast.makeText(this, "Kamerani harakatga keltiring", Toast.LENGTH_SHORT).show()
             return
         }
         
         // Get screen center coordinates
         val screenWidth = fragment.arSceneView.width.toFloat()
         val screenHeight = fragment.arSceneView.height.toFloat()
         val centerX = screenWidth / 2f
         val centerY = screenHeight / 2f
         
         // Try to hit test at screen center (where camera is looking)
         val hitResults = frame.hitTest(centerX, centerY)
         
         if (hitResults.isNotEmpty()) {
             // Found a surface - place model there
             val hit = hitResults[0]
             val anchor = hit.createAnchor()
             placeModel(anchor)
             Toast.makeText(this, "Model joylashtirildi!", Toast.LENGTH_SHORT).show()
         } else {
             // No surface found - place in air at camera focus direction
             val cameraPose = frame.camera.pose
             val cameraPosition = cameraPose.translation
             
             // Get camera forward direction (negative Z axis)
             val forwardVector = cameraPose.zAxis
             
             // Also get camera's down direction for 45 degree angle
             val upVector = cameraPose.yAxis
             
             // Place 1.5m in front, angled 45 degrees down
             // Forward component
             val forwardDist = AIR_PLACEMENT_FORWARD_M
             // Down component (for 45 degrees, use same as forward)
             val downDist = AIR_PLACEMENT_DOWN_M
             
             val placementX = cameraPosition[0] - forwardVector[0] * forwardDist - upVector[0] * downDist
             val placementY = cameraPosition[1] - forwardVector[1] * forwardDist - upVector[1] * downDist
             val placementZ = cameraPosition[2] - forwardVector[2] * forwardDist - upVector[2] * downDist
             
             val placementPose = Pose.makeTranslation(placementX, placementY, placementZ)
             val anchor = fragment.arSceneView.session?.createAnchor(placementPose)
             
             if (anchor != null) {
                 placeModel(anchor)
                 Toast.makeText(this, "Model fazoga joylashtirildi!", Toast.LENGTH_SHORT).show()
             }
         }
    }

    private fun placeModel(anchor: Anchor) {
        val fragment = arFragment ?: return
        val renderable = modelRenderable ?: return

        val anchorNode = AnchorNode(anchor)
        
        // Create reference cube first (1m³ wireframe) - only if enabled
        if (showCube) {
            createReferenceCube(anchorNode)
        }
        
        // Create model node
        val modelNode = TransformableNode(fragment.transformationSystem)
        modelNode.scaleController.isEnabled = true
        modelNode.translationController.isEnabled = true
        modelNode.rotationController.isEnabled = true
        
        // First set parent and renderable
        modelNode.setParent(anchorNode)
        modelNode.renderable = renderable

        val maxExtent = extractModelExtent(renderable)
        val targetDiameter = adaptiveTargetModelDiameter()
        val initialScale = computeSafeAutoScale(maxExtent, targetDiameter)
        val finalScale = clampScaleByCameraDistance(anchor, maxExtent, initialScale)
        val wasClampedByDistance = finalScale + 0.0001f < initialScale

        modelNode.localScale = Vector3(finalScale, finalScale, finalScale)
        Log.d(TAG, "Smart Scaling: MaxExtent=$maxExtent, TargetDiameter=$targetDiameter, AppliedScale=$finalScale")

        // Keep manual scaling bounded so users don't end up inside an oversized model.
        val maxScaleByDistance = maxScaleAllowedByCameraDistance(anchor, maxExtent)
        modelNode.scaleController.minScale = maxOf(MIN_NODE_SCALE, finalScale * 0.2f)
        modelNode.scaleController.maxScale = minOf(maxOf(finalScale * 25.0f, 1.5f), maxScaleByDistance)

        placedModelNode = modelNode
        placedModelRadiusM = estimateRenderedRadius(maxExtent, finalScale, targetDiameter)

        if (wasClampedByDistance) {
            Toast.makeText(this, "Model juda katta edi, avtomatik kichraytirildi", Toast.LENGTH_SHORT).show()
        }
        
        fragment.arSceneView.scene.addChild(anchorNode)
        
        // Show Clear button
        findViewById<View>(R.id.clear)?.visibility = View.VISIBLE
    }

    private fun extractModelExtent(renderable: Renderable): Float {
        val shape = renderable.collisionShape ?: return 0f
        return when (shape) {
            is com.google.ar.sceneform.collision.Box -> {
                val extents = shape.extents
                maxOf(extents.x, extents.y, extents.z)
            }
            is com.google.ar.sceneform.collision.Sphere -> shape.radius * 2.0f
            else -> 0f
        }
    }

    private fun computeSafeAutoScale(maxExtent: Float, targetDiameter: Float): Float {
        if (!maxExtent.isFinite() || maxExtent <= INVALID_EXTENT_THRESHOLD) {
            return FALLBACK_NODE_SCALE
        }
        return (targetDiameter / maxExtent).coerceIn(MIN_NODE_SCALE, MAX_NODE_SCALE)
    }

    private fun adaptiveTargetModelDiameter(): Float {
        val factor = screenScaleFactor()
        return (TARGET_MODEL_DIAMETER_M * factor).coerceIn(
            TARGET_MODEL_DIAMETER_M,
            MAX_ADAPTIVE_TARGET_DIAMETER_M
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

    private fun clampScaleByCameraDistance(anchor: Anchor, modelExtent: Float, rawScale: Float): Float {
        if (modelExtent <= INVALID_EXTENT_THRESHOLD || !modelExtent.isFinite()) {
            return rawScale
        }
        val maxScale = maxScaleAllowedByCameraDistance(anchor, modelExtent)
        return rawScale.coerceAtMost(maxScale).coerceAtLeast(MIN_NODE_SCALE)
    }

    private fun maxScaleAllowedByCameraDistance(anchor: Anchor, modelExtent: Float): Float {
        if (modelExtent <= INVALID_EXTENT_THRESHOLD || !modelExtent.isFinite()) {
            return MAX_NODE_SCALE
        }
        val cameraDistance = cameraDistanceToAnchor(anchor) ?: return MAX_NODE_SCALE
        val maxRadius = maxOf(cameraDistance - CAMERA_MODEL_SAFETY_MARGIN_M, MIN_ALLOWED_MODEL_RADIUS_M)
        val maxDiameter = maxRadius * 2f
        val scaleByDistance = maxDiameter / modelExtent
        return scaleByDistance.coerceIn(MIN_NODE_SCALE, MAX_NODE_SCALE)
    }

    private fun estimateRenderedRadius(modelExtent: Float, scale: Float, fallbackDiameter: Float): Float {
        val diameter = if (modelExtent > INVALID_EXTENT_THRESHOLD && modelExtent.isFinite()) {
            modelExtent * scale
        } else {
            fallbackDiameter
        }
        return maxOf(diameter / 2f, MIN_ALLOWED_MODEL_RADIUS_M)
    }

    private fun cameraDistanceToAnchor(anchor: Anchor): Float? {
        val frame = arFragment?.arSceneView?.arFrame ?: return null
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return null
        val cameraPosition = camera.pose.translation
        val anchorPosition = anchor.pose.translation
        return distance(cameraPosition[0], cameraPosition[1], cameraPosition[2], anchorPosition[0], anchorPosition[1], anchorPosition[2])
    }

    private fun monitorInsideModel() {
        val fragment = arFragment ?: return
        val modelNode = placedModelNode ?: return
        val cameraPosition = fragment.arSceneView.scene.camera.worldPosition
        val modelPosition = modelNode.worldPosition
        val cameraDistance = distance(cameraPosition.x, cameraPosition.y, cameraPosition.z, modelPosition.x, modelPosition.y, modelPosition.z)
        val insideThreshold = maxOf(placedModelRadiusM - INSIDE_WARNING_BUFFER_M, MIN_ALLOWED_MODEL_RADIUS_M)
        if (cameraDistance >= insideThreshold) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastInsideWarningMs < INSIDE_WARNING_COOLDOWN_MS) return
        lastInsideWarningMs = now
        Toast.makeText(this, "Siz model ichida qolib ketdingiz, orqaga yuring", Toast.LENGTH_SHORT).show()
    }

    private fun distance(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        val dz = z1 - z2
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
    
    // Create a semi-transparent reference cube (1m³)
    private fun createReferenceCube(anchorNode: AnchorNode) {
        MaterialFactory.makeTransparentWithColor(this, Color(0.2f, 0.6f, 1.0f, 0.15f))
            .thenAccept { material ->
                // Create cube shape
                val cubeRenderable = ShapeFactory.makeCube(
                    Vector3(CUBE_SIZE, CUBE_SIZE, CUBE_SIZE),
                    Vector3.zero(),
                    material
                )
                
                runOnUiThread {
                    cubeNode = Node()
                    cubeNode?.renderable = cubeRenderable
                    cubeNode?.setParent(anchorNode)
                    
                    // Position cube so model sits in the center
                    cubeNode?.localPosition = Vector3(0f, CUBE_SIZE / 2f, 0f)
                }
            }
    }

    private fun onClear() {
        val fragment = arFragment ?: return
        val children: List<com.google.ar.sceneform.Node> = ArrayList(fragment.arSceneView.scene.children)
        for (node in children) {
            if (node is AnchorNode) {
                node.anchor?.detach()
            }
        }
        placedModelNode = null
        placedModelRadiusM = 0f
    }
    
    // Keep setup methods for UI elements 
    private fun setupToast() { Toasty.Config.getInstance().allowQueue(false).apply() }
    private fun setupFireBase() { FirebaseApp.initializeApp(this) }
    
    private fun setARFragment() {
        arFragment = supportFragmentManager.findFragmentById(R.id.fragment) as? MyArFragment
        // Re-enable Tap listener
        setARFragmentAction()
        arFragment?.arSceneView?.scene?.addOnUpdateListener {
            tryAutoPlaceModel()
            monitorInsideModel()
        }
        
        // IMPORTANT: For Space/Air placement, we might want to disable Plane discovery UI 
        // or keep it if we prefer users to find a floor.
        // MyArFragment.kt hides it by default in the provided code (line 35).
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
            clear.visibility = View.GONE
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
        val fragment = arFragment ?: return
        val arSceneView = fragment.arSceneView
        
        // Capture AR view as bitmap
        val bitmap = android.graphics.Bitmap.createBitmap(
            arSceneView.width,
            arSceneView.height,
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
            android.view.PixelCopy.request(arSceneView, bitmap, { copyResult ->
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
    
    private fun setupHelpButton() {
         // Help functionality removed - HelpActivity deleted
         val help = findViewById<View>(R.id.help_btn)
         help?.visibility = View.GONE
    }
    
    // ... Helper functions
    @Suppress("UNUSED_PARAMETER")
    private fun checkIsSupportedDeviceOrFinish(activity: android.app.Activity): Boolean {
        // ... (Assume standard ARCore check)
        return true
    }

    private fun tryAutoPlaceModel() {
        if (!autoPlaceRequested || autoPlaceDone || modelRenderable == null) return
        val frame = arFragment?.arSceneView?.arFrame ?: return
        if (frame.camera.trackingState != TrackingState.TRACKING) return
        autoPlaceDone = true
        placeModelInAir()
    }
}
