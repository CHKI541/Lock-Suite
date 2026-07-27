package com.ejemplo.locksuite.mdm

import android.content.Context

object ImageBlockManager {

    private const val PREFS_NAME = "image_block_prefs"
    private const val KEY_PREFIX = "img_block_"
    private const val KEY_GLOBAL_AI_ENABLED = "global_ai_mode_enabled"
    private const val KEY_MAPS_IMAGE_BLOCKING_ENABLED = "maps_image_blocking_enabled"

    @Volatile private var cachedModes: Map<String, String>? = null
    @Volatile private var cachedGlobalAi: Boolean? = null
    @Volatile private var cachedMapsBlocking: Boolean? = null

    fun setGlobalAiEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GLOBAL_AI_ENABLED, enabled).apply()
        synchronized(this) {
            cachedGlobalAi = enabled
        }
    }

    fun isGlobalAiEnabled(context: Context): Boolean {
        cachedGlobalAi?.let { return it }
        synchronized(this) {
            cachedGlobalAi?.let { return it }
            val enabled = prefs(context).getBoolean(KEY_GLOBAL_AI_ENABLED, false)
            cachedGlobalAi = enabled
            return enabled
        }
    }

    fun setMapsImageBlockingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MAPS_IMAGE_BLOCKING_ENABLED, enabled).apply()
        synchronized(this) {
            cachedMapsBlocking = enabled
        }
    }

    fun isMapsImageBlockingEnabled(context: Context): Boolean {
        cachedMapsBlocking?.let { return it }
        synchronized(this) {
            cachedMapsBlocking?.let { return it }
            val enabled = prefs(context).getBoolean(KEY_MAPS_IMAGE_BLOCKING_ENABLED, false)
            cachedMapsBlocking = enabled
            return enabled
        }
    }

    fun setMode(context: Context, packageName: String, mode: String): Boolean {
        prefs(context).edit().putString(KEY_PREFIX + packageName, mode).apply()
        synchronized(this) {
            cachedModes = null // Invalidate cache
        }
        return true
    }

    fun getMode(context: Context, packageName: String): String {
        return getModesMap(context)[packageName] ?: "none"
    }

    fun isLayer1Enabled(context: Context, packageName: String): Boolean {
        val mode = getMode(context, packageName)
        return mode == "layer1" || mode == "both"
    }

    fun isLayer2Enabled(context: Context, packageName: String): Boolean {
        val mode = getMode(context, packageName)
        return mode == "layer2" || mode == "both"
    }

    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
        synchronized(this) {
            cachedModes = null
            cachedGlobalAi = null
            cachedMapsBlocking = null
        }
    }

    private fun getModesMap(context: Context): Map<String, String> {
        cachedModes?.let { return it }
        synchronized(this) {
            cachedModes?.let { return it }
            val allPrefs = prefs(context).all
            val fresh = allPrefs
                .filterKeys { it.startsWith(KEY_PREFIX) }
                .mapValues { it.value as? String ?: "none" }
                .mapKeys { it.key.removePrefix(KEY_PREFIX) }
            cachedModes = fresh
            return fresh
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
