package com.gdroidlan.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView
import com.gdroidlan.app.R
import com.google.android.material.button.MaterialButton

/**
 * Motor centralizado de Motion Design (Material Design 3 + Apple Fluid Physics).
 *
 * Princípios aplicados:
 * - Curvas assimétricas tokenizadas: Emphasized Decelerate para entradas espaciais,
 *   Emphasized Accelerate para saídas, Standard (criticamente amortecida, sem bounce)
 *   para cor/opacidade e Tactile Spring para resposta física ao toque.
 * - Anima exclusivamente propriedades de RenderNode (translationX/Y, scaleX/Y, alpha, rotation)
 *   para manter 60/120 fps constantes sem recálculo de layout.
 * - Respeita a preferência global de acessibilidade do Android (ANIMATOR_DURATION_SCALE).
 */
object UiMotion {

    // Durações oficiais (escala Material Design 3)
    const val SHORT_2 = 90L     // Compressão imediata ao toque (<100ms)
    const val SHORT_4 = 200L    // Seleção de pílulas/chips e transição de cor
    const val MEDIUM_1 = 250L   // Abertura de subpainéis e micro-pop
    const val MEDIUM_2 = 300L   // Transição direcional de abas (Shared-Axis X) e shake de erro
    const val LONG_1 = 380L     // Coreografia de entrada escalonada na abertura

