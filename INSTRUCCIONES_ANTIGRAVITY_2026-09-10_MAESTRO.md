# MAESTRO — Antigravity, empezá por acá. 10/9/2026

**Este documento reemplaza a los otros dos como punto de entrada.** Los otros dos siguen
siendo la referencia de detalle de cada tanda; acá está el mapa completo, el orden, y qué
tiene que hacer Antigravity que ninguna de las dos sesiones de Claude pudo hacer.

---

## 0. LA SITUACIÓN EN UNA PANTALLA

Hubo **dos sesiones de Claude** el 10/9, sobre el mismo árbol, sin pisarse. **Ninguna de
las dos pudo commitear en el disco**: `device_bash` no monta la carpeta desde hace diez
sesiones seguidas y sin él no hay `git`. Las dos entregaron **parches**.

**Verificado hoy contra el disco: NO hay nada aplicado todavía.** El repo sigue en
**0.6.48 / código 111** (`199a2de`), y en `app/…/mdm/` no existen ni `GracePeriodManager.kt`,
ni `ApkChecksum.kt`, ni `AppRequestManager.kt`, ni `EmbeddedBrowserDetector.kt`.

| Tanda | Qué trae | Archivo | Estado |
|---|---|---|---|
| **1ª — B.58** | Cierra **B.6** (checksum obligatorio de APK) + **Nivel 4 con período de gracia** + selector de celular en los perfiles | `Claude outputs\B58_tienda_y_gracia.patch` | sin aplicar |
| **2ª — B.59/B.60/B.61** | **Pedir una app** desde el celular · **Detector de navegadores embebidos** (cierra B.44) · **Alta por QR** | `Claude outputs\B59_B61_solicitudes_iab_qr.patch` | sin aplicar |

**El orden importa y no es negociable:** los cinco commits de la segunda tanda tienen al
commit de la primera como padre. Si aplicás la segunda sin la primera, `git am` falla.

**Lo que ninguna sesión de Claude pudo hacer, y por eso existe este documento:** compilar
con Gradle, desplegar a Firebase, probar en un equipo real, y commitear/pushear en tu PC.
Todo lo demás está hecho y verificado hasta donde se puede verificar sin equipo.

---

## 1. QUÉ HIZO CADA SESIÓN, EN CRIOLLO

### 1ª tanda (B.58) — la madrugada del 10/9

- **Cerró B.6, que era el agujero más grande que quedaba abierto y estaba desde el
  principio.** La Tienda administrada bajaba un APK de una URL y lo instalaba **en
  silencio, como Device Owner, sin verificar absolutamente nada**. `ApkSignatureVerifier`
  (B.37) no podía cubrirlo porque compara contra el paquete **ya instalado** y la Tienda
  hace primeras instalaciones. Ahora el `sha256` se publica en `storeApps` y se compara
  contra el archivo en disco **antes** de abrir la sesión de instalación. **Falla cerrado
  y no hay interruptor para saltearlo**, por la lección de B.31.
- **Agregó el Nivel 4, período de gracia**, que pediste vos: bloquea lo claramente no
  kosher desde el minuto cero, deja lo dudoso abierto por el plazo que elijas, y al vencer
  aplica el Nivel 1 solo. Lo valioso no es el temporizador: durante el plazo la auditoría
  de la lista blanca anota qué dominios usa esa persona, **así que el período de gracia es
  lo que llena el catálogo** y permite cerrar con datos en vez de a ciegas. El vencimiento
  lo sostienen **dos relojes** —el de pared y un acumulador que nadie puede mover— así que
  atrasar la hora no lo evade.
- **Arregló un defecto de diseño de B.57** que habías reportado: aplicar un perfil abría un
  `prompt()` pidiendo pegar el `ANDROID_ID`. Ahora hay un desplegable con la flota.

Detalle completo, con el porqué de cada decisión: **`INSTRUCCIONES_ANTIGRAVITY_2026-09-10_TIENDA_Y_GRACIA.md`**.

### 2ª tanda (B.59/B.60/B.61) — la tarde y la noche del 10/9

