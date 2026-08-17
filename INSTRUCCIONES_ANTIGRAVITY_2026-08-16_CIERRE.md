# Handoff a Antigravity — 16/8/2026 (cierre del día)

**De:** sesión de Claude vía Cowork (solo herramientas de archivo; sin terminal:
bash/git/gradle estaban caídos toda la sesión — "VM service not running").
**Para:** Antigravity, con terminal real, que compila / prueba / commitea / despliega.

Leé primero `LOCKSUITE_CONTEXTO_PARA_IA.md` (sección C ya actualizada con esta
sesión, y B.13 / B.14 / B.15 con el estado real).

---

## 0. Lo más importante en tres frases

1. **Casi todo ya estaba aplicado en código** cuando esta sesión arrancó: el
   cableado de la actualización por Play Store (B.14) y la protección de
   accesibilidad (B.15) ya estaban hechos (por vos, seguramente), y la versión ya
   estaba en **0.6.20 / versionCode 82** — no en 0.6.18/80 como decían las
   instrucciones viejas. Esta sesión lo **verificó línea por línea** y está OK.
2. **Esta sesión agregó un arreglo nuevo**: el bug del recuadro negro de imágenes
   que "tarda en enganchar y queda en su lugar al scrollear" (con captura del
   dueño). Se suscribió `TYPE_VIEW_SCROLLED` y se agregó un camino rápido de
   scroll en `LockSuiteAccessibilityService.kt`.
3. **Nada se compiló, probó ni commiteó** (no había terminal). Eso lo hacés vos.

---

## 1. Verificá el estado antes de tocar nada

```bash
git status
git log -1
cat app/build.gradle.kts | grep -E "versionCode|versionName"   # debería decir 83 / 0.6.21
```

Si `git status` muestra cambios sin commitear que no reconocés, puede haber otra
sesión en paralelo — revisá el diff antes de seguir.

---

## 2. Cambios de ESTA sesión (working tree, sin commitear)

| Archivo | Qué cambió | Por qué |
|---|---|---|
| `app/src/main/java/com/ejemplo/locksuite/service/LockSuiteAccessibilityService.kt` | (a) suscripción a `TYPE_VIEW_SCROLLED` en `onServiceConnected` y en el filtro de eventos; (b) camino rápido de scroll que reubica los recuadros de imágenes si ya hay overlays en pantalla, con `IMAGE_SCROLL_DEBOUNCE_MS = 50`; (c) `lastImageScrollAt`; (d) rama `SCROLLED`/`VIEW_SELECTED` en `toEventName()` | Sin el evento de scroll, los recuadros solo se movían en cada `CONTENT_CHANGED` (debounce 300 ms) o nada → se veían trabados y quedaban en el lugar al bajar. |
| `app/build.gradle.kts` | `versionCode 82 → 83`, `versionName 0.6.20 → 0.6.21` | Esta sesión agregó código nuevo; hay que publicar por encima de 0.6.20. **No bajar a 0.6.19/81: sería un downgrade y Android lo rechaza.** |
| `LOCKSUITE_CONTEXTO_PARA_IA.md` | B.13 (nuevo bug de scroll), B.14/B.15 (marcados "cableado/implementado, falta compilar/probar"), estado de versión, y sección C reemplazada | Mantener el contexto al día. |
| `INSTRUCCIONES_ANTIGRAVITY_2026-08-16_CIERRE.md` | este archivo | Handoff. |

`version.json` **NO se tocó** a propósito (sigue en 0.6.20/82): es el manifiesto
publicado y solo debe subir junto con el APK. Lo hace `deploy_all.ps1`.

---

## 3. Qué verificó esta sesión (no hay que rehacerlo, solo confiar/re-chequear al compilar)

- **Actualización (B.14):** `updateTickRunnable`/`startUpdateTicker`/`stopUpdateTicker`
  (cortado también en `onDestroy`); `scanAndAct()` reemplazó a `scanPlayStoreTree`/
  `StoreScan`/`isInMainRow` (borrados); APIs de `PlayButtonFinder.scan()` y
  `PlayUpdateSessionWatcher` coinciden con el uso; `setUninstallBlocked` puesto en
  `start()` y restaurado en `finish()`; **no** se levanta
  `DISALLOW_INSTALL_UNKNOWN_SOURCES` (solo `DISALLOW_INSTALL_APPS`); `debugLabels`
  llega al panel.
- **Protección de accesibilidad (B.15):** `PolicyManager.setPermittedAccessibilityServices`
  (apply/reapply/lift), `ContentObserver` en `WatchdogForegroundService` con reacción
  atada a `isAccessibilityProtectionEnabled()` y relanzamiento a 3 s, default-on en
  `onServiceConnected`, comandos `PROTECT/UNPROTECT_ACCESSIBILITY` (exigen PIN, fuera
  de `allowedWhileSuspended`), `accessibilityProtected` en panel + sync.
- **Caso reinicio:** ya cubierto por `BootReceiver` → `WatchdogForegroundService` →
  `BlockAccessibilityActivity` (pantalla completa, Atrás bloqueado, se auto-cierra al
  reactivar). No hizo falta código nuevo.
- **Panel:** `#update-flow-debug-row`/`-text` con `flow.debugLabels`, toggle de
  protección, `ALLOWED_COMMANDS`, y PIN requerido para PROTECT/UNPROTECT.

---

## 4. Compilar

```powershell
.\gradlew.bat compileDebugKotlin
```

