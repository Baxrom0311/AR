package com.huji.couchmirage

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.firebase.storage.FirebaseStorage
import com.huji.couchmirage.catalog.CelestialBody
import com.huji.couchmirage.catalog.FirebaseRepository
import com.huji.couchmirage.utils.ARScaleHelper
import com.huji.couchmirage.utils.ModelCacheHelper
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ARCompareActivity : AppCompatActivity() {

    private val TAG = "ARCompareActivity"
    @Inject
    lateinit var repository: FirebaseRepository
    
    private lateinit var sceneView: ARSceneView
    private var anchorNode: AnchorNode? = null
    
    private val placedModels = mutableMapOf<Int, ModelNode>()
    private val renderedModelRadii = mutableMapOf<Int, Float>()
    private val selectedBodies = mutableListOf<CelestialBody>()
    private var currentScale = 0.67f 
    private var activePlacementId = 0
    private var lastInsideWarningMs = 0L
    private var distanceClampToastShown = false
    
    private val selectedModelIndex = androidx.compose.runtime.mutableStateOf(-1)
    private var isNodeTap = false
    
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var selectedPlanetsContainer: LinearLayout
    private lateinit var btnClear: ImageButton
    private lateinit var scaleSeekbar: SeekBar
    private lateinit var scaleValueText: TextView
    
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
        
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.planet_info_card_view).apply {
            setContent {
                val index = selectedModelIndex.value
                val body = if (index != -1 && index < selectedBodies.size) selectedBodies[index] else null
                
                com.huji.couchmirage.ui.components.PlanetInfoCard(
                    celestialBody = body,
                    isVisible = body != null
                )
            }
        }
    }

    private fun setupAR() {
        sceneView = findViewById(R.id.ar_fragment)
        
        sceneView.configureSession { _, config ->
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            config.depthMode = Config.DepthMode.AUTOMATIC
            config.focusMode = Config.FocusMode.AUTO
        }
        
        // Add manual studio lighting to ensure metallic models (Earth) are visible
        setupStudioLighting()
        
        sceneView.setOnTouchListener { _, event ->
             if (event.action == MotionEvent.ACTION_UP) {
                 // Reset flag
                 isNodeTap = false
                 
                 // Return false to allow sceneView to process the touch (and trigger node listeners)
                 // We post the placement logic to run AFTER the node listeners have fired.
                 sceneView.post {
                     if (isNodeTap) return@post
                     
                     // If selection was active and we tapped empty space, deselect
                     if (selectedModelIndex.value != -1) {
                         selectedModelIndex.value = -1
                         return@post
                     }

                     val frame = sceneView.frame
                     val hitResult = frame?.hitTest(event)?.firstOrNull()

                    if (activePlacementId == -1) {
                         activePlacementId = 0
                    }

                    if (selectedBodies.isEmpty()) {
                        Toast.makeText(this, "Avval sayyora tanlang", Toast.LENGTH_SHORT).show()
                    } else if (hitResult != null) {
                         if (anchorNode != null) {
                              clearAllModels()
                         }
                         
                         val hitAnchor = hitResult.createAnchor()
                         val newAnchorNode = AnchorNode(sceneView.engine, hitAnchor)
                         
                         sceneView.addChildNode(newAnchorNode)
                         anchorNode = newAnchorNode
                         
                         placeAllModels(isAir = false)
                    }
                 }
                 return@setOnTouchListener false
             }
             return@setOnTouchListener false // Allow other events (DOWN/MOVE) to exist
        }
    }
    
    private fun placeInAir() {
        val session = sceneView.session ?: return
        if (session.getAllTrackables(Plane::class.java).isEmpty()) {
             Toast.makeText(this, "Kamerani harakatga keltiring", Toast.LENGTH_SHORT).show()
        }

        if (anchorNode != null) {
             clearAllModels()
        }

        val frame = sceneView.frame ?: return
        val camera = frame.camera
        val pose = camera.pose.compose(Pose.makeTranslation(0f, 0f, -AIR_PLACEMENT_FORWARD_M))
        val anchor = session.createAnchor(pose)
        
        val newAnchorNode = AnchorNode(sceneView.engine, anchor)
        
        sceneView.addChildNode(newAnchorNode)
        anchorNode = newAnchorNode
        
        placeAllModels(isAir = true)
        Toast.makeText(this, "Modellar fazoga joylashtirildi!", Toast.LENGTH_SHORT).show()
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
        
        findViewById<Button>(R.id.btn_place_in_air)?.setOnClickListener {
            placeInAir()
        }
        
        scaleSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
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

    private fun placeAllModels(isAir: Boolean) {
        if (selectedBodies.isEmpty() || anchorNode == null) return

        activePlacementId += 1
        val placementId = activePlacementId
        distanceClampToastShown = false
        
        // Clear children
        anchorNode?.childNodes?.forEach { anchorNode?.removeChildNode(it) }
        placedModels.clear()
        
        loadingOverlay.visibility = View.VISIBLE
        
        val normalizedRadii = selectedBodies.map { normalizedRadius(it) }
        val maxRadius = normalizedRadii.maxOrNull() ?: 1.0
        
        var loadedCount = 0
        val totalCount = selectedBodies.size
        
        for ((index, body) in selectedBodies.withIndex()) {
            val bodyRadius = normalizedRadii.getOrElse(index) { 1.0 }
            loadAndPlaceModel(body, index, bodyRadius, maxRadius, placementId, isAir) {
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
        body: CelestialBody,
        index: Int,
        bodyRadius: Double,
        maxRadius: Double,
        placementId: Int,
        isAir: Boolean,
        onComplete: () -> Unit
    ) {
        if (placementId != activePlacementId) {
            onComplete()
            return
        }

        if (body.modelUrl.isNullOrEmpty()) {
            onComplete()
            return
        }

        val modelCacheDir = File(cacheDir, "models").apply { mkdirs() }
        val localFile = File(modelCacheDir, ModelCacheHelper.buildCacheFileName(body.modelUrl!!))

        if (localFile.exists()) {
            buildAndPlaceModel(localFile, index, placementId, isAir, onComplete)
        } else {
            val storage = FirebaseStorage.getInstance()
            val ref = try {
                when {
                    body.modelUrl!!.startsWith("gs://") ||
                        body.modelUrl!!.startsWith("https://firebasestorage.googleapis.com/") ->
                        storage.getReferenceFromUrl(body.modelUrl!!)
                    body.modelUrl!!.startsWith("http") ->
                        throw IllegalArgumentException("Only Firebase Storage URLs are supported")
                    else ->
                        storage.reference.child(body.modelUrl!!.trimStart('/'))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Invalid model URL for ${body.name}: ${body.modelUrl}", e)
                onComplete()
                return
            }

            ref.getFile(localFile)
                .addOnSuccessListener {
                    buildAndPlaceModel(localFile, index, placementId, isAir, onComplete)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to download model", e)
                    onComplete()
                }
        }
    }

    private fun buildAndPlaceModel(
        file: File,
        index: Int,
        placementId: Int,
        isAir: Boolean,
        onComplete: () -> Unit = {}
    ) {
        val root = anchorNode
        if (root == null || placementId != activePlacementId) {
            onComplete()
            return
        }

        lifecycleScope.launch {
            try {
                val modelInstance = sceneView.modelLoader.createModelInstance(file)
                if (modelInstance != null) {
                    val modelNode = ModelNode(
                        modelInstance = modelInstance,
                        scaleToUnits = null
                    )
                    
                    modelNode.isShadowCaster = !isAir
                    modelNode.isShadowReceiver = !isAir
                    modelNode.isTouchable = true
                    
                    modelNode.onSingleTapConfirmed = {
                        isNodeTap = true
                        selectedModelIndex.value = index
                        true
                    }
                    
                    root.addChildNode(modelNode)
                    placedModels[index] = modelNode
                    
                    updateLayout()
                    onComplete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating model node", e)
                onComplete()
            }
        }
    }

    private fun updateLayout() {
        val trueRadii = selectedBodies.map { normalizedRadius(it) }
        val maxRadius = trueRadii.maxOrNull() ?: 1.0
        val baseDiameter = adaptiveBaseVisualDiameter()
        val modelGap = adaptiveModelGap()

        var totalWidth = 0f
        val calculatedRadii = FloatArray(selectedBodies.size)
        val calculatedScales = FloatArray(selectedBodies.size)

        for (i in selectedBodies.indices) {
            val node = placedModels[i]
            if (node == null) {
                calculatedRadii[i] = 0f
                continue
            }
            
            val trueRadius = trueRadii[i]
            val radiusRatio = safeRadiusRatio(trueRadius, maxRadius)
            val targetDiameter = baseDiameter * radiusRatio * currentScale
            
            val modelExtent = ARScaleHelper.extractModelExtent(node)
            
            var finalScale = ARScaleHelper.computeSafeScale(
                modelExtent = modelExtent,
                targetDiameter = targetDiameter,
                minScale = MIN_NODE_SCALE,
                maxScale = MAX_NODE_SCALE,
                fallbackScale = FALLBACK_NODE_SCALE,
                invalidExtentThreshold = INVALID_EXTENT_THRESHOLD
            )
            
             val cameraDistance = cameraDistanceToNode(node)
             if (cameraDistance != null) {
                val maxAllowedRadius = maxOf(cameraDistance - CAMERA_MODEL_SAFETY_MARGIN_M, MIN_ALLOWED_MODEL_RADIUS_M)
                val approxRenderedRadius = if (modelExtent > INVALID_EXTENT_THRESHOLD) modelExtent * finalScale / 2f else targetDiameter / 2f
                
                if (approxRenderedRadius > maxAllowedRadius && approxRenderedRadius > 0f) {
                     val ratio = maxAllowedRadius / approxRenderedRadius
                     finalScale = maxOf(finalScale * ratio, MIN_NODE_SCALE)
                }
             }

            calculatedScales[i] = finalScale
            
            val renderedDiameter = if (modelExtent > INVALID_EXTENT_THRESHOLD) {
                modelExtent * finalScale
            } else {
                targetDiameter
            }
            val renderedRadius = maxOf(renderedDiameter / 2f, MIN_ALLOWED_MODEL_RADIUS_M)
            calculatedRadii[i] = renderedRadius
            
            if (i > 0) totalWidth += modelGap
            totalWidth += renderedRadius * 2
        }

        var currentX = -totalWidth / 2f
        
        for (i in selectedBodies.indices) {
            val node = placedModels[i] ?: continue
            val radius = calculatedRadii[i]
            
            if (radius > 0f) {
                val scale = calculatedScales[i]
                node.scale = Scale(scale)
                
                currentX += radius
                node.position = Position(currentX, 0f, 0f)
                
                renderedModelRadii[i] = radius
                currentX += radius + modelGap
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

    private fun clearAllModels(invalidatePlacement: Boolean = true) {
        if (invalidatePlacement) {
            activePlacementId += 1
        }
        
        anchorNode?.let { 
             sceneView.removeChildNode(it)
             it.destroy()
        }
        anchorNode = null
        
        placedModels.clear()
        renderedModelRadii.clear()
        selectedModelIndex.value = -1
        btnClear.visibility = View.GONE
    }
    
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
        return ARScaleHelper.adaptiveTargetSize(
            baseSize = BASE_VISUAL_DIAMETER_M,
            maxAdaptiveSize = MAX_ADAPTIVE_VISUAL_DIAMETER_M,
            smallestScreenWidthDp = resources.configuration.smallestScreenWidthDp
        )
    }

    private fun adaptiveModelGap(): Float {
        return ARScaleHelper.adaptiveTargetSize(
            baseSize = MODEL_GAP_M,
            maxAdaptiveSize = MAX_ADAPTIVE_MODEL_GAP_M,
            smallestScreenWidthDp = resources.configuration.smallestScreenWidthDp
        )
    }
    
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

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
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

    private fun setupStudioLighting() {
        try {
            // 1. Key Light (Main Sun) - Bright, from top-right-front
            val keyLightEntity = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1.0f, 1.0f, 1.0f)
                .intensity(110000.0f) // Very bright
                .direction(-1.0f, -1.0f, -1.0f) // Down and forward-right
                .castShadows(true)
                .build(sceneView.engine, keyLightEntity)
            
            // Add to scene
            sceneView.addChildNode(io.github.sceneview.node.Node(sceneView.engine, keyLightEntity).apply {
                name = "KeyLight"
            })

            // 2. Fill Light (Softener) - Softer, from left
            val fillLightEntity = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(0.9f, 0.9f, 1.0f) // Slightly cool
                .intensity(50000.0f) 
                .direction(1.0f, -0.5f, -0.5f) // From left-side
                .castShadows(false)
                .build(sceneView.engine, fillLightEntity)
            sceneView.addChildNode(io.github.sceneview.node.Node(sceneView.engine, fillLightEntity).apply{
                name = "FillLight"
            })

            // 3. Back Light (Rim) - Separates model from background
            val backLightEntity = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1.0f, 0.9f, 0.8f) // Slightly warm
                .intensity(60000.0f)
                .direction(0.0f, -1.0f, 1.0f) // From behind/top
                .castShadows(false)
                .build(sceneView.engine, backLightEntity)
            sceneView.addChildNode(io.github.sceneview.node.Node(sceneView.engine, backLightEntity).apply{
                name = "BackLight"
            })
            
            Log.d(TAG, "Studio lighting setup complete")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup studio lighting", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        placedModels.clear()
        renderedModelRadii.clear()
        anchorNode = null
    }
}