- **B.59 — pedir una app desde el celular.** El botón **"Bloqueada"** de la Tienda deja de
  existir como estado final: ahora dice **"Pedir"**. Antes, si no habías adivinado de
  antemano qué app iba a necesitar esa persona, el único canal era que te llamara y te
  dictara el nombre del paquete. Pestaña nueva **🔔 Pedidos** en el panel, con el contador
  de pendientes de toda la flota y **Aprobar / Rechazar**.
- **B.60 — detector de navegadores embebidos.** Cierra **B.44 de raíz**, y **sin la lista
  blanca de WebView que rechazaste el 4/9**. En vez de una lista de apps conocidas, detecta
  por la **forma de la pantalla** —si hay una barra de direcciones o los controles de un
  navegador—, así que funciona con apps en las que nadie pensó. **Viene apagado y en
  simulación**, con dos interruptores separados.
- **B.61 — alta por QR.** Dar de alta un equipo deja de necesitar una PC con ADB, drivers y
  depuración USB: se escanea un código y listo. El panel lo dibuja con un codificador
  propio (`admin-backend/public/qr.js`), porque `:admin-app` no dejaría cargar uno de un
  CDN.

Detalle completo: **`INSTRUCCIONES_ANTIGRAVITY_2026-09-10_SOLICITUDES_IAB_QR.md`**.

---

## 2. PASO A PASO, DE PRINCIPIO A FIN

### Paso 1 — aplicar las dos tandas, en orden

```powershell
cd "E:\Documentos\Lock Suite segunda version"

git log -1        # tiene que decir 0.6.48 / codigo 111  (199a2de)
git status        # tiene que estar limpio

git am "Claude outputs\B58_tienda_y_gracia.patch"
git am "Claude outputs\B59_B61_solicitudes_iab_qr.patch"

git log --oneline -7    # 1 commit de la 1ra tanda + 5 de la 2da, encima de 199a2de
```

Si el segundo `git am` falla, es porque el primero no se aplicó. `git am --abort` y volvé.

**Los mensajes de commit ya vienen adentro de los parches**, escritos y completos. No hay
que redactar ninguno.

### Paso 2 — los tres chequeos de simetría, los tres en verde

```powershell
python tools\check_profile_sync.py
python tools\check_whitelist_sync.py
python tools\check_command_sync.py     # nuevo: lo trae B.59
```

Si alguno sale en rojo, **parar acá**. Están escritos justamente para atajar la familia de
bugs que este proyecto ya pagó cuatro veces (`no_apps_control`, B.28): una clave que un
lado escribe y el otro no lee **no da error en ningún lado**, se acepta en silencio y la
protección nunca se aplica.

### Paso 3 — compilar

```powershell
.\gradlew compileReleaseKotlin
```

**Lo único que no se pudo type-checkear desde el contenedor es Compose** (`LoginActivity.kt`
y `DashboardActivity.kt`). Si algo no compila, lo más probable es que esté ahí. Todo el
resto pasó por `kotlinc` 2.0.21 contra stubs, con el código extraído de los archivos reales.

### Paso 4 — limpiar antes de desplegar

`deploy_all.ps1` hace `git add .`, así que commitea junto todo lo que esté sin commitear.

- ⚠️ **Sigue pendiente desde el 6/9:** sacar `scratch\diag_red_2026-09-06*.txt` del árbol o
  confirmar que `scratch/` esté en `.gitignore`. Son volcados de red de un celular real y
  hoy irían a un **repo público** (B.32).

### Paso 5 — desplegar

```powershell
.\deploy_all.ps1 -VersionName "0.6.49"
```

Al abrir el panel después: **Ctrl+F5** (el cache-buster de `app.js` pasó a `v=38` y hay un
`qr.js` nuevo).

### Paso 6 — ⚠️ LO PRIMERO DESPUÉS DE DESPLEGAR, ANTES DE CUALQUIER PRUEBA

**Panel → Ajustes → Tienda → "Calcular huella" en cada tarjeta roja.** Un clic por app.

