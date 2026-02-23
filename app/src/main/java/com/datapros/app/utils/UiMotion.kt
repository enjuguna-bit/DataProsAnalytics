package com.datapros.app.utils

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import com.datapros.app.R

object UiMotion {

    fun runEntryStagger(root: View, panelId: Int = R.id.content_panel) {
        val panel = root.findViewById<ViewGroup?>(panelId) ?: (root as? ViewGroup) ?: return
        panel.post { panel.scheduleLayoutAnimation() }
    }

    fun reveal(view: View) {
        view.startAnimation(AnimationUtils.loadAnimation(view.context, R.anim.fx_card_reveal))
    }

    fun bindPress(vararg views: View) {
        views.forEach { target ->
            target.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate()
                            .scaleX(0.97f)
                            .scaleY(0.97f)
                            .setDuration(90L)
                            .start()
                    }

                    MotionEvent.ACTION_CANCEL,
                    MotionEvent.ACTION_UP -> {
                        v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(140L)
                            .start()
                    }
                }
                false
            }
        }
    }
}
