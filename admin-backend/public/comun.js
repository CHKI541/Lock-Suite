/* ═══════════════════════════════════════════════════════════════════════════
 * comun.js — lo que comparten las pantallas nuevas del panel (10/9/2026).
 *
 * Existe porque a partir de hoy el panel tiene más de una página:
 *
 *   · index.html    — el panel de siempre (lista de celulares, grupos, ajustes).
 *   · celular.html  — la ficha COMPLETA de UN celular, que se abre en pestaña nueva.
 *   · dominios.html — el editor GLOBAL de "qué dominio de cada app es kosher".
 *
 * NO toca `app.js` ni `index.html`. Fue la condición del pedido —"rediseñá todo
 * desde cero sin arruinar el código"— y además es lo prudente: si algo de las
 * pantallas nuevas falla, el panel viejo sigue entero y sigue siendo la vía para
 * administrar la flota. Por eso hay algo de código parecido al de `app.js` en vez
 * de refactorizarlo: refactorizar 220 KB de panel en producción, sin poder
 * probarlo, sería exactamente la clase de cosa que este proyecto ya pagó caro.
 * ═══════════════════════════════════════════════════════════════════════════ */

(function () {
  "use strict";

  const LS = {};
  window.LS = LS;

  LS.auth = firebase.auth();
  LS.db = firebase.database();

  /** Las claves de Firebase no pueden llevar punto. Mismo criterio que app.js. */
  LS.pkgKey = (pkg) => String(pkg).replace(/\./g, "_");
  LS.keyPkg = (key) => String(key).replace(/_/g, ".");

  LS.esc = (s) =>
    String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
    }[c]));

  LS.qs = (name) => new URLSearchParams(location.search).get(name) || "";

  /* ─────────────────────────────────────────────────────────────────────────
   * PUERTA DE ENTRADA
   *
   * La sesión de Firebase Auth es del ORIGEN, no de la pestaña, así que quien ya
   * entró al panel entra acá sin volver a escribir nada. Si no hay sesión no se
   * dibuja un segundo formulario de login: se manda al panel, que es el único
   * lugar donde el login está probado (y donde vive el rodeo de
   * `signInWithRedirect` para el WebView de :admin-app — ver B.22/B.23).
   * ───────────────────────────────────────────────────────────────────────── */
  LS.requireAuth = function (onReady) {
    LS.auth.onAuthStateChanged(function (user) {
      if (!user) {
        document.body.innerHTML =
          '<div style="max-width:520px;margin:15vh auto;text-align:center;padding:0 20px">' +
          '<h2 style="color:var(--text-light)">Sesión no iniciada</h2>' +
          '<p style="color:var(--text-gray)">Entrá al panel y volvé a abrir esta ficha.</p>' +
          '<p><a class="ls-btn ls-btn-primary" href="index.html">Ir al panel</a></p></div>';
        return;
      }
      onReady(user);
    });
  };

  /* ─────────────────────────────────────────────────────────────────────────
   * AVISOS
   * ───────────────────────────────────────────────────────────────────────── */
  let toastBox = null;
  LS.toast = function (msg, kind) {
    if (!toastBox) {
      toastBox = document.createElement("div");
      toastBox.className = "ls-toasts";
      document.body.appendChild(toastBox);
    }
    const el = document.createElement("div");
    el.className = "ls-toast " + (kind || "info");
    el.textContent = msg;
    toastBox.appendChild(el);
    // Los avisos que EXPLICAN algo se quedan más tiempo. Es la misma corrección
    // que B.41 hizo en la pantalla del celular: 1,8 s alcanzan para "✓ listo" y
    // no para una frase de dos renglones.
    const ms = kind === "error" ? 9000 : msg.length > 60 ? 6000 : 3200;
    setTimeout(() => {
      el.style.opacity = "0";
      setTimeout(() => el.remove(), 300);
    }, ms);
  };

  /* ─────────────────────────────────────────────────────────────────────────
   * COMANDOS AL CELULAR
   *
   * Mismo endpoint y mismo contrato que `runCommandOnDevice` de `app.js`, con dos
   * diferencias que importan:
   *
   *  1. Devuelve una promesa con el desenlace REAL (`applied` / `failed` /
   *     `rejected` / `timeout`) en vez de escribir en una barra de estado. Sin eso
   *     no se puede hacer una cola: la ficha nueva manda N comandos de un click y
   *     necesita saber cuál falló para poder decirlo.
   *  2. NUNCA llama "✓" al silencio. Es literal la corrección de B.42: hasta el
   *     3/9 el panel decía "✓ Comando enviado (sin confirmación del celular)" con
   *     tilde verde, que es indistinguible de que haya funcionado y también de un
   *     equipo apagado.
   * ───────────────────────────────────────────────────────────────────────── */
  const URL_CMD = "https://sendcommandv8-687828714595.us-central1.run.app";

  /** PIN por dispositivo recordado en memoria, igual que el panel viejo. */
  LS.pins = {};

  LS.askPin = function (deviceId, nombre) {
    return new Promise((resolve) => {
      const v = window.prompt(
        "PIN de administrador de " + (nombre || "este celular") + ":\n\n" +
        "Se pide una sola vez por sesión de esta pestaña."
      );
      if (v) LS.pins[deviceId] = v;
      resolve(v || null);
    });
  };

  /**
   * Manda un comando y espera el ACK real.
   * @returns {Promise<{ok:boolean, status:string, reason:string}>}
   */
  LS.sendCommand = async function (deviceId, command, packages, extra, opts) {
    opts = opts || {};
    const timeoutMs = opts.timeoutMs || 12000;
    let pin = LS.pins[deviceId] || null;

    for (let intento = 0; intento < 2; intento++) {
      let res;
      try {
        const idToken = await LS.auth.currentUser.getIdToken();
        const payload = {
          deviceId: deviceId,
          command: command,
          packages: packages
            ? Array.isArray(packages) ? packages.join(",") : String(packages)
            : null,
          devicePin: pin,
          rememberDevice: false
        };
        if (extra) Object.assign(payload, extra);
        res = await fetch(URL_CMD, {
          method: "POST",
          headers: { "Content-Type": "application/json", Authorization: "Bearer " + idToken },
          body: JSON.stringify(payload)
        });
      } catch (e) {
        return { ok: false, status: "red", reason: "No se pudo hablar con el servidor: " + e.message };
      }

      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        const msg = err.error || "HTTP_" + res.status;
        // El único caso que se reintenta: falta el PIN de ese equipo.
        if (/PIN/i.test(msg) && intento === 0) {
          pin = await LS.askPin(deviceId, opts.nombre);
          if (!pin) return { ok: false, status: "sin_pin", reason: "Hace falta el PIN de este celular." };
          continue;
        }
        return { ok: false, status: "rechazado", reason: msg };
      }

      const data = await res.json();
      if (!data.commandId) return { ok: true, status: "enviado", reason: "" };

      return await new Promise((resolve) => {
        const ref = LS.db.ref("devices/" + deviceId + "/commandAcks/" + data.commandId);
        const t = setTimeout(() => {
          ref.off();
          resolve({
            ok: false,
            status: "timeout",
            // El texto nombra las tres causas reales, como pide B.42: un equipo
            // que no contesta está dormido, sin red, o con el canal de comandos
            // desincronizado (B.26), y esa última se arregla con "Re-vincular".
            reason: "El celular no confirmó en " + Math.round(timeoutMs / 1000) +
              " s. Puede estar dormido, sin red, o con el canal de comandos desincronizado (probá \"Re-vincular\")."
          });
        }, timeoutMs);
        ref.on("value", (snap) => {
          if (!snap.exists()) return;
          const v = snap.val() || {};
          clearTimeout(t);
          ref.off();
          if (v.status === "applied") resolve({ ok: true, status: "applied", reason: "" });
          else resolve({ ok: false, status: v.status || "failed", reason: v.reason || v.error || "" });
        });
      });
    }
    return { ok: false, status: "sin_pin", reason: "Hace falta el PIN de este celular." };
  };

  /* ─────────────────────────────────────────────────────────────────────────
   * ESTADO "EN LÍNEA"
   *
   * Mismos umbrales que el panel viejo, y por el motivo que dejó anotado B.29:
   * llamarle "desconectado" a los 5 minutos es engañoso, porque un FCM de alta
   * prioridad despierta al equipo igual. Un celular "en reposo" se administra sin
   * problema; por eso el estado del medio existe y no dice "desconectado".
   * ───────────────────────────────────────────────────────────────────────── */
  LS.estadoConexion = function (lastSeen) {
    const mins = (Date.now() - (lastSeen || 0)) / 60000;
    if (!lastSeen) return { txt: "sin datos", cls: "off" };
    if (mins < 5) return { txt: "en línea", cls: "on" };
    if (mins < 20) return { txt: "en reposo", cls: "idle" };
    if (mins < 60 * 24) return { txt: "hace " + Math.round(mins / 60) + " h", cls: "off" };
    return { txt: "hace " + Math.round(mins / 1440) + " d", cls: "off" };
  };

  LS.fecha = function (ms) {
    if (!ms) return "—";
    try { return new Date(ms).toLocaleString("es-AR"); } catch (e) { return "—"; }
  };

  /* ─────────────────────────────────────────────────────────────────────────
   * CATÁLOGO
   * ───────────────────────────────────────────────────────────────────────── */
  LS.catalogo = function () {
    const c = window.LOCKSUITE_CATALOG;
    if (!c) {
      LS.toast("Falta catalog.js: corré python tools/gen_catalog_js.py y desplegá.", "error");
      return { apps: [], infrastructure: [], blockAlways: [], sharedCdn: [], neverBlock: [] };
    }
    return c;
  };

  LS.politicas = function () {
    const p = window.LOCKSUITE_POLICIES;
    if (!p) {
      LS.toast("Falta policies.js: corré python tools/gen_policies_js.py y desplegá.", "error");
      return { policies: [] };
    }
    return p;
  };

  /**
   * Los dominios EFECTIVOS de una app: catálogo de fábrica corregido con lo que el
   * panel haya guardado. Es la misma cuenta que hace `WhitelistManager` en el
   * celular (`allowedDomainsOf` / `blockedDomainsOf`), y tiene que dar lo mismo:
   * si no, el panel muestra una cosa y el equipo aplica otra, que es la familia
   * de bugs que este proyecto arrastra desde B.28.
   */
  LS.dominiosDe = function (pkg, custom) {
    const base = (LS.catalogo().apps || []).find((a) => a.pkg === pkg) || { allow: [], block: [], label: pkg };
    const c = (custom || {})[LS.pkgKey(pkg)] || {};
    const norm = (d) => String(d).trim().replace(/\.$/, "").toLowerCase();
    const desbloq = new Set((c.unblock || []).map(norm));
    return {
      label: base.label || pkg,
      note: base.note || "",
      allow: (base.allow || []).map((d) => ({ d: d, origen: "fabrica" }))
        .concat((c.allow || []).map((d) => ({ d: d, origen: "panel" }))),
      block: (base.block || []).filter((d) => !desbloq.has(norm(d))).map((d) => ({ d: d, origen: "fabrica" }))
        .concat((c.block || []).map((d) => ({ d: d, origen: "panel" }))),
      unblocked: (base.block || []).filter((d) => desbloq.has(norm(d)))
    };
  };
})();