Las entradas de `storeApps` que ya existen no tienen `sha256`, así que **los celulares
dejan de instalarlas**. Es lo correcto —es exactamente el agujero que B.6 cierra— pero si
no lo hacés el mismo día parece que se rompió la Tienda, y vas a perder media hora
buscando en el lugar equivocado.

### Paso 7 — probar en equipo real

El orden completo está en los dos documentos de detalle. Acá va **el orden consolidado**,
que es distinto de leer los dos por separado: las regresiones de las dos tandas van todas
juntas al principio, porque si algo de eso se rompió no importa nada de lo demás.

---

## 3. ORDEN DE PRUEBA CONSOLIDADO

### Bloque A — las regresiones. Van primero y no son opcionales.

1. **La Tienda sigue andando.** Después de calcular las huellas (paso 6), instalar una app
   desde el celular. Tiene que funcionar igual que antes.
2. **Un perfil maestro se sigue aplicando entero.** Aplicá el Nivel 1 desde el panel a un
   equipo ya dado de alta. Tiene que aplicar **todo**, incluidas las cuentas y el idioma.
   El recorte de `POST_ALTA` que trae B.61 es **solo** para el camino del QR — si se coló
   en el camino normal, es un equipo menos protegido y no se nota.
3. **★ Con el detector de navegadores APAGADO, que no se haya roto nada.** Usá el equipo
   media hora normal: WhatsApp, Waze, Mercado Pago, Ajustes, el Wi-Fi. El detector está en
   el camino caliente de la Capa 3, y ese camino ya costó una sesión entera en B.13. **Si
   el equipo se siente más lento, pará y avisá.**

### Bloque B — B.6, el checksum (1ª tanda)

4. **Una entrada SIN huella no se instala.** Sacale el `sha256` a una entrada desde la
   consola de Firebase: en el celular tiene que aparecer deshabilitada con *"⚠ Sin
   verificación"*.
5. **Una huella que NO coincide no se instala.** Poné un `sha256` inventado: el celular
   tiene que bajar el APK y **rechazarlo**.

### Bloque C — pedidos de apps (B.59)

6. Tocá **"Pedir"** en una app bloqueada → *"Pedido enviado al administrador"*, y el botón
   pasa a **"Pedida"**. Tocalo otra vez → *"Ya la pediste"*.
7. **"Pedir otra app…"** → `com.spotify.music` entra; `hola` tiene que decir *"Ese no es un
   nombre de paquete válido"* y **no escribir nada** en Firebase.
8. En el panel, **🔔 Pedidos** → **Aprobar** (pide el PIN del equipo). Tiene que hacer las
   cuatro cosas: sumar la app a la lista blanca, abrirle los dominios, des-suspenderla y
   des-ocultarla, y avisarle al celular.
9. **★ Al celular tiene que llegar una notificación** *"✅ … : aprobada"*. Es lo que evita
   que el usuario tenga que estar abriendo la Tienda a probar suerte.
10. ⚠️ Si después aparece permitida pero **no está en la tienda**, es lo esperado: aprobar
    **no sube el APK**. Cargala en Ajustes → Tienda con su URL y calculale la huella.
11. **Rechazar** otro pedido → notificación *"❌ … : rechazada"*, y en el celular no deja
    volver a pedirla hasta mañana.
12. **Con el celular apagado**, aprobá un pedido: el panel tiene que decir *"aprobada y
    guardada, pero el celular no contestó a: …"*. **No puede decir que salió todo bien.**

### Bloque D — período de gracia (1ª tanda)

13. **Nivel 4 con plazo de 2 HORAS, no 2 días**, en un equipo de descarte. Confirmar: la
    tienda queda abierta, el lanzador es el normal, y WhatsApp Estados **sigue bloqueado**.
14. **Que cierre solo** al vencer, y que el panel muestre el Nivel 1 aplicado.
15. **★ La prueba del reloj.** Con la gracia activa, atrasá la hora del equipo un año (hay
    que apagar `DISALLOW_CONFIG_DATE_TIME` a mano). Tiene que cerrarse igual al cumplirse
    el tiempo de uso, y el panel decir *"vencido (tiempo de uso; el reloj no llegó…)"*.