Si falla, prestá atención a:
- `LockSuiteAccessibilityService.kt` — es donde esta sesión agregó código (scroll).
- Los dos archivos nuevos ya cableados (`PlayButtonFinder.kt`, `PlayUpdateSessionWatcher.kt`).
- Nada de esto pasó por un compilador todavía.

---

## 5. Probar en equipo real (orden que importa)

**B.8 primero** (Android 13 real), es el bloqueante de todo:

```bash
adb shell dumpsys accessibility | grep -E "installedServiceCount|Bound services"
```

Después:

1. **Imágenes / scroll (lo nuevo de esta sesión, con captura del dueño):** en una app
   con bloqueo de imágenes Capa 1, desplazar una lista larga con imágenes. Los
   recuadros negros tienen que **seguir el scroll pegados**, sin quedarse en el lugar
   y sin parpadear. Si van retrasados, bajar `IMAGE_SCROLL_DEBOUNCE_MS`; si el equipo
   se siente pesado en scroll, subirlo. Confirmar alineación vertical (ver §4.6 del
   informe de optimización: si el recuadro está corrido, sacar
   `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`).
2. **Mercado Pago:** que NO rebote en el inicio ni en un pago; que SÍ salga de
   ofertas/Mercado Puntos con un solo "atrás".
3. **WhatsApp:** Estados y Canales se bloquean y rebota a Chats, sin molestar en el resto.
4. **Actualización de apps** (checklist §5 de `INSTRUCCIONES_ANTIGRAVITY_2026-08-16_ACTUALIZACION_Y_ACCESIBILIDAD.md`):
   app con update pendiente en español / inglés / hebreo → se actualiza sola, el % avanza,
   Play Store se cierra y queda re-bloqueada; app ya al día → sale sola en ~3 s; Cancelar;
   con Accesibilidad apagada, "Actualizar apps" se niega a arrancar.
5. **Protección de accesibilidad:** intentar habilitar otro servicio → Android lo impide;
   apagar el de LockSuite → cartel a pantalla completa **al instante** (no a los 20 s);
   reiniciar el equipo sin accesibilidad → cartel a pantalla completa; desde el panel,
   apagar/encender la protección sin que salte nada.
6. **Suspensión (B.11)** y **evasión de Ajustes**.

---

## 6. Commit sugerido

Esta sesión no pudo commitear (sin terminal). Cuando confirmes que compila (y,
idealmente, después de probar), commiteá. Mensaje sugerido:

```bash
git add app/src/main/java/com/ejemplo/locksuite/service/LockSuiteAccessibilityService.kt \
        app/build.gradle.kts \
        LOCKSUITE_CONTEXTO_PARA_IA.md \
        INSTRUCCIONES_ANTIGRAVITY_2026-08-16_CIERRE.md

git commit -m "Capa 3: recuadros de imágenes siguen el scroll (TYPE_VIEW_SCROLLED + camino rápido)

- Suscribe TYPE_VIEW_SCROLLED y agrega un camino rápido en onAccessibilityEvent que
  reubica los overlays de imágenes ~20 fps (IMAGE_SCROLL_DEBOUNCE_MS=50) solo si ya hay
  recuadros en pantalla; si no, descarta el evento con un chequeo O(1). Arregla el bug
  reportado por el dueño: el recuadro negro tardaba en enganchar y quedaba en el lugar
  al scrollear, porque solo se movía en cada CONTENT_CHANGED (debounce 300 ms).
- Sube versionCode 82->83 / versionName 0.6.20->0.6.21 (por encima de lo publicado;
  0.6.19/81 habría sido downgrade).
- Verificado el cableado ya existente de B.14 (actualización Play Store) y B.15
  (protección de accesibilidad); documentado en LOCKSUITE_CONTEXTO_PARA_IA.md."
```

> Si preferís separar el bump de versión, hacelo — pero acordate de que `deploy_all.ps1`
> hace `git add .` y commitea TODO lo que esté sin commitear.

---

## 7. Publicar (cuando pase las pruebas)

```powershell
.\deploy_all.ps1 -VersionName "0.6.21"
```

Sube el versionCode solo (currentCode + 1), compila `assembleRelease`, copia el APK,
sincroniza `version.json` con el APK, despliega y pushea. Recordá el orden del panel
si lo hacés a mano: **`database` → `functions` → `hosting`** (hay comandos nuevos en
`ALLOWED_COMMANDS`; sin `functions` desplegado, los botones nuevos devuelven "Comando
no reconocido").

---

## 8. Para decirle al dueño (tal cual)

- El progreso de la actualización ahora se ve por número real de descarga
  (`PackageInstaller`, funciona en cualquier idioma), y las apps ya actualizadas
  salen solas en pocos segundos en vez de trabarse — **falta confirmarlo en el
  celular**.
- El recuadro negro de imágenes ahora sigue el scroll; **hay que probarlo** y avisar
  si todavía se ve retrasado o corrido, que se ajusta con una perilla.
- **Android no permite impedir que se desactive un servicio de accesibilidad** (no
  existe la API). Lo que se hizo es lo más cerca posible: no deja habilitar otros
  servicios de accesibilidad, detecta al instante si se apaga el de LockSuite, y
  muestra un cartel a pantalla completa (incluido después de reiniciar) que no deja
  usar el equipo hasta reactivarla. En Samsung con Knox licenciado sí se podría
  ocultar la opción del todo (pendiente, va en `KnoxHardening.kt`).
