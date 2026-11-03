package com.jerrymk.kigeyes

import java.util.Locale
import kotlin.math.abs

/** Camera-reported bounds, shared by buttons, pinch and capture requests. */
data class CameraZoom(val min: Float=1f,val max: Float=1f) {
    init { require(min.isFinite() && max.isFinite() && min>0 && max>=min) }
    fun clamp(value: Float)=if(value.isFinite())value.coerceIn(min,max) else 1f.coerceIn(min,max)
    fun pinch(current: Float,factor: Float)=if(factor.isFinite() && factor>0)clamp(current*factor) else clamp(current)
    fun remote(value: Double): Float {
        require(value.isFinite() && value>0 && value<=100) { "Invalid remote zoom" }
        return clamp(value.toFloat())
    }
    val presets: List<Float> get() {
        val stops=mutableListOf<Float>()
        if(min<1f)stops.add(if(min<=.5f && max>=.5f).5f else min)
        stops.addAll(listOf(1f,2f,5f).filter { it>=min && it<=max })
        if(stops.isEmpty())stops.add(min)
        return stops.distinct()
    }
    companion object {
        fun label(ratio: Float): String {
            val number=if(abs(ratio-ratio.toInt())<.01f)ratio.toInt().toString() else String.format(Locale.US,"%.1f",ratio)
            return "${number}×"
        }
    }
}
