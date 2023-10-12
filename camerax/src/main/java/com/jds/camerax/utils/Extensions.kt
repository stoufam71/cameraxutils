package com.jds.camerax.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.*
import android.view.View.*
import android.widget.ImageButton
import androidx.annotation.DrawableRes
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

fun ImageButton.toggleButton(
    flag: Boolean, rotationAngle: Float, duration:Long? = null, @DrawableRes firstIcon: Int, @DrawableRes secondIcon: Int,
    action: (Boolean) -> Unit
) {
    val mDuration = duration ?: 200
    if (flag) {
        if (rotationY == 0f) rotationY = rotationAngle
        animate().rotationY(0f).apply {
            setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    super.onAnimationEnd(animation)
                    action(!flag)
                }
            })
        }.duration = mDuration
        GlobalScope.launch(Dispatchers.Main) {
            delay(100)
            setImageResource(firstIcon)
        }
    } else {
        if (rotationY == rotationAngle) rotationY = 0f
        animate().rotationY(rotationAngle).apply {
            setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    super.onAnimationEnd(animation)
                    action(!flag)
                }
            })
        }.duration = mDuration
        GlobalScope.launch(Dispatchers.Main) {
            delay(100)
            setImageResource(secondIcon)
        }
    }
}

fun ImageButton.rotateButton(
    flag: Boolean,
    action: (Boolean) -> Unit
) {
    val desiredRotation = rotation + 180f
    animate()
        .rotation(desiredRotation)
        .setDuration(200) // Animation duration in milliseconds (adjust as needed)
        .withEndAction {
            // This code block will be executed when the animation ends
            // Add your custom behavior here
            action(!flag)
        }
}

fun ViewGroup.circularReveal(button: ImageButton) {
    ViewAnimationUtils.createCircularReveal(
        this,
        button.x.toInt() + button.width / 2,
        button.y.toInt() + button.height / 2,
        0f,
        if (button.context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) this.width.toFloat() else this.height.toFloat()
    ).apply {
        duration = 500
        doOnStart { visibility = VISIBLE }
    }.start()
}

fun ViewGroup.circularClose(button: ImageButton, action: () -> Unit = {}) {
    ViewAnimationUtils.createCircularReveal(
        this,
        button.x.toInt() + button.width / 2,
        button.y.toInt() + button.height / 2,
        if (button.context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) this.width.toFloat() else this.height.toFloat(),
        0f
    ).apply {
        duration = 500
        doOnStart { action() }
        doOnEnd { visibility = GONE }
    }.start()
}

fun View.onWindowInsets(action: (View, WindowInsetsCompat) -> Unit) {
    ViewCompat.requestApplyInsets(this)
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        action(v, insets)
        insets
    }
}

fun Window.fitSystemWindows() {
    WindowCompat.setDecorFitsSystemWindows(this, false)
}

fun ViewPager2.onPageSelected(action: (Int) -> Unit) {
    registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            super.onPageSelected(position)
            action(position)
        }
    })
}

fun Context.mainExecutor(): Executor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    mainExecutor
} else {
    MainExecutor()
}

val Context.layoutInflater: LayoutInflater
    get() = LayoutInflater.from(this)

var View.topMargin: Int
    get() = (this.layoutParams as ViewGroup.MarginLayoutParams).topMargin
    set(value) {
        updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = value }
    }

var View.topPadding: Int
    get() = paddingTop
    set(value) {
        updateLayoutParams { setPaddingRelative(paddingStart, value, paddingEnd, paddingBottom) }
    }

var View.bottomMargin: Int
    get() = (this.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
    set(value) {
        updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = value }
    }

var View.endMargin: Int
    get() = (this.layoutParams as ViewGroup.MarginLayoutParams).marginEnd
    set(value) {
        updateLayoutParams<ViewGroup.MarginLayoutParams> { marginEnd = value }
    }

var View.startMargin: Int
    get() = (this.layoutParams as ViewGroup.MarginLayoutParams).marginStart
    set(value) {
        updateLayoutParams<ViewGroup.MarginLayoutParams> { marginStart = value }
    }

var View.startPadding: Int
    get() = paddingStart
    set(value) {
        updateLayoutParams { setPaddingRelative(value, paddingTop, paddingEnd, paddingBottom) }
    }