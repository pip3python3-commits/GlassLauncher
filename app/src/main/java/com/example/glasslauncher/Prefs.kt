package com.example.glasslauncher

import android.content.Context

class Prefs(ctx: Context) {

    class Setting(
        val key: String,
        val label: String,
        val min: Int,
        val max: Int,
        val def: Int,
        val unit: String
    )

    companion object {
        val ALL = listOf(
            Setting("speed", "Animation speed", 50, 200, 100, "%"),
            Setting("blur", "Blur strength", 0, 60, 30, ""),
            Setting("bounce", "Spring bounciness", 0, 100, 50, "%"),
            Setting("iconSize", "Icon size", 40, 72, 56, " dp"),
            Setting("round", "Icon roundness", 0, 50, 32, "%"),
            Setting("cols", "Grid columns", 4, 6, 5, ""),
            Setting("rows", "Grid rows", 4, 7, 6, "")
        )
    }

    private val sp = ctx.applicationContext.getSharedPreferences("glass", Context.MODE_PRIVATE)

    fun get(key: String): Int = sp.getInt(key, ALL.first { it.key == key }.def)

    fun put(key: String, v: Int) {
        sp.edit().putInt(key, v).apply()
    }

    fun reset() {
        sp.edit().clear().apply()
    }

    fun blurPx(): Float = get("blur").toFloat()

    /** 0.95 = firm, 0.45 = bouncy */
    fun damping(): Float = 0.95f - 0.5f * get("bounce") / 100f

    fun stiffness(): Float {
        val s = get("speed") / 100f
        return 500f * s * s
    }

    /** Scale a base duration by the speed setting. */
    fun ms(base: Long): Long = base * 100L / get("speed")
}
