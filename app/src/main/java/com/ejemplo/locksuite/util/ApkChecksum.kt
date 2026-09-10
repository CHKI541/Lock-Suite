package com.ejemplo.locksuite.util

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * ApkChecksum — LA MITAD DE B.6 QUE FALTABA (10/9/2026).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * POR QUÉ ESTO NO ES UNA REDUNDANCIA DE `ApkSignatureVerifier`
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * B.37 agregó `ApkSignatureVerifier`, que compara la firma del APK descargado contra la
 * del paquete **ya instalado**. Sirve para las actualizaciones y no sirve para nada más,
 * porque **si el paquete no está instalado no hay contra qué comparar**. Su propio
 * comentario lo dice, y B.37 lo dejó escrito: *"Esto NO cierra B.6 entero y no hay que
 * darlo por cerrado"*.
 *
 * Y el caso que queda afuera es justamente el peor:
 *
 *   La **Tienda administrada** instala apps por PRIMERA vez, en silencio, con el
 *   privilegio de Device Owner, sin que el usuario vea ni confirme nada.
 *
 * O sea: hasta hoy, cualquiera que pudiera cambiar el contenido servido en la `apkUrl` de
 * una entrada de `storeApps` conseguía ejecución silenciosa con privilegios en toda la
 * flota. No hace falta romper Firebase para eso — alcanza con que la URL apunte a un
 * hosting de terceros que caduque, cambie de dueño o se comprometa.
 *
 * Esto lo cierra: el `sha256` se publica junto con la entrada, y se compara **contra el
 * archivo ya descargado en disco, antes de abrir la sesión de `PackageInstaller`**.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * SE FALLA CERRADO, Y NO HAY INTERRUPTOR PARA APAGARLO
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Una entrada de la tienda **sin `sha256` no se instala**. Es deliberado y hay que
 * defenderlo cuando alguien proponga "un switch para saltearlo mientras tanto":
 *
 * A Bloq tiene este mismo código escrito y **comentado**, con un
 * `return true // TEMPORARILY BYPASS` adentro (ver B.31). O sea que alguien lo escribió
 * bien, lo apagó "por un rato" y quedó así: una verificación que parece existir y no
 * existe. **Es peor que no tenerla**, porque nadie vuelve a mirarla. B.31 dejó anotado
 * textual: *"Copiar la forma, no el estado"*.
 *
 * El costo de fallar cerrado es que una entrada vieja sin `sha256` deja de instalarse, y
 * eso se arregla en un clic: el panel calcula el hash solo al cargar la app, y para las
 * entradas viejas tiene un botón que lo recalcula. El mensaje de error del celular dice
 * exactamente eso, así que el administrador no tiene que adivinar.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * POR QUÉ LA AUTOACTUALIZACIÓN DE LOCKSUITE **NO** FALLA CERRADO
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Es la única asimetría de este archivo y conviene entenderla antes de "emparejarla":
 *
 *  · La autoactualización de LockSuite **ya tiene protección gratis**: Android exige la
 *    misma firma para reemplazar un paquete instalado, así que un APK falsificado se
 *    rechaza solo. B.6 lo dice desde el primer día.
 *  · Y el OTA es **la vía de rescate de un equipo**. Un `version.json` viejo sin `sha256`
 *    que impidiera actualizar dejaría equipos sin poder recibir el arreglo de lo que sea
 *    que esté roto. Eso es exactamente el criterio que B.16 usó para el arranque
 *    protegido: *"es preferible unos minutos sin filtrar a un ladrillo"*.
 *
 * Entonces: en el OTA el hash se verifica **si está publicado** y se registra si no lo
 * está. En la Tienda es obligatorio. Las dos decisiones son correctas para su caso.
 */
object ApkChecksum {

    private const val TAG = "ApkChecksum"

    /** 64 caracteres hexadecimales, sin distinguir mayúsculas. */
    private val HEX_64 = Regex("^[0-9a-fA-F]{64}$")

    sealed class Result {
        /** El hash coincide. */
        object Match : Result()

        /** El hash NO coincide: el archivo no es el que el administrador publicó. */
        data class Mismatch(val expected: String, val actual: String) : Result()

        /** No se publicó ningún hash para esta entrada. */
        object NotPublished : Result()

        /** El hash publicado no tiene forma de sha256, o no se pudo leer el archivo. */
        data class Unusable(val reason: String) : Result()
    }

