package com.smartisanrevival.onestep.anim

import android.animation.TimeInterpolator
import android.view.animation.Interpolator

/**
 * 官方 packages_apps_OneStep `util/anim/AnimInterpolator` 的 Penner 缓动移植（Apache-2.0）。
 * 原版全部动效代码驱动、无 XML anim，缓动只有这一个来源。
 */
object OneStepAnim {

    /** cubic-out：p = 1-(1-t)^3，原版侧栏/顶栏进出主缓动 */
    class CubicOut : TimeInterpolator {
        override fun getInterpolation(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)
    }

    /** quad-out：p = 1-(1-t)^2 */
    class QuadOut : TimeInterpolator {
        override fun getInterpolation(t: Float): Float = (1f - (1f - t) * (1f - t))
    }

    /** circ-out：p = sqrt(1-(t-1)^2)，垃圾桶摇晃用 IN_OUT */
    class CircInOut : TimeInterpolator {
        override fun getInterpolation(t: Float): Float {
            var t = t * 2f
            if (t < 1f) return -0.5f * (kotlin.math.sqrt(1f - t * t) - 1f)
            t -= 2f
            return 0.5f * (kotlin.math.sqrt(1f - t * t) + 1f)
        }
    }

    // 官方动效时长（ms）
    const val SIDEBAR_IN = 150L
    const val SIDEBAR_OUT = 200L
    const val TOPBAR_IN = 200L
    const val CONTENT_ALPHA = 300L
    const val DRAG_SWAP = 300L
    const val ITEM_POP = 200L
}
