/* ═══════════════════════════════════════════════════════════════════════════
 * celular.js — la ficha completa de UN celular (10/9/2026).
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
    installed: [],         // apps instaladas reportadas por el equipo
    // Carrito
    pendApps: {},          // pkg -> "allow"|"block"|"unset"|null(=heredar)
    pendPol: {},           // campo -> boolean
    aplicando: false
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

  /* ═════════════════════════════════════════════════════════════════════════
   * ESTADO EFECTIVO DE UNA APP
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

  function irA(sec) {
    document.querySelectorAll("#c-rail button").forEach((x) =>
      x.classList.toggle("active", x.dataset.sec === sec));
    document.querySelectorAll(".ls-section").forEach((x) =>
      x.classList.toggle("active", x.dataset.sec === sec));
    history.replaceState(null, "", "?id=" + encodeURIComponent(deviceId) + "#" + sec);
  }
  if (location.hash) irA(location.hash.slice(1));

  // Buscador de arriba: manda a la sección donde está lo que se buscó. Es lo que
  // reemplaza a "acordarse en qué pestaña estaba tal cosa".
  $("c-buscar").addEventListener("input", function () {
    const q = this.value.trim().toLowerCase();
    if (!q) return;
    const hayPol = (LS.politicas().policies || [])
      .some((p) => p.label.toLowerCase().includes(q) || p.field.toLowerCase().includes(q));
    const hayApp = todasLasApps()
      .some((a) => a.label.toLowerCase().includes(q) || a.pkg.toLowerCase().includes(q) ||
        a.allow.concat(a.block).some((d) => d.d.toLowerCase().includes(q)));
    if (hayApp) { $("c-apps-buscar").value = q; irA("apps"); pintarApps(); }
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
        if (r.ok) { l2.textContent = "✓ El celular aplicó la configuración de apps"; l2.className = "ok"; }
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
      if (r.ok) {
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

    if (!fallaron) {
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
    let cambio = false;
    for (const pkg of pkgs) {
      const st = estadoDe(pkg);
      const i = lista.indexOf(pkg);
      if (st === "allow" && i < 0) { lista.push(pkg); cambio = true; }
      if (st === "block" && i >= 0) { lista.splice(i, 1); cambio = true; }
    }
    if (cambio) await ref.set(lista);
  }

  /* ═════════════════════════════════════════════════════════════════════════
   * APPS
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
          '<div class="ls-appbody hidden">' +
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
      fila.querySelector(".ls-appbody").classList.toggle("hidden");
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
    $("c-modos").innerHTML = MODOS.map((m) =>
      '<button class="ls-mode ' + (m.id === act ? "active" : "") + '" data-modo="' + m.id + '">' +
      "<b>" + esc(m.titulo) + "</b><small>" + esc(m.desc) + "</small></button>").join("");

    const cdn = campo("whitelistSharedCdn", true);
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
      else LS.toast("Listo.", "ok");
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
    for (const cmd of pasos) {
      const r = await LS.sendCommand(deviceId, cmd, null, null, { nombre: nombreDispositivo() });
      if (!r.ok) {
        LS.toast("No se pudo cambiar el modo: " + (r.reason || r.status), "error");
        b.disabled = false;
        return;
      }
    }
    b.disabled = false;
    LS.toast("Modo cambiado.", "ok");
  });

  /* ═════════════════════════════════════════════════════════════════════════
   * POLÍTICAS
   * ═══════════════════════════════════════════════════════════════════════ */
  function pintarPoliticas() {
    const defs = LS.politicas().policies || [];
    const q = ($("c-pol-buscar").value || "").trim().toLowerCase();
    const soloOn = $("c-pol-solo-on").checked;

    const valor = (f) => Object.prototype.hasOwnProperty.call(S.pendPol, f)
      ? S.pendPol[f] : !!campo(f, false);

    let lista = defs;
    if (q) lista = lista.filter((p) => p.label.toLowerCase().includes(q) ||
      p.field.toLowerCase().includes(q) || p.group.toLowerCase().includes(q));
    if (soloOn) lista = lista.filter((p) => valor(p.field));

    $("cnt-pol").textContent = defs.filter((p) => valor(p.field)).length + "/" + defs.length;

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

    $("c-politicas").innerHTML = grupos.map((g) => {
      const encendidas = g.items.filter((p) => valor(p.field)).length;
      return '<details class="ls-polgroup" ' + (q || soloOn ? "open" : "") + ">" +
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

  /* ── Los tres que van aparte ─────────────────────────────────────────── */
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
    $("c-especiales").innerHTML = ESPECIALES.map((p) =>
      '<div class="ls-polrow">' +
      '<label for="esp-' + p.field + '">' + esc(p.label) + "</label>" +
      '<span class="ls-sw"><input type="checkbox" id="esp-' + p.field + '" data-esp="' + p.field +
      '" ' + (campo(p.field, false) ? "checked" : "") + "><span></span></span></div>").join("");
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
    else LS.toast(def.label + ": listo.", "ok");
  });

  /* ═════════════════════════════════════════════════════════════════════════
   * RESUMEN
   * ═══════════════════════════════════════════════════════════════════════ */
  function pintarResumen() {
    const kv = (k, v) => '<div class="ls-kv"><span>' + esc(k) + "</span><span>" + v + "</span></div>";

    $("c-ident").innerHTML =
      kv("Modelo", esc(campo("model", "—"))) +
      kv("Android", esc(String(campo("androidVersion", campo("androidSdkInt", "—"))))) +
      kv("LockSuite", esc(String(campo("appVersionName", "—"))) + " (" + esc(String(campo("appVersionCode", "—"))) + ")") +
      kv("Última vez", LS.fecha(campo("lastSeen", 0))) +
      kv("Batería", campo("batteryLevel", null) != null ? campo("batteryLevel") + " %" : "—");

    const salud = campo("dnsTunnelHealth", null);
    const chipSalud = salud === "OK" ? '<span class="ls-chip ok">OK</span>'
      : salud ? '<span class="ls-chip bad">' + esc(salud) + "</span>"
      : '<span class="ls-chip">sin datos</span>';
    $("c-salud").innerHTML =
      kv("Túnel DNS", chipSalud) +
      kv("Paquetes filtrados", esc(String(campo("dnsTunnelPacketsIn", "—")))) +
      kv("Auto-reparaciones", esc(String(campo("dnsTunnelHeals", 0)))) +
      kv("Accesibilidad", campo("accessibilityActive", null) === true
        ? '<span class="ls-chip ok">activa</span>'
        : campo("accessibilityActive", null) === false
          ? '<span class="ls-chip bad">caída</span>' : "—") +
      kv("Dominios en el filtro", esc(String(campo("whitelistDomainCount", "—"))));

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
    if (campo("accessibilityActive", null) === false) {
      avisos.push(["bad", "El servicio de Accesibilidad está caído: el filtro visual (Capa 3) no está funcionando."]);
    }
    if (salud && salud !== "OK" && salud !== "SIN_DATOS") {
      avisos.push(["warn", "El túnel DNS reporta «" + salud + "»: el filtro de dominios puede no estar filtrando."]);
    }
    if (campo("commandSecretMismatch", false)) {
      avisos.push(["bad", "El canal de comandos está desincronizado: los comandos del panel no llegan. Usá «Re-vincular» en Acciones."]);
    }
    if (campo("policyDriftCount", 0) > 0) {
      avisos.push(["warn", campo("policyDriftCount") + " políticas figuran encendidas pero el sistema no las tiene puestas."]);
    }
    if (modoActual() === "simulacion") {
      avisos.push(["warn", "La lista blanca está en simulación: registra, pero no bloquea nada todavía."]);
    }
    $("c-alertas").innerHTML = avisos.map(([cls, txt]) =>
      '<div class="ls-card" style="border-left:4px solid var(--' +
      (cls === "bad" ? "alert-red" : "warning-yellow") + '); padding:12px 16px; margin-bottom:10px">' +
      esc(txt) + "</div>").join("");

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
    $("c-version").textContent = campo("appVersionName", "—");
    document.title = nombreDispositivo() + " — LockSuite";
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
    { id: "1", nombre: "Nivel 1 — Kosher estricto",
      desc: "Todo cerrado. Bloquea el cambio de cuentas y de idioma.",
      aviso: "⚠️ Aplicalo DESPUÉS de agregar la cuenta de Google y fijar el idioma: bloquea las dos cosas. Si lo aplicás antes, el equipo no se puede terminar de dar de alta." },
    { id: "2", nombre: "Nivel 2 — Trabajo",
      desc: "Restricciones fuertes, pero deja trabajar.", aviso: "" },
    { id: "3", nombre: "Nivel 3 — Base mínima",
      desc: "Solo el piso anti-manipulación.", aviso: "" },
    { id: "4", nombre: "Nivel 4 — Período de gracia",
      desc: "Cierra lo claramente no kosher y deja lo dudoso abierto por un plazo. Mientras tanto, anota qué usa esta persona para completar el catálogo.",
      aviso: "Al vencer, se cierra solo. El plazo lo aguantan dos relojes, así que atrasar la hora del equipo no lo estira." }
  ];

  function pintarPerfiles() {
    $("c-perfiles").innerHTML = NIVELES.map((n) =>
      '<div class="ls-card">' +
        "<h3>" + esc(n.nombre) + "</h3>" +
        '<p class="ls-hint">' + esc(n.desc) + (n.aviso ? "<br><b>" + esc(n.aviso) + "</b>" : "") + "</p>" +
        (n.id === "4"
          ? '<div style="display:flex; gap:8px; flex-wrap:wrap; align-items:center">' +
            '<label style="font-size:12.5px; color:var(--text-gray)">Plazo:</label>' +
            '<select class="ls-select" id="gracia-horas">' +
              '<option value="2">2 horas (para probar)</option>' +
              '<option value="24">1 día</option>' +
              '<option value="48" selected>2 días</option>' +
              '<option value="168">1 semana</option></select>' +
            '<button class="ls-btn ls-btn-primary" data-nivel="4">Aplicar a ' + esc(nombreDispositivo()) + "</button></div>"
          : '<button class="ls-btn ls-btn-primary" data-nivel="' + n.id + '">Aplicar a ' +
            esc(nombreDispositivo()) + "</button>") +
      "</div>").join("");
  }

  $("c-perfiles").addEventListener("click", async (e) => {
    const b = e.target.closest("[data-nivel]");
    if (!b) return;
    const n = NIVELES.find((x) => x.id === b.dataset.nivel);
    let extra = null;
    if (n.id === "4") {
      const h = parseInt($("gracia-horas").value, 10) || 48;
      extra = { graceHours: h };
    }
    if (!confirm("¿Aplicar «" + n.nombre + "» a " + nombreDispositivo() + "?\n\n" +
      n.desc + (n.aviso ? "\n\n" + n.aviso : ""))) return;
    b.disabled = true;
    const r = await LS.sendCommand(deviceId, "APPLY_MASTER_PROFILE", n.id, extra,
      { nombre: nombreDispositivo(), timeoutMs: 20000 });
    b.disabled = false;
    if (r.ok) LS.toast("Perfil aplicado.", "ok");
    else LS.toast("No se pudo aplicar: " + (r.reason || r.status), "error");
  });

  function pintarGracia() {
    const hasta = campo("graceEndsAt", 0);
    if (!hasta || hasta < Date.now()) { $("c-gracia").innerHTML = ""; return; }
    const faltan = Math.max(0, hasta - Date.now());
    const h = Math.floor(faltan / 3600000);
    const m = Math.floor((faltan % 3600000) / 60000);
    $("c-gracia").innerHTML =
      '<div class="ls-card" style="border-left:4px solid var(--accent)">' +
      "<h3>⏳ Período de gracia activo</h3>" +
      '<p class="ls-hint">Faltan <b>' + h + " h " + m + " min</b> (vence " + LS.fecha(hasta) + "). " +
      "Al vencer se aplica el perfil de cierre y la Tienda se cierra.</p>" +
      '<button class="ls-btn" id="c-gracia-cancelar">Cancelar el vencimiento</button></div>';
    $("c-gracia-cancelar").addEventListener("click", async function () {
      if (!confirm("¿Cancelar el vencimiento?\n\nEl equipo se queda como está ahora, sin cerrar.")) return;
      this.disabled = true;
      const r = await LS.sendCommand(deviceId, "CANCEL_GRACE_PERIOD", null, null,
        { nombre: nombreDispositivo() });
      this.disabled = false;
      LS.toast(r.ok ? "Vencimiento cancelado." : "No se pudo: " + (r.reason || r.status), r.ok ? "ok" : "error");
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
      return '<div class="ls-polrow"><label>' + esc(p.name || k) +
        '<br><small style="color:var(--text-muted)">' + LS.fecha(p.createdAt || 0) + "</small></label>" +
        '<button class="ls-btn ls-btn-sm" data-preset="' + esc(k) + '">Aplicar</button></div>';
    }).join("");
  }

  $("c-presets").addEventListener("click", async (e) => {
    const b = e.target.closest("[data-preset]");
    if (!b) return;
    const p = S.presets[b.dataset.preset] || {};
    if (!confirm("¿Aplicar el perfil «" + (p.name || b.dataset.preset) + "» a " + nombreDispositivo() + "?")) return;
    b.disabled = true;
    const r = await LS.sendCommand(deviceId, "APPLY_PROFILE", null, { profileId: b.dataset.preset },
      { nombre: nombreDispositivo(), timeoutMs: 20000 });
    b.disabled = false;
    LS.toast(r.ok ? "Perfil aplicado." : "No se pudo: " + (r.reason || r.status), r.ok ? "ok" : "error");
  });

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

    const audit = campo("whitelistAudit", {}) || {};
    const filas = Object.keys(audit).map((k) => audit[k]).filter(Boolean)
      .sort((a, b) => (b.hits || 0) - (a.hits || 0));
    if (!filas.length) {
      $("c-auditoria").innerHTML = '<p class="ls-empty">Sin registro. Aparece cuando la lista blanca está encendida.</p>';
    } else {
      $("c-auditoria").innerHTML =
        '<div class="ls-tablewrap"><table class="ls-table"><thead><tr>' +
        "<th>Dominio</th><th>Intentos</th><th></th></tr></thead><tbody>" +
        filas.slice(0, 200).map((f) =>
          "<tr><td style=\"font-family:monospace\">" + esc(f.domain) + "</td><td>" + (f.hits || 0) + "</td>" +
          '<td style="text-align:right"><button class="ls-btn ls-btn-sm" data-permitir="' +
          esc(f.domain) + '">Forzar permitir</button></td></tr>').join("") +
        "</tbody></table></div>";
    }
  }

  async function reglaDns(dominio, tipo) {
    const cmd = tipo === "REMOVE" ? "REMOVE_DOMAIN_RULE" : "SET_DOMAIN_RULE_" + tipo;
    const r = await LS.sendCommand(deviceId, cmd, dominio, null, { nombre: nombreDispositivo() });
    LS.toast(r.ok ? "Regla aplicada en el celular." : "No se pudo: " + (r.reason || r.status),
      r.ok ? "ok" : "error");
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
    if (!keys.length) {
      $("c-tienda").innerHTML = '<p class="ls-empty">No hay apps cargadas en la Tienda.</p>';
      return;
    }
    $("c-tienda").innerHTML =
      '<div class="ls-tablewrap"><table class="ls-table"><thead><tr>' +
      "<th>App</th><th>Estado en este celular</th><th>Huella</th><th>Firmante</th>" +
      "</tr></thead><tbody>" +
      keys.map((k) => {
        const a = S.storeApps[k] || {};
        const pkg = a.packageName || LS.keyPkg(k);
        const st = estadoDe(pkg);
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
        return "<tr><td><b>" + esc(a.label || a.name || pkg) + "</b><br>" +
          '<span class="ls-apppkg">' + esc(pkg) + "</span></td>" +
          "<td>" + chipSt + "</td><td>" + chipHash + "</td><td>" + firm + "</td></tr>";
      }).join("") + "</tbody></table></div>" +
      '<p class="ls-hint" style="margin-top:12px">La Tienda es <b>global</b>: las apps y sus huellas ' +
      "se cargan una vez para toda la flota, desde Ajustes → Tienda en el panel. Lo que cambia por " +
      "celular es si esa app está permitida.</p>";
  }

  function pintarPedidos() {
    const pedidos = campo("appRequests", {}) || {};
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
        '<span><button class="ls-btn ls-btn-sm" data-aprobar="' + esc(pkg) + '">Aprobar</button> ' +
        '<button class="ls-btn ls-btn-sm ls-btn-danger" data-rechazar="' + esc(k) + '">Rechazar</button></span></div>';
    }).join("");
  }

  $("c-pedidos").addEventListener("click", (e) => {
    const ap = e.target.closest("[data-aprobar]");
    if (ap) {
      // Aprobar = permitir la app en este equipo. Se encola en el carrito en vez
      // de mandarse suelto: así sale con el resto y por el mismo camino, que es
      // lo que evita que "aprobar" y "permitir" se separen en dos lógicas.
      S.pendApps[ap.dataset.aprobar] = "allow";
      pintarCarrito();
      irA("apps");
      $("c-apps-buscar").value = ap.dataset.aprobar;
      pintarApps();
      LS.toast("Quedó marcada como permitida. Aplicá los cambios con el botón de abajo.", "ok");
      return;
    }
    const re = e.target.closest("[data-rechazar]");
    if (re) {
      LS.db.ref("devices/" + deviceId + "/appRequests/" + re.dataset.rechazar + "/status")
        .set("rejected")
        .then(() => LS.toast("Pedido rechazado.", "ok"))
        .catch((err) => LS.toast("No se pudo: " + err.message, "error"));
    }
  });

  /* ═════════════════════════════════════════════════════════════════════════
   * ACCIONES
   * ═══════════════════════════════════════════════════════════════════════ */
  /**
   * ⚠️ CADA COMANDO DE ESTA LISTA TIENE QUE EXISTIR EN LOS DOS LADOS: en el `when`
   * de `LockSuiteFirebaseService` (el celular) y en `ALLOWED_COMMANDS` (la Cloud
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
    ["CLEAR_WHITELIST_AUDIT", "Borrar el registro de dominios afuera", true],
    ["LOCK_DEVICE", "Bloquear la pantalla ahora", false]
  ];

  function pintarAcciones() {
    $("c-acciones").innerHTML = ACCIONES.map(([cmd, txt]) =>
      '<button class="ls-btn" data-cmd="' + cmd + '">' + esc(txt) + "</button>").join("") +
      '<button class="ls-btn" id="c-relink">Re-vincular canal de comandos</button>';
    $("c-peligro").innerHTML =
      '<button class="ls-btn ls-btn-danger" data-cmd="RESUME_LOCKSUITE">Reanudar LockSuite</button>';
  }

  $("c-acciones").addEventListener("click", async (e) => {
    if (e.target.id === "c-relink") {
      if (!confirm("¿Re-vincular el canal de comandos?\n\nSe borra el secreto guardado en el " +
        "servidor para que el celular lo vuelva a escribir. Es lo que arregla que el equipo " +
        "reciba los comandos y no los aplique nunca.")) return;
      try {
        await LS.db.ref("deviceSecrets/" + deviceId + "/commandSecret").remove();
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
    LS.toast(r.ok ? "Listo." : "No se pudo: " + (r.reason || r.status), r.ok ? "ok" : "error");
  });

  $("c-peligro").addEventListener("click", async (e) => {
    const b = e.target.closest("[data-cmd]");
    if (!b) return;
    b.disabled = true;
    const r = await LS.sendCommand(deviceId, b.dataset.cmd, null, null, { nombre: nombreDispositivo() });
    b.disabled = false;
    LS.toast(r.ok ? "Listo." : "No se pudo: " + (r.reason || r.status), r.ok ? "ok" : "error");
  });

  $("c-upd-btn").addEventListener("click", async function () {
    const pkg = $("c-upd-pkg").value.trim();
    if (!pkg) return;
    this.disabled = true;
    $("c-upd-estado").textContent = "Mandando…";
    const r = await LS.sendCommand(deviceId, "UPDATE_APP", pkg, null,
      { nombre: nombreDispositivo(), timeoutMs: 20000 });
    this.disabled = false;
    $("c-upd-estado").textContent = r.ok
      ? "El flujo arrancó. Mirá el progreso acá abajo."
      : "No arrancó: " + (r.reason || r.status);
  });

  function pintarFlujoActualizacion() {
    const f = campo("updateFlow", null);
    if (!f) return;
    const partes = [];
    if (f.running) partes.push("En curso: " + (f.stage || "…") + (f.progress != null ? " — " + f.progress + " %" : ""));
    else if (f.lastResult) {
      partes.push("Último resultado: " + f.lastResult +
        (f.lastResultReason ? " — " + f.lastResultReason : "") +
        (f.freeSpaceMb != null ? " (libre: " + f.freeSpaceMb + " MB)" : ""));
    }
    if (partes.length) $("c-upd-estado").textContent = partes.join(" · ");
  }

  $("c-pin-btn").addEventListener("click", async function () {
    const v = $("c-pin-nuevo").value.trim();
    if (!/^\d{4,16}$/.test(v)) { LS.toast("El PIN tiene que ser de 4 a 16 dígitos.", "error"); return; }
    this.disabled = true;
    const r = await LS.sendCommand(deviceId, "CHANGE_PIN", null, { newPin: v },
      { nombre: nombreDispositivo() });
    this.disabled = false;
    if (r.ok) { $("c-pin-nuevo").value = ""; LS.pins[deviceId] = v; LS.toast("PIN cambiado.", "ok"); }
    else LS.toast("No se pudo: " + (r.reason || r.status), "error");
  });

  /* ═════════════════════════════════════════════════════════════════════════
   * PINTAR TODO / SUSCRIPCIONES
   * ═══════════════════════════════════════════════════════════════════════ */
  function pintarTodo() {
    pintarResumen();
    pintarModos();
    pintarApps();
    pintarPoliticas();
    pintarEspeciales();
    pintarPerfiles();
    pintarGracia();
    pintarPresets();
    pintarDns();
    pintarTienda();
    pintarPedidos();
    pintarAcciones();
    pintarFlujoActualizacion();
    pintarCarrito();
  }

  LS.requireAuth(function () {
    LS.db.ref("devices/" + deviceId).on("value", (snap) => {
      S.device = snap.val() || {};
      S.appPolicy = S.device.appPolicy || {};
      const inv = S.device.installedApps || (S.device.info && S.device.info.installedApps) || [];
      S.installed = Array.isArray(inv) ? inv.map((x) => (typeof x === "string" ? x : x && x.packageName))
        .filter(Boolean) : Object.keys(inv).map((k) => LS.keyPkg(k));
      pintarTodo();
    }, (err) => {
      LS.toast("No se pudo leer el celular: " + err.message, "error");
    });

    LS.db.ref("globalSettings/whitelist/decisions").on("value", (snap) => {
      S.globalDecisions = snap.val() || {};
      pintarApps(); pintarResumen();
    });
    LS.db.ref("globalSettings/whitelist/custom").on("value", (snap) => {
      S.custom = snap.val() || {};
      pintarApps();
    });
    LS.db.ref("storeApps").on("value", (snap) => {
      S.storeApps = snap.val() || {};
      pintarApps(); pintarTienda();
    });
    LS.db.ref("presets").on("value", (snap) => {
      S.presets = snap.val() || {};
      pintarPresets();
    });
  });
})();
