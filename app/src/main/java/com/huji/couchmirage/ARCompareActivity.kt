package com.huji.couchmirage

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.ar.core.Anchor
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.assets.RenderableSource
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.TransformableNode
import com.google.firebase.storage.FirebaseStorage
import com.huji.couchmirage.catalog.CelestialBody
import com.huji.couchmirage.catalog.FirebaseRepository
import java.io.File
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import com.huji.couchmirage.utils.ModelCacheHelper
import kotlin.math.sqrt

class ARCompareActivity : AppCompatActivity() {

    private val TAG = "ARCompareActivity"
    private val repository = FirebaseRepository.instance
    
    private var arFragment: ArFragment? = null
    private val placedModels = mutableMapOf<Int, TransformableNode>()
    private val placedAnchorNodes = mutableSetOf<AnchorNode>()
    private val renderedModelRadii = mutableMapOf<Int, Float>()
    private val selectedBodies = mutableListOf<CelestialBody>()
    private var currentScale = 0.67f // Default scale for progress 30
    private var activePlacementId = 0
    private var lastInsideWarningMs = 0L
    private var distanceClampToastShown = false
    
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var selectedPlanetsContainer: LinearLayout
    private lateinit var btnClear: ImageButton
    private lateinit var scaleSeekbar: SeekBar
    private lateinit var scaleValueText: TextView
    
    // Reference cube (1m³)
    private val CUBE_SIZE = 1.0f
    private var showReferenceCube = false // Kub ko'rinmaydi, faqat masshtab uchun
    private var cubeNode: Node? = null

