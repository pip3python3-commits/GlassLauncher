package com.example.glasslauncher

import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Blur a view's own pixels (Android 12+). Radius under 0.5 removes the effect. */
fun View.setBlur(radius: Float) {
    if (radius < 0.5f) {
        setRenderEffect(null)
    } else {
        setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
    }
}

/** Spring-animate one property of a view, using the speed/bounce settings. */
fun View.spring(
    prop: DynamicAnimation.ViewProperty,
    to: Float,
    prefs: Prefs,
    delay: Long = 0L,
    onEnd: (() -> Unit)? = null
) {
    val view = this
    val go = Runnable {
        val a = SpringAnimation(view, prop, to)
        a.spring.setDampingRatio(prefs.damping())
        a.spring.setStiffness(prefs.stiffness())
        if (onEnd != null) {
            a.addEndListener { _, _, _, _ -> onEnd() }
        }
        a.start()
    }
    if (delay > 0L) postDelayed(go, delay) else go.run()
}
