package com.ejemplo.locksuite.util

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader

object AdBlocker {

    @Volatile private var blockedSet: Set<String> = emptySet()
    @Volatile var isReady: Boolean = false
        private set

    private const val ASSET_FILENAME = "hagezi-light.txt"
    private const val LOCAL_FILENAME = "adblock_list.txt" // Permite actualizarla en caliente en el futuro

    /**
     * Carga la lista de dominios bloqueados en segundo plano para no congelar la UI de la app.
     */
    fun loadAsync(context: Context) {
        if (isReady) return
        Thread {
            try {
                val localFile = File(context.filesDir, LOCAL_FILENAME)
                val reader: BufferedReader = if (localFile.exists()) {
                    BufferedReader(FileReader(localFile))
                } else {
                    BufferedReader(InputStreamReader(context.assets.open(ASSET_FILENAME)))
                }

                // Cargamos con un tamaño inicial estimado de ~60,000 dominios para evitar redimensionar el set en runtime
                val parsed = HashSet<String>(60_000)
                reader.useLines { lines ->
                    lines.forEach { rawLine ->
                        val line = rawLine.trim()
                        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEach
                        
                        // Si la línea contiene espacios (ej. "0.0.0.0 adserver.com"), extraemos el dominio (la última parte)
                        var domain = line.split(Regex("\\s+")).lastOrNull()?.lowercase()?.trim()
                        if (!domain.isNullOrEmpty()) {
                            domain = domain
                                .removePrefix("||")
                                .removePrefix("*.")
                                .removePrefix(".")
                                .substringBefore("^")
                                .substringBefore("/")
                                .removeSuffix(".")

                            if (domain.matches(Regex("^[a-z0-9.-]+\\.[a-z0-9]+$"))) {
                                parsed.add(domain)
                            }
                        }
                    }
                }

                blockedSet = parsed // Publicación atómica (cambio instantáneo una vez cargado)
                isReady = true
                android.util.Log.i("AdBlocker", "Lista de bloqueo de anuncios cargada: ${parsed.size} dominios.")
            } catch (e: Exception) {
                android.util.Log.e("AdBlocker", "Error cargando lista de bloqueo de anuncios: ${e.message}")
            }
        }.start()
    }

    /**
     * Comprueba en tiempo de ejecución si un dominio de consulta DNS debe ser bloqueado.
     * Implementa la búsqueda eficiente de sufijos en HashSet (O(1)) para no ralentizar el celular.
     */
    fun isBlocked(domain: String): Boolean {
        if (!isReady) return false
        
        // Limpiamos el dominio y lo separamos por puntos (ej: "ads.google-analytics.com" -> ["ads", "google-analytics", "com"])
        val labels = domain.lowercase().removeSuffix(".").split(".")
        if (labels.size < 2) return false
        
        // Probamos los sufijos combinando los componentes del final hacia el principio (ej: "ads.google-analytics.com", "google-analytics.com")
        // Excluimos el último elemento (el TLD, como ".com" solo) para no romper el internet completo.
        for (i in 0 until labels.size - 1) {
            val suffix = labels.subList(i, labels.size).joinToString(".")
            if (blockedSet.contains(suffix)) {
                return true
            }
        }
        return false
    }
}
