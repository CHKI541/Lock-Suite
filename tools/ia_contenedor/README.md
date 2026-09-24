# Compilar y probar en el contenedor de una sesión de IA

Esto NO se usa en la PC del dueño (ahí está Android Studio y `deploy_all.ps1`). Sirve para
que una sesión de IA que trabaja en un contenedor Linux compile `:app` de verdad, corra
las pruebas unitarias y el lint, en vez de entregar Kotlin "type-checkeado a mano".

Medido el 22/9/2026: `compileDebugKotlin` + `testDebugUnitTest` tardan ~2 min con la
caché caliente (la primera vez más, porque baja las dependencias); `lintDebug`, unos
minutos más.

## Una sola vez por contenedor

```bash
bash tools/ia_contenedor/setup_sdk.sh      # SDK en /opt/android-sdk + espejo de Maven
```

`central-mirror.gradle` va a `~/.gradle/init.d/`: Maven Central contesta 429 desde la IP
de salida del contenedor, y el espejo oficial de Google en Cloud Storage no.

## Cada vez que cambia el código

```bash
python3 tools/ia_contenedor/sync_build_copy.py     # repo → /home/claude/build/ls
cd /home/claude/build/ls
bash ./gradlew --offline -q :app:compileDebugKotlin :app:testDebugUnitTest
bash ./gradlew --offline -q :app:lintDebug          # opcional, lento
```

(`--offline` solo después de la primera compilación, que baja las dependencias.)

Por qué se compila en una COPIA y no en el clon:

- el `gradle.properties` del repo fija `javax.net.ssl.trustStoreType=WINDOWS-ROOT`, que
  en Linux rompe el wrapper con un error de "trust store";
- hacen falta un `google-services.json` y un keystore, y los reales NO están en git (ni
  tienen que estar). La copia usa unos de mentira, que alcanzan para compilar y probar;
- `gradlew` no tiene permiso de ejecución en git: se corre con `bash ./gradlew`.

## Ojo con la memoria

El contenedor tiene ~8 GB. El daemon de Gradle (~3 GB) más el de Kotlin (~2 GB) dejan sin
memoria a todo lo demás: con ellos vivos, `import "firebase/database"` en Node se colgó
varios minutos. Antes de correr Node o el emulador de Firebase:

```bash
pkill -f "[G]radleDaemon"; pkill -f "[K]otlinCompileDaemon"
```

(Los corchetes evitan que `pkill -f` se mate a sí mismo.)

## Las reglas de la base

Ver `tools/rules_tests/README.md` (emulador real de Realtime Database; necesita Java).
