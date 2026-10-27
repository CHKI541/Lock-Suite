/* ═══════════════════════════════════════════════════════════════════════════
 * celular.js — la ficha completa de UN celular (10/9/2026; rearmada el 26/10/2026, B.98).
 *
 * LA IDEA QUE ORDENA TODO, Y CONVIENE LEERLA ANTES DE TOCAR ALGO
 * ─────────────────────────────────────────────────────────────────────────
 * Una app está en uno de TRES estados, y ese estado decide las tres cosas a la
 * vez: si se ve y se puede abrir, si se puede bajar de la Tienda, y qué dominios
 * suyos resuelven. Eso ya lo hacía `WhitelistManager.setState()` en el celular
 * desde B.53 ("un solo toque"); lo que faltaba era que fuera **la** interfaz, por
 * equipo, en vez de una pestaña global escondida.
 *
 * "Lista negra" y "lista blanca" dejan de ser dos pantallas distintas: son la
 * MISMA lista, y lo único que cambia entre los modos es qué le pasa a las apps
 * SIN MARCAR. Eso es lo que saca de encima la sensación de "cosas en distintas
 * pestañas": no hay dos configuraciones que mantener en la cabeza, hay una sola
 * con un interruptor arriba.
 *
 * LAS TRES DECISIONES DE DISEÑO
 * ─────────────────────────────────────────────────────────────────────────
 * 1. **El carrito de cambios.** Cada interruptor del panel viejo es un comando
 *    FCM que espera hasta 10 s la confirmación del celular. Con 71 interruptores,
 *    configurar un equipo a mano son 71 esperas — literal el *"configurar un
 *    equipo desde cero lleva tiempo y paciencia"* que B.57 cita del dueño. Acá se
 *    marca todo lo que se quiera y se manda con un botón.
 *
 *    Y la parte honesta: **lo que se puede mandar junto de verdad se manda junto,
 *    y lo que no, no se disfraza.** Las decisiones de apps viajan por la base de
 *    datos (`devices/<id>/appPolicy`) y se cierran con UN solo `SYNC_WHITELIST`,
 *    así que 30 apps son 30 escrituras y 1 comando. Las políticas sí son un
 *    comando cada una —así está hecho el canal—, pero salen de un click y con una
 *    lista de progreso al lado, que es la diferencia entre esperar y volver más
 *    tarde. Ninguna de las dos cosas se marca como aplicada hasta que el celular
 *    lo confirma (B.42: nunca un ✓ sobre el silencio).
 *
 * 2. **Lo global se ve como global.** El catálogo de dominios y la Tienda son de
 *    toda la flota. Antes eso no se notaba —eran pestañas iguales a las demás— y
 *    tocar ahí le cambiaba la configuración a todos los equipos. Acá, lo que es
 *    global lleva la palabra "global" al lado y se edita en otra pantalla.
 *
 * 3. **No se toca `app.js` ni `index.html`.** Si algo de acá falla, el panel de
 *    siempre sigue entero. Era la condición del pedido y además es lo prudente.
 *
 * 26/10/2026 (B.98) — LO QUE SE AGREGÓ PARA QUE SEA COMPLETA
 * ─────────────────────────────────────────────────────────────────────────
 * El dueño: «en ficha completa faltan cosas que se pueden hacer solo desde vista
 * rápida». Estaban afuera: los controles por app (ocultar, suspender, web,
 * internet, imágenes, actualizar), Play Store (oculta/suspendida, ocultar íconos),
 * la lista de apps permitidas, «desbloquear todas», olvidar la confianza del PIN,
 * archivar y eliminar el equipo, el detector de navegadores embebidos y «terminar
 * un alta». Ahora están todos, y se sumaron dos secciones: **Actualizaciones** (el
 * proceso de cada actualización paso a paso, en vivo, con su camino y su motivo —
 * ver B.97 en el celular) y **Diagnóstico** (todo lo que reporta el equipo).
 *
 * Los controles por app NO pasan por el carrito: son de un toque, como en la vista
 * rápida, porque se usan de a uno ("ocultale esta app ya"). Mientras el celular no
 * reporta el estado nuevo, el botón muestra ⏳ en vez de volver solo al valor viejo.
 *
 * ⚠️ CHEQUEO DE COMANDOS: `tools/check_panel_commands.py` encuentra los comandos de
 * este archivo por su FORMA: el nombre entre comillas como segundo argumento de
 * LS.sendCommand, la primera celda de una tabla de arrays, las claves on/off de un
 * objeto, un push(…) o las dos ramas de un ternario. Un comando armado de otra manera
 * (concatenado, en una variable) es un punto ciego del chequeo: escribilo en una de
 * esas formas. (Este comentario no lleva ejemplos literales a propósito: el chequeo
 * los tomaría por comandos de verdad.)
 * ═══════════════════════════════════════════════════════════════════════════ */

