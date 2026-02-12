package com.huji.couchmirage.ar

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.Scene
import com.google.ar.sceneform.ux.ArFragment
import com.huji.couchmirage.R

/****
 * Custom ar fragment
 */
class MyArFragment : ArFragment(), Scene.OnUpdateListener {

    //
    var animationLayout: ConstraintLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        val view = super.onCreateView(inflater, container, savedInstanceState)

        planeDiscoveryController.hide()
        planeDiscoveryController.setInstructionView(null)

        animationLayout = activity?.findViewById(R.id.animation)

        return view
    }

    override fun onUpdate(p0: FrameTime?) {

        super.onUpdate(p0)

        //
        val animation = animationLayout ?: return
        if (animation.visibility != View.VISIBLE) {
            return
        }

        // change the information button state to yellow
        val frame = arSceneView?.arFrame ?: return
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            return
        }

        //
        for (plane in frame.getUpdatedTrackables(Plane::class.java)) {
            if (plane.trackingState == TrackingState.TRACKING) {
                hideLoadingMessage()
            }
        }

    }


    private fun hideLoadingMessage() {

        val animation = animationLayout ?: return
        if (animation.visibility == View.INVISIBLE) {
            return
        }

        animation.visibility = View.INVISIBLE
    }


    private fun showLoadingMessage() {

        val animation = animationLayout ?: return
        if (animation.visibility != View.INVISIBLE) {
            return
        }

        animation.visibility = View.VISIBLE

    }


    override fun onResume() {
        super.onResume()

        //
        if (arSceneView == null) {
            return
        }

        //   whenever the arSceneView has started show loading message
        if (arSceneView!!.session != null) {
            showLoadingMessage()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        animationLayout = null
    }


}