16. **Reiniciar a mitad** del período: el tiempo que faltaba no se reinicia.
17. **Cancelar el vencimiento** desde el panel: el equipo se queda como está.

### Bloque E — detector de navegadores embebidos (B.60)

18. Encendelo desde el panel (Lista blanca → *Detector de navegadores embebidos*). **Sigue
    en simulación: no bloquea nada.**
19. Usá el equipo **un par de días** normalmente. La lista se llena sola (la publica el
    `WatchdogWorker`, así que tarda hasta 15 minutos en aparecer).
20. **★ Mirá esa lista antes de tocar nada más.** Es lo que se va a empezar a bloquear. Si
    hay ahí una app que el usuario necesita, **no pases a bloquear**: avisá.
21. **Las tres exclusiones, a mano, con el detector encendido:**
    - **Portal cautivo** — conectate a un Wi-Fi con login. La página tiene que abrir y
      dejarte entrar. *Esto ya rompió el equipo una vez (B.50).*
    - **Alta de cuenta de Google** — Ajustes → Agregar cuenta → Google, hasta el final.
      *Esto ya rompió el equipo una vez (B.43).*
    - **`:admin-app`** — la app de administración tiene que funcionar entera.
22. Recién ahí, **"Pasar a bloquear de verdad"**, y repetir la 21 completa.
23. Si algo se rompe: **"Volver a simulación"** deja de bloquear al instante.

### Bloque F — alta por QR (B.61). Necesita un equipo de descarte reseteado de fábrica.

24. Panel → Lista blanca → *Alta de un equipo nuevo por QR* → Nivel 1 → **Generar datos del
    QR**. Tiene que dibujarse el código (versión 17, 85×85).
    Si dice que no hay huella de firma, es que ningún equipo publicó todavía
    `signatureChecksum`: esperá un ciclo del Watchdog en cualquier equipo ya actualizado.
25. **★ Escanealo en el equipo reseteado** (seis toques en la pantalla de bienvenida).
    **Esta es la única prueba que no se pudo hacer desde el contenedor**: que el código sea
    correcto está verificado contra un decodificador real, pero que **Android lo acepte como
    aprovisionamiento** solo lo dice un equipo.
26. **★ Cuando termine, TIENE QUE PODERSE AGREGAR LA CUENTA DE GOOGLE Y CAMBIAR EL IDIOMA.**
    Es el punto entero de la función. Si no se puede, el recorte de `POST_ALTA` no funcionó
    y el equipo quedó inservible para el alta.
27. El panel lo muestra en **"Terminar un alta"**. Agregá la cuenta, dejá el idioma
    definitivo, tocá **"Terminar alta"**, y confirmá que **ahora sí** no se pueden agregar
    cuentas ni cambiar el idioma, y que el equipo desaparece de esa lista.

---

## 4. LO QUE HACE FALTA QUE HAGAS VOS, Y NINGUNA IA PUDO

1. **Compilar.** No hay Android SDK ni Gradle en el contenedor.
2. **Desplegar.** No hay credenciales de Firebase.
3. **Probar en equipo real.** Todo el bloque 3 de arriba.
4. **Commitear y pushear.** Los parches traen los commits armados, pero el `git` que cuenta
   es el de la PC. Ninguna sesión de Claude tiene credenciales de
   `github.com/CHKI541/Lock-Suite`, y no correspondería que las tuviera.
5. **Calcular las huellas de la tienda** (paso 6). Es un clic por app y no se puede
   automatizar desde afuera: el panel las calcula en el navegador.
6. **La prueba 25**, que es la única afirmación de todo esto que no tiene ninguna
   verificación detrás salvo un equipo real.

---

## 5. QUÉ SE VERIFICÓ SIN EQUIPO, PARA QUE SEPAS CUÁNTO PESA CADA COSA

