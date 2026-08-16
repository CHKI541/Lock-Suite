# Guía de despliegue — LockSuite Kosher (panel de administración remota)

Este documento está escrito para que lo ejecute un agente de IA con acceso a
terminal (Claude Code u otro) en la computadora del usuario. Incluye los
datos del proyecto real para no tener que volver a pedirlos.

**Contexto que el agente debe saber antes de arrancar:**
- Proyecto de Firebase: `looksuite-41866`
- Cuenta de Google dueña del proyecto: `locksuite2@gmail.com`
- Carpeta del backend en este repo: `admin-backend/`
- El archivo `admin-backend/public/firebase-config.js` ya tiene la
  configuración real cargada — no hace falta tocarlo.
- El archivo `admin-backend/.firebaserc` ya apunta al proyecto correcto.

---

## Paso 0 — Verificar prerrequisitos

```bash
node --version   # necesita ser 20 o superior
npm --version
```

Si no hay Node 20+, instalarlo antes de continuar (nvm, o el instalador
oficial de nodejs.org).

## Paso 1 — Instalar Firebase CLI

```bash
npm install -g firebase-tools
firebase --version
```

## Paso 2 — Iniciar sesión

```bash
firebase login
```

Esto abre una ventana de navegador para autenticarse. **El humano tiene que
completar el login con la cuenta `locksuite2@gmail.com`** — este paso no se
puede automatizar sin intervención humana (es el flujo OAuth de Google). Si
el agente corre en un entorno sin navegador disponible, usar en su lugar:

```bash
firebase login --no-localhost
```

y seguir las instrucciones que imprime en pantalla (copiar una URL, abrirla
en cualquier navegador, pegar el código que devuelve).

## Paso 3 — Pasos manuales en Firebase Console (NO automatizables)

Estos tres pasos requieren que un humano haga clicks en la consola web —
ningún comando de CLI los reemplaza:

1. **Plan Blaze** (pago por uso, lo pide Cloud Functions):
   `https://console.firebase.google.com/project/looksuite-41866/usage/details`
   → "Modificar plan" → Blaze. El volumen esperado de este proyecto cae
   dentro de la capa gratuita de Blaze (costo esperado: $0/mes).

2. **Habilitar proveedores de inicio de sesión**:
   `https://console.firebase.google.com/project/looksuite-41866/authentication/providers`
   → Habilitar **Email/Password** y **Anonymous**.

3. **Crear la cuenta de administrador del panel**:
   `https://console.firebase.google.com/project/looksuite-41866/authentication/users`
   → "Add user" → email y contraseña a elección (puede ser
   `locksuite2@gmail.com` con una contraseña nueva, distinta a la de Gmail —
   es un sistema de credenciales separado). Esta es la cuenta con la que se
   inicia sesión en el panel web, no la cuenta de Google del proyecto.

Si el agente tiene forma de confirmar con el humano que ya completó estos
tres pasos antes de seguir, mejor — el resto falla si no están hechos.

## Paso 4 — Instalar dependencias y desplegar

Desde la raíz del repo:

```bash
cd admin-backend/functions
npm install
cd ..
firebase deploy --only functions,hosting,database
```

Si tira un error de permisos o de proyecto no encontrado, correr primero:

```bash
firebase use looksuite-41866
```

Al terminar sin errores, va a imprimir una URL de Hosting. Debería ser:

```
https://looksuite-41866.web.app
```

(también funciona `https://looksuite-41866.firebaseapp.com`, son alias del
mismo sitio).

## Paso 5 — Lado Android

Agregar al `build.gradle` del módulo `app`, si no está ya:

```gradle
implementation("androidx.security:security-crypto:1.1.0-alpha06")
implementation("com.google.firebase:firebase-auth-ktx")
```

Compilar e instalar la app actualizada en los dispositivos gestionados —
sin esto, el celular no va a poder autenticarse de forma anónima y las
reglas de la base de datos van a rechazar sus escrituras (token FCM, PIN
hash, estado de políticas).

## Paso 6 — Verificación

1. Abrir `https://looksuite-41866.web.app` en un navegador.
2. Iniciar sesión con la cuenta creada en el Paso 3.3.
3. Si algún celular con la app ya instalada tuvo conexión a internet al
   menos una vez, debería aparecer una tarjeta para él (aunque no tenga
   nada configurado todavía).
4. Probar un comando cualquiera (por ejemplo, tildar "Wi-Fi bloqueado") —
   la primera vez para cada celular va a pedir el PIN de administrador
   configurado en ese equipo.

## Si algo falla

```bash
firebase deploy --only functions,hosting,database --debug
```

y revisar el log — los errores más comunes son (a) plan Blaze no
activado todavía, (b) `npm install` no corrido dentro de `functions/`,
(c) sesión de `firebase login` vencida (correr `firebase login` de nuevo).
