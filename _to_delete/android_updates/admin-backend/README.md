# Panel de administración remota — LockSuite Kosher

Panel web + Cloud Functions para mandar comandos a los celulares gestionados
(bloquear ahora, bloquear/desbloquear Wi-Fi, Bluetooth, VPN, instalación de
apps, y actualizar la lista de apps permitidas del modo kiosco).

> **¿Vas a desplegar esto con ayuda de un agente de IA con terminal (Claude
> Code, etc.)?** Dale directamente el archivo `DEPLOY_GUIDE_FOR_AI_AGENT.md`
> — ya tiene el project ID y los datos del proyecto cargados, pensado para
> seguirse paso a paso sin tener que volver a preguntar nada. Este README es
> la versión para vos.

## Qué vas a necesitar

- Una cuenta de Google con un proyecto de Firebase (podés usar el mismo
  proyecto que ya tiene la app de Android, o uno nuevo).
- El proyecto en el **plan Blaze** (pago por uso) — lo pide Cloud Functions.
  Para el volumen de uso de esto (unos pocos administradores, algunas
  decenas/cientos de dispositivos), el costo esperado es **$0/mes**: la capa
  gratuita de Blaze incluye 2 millones de invocaciones de Functions por mes.
- Node.js 20+ instalado en tu computadora.
- Firebase CLI: `npm install -g firebase-tools`

## Paso 1 — Habilitar servicios en Firebase Console

1. Andá a [console.firebase.google.com](https://console.firebase.google.com) → tu proyecto.
2. **Authentication → Sign-in method**: habilitá **Email/Password** (para vos, el
   administrador) y **Anonymous** (lo usa la app de Android para poder
   escribir su propio estado sin exponer la base de datos a cualquiera).
3. **Authentication → Users → Add user**: creá tu cuenta de administrador
   (email + contraseña). No hay pantalla de registro público en el panel a
   propósito — las cuentas se crean acá, a mano, una por una.
4. **Realtime Database**: si todavía no existe, creála (elegí la región).
5. **Project settings → General → Tus apps → Agregar app → Web**: registrá
   una app web (es gratis, no tiene nada que ver con la app de Android) y
   copiá el objeto de configuración que te muestra.

## Paso 2 — Configurar el panel

Pegá los valores del paso anterior en `public/firebase-config.js`,
reemplazando los placeholders (`TU_API_KEY`, etc.).

## Paso 3 — Instalar dependencias y desplegar

```bash
cd admin-backend
firebase login
firebase use --add        # elegí tu proyecto de la lista
cd functions
npm install
cd ..
firebase deploy --only functions,hosting,database
```

Al terminar te va a mostrar la URL del panel (algo como
`https://tu-proyecto.web.app`). Esa es la que abrís desde cualquier
navegador para administrar los dispositivos.

## Paso 4 — Lado Android

Agregá la dependencia de autenticación (si no la tenías ya) en
`app/build.gradle`:

```gradle
implementation("com.google.firebase:firebase-auth-ktx")
```

Sin esto, `FirebaseDeviceSync` no va a poder autenticarse de forma anónima y
las reglas de la base de datos (que exigen `auth != null`) van a rechazar la
escritura del token — el dispositivo dejaría de reportarse al panel.

## Cómo se usa

1. Abrí la URL del panel, iniciá sesión con la cuenta que creaste en el
   Paso 1.
2. Vas a ver una tarjeta por cada celular que alguna vez abrió la app
   (aparece apenas se registra, aunque no tenga nada configurado todavía).
3. "Bloquear ahora" fuerza la pantalla de bloqueo inmediatamente.
4. Los checkboxes de Wi-Fi/Bluetooth/VPN/Instalación reflejan el estado real
   reportado por el dispositivo — tildarlos/destildarlos manda el comando
   correspondiente.
5. El cuadro de texto de apps permitidas actualiza la lista blanca completa
   (reemplaza la anterior, no la suma) — separá los nombres de paquete con
   comas, ej.: `com.whatsapp, com.android.dialer`.

## PIN por dispositivo

La primera vez que intentás administrar un celular puntual (cualquier
acción: bloquear, tocar un toggle, actualizar la lista de apps), el panel
pide el PIN de administrador que se configuró en ESE celular durante
`SetupPinActivity`. Esto es intencional: sin esto, cualquier cuenta con
acceso al panel podría controlar cualquier dispositivo con solo saber que
existe.

- El PIN nunca se guarda en texto plano en ningún lado — el celular sube un
  hash (el mismo que ya usa localmente) y la Cloud Function compara hashes.
- Si tildás **"No volver a pedirlo en este dispositivo"**, esa cuenta de
  administrador queda marcada como confiable para ese celular puntual — no
  se vuelve a pedir el PIN en sesiones futuras, desde cualquier navegador,
  mientras uses la misma cuenta.
- Cada tarjeta muestra 🔒 (va a pedir el PIN) o 🔓 (ya recordado) según el
  estado. Con 🔓 aparece un enlace "Olvidar PIN" para revertirlo.
- Si el celular todavía no tiene un PIN configurado (recién instalado, sin
  pasar por `SetupPinActivity`), el panel deja administrar sin pedir nada
  — no hay contra qué verificar todavía.

## Notas de seguridad

- **No hay alta de usuarios pública a propósito.** Cualquiera que pueda
  crear una cuenta en el panel podría mandarle comandos a cualquier
  dispositivo gestionado. Creá cuentas solo para administradores de
  confianza, a mano, desde Firebase Console.
- Ahora mismo, cualquier administrador autenticado puede controlar
  **cualquier** dispositivo registrado (no hay un esquema de "este admin
  solo ve estos dispositivos"). Para un uso familiar/comunitario chico esto
  suele alcanzar; si más adelante hay varios administradores que no deberían
  ver los dispositivos de otros, avisame y lo agregamos (necesitaría una
  tabla de permisos por usuario).
- El token FCM de cada dispositivo nunca sale de la base de datos hacia el
  panel — `listDevices` explícitamente no lo incluye en la respuesta.
- Queda un registro (`commandLog/{deviceId}`) de qué administrador mandó
  qué comando y cuándo, por si hace falta auditar más adelante.