(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const esc = LS.esc;

  const deviceId = LS.qs("id");
  if (!deviceId) {
    document.body.innerHTML =
      '<div style="max-width:520px;margin:15vh auto;text-align:center">' +
      '<h2 style="color:var(--text-light)">Falta el celular</h2>' +
      '<p style="color:var(--text-gray)">Esta página se abre desde el panel.</p>' +
      '<p><a class="ls-btn ls-btn-primary" href="index.html">Ir al panel</a></p></div>';
    return;
  }

  /** Paquete propio: no se ofrece "actualizar LockSuite por Play Store" (eso es el OTA). */
  const PKG_LOCKSUITE = "com.ejemplo.locksuite";
  const PKG_PLAY = "com.android.vending";

  /**
   * Primera versión de LockSuite con el camino de la Tienda y el registro paso a paso
   * (B.97). Solo se usa para AVISAR que un equipo más viejo actualiza como antes.
   * ⚠️ Si al desplegar B.97 la versión no es 0.6.68 (131), ajustar este número.
   */
  const VERSION_CON_B97 = 131;

  /* ═════════════════════════════════════════════════════════════════════════
   * ESTADO
   * ═══════════════════════════════════════════════════════════════════════ */
  const S = {
    device: {},            // devices/<id>
    appPolicy: {},         // devices/<id>/appPolicy      (override de ESTE equipo)
    globalDecisions: {},   // globalSettings/whitelist/decisions
    custom: {},            // globalSettings/whitelist/custom
    allowedPackages: [],   // globalSettings/allowedPackages (tienda)
    storeApps: {},         // storeApps
    presets: {},           // presets guardados
    installed: [],         // apps instaladas reportadas por el equipo (solo paquetes)
    instaladas: {},        // paquete -> datos que reporta el equipo (devices/<id>/apps)
    // Carrito
    pendApps: {},          // pkg -> "allow"|"block"|"unset"|null(=heredar)
    pendPol: {},           // campo -> boolean
    aplicando: false,
    // Estado de la pantalla (no se pierde al redibujar)
    ui: {
      domAbiertos: new Set(),   // filas del Filtro con los dominios a la vista
      instAbiertos: new Set(),  // filas de Apps del celular con "Más" abierto
      instFiltro: "usuario",
      rutaUpd: "auto",
      esperando: {},            // "pkg|accion" -> { quiere, hasta, buzon }
      cerrado: false            // el equipo se archivó o eliminó desde esta ficha
    }
  };

  const nombreDispositivo = () =>
    S.device.deviceName || (S.device.info && S.device.info.deviceName) ||
    S.device.model || (S.device.info && S.device.info.model) || "Celular";

  /** Igual que `field()` del panel viejo: los campos viven arriba o en `info`. */
  function campo(key, fallback) {
    const d = S.device;
    if (d && d[key] !== undefined && d[key] !== null) return d[key];
    if (d && d.info && d.info[key] !== undefined && d.info[key] !== null) return d.info[key];
    return fallback;
  }

  /**
   * ¿Cambió lo que dibuja esta parte desde la última vez? El celular reescribe su nodo
   * cada pocos segundos (y cada 1,5 s durante una actualización): redibujar la lista de
   * apps en cada latido cerraba lo que el administrador tenía abierto y le cerraba el
   * desplegable de la mano.
   */
  const firmas = {};
  function cambio(clave, datos) {
    const f = JSON.stringify(datos);
    if (firmas[clave] === f) return false;
    firmas[clave] = f;
    return true;
  }

  /** Comando a ESTE celular, con el nombre para el pedido de PIN. */
  const opc = (extra) => Object.assign({ nombre: nombreDispositivo() }, extra || {});

  function hace(ms) {
    const s = Math.max(0, Math.round(ms / 1000));
    if (s < 60) return s + " s";
    const m = Math.floor(s / 60);
    if (m < 60) return m + " min" + (s % 60 ? " " + (s % 60) + " s" : "");
    const h = Math.floor(m / 60);
    if (h < 48) return h + " h" + (m % 60 ? " " + (m % 60) + " min" : "");
    return Math.round(h / 24) + " días";
  }

  /* ═════════════════════════════════════════════════════════════════════════
   * ESTADO EFECTIVO DE UNA APP (filtro)
   *
   * Es la MISMA cuenta que hace `WhitelistManager.allDecisions()` en el celular:
   * catálogo global primero, override del equipo encima. Si esto y el Kotlin
   * dieran distinto, el panel mostraría una cosa y el equipo aplicaría otra —
   * que es la familia de bugs que este proyecto arrastra desde B.28.
   * ═══════════════════════════════════════════════════════════════════════ */
  function estadoDe(pkg) {
    const k = LS.pkgKey(pkg);
    if (Object.prototype.hasOwnProperty.call(S.pendApps, pkg)) {
      const p = S.pendApps[pkg];
      return p === null ? (S.globalDecisions[k] || "unset") : p;
    }
    if (S.appPolicy[k]) return S.appPolicy[k];
    return S.globalDecisions[k] || "unset";
  }

  /** ¿Este equipo tiene decisión propia (o pendiente) para esa app? */
  function esPropia(pkg) {
    const k = LS.pkgKey(pkg);
    if (Object.prototype.hasOwnProperty.call(S.pendApps, pkg)) return S.pendApps[pkg] !== null;
    return !!S.appPolicy[k];
  }

  /* ═════════════════════════════════════════════════════════════════════════
   * NAVEGACIÓN
   * ═══════════════════════════════════════════════════════════════════════ */
  $("c-rail").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-sec]");
    if (!b) return;
    irA(b.dataset.sec);
  });

  // Enlaces internos (`data-ir="seccion"`) en cualquier parte de la ficha.
  document.addEventListener("click", (e) => {
    const a = e.target.closest("[data-ir]");
    if (!a) return;
    e.preventDefault();
    irA(a.dataset.ir);
  });

  function irA(sec) {
    if (!document.querySelector('.ls-section[data-sec="' + sec + '"]')) sec = "resumen";
    document.querySelectorAll("#c-rail button").forEach((x) =>
      x.classList.toggle("active", x.dataset.sec === sec));
    document.querySelectorAll(".ls-section").forEach((x) =>
      x.classList.toggle("active", x.dataset.sec === sec));
    history.replaceState(null, "", "?id=" + encodeURIComponent(deviceId) + "#" + sec);
    window.scrollTo(0, 0);
  }
  if (location.hash) irA(location.hash.slice(1));

  // Buscador de arriba: manda a la sección donde está lo que se buscó. Es lo que
  // reemplaza a "acordarse en qué pestaña estaba tal cosa".
  $("c-buscar").addEventListener("input", function () {
    const q = this.value.trim().toLowerCase();
    if (!q) return;
    const hayInst = Object.values(S.instaladas)
      .some((a) => (a.label || "").toLowerCase().includes(q) || (a.packageName || "").toLowerCase().includes(q));
    const hayPol = (LS.politicas().policies || [])
      .some((p) => p.label.toLowerCase().includes(q) || p.field.toLowerCase().includes(q));
    const hayApp = todasLasApps()
      .some((a) => a.label.toLowerCase().includes(q) || a.pkg.toLowerCase().includes(q) ||
        a.allow.concat(a.block).some((d) => d.d.toLowerCase().includes(q)));
    if (hayInst) {
      $("c-inst-buscar").value = q;
      S.ui.instFiltro = "todas";
      irA("celapps"); pintarInstaladas();
    } else if (hayApp) { $("c-apps-buscar").value = q; irA("apps"); pintarApps(); }
    else if (hayPol) { $("c-pol-buscar").value = q; irA("politicas"); pintarPoliticas(); }
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "/" && document.activeElement.tagName !== "INPUT" &&
        document.activeElement.tagName !== "TEXTAREA") {
      e.preventDefault();
      $("c-buscar").focus();
    }
  });

  /* ═════════════════════════════════════════════════════════════════════════
   * CARRITO
   * ═══════════════════════════════════════════════════════════════════════ */
  function cuentaCarrito() {
    return Object.keys(S.pendApps).length + Object.keys(S.pendPol).length;
  }

  function pintarCarrito() {
    const n = cuentaCarrito();
    const cart = $("c-cart");
    cart.classList.toggle("show", n > 0);
    if (!n) return;
    const nApps = Object.keys(S.pendApps).length;
    const nPol = Object.keys(S.pendPol).length;
    $("c-cart-n").textContent = n === 1 ? "1 cambio sin aplicar" : n + " cambios sin aplicar";
    const partes = [];
    if (nApps) partes.push(nApps + (nApps === 1 ? " app" : " apps") + " (van juntas, 1 solo comando)");
    if (nPol) partes.push(nPol + (nPol === 1 ? " política" : " políticas") + " (1 comando cada una)");
    $("c-cart-detalle").textContent = partes.join(" · ");
  }

  $("c-cart-descartar").addEventListener("click", () => {
    if (!cuentaCarrito()) return;
    if (!confirm("¿Descartar los cambios sin aplicar?")) return;
    S.pendApps = {};
    S.pendPol = {};
    pintarTodo();
  });

  $("c-cart-aplicar").addEventListener("click", aplicarCarrito);

  // Aviso al cerrar con cambios sin aplicar. Sin esto, cerrar la pestaña por
  // costumbre tira el trabajo sin decir nada.
  window.addEventListener("beforeunload", (e) => {
    if (cuentaCarrito() && !S.aplicando) { e.preventDefault(); e.returnValue = ""; }
  });

  async function aplicarCarrito() {
    if (S.aplicando || !cuentaCarrito()) return;
    S.aplicando = true;
    const btn = $("c-cart-aplicar");
    btn.disabled = true;
    const log = $("c-acciones-log");
    irA("acciones");
    log.innerHTML = "";

    const linea = (txt, cls) => {
      const d = document.createElement("div");
      d.className = cls || "wait";
      d.textContent = txt;
      log.appendChild(d);
      return d;
    };

    let fallaron = 0;
    let enCola = 0;   // 26/10/2026 (B.95): mandados pero sin confirmar (en el buzón)

    // ── 1. APPS: todo a la base, y UN SOLO comando al final ──────────────
    const apps = Object.keys(S.pendApps);
    if (apps.length) {
      const l = linea("Guardando " + apps.length + " decisiones de apps…");
      try {
        const updates = {};
        for (const pkg of apps) {
          const v = S.pendApps[pkg];
          // `null` = "que este equipo vuelva a heredar el catálogo global".
          updates["devices/" + deviceId + "/appPolicy/" + LS.pkgKey(pkg)] = v;
        }
        await LS.db.ref().update(updates);
        l.textContent = "✓ " + apps.length + " decisiones de apps guardadas";
        l.className = "ok";

        // El paquete también tiene que poder BAJARSE de la Tienda. Es la parte
        // "en la tienda" del un-solo-toque de B.53, y la hace el panel porque
        // `allowedPackages` es global (lo lee LoginActivity al abrir la tienda).
        await sincronizarAllowedPackages(apps);

        const l2 = linea("Avisándole al celular que relea la configuración…");
        const r = await LS.sendCommand(deviceId, "SYNC_WHITELIST", null, null,
          { nombre: nombreDispositivo(), timeoutMs: 25000 });
        if (r.ok && r.pendiente) {
          // 26/10/2026 (B.95): no confirmó todavía, pero NO falló — está en el buzón del
          // celular y se aplica solo. No se cuenta como falla ni se pide reintentar.
          enCola++;
          l2.className = "wait";
          l2.textContent = "⏳ El celular todavía no confirmó: " + r.reason;
        } else if (r.ok) { l2.textContent = "✓ El celular aplicó la configuración de apps"; l2.className = "ok"; }
        else {
          fallaron++;
          l2.className = "bad";
          l2.textContent = "✗ El celular no confirmó: " + (r.reason || r.status) +
            "  ·  La configuración quedó guardada igual; se reintenta con el botón «Sincronizar apps».";
        }
      } catch (err) {
        fallaron++;
        l.className = "bad";
        l.textContent = "✗ No se pudieron guardar las decisiones: " + err.message;
      }
    }

    // ── 2. POLÍTICAS: un comando cada una, en fila ───────────────────────
    const pols = LS.politicas().policies || [];
    for (const campoNombre of Object.keys(S.pendPol)) {
      const def = pols.find((p) => p.field === campoNombre);
      if (!def) continue;
      const encender = S.pendPol[campoNombre];
      const cmd = encender ? def.on : def.off;
      const l = linea("… " + def.label);
      const r = await LS.sendCommand(deviceId, cmd, null, null,
        { nombre: nombreDispositivo(), timeoutMs: 12000 });
      if (r.ok && r.pendiente) {
        // 26/10/2026 (B.95): está en el buzón del celular. Se saca del carrito porque
        // la orden ya quedó guardada del lado del equipo (reenviarla no agrega nada).
        enCola++;
        l.className = "wait";
        l.textContent = "⏳ " + def.label + " — en el buzón del celular, sin confirmar todavía";
        delete S.pendPol[campoNombre];
      } else if (r.ok) {
        l.className = "ok";
        l.textContent = "✓ " + def.label;
        delete S.pendPol[campoNombre];
      } else {
        fallaron++;
        l.className = "bad";
        l.textContent = "✗ " + def.label + " — " + (r.reason || r.status);
        // Se DEJA en el carrito a propósito: lo que no se aplicó tiene que
        // seguir viéndose como pendiente, no desaparecer como si hubiera salido.
      }
    }

    if (!fallaron && enCola) {
      S.pendApps = {};
      S.pendPol = {};
      linea("⏳ " + enCola + " cambio(s) quedaron en el buzón del celular sin confirmar todavía. " +
        "No hace falta mandarlos de nuevo: se aplican solos cuando el celular los levante.", "wait");
      LS.toast("Mandado. " + enCola + " sin confirmar todavía: quedaron en el buzón del celular.", "warn");
    } else if (!fallaron) {
      S.pendApps = {};
      S.pendPol = {};
      linea("✓ Listo, todo aplicado.", "ok");
      LS.toast("Cambios aplicados.", "ok");
    } else {
      S.pendApps = {};
      linea("Quedaron " + fallaron + " sin aplicar. Lo que falló sigue marcado abajo.", "bad");
      LS.toast("Quedaron " + fallaron + " cambios sin aplicar. Mirá la lista de arriba.", "error");
    }

    S.aplicando = false;
    btn.disabled = false;
    pintarTodo();
  }

  /**
   * Mantiene `globalSettings/allowedPackages` alineado con las apps permitidas.
   *
   * ⚠️ Es GLOBAL (la Tienda lo es), así que se toca lo mínimo: se agrega lo que se
   * permitió acá y se saca solo lo que se prohibió acá. No se reescribe la lista
   * entera desde este equipo — eso le cambiaría la Tienda a toda la flota por
   * configurar un teléfono, que es justo la sorpresa que esta pantalla viene a
   * eliminar.
   */
  async function sincronizarAllowedPackages(pkgs) {
    const ref = LS.db.ref("globalSettings/allowedPackages");
    const snap = await ref.once("value");
    const crudo = snap.val();
    const lista = Array.isArray(crudo)
      ? crudo.slice()
      : typeof crudo === "string" ? crudo.split(",").map((s) => s.trim()).filter(Boolean) : [];
    let cambiada = false;
    for (const pkg of pkgs) {
      const st = estadoDe(pkg);
      const i = lista.indexOf(pkg);
      if (st === "allow" && i < 0) { lista.push(pkg); cambiada = true; }
      if (st === "block" && i >= 0) { lista.splice(i, 1); cambiada = true; }
    }
    if (cambiada) await ref.set(lista);
  }

  /* ═════════════════════════════════════════════════════════════════════════
   * FILTRO DE APPS (catálogo + decisiones de este equipo)
   * ═══════════════════════════════════════════════════════════════════════ */
  function todasLasApps() {
    const cat = LS.catalogo();
    const vistos = new Set();
    const out = [];

    for (const a of cat.apps || []) {
      vistos.add(a.pkg);
      const d = LS.dominiosDe(a.pkg, S.custom);
      out.push({ pkg: a.pkg, label: d.label, note: d.note, allow: d.allow, block: d.block,
        unblocked: d.unblocked, enCatalogo: true });
    }
    // Apps que el panel agregó y no están en el catálogo de fábrica.
    for (const key of Object.keys(S.custom)) {
      const pkg = LS.keyPkg(key);
      if (vistos.has(pkg)) continue;
      vistos.add(pkg);
      const c = S.custom[key] || {};
      out.push({
        pkg: pkg, label: c.label || pkg, note: "",
        allow: (c.allow || []).map((x) => ({ d: x, origen: "panel" })),
        block: (c.block || []).map((x) => ({ d: x, origen: "panel" })),
        unblocked: [], enCatalogo: false
      });
    }
    // Apps que están en la Tienda pero en ninguna lista: si se pueden instalar,
    // tienen que poder decidirse desde acá.
    for (const key of Object.keys(S.storeApps)) {
      const app = S.storeApps[key] || {};
      const pkg = app.packageName || LS.keyPkg(key);
      if (!pkg || vistos.has(pkg)) continue;
      vistos.add(pkg);
      out.push({ pkg: pkg, label: app.label || app.name || pkg, note: "",
        allow: [], block: [], unblocked: [], enCatalogo: false });
    }
    out.sort((a, b) => a.label.localeCompare(b.label, "es"));
    return out;
  }

  function enTienda(pkg) {
    for (const k of Object.keys(S.storeApps)) {
      const a = S.storeApps[k] || {};
      if ((a.packageName || LS.keyPkg(k)) === pkg) return a;
    }
    return null;
  }

  function pintarApps() {
    const cont = $("c-apps");
    const q = ($("c-apps-buscar").value || "").trim().toLowerCase();
    const filtro = $("c-apps-filtro").value;
    const instaladas = new Set(S.installed);

    if (!cambio("apps", [q, filtro, S.installed, S.appPolicy, S.globalDecisions, S.custom,
      S.storeApps, S.pendApps, Array.from(S.ui.domAbiertos)])) return;

    let apps = todasLasApps();
    if (q) {
      apps = apps.filter((a) =>
        a.label.toLowerCase().includes(q) ||
        a.pkg.toLowerCase().includes(q) ||
        a.allow.concat(a.block).some((x) => x.d.toLowerCase().includes(q)));
    }
    if (filtro === "instaladas") apps = apps.filter((a) => instaladas.has(a.pkg));
    else if (filtro === "propias") apps = apps.filter((a) => esPropia(a.pkg));
    else if (filtro === "tienda") apps = apps.filter((a) => !!enTienda(a.pkg));
    else if (filtro !== "todas") apps = apps.filter((a) => estadoDe(a.pkg) === filtro);

    $("cnt-apps").textContent = apps.length;

    if (!apps.length) {
      cont.innerHTML = '<p class="ls-empty">No hay apps que coincidan.</p>';
      return;
    }

    const html = apps.map((a) => {
      const st = estadoDe(a.pkg);
      const propia = esPropia(a.pkg);
      const pend = Object.prototype.hasOwnProperty.call(S.pendApps, a.pkg);
      const tienda = enTienda(a.pkg);
      const inst = instaladas.has(a.pkg);
      const abierta = S.ui.domAbiertos.has(a.pkg);

      const chips = [];
      if (inst) chips.push('<span class="ls-chip">instalada</span>');
      if (tienda) {
        chips.push(tienda.sha256
          ? '<span class="ls-chip ok">en la Tienda ✓</span>'
          : '<span class="ls-chip bad">en la Tienda, sin huella</span>');
      }
      if (propia) chips.push('<span class="ls-chip warn">propio de este celular</span>');
      else if (S.globalDecisions[LS.pkgKey(a.pkg)]) chips.push('<span class="ls-chip">del catálogo global</span>');
      if (pend) chips.push('<span class="ls-chip warn">sin aplicar</span>');

      const dom = (lista, cls) => lista.length
        ? '<div class="ls-domlist">' + lista.map((x) =>
            '<span class="ls-dom ' + cls + '">' + esc(x.d) +
            (x.origen === "panel" ? ' <span title="agregado desde el panel">✎</span>' : "") +
            "</span>").join("") + "</div>"
        : '<p class="ls-empty" style="padding:4px 0">—</p>';

      return '' +
        '<div class="ls-approw st-' + st + (pend ? " pending" : "") + '" data-pkg="' + esc(a.pkg) + '">' +
          '<div class="ls-apphead">' +
            '<div class="ls-appgrow">' +
              '<div class="ls-appname">' + esc(a.label) + "</div>" +
              '<div class="ls-apppkg">' + esc(a.pkg) + "</div>" +
              (chips.length ? '<div style="margin-top:5px; display:flex; gap:5px; flex-wrap:wrap">' + chips.join("") + "</div>" : "") +
            "</div>" +
            '<div class="ls-seg">' +
              '<button data-st="allow" class="' + (st === "allow" ? "on-allow" : "") + '">Permitir</button>' +
              '<button data-st="unset" class="' + (st === "unset" ? "on-unset" : "") + '">Sin marcar</button>' +
              '<button data-st="block" class="' + (st === "block" ? "on-block" : "") + '">Prohibir</button>' +
            "</div>" +
            '<button class="ls-btn ls-btn-sm" data-toggle="1">Dominios</button>' +
          "</div>" +
          '<div class="ls-appbody' + (abierta ? "" : " hidden") + '">' +
            (a.note ? '<p class="ls-hint" style="margin:10px 0 0">' + esc(a.note) + "</p>" : "") +
            '<div class="ls-domtitle">Permitidos</div>' + dom(a.allow, "allow") +
            '<div class="ls-domtitle">No kosher — bloqueados siempre, aunque la app esté permitida</div>' +
            dom(a.block, "block") +
            (a.unblocked.length
              ? '<div class="ls-domtitle">Desbloqueados a mano desde el panel</div>' +
                '<div class="ls-domlist">' + a.unblocked.map((d) =>
                  '<span class="ls-dom off">' + esc(d) + "</span>").join("") + "</div>"
              : "") +
            (propia
              ? '<div style="margin-top:12px"><button class="ls-btn ls-btn-sm" data-heredar="1">' +
                "↩︎ Volver a lo que diga el catálogo global</button></div>"
              : "") +
          "</div>" +
        "</div>";
    }).join("");

    cont.innerHTML = html;
  }

  $("c-apps").addEventListener("click", (e) => {
    const fila = e.target.closest(".ls-approw");
    if (!fila) return;
    const pkg = fila.dataset.pkg;

    if (e.target.closest("[data-toggle]")) {
      const cuerpo = fila.querySelector(".ls-appbody");
      cuerpo.classList.toggle("hidden");
      if (cuerpo.classList.contains("hidden")) S.ui.domAbiertos.delete(pkg);
      else S.ui.domAbiertos.add(pkg);
      firmas.apps = null;   // el estado de apertura es parte de lo dibujado
      return;
    }
    if (e.target.closest("[data-heredar]")) {
      S.pendApps[pkg] = null;
      pintarApps();
      pintarCarrito();
      return;
    }
    const b = e.target.closest("[data-st]");
    if (!b) return;
    const nuevo = b.dataset.st;

    // Volver al estado que ya tenía = sacar del carrito, no encolar un cambio nulo.
    const k = LS.pkgKey(pkg);
    const guardado = S.appPolicy[k] || null;
    if (guardado === nuevo || (!guardado && (S.globalDecisions[k] || "unset") === nuevo)) {
      delete S.pendApps[pkg];
    } else {
      S.pendApps[pkg] = nuevo;
    }
    pintarApps();
    pintarCarrito();
  });

  $("c-apps-buscar").addEventListener("input", pintarApps);
  $("c-apps-filtro").addEventListener("change", pintarApps);

  /* ═════════════════════════════════════════════════════════════════════════
   * MODO DEL FILTRO
   *
   * Tres botones en vez de tres interruptores sueltos. El panel viejo tenía
   * "Activar" y "Bloquear de verdad" separados, y esa combinación tiene un estado
   * sin sentido (bloquear de verdad con el modo apagado) que igual se podía
   * marcar. Acá los estados posibles son exactamente tres y no hay forma de
   * pedir uno inválido.
   * ═══════════════════════════════════════════════════════════════════════ */
  const MODOS = [
    {
      id: "negra",
      titulo: "Lista negra",
      desc: "Lo normal. Las apps sin marcar funcionan; las prohibidas quedan cerradas y sus dominios no resuelven. Los dominios no kosher de las apps permitidas se bloquean igual."
    },
    {
      id: "simulacion",
      titulo: "Lista blanca — simulación",
      desc: "No bloquea nada todavía: anota qué habría bloqueado, para completar las listas mirando el equipo real. Es el paso obligatorio antes del estricto."
    },
    {
      id: "estricta",
      titulo: "Lista blanca — estricta",
      desc: "Solo resuelven los dominios de las apps permitidas. Lo que no está en la lista, no anda para nadie. Pasar acá sin haber simulado deja el equipo a medio funcionar."
    }
  ];

  function modoActual() {
    if (!campo("whitelistEnabled", false)) return "negra";
    return campo("whitelistSimulation", true) ? "simulacion" : "estricta";
  }

  function pintarModos() {
    const act = modoActual();
    const cdn = campo("whitelistSharedCdn", true);
    if (!cambio("modos", [act, cdn])) return;
    $("c-modos").innerHTML = MODOS.map((m) =>
      '<button class="ls-mode ' + (m.id === act ? "active" : "") + '" data-modo="' + m.id + '">' +
      "<b>" + esc(m.titulo) + "</b><small>" + esc(m.desc) + "</small></button>").join("");

    $("c-cdn").innerHTML =
      '<div class="ls-polrow" style="border:1px solid var(--navy-border); border-radius:8px">' +
      '<label for="cdn-sw">Permitir CDN compartidos (CloudFront, Akamai, Fastly…)<br>' +
      '<small style="color:var(--text-muted)">Varias apps los necesitan, pero permitirlos es permitir una porción grande de internet. Solo tiene efecto con la lista blanca encendida.</small></label>' +
      '<span class="ls-sw"><input type="checkbox" id="cdn-sw" ' + (cdn ? "checked" : "") +
      '><span></span></span></div>';
    $("cdn-sw").addEventListener("change", async function () {
      this.disabled = true;
      const r = await LS.sendCommand(deviceId,
        this.checked ? "ENABLE_WHITELIST_SHARED_CDN" : "DISABLE_WHITELIST_SHARED_CDN",
        null, null, { nombre: nombreDispositivo() });
      this.disabled = false;
      if (!r.ok) { this.checked = !this.checked; LS.toast("No se pudo cambiar: " + (r.reason || r.status), "error"); }
      else LS.toast(...LS.resultado(r, "Listo."));
    });
  }

  $("c-modos").addEventListener("click", async (e) => {
    const b = e.target.closest("[data-modo]");
    if (!b) return;
    const destino = b.dataset.modo;
    if (destino === modoActual()) return;

    if (destino === "estricta") {
      const audit = campo("whitelistAuditCount", 0);
      if (!confirm(
        "¿Pasar este celular a lista blanca ESTRICTA?\n\n" +
        "A partir de ahora solo van a resolver los dominios de las apps permitidas. " +
        "Todo lo demás deja de andar, para todas las apps.\n\n" +
        (audit ? "La simulación registró " + audit + " dominios que quedarían afuera. " +
                 "Revisalos en la sección DNS antes de seguir.\n\n"
               : "⚠️ Este equipo NO tiene registro de simulación. Sin eso estás adivinando " +
                 "qué necesita esta persona, y lo más probable es que le rompas algo.\n\n") +
        "¿Seguir igual?"
      )) return;
    }

    b.disabled = true;
    const pasos = [];
    if (destino === "negra") pasos.push("DISABLE_WHITELIST_MODE");
    else {
      pasos.push("ENABLE_WHITELIST_MODE");
      pasos.push(destino === "simulacion" ? "SET_WHITELIST_SIMULATION" : "SET_WHITELIST_ENFORCE");
    }
    let ultimoModo = null;
    for (const cmd of pasos) {
      const r = await LS.sendCommand(deviceId, cmd, null, null, { nombre: nombreDispositivo() });
      // Si alguno quedó pendiente (en el buzón), el aviso final lo dice con ⏳.
      if (!ultimoModo || !ultimoModo.pendiente) ultimoModo = r;
      if (!r.ok) {
        LS.toast("No se pudo cambiar el modo: " + (r.reason || r.status), "error");
        b.disabled = false;
        return;
      }
    }
    b.disabled = false;
    LS.toast(...LS.resultado(ultimoModo, "Modo cambiado."));
  });

  /* ═════════════════════════════════════════════════════════════════════════
   * DETECTOR DE NAVEGADORES EMBEBIDOS (B.60) — antes solo en la pestaña Lista
   * blanca del panel, eligiendo el celular en una lista. Mismo flujo: encender,
   * dejarlo en simulación unos días mirando lo que anota, y recién ahí bloquear.
   * ═══════════════════════════════════════════════════════════════════════ */
  function pintarIab() {
    const on = campo("iabFinderEnabled", false) === true;
    const sim = campo("iabFinderSimulation", true) !== false;
    const audit = campo("embeddedBrowserAudit", {}) || {};
    const descartadas = campo("iabFinderDropped", 0);
    if (!cambio("iab", [on, sim, audit, descartadas])) return;

    const filas = Object.keys(audit).map((k) => audit[k]).filter(Boolean)
      .sort((a, b) => (b.hits || 0) - (a.hits || 0));
    const estado = !on ? '<span class="ls-chip">apagado</span>'
      : sim ? '<span class="ls-chip warn">encendido · en simulación (no bloquea)</span>'
      : '<span class="ls-chip bad">encendido · BLOQUEA</span>';

    $("c-iab").innerHTML =
      "<h3>🕵️ Detector de navegadores embebidos</h3>" +
      '<p class="ls-hint">Detecta un navegador escondido adentro de una app <b>por la forma de la pantalla</b> ' +
      "(barra de direcciones, controles de navegador), así que funciona con apps en las que nadie pensó. " +
      "<b>Dejalo en simulación unos días antes de que bloquee</b>: un falso positivo deja una app sin " +
      "funcionar y nadie sabe por qué.</p>" +
      '<div class="ls-row" style="margin-bottom:10px">' + estado + "</div>" +
      '<div class="ls-row">' +
        '<button class="ls-btn" id="c-iab-on">' + (on ? "Apagar detector" : "Encender detector") + "</button>" +
        '<button class="ls-btn" id="c-iab-sim"' + (on ? "" : " disabled") + ">" +
          (sim ? "Pasar a bloquear de verdad" : "Volver a simulación") + "</button>" +
        '<button class="ls-btn" id="c-iab-clear">Vaciar la lista</button>' +
      "</div>" +
      '<div class="ls-domtitle" style="margin-top:14px">Lo que detectó en este celular</div>' +
      (filas.length
        ? '<div class="ls-tablewrap"><table class="ls-table"><thead><tr><th>App</th><th>Motivo</th><th>Veces</th><th></th></tr></thead><tbody>' +
          filas.map((f) => "<tr><td style=\"font-family:monospace\">" + esc(f.packageName || "?") + "</td><td>" +
            esc(f.reason || "") + "</td><td>" + esc(String(Number(f.hits) || 0)) + "</td><td>" +
            (f.blocked ? '<span class="ls-chip bad">bloqueada</span>' : '<span class="ls-chip">solo anotada</span>') +
            "</td></tr>").join("") + "</tbody></table></div>" +
          (descartadas > 0 ? '<p class="ls-hint">Y ' + esc(String(descartadas)) + " app(s) más que no entraron por el tope de la lista.</p>" : "")
        : '<p class="ls-empty">' + (on ? "Todavía no detectó ningún navegador embebido." : "El detector está apagado en este celular.") + "</p>");

    $("c-iab-on").addEventListener("click", async function () {
      this.disabled = true;
      const r = await LS.sendCommand(deviceId, on ? "DISABLE_IAB_FINDER" : "ENABLE_IAB_FINDER", null, null, opc());
      this.disabled = false;
      LS.toast(...LS.resultado(r, on ? "Detector apagado." : "Detector encendido, en simulación: no bloquea nada todavía."));
    });
    $("c-iab-sim").addEventListener("click", async function () {
      if (sim && !confirm(
        "A partir de ahora el detector va a BLOQUEAR de verdad en este celular.\n\n" +
        "Antes de seguir, mirá la lista: eso es lo que se va a empezar a bloquear. Si hay alguna " +
        "app que el usuario necesita, va a dejar de funcionar sin ningún mensaje que lo explique.\n\n¿Seguimos?"
      )) return;
      this.disabled = true;
      const r = await LS.sendCommand(deviceId, sim ? "DISABLE_IAB_SIMULATION" : "ENABLE_IAB_SIMULATION", null, null, opc());
      this.disabled = false;
      LS.toast(...LS.resultado(r, sim ? "El detector ahora bloquea en este celular." : "Vuelto a simulación: no bloquea."));
    });
    $("c-iab-clear").addEventListener("click", async function () {
      this.disabled = true;
      const r = await LS.sendCommand(deviceId, "CLEAR_IAB_AUDIT", null, null, opc());
      this.disabled = false;
      LS.toast(...LS.resultado(r, "Lista vaciada."));
    });
  }

  /* ═════════════════════════════════════════════════════════════════════════
   * POLÍTICAS
   * ═══════════════════════════════════════════════════════════════════════ */
  function pintarPoliticas() {
    const defs = LS.politicas().policies || [];
    const q = ($("c-pol-buscar").value || "").trim().toLowerCase();
    const soloOn = $("c-pol-solo-on").checked;

    const valor = (f) => Object.prototype.hasOwnProperty.call(S.pendPol, f)
      ? S.pendPol[f] : !!campo(f, false);

    $("cnt-pol").textContent = defs.filter((p) => valor(p.field)).length + "/" + defs.length;
    // Se compara contra lo DIBUJADO: abrir/cerrar los grupos no tiene que perderse
    // en cada latido del celular.
    if (!cambio("pol", [q, soloOn, defs.map((p) => valor(p.field)), S.pendPol])) return;

    let lista = defs;
    if (q) lista = lista.filter((p) => p.label.toLowerCase().includes(q) ||
      p.field.toLowerCase().includes(q) || p.group.toLowerCase().includes(q));
    if (soloOn) lista = lista.filter((p) => valor(p.field));

    const grupos = [];
    for (const p of lista) {
      let g = grupos.find((x) => x.nombre === p.group);
      if (!g) { g = { nombre: p.group, items: [] }; grupos.push(g); }
      g.items.push(p);
    }

    if (!grupos.length) {
      $("c-politicas").innerHTML = '<p class="ls-empty">Ninguna política coincide.</p>';
      return;
    }

    const abiertos = new Set(Array.from(document.querySelectorAll("#c-politicas details[open]"))
      .map((d) => d.dataset.grupo));
    $("c-politicas").innerHTML = grupos.map((g) => {
      const encendidas = g.items.filter((p) => valor(p.field)).length;
      return '<details class="ls-polgroup" data-grupo="' + esc(g.nombre) + '" ' +
        (q || soloOn || abiertos.has(g.nombre) ? "open" : "") + ">" +
        "<summary>" + esc(g.nombre) +
        ' <span style="color:var(--text-muted); font-weight:400; font-size:12px">' +
        encendidas + "/" + g.items.length + " encendidas</span></summary>" +
        g.items.map((p) => {
          const pend = Object.prototype.hasOwnProperty.call(S.pendPol, p.field);
          return '<div class="ls-polrow' + (pend ? " pending" : "") + '">' +
            '<label for="pol-' + esc(p.field) + '">' + esc(p.label) +
            (pend ? ' <span class="ls-polmark">· sin aplicar</span>' : "") + "</label>" +
            '<span class="ls-sw"><input type="checkbox" id="pol-' + esc(p.field) + '" ' +
            'data-field="' + esc(p.field) + '" ' + (valor(p.field) ? "checked" : "") +
            "><span></span></span></div>";
        }).join("") + "</details>";
    }).join("");
  }

  $("c-politicas").addEventListener("change", (e) => {
    const inp = e.target.closest("input[data-field]");
    if (!inp) return;
    const f = inp.dataset.field;
    const actual = !!campo(f, false);
    if (inp.checked === actual) delete S.pendPol[f];
    else S.pendPol[f] = inp.checked;
    pintarPoliticas();
    pintarCarrito();
  });
  $("c-pol-buscar").addEventListener("input", pintarPoliticas);
  $("c-pol-solo-on").addEventListener("change", pintarPoliticas);
  $("c-pol-expandir").addEventListener("click", function () {
    const abrir = this.textContent.startsWith("Abrir");
    document.querySelectorAll("#c-politicas details").forEach((d) => { d.open = abrir; });
    this.textContent = abrir ? "Cerrar todo" : "Abrir todo";
  });

  /* ── Los interruptores especiales ─────────────────────────────────────── */
  const ESPECIALES = [
    {
      field: "locksuiteSuspended", label: "Suspender LockSuite",
      on: "SUSPEND_LOCKSUITE", off: "RESUME_LOCKSUITE",
      confirmarAl: "encender",
      texto: "¿Suspender LockSuite en este celular?\n\nSe levantan TODAS las restricciones, " +
        "incluidas las que impiden desinstalar LockSuite y restaurar de fábrica. Mientras dure, " +
        "el equipo queda sin ninguna protección.\n\nAl desactivarla vuelve todo como estaba."
    },
    {
      field: "kioskLockTaskEnabled", label: "Modo kiosco (Lock Task)",
      on: "ENABLE_KIOSK_LOCK_TASK", off: "DISABLE_KIOSK_LOCK_TASK",
      confirmarAl: "encender",
      texto: "¿Anclar este equipo al launcher kosher?\n\nAndroid va a permitir abrir ÚNICAMENTE " +
        "las apps de la lista permitida.\n\n⚠️ Si el marcador telefónico NO está en esa lista, " +
        "en este equipo no se va a poder marcar el código de recuperación *#*#9999#*#*."
    },
    {
      field: "nokiaKeypadMode", label: "Modo teléfono de teclas (Nokia)",
      on: "ENABLE_NOKIA_MODE", off: "DISABLE_NOKIA_MODE"
    },
    {
      field: "nokiaTouchEnabled", label: "Táctil del launcher de teclas",
      on: "ENABLE_NOKIA_TOUCH", off: "DISABLE_NOKIA_TOUCH",
      confirmarAl: "apagar",
      texto: "¿Apagar el táctil del launcher?\n\nLa pantalla de inicio va a responder solo a las " +
        "teclas físicas.\n\n⚠️ Si este celular no tiene cruceta, la pantalla de inicio queda " +
        "manejable solo desde el panel.\n\nSalida de emergencia en el equipo: mantener 3 segundos " +
        "el dedo en la esquina superior derecha."
    }
  ];

  function pintarEspeciales() {
    const vals = ESPECIALES.map((p) => campo(p.field, p.field === "nokiaTouchEnabled"));
    if (!cambio("esp", vals)) return;
    $("c-especiales").innerHTML = ESPECIALES.map((p, i) => {
      const val = vals[i];
      return '<div class="ls-polrow">' +
        '<label for="esp-' + p.field + '">' + esc(p.label) + "</label>" +
        '<span class="ls-sw"><input type="checkbox" id="esp-' + p.field + '" data-esp="' + p.field +
        '" ' + (val ? "checked" : "") + "><span></span></span></div>";
    }).join("");
  }

  $("c-especiales").addEventListener("change", async (e) => {
    const inp = e.target.closest("input[data-esp]");
    if (!inp) return;
    const def = ESPECIALES.find((x) => x.field === inp.dataset.esp);
    const enc = inp.checked;
    const hayQueConfirmar = (def.confirmarAl === "encender" && enc) ||
                            (def.confirmarAl === "apagar" && !enc);
    if (hayQueConfirmar && !confirm(def.texto)) { inp.checked = !enc; return; }
    inp.disabled = true;
    const r = await LS.sendCommand(deviceId, enc ? def.on : def.off, null, null,
      { nombre: nombreDispositivo() });
    inp.disabled = false;
    if (!r.ok) { inp.checked = !enc; LS.toast("No se pudo: " + (r.reason || r.status), "error"); }
    else LS.toast(...LS.resultado(r, def.label + ": listo."));
  });

  /* ═════════════════════════════════════════════════════════════════════════
   * RESUMEN
   * ═══════════════════════════════════════════════════════════════════════ */
  /**
   * «Canal de comandos» del Resumen (26/10/2026, B.95): por dónde le llegan los comandos
   * a este equipo. FCM es lo normal; sin FCM, solo por su buzón (`pendingCommands`), en
   * vivo desde 0.6.67 o cada 15 min antes. Devuelve HTML ya escapado.
   */
  function canalDeComandos() {
    const pend = campo("pendingCommands", null) || {};
    const ahora = Date.now();
    const enCola = Object.keys(pend).filter((k) => {
      const x = pend[k] || {};
      return !(x.expiresAt && x.expiresAt < ahora);
    }).length;
    const cola = enCola ? ' · <span class="ls-chip warn">' + enCola + " en su buzón</span>" : "";
    if (campo("fcmToken", "")) return '<span class="ls-chip ok">FCM</span>' + cola;
    const vivo = campo("mailboxLive", null);
    const ult = campo("mailboxLastCheckAt", 0);
    return '<span class="ls-chip warn">sin FCM</span> · ' +
      (vivo === true ? "buzón en vivo" : vivo === false ? "buzón cada 15 min (sin conexión en vivo)" : "buzón cada 15 min") +
      (ult ? "<br><small>último chequeo " + esc(LS.fecha(ult)) + " — " + esc(String(campo("mailboxLastResult", ""))) + "</small>" : "") +
      cola;
  }

  function pintarResumen() {
    const kv = (k, v) => '<div class="ls-kv"><span>' + esc(k) + "</span><span>" + v + "</span></div>";

    const vName = campo("versionName", campo("appVersionName", "—"));
    const vCode = campo("versionCode", campo("appVersionCode", "—"));
    const accActiva = campo("accessibilityRunning", campo("accessibilityActive", null));
    const exitReason = campo("lastExitReason", null);
    const exitTs = campo("lastExitTimestamp", 0);

    $("c-ident").innerHTML =
      kv("Modelo", esc(campo("model", "—"))) +
      kv("Android", esc(String(campo("androidVersion", campo("androidSdkInt", "—"))))) +
      kv("LockSuite", esc(String(vName)) + " (" + esc(String(vCode)) + ")") +
      kv("Última vez", LS.fecha(campo("lastSeen", 0))) +
      kv("Batería", campo("batteryLevel", null) != null ? esc(String(campo("batteryLevel"))) + " %" : "—") +
      (campo("freeSpaceMb", null) != null ? kv("Espacio libre", esc(String(campo("freeSpaceMb"))) + " MB") : "");

    const salud = campo("dnsTunnelHealth", null);
    const chipSalud = salud === "OK" ? '<span class="ls-chip ok">OK</span>'
      : salud ? '<span class="ls-chip bad">' + esc(salud) + "</span>"
      : '<span class="ls-chip">sin datos</span>';
    $("c-salud").innerHTML =
      kv("Túnel DNS", chipSalud) +
      kv("Paquetes filtrados", esc(String(campo("dnsTunnelPacketsIn", "—")))) +
      kv("Auto-reparaciones", esc(String(campo("dnsTunnelHeals", 0)))) +
      kv("Accesibilidad", accActiva === true
        ? '<span class="ls-chip ok">activa</span>'
        : accActiva === false
          ? '<span class="ls-chip bad">caída</span>' : "—") +
      kv("Dominios en el filtro", esc(String(campo("whitelistDomainCount", "—")))) +
      kv("Canal de comandos", canalDeComandos()) +
      (exitReason ? kv("Última salida", esc(String(exitReason)) + (exitTs ? " · " + LS.fecha(exitTs) : "")) : "");

    const m = MODOS.find((x) => x.id === modoActual());
    const perfil = campo("masterProfileId", null);
    $("c-config").innerHTML =
      kv("Modo del filtro", '<span class="ls-chip">' + esc(m.titulo) + "</span>") +
      kv("Perfil aplicado", perfil ? esc(perfil) + "<br><small>" + LS.fecha(campo("masterProfileAt", 0)) + "</small>" : "ninguno") +
      kv("Apps con decisión propia", String(Object.keys(S.appPolicy).length)) +
      kv("Reglas DNS propias", String(Object.keys(campo("dnsRules", {}) || {}).length)) +
      kv("Dominios afuera (auditoría)", esc(String(campo("whitelistAuditCount", 0))));

    // Avisos: solo lo que está mal AHORA. Un panel que grita siempre no se mira.
    const avisos = [];
    if (campo("locksuiteSuspended", false)) {
      avisos.push(["bad", "LockSuite está SUSPENDIDO en este equipo: no hay ninguna protección activa."]);
    }
    if (accActiva === false) {
      avisos.push(["bad", "El servicio de Accesibilidad está caído: el filtro visual (Capa 3) no está funcionando."]);
    }
    if (salud && salud !== "OK" && salud !== "SIN_DATOS") {
      avisos.push(["warn", "El túnel DNS reporta «" + salud + "»: el filtro de dominios puede no estar filtrando."]);
    }
    if (campo("commandSecretMismatch", false)) {
      avisos.push(["bad", "El canal de comandos está desincronizado: los comandos del panel no llegan. Usá «Re-vincular» en Acciones."]);
    }
    // 26/10/2026 (B.95): un equipo sin FCM solo recibe por su buzón. Si además no lo
    // escucha en vivo, cada cambio tarda hasta 15 min y parece que "el panel no manda".
    if (!campo("fcmToken", "") && campo("mailboxLive", null) !== true) {
      avisos.push(["warn", "Este celular no tiene FCM" +
        (campo("fcmTokenError", "") ? " (" + campo("fcmTokenError", "") + ")" : "") +
        " y no está escuchando su buzón en vivo: los comandos le llegan cada 15 min. " +
        "Actualizalo a LockSuite 0.6.67+ para que le lleguen en segundos."]);
    }
    if (campo("policyDriftCount", 0) > 0) {
      avisos.push(["warn", campo("policyDriftCount") + " políticas figuran encendidas pero el sistema no las tiene puestas."]);
    }
    if (modoActual() === "simulacion") {
      avisos.push(["warn", "La lista blanca está en simulación: registra, pero no bloquea nada todavía."]);
    }
    if (exitReason === "ANR" || exitReason === "CRASH" || exitReason === "CRASH_NATIVE") {
      const det = campo("lastExitDetail", "");
      avisos.push(["warn", "Último cierre anormal del proceso: " + exitReason +
        (exitTs ? " (" + LS.fecha(exitTs) + ")" : "") + (det ? " — " + det : "")]);
    }
    const alta = campo("enrollmentPending", false) === true;
    $("c-alertas").innerHTML = avisos.map(([cls, txt]) =>
      '<div class="ls-card ls-alert ' + cls + '">' + esc(txt) + "</div>").join("") +
      // B.98: "Terminar un alta" estaba solo en la pestaña de QR del panel.
      (alta
        ? '<div class="ls-card ls-alert warn">Este equipo tiene un <b>alta a medio terminar</b>: falta ' +
          'bloquear el cambio de cuentas y de idioma. Hacelo cuando la cuenta de Google y el idioma ya ' +
          'estén como tienen que quedar. <button class="ls-btn ls-btn-sm" id="c-alta-btn" style="margin-left:8px">✔ Terminar alta</button></div>'
        : "");
    if (alta) {
      $("c-alta-btn").addEventListener("click", async function () {
        if (!confirm("¿Terminar el alta de " + nombreDispositivo() + "?\n\nSe bloquea el cambio de cuentas y de idioma.")) return;
        this.disabled = true;
        const r = await LS.sendCommand(deviceId, "FINISH_ENROLLMENT", null, null, opc());
        this.disabled = false;
        LS.toast(...LS.resultado(r, "Alta terminada."));
      });
    }

    pintarRapidas();
    pintarLayer3();

    if (document.activeElement !== $("c-nombre-input")) {
      $("c-nombre-input").value = campo("deviceName", "") || "";
    }

    // Cabecera
    $("c-nombre").textContent = nombreDispositivo();
    $("c-id").textContent = deviceId;
    const con = LS.estadoConexion(campo("lastSeen", 0));
    $("c-conexion").innerHTML = '<span class="ls-dot ' + con.cls + '"></span>' + esc(con.txt);
    const bat = campo("batteryLevel", null);
    $("c-bateria").textContent = "🔋 " + (bat != null ? bat + " %" : "—");
    $("c-version").textContent = vName;
    document.title = nombreDispositivo() + " — LockSuite";
  }

  /** Los atajos del Resumen: lo que más se usa, a un toque. */
  function pintarRapidas() {
    if (!cambio("rapidas", [1])) return;
    $("c-rapidas").innerHTML =
      '<button class="ls-btn" id="c-q-lock">🔒 Bloquear la pantalla ahora</button>' +
      '<button class="ls-btn" data-ir="celapps">📱 Apps del celular</button>' +
      '<button class="ls-btn" data-ir="actualizaciones">⬆️ Actualizar una app</button>' +
      '<button class="ls-btn" data-ir="diag">🩺 Diagnóstico</button>';
    $("c-q-lock").addEventListener("click", async function () {
      this.disabled = true;
      const r = await LS.sendCommand(deviceId, "LOCK_DEVICE", null, null, opc());
      this.disabled = false;
      LS.toast(...LS.resultado(r, "Pantalla bloqueada."));
    });
  }

  /* Registro de rebotes de la Capa 3 (mdm/Layer3Audit.kt).
   *
   * El formato es texto plano a propósito, una línea por entrada y siete campos
   * separados por "|": ts|origen|paquete|detalle|motivo|bloqueado|veces. Se eligió así
   * para que también se pueda leer de un vistazo por ADB; acá solo se parsea.
   *
   * Una línea con menos de 7 campos se ignora en vez de romper la tarjeta: el celular
   * puede estar corriendo una versión más vieja o más nueva que el panel. */
  const ORIGEN_LEGIBLE = {
    "selector-de-foto": "Selector de foto",
    "mp-ofertas": "Ofertas de Mercado Pago",
    "webview": "Navegador interno",
    "whatsapp": "WhatsApp",
    "cuenta-google": "Cuenta de Google",
    "menu-accesibilidad": "Menú de Accesibilidad",
    "legales": "Pantallas legales",
    "portal-cautivo": "Portal cautivo",
    "play-store": "Play Store",
    "anti-evasion": "Anti-evasión en Ajustes"
  };

  function pintarLayer3() {
    const cont = $("c-layer3");
    if (!cont) return;
    const crudo = campo("layer3Audit", "") || "";
    if (!cambio("layer3", crudo)) return;
    const filas = [];
    crudo.split("\n").forEach(function (linea) {
      if (!linea) return;
      const c = linea.split("|");
      if (c.length < 7) return;
      filas.push({
        ts: parseInt(c[0], 10) || 0,
        origen: c[1], paquete: c[2], detalle: c[3], motivo: c[4],
        bloqueado: c[5] === "1", veces: parseInt(c[6], 10) || 1
      });
    });

    if (!filas.length) {
      cont.innerHTML = '<p class="ls-hint" style="margin:0">Todavía no cerró nada. ' +
        'Si una app se cierra sola, abrila una vez y volvé acá.</p>';
      return;
    }

    cont.innerHTML = filas.map(function (f) {
      const chip = f.bloqueado
        ? '<span class="ls-chip bad">rebotó</span>'
        : '<span class="ls-chip">solo anotado</span>';
      const veces = f.veces > 1 ? ' <small>×' + f.veces + "</small>" : "";
      const nombre = ORIGEN_LEGIBLE[f.origen] || f.origen;
      return '<div style="padding:8px 0; border-bottom:1px solid var(--navy-border)">' +
        "<div>" + chip + " <strong>" + esc(nombre) + "</strong>" + veces +
        ' <small style="opacity:.7">' + LS.fecha(f.ts) + "</small></div>" +
        '<div style="font-size:12px; opacity:.85">' + esc(f.paquete) +
        (f.detalle ? " · " + esc(f.detalle) : "") + "</div>" +
        '<div style="font-size:12px; opacity:.7">' + esc(f.motivo) + "</div>" +
        "</div>";
    }).join("");
  }

  $("c-nombre-btn").addEventListener("click", async function () {
    const v = $("c-nombre-input").value.trim();
    if (!v) return;
    this.disabled = true;
    try {
      await LS.db.ref("devices/" + deviceId + "/deviceName").set(v);
      await LS.db.ref("devices/" + deviceId + "/info/deviceName").set(v);
      LS.toast("Nombre guardado.", "ok");
    } catch (e) { LS.toast("No se pudo guardar: " + e.message, "error"); }
    this.disabled = false;
  });

  /* ═════════════════════════════════════════════════════════════════════════
   * CONFIGURACIÓN RÁPIDA
   * ═══════════════════════════════════════════════════════════════════════ */
  const NIVELES = [
    { id: "kosher_estricto", nombre: "Nivel 1 — Kosher estricto",
      desc: "Todo cerrado. Bloquea el cambio de cuentas y de idioma.",
      aviso: "⚠️ Aplicalo DESPUÉS de agregar la cuenta de Google y fijar el idioma: bloquea las dos cosas. Si lo aplicás antes, el equipo no se puede terminar de dar de alta." },
    { id: "trabajo", nombre: "Nivel 2 — Trabajo",
      desc: "Restricciones fuertes, pero deja trabajar.", aviso: "" },
    { id: "base_minima", nombre: "Nivel 3 — Base mínima",
      desc: "Solo el piso anti-manipulación.", aviso: "" },
    { id: "gracia", nombre: "Nivel 4 — Período de gracia",
      desc: "Cierra lo claramente no kosher y deja lo dudoso abierto por un plazo. Mientras tanto, anota qué usa esta persona para completar el catálogo.",
      aviso: "Al vencer, se cierra solo. El plazo lo aguantan dos relojes, así que atrasar la hora del equipo no lo estira." }
  ];

  function pintarPerfiles() {
    if (!cambio("perfiles", nombreDispositivo())) return;
    $("c-perfiles").innerHTML = NIVELES.map((n) =>
      '<div class="ls-card">' +
        "<h3>" + esc(n.nombre) + "</h3>" +
        '<p class="ls-hint">' + esc(n.desc) + (n.aviso ? "<br><b>" + esc(n.aviso) + "</b>" : "") + "</p>" +
        (n.id === "gracia"
          ? '<div class="ls-row">' +
            '<label style="font-size:12.5px; color:var(--text-gray)">Plazo:</label>' +
            '<select class="ls-select" id="gracia-horas">' +
              '<option value="2">2 horas (para probar)</option>' +
              '<option value="24">1 día</option>' +
              '<option value="48" selected>2 días</option>' +
              '<option value="168">1 semana</option></select>' +
            '<button class="ls-btn ls-btn-primary" data-nivel="gracia">Aplicar a ' + esc(nombreDispositivo()) + "</button></div>"
          : '<button class="ls-btn ls-btn-primary" data-nivel="' + n.id + '">Aplicar a ' +
            esc(nombreDispositivo()) + "</button>") +
      "</div>").join("");
  }

  $("c-perfiles").addEventListener("click", async (e) => {
    const b = e.target.closest("[data-nivel]");
    if (!b) return;
    const n = NIVELES.find((x) => x.id === b.dataset.nivel);
    if (!n) return;
    const extra = { level: n.id };
    if (n.id === "gracia") {
      const h = parseInt($("gracia-horas").value, 10) || 48;
      extra.graceMs = String(h * 3600000);
      extra.graceTarget = "kosher_estricto";
    }
    if (!confirm("¿Aplicar «" + n.nombre + "» a " + nombreDispositivo() + "?\n\n" +
      n.desc + (n.aviso ? "\n\n" + n.aviso : ""))) return;
    b.disabled = true;
    const r = await LS.sendCommand(deviceId, "APPLY_MASTER_PROFILE", null, extra,
      { nombre: nombreDispositivo(), timeoutMs: 20000 });
    b.disabled = false;
    LS.toast(...LS.resultado(r, "Perfil aplicado."));
  });

  function pintarGracia() {
    const activa = campo("graceActive", false);
    const hasta = campo("graceDeadline", campo("graceEndsAt", 0));
    const rem = campo("graceRemainingMs", hasta ? Math.max(0, hasta - Date.now()) : 0);
    if (!activa && (!hasta || hasta < Date.now())) { $("c-gracia").innerHTML = ""; return; }
    const faltan = Math.max(0, rem || (hasta - Date.now()));
    const h = Math.floor(faltan / 3600000);
    const m = Math.floor((faltan % 3600000) / 60000);
    $("c-gracia").innerHTML =
      '<div class="ls-card" style="border-left:4px solid var(--accent)">' +
      "<h3>⏳ Período de gracia activo</h3>" +
      '<p class="ls-hint">Faltan <b>' + h + " h " + m + " min</b>" +
      (hasta ? " (vence " + LS.fecha(hasta) + ")" : "") + ". " +
      "Al vencer se aplica el perfil de cierre y la Tienda se cierra.</p>" +
      '<button class="ls-btn" id="c-gracia-cancelar">Cancelar el vencimiento</button></div>';
    $("c-gracia-cancelar").addEventListener("click", async function () {
      if (!confirm("¿Cancelar el vencimiento?\n\nEl equipo se queda como está ahora, sin cerrar.")) return;
      this.disabled = true;
      const r = await LS.sendCommand(deviceId, "CANCEL_GRACE_PERIOD", null, null,
        { nombre: nombreDispositivo() });
      this.disabled = false;
      LS.toast(...LS.resultado(r, "Vencimiento cancelado."));
    });
  }

  function pintarPresets() {
    const keys = Object.keys(S.presets);
    if (!keys.length) {
      $("c-presets").innerHTML = '<p class="ls-empty">No hay perfiles guardados. Se crean desde la pestaña Perfiles del panel.</p>';
      return;
    }
    $("c-presets").innerHTML = keys.map((k) => {
      const p = S.presets[k] || {};
      const nombre = p.presetName || p.name || k;
      return '<div class="ls-polrow"><label>' + esc(nombre) +
        '<br><small style="color:var(--text-muted)">' + LS.fecha(p.createdAt || 0) + "</small></label>" +
        '<button class="ls-btn ls-btn-sm" data-preset="' + esc(k) + '">Aplicar</button></div>';
    }).join("");
  }

  $("c-presets").addEventListener("click", async (e) => {
    const b = e.target.closest("[data-preset]");
    if (!b) return;
    const presetId = b.dataset.preset;
    const p = S.presets[presetId] || {};
    const nombre = p.presetName || p.name || presetId;
    if (!confirm("¿Aplicar el perfil «" + nombre + "» a " + nombreDispositivo() + "?")) return;
    b.disabled = true;
    try {
      const cloudId = /^[A-Za-z0-9_-]{1,128}$/.test(presetId) ? presetId : "p" + Date.now().toString(36);
      await LS.db.ref("globalSettings/profiles/" + cloudId).set({
        name: nombre,
        createdAt: Date.now(),
        json: JSON.stringify(p)
      });
      const r = await LS.sendCommand(deviceId, "APPLY_PROFILE", null, { profileId: cloudId },
        { nombre: nombreDispositivo(), timeoutMs: 20000 });
      LS.toast(...LS.resultado(r, "Perfil aplicado."));
    } catch (err) {
      LS.toast("No se pudo publicar el perfil: " + err.message, "error");
    }
    b.disabled = false;
  });

  /* ═════════════════════════════════════════════════════════════════════════
   * APPS DEL CELULAR (B.98) — lo que estaba solo en la vista rápida
   *
   * Los datos son los que el equipo reporta en `devices/<id>/apps` (uno por app
   * instalada: oculta, suspendida, web, internet, modo de imágenes, tipo, crítica y,
   * desde B.97, la versión). Cada control es UN comando inmediato, igual que en la
   * vista rápida: se usan de a uno.
   * ═══════════════════════════════════════════════════════════════════════ */
  const ACCIONES_APP = [
    { id: "oculta", campo: "isHidden", on: "HIDE_APP", off: "UNHIDE_APP",
      hacer: "Ocultar", deshacer: "Mostrar", chip: "oculta", cls: "bad",
      ayuda: "Desaparece del celular (ícono, ajustes, todo) hasta que se la vuelva a mostrar." },
    { id: "suspendida", campo: "isSuspended", on: "SUSPEND_APP", off: "UNSUSPEND_APP",
      hacer: "Suspender", deshacer: "Reanudar", chip: "suspendida", cls: "bad",
      ayuda: "Queda en gris: se ve pero no abre." },
    { id: "web", campo: "isWebViewBlocked", on: "BLOCK_WEBVIEW", off: "UNBLOCK_WEBVIEW",
      hacer: "Bloquear su navegador interno", deshacer: "Permitir su navegador interno", chip: "sin web", cls: "warn",
      ayuda: "Corta las páginas web que la app abre adentro suyo (enlaces, publicidad)." },
    { id: "internet", campo: "isInternetBlocked", on: "BLOCK_APP_INTERNET", off: "UNBLOCK_APP_INTERNET",
      hacer: "Cortarle internet", deshacer: "Devolverle internet", chip: "sin internet", cls: "warn",
      ayuda: "La app sigue abriendo, pero sin conexión." }
  ];

  const MODOS_IMAGEN = [
    ["SET_IMAGE_BLOCK_NONE", "Sin tapar imágenes", "none"],
    ["SET_IMAGE_BLOCK_LAYER_1", "Capa 1: por la estructura de la pantalla", "layer1"],
    ["SET_IMAGE_BLOCK_LAYER_2", "Capa 2: IA (siluetas)", "layer2"],
    ["SET_IMAGE_BLOCK_BOTH", "Las dos capas", "both"]
  ];

  function leerInstaladas() {
    const inv = S.device.apps || (S.device.info && S.device.info.apps) ||
      S.device.installedApps || (S.device.info && S.device.info.installedApps) || [];
    const mapa = {};
    if (Array.isArray(inv)) {
      inv.forEach((x) => {
        if (typeof x === "string") mapa[x] = { packageName: x, label: x };
        else if (x && x.packageName) mapa[x.packageName] = x;
      });
    } else {
      Object.keys(inv).forEach((k) => {
        const x = inv[k] || {};
        const pkg = x.packageName || LS.keyPkg(k);
        mapa[pkg] = Object.assign({ packageName: pkg }, x);
      });
    }
    S.instaladas = mapa;
    S.installed = Object.keys(mapa);
  }

  /** "⏳" de un control por app: hasta que el celular reporta lo pedido, o vence. */
  function esperandoDe(pkg, id, actual) {
    const k = pkg + "|" + id;
    const e = S.ui.esperando[k];
    if (!e) return null;
    if (Date.now() > e.hasta || (e.quiere !== undefined && actual === e.quiere)) {
      delete S.ui.esperando[k];
      return null;
    }
    return e;
  }

  async function mandarAccionApp(pkg, def, encender) {
    const k = pkg + "|" + def.id;
    S.ui.esperando[k] = { quiere: encender, hasta: Date.now() + 30000, buzon: false };
    pintarInstaladas(true);
    pintarPlayStore(true);
    const r = await LS.sendCommand(deviceId, encender ? def.on : def.off, [pkg], null, opc());
    if (r.ok && r.pendiente) {
      // En el buzón: se aplica solo. Se sigue mostrando ⏳ (hasta 15 min), no se deshace.
      S.ui.esperando[k] = { quiere: encender, hasta: Date.now() + 15 * 60000, buzon: true };
    } else if (!r.ok) {
      delete S.ui.esperando[k];
    }
    LS.toast(...LS.resultado(r, (encender ? def.hacer : def.deshacer) + ": listo."));
    pintarInstaladas(true);
    pintarPlayStore(true);
  }

  function chipsDeApp(a) {
    const chips = [];
    if (a.isCritical) chips.push('<span class="ls-chip">crítica</span>');
    ACCIONES_APP.forEach((d) => {
      if (a[d.campo] === true) chips.push('<span class="ls-chip ' + d.cls + '">' + d.chip + "</span>");
    });
    const img = a.imageBlockingMode || "none";
    if (img !== "none") {
      const m = MODOS_IMAGEN.find((x) => x[2] === img);
      chips.push('<span class="ls-chip warn">imágenes: ' + esc(m ? m[1].split(":")[0] : img) + "</span>");
    }
    const st = estadoDe(a.packageName);
    if (st === "block") chips.push('<span class="ls-chip bad">prohibida en el filtro</span>');
    else if (st === "allow") chips.push('<span class="ls-chip ok">permitida en el filtro</span>');
    return chips;
  }

  function versionDe(a) {
    if (!a) return "";
    if (a.versionName) return a.versionName + (a.versionCode != null ? " (" + a.versionCode + ")" : "");
    return a.versionCode != null ? String(a.versionCode) : "";
  }

  function pintarInstaladas(forzar) {
    const cont = $("c-inst");
    const q = ($("c-inst-buscar").value || "").trim().toLowerCase();
    const filtro = S.ui.instFiltro;
    const espera = Object.keys(S.ui.esperando).map((k) => [k, S.ui.esperando[k].quiere, S.ui.esperando[k].buzon]);
    if (!cambio("inst", [q, filtro, S.instaladas, espera, Array.from(S.ui.instAbiertos),
      S.appPolicy, S.globalDecisions, S.pendApps, S.ui.rutaUpd]) && !forzar) return;

    document.querySelectorAll("#c-inst-filtro button").forEach((b) =>
      b.classList.toggle("on", b.dataset.f === filtro));

    let apps = Object.values(S.instaladas).filter((a) => a.packageName !== PKG_PLAY);
    $("cnt-cel").textContent = apps.filter((a) => a.appType === "Usuario").length || apps.length || "";
    if (q) {
      apps = apps.filter((a) => (a.label || "").toLowerCase().includes(q) ||
        (a.packageName || "").toLowerCase().includes(q));
    }
    if (filtro === "usuario") apps = apps.filter((a) => a.appType === "Usuario" || !a.appType);
    else if (filtro === "preinstaladas") apps = apps.filter((a) => a.appType === "Preinstalada");
    else if (filtro === "sistema") apps = apps.filter((a) => a.appType === "Sistema");
    else if (filtro === "restringidas") {
      apps = apps.filter((a) => a.isHidden || a.isSuspended || a.isWebViewBlocked ||
        a.isInternetBlocked || (a.imageBlockingMode && a.imageBlockingMode !== "none"));
    }
    apps.sort((a, b) => (a.label || a.packageName).localeCompare(b.label || b.packageName, "es"));

    if (!Object.keys(S.instaladas).length) {
      cont.innerHTML = '<p class="ls-empty">El celular todavía no reportó sus apps.</p>';
      return;
    }
    if (!apps.length) {
      cont.innerHTML = '<p class="ls-empty">No hay apps que coincidan' +
        (filtro !== "todas" ? ' con este filtro. <a href="#" data-instf="todas">Ver todas</a>' : ".") + "</p>";
      return;
    }

    cont.innerHTML = apps.map((a) => {
      const pkg = a.packageName;
      const abierta = S.ui.instAbiertos.has(pkg);
      const chips = chipsDeApp(a);
      const ver = versionDe(a);
      const inicial = String(a.label || pkg).trim().charAt(0).toUpperCase() || "?";
      const botonRapido = (def) => {
        const actual = a[def.campo] === true;
        const e = esperandoDe(pkg, def.id, actual);
        const quiere = e ? e.quiere : !actual;
        const txt = e ? "⏳ " + (e.quiere ? def.hacer : def.deshacer) + (e.buzon ? " (en el buzón)" : "…")
          : (actual ? def.deshacer : def.hacer);
        return '<button class="ls-btn ls-btn-sm' + (actual && !e ? " ls-on" : "") + '" data-acc="' + def.id +
          '" data-quiere="' + (quiere ? "1" : "0") + '"' + (e ? " disabled" : "") + ">" + esc(txt) + "</button>";
      };
      const imgActual = a.imageBlockingMode || "none";
      const eImg = esperandoDe(pkg, "img", imgActual);
      return '<div class="ls-irow' + (a.isHidden || a.isSuspended ? " restr" : "") + '" data-pkg="' + esc(pkg) + '">' +
        '<div class="ls-ihead">' +
          '<span class="ls-avatar">' + esc(inicial) + "</span>" +
          '<div class="ls-appgrow">' +
            '<div class="ls-appname">' + esc(a.label || pkg) + "</div>" +
            '<div class="ls-apppkg">' + esc(pkg) + (ver ? " · v" + esc(ver) : "") + "</div>" +
            (chips.length ? '<div class="ls-chiprow">' + chips.join("") + "</div>" : "") +
          "</div>" +
          (a.isCritical
            ? '<span class="ls-hint" style="margin:0">Crítica para el sistema: no se toca.</span>'
            : botonRapido(ACCIONES_APP[0]) + botonRapido(ACCIONES_APP[1])) +
          '<button class="ls-btn ls-btn-sm" data-mas="1">' + (abierta ? "Menos ▴" : "Más ▾") + "</button>" +
        "</div>" +
        (abierta
          ? '<div class="ls-ibody">' +
            (a.isCritical ? "" :
              ACCIONES_APP.slice(2).map((d) =>
                '<div class="ls-kv"><span>' + esc(d.ayuda) + "</span><span>" + botonRapido(d) + "</span></div>").join("") +
              '<div class="ls-kv"><span>Tapar imágenes en esta app</span><span>' +
                '<select class="ls-select" data-img="1"' + (eImg ? " disabled" : "") + ">" +
                MODOS_IMAGEN.map((m) => '<option value="' + m[2] + '"' +
                  ((eImg ? eImg.quiere : imgActual) === m[2] ? " selected" : "") + ">" + esc(m[1]) + "</option>").join("") +
                "</select>" + (eImg ? " ⏳" : "") + "</span></div>") +
            '<div class="ls-kv"><span>Actualizar esta app' +
              '<br><small>Camino: ' + esc(nombreRuta(S.ui.rutaUpd)) + ' · <a href="#" data-ir="actualizaciones">cambiar</a></small></span>' +
              '<span><button class="ls-btn ls-btn-sm" data-upd="1">⬆️ Actualizar</button></span></div>' +
            '<div class="ls-kv"><span>En el filtro de apps</span><span>' +
              '<a href="#" data-filtro-app="' + esc(pkg) + '">' + esc(textoEstadoFiltro(estadoDe(pkg))) + " →</a></span></div>" +
            "</div>"
          : "") +
      "</div>";
    }).join("");
  }

  function textoEstadoFiltro(st) {
    return st === "allow" ? "Permitida" : st === "block" ? "Prohibida" : "Sin marcar";
  }

  $("c-inst-filtro").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-f]");
    if (!b) return;
    S.ui.instFiltro = b.dataset.f;
    pintarInstaladas();
  });
  $("c-inst-buscar").addEventListener("input", () => pintarInstaladas());

  $("c-inst").addEventListener("click", (e) => {
    const verTodas = e.target.closest("[data-instf]");
    if (verTodas) { e.preventDefault(); S.ui.instFiltro = verTodas.dataset.instf; pintarInstaladas(); return; }
    const fila = e.target.closest(".ls-irow");
    if (!fila) return;
    const pkg = fila.dataset.pkg;
    const a = S.instaladas[pkg] || { packageName: pkg };

    if (e.target.closest("[data-mas]")) {
      if (S.ui.instAbiertos.has(pkg)) S.ui.instAbiertos.delete(pkg); else S.ui.instAbiertos.add(pkg);
      pintarInstaladas();
      return;
    }
    const acc = e.target.closest("[data-acc]");
    if (acc) {
      const def = ACCIONES_APP.find((d) => d.id === acc.dataset.acc);
      const encender = acc.dataset.quiere === "1";
      if (def.id === "oculta" && encender && !confirm("¿Ocultar «" + (a.label || pkg) + "»?\n\n" +
        "Desaparece del celular por completo hasta que se la vuelva a mostrar desde acá.")) return;
      mandarAccionApp(pkg, def, encender);
      return;
    }
    if (e.target.closest("[data-upd]")) {
      pedirActualizacion(pkg, a.label || pkg);
      return;
    }
    const fa = e.target.closest("[data-filtro-app]");
    if (fa) {
      e.preventDefault();
      $("c-apps-buscar").value = fa.dataset.filtroApp;
      $("c-apps-filtro").value = "todas";
      irA("apps");
      pintarApps();
    }
  });

  $("c-inst").addEventListener("change", async (e) => {
    const sel = e.target.closest("select[data-img]");
    if (!sel) return;
    const pkg = sel.closest(".ls-irow").dataset.pkg;
    const m = MODOS_IMAGEN.find((x) => x[2] === sel.value);
    if (!m) return;
    const k = pkg + "|img";
    S.ui.esperando[k] = { quiere: m[2], hasta: Date.now() + 30000, buzon: false };
    pintarInstaladas(true);
    const r = await LS.sendCommand(deviceId, m[0], [pkg], null, opc());
    if (r.ok && r.pendiente) S.ui.esperando[k] = { quiere: m[2], hasta: Date.now() + 15 * 60000, buzon: true };
    else if (!r.ok) delete S.ui.esperando[k];
    LS.toast(...LS.resultado(r, "Imágenes: " + m[1] + "."));
    pintarInstaladas(true);
  });

  /* ── Play Store: lo que en la vista rápida es la tarjeta "Control principal" ── */
  const ACC_PLAY = [ACCIONES_APP[0], ACCIONES_APP[1]];

  function pintarPlayStore(forzar) {
    const vend = S.instaladas[PKG_PLAY] || null;
    const bloqueoInstalacion = campo("installAppsBlocked", false) === true;
    const ocultarIconos = campo("hideSuspendedApps", false) === true;
    const espera = Object.keys(S.ui.esperando).filter((k) => k.startsWith(PKG_PLAY + "|") || k === "hideSuspended")
      .map((k) => [k, S.ui.esperando[k].quiere]);
    if (!cambio("play", [vend, bloqueoInstalacion, ocultarIconos, espera]) && !forzar) return;

    const fila = (def) => {
      // Sin datos de la app se muestra lo que implica el bloqueo de instalación, como la vista rápida.
      const actual = vend ? vend[def.campo] === true : bloqueoInstalacion;
      const e = esperandoDe(PKG_PLAY, def.id, actual);
      const val = e ? e.quiere : actual;
      return '<div class="ls-polrow"><label for="play-' + def.id + '">' +
        (def.id === "oculta" ? "Oculta (bloqueo total)" : "Suspendida") +
        (e ? ' <span class="ls-polmark">· ⏳ ' + (e.buzon ? "en el buzón" : "aplicando") + "</span>" : "") +
        "</label>" +
        '<span class="ls-sw"><input type="checkbox" id="play-' + def.id + '" data-play="' + def.id + '"' +
        (val ? " checked" : "") + (e ? " disabled" : "") + "><span></span></span></div>";
    };
    const eIco = esperandoDe("hideSuspended", "x", ocultarIconos);
    $("c-play").innerHTML =
      "<h3>Google Play Store</h3>" +
      '<p class="ls-hint">Se controla aparte del resto porque es la puerta de entrada de todas las apps. ' +
      "Mientras dura una actualización por Google Play, LockSuite la destapa sola y la vuelve a cerrar al terminar." +
      (vend && versionDe(vend) ? " Versión en el equipo: " + esc(versionDe(vend)) + "." : "") + "</p>" +
      ACC_PLAY.map(fila).join("") +
      '<div class="ls-polrow"><label for="play-iconos">Ocultar el ícono de las apps suspendidas<br>' +
      '<small style="color:var(--text-muted)">Vale para todas las apps del celular, no solo Play Store.</small>' +
      (eIco ? ' <span class="ls-polmark">· ⏳ ' + (eIco.buzon ? "en el buzón" : "aplicando") + "</span>" : "") + "</label>" +
      '<span class="ls-sw"><input type="checkbox" id="play-iconos"' + ((eIco ? eIco.quiere : ocultarIconos) ? " checked" : "") +
      (eIco ? " disabled" : "") + "><span></span></span></div>";
  }

  $("c-play").addEventListener("change", async (e) => {
    const inp = e.target;
    if (inp.dataset && inp.dataset.play) {
      const def = ACC_PLAY.find((d) => d.id === inp.dataset.play);
      mandarAccionApp(PKG_PLAY, def, inp.checked);
      return;
    }
    if (inp.id === "play-iconos") {
      const quiere = inp.checked;
      S.ui.esperando["hideSuspended|x"] = { quiere: quiere, hasta: Date.now() + 30000, buzon: false };
      pintarPlayStore(true);
      const r = await LS.sendCommand(deviceId, "SET_HIDE_SUSPENDED_APPS", null, { enabled: quiere }, opc());
      if (r.ok && r.pendiente) S.ui.esperando["hideSuspended|x"] = { quiere: quiere, hasta: Date.now() + 15 * 60000, buzon: true };
      else if (!r.ok) delete S.ui.esperando["hideSuspended|x"];
      LS.toast(...LS.resultado(r, "Listo."));
      pintarPlayStore(true);
    }
  });

  /* ── Desbloquear todas / lista de apps permitidas ── */
  $("c-unsuspend-all").addEventListener("click", async function () {
    if (!confirm("¿Desbloquear todas las apps de " + nombreDispositivo() + "?\n\n" +
      "Se saca la suspensión de todas (menos las críticas) y se vacía la lista de apps permitidas.")) return;
    this.disabled = true;
    const r = await LS.sendCommand(deviceId, "UNSUSPEND_ALL_APPS", null, null, opc());
    this.disabled = false;
    LS.toast(...LS.resultado(r, "Todas las apps desbloqueadas."));
  });

  function pintarAllowlist() {
    const v = campo("allowedPackages", "");
    const txt = Array.isArray(v) ? v.join(", ") : String(v || "");
    if (document.activeElement !== $("c-allowlist") && cambio("allowlist", txt)) $("c-allowlist").value = txt;
  }

  $("c-allowlist-btn").addEventListener("click", async function () {
    const lista = $("c-allowlist").value.split(",").map((s) => s.trim()).filter(Boolean);
    const malos = lista.filter((p) => !/^[a-zA-Z0-9_.]+$/.test(p));
    if (malos.length) { LS.toast("Estos no parecen paquetes: " + malos.join(", "), "error"); return; }
    if (!lista.length) {
      // La Function rechaza una lista vacía (y el celular también: no aplicaría nada). Lo
      // que la vacía de verdad es «Desbloquear todas», que además saca las suspensiones.
      LS.toast("La lista no se puede mandar vacía. Para vaciarla usá «Desbloquear todas» " +
        "(que además saca la suspensión de todas las apps).", "warn");
      return;
    }
    this.disabled = true;
    const r = await LS.sendCommand(deviceId, "UPDATE_ALLOWLIST", lista, null, opc());
    this.disabled = false;
    LS.toast(...LS.resultado(r, "Lista de apps permitidas guardada."));
  });

  /* ═════════════════════════════════════════════════════════════════════════
   * ACTUALIZACIONES (B.97 en el celular, B.98 acá)
   *
   * El celular publica en `devices/<id>/updateFlow` el estado (etapa, camino y por
   * qué, porcentaje, de qué versión a cuál, si espera que lo desbloqueen) y en
   * `devices/<id>/updateFlowSteps` el registro paso a paso. Acá se muestra todo, en
   * vivo, y se puede elegir el camino al pedir una actualización.
   * ═══════════════════════════════════════════════════════════════════════ */
  const RUTAS = {
    auto: ["Automático", "Las apps que instaló Google Play se actualizan por Google Play, que tiene siempre la última " +
      "versión. Las que vinieron de la Tienda de LockSuite, de un APK o por ADB se actualizan en silencio desde la " +
      "Tienda (si están cargadas ahí), porque Google Play no puede reemplazar una app firmada por otro. Si la copia " +
      "de la Tienda no sirve, sigue solo por Google Play."],
    play: ["Solo Google Play", "Abre la ficha de la app en Google Play, tapa la pantalla con el cartel negro y aprieta " +
      "«Actualizar» sola. Necesita la Accesibilidad activa y una cuenta de Google. Si el celular está bloqueado, " +
      "espera a que lo desbloqueen (avisa en la pantalla de bloqueo)."],
    hosted: ["Solo Tienda de LockSuite", "Baja la copia cargada en la Tienda y la instala en silencio, sin abrir " +
      "Google Play ni tocar la pantalla: el usuario sigue usando el teléfono. Solo si esa copia es más nueva que la " +
      "instalada y está firmada igual."]
  };
  const nombreRuta = (r) => (RUTAS[r] || RUTAS.auto)[0];

  const ETIQUETA_CAMINO = {
    PLAY_STORE: ["Google Play", ""],
    HOSTED: ["Tienda de LockSuite (en silencio)", "ok"],
    "": ["eligiendo el camino…", "warn"]
  };

  function instaladorLegible(inst) {
    if (inst === undefined || inst === null) return "";
    if (inst === "") return "desconocido (APK, ADB o de fábrica)";
    if (inst === PKG_PLAY) return "Google Play";
    if (inst === PKG_LOCKSUITE) return "la Tienda de LockSuite";
    return inst;
  }

  function textoResultado(f) {
    const porTienda = f.route === "HOSTED";
    switch (f.lastResult) {
      case "UPDATED": return "✓ Actualizada" + (f.versionAfter ? " a la versión " + f.versionAfter : "");
      case "UP_TO_DATE": return "✓ Ya estaba en la última versión" + (porTienda ? " (la de la Tienda)" : "");
      case "NO_SPACE": return "✗ No hay espacio en el celular";
      case "NEEDS_ACCOUNT": return "✗ El celular no tiene cuenta de Google";
      case "NOT_AVAILABLE": return porTienda ? "✗ No está disponible en la Tienda de LockSuite"
        : "✗ Google Play no ofrece esa actualización para este equipo";
      case "STORE_ERROR": return "✗ Error de Google Play";
      case "CANCELLED": return "Cancelada";
      case "TIMEOUT": return "✗ Tardó demasiado y se cortó";
      case "ERROR": return "✗ No se pudo actualizar";
      default: return "Terminó (" + (f.lastResult || "?") + ")";
    }
  }

  function flujo() { return campo("updateFlow", null) || {}; }

  function pasosDelFlujo() {
    const crudo = S.device.updateFlowSteps;
    const lista = Array.isArray(crudo) ? crudo : crudo && typeof crudo === "object" ? Object.values(crudo) : [];
    return lista.filter((s) => s && s.m).sort((a, b) => (a.t || 0) - (b.t || 0));
  }

  const MARCA_PASO = { ok: ["✓", "ok"], error: ["✗", "bad"], warn: ["!", "warn"], act: ["›", "act"], info: ["·", ""] };

  function etiquetaApp(pkg) {
    const a = S.instaladas[pkg];
    return (a && a.label) || pkg || "";
  }

  function pintarActualizaciones() {
    pintarOta();
    const f = flujo();
    const pasos = pasosDelFlujo();
    const vivo = f.running === true;
    const vCode = Number(campo("versionCode", 0)) || 0;
    if (!cambio("upd", [f, pasos, vivo, vCode, S.instaladas[f.packageName || f.lastResultPackage || ""] || null])) return;

    // Cabecera y rail
    $("c-upd-chip").classList.toggle("hidden", !vivo);
    $("cnt-upd").textContent = vivo ? "●" : "";

    // ── En curso ──
    if (vivo) {
      const cam = ETIQUETA_CAMINO[f.route == null ? "PLAY_STORE" : f.route] || [f.route, ""];
      const pct = typeof f.progress === "number" && f.progress >= 0 ? f.progress : null;
      $("c-upd-vivo").innerHTML =
        '<div class="ls-card ls-live">' +
          '<div class="ls-row" style="justify-content:space-between">' +
            "<h3 style=\"margin:0\">⬆️ Actualizando " + esc(etiquetaApp(f.packageName)) + "</h3>" +
            '<span class="ls-chip ' + cam[1] + '">' + esc(cam[0]) + "</span>" +
          "</div>" +
          '<div class="ls-apppkg" style="margin:2px 0 10px">' + esc(f.packageName || "") +
            (f.versionBefore ? " · ahora v" + esc(f.versionBefore) : "") + "</div>" +
          '<div class="ls-stage">' + esc(f.statusText || f.stage || "…") + "</div>" +
          (pct !== null ? '<div class="ls-bar"><span style="width:' + pct + '%"></span></div>' : '<div class="ls-bar ls-bar-ind"><span></span></div>') +
          (f.keyguardLocked ? '<p class="ls-hint" style="color:var(--warning-yellow); margin:10px 0 0">🔒 El celular está bloqueado: ' +
            "la actualización espera a que lo desbloqueen (la pantalla no se tapa para que se pueda desbloquear).</p>" : "") +
          (f.routeWhy ? '<p class="ls-hint" style="margin:10px 0 0"><b>Por qué este camino:</b> ' + esc(f.routeWhy) + "</p>" : "") +
          '<p class="ls-hint" style="margin:6px 0 0">' +
            (f.source === "local" ? "Pedida desde el celular" : "Pedida desde el panel") +
            (f.startedAt ? ' · hace <span data-desde="' + Number(f.startedAt) + '">' + esc(hace(Date.now() - f.startedAt)) + "</span>" : "") +
            (f.installer !== undefined ? " · la instaló: " + esc(instaladorLegible(f.installer)) : "") +
          "</p>" +
          (f.debugLabels ? '<p class="ls-hint" style="margin:6px 0 0; font-size:11px">Botones que ve en Google Play: ' + esc(f.debugLabels) + "</p>" : "") +
          '<div class="ls-row" style="margin-top:12px"><button class="ls-btn" id="c-upd-cancelar"' +
            (f.cancelable === false ? " disabled" : "") + ">✖ Cancelar</button></div>" +
        "</div>";
      $("c-upd-cancelar").addEventListener("click", async function () {
        if (!confirm("¿Cancelar la actualización en curso?")) return;
        this.disabled = true;
        const r = await LS.sendCommand(deviceId, "CANCEL_UPDATE_APP", null, null, opc());
        this.disabled = false;
        LS.toast(...LS.resultado(r, "Pedido de cancelación enviado."));
      });
    } else {
      $("c-upd-vivo").innerHTML = "";
    }

    // ── Registro paso a paso ──
    if (pasos.length) {
      const base = pasos[0].t || 0;
      const app = etiquetaApp(vivo ? f.packageName : (f.lastResultPackage || f.packageName));
      $("c-upd-pasos").innerHTML =
        '<div class="ls-card">' +
          "<h3>📜 Paso a paso" + (vivo ? " (en vivo)" : " de la última actualización" + (app ? " — " + esc(app) : "")) + "</h3>" +
          '<p class="ls-hint">Cada decisión que tomó el celular, con su tiempo desde el pedido. Es lo mismo ' +
          "que se ve en la pantalla negra del teléfono, completo.</p>" +
          '<ol class="ls-steps">' + pasos.map((s) => {
            const mk = MARCA_PASO[s.k] || MARCA_PASO.info;
            return '<li class="' + mk[1] + '"><span class="ls-steptime">+' + esc(hace((s.t || 0) - base)) + "</span>" +
              '<span class="ls-stepmark">' + mk[0] + "</span><span>" + esc(s.m) + "</span></li>";
          }).join("") + "</ol>" +
          (pasos.length >= 40 ? '<p class="ls-hint">(Se guardan los 40 pasos más recientes; el primero, el pedido, siempre queda.)</p>' : "") +
        "</div>";
    } else {
      $("c-upd-pasos").innerHTML = vCode && vCode < VERSION_CON_B97
        ? '<div class="ls-card ls-alert warn">Este celular tiene LockSuite ' + esc(String(campo("versionName", vCode))) +
          ": actualiza las apps solo por Google Play y no publica el paso a paso. Actualizá LockSuite (abajo) para tener " +
          "el proceso completo a la vista y poder elegir el camino.</div>"
        : "";
    }

    // ── Último resultado ──
    if (!vivo && f.lastResult) {
      const ok = f.lastResult === "UPDATED" || f.lastResult === "UP_TO_DATE";
      $("c-upd-ultimo").innerHTML =
        '<div class="ls-card ls-alert ' + (ok ? "okb" : f.lastResult === "CANCELLED" ? "" : "bad") + '">' +
          "<h3 style=\"margin:0 0 6px\">Última actualización: " + esc(etiquetaApp(f.lastResultPackage)) + "</h3>" +
          '<div class="ls-stage">' + esc(textoResultado(f)) + "</div>" +
          (f.lastResultReason && !ok ? '<p class="ls-hint" style="margin:8px 0 0"><b>Motivo:</b> ' + esc(f.lastResultReason) + "</p>" : "") +
          '<p class="ls-hint" style="margin:8px 0 0">' +
            (f.lastResultAt ? LS.fecha(f.lastResultAt) : "") +
            (f.route ? " · camino: " + esc((ETIQUETA_CAMINO[f.route] || [f.route])[0]) : "") +
            (f.versionBefore && f.versionAfter && f.lastResult === "UPDATED" ? " · " + esc(f.versionBefore) + " → " + esc(f.versionAfter) : "") +
            (typeof f.freeSpaceMb === "number" && f.freeSpaceMb >= 0 ? " · espacio libre: " + esc(String(f.freeSpaceMb)) + " MB" : "") +
          "</p>" +
        "</div>";
    } else {
      $("c-upd-ultimo").innerHTML = "";
    }

    pintarResumenActualizacion(f, vivo);
  }

  /** Tarjetita del Resumen: la actualización en curso, o el resultado si es de las últimas 24 h. */
  function pintarResumenActualizacion(f, vivo) {
    const reciente = f.lastResultAt && Date.now() - f.lastResultAt < 24 * 3600000;
    if (!vivo && !(f.lastResult && reciente)) { $("c-upd-resumen").innerHTML = ""; return; }
    $("c-upd-resumen").innerHTML =
      '<div class="ls-card ls-row" style="justify-content:space-between">' +
        "<span>" + (vivo
          ? "⬆️ <b>Actualizando " + esc(etiquetaApp(f.packageName)) + "</b> — " + esc(f.statusText || f.stage || "")
          : "⬆️ Última actualización (" + esc(etiquetaApp(f.lastResultPackage)) + "): " + esc(textoResultado(f))) +
        "</span>" +
        '<button class="ls-btn ls-btn-sm" data-ir="actualizaciones">' + (vivo ? "Ver el proceso" : "Ver detalle") + "</button>" +
      "</div>";
  }

  // El "hace N s" de la actualización en curso, sin redibujar la tarjeta entera.
  setInterval(() => {
    document.querySelectorAll("[data-desde]").forEach((el) => {
      el.textContent = hace(Date.now() - Number(el.dataset.desde));
    });
  }, 1000);

  $("c-upd-chip").addEventListener("click", () => irA("actualizaciones"));

  function pintarRutaElegida() {
    document.querySelectorAll("#c-upd-ruta button").forEach((b) => {
      b.className = b.dataset.ruta === S.ui.rutaUpd ? "on-allow" : "";
    });
    $("c-upd-ruta-txt").textContent = (RUTAS[S.ui.rutaUpd] || RUTAS.auto)[1];
  }

  $("c-upd-ruta").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-ruta]");
    if (!b) return;
    S.ui.rutaUpd = b.dataset.ruta;
    pintarRutaElegida();
    pintarListaActualizar();
    pintarInstaladas();
  });

  function pintarListaActualizar() {
    const q = ($("c-upd-buscar").value || "").trim().toLowerCase();
    const sistema = $("c-upd-sistema").checked;
    if (!cambio("updlista", [q, sistema, S.instaladas, S.storeApps, S.ui.rutaUpd])) return;
    let apps = Object.values(S.instaladas).filter((a) =>
      a.packageName !== PKG_PLAY && a.packageName !== PKG_LOCKSUITE);
    if (!sistema) apps = apps.filter((a) => a.appType !== "Sistema");
    if (q) apps = apps.filter((a) => (a.label || "").toLowerCase().includes(q) || (a.packageName || "").toLowerCase().includes(q));
    // Las del usuario primero: son las que se actualizan casi siempre.
    const orden = { Usuario: 0, Preinstalada: 1, Sistema: 2 };
    apps.sort((a, b) => ((orden[a.appType] || 0) - (orden[b.appType] || 0)) ||
      (a.label || a.packageName).localeCompare(b.label || b.packageName, "es"));

    if (!apps.length) {
      $("c-upd-lista").innerHTML = '<p class="ls-empty">' +
        (Object.keys(S.instaladas).length ? "Ninguna app coincide." : "El celular todavía no reportó sus apps.") + "</p>";
      return;
    }
    $("c-upd-lista").innerHTML = '<div class="ls-updlist">' + apps.map((a) => {
      const t = enTienda(a.packageName);
      const chips = [];
      if (t && t.sha256) chips.push('<span class="ls-chip ok" title="Se puede actualizar en silencio">en la Tienda</span>');
      if (a.appType && a.appType !== "Usuario") chips.push('<span class="ls-chip">' + esc(a.appType.toLowerCase()) + "</span>");
      return '<div class="ls-updrow" data-pkg="' + esc(a.packageName) + '">' +
        '<div class="ls-appgrow"><div class="ls-appname">' + esc(a.label || a.packageName) + "</div>" +
        '<div class="ls-apppkg">' + esc(a.packageName) + (versionDe(a) ? " · v" + esc(versionDe(a)) : "") + "</div>" +
        (chips.length ? '<div class="ls-chiprow">' + chips.join("") + "</div>" : "") + "</div>" +
        '<button class="ls-btn ls-btn-sm" data-upd="1">⬆️ Actualizar</button></div>';
    }).join("") + "</div>";
  }

  $("c-upd-buscar").addEventListener("input", pintarListaActualizar);
  $("c-upd-sistema").addEventListener("change", pintarListaActualizar);
  $("c-upd-lista").addEventListener("click", (e) => {
    const b = e.target.closest("[data-upd]");
    if (!b) return;
    const pkg = b.closest(".ls-updrow").dataset.pkg;
    pedirActualizacion(pkg, etiquetaApp(pkg), b);
  });

  /**
   * Pide la actualización con el camino elegido. "auto" NO viaja (es lo que hace el
   * celular sin el campo, y así el comando es idéntico al de siempre); los otros van
   * en `route`, dentro del payload firmado (functions/index.js valida los tres).
   */
  async function pedirActualizacion(pkg, label, boton, rutaForzada) {
    const ruta = rutaForzada || S.ui.rutaUpd;
    const f = flujo();
    if (f.running === true) {
      LS.toast("Ya hay una actualización en curso (" + etiquetaApp(f.packageName) + "). Esperá a que termine o cancelala.", "warn");
      irA("actualizaciones");
      return;
    }
    if (!confirm("¿Actualizar «" + label + "» en " + nombreDispositivo() + "?\n\n" +
      "Camino: " + nombreRuta(ruta) + "\n" + (RUTAS[ruta] || RUTAS.auto)[1])) return;
    if (boton) boton.disabled = true;
    $("c-upd-estado").textContent = "Mandando…";
    const r = await LS.sendCommand(deviceId, "UPDATE_APP", pkg,
      ruta === "auto" ? null : { route: ruta },
      { nombre: nombreDispositivo(), timeoutMs: 20000 });
    if (boton) boton.disabled = false;
    $("c-upd-estado").textContent = r.ok && r.pendiente
      ? "⏳ Todavía no arrancó: " + r.reason
      : r.ok
      ? "✓ Arrancó. El proceso se ve arriba, paso a paso."
      : "✗ No arrancó: " + (r.reason || r.status);
    LS.toast(...LS.resultado(r, "La actualización de " + label + " arrancó."));
    if (r.ok) { irA("actualizaciones"); }
  }

  $("c-upd-btn").addEventListener("click", function () {
    const pkg = $("c-upd-pkg").value.trim();
    if (!pkg) return;
    if (!/^[a-zA-Z0-9_.]+$/.test(pkg)) { LS.toast("Eso no parece un nombre de paquete (ej. com.whatsapp).", "error"); return; }
    pedirActualizacion(pkg, etiquetaApp(pkg), this);
  });

  /** LockSuite mismo (OTA). El progreso lo publica el equipo en `updateProgress`. */
  function pintarOta() {
    const vName = campo("versionName", "—");
    const vCode = campo("versionCode", "—");
    const prog = S.device.updateProgress;
    if (!cambio("ota", [vName, vCode, prog])) return;
    const bajando = typeof prog === "number";
    $("c-ota").innerHTML =
      "<h3>🛡️ LockSuite (este programa)</h3>" +
      '<p class="ls-hint">Versión instalada: <b>' + esc(String(vName)) + "</b> (" + esc(String(vCode)) + "). " +
      "Se actualiza sola desde el servidor de LockSuite (no por Google Play).</p>" +
      (bajando ? '<div class="ls-stage">Descargando: ' + esc(String(prog)) + ' %</div><div class="ls-bar"><span style="width:' +
        Math.max(0, Math.min(100, prog)) + '%"></span></div>' : "") +
      '<div class="ls-row" style="margin-top:10px"><button class="ls-btn" id="c-ota-btn"' + (bajando ? " disabled" : "") +
      ">⬆️ Actualizar LockSuite ahora</button></div>";
    $("c-ota-btn").addEventListener("click", async function () {
      if (!confirm("¿Actualizar LockSuite en " + nombreDispositivo() + "?")) return;
      this.disabled = true;
      const r = await LS.sendCommand(deviceId, "UPDATE_LOCKSUITE", null, null, { nombre: nombreDispositivo(), timeoutMs: 120000 });
      this.disabled = false;
      LS.toast(...LS.resultado(r, "LockSuite se está actualizando."));
    });
  }

  /* ═════════════════════════════════════════════════════════════════════════
   * DNS
   * ═══════════════════════════════════════════════════════════════════════ */
  const NOMBRE_REGLA = {
    FORCE_ALLOW: ["Forzar permitir", "ok"],
    FORCE_BLOCK: ["Forzar prohibir", "bad"],
    ALLOW: ["Permitir", "ok"],
    BLOCK: ["Prohibir", "bad"]
  };

  function pintarDns() {
    const reglas = campo("dnsRules", {}) || {};
    const audit = campo("whitelistAudit", {}) || {};
    if (!cambio("dns", [reglas, audit])) return;
    const keys = Object.keys(reglas);
    if (!keys.length) {
      $("c-dns-lista").innerHTML = '<p class="ls-empty">Este celular no tiene reglas propias.</p>';
    } else {
      $("c-dns-lista").innerHTML =
        '<div class="ls-tablewrap"><table class="ls-table"><thead><tr>' +
        "<th>Dominio</th><th>Regla</th><th></th></tr></thead><tbody>" +
        keys.map((k) => {
          const r = reglas[k] || {};
          const n = NOMBRE_REGLA[r.rule] || [r.rule, ""];
          return "<tr><td style=\"font-family:monospace\">" + esc(r.domain || LS.keyPkg(k)) + "</td>" +
            '<td><span class="ls-chip ' + n[1] + '">' + esc(n[0]) + "</span></td>" +
            '<td style="text-align:right"><button class="ls-btn ls-btn-sm" data-quitar="' +
            esc(r.domain || LS.keyPkg(k)) + '">Quitar</button></td></tr>';
        }).join("") + "</tbody></table></div>";
    }

    const filas = Object.keys(audit).map((k) => audit[k]).filter(Boolean)
      .sort((a, b) => (b.hits || 0) - (a.hits || 0));
    if (!filas.length) {
      $("c-auditoria").innerHTML = '<p class="ls-empty">Sin registro. Aparece cuando la lista blanca está encendida.</p>';
    } else {
      $("c-auditoria").innerHTML =
        '<div class="ls-tablewrap"><table class="ls-table"><thead><tr>' +
        "<th>Dominio</th><th>Intentos</th><th></th></tr></thead><tbody>" +
        filas.slice(0, 200).map((f) =>
          "<tr><td style=\"font-family:monospace\">" + esc(f.domain) + "</td><td>" + esc(String(f.hits || 0)) + "</td>" +
          '<td style="text-align:right"><button class="ls-btn ls-btn-sm" data-permitir="' +
          esc(f.domain) + '">Forzar permitir</button></td></tr>').join("") +
        "</tbody></table></div>";
    }
  }

  async function reglaDns(dominio, tipo) {
    const cmd = tipo === "REMOVE" ? "REMOVE_DOMAIN_RULE" : "SET_DOMAIN_RULE_" + tipo;
    const r = await LS.sendCommand(deviceId, cmd, dominio, null, { nombre: nombreDispositivo() });
    LS.toast(...LS.resultado(r, "Regla aplicada en el celular."));
  }

  $("c-dns-add").addEventListener("click", async function () {
    const d = $("c-dns-dominio").value.trim().toLowerCase().replace(/\.$/, "");
    if (!d || !/^[a-z0-9.-]+\.[a-z]{2,}$/.test(d)) {
      LS.toast("Escribí un dominio válido, por ejemplo ejemplo.com", "error");
      return;
    }
    this.disabled = true;
    await reglaDns(d, $("c-dns-tipo").value);
    this.disabled = false;
    $("c-dns-dominio").value = "";
  });

  $("c-dns-lista").addEventListener("click", async (e) => {
    const b = e.target.closest("[data-quitar]");
    if (!b) return;
    b.disabled = true;
    await reglaDns(b.dataset.quitar, "REMOVE");
    b.disabled = false;
  });

  $("c-auditoria").addEventListener("click", async (e) => {
    const b = e.target.closest("[data-permitir]");
    if (!b) return;
    b.disabled = true;
    await reglaDns(b.dataset.permitir, "FORCE_ALLOW");
    b.disabled = false;
  });

  /* ═════════════════════════════════════════════════════════════════════════
   * TIENDA
   * ═══════════════════════════════════════════════════════════════════════ */
  function pintarTienda() {
    const keys = Object.keys(S.storeApps);
    if (!cambio("tienda", [S.storeApps, S.appPolicy, S.globalDecisions, S.pendApps,
      keys.map((k) => S.instaladas[(S.storeApps[k] || {}).packageName || LS.keyPkg(k)] || null)])) return;
    if (!keys.length) {
      $("c-tienda").innerHTML = '<p class="ls-empty">No hay apps cargadas en la Tienda.</p>';
      return;
    }
    $("c-tienda").innerHTML =
      '<div class="ls-tablewrap"><table class="ls-table"><thead><tr>' +
      "<th>App</th><th>Estado en este celular</th><th>En el celular</th><th>Huella</th><th>Firmante</th>" +
      "</tr></thead><tbody>" +
      keys.map((k) => {
        const a = S.storeApps[k] || {};
        const pkg = a.packageName || LS.keyPkg(k);
        const st = estadoDe(pkg);
        const inst = S.instaladas[pkg] || null;
        const chipSt = st === "allow" ? '<span class="ls-chip ok">permitida</span>'
          : st === "block" ? '<span class="ls-chip bad">prohibida</span>'
          : '<span class="ls-chip">sin marcar</span>';
        const chipHash = a.sha256
          ? '<span class="ls-chip ok" title="' + esc(a.sha256) + '">✓ verificable</span>'
          : '<span class="ls-chip bad">sin huella — no se instala</span>';
        // El firmante lo publica el equipo al instalar; si no está, se dice que
        // no se sabe en vez de dar a entender que está todo bien.
        const firm = a.signerLabel
          ? (a.signerTrusted === false
              ? '<span class="ls-chip warn" title="' + esc(a.signerLabel) + '">firmante no oficial</span>'
              : '<span class="ls-chip">' + esc(a.signerLabel) + "</span>")
          : '<span class="ls-chip">sin verificar</span>';
        const enCel = inst
          ? "instalada" + (versionDe(inst) ? " · v" + esc(versionDe(inst)) : "") +
            (a.sha256 ? '<br><button class="ls-btn ls-btn-sm" data-tienda-upd="' + esc(pkg) + '" style="margin-top:4px">⬆️ Actualizar desde la Tienda</button>' : "")
          : '<span style="color:var(--text-muted)">no instalada</span>';
        return "<tr><td><b>" + esc(a.label || a.name || pkg) + "</b><br>" +
          '<span class="ls-apppkg">' + esc(pkg) + "</span></td>" +
          "<td>" + chipSt + "</td><td>" + enCel + "</td><td>" + chipHash + "</td><td>" + firm + "</td></tr>";
      }).join("") + "</tbody></table></div>" +
      '<p class="ls-hint" style="margin-top:12px">La Tienda es <b>global</b>: las apps y sus huellas ' +
      "se cargan una vez para toda la flota, desde Ajustes → Tienda en el panel. Lo que cambia por " +
      "celular es si esa app está permitida. «Actualizar desde la Tienda» instala en silencio la copia " +
      "cargada, solo si es más nueva que la del celular y está firmada igual.</p>";
  }

  $("c-tienda").addEventListener("click", (e) => {
    const b = e.target.closest("[data-tienda-upd]");
    if (!b) return;
    const pkg = b.dataset.tiendaUpd;
    pedirActualizacion(pkg, etiquetaApp(pkg), b, "hosted");
  });

  function pintarPedidos() {
    const pedidos = campo("appRequests", {}) || {};
    if (!cambio("pedidos", pedidos)) return;
    const keys = Object.keys(pedidos);
    if (!keys.length) {
      $("c-pedidos").innerHTML = '<p class="ls-empty">Sin pedidos.</p>';
      return;
    }
    $("c-pedidos").innerHTML = keys.map((k) => {
      const p = pedidos[k] || {};
      const pkg = p.packageName || LS.keyPkg(k);
      return '<div class="ls-polrow"><label><b>' + esc(p.label || pkg) + "</b><br>" +
        '<span class="ls-apppkg">' + esc(pkg) + "</span> · " + esc(p.status || "pendiente") +
        "</label>" +
        '<span><button class="ls-btn ls-btn-sm" data-aprobar="' + esc(pkg) + '" data-key="' + esc(k) + '">Aprobar</button> ' +
        '<button class="ls-btn ls-btn-sm ls-btn-danger" data-rechazar="' + esc(k) + '">Rechazar</button></span></div>';
    }).join("");
  }

  $("c-pedidos").addEventListener("click", async (e) => {
    const ap = e.target.closest("[data-aprobar]");
    if (ap) {
      // Aprobar = permitir la app en este equipo + marcar el pedido como aprobado.
      const pkg = ap.dataset.aprobar;
      const key = ap.dataset.key || LS.pkgKey(pkg);
      try {
        await LS.db.ref("devices/" + deviceId + "/appRequests/" + key).update({
          status: "approved",
          resolvedAt: Date.now(),
          notified: false
        });
      } catch (_) {}
      S.pendApps[pkg] = "allow";
      pintarCarrito();
      irA("apps");
      $("c-apps-buscar").value = pkg;
      pintarApps();
      LS.toast("Quedó marcada como permitida. Aplicá los cambios con el botón de abajo.", "ok");
      return;
    }
    const re = e.target.closest("[data-rechazar]");
    if (re) {
      re.disabled = true;
      try {
        await LS.db.ref("devices/" + deviceId + "/appRequests/" + re.dataset.rechazar).update({
          status: "rejected",
          resolvedAt: Date.now(),
          notified: false
        });
        await LS.sendCommand(deviceId, "SYNC_APP_REQUESTS", null, null, { nombre: nombreDispositivo() });
        LS.toast("Pedido rechazado y avisado al celular.", "ok");
      } catch (err) {
        LS.toast("No se pudo: " + err.message, "error");
      }
      re.disabled = false;
    }
  });

  /* ═════════════════════════════════════════════════════════════════════════
   * ACCIONES
   * ═══════════════════════════════════════════════════════════════════════ */
  /**
   * ⚠️ CADA COMANDO DE ESTA LISTA TIENE QUE EXISTIR EN LOS DOS LADOS: en el `when`
   * de `CommandProcessor` (el celular) y en `ALLOWED_COMMANDS` (la Cloud
   * Function). Lo verifica `tools/check_panel_commands.py`, y no es un trámite:
   * el modo de falla es MUDO. Un comando que la Function permite y el celular no
   * matchea sale, llega, y el panel muestra el tilde verde igual.
   *
   * Esta lista ya cazó dos: `REAPPLY_RESTRICTIONS` no existe en ningún lado, y
   * `HEAL_VPN` existe pero es una acción INTERNA del servicio de VPN
   * (`KosherVpnService`), no un comando FCM — o sea que el botón "Reparar el túnel"
   * habría sido un botón que no hace nada y contesta que sí. Se sacaron los dos.
   * Si algún día se quiere el de reparar el túnel, hay que agregarlo al `when` del
   * celular primero.
   */
  const ACCIONES = [
    ["SYNC_WHITELIST", "Sincronizar apps y dominios", false],
    ["SYNC_APP_REQUESTS", "Sincronizar pedidos de apps", false],
    ["UPDATE_LOCKSUITE", "Actualizar LockSuite (OTA)", false],
    ["LOCK_DEVICE", "Bloquear la pantalla ahora", false],
    ["UNSUSPEND_ALL_APPS", "Desbloquear todas las apps", true],
    ["CLEAR_WHITELIST_AUDIT", "Borrar el registro de dominios afuera", true]
  ];

  function pintarAcciones() {
    const tienePin = !!(campo("hasPinConfigured", false) || (campo("pinHash", "") && campo("pinSalt", "")));
    const uid = LS.auth.currentUser ? LS.auth.currentUser.uid : "";
    const confianza = !!((campo("trustedAdmins", {}) || {})[uid]);
    const suspendido = campo("locksuiteSuspended", false) === true;
    if (!cambio("acciones", [tienePin, confianza, suspendido])) return;

    $("c-acciones").innerHTML = ACCIONES.map(([cmd, txt]) =>
      '<button class="ls-btn" data-cmd="' + cmd + '">' + esc(txt) + "</button>").join("") +
      '<button class="ls-btn" id="c-relink">Re-vincular canal de comandos</button>';

    $("c-pin-estado").textContent = !tienePin
      ? "Este celular todavía no configuró un PIN de administrador."
      : confianza
        ? "🔓 Este celular te tiene como administrador de confianza: no te pide el PIN para las acciones críticas."
        : "🔒 Las acciones críticas piden el PIN de este celular.";
    $("c-pin-olvidar").classList.toggle("hidden", !tienePin);

    $("c-peligro").innerHTML =
      (suspendido ? '<button class="ls-btn ls-btn-primary" data-cmd="RESUME_LOCKSUITE">▶️ Reanudar LockSuite</button>'
        : '<button class="ls-btn" data-cmd="RESUME_LOCKSUITE" title="Por si quedó suspendido">Reanudar LockSuite</button>') +
      '<button class="ls-btn" id="c-archivar">📦 Archivar el dispositivo</button>' +
      '<button class="ls-btn ls-btn-danger" id="c-eliminar">🗑️ Eliminar el dispositivo</button>';
  }

  $("c-acciones").addEventListener("click", async (e) => {
    if (e.target.id === "c-relink") {
      if (!confirm("¿Re-vincular el canal de comandos?\n\nSe borra el secreto guardado en el " +
        "servidor para que el celular lo vuelva a escribir. Es lo que arregla que el equipo " +
        "reciba los comandos y no los aplique nunca.")) return;
      try {
        await LS.db.ref("deviceSecrets/" + deviceId + "/commandSecret").remove();
        await LS.db.ref("devices/" + deviceId).update({
          commandSecretMismatch: null,
          "info/commandSecretMismatch": null
        });
        LS.toast("Listo. El celular va a reescribir el secreto en su próxima sincronización.", "ok");
      } catch (err) { LS.toast("No se pudo: " + err.message, "error"); }
      return;
    }
    const b = e.target.closest("[data-cmd]");
    if (!b) return;
    const def = ACCIONES.find((a) => a[0] === b.dataset.cmd);
    if (def && def[2] && !confirm("¿" + def[1] + "?")) return;
    b.disabled = true;
    const r = await LS.sendCommand(deviceId, b.dataset.cmd, null, null,
      { nombre: nombreDispositivo(), timeoutMs: b.dataset.cmd === "UPDATE_LOCKSUITE" ? 120000 : 15000 });
    b.disabled = false;
    LS.toast(...LS.resultado(r, "Listo."));
  });

  $("c-peligro").addEventListener("click", async (e) => {
    if (e.target.id === "c-archivar") { archivar(e.target); return; }
    if (e.target.id === "c-eliminar") { eliminar(e.target); return; }
    const b = e.target.closest("[data-cmd]");
    if (!b) return;
    b.disabled = true;
    const r = await LS.sendCommand(deviceId, b.dataset.cmd, null, null, { nombre: nombreDispositivo() });
    b.disabled = false;
    LS.toast(...LS.resultado(r, "Listo."));
  });

  /** Lo mismo que «Archivar» de la vista rápida: copia a archivedDevices y saca de devices. */
  async function archivar(boton) {
    if (!confirm("¿Archivar «" + nombreDispositivo() + "»?\n\nDesaparece del panel, pero sus datos se " +
      "conservan en la base (archivedDevices) y se puede restaurar.")) return;
    boton.disabled = true;
    try {
      const snap = await LS.db.ref("devices/" + deviceId).once("value");
      const data = snap.val();
      if (data) {
        data._archivedAt = new Date().toISOString();
        await LS.db.ref("archivedDevices/" + deviceId).set(data);
      }
      S.ui.cerrado = true;
      await LS.db.ref("devices/" + deviceId).remove();
      cerrarFicha("📦 «" + nombreDispositivo() + "» quedó archivado.");
    } catch (err) {
      S.ui.cerrado = false;
      LS.toast("No se pudo archivar: " + err.message, "error");
      boton.disabled = false;
    }
  }

  async function eliminar(boton) {
    const n = nombreDispositivo();
    if (!confirm("⚠️ ¿Eliminar PARA SIEMPRE «" + n + "»?\n\nNo se puede deshacer: se borran todos sus datos.")) return;
    if (!confirm("¿Confirmás que querés eliminar «" + n + "» de forma irreversible?")) return;
    boton.disabled = true;
    try {
      S.ui.cerrado = true;
      await LS.db.ref("devices/" + deviceId).remove();
      await LS.db.ref("deviceSecrets/" + deviceId).remove().catch(() => {});
      cerrarFicha("🗑️ «" + n + "» quedó eliminado.");
    } catch (err) {
      S.ui.cerrado = false;
      LS.toast("No se pudo eliminar: " + err.message, "error");
      boton.disabled = false;
    }
  }

  function cerrarFicha(texto) {
    window.onbeforeunload = null;
    S.pendApps = {};
    S.pendPol = {};
    document.body.innerHTML =
      '<div style="max-width:520px;margin:15vh auto;text-align:center;padding:0 20px">' +
      '<h2 style="color:var(--text-light)">' + esc(texto) + "</h2>" +
      '<p><a class="ls-btn ls-btn-primary" href="index.html">Volver al panel</a></p></div>';
  }

  $("c-pin-btn").addEventListener("click", async function () {
    const v = $("c-pin-nuevo").value.trim();
    if (!/^\d{4,16}$/.test(v)) { LS.toast("El PIN tiene que ser de 4 a 16 dígitos.", "error"); return; }
    if (!confirm("¿Cambiar el PIN de administrador de " + nombreDispositivo() + "?")) return;
    this.disabled = true;
    const r = await LS.sendCommand(deviceId, "CHANGE_PIN", null, { newPin: v },
      { nombre: nombreDispositivo() });
    this.disabled = false;
    if (r.ok) { $("c-pin-nuevo").value = ""; LS.pins[deviceId] = v; LS.toast(...LS.resultado(r, "PIN cambiado.")); }
    else LS.toast("No se pudo: " + (r.reason || r.status), "error");
  });

  // B.98: "Olvidar confianza del PIN" estaba solo en la vista rápida. Es un borrado
  // directo en la base, sin comando: el que confía es el servidor, no el celular.
  $("c-pin-olvidar").addEventListener("click", async function () {
    const uid = LS.auth.currentUser && LS.auth.currentUser.uid;
    if (!uid) return;
    this.disabled = true;
    try {
      await LS.db.ref("devices/" + deviceId + "/trustedAdmins/" + uid).remove();
      await LS.db.ref("devices/" + deviceId + "/info/trustedAdmins/" + uid).remove();
      delete LS.pins[deviceId];
      LS.toast("Listo: la próxima acción crítica va a pedir el PIN.", "ok");
    } catch (err) {
      LS.toast("No se pudo: " + err.message, "error");
    }
    this.disabled = false;
  });

  /* ═════════════════════════════════════════════════════════════════════════
   * DIAGNÓSTICO (B.98)
   * ═══════════════════════════════════════════════════════════════════════ */
  /** Nodos grandes que ya se muestran en su sección: en la tabla van resumidos. */
  const NODOS_GRANDES = new Set(["apps", "info", "appPolicy", "commandAcks", "pendingCommands",
    "whitelistAudit", "embeddedBrowserAudit", "layer3Audit", "dnsRules", "updateFlowSteps",
    "appRequests", "trustedAdmins", "installedApps"]);

  /**
   * Campos que no se muestran enteros ni acá ni en el JSON: el token de FCM y restos de
   * PIN de versiones viejas (hoy el PIN vive en deviceSecrets). Con el final alcanza para
   * comparar dos valores sin que la pantalla los exponga enteros.
   */
  const CAMPO_SENSIBLE = /^(fcmToken|token|commandSecret|secret|pinHash|pinSalt|password)$/i;
  function enmascarar(v) {
    const t = String(v);
    return t.length <= 8 ? "••••" : "••••" + t.slice(-6) + " (" + t.length + " caracteres)";
  }
  function jsonSeguro(d) {
    return JSON.stringify(d, (k, v) => (k && CAMPO_SENSIBLE.test(k) && (typeof v === "string" || typeof v === "number")) ? enmascarar(v) : v, 2);
  }

  function valorLegible(v, clave) {
    if (v === null || v === undefined) return "—";
    if (clave && CAMPO_SENSIBLE.test(clave) && typeof v !== "object") return enmascarar(v);
    if (typeof v === "object") {
      const n = Object.keys(v).length;
      return Array.isArray(v) ? "[" + n + (n === 1 ? " elemento]" : " elementos]") : "{" + n + (n === 1 ? " clave}" : " claves}");
    }
    if (typeof v === "number" && v > 1500000000000 && v < 4102444800000) return v + "  (" + LS.fecha(v) + ")";
    return String(v);
  }

  function pintarDiagnostico() {
    const d = S.device || {};
    const acks = campo("commandAcks", {}) || {};
    const pend = campo("pendingCommands", {}) || {};
    const q = ($("c-diag-buscar").value || "").trim().toLowerCase();
    if (!cambio("diag", [d, q])) return;

    // Comandos recientes
    const lista = Object.keys(acks).map((k) => Object.assign({ id: k }, acks[k] || {}))
      .sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0)).slice(0, 25);
    const chipAck = (s) => s === "applied" ? '<span class="ls-chip ok">aplicado</span>'
      : s === "sent" ? '<span class="ls-chip">enviado</span>'
      : s === "queued" ? '<span class="ls-chip warn">en el buzón</span>'
      : '<span class="ls-chip bad">' + esc(s || "?") + "</span>";
    $("c-diag-acks").innerHTML = lista.length
      ? '<div class="ls-tablewrap"><table class="ls-table"><thead><tr><th>Cuándo</th><th>Comando</th><th>Resultado</th><th>Motivo</th></tr></thead><tbody>' +
        lista.map((a) => "<tr><td>" + esc(LS.fecha(a.timestamp)) + '</td><td style="font-family:monospace">' + esc(a.command || "?") +
          "</td><td>" + chipAck(a.status) + "</td><td>" + esc(a.reason || "") + "</td></tr>").join("") +
        "</tbody></table></div>"
      : '<p class="ls-empty">Sin comandos registrados.</p>';

    // Buzón
    const enBuzon = Object.keys(pend).map((k) => Object.assign({ id: k }, pend[k] || {}))
      .sort((a, b) => (a.queuedAt || 0) - (b.queuedAt || 0));
    $("c-diag-buzon").innerHTML = enBuzon.length
      ? '<div class="ls-tablewrap"><table class="ls-table"><thead><tr><th>Comando</th><th>Encolado</th><th>Vence</th></tr></thead><tbody>' +
        enBuzon.map((p) => '<tr><td style="font-family:monospace">' + esc((p.data && p.data.command) || p.family || "?") +
          (p.data && p.data.packages ? " · " + esc(p.data.packages) : "") + "</td><td>" + esc(LS.fecha(p.queuedAt)) +
          "</td><td>" + esc(LS.fecha(p.expiresAt)) + "</td></tr>").join("") + "</tbody></table></div>"
      : '<p class="ls-empty">Vacío: el celular levantó todo lo que se le mandó.</p>';

    // Todos los campos (arriba y en info, sin repetir)
    // Los nodos grandes (NODOS_GRANDES) salen resumidos ("{N claves}"): su contenido
    // ya se ve en su sección, y entero en el JSON de abajo.
    const filas = {};
    const sumar = (obj) => {
      Object.keys(obj || {}).forEach((k) => {
        if (k === "info" || filas[k] !== undefined) return;
        filas[k] = valorLegible(obj[k], k);
      });
    };
    sumar(d);
    sumar(d.info || {});
    let claves = Object.keys(filas).sort((a, b) => a.localeCompare(b));
    if (q) claves = claves.filter((k) => k.toLowerCase().includes(q) || String(filas[k]).toLowerCase().includes(q));
    $("c-diag-campos").innerHTML = claves.length
      ? '<div class="ls-tablewrap ls-diagtable"><table class="ls-table"><tbody>' +
        claves.map((k) => '<tr><td style="font-family:monospace; white-space:nowrap">' + esc(k) + '</td><td style="word-break:break-word">' +
          esc(filas[k]) + "</td></tr>").join("") + "</tbody></table></div>"
      : '<p class="ls-empty">Nada coincide.</p>';

    if ($("c-diag-json-box").open) $("c-diag-json").textContent = jsonSeguro(d);
  }

  $("c-diag-buscar").addEventListener("input", pintarDiagnostico);
  $("c-diag-json-box").addEventListener("toggle", function () {
    if (this.open) $("c-diag-json").textContent = jsonSeguro(S.device || {});
  });
  $("c-diag-copiar").addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(jsonSeguro(S.device || {}));
      LS.toast("Copiado.", "ok");
    } catch (e) {
      LS.toast("El navegador no dejó copiar: seleccioná el texto a mano.", "warn");
    }
  });

  /* ═════════════════════════════════════════════════════════════════════════
   * PINTAR TODO / SUSCRIPCIONES
   * ═══════════════════════════════════════════════════════════════════════ */
  function pintarTodo() {
    if (S.ui.cerrado) return;
    pintarResumen();
    pintarModos();
    pintarApps();
    pintarIab();
    pintarPoliticas();
    pintarEspeciales();
    pintarPerfiles();
    pintarGracia();
    pintarPresets();
    pintarPlayStore();
    pintarInstaladas();
    pintarAllowlist();
    pintarActualizaciones();
    pintarListaActualizar();
    pintarDns();
    pintarTienda();
    pintarPedidos();
    pintarAcciones();
    pintarDiagnostico();
    pintarCarrito();
  }

  pintarRutaElegida();

  LS.requireAuth(function () {
    LS.db.ref("devices/" + deviceId).on("value", (snap) => {
      if (S.ui.cerrado) return;
      const v = snap.val();
      if (!v) {
        // Lo archivó o eliminó otra pestaña (o la vista rápida): se dice, no se dibuja vacío.
        cerrarFicha("Este celular ya no está en el panel (lo archivaron o lo eliminaron).");
        S.ui.cerrado = true;
        return;
      }
      S.device = v;
      S.appPolicy = S.device.appPolicy || {};
      leerInstaladas();
      pintarTodo();
    }, (err) => {
      LS.toast("No se pudo leer el celular: " + err.message, "error");
    });

    LS.db.ref("globalSettings/whitelist/decisions").on("value", (snap) => {
      S.globalDecisions = snap.val() || {};
      pintarApps(); pintarResumen(); pintarInstaladas();
    });
    LS.db.ref("globalSettings/whitelist/custom").on("value", (snap) => {
      S.custom = snap.val() || {};
      pintarApps();
    });
    LS.db.ref("storeApps").on("value", (snap) => {
      S.storeApps = snap.val() || {};
      pintarApps(); pintarTienda(); pintarListaActualizar();
    });
    LS.db.ref("presets").on("value", (snap) => {
      S.presets = snap.val() || {};
      pintarPresets();
    });
  });
})();