    /**
     * sha256 del archivo, en hexadecimal minúscula, o `null` si no se pudo leer.
     *
     * Se lee de a bloques y nunca entero en memoria: un APK puede pesar decenas de MB y
     * esto corre en equipos de 2 GB de RAM (el CAT S22 Flip de B.41 es el piso de la
     * flota). Cargarlo entero sería un `OutOfMemoryError` justo en el equipo más chico.
     */
    fun sha256Of(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo calcular el sha256 de ${file.name}: ${e.message}", e)
            null
        }
    }

    /**
     * Compara el archivo contra el hash publicado.
     *
     * @param expected el `sha256` de la entrada, o `null`/vacío si no se publicó ninguno.
     */
    fun verify(file: File, expected: String?): Result {
        if (expected.isNullOrBlank()) return Result.NotPublished
        val clean = expected.trim()
        // Un valor con forma rara se trata como INUTILIZABLE, no como "no publicado":
        // la diferencia importa porque quien llama falla cerrado ante `Unusable` y
        // podría no hacerlo ante `NotPublished`. Un hash mal pegado (con espacios, con
        // el nombre del archivo al lado, cortado a la mitad) es un error del
        // administrador, y tiene que verse como error y no como ausencia.
        if (!HEX_64.matches(clean)) {
            return Result.Unusable("el hash publicado no es un sha256 de 64 caracteres: '$clean'")
        }
        if (!file.exists() || file.length() == 0L) {
            return Result.Unusable("el archivo descargado no existe o está vacío")
        }
        val actual = sha256Of(file) ?: return Result.Unusable("no se pudo leer el archivo descargado")
        return if (actual.equals(clean, ignoreCase = true)) {
            Result.Match
        } else {
            Result.Mismatch(clean.lowercase(), actual)
        }
    }

    /**
     * La regla de la **Tienda administrada**: se instala solo si el hash coincide.
     *
     * @return `null` si se puede instalar, o el mensaje de error a mostrar y a reportar.
     */
    fun blockStoreInstall(file: File, expected: String?, label: String): String? {
        return when (val r = verify(file, expected)) {
            Result.Match -> {
                Log.i(TAG, "sha256 de $label verificado correctamente.")
                null
            }
            Result.NotPublished ->
                "\"$label\" no tiene sha256 publicado en la tienda, así que no se puede " +
                    "verificar que el archivo descargado sea el que el administrador subió. " +
                    "No se instaló. Agregá el sha256 desde el panel (Ajustes → Tienda) y volvé a intentar."
            is Result.Unusable -> {
                Log.e(TAG, "sha256 inutilizable para $label: ${r.reason}")
                "No se pudo verificar el archivo de \"$label\" (${r.reason}). No se instaló."
            }
            is Result.Mismatch -> {
                // Esto no es un error de red: una descarga cortada da un archivo más
                // chico y también cae acá, pero un archivo entero con otro hash es
                // contenido distinto del publicado. Se registra completo porque es el
                // único rastro que va a quedar.
                Log.e(TAG, "sha256 DISTINTO para $label: esperado=${r.expected} obtenido=${r.actual}")
                "El archivo descargado de \"$label\" NO es el que el administrador publicó " +
                    "(la huella no coincide). No se instaló."
            }
        }
    }

    /**
     * La regla de la **autoactualización de LockSuite**: se verifica si hay hash, y si no
     * hay se sigue igual. El porqué está en el comentario de cabecera de este archivo —
     * en dos palabras, acá Android ya exige la misma firma y el OTA es la vía de rescate.
     *
     * @return `null` si se puede instalar, o el mensaje de error.
     */
    fun blockSelfUpdate(file: File, expected: String?): String? {
        return when (val r = verify(file, expected)) {
            Result.Match -> {
                Log.i(TAG, "sha256 de la actualización de LockSuite verificado correctamente.")
                null
            }
            Result.NotPublished -> {
                Log.w(TAG, "version.json no publica sha256: se instala igual (Android exige la misma firma).")
                null
            }
            is Result.Unusable -> {
                Log.w(TAG, "sha256 inutilizable en version.json (${r.reason}): se instala igual.")
                null
            }
            is Result.Mismatch -> {
                Log.e(TAG, "sha256 DISTINTO en la actualización de LockSuite: esperado=${r.expected} obtenido=${r.actual}")
                "El archivo de actualización no coincide con el publicado. No se instaló."
            }
        }
    }
}
