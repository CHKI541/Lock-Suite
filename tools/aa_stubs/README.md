# Stubs de Android Auto (B.88)

Dos apps **vacías** que existen solo para que Android Auto deje de pedir "Descargá apps de
Google Play" y siga. Android Auto exige que estén instaladas Google Maps y la App de Google,
y "Salir" en esa pantalla cierra Android Auto. En un equipo kosher esas dos no están. La
navegación en el auto la hace **Waze (el oficial)**.

| Archivo | Paquete | SHA-256 del archivo |
|---|---|---|
| `apk/aa-stub-maps.apk` | `com.google.android.apps.maps` | `b0d1fc188e88e5a27b366f1d7160488d1b0ebe393ad78e3c69fda96c6692cbd3` |
| `apk/aa-stub-google.apk` | `com.google.android.googlequicksearchbox` | `d81901b6c544df6a2e16cc3e8c96cd83677225c5073eefb64ff2331666bb6152` |

Certificado de firma (los dos): `CN=LockSuite Android Auto stub, O=LockSuite`, SHA-256
`62786d1c3bfaa36cac1d71ec943227c2ace1f622c805c9affc44e39f81bf52de`.

## Qué tienen adentro (nada)

- `android:hasCode="false"` y **ningún `classes.dex`**: no pueden ejecutar código.
- **Sin activities, services, receivers ni providers**: no tienen ícono, no aparecen en ningún
  lanzador ni en la pantalla del auto, no se conectan a nada.
- `versionCode` 2100000000: Android Auto nunca pide "actualizar" y Play Store nunca los reemplaza
  (además la firma no coincide con la de Google).
- Etiqueta honesta: "Stub de Maps / de Google para Android Auto (LockSuite)". En Ajustes y en el
  panel se ven así, no como la app real.

Pesan 8,5 KB cada uno. Los de la comunidad (`aa4mg`, `SolidEva/android-auto-stub`,
`rik-shaw/aa-stubs`) son lo mismo —un `<application/>` vacío, medido con `aapt2` el 25/9—,
pero con código de relleno, y el de Maps está firmado con la **clave de prueba pública de
Android**, así que cualquiera podría publicarle una "actualización". Por eso se arman acá.

## La clave privada no existe

`build_stubs.sh` la genera en una carpeta temporal, firma, y la borra al salir. Nadie —ni
nosotros— puede firmar otra app con ese certificado, así que nadie puede hacerse pasar por un
stub ante LockSuite. **Si se vuelve a correr el script, sale una clave nueva:** hay que copiar las
huellas que imprime a `AndroidAutoStubs.kt` (`CERT_SHA256` y `APK_SHA256`), reemplazar los
archivos de `apk/`, subirlos a la Tienda con la huella nueva y, en los equipos que ya los
tenían, desinstalar los viejos antes de instalar los nuevos (la firma cambia).

## Cómo los trata LockSuite

`app/.../mdm/AndroidAutoStubs.kt`, desde 0.6.56:

- **Por firma, nunca por nombre.** Un paquete con uno de estos dos nombres es un stub solo si
  está firmado con el certificado de arriba. La Maps y la App de Google reales —y cualquier
  imitación— se tratan igual que siempre.
- **No se ocultan, no se suspenden, no se desinstalan.** `AppController.isCritical()` los
  cuenta como críticos, y todos los caminos que ocultan, suspenden o auto-desinstalan lo
  consultan (panel, reaplicación cada 15 min, `PackageReceiver` al instalar, suspensión de
  emergencia, bloqueo de apps no kosher). Si uno quedó oculto de antes, la reaplicación lo
  des-oculta.
- **La Tienda los deja instalar a cualquiera sin pedirlos**, reconociéndolos por la huella
  exacta del archivo. No se agregan a `allowedPackages` a propósito: esa lista es por nombre y
  permitiría también la app real.
- **Si en el equipo ya está la app real, la tarjeta de la Tienda lo dice** en vez de fallar con
  un error de firma: *instalada* (se desinstala primero: LockSuite → Aplicaciones, con "Bloquear
  desinstalación de apps" apagado un momento) o *de fábrica* (en ese equipo el stub no se puede
  instalar nunca: la copia del sistema manda).
- **Para sacarlos:** ADB (`adb uninstall <paquete>`) o Ajustes → Apps, con "Bloquear
  desinstalación de apps" apagado. LockSuite no los desinstala, ni desde su propio panel: cuentan
  como críticos.

## Verificar

```
python tools/aa_stubs/check_stubs.py     # huellas de los archivos == las del código, y vacíos
```

La huella del certificado la prueba `AndroidAutoStubsTest` contra el certificado real.

## Regenerar (solo si hace falta)

En el contenedor de una IA, con el SDK de `tools/ia_contenedor/setup_sdk.sh`:

```
bash tools/aa_stubs/build_stubs.sh
```
