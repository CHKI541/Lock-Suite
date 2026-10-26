# tools/panel_render — el panel web probado SIN red y SIN Firebase

**(26/10/2026, B.95.)** Arma una copia de `admin-backend/public/` con un Firebase
**falso, en memoria** (`fake-firebase.js`) y una Cloud Function falsa
(`fake-function.js`, que escribe en la base falsa lo mismo que `sendCommandV8`: el ack
`sent` y la copia del buzón), la abre en Chromium con Playwright y **ejercita el canal
de comandos como lo usa el administrador**: manda comandos, simula que el celular los
aplica (o los rechaza, o no contesta) y mira qué dice el panel.

Es la receta de renderizado de B.65, convertida en prueba que se puede repetir.

## Cómo se corre

```bash
bash tools/panel_render/run.sh
```

Necesita `node` y el paquete `playwright` con su Chromium (en el contenedor de las
sesiones de IA ya están; en una PC: `npm i -g playwright && npx playwright install chromium`).
No necesita red ni credenciales: nunca toca el Firebase de verdad.

## Qué prueba

`test_panel.js` (barra lateral, `app.js`):

- El badge «Sin FCM» dice el motivo (`fcmTokenError`).
- La tarjeta «📡 Canal de comandos» aparece en un equipo sin FCM y se calla en uno sano.
- Un interruptor con un comando en el buzón muestra lo PEDIDO con «⏳ pendiente», y no
  "vuelve solo" al valor viejo.
- **A** — FCM salió (Function vieja, `queued:true` sin `fcmError`): el panel ESPERA el
  ack y muestra «✓ aplicado». *Con el app.js de B.94 esto decía "encolado, hasta 15 min"
  y deshacía el interruptor: es la regresión que motivó B.95.*
- **B** — equipo sin FCM ni escucha en vivo: avisa que tarda hasta 15 min, NO deshace, y
  cuando el celular lo aplica minutos después, lo dice.
- **C** — equipo sin FCM que escucha en vivo (`mailboxLive`): espera y muestra el ✓.
- **D** — un rechazo real (`BAD_SIGNATURE`) sí deshace el interruptor.
- **E** — FCM salió pero el celular no contesta en 10 s: pendiente, sin deshacer, y el
  ack tardío se muestra.
- **F** — autoactualización a un equipo sin FCM: no promete "se está actualizando".

`test_ficha.js` (ficha completa, `comun.js` + `celular.js`): el contrato de
`LS.sendCommand` (`applied` / `pendiente` / falla) y el «Canal de comandos» del Resumen.

## Control negativo

Copiar el `app.js` viejo (`git show <commit>:admin-backend/public/app.js`) a la carpeta
armada y volver a correr `node test_panel.js`: con el de B.94 (`e2b22f2`) fallan 18 de 20
(las dos que pasan son las que no dependen del cambio: la tarjeta que se calla y la
ausencia de errores de consola). Si una
prueba nueva no falla contra el código viejo, la prueba no está mirando lo que cree.

## Límites

El Firebase falso implementa solo lo que el panel usa (`ref/on/off/once/set/update/
remove/push`, `onAuthStateChanged`), notifica a los listeners por prefijo de ruta y
resuelve `ServerValue.TIMESTAMP`. No aplica reglas de seguridad: eso lo prueba
`tools/rules_tests/` contra el emulador real.