    // Conservative model fitting for mixed datasets (including large NASA assets)
    private val BASE_VISUAL_DIAMETER_M = 0.7f
    private val MODEL_GAP_M = 0.15f
    private val MAX_ADAPTIVE_VISUAL_DIAMETER_M = 1.0f
    private val MAX_ADAPTIVE_MODEL_GAP_M = 0.25f
    private val MIN_NODE_SCALE = 0.0005f
    private val MAX_NODE_SCALE = 0.8f
    private val FALLBACK_NODE_SCALE = 0.03f
    private val INVALID_EXTENT_THRESHOLD = 0.02f
    private val AIR_PLACEMENT_FORWARD_M = 1.5f
    private val CAMERA_MODEL_SAFETY_MARGIN_M = 0.28f
    private val MIN_ALLOWED_MODEL_RADIUS_M = 0.08f
    private val INSIDE_WARNING_BUFFER_M = 0.06f
    private val INSIDE_WARNING_COOLDOWN_MS = 4500L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar_compare)
        
        initViews()
        setupAR()
        setupListeners()
        
        // NEW: Check for passed items from ARSelectionActivity
        checkIntentData()
    }
    
    private fun checkIntentData() {
        val ids = intent.getStringArrayListExtra("item_ids")
        if (ids != null && ids.isNotEmpty()) {
            loadSelectedBodies(ids)
        }
    }
    
    private fun loadSelectedBodies(ids: ArrayList<String>) {
        loadingOverlay.visibility = View.VISIBLE

        val collected = Collections.synchronizedMap(mutableMapOf<String, CelestialBody>())
        val pending = AtomicInteger(ids.size)

        for (id in ids) {
            repository.getCelestialBodyById(id,
                onSuccess = { body ->
                    collected[id] = body
                    if (pending.decrementAndGet() == 0) {
                        val ordered = ids.mapNotNull { collected[it] }
                        runOnUiThread {
                            selectedBodies.clear()
                            selectedBodies.addAll(ordered)
                            loadingOverlay.visibility = View.GONE
                            updateSelectedChips()

                            if (selectedBodies.isNotEmpty()) {
                                Toast.makeText(this, "Joylarni aniqlash uchun telefonni harakatlantiring va ekranga bosing", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                onError = {
                    if (pending.decrementAndGet() == 0) {
                        val ordered = ids.mapNotNull { collected[it] }
                        runOnUiThread {
                            selectedBodies.clear()
                            selectedBodies.addAll(ordered)
                            loadingOverlay.visibility = View.GONE
                            updateSelectedChips()
                        }
                    }
                }
            )
        }
    }

    private fun initViews() {
        loadingOverlay = findViewById(R.id.loading_overlay)
        selectedPlanetsContainer = findViewById(R.id.selected_planets_container)
        btnClear = findViewById(R.id.btn_clear)
        scaleSeekbar = findViewById(R.id.scale_seekbar)
        scaleValueText = findViewById(R.id.scale_value)
    }

    private fun setupAR() {
        arFragment = supportFragmentManager.findFragmentById(R.id.ar_fragment) as? ArFragment
        
        // Tap on plane - place models
        arFragment?.setOnTapArPlaneListener { hitResult, _, _ ->
            if (selectedBodies.isNotEmpty()) {
                val anchor = hitResult.createAnchor()
                placeAllModels(anchor)
            } else {
                Toast.makeText(this, "Avval sayyora tanlang", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Also allow placing in air with a button
        arFragment?.arSceneView?.scene?.addOnUpdateListener { _ ->
            monitorInsideAnyModel()
        }
    }
    
    // Place in air (no plane required) - 1 meter in front of camera
    private fun placeInAir() {
        val fragment = arFragment ?: return
        val frame = fragment.arSceneView.arFrame ?: return
        
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            Toast.makeText(this, "Kamerani harakatga keltiring", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (selectedBodies.isEmpty()) {
            Toast.makeText(this, "Avval sayyora tanlang", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Create anchor in front of camera (in the air)
        val cameraPose = frame.camera.pose
        val placementPose = cameraPose.compose(Pose.makeTranslation(0f, 0f, -AIR_PLACEMENT_FORWARD_M))
        
        val anchor = fragment.arSceneView.session?.createAnchor(placementPose)
        if (anchor != null) {
            placeAllModels(anchor)
            Toast.makeText(this, "Sayyoralar havoda joylashtirildi!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupListeners() {
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
            finish()
        }
        
        btnClear.setOnClickListener {
            clearAllModels()
        }
        
        findViewById<Button>(R.id.btn_add_planet).setOnClickListener {
            showPlanetSelector()
        }
        
        // Air placement button for planets in space
        findViewById<Button>(R.id.btn_place_in_air)?.setOnClickListener {
            placeInAir()
        }
        
        scaleSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Modified range: 0.01x to 2.0x to handle very large models
                // Default (progress=30) will be around 0.6x
                val minScale = 0.01f
                val maxScale = 2.0f
                currentScale = minScale + (progress / 100f * (maxScale - minScale))
                scaleValueText.text = String.format("%.2fx", currentScale)
                updateLayout()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun showPlanetSelector() {
        loadingOverlay.visibility = View.VISIBLE
        
        // Load all celestial bodies using the new unified method
        repository.getAllCelestialBodies(
            onSuccess = { allBodies ->
                runOnUiThread {
                    loadingOverlay.visibility = View.GONE
                    showSelectorDialog(allBodies)
                }
            },
            onError = {
                runOnUiThread {
                    loadingOverlay.visibility = View.GONE
                    Toast.makeText(this, "Ma'lumotlarni yuklashda xatolik", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun showSelectorDialog(bodies: List<CelestialBody>) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_planet_selector, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.planets_recycler)
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        val selectedIds = selectedBodies.map { it.id }.toMutableSet()
        
        val adapter = PlanetSelectorAdapter(bodies, selectedIds) { body, isSelected ->
            if (isSelected) {
                selectedIds.add(body.id)
            } else {
                selectedIds.remove(body.id)
            }
        }
        recyclerView.adapter = adapter
        
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Sayyoralarni tanlang")
            .setView(dialogView)
            .setPositiveButton("Qo'shish") { _, _ ->
                selectedBodies.clear()
                selectedBodies.addAll(bodies.filter { selectedIds.contains(it.id) })
                updateSelectedChips()
            }
            .setNegativeButton("Bekor", null)
            .show()
    }

    private fun updateSelectedChips() {
        selectedPlanetsContainer.removeAllViews()
        
        for (body in selectedBodies) {
            val chipView = LayoutInflater.from(this).inflate(R.layout.item_planet_chip, selectedPlanetsContainer, false)
            
            chipView.findViewById<TextView>(R.id.chip_name).text = body.name
            
            // Load image
            if (body.images.isNotEmpty()) {
                val imageView = chipView.findViewById<ImageView>(R.id.chip_image)
                Glide.with(this).load(body.images[0]).into(imageView)
            }
            
            chipView.findViewById<ImageButton>(R.id.chip_remove).setOnClickListener {
                selectedBodies.remove(body)
                updateSelectedChips()
            }
            
            selectedPlanetsContainer.addView(chipView)
        }
    }

    private fun placeAllModels(anchor: Anchor) {
        if (selectedBodies.isEmpty()) return

        // Invalidate any previous async placement flow and clear old scene nodes first.
        activePlacementId += 1
        val placementId = activePlacementId
        distanceClampToastShown = false
        clearAllModels(invalidatePlacement = false)
        
        loadingOverlay.visibility = View.VISIBLE
        
        // Use normalized radii to keep placement stable when DB values are missing/invalid.
        val normalizedRadii = selectedBodies.map { normalizedRadius(it) }
        val maxRadius = normalizedRadii.maxOrNull() ?: 1.0
        
        var loadedCount = 0
        val totalCount = selectedBodies.size
        
        for ((index, body) in selectedBodies.withIndex()) {
            val bodyRadius = normalizedRadii.getOrElse(index) { 1.0 }
            loadAndPlaceModel(anchor, body, index, bodyRadius, maxRadius, placementId) {
                loadedCount++
                if (loadedCount == totalCount) {
                    runOnUiThread {
                        if (placementId == activePlacementId) {
                            loadingOverlay.visibility = View.GONE
                            btnClear.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    private fun loadAndPlaceModel(
        anchor: Anchor,
        body: CelestialBody,
        index: Int,
        bodyRadius: Double,
        maxRadius: Double,
        placementId: Int,
        onComplete: () -> Unit
    ) {
        if (placementId != activePlacementId) {
            onComplete()
            return
        }

        if (body.modelUrl.isEmpty()) {
            onComplete()
            return
        }

        val modelCacheDir = File(cacheDir, "models").apply { mkdirs() }
        val localFile = File(modelCacheDir, ModelCacheHelper.buildCacheFileName(body.modelUrl))

        if (localFile.exists()) {
            buildAndPlaceModel(localFile, anchor, index, bodyRadius, maxRadius, placementId, onComplete)
        } else {
            val storage = FirebaseStorage.getInstance()
            val ref = try {
                when {
                    body.modelUrl.startsWith("gs://") ||
                        body.modelUrl.startsWith("https://firebasestorage.googleapis.com/") ->
                        storage.getReferenceFromUrl(body.modelUrl)
                    body.modelUrl.startsWith("http") ->
                        throw IllegalArgumentException("Only Firebase Storage URLs are supported")
                    else ->
                        storage.reference.child(body.modelUrl.trimStart('/'))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Invalid model URL for ${body.name}: ${body.modelUrl}", e)
                runOnUiThread {
                    Toast.makeText(this, "Noto'g'ri model URL: ${body.name}", Toast.LENGTH_SHORT).show()
                }
                onComplete()
                return
            }

            ref.getFile(localFile)
                .addOnSuccessListener {
                    buildAndPlaceModel(localFile, anchor, index, bodyRadius, maxRadius, placementId, onComplete)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to download model", e)
                    runOnUiThread {
                        if (placementId == activePlacementId) {
                            Toast.makeText(this, "Model yuklanmadi: ${body.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    onComplete()
                }
        }
    }

    private fun buildAndPlaceModel(
        file: File,
        anchor: Anchor,
        index: Int,
        bodyRadius: Double,
        maxRadius: Double,
        placementId: Int,
        onComplete: () -> Unit
    ) {
        val sourceType = if (file.extension.equals("gltf", ignoreCase = true)) {
            RenderableSource.SourceType.GLTF2
        } else {
            RenderableSource.SourceType.GLB
        }
        
        val renderableSource = RenderableSource.builder()
            .setSource(this, Uri.fromFile(file), sourceType)
            .setRecenterMode(RenderableSource.RecenterMode.ROOT)
            .build()
        
        ModelRenderable.builder()
            .setSource(this, renderableSource)
            .build()
            .thenAccept { renderable ->
                runOnUiThread {
                    if (placementId != activePlacementId) {
                        onComplete()
                        return@runOnUiThread
                    }
                    placeModelInScene(renderable, anchor, index, bodyRadius, maxRadius, placementId)
                    onComplete()
                }
            }
            .exceptionally { e ->
                Log.e(TAG, "Failed to build model", e)
                runOnUiThread { onComplete() }
                null
            }
    }

    private fun placeModelInScene(
        renderable: ModelRenderable,
        anchor: Anchor,
        index: Int,
        bodyRadius: Double,
        maxRadius: Double,
        placementId: Int
    ) {
        if (placementId != activePlacementId) return
        val fragment = arFragment ?: return
        
        val anchorNode = AnchorNode(anchor)
        
        // Create reference cube on first model placement
        if (index == 0 && showReferenceCube && cubeNode == null) {
            createReferenceCube(anchorNode)
        }
        
        val node = TransformableNode(fragment.transformationSystem)
        
        node.renderable = renderable

        node.scaleController.isEnabled = true
        node.translationController.isEnabled = true
        node.rotationController.isEnabled = true

        val modelExtent = extractModelExtent(renderable)
        val baseDiameter = adaptiveBaseVisualDiameter()
        val radiusRatio = safeRadiusRatio(bodyRadius, maxRadius)
        val targetDiameter = baseDiameter * radiusRatio * currentScale
        val initialScale = computeSafeNodeScale(targetDiameter, modelExtent)
        val finalScale = clampScaleByCameraDistance(anchor, modelExtent, initialScale)

        node.localScale = Vector3(finalScale, finalScale, finalScale)
        val maxScaleByDistance = maxScaleAllowedByCameraDistance(anchor, modelExtent)
        node.scaleController.minScale = maxOf(MIN_NODE_SCALE, finalScale * 0.2f)
        node.scaleController.maxScale = minOf(maxOf(finalScale * 15.0f, 1.5f), maxScaleByDistance)
        
        // Offset position for multiple models - spread them out
        // Store in map using index
        placedModels[index] = node
        node.setParent(anchorNode)
        fragment.arSceneView.scene.addChild(anchorNode)
        placedAnchorNodes.add(anchorNode)

        if (finalScale + 0.0001f < initialScale && !distanceClampToastShown) {
            distanceClampToastShown = true
            Toast.makeText(this, "Model juda katta edi, avtomatik kichraytirildi", Toast.LENGTH_SHORT).show()
        }

        // Update scales and positions for ALL models (dynamic layout)
        updateLayout()
        
        // placedModels.add(node) - REMOVED (using Map now)
    }
    
    // Create semi-transparent reference cube (1m³)
    private fun createReferenceCube(anchorNode: AnchorNode) {
        MaterialFactory.makeTransparentWithColor(this, Color(0.3f, 0.7f, 1.0f, 0.12f))
            .thenAccept { material ->
                val cubeRenderable = ShapeFactory.makeCube(
                    Vector3(CUBE_SIZE, CUBE_SIZE, CUBE_SIZE),
                    Vector3.zero(),
                    material
                )
                
                runOnUiThread {
                    cubeNode = Node()
                    cubeNode?.renderable = cubeRenderable
                    cubeNode?.setParent(anchorNode)
                    cubeNode?.localPosition = Vector3(0f, CUBE_SIZE / 2f, 0f)
                }
            }
    }

    private fun extractModelExtent(renderable: ModelRenderable): Float {
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

    private fun computeSafeNodeScale(targetDiameter: Float, modelExtent: Float): Float {
        if (!modelExtent.isFinite() || modelExtent <= INVALID_EXTENT_THRESHOLD) {
            return FALLBACK_NODE_SCALE
        }
        return (targetDiameter / modelExtent).coerceIn(MIN_NODE_SCALE, MAX_NODE_SCALE)
    }

    private fun clampScaleByCameraDistance(anchor: Anchor, modelExtent: Float, rawScale: Float): Float {
        if (modelExtent <= INVALID_EXTENT_THRESHOLD || !modelExtent.isFinite()) return rawScale
        val maxScale = maxScaleAllowedByCameraDistance(anchor, modelExtent)
        return rawScale.coerceAtMost(maxScale).coerceAtLeast(MIN_NODE_SCALE)
    }

    private fun maxScaleAllowedByCameraDistance(anchor: Anchor, modelExtent: Float): Float {
        if (modelExtent <= INVALID_EXTENT_THRESHOLD || !modelExtent.isFinite()) return MAX_NODE_SCALE
        val distance = cameraDistanceToAnchor(anchor) ?: return MAX_NODE_SCALE
        val maxRadius = maxOf(distance - CAMERA_MODEL_SAFETY_MARGIN_M, MIN_ALLOWED_MODEL_RADIUS_M)
        val maxDiameter = maxRadius * 2f
        return (maxDiameter / modelExtent).coerceIn(MIN_NODE_SCALE, MAX_NODE_SCALE)
    }

    private fun cameraDistanceToAnchor(anchor: Anchor): Float? {
        val frame = arFragment?.arSceneView?.arFrame ?: return null
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return null
        val cameraPosition = camera.pose.translation
        val anchorPosition = anchor.pose.translation
        return distance(
            cameraPosition[0], cameraPosition[1], cameraPosition[2],
            anchorPosition[0], anchorPosition[1], anchorPosition[2]
        )
    }


    
    // Helper to get estimated radius if DB fails
    private fun estimateRadius(name: String): Double {
        return when (name.lowercase().trim()) {
            "sun", "quyosh" -> 696340.0
            "mercury", "merkuriy" -> 2439.0
            "venus", "venera" -> 6051.0
            "earth", "yer" -> 6371.0
            "moon", "oy" -> 1737.0
            "mars" -> 3389.0
            "jupiter", "yupiter" -> 69911.0
            "saturn" -> 58232.0
            "uranus", "uran" -> 25362.0
            "neptune", "neptun" -> 24622.0
            "pluto", "pluton" -> 1188.0
            else -> 1000.0
        }
    }

    private fun normalizedRadius(body: CelestialBody): Double {
        val radius = body.radius
        return if (radius.isFinite() && radius > 1.0) radius else estimateRadius(body.name)
    }

    private fun safeRadiusRatio(radius: Double, maxRadius: Double): Float {
        if (!radius.isFinite() || !maxRadius.isFinite() || maxRadius <= 0.0) {
            return 1.0f
        }
        return (radius / maxRadius).toFloat().let { ratio ->
            if (ratio.isFinite()) ratio.coerceIn(0f, 1f) else 1.0f
        }
    }

    private fun adaptiveBaseVisualDiameter(): Float {
        val factor = screenScaleFactor()
        return (BASE_VISUAL_DIAMETER_M * factor).coerceIn(
            BASE_VISUAL_DIAMETER_M,
            MAX_ADAPTIVE_VISUAL_DIAMETER_M
        )
    }

    private fun adaptiveModelGap(): Float {
        val factor = screenScaleFactor()
        return (MODEL_GAP_M * factor).coerceIn(MODEL_GAP_M, MAX_ADAPTIVE_MODEL_GAP_M)
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
    
    private fun updateLayout() {
        val trueRadii = selectedBodies.map { normalizedRadius(it) }
        val maxRadius = trueRadii.maxOrNull() ?: 1.0
        val baseDiameter = adaptiveBaseVisualDiameter()
        val modelGap = adaptiveModelGap()

        var cursor = 0f

        for (i in selectedBodies.indices) {
            val node = placedModels[i] ?: continue
            val renderable = node.renderable as? ModelRenderable ?: continue
            val trueRadius = trueRadii[i]
            val radiusRatio = safeRadiusRatio(trueRadius, maxRadius)
            val targetDiameter = baseDiameter * radiusRatio * currentScale
            val modelExtent = extractModelExtent(renderable)
            var finalScale = computeSafeNodeScale(targetDiameter, modelExtent)
            node.localScale = Vector3(finalScale, finalScale, finalScale)

            var renderedDiameter = if (modelExtent > INVALID_EXTENT_THRESHOLD) {
                modelExtent * finalScale
            } else {
                targetDiameter
            }
            var renderedRadius = maxOf(renderedDiameter / 2f, MIN_ALLOWED_MODEL_RADIUS_M)

            if (i > 0) {
                cursor += modelGap
            }
            cursor += renderedRadius
            node.localPosition = Vector3(cursor, CUBE_SIZE / 2f, 0f)
            cursor += renderedRadius

            val cameraDistance = cameraDistanceToNode(node)
            if (cameraDistance != null) {
                val maxAllowedRadius = maxOf(cameraDistance - CAMERA_MODEL_SAFETY_MARGIN_M, MIN_ALLOWED_MODEL_RADIUS_M)
                if (renderedRadius > maxAllowedRadius && renderedRadius > 0f) {
                    val ratio = maxAllowedRadius / renderedRadius
                    finalScale = maxOf(finalScale * ratio, MIN_NODE_SCALE)
                    node.localScale = Vector3(finalScale, finalScale, finalScale)
                    renderedDiameter = if (modelExtent > INVALID_EXTENT_THRESHOLD) {
                        modelExtent * finalScale
                    } else {
                        targetDiameter * ratio
                    }
                    renderedRadius = maxOf(renderedDiameter / 2f, MIN_ALLOWED_MODEL_RADIUS_M)
                }
            }

            renderedModelRadii[i] = renderedRadius
        }
    }

    private fun cameraDistanceToNode(node: TransformableNode): Float? {
        val scene = arFragment?.arSceneView?.scene ?: return null
        val cameraPosition = scene.camera.worldPosition
        val nodePosition = node.worldPosition
        return distance(
            cameraPosition.x, cameraPosition.y, cameraPosition.z,
            nodePosition.x, nodePosition.y, nodePosition.z
        )
    }

    private fun monitorInsideAnyModel() {
        if (placedModels.isEmpty()) return

        for ((index, node) in placedModels) {
            val radius = renderedModelRadii[index] ?: continue
            val cameraDistance = cameraDistanceToNode(node) ?: continue
            val insideThreshold = maxOf(radius - INSIDE_WARNING_BUFFER_M, MIN_ALLOWED_MODEL_RADIUS_M)
            if (cameraDistance < insideThreshold) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastInsideWarningMs >= INSIDE_WARNING_COOLDOWN_MS) {
                    lastInsideWarningMs = now
                    Toast.makeText(this, "Siz model ichida qolib ketdingiz, orqaga yuring", Toast.LENGTH_SHORT).show()
                }
                return
            }
        }
    }

    private fun distance(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        val dz = z1 - z2
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun clearAllModels(invalidatePlacement: Boolean = true) {
        if (invalidatePlacement) {
            activePlacementId += 1
        }

        for (anchorNode in placedAnchorNodes.toList()) {
            anchorNode.anchor?.detach()
            arFragment?.arSceneView?.scene?.removeChild(anchorNode)
        }
        placedModels.clear()
        renderedModelRadii.clear()
        placedAnchorNodes.clear()
        cubeNode = null // Reset cube reference
        btnClear.visibility = View.GONE
    }

    // Inner adapter class
    inner class PlanetSelectorAdapter(
        private val bodies: List<CelestialBody>,
        private val selectedIds: MutableSet<String>,
        private val onSelectionChanged: (CelestialBody, Boolean) -> Unit
    ) : RecyclerView.Adapter<PlanetSelectorAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.planet_image)
            val name: TextView = view.findViewById(R.id.planet_name)
            val type: TextView = view.findViewById(R.id.planet_type)
            val checkbox: CheckBox = view.findViewById(R.id.planet_checkbox)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_planet_selector, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val body = bodies[position]
            
            holder.name.text = body.name
            holder.type.text = body.category.replaceFirstChar { it.uppercase() }

            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = selectedIds.contains(body.id)
            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                onSelectionChanged(body, isChecked)
            }
            
            if (body.images.isNotEmpty()) {
                Glide.with(holder.itemView).load(body.images[0]).into(holder.image)
            }
            
            holder.itemView.setOnClickListener {
                holder.checkbox.toggle()
            }
        }

        override fun getItemCount() = bodies.size
    }
}