| | 1ª tanda (B.58) | 2ª tanda (B.59-B.61) |
|---|---|---|
| Aserciones de comportamiento | 183, en 3 bancos | 185, en 2 bancos que compilan **los archivos reales** |
| Controles negativos | 13, los 13 detectados | 23 en los bancos (21 detectados, 2 redundantes) + 13 en el type-check, los 13 detectados |
| Type-check `kotlinc` 2.0.21 | 0 errores / 0 warnings, 4 controles negativos | 0 / 0 en dos bancos, 13 controles negativos |
| Chequeos de simetría | 2 en verde | 3 en verde (uno nuevo) |
| Extra | — | **QR: 84 casos idénticos módulo por módulo sobre las 40 versiones, 10 controles negativos, y el payload decodificado con OpenCV devolviendo los 608 bytes exactos** |

**Ninguna de las dos compiló con Gradle ni probó en un equipo.** Eso sigue siendo tuyo.

Dos cosas que vale la pena que sepas porque cambian cuánto confiar en el resto:

- **Un banco encontró un bug real antes de que llegara a un equipo.** El detector de
  navegadores contaba *palabras* de navegación en vez de *funciones*, así que cualquier app
  con `SwipeRefreshLayout` y un botón "recargar" quedaba clasificada como navegador y se
  bloqueaba. Son el mismo control en dos idiomas.
- **Dos controles negativos encontraron dos casos flojos del propio banco**, que decían
  afirmar algo y no lo afirmaban. Por eso los controles negativos no son un trámite.

---

## 6. LO QUE QUEDA ABIERTO DESPUÉS DE TODO ESTO

**De la 1ª tanda:**
- El período de gracia **no avisa cuando está por vencer**. Hoy hay que entrar al panel.
- **No hay gracia por grupo** (misma razón que B.12 y B.53).

**De la 2ª tanda:**
- **No hay pedidos de apps por grupo** (misma razón).
- El aviso de "tu pedido fue aprobado" depende de que el equipo esté en línea en ese
  momento; si no, le llega al reconectar.

**De antes, y sigue igual:**
- **B.2 — el repo de GitHub es público.** Sigue siendo la recomendación de mayor impacto y
  menor esfuerzo de toda la lista. Y ojo: el día que pase a privado, **la receta que usan
  las sesiones de Claude para leer el código deja de funcionar** (clonan el repo público),
  así que hay que agregarles la carpeta `app\src\main\java` desde "Add folder".
- **B.5 — la clave HMAC de los presets sigue en texto plano y pública.**
- **B.7 — `UPDATE_APP` y `UPDATE_LOCKSUITE` no piden PIN.**
- **B.32 — los volcados de red en `scratch/`** (ver paso 4).
- **PROMPT D (DNS por categorías) sigue sin hacerse, y a propósito.** Las tres objeciones
  medidas están en `PROMPTS_PARA_OTRAS_CONVERSACIONES_2026-09-09.md`. La más grave:
  `NetworkForwarder` reintenta contra `8.8.8.8`/`1.1.1.1` ante cualquier `IOException`, así
  que si el resolutor de arriba pasara a ser el que filtra, **cada timeout dejaría el
  equipo sin filtrar, en silencio**.

---

## 7. SI ALGO SALE MAL

- **`git am` falla** → `git am --abort`, revisá el orden (primero B.58) y que `git status`
  esté limpio.
- **No compila** → mirá primero `LoginActivity.kt` y `DashboardActivity.kt`: Compose es lo
  único que no se pudo type-checkear.
- **Un chequeo de simetría sale en rojo** → no es un falso positivo hasta que se demuestre.
  Cada uno dice en su salida qué clave está desalineada y qué pasa si se ignora.
- **La Tienda "dejó de andar"** → casi seguro son las huellas del paso 6.
- **Una app dejó de funcionar de la nada** → si el detector de navegadores está en modo
  bloqueo, "Volver a simulación" lo desactiva al instante. Es la primera hipótesis a
  descartar.
- **El equipo dado de alta por QR no deja agregar la cuenta** → el recorte de `POST_ALTA`
  no funcionó. Es el defecto más caro de esta tanda: avisá antes de dar de alta más equipos.
