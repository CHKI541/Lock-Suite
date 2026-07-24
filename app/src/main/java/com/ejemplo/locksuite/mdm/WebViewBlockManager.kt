package com.ejemplo.locksuite.mdm

import android.content.Context

object WebViewBlockManager {

    private const val PREFS_NAME = "webview_block_prefs"
    private const val KEY_PREFIX = "wv_block_"

    @Volatile private var cachedBlockedSet: Set<String>? = null

    fun setBlocked(context: Context, packageName: String, blocked: Boolean): Boolean {
        prefs(context).edit().putBoolean(KEY_PREFIX + packageName, blocked).apply()
        synchronized(this) { cachedBlockedSet = null } // Invalida caché
        if (blocked) {
            com.ejemplo.locksuite.receiver.BootReceiver.ensureVpnRunning(context)
        }
        return true
    }

    fun isBlocked(context: Context, packageName: String): Boolean {
        return getBlockedSet(context).contains(packageName)
    }

    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
        synchronized(this) { cachedBlockedSet = null }
    }

    fun getBlockedPackages(context: Context): Set<String> {
        return getBlockedSet(context)
    }

    private fun getBlockedSet(context: Context): Set<String> {
        cachedBlockedSet?.let { return it }
        synchronized(this) {
            cachedBlockedSet?.let { return it }
            val fresh = prefs(context).all
                .filterKeys { it.startsWith(KEY_PREFIX) }
                .filterValues { it == true }
                .keys.map { it.removePrefix(KEY_PREFIX) }
                .toSet()
            cachedBlockedSet = fresh
            return fresh
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