    // Curvas de aceleração (PathInterpolator — API 21+)
    val EMPHASIZED_DECELERATE: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)
    val EMPHASIZED_ACCELERATE: Interpolator = PathInterpolator(0.3f, 0.0f, 0.8f, 0.15f)
    val STANDARD: Interpolator = PathInterpolator(0.2f, 0.0f, 0.0f, 1.0f)
    val TACTILE_SPRING: Interpolator = OvershootInterpolator(1.32f)
    val SINE_BREATHE: Interpolator = PathInterpolator(0.42f, 0.0f, 0.58f, 1.0f)

    private val argbEvaluator = ArgbEvaluator()

    fun isReducedMotion(context: Context): Boolean {
        return try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
            scale <= 0.05f
        } catch (_: Exception) {
            false
        }
    }

    private fun dp(view: View, value: Float): Float =
        value * view.resources.displayMetrics.density

    // =========================================================================
    // FEEDBACK TÁTIL (HAPTICS SINCRONIZADO COM A ANIMAÇÃO)
    // =========================================================================

    fun hapticTick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun hapticConfirm(view: View) {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(code)
    }

    fun hapticReject(view: View) {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(code)
    }

    // =========================================================================
    // MICRO-INTERAÇÃO FÍSICA DE PRESSÃO (COMPRESSÃO <100ms + RETORNO EM MOLA)
    // =========================================================================

    @SuppressLint("ClickableViewAccessibility")
    fun attachTactilePress(vararg views: View, scaleDown: Float = 0.965f) {
        for (v in views) {
            v.setOnTouchListener { target, event ->
                if (!target.isEnabled || isReducedMotion(target.context)) {
                    if (target.scaleX != 1f || target.scaleY != 1f) {
                        target.scaleX = 1f
                        target.scaleY = 1f
                    }
                    return@setOnTouchListener false
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        target.animate().cancel()
                        target.animate()
                            .scaleX(scaleDown)
                            .scaleY(scaleDown)
                            .setDuration(SHORT_2)
                            .setInterpolator(STANDARD)
                            .start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        target.animate().cancel()
                        target.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(SHORT_4)
                            .setInterpolator(TACTILE_SPRING)
                            .start()
                    }
                }
                false
            }
        }
    }

    // =========================================================================
    // COREOGRAFIA ESCALONADA DE ENTRADA (HERO / CARDS / LISTAS)
    // =========================================================================

    fun animateStaggeredEntrance(
        views: List<View>,
        startDelayMs: Long = 25L,
        stepDelayMs: Long = 45L,
        distanceDp: Float = 14f,
        maxAnimatedItems: Int = 6
    ) {
        if (views.isEmpty()) return
        if (isReducedMotion(views.first().context)) {
            for (v in views) {
                v.alpha = 1f
                v.translationY = 0f
                v.translationX = 0f
            }
            return
        }

        var idx = 0
        for (v in views) {
            if (v.visibility != View.VISIBLE) continue
            v.animate().cancel()
            if (idx >= maxAnimatedItems) {
                v.alpha = 1f
                v.translationY = 0f
                continue
            }
            v.alpha = 0f
            v.translationY = dp(v, distanceDp)
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(startDelayMs + idx * stepDelayMs)
                .setDuration(LONG_1)
                .setInterpolator(EMPHASIZED_DECELERATE)
                .start()
            idx++
        }
    }

    fun animateDrawerItems(views: List<View>) {
        if (views.isEmpty()) return
        if (isReducedMotion(views.first().context)) {
            for (v in views) {
                v.alpha = 1f
                v.translationX = 0f
            }
            return
        }

        views.forEachIndexed { i, v ->
            v.animate().cancel()
            v.alpha = 0f
            v.translationX = dp(v, 18f)
            v.animate()
                .alpha(1f)
                .translationX(0f)
                .setStartDelay(30L + i * 38L)
                .setDuration(MEDIUM_2)
                .setInterpolator(EMPHASIZED_DECELERATE)
                .start()
        }
    }

    // =========================================================================
    // TRANSIÇÃO DIRECIONAL DE ABAS (SHARED-AXIS X)
    // =========================================================================

    fun animateSharedAxisX(entering: View, direction: Int, distanceDp: Float = 18f) {
        entering.visibility = View.VISIBLE
        if (isReducedMotion(entering.context) || direction == 0) {
            entering.alpha = 1f
            entering.translationX = 0f
            return
        }
        val offset = dp(entering, distanceDp) * (if (direction > 0) 1f else -1f)
        entering.animate().cancel()
        entering.alpha = 0f
        entering.translationX = offset
        entering.animate()
            .alpha(1f)
            .translationX(0f)
            .setStartDelay(0L)
            .setDuration(MEDIUM_2)
            .setInterpolator(EMPHASIZED_DECELERATE)
            .start()
    }

    // =========================================================================
    // PÍLULAS SEGMENTADAS E BADGES (TRANSIÇÃO DE COR CRITICAMENTE AMORTECIDA)
    // =========================================================================

    fun styleSegmentedPill(
        button: MaterialButton,
        selected: Boolean,
        activeBg: Int,
        inactiveBg: Int,
        activeText: Int,
        inactiveText: Int,
        strokeWidthPx: Int,
        strokeColor: Int,
        activeIcon: Int? = null,
        inactiveIcon: Int? = null,
        animate: Boolean = true
    ) {
        val prevAnim = button.getTag(R.id.tag_motion_color_anim) as? ValueAnimator
        prevAnim?.cancel()

        val targetBg = if (selected) activeBg else inactiveBg
        val targetText = if (selected) activeText else inactiveText
        val targetIcon = if (selected) activeIcon else inactiveIcon

        button.strokeWidth = if (selected) strokeWidthPx else 0
        if (selected) {
            button.strokeColor = ColorStateList.valueOf(strokeColor)
        }

        val currentBg = (button.getTag(R.id.tag_motion_bg_color) as? Int) ?: targetBg
        val currentText = button.currentTextColor
        val currentIcon = (button.getTag(R.id.tag_motion_icon_color) as? Int) ?: (targetIcon ?: currentText)

        if (!animate || isReducedMotion(button.context) || (currentBg == targetBg && currentText == targetText)) {
            button.backgroundTintList = ColorStateList.valueOf(targetBg)
            button.setTextColor(targetText)
            if (targetIcon != null) {
                button.iconTint = ColorStateList.valueOf(targetIcon)
                button.setTag(R.id.tag_motion_icon_color, targetIcon)
            }
            button.setTag(R.id.tag_motion_bg_color, targetBg)
            return
        }

        val va = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SHORT_4
            interpolator = STANDARD
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val bg = argbEvaluator.evaluate(f, currentBg, targetBg) as Int
                val tx = argbEvaluator.evaluate(f, currentText, targetText) as Int
                button.backgroundTintList = ColorStateList.valueOf(bg)
                button.setTextColor(tx)
                button.setTag(R.id.tag_motion_bg_color, bg)
                if (targetIcon != null) {
                    val ic = argbEvaluator.evaluate(f, currentIcon, targetIcon) as Int
                    button.iconTint = ColorStateList.valueOf(ic)
                    button.setTag(R.id.tag_motion_icon_color, ic)
                }
            }
        }
        button.setTag(R.id.tag_motion_color_anim, va)
        va.start()

        if (selected) {
            button.animate().cancel()
            button.scaleX = 0.96f
            button.scaleY = 0.96f
            button.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(SHORT_4)
                .setInterpolator(TACTILE_SPRING)
                .start()
        }
    }

    fun morphPillColors(
        view: TextView,
        targetBg: Int,
        targetText: Int,
        animate: Boolean = true,
        pop: Boolean = false
    ) {
        val prevAnim = view.getTag(R.id.tag_motion_color_anim) as? ValueAnimator
        prevAnim?.cancel()

        val currentBg = (view.getTag(R.id.tag_motion_bg_color) as? Int) ?: targetBg
        val currentText = view.currentTextColor

        if (!animate || isReducedMotion(view.context) || (currentBg == targetBg && currentText == targetText)) {
            view.backgroundTintList = ColorStateList.valueOf(targetBg)
            view.setTextColor(targetText)
            view.setTag(R.id.tag_motion_bg_color, targetBg)
        } else {
            val va = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = SHORT_4
                interpolator = STANDARD
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    val bg = argbEvaluator.evaluate(f, currentBg, targetBg) as Int
                    val tx = argbEvaluator.evaluate(f, currentText, targetText) as Int
                    view.backgroundTintList = ColorStateList.valueOf(bg)
                    view.setTextColor(tx)
                    view.setTag(R.id.tag_motion_bg_color, bg)
                }
            }
            view.setTag(R.id.tag_motion_color_anim, va)
            va.start()
        }

        if (pop && !isReducedMotion(view.context)) {
            view.animate().cancel()
            view.scaleX = 0.92f
            view.scaleY = 0.92f
            view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(MEDIUM_1)
                .setInterpolator(TACTILE_SPRING)
                .start()
        }
    }

    // =========================================================================
    // PULSO AMBIENTE PARA ESTADOS DE CONEXÃO ATIVA / AGUARDANDO
    // =========================================================================

    fun setBreathingPulse(view: View, active: Boolean) {
        val current = view.getTag(R.id.tag_motion_pulse_anim) as? ObjectAnimator
        if (!active) {
            if (current != null) {
                current.cancel()
                view.setTag(R.id.tag_motion_pulse_anim, null)
                view.alpha = 1f
            }
            return
        }

        // Nota: indicadores de atividade/carregamento mantêm sinal suave mesmo em reduced motion
        if (current != null && current.isRunning) return
        val pulse = ObjectAnimator.ofFloat(view, View.ALPHA, 1.0f, 0.56f).apply {
            duration = 920L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = SINE_BREATHE
        }
        view.setTag(R.id.tag_motion_pulse_anim, pulse)
        pulse.start()
    }

    // =========================================================================
    // SUBPAINÉIS EXPANSÍVEIS (REVEAL / COLLAPSE SUAVE)
    // =========================================================================

    fun setPanelVisible(view: View, visible: Boolean, animate: Boolean = true) {
        if (visible) {
            if (view.visibility == View.VISIBLE && view.alpha == 1f) return
            view.animate().cancel()
            view.visibility = View.VISIBLE
            if (!animate || isReducedMotion(view.context)) {
                view.alpha = 1f
                view.translationY = 0f
                view.scaleY = 1f
                return
            }
            view.pivotY = 0f
            view.alpha = 0f
            view.translationY = -dp(view, 8f)
            view.scaleY = 0.97f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleY(1f)
                .setDuration(MEDIUM_1)
                .setInterpolator(EMPHASIZED_DECELERATE)
                .setListener(null)
                .start()
        } else {
            if (view.visibility == View.GONE) return
            view.animate().cancel()
            if (!animate || isReducedMotion(view.context)) {
                view.visibility = View.GONE
                view.alpha = 1f
                view.translationY = 0f
                view.scaleY = 1f
                return
            }
            view.animate()
                .alpha(0f)
                .translationY(-dp(view, 6f))
                .setDuration(160L)
                .setInterpolator(EMPHASIZED_ACCELERATE)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.visibility = View.GONE
                        view.alpha = 1f
                        view.translationY = 0f
                        view.scaleY = 1f
                        view.animate().setListener(null)
                    }
                })
                .start()
        }
    }

    // =========================================================================
    // CONTADOR DIRECIONAL (+ / -), POP DE VALOR E SHAKE DE ERRO
    // =========================================================================

    fun animateCounterStep(textView: TextView, newText: String, delta: Int) {
        textView.text = newText
        if (isReducedMotion(textView.context) || delta == 0) return
        val dir = if (delta > 0) -1f else 1f
        textView.animate().cancel()
        textView.translationY = dp(textView, 7f) * dir
        textView.scaleX = 1.15f
        textView.scaleY = 1.15f
        textView.alpha = 0.65f
        textView.animate()
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(SHORT_4)
            .setInterpolator(TACTILE_SPRING)
            .start()
    }

    fun animateValueRefresh(view: View) {
        if (isReducedMotion(view.context)) return
        view.animate().cancel()
        view.alpha = 0.45f
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(MEDIUM_1)
            .setInterpolator(TACTILE_SPRING)
            .start()
    }

    fun pulseConfirmation(view: View) {
        if (isReducedMotion(view.context)) return
        view.animate().cancel()
        view.scaleX = 1.035f
        view.scaleY = 1.035f
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(MEDIUM_1)
            .setInterpolator(TACTILE_SPRING)
            .start()
    }

    fun shakeError(view: View) {
        hapticReject(view)
        if (isReducedMotion(view.context)) return
        val prev = view.getTag(R.id.tag_motion_shake_anim) as? ObjectAnimator
        prev?.cancel()
        val amp = dp(view, 8f)
        val shake = ObjectAnimator.ofFloat(
            view,
            View.TRANSLATION_X,
            0f, -amp, amp * 0.85f, -amp * 0.6f, amp * 0.4f, -amp * 0.2f, 0f
        ).apply {
            duration = MEDIUM_2
            interpolator = STANDARD
        }
        view.setTag(R.id.tag_motion_shake_anim, shake)
        shake.start()
    }

    @Suppress("DEPRECATION")
    fun applyActivityTransition(activity: Activity) {
        if (isReducedMotion(activity)) return
        activity.overridePendingTransition(R.anim.anim_activity_enter, R.anim.anim_activity_exit)
    }
}
