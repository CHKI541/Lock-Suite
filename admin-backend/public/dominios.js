/* ═══════════════════════════════════════════════════════════════════════════
 * dominios.js — editor GLOBAL de "qué dominio de cada app es kosher" (10/9/2026).
 *
 * CÓMO SE GUARDA, QUE ES LA PARTE QUE HAY QUE ENTENDER
 * ─────────────────────────────────────────────────────────────────────────
 * El catálogo de fábrica vive en el Kotlin (`WhitelistCatalog.kt`) y es la fuente
 * de verdad de lo que el celular aplica. Esta pantalla NO lo reescribe —no puede,
 * está compilado adentro del APK—: guarda las correcciones aparte, en
 * `globalSettings/whitelist/custom/<paquete>`, con TRES campos:
 *
 *   allow   → dominios que se SUMAN a los permitidos de fábrica.
 *   block   → dominios que se SUMAN a los no kosher de fábrica.
 *   unblock → dominios no kosher DE FÁBRICA que se sacan de la lista.
 *
 * El celular fusiona las tres cosas en `WhitelistManager.blockedDomainsOf()` /
 * `allowedDomainsOf()`. Suman en vez de reemplazar, y eso es una corrección
 * deliberada de B.53: la primera versión hacía `custom ?: catálogo`, así que
 * agregar UN dominio desde el panel le sacaba a la app todos los demás — o sea que
 * el camino más usado era también la forma más fácil de romper una app.
 *
 * `unblock` es nuevo del 10/9 y existe para una sola cosa: que se pueda CORREGIR
 * una decisión del catálogo. Pedido textual del dueño: *"poné lo que ya hiciste,
 * así puedo corregir si te equivocaste"*. Sin él, un bloqueo de fábrica mal puesto
 * solo se podía sortear con un `FORCE_ALLOW` equipo por equipo.
 *
 * LAS DOS OPERACIONES, Y POR QUÉ NO SON SIMÉTRICAS
 * ─────────────────────────────────────────────────────────────────────────
 * · Sacar un dominio de PERMITIDOS  → se escribe en `block`. No hace falta nada
 *   nuevo: los bloqueos se escriben después de los permisos y ganan.
 * · Sacar un dominio de NO KOSHER   → si es de fábrica va a `unblock`; si lo
 *   agregaste vos, se borra de `block`.
 * ═══════════════════════════════════════════════════════════════════════════ */

(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const esc = LS.esc;
  const norm = (d) => String(d).trim().replace(/\.$/, "").toLowerCase();

  const S = {
    custom: {},      // globalSettings/whitelist/custom  (lo guardado)
    pend: {},        // pkg -> {allow:[], block:[], unblock:[], label}  (sin guardar)
    devices: {}
  };

  /* ── Estado efectivo: fábrica + lo guardado + lo pendiente ───────────── */
  function efectivo(pkg) {
    const base = (LS.catalogo().apps || []).find((a) => a.pkg === pkg) ||
      { pkg: pkg, label: pkg, allow: [], block: [], note: "" };
    const k = LS.pkgKey(pkg);
    const g = S.custom[k] || {};
    const p = S.pend[pkg] || {};
    const unir = (a, b) => (a || []).concat(b || []);

    const allowExtra = unir(g.allow, p.allow);
    const blockExtra = unir(g.block, p.block);
    const unblocked = new Set(unir(g.unblock, p.unblock).map(norm));

    // De fábrica: un permitido que haya pasado a bloqueado se muestra en rojo y
    // no en verde, que es lo que el celular va a aplicar de verdad.
    const bloqueadosNorm = new Set(blockExtra.map(norm));

    const allow = [];
    for (const d of base.allow || []) {
      if (bloqueadosNorm.has(norm(d))) continue;
      allow.push({ d: d, origen: "fabrica" });
    }
    for (const d of allowExtra) {
      if (bloqueadosNorm.has(norm(d))) continue;
      allow.push({ d: d, origen: "panel" });
    }

    const block = [];
    for (const d of base.block || []) {
      if (unblocked.has(norm(d))) continue;
      block.push({ d: d, origen: "fabrica" });
    }
    for (const d of blockExtra) block.push({ d: d, origen: "panel" });

    return {
      pkg: pkg,
      label: (p.label || g.label || base.label || pkg),
      note: base.note || "",
      enCatalogo: !!(LS.catalogo().apps || []).find((a) => a.pkg === pkg),
      allow: allow,
      block: block,
      liberados: (base.block || []).filter((d) => unblocked.has(norm(d))),
      editada: !!(g.allow || g.block || g.unblock || p.allow || p.block || p.unblock)
    };
  }

  function listaApps() {
    const pkgs = new Set((LS.catalogo().apps || []).map((a) => a.pkg));
    for (const k of Object.keys(S.custom)) pkgs.add(LS.keyPkg(k));
    for (const p of Object.keys(S.pend)) pkgs.add(p);
    return Array.from(pkgs).map(efectivo)
      .sort((a, b) => a.label.localeCompare(b.label, "es"));
  }

  /* ── Mutaciones ──────────────────────────────────────────────────────── */
  function pend(pkg) {
    if (!S.pend[pkg]) S.pend[pkg] = { allow: [], block: [], unblock: [] };
    return S.pend[pkg];
  }

  function quitar(arr, d) {
    const i = (arr || []).findIndex((x) => norm(x) === norm(d));
    if (i >= 0) arr.splice(i, 1);
    return i >= 0;
  }

  /** Mover un dominio de PERMITIDOS a NO KOSHER. */
  function aNoKosher(pkg, dominio, origen) {
    const p = pend(pkg);
    if (origen === "panel") {
      // Lo había agregado el panel a `allow`: se saca de ahí…
      const g = S.custom[LS.pkgKey(pkg)] || {};
      if (!quitar(p.allow, dominio) && (g.allow || []).some((x) => norm(x) === norm(dominio))) {
        // …y si lo que hay que sacar está ya GUARDADO, se marca para borrarlo al
        // guardar. Se anota en `quitarAllow`, que solo existe en memoria: lo que
        // viaja a Firebase es la lista final, no una orden de borrado.
        p.quitarAllow = (p.quitarAllow || []).concat([dominio]);
      }
    }
    // De fábrica o del panel, el efecto que hay que conseguir es el mismo:
    // que quede bloqueado. Un bloqueo gana sobre cualquier permiso.
    if (!(p.block || []).some((x) => norm(x) === norm(dominio))) {
      p.block = (p.block || []).concat([dominio]);
    }
    quitar(p.unblock, dominio);
    pintar();
  }

  /** Sacar un dominio de NO KOSHER (o sea: decidir que sí es kosher). */
  function aKosher(pkg, dominio, origen) {
    const p = pend(pkg);
    const g = S.custom[LS.pkgKey(pkg)] || {};
    if (origen === "fabrica") {
      // No se puede borrar del Kotlin: se marca como desbloqueado.
      if (!(p.unblock || []).some((x) => norm(x) === norm(dominio))) {
        p.unblock = (p.unblock || []).concat([dominio]);
      }
    } else {
      if (!quitar(p.block, dominio) && (g.block || []).some((x) => norm(x) === norm(dominio))) {
        p.quitarBlock = (p.quitarBlock || []).concat([dominio]);
      }
    }
    pintar();
  }

  function deshacerLiberado(pkg, dominio) {
    const p = pend(pkg);
    if (!quitar(p.unblock, dominio)) {
      p.quitarUnblock = (p.quitarUnblock || []).concat([dominio]);
    }
    pintar();
  }

  function agregar(pkg, dominio, donde) {
    const d = norm(dominio);
    if (!/^[a-z0-9.-]+\.[a-z]{2,}$/.test(d)) {
      LS.toast("«" + dominio + "» no parece un dominio. Ejemplo: ofertas.mercadopago.com", "error");
      return false;
    }
    const p = pend(pkg);
    p[donde] = (p[donde] || []).concat([d]);
    // Si se agrega como permitido algo que estaba bloqueado, hay que soltar el
    // bloqueo o el permiso no tendría efecto — y el panel mostraría verde algo
    // que el celular bloquea. Ese desacuerdo es el bug de B.28.
    if (donde === "allow") {
      quitar(p.block, d);
      const g = S.custom[LS.pkgKey(pkg)] || {};
      if ((g.block || []).some((x) => norm(x) === d)) p.quitarBlock = (p.quitarBlock || []).concat([d]);
      const base = (LS.catalogo().apps || []).find((a) => a.pkg === pkg);
      if (base && (base.block || []).some((x) => norm(x) === d)) {
        p.unblock = (p.unblock || []).concat([d]);
      }
    }
    pintar();
    return true;
  }

  /* ── Guardar ─────────────────────────────────────────────────────────── */
  function cuentaPend() {
    let n = 0;
    for (const pkg of Object.keys(S.pend)) {
      const p = S.pend[pkg];
      n += (p.allow || []).length + (p.block || []).length + (p.unblock || []).length +
           (p.quitarAllow || []).length + (p.quitarBlock || []).length + (p.quitarUnblock || []).length;
    }
    return n;
  }

  async function guardar() {
    const updates = {};
    for (const pkg of Object.keys(S.pend)) {
      const k = LS.pkgKey(pkg);
      const g = S.custom[k] || {};
      const p = S.pend[pkg];
      const fuera = (lista, quitarLista) => {
        const q = new Set((quitarLista || []).map(norm));
        return (lista || []).filter((x) => !q.has(norm(x)));
      };
      const allow = dedup(fuera(g.allow, p.quitarAllow).concat(p.allow || []));
      const block = dedup(fuera(g.block, p.quitarBlock).concat(p.block || []));
      const unblock = dedup(fuera(g.unblock, p.quitarUnblock).concat(p.unblock || []));
      const label = p.label || g.label ||
        (((LS.catalogo().apps || []).find((a) => a.pkg === pkg) || {}).label) || pkg;

      if (!allow.length && !block.length && !unblock.length) {
        // Sin ninguna corrección, la entrada no tiene por qué existir. Dejar un
        // objeto vacío haría que la app figure como "corregida" para siempre.
        updates["globalSettings/whitelist/custom/" + k] = null;
      } else {
        updates["globalSettings/whitelist/custom/" + k] = {
          packageName: pkg, label: label,
          allow: allow, block: block, unblock: unblock
        };
      }
    }
    await LS.db.ref().update(updates);
    S.pend = {};
    LS.toast("Guardado. Para que los celulares lo apliquen, tocá «Enviar a todos los celulares».", "ok");
  }

  const dedup = (a) => {
    const vistos = new Set();
    const out = [];
    for (const x of a || []) { const n = norm(x); if (!vistos.has(n)) { vistos.add(n); out.push(n); } }
    return out;
  };

  /* ── Dibujo ──────────────────────────────────────────────────────────── */
  function pintar() {
    const n = cuentaPend();
    const chip = $("d-pend");
    chip.textContent = n ? (n === 1 ? "1 cambio sin guardar" : n + " cambios sin guardar") : "sin cambios";
    chip.className = "ls-chip " + (n ? "warn" : "");

    const q = ($("d-buscar").value || "").trim().toLowerCase();
    const filtro = $("d-filtro").value;
    let apps = listaApps();
    if (q) {
      apps = apps.filter((a) => a.label.toLowerCase().includes(q) || a.pkg.toLowerCase().includes(q) ||
        a.allow.concat(a.block).some((x) => x.d.toLowerCase().includes(q)));
    }
    if (filtro === "conbloqueos") apps = apps.filter((a) => a.block.length);
    else if (filtro === "sinbloqueos") apps = apps.filter((a) => !a.block.length);
    else if (filtro === "editadas") apps = apps.filter((a) => a.editada);

    if (!apps.length) {
      $("d-apps").innerHTML = '<p class="ls-empty">No hay apps que coincidan.</p>';
      return;
    }

    // "Permitir todo excepto" vs "Bloquear todo excepto" no es un interruptor
    // aparte: sale de la forma de la lista. Si entre los permitidos hay un dominio
    // RAÍZ (dos etiquetas, tipo `mercadopago.com`), permitirlo abre todo lo de
    // abajo y los rojos son las excepciones. Si solo hay hosts exactos, la app
    // está en "bloquear todo excepto estos". Se dice en palabras en vez de
    // esconderlo en un selector, porque es la consecuencia de lo que hay escrito.
    const esRaiz = (d) => d.split(".").length <= 2 ||
      /\.(com|net|org|co|io)\.[a-z]{2}$/.test(d) && d.split(".").length <= 3;

    $("d-apps").innerHTML = apps.map((a) => {
      const raices = a.allow.filter((x) => esRaiz(x.d));
      const modo = raices.length
        ? '<span class="ls-chip ok">permitir todo menos las excepciones</span>' +
          ' <small style="color:var(--text-muted)">raíz: ' +
          raices.map((x) => esc(x.d)).join(", ") + "</small>"
        : a.allow.length
          ? '<span class="ls-chip warn">bloquear todo menos estos hosts exactos</span>'
          : '<span class="ls-chip">sin dominios — esta app no resuelve nada</span>';

      const chip = (x, cls, accion) =>
        '<span class="ls-dom ' + cls + '">' + esc(x.d) +
        (x.origen === "fabrica" ? ' <small title="del catálogo de fábrica">f</small>' : ' <small title="agregado desde el panel">✎</small>') +
        '<button data-accion="' + accion + '" data-pkg="' + esc(a.pkg) + '" data-dom="' + esc(x.d) +
        '" data-origen="' + x.origen + '" title="' +
        (accion === "anokosher" ? "Marcar como NO kosher" : "Marcar como kosher") + '">✕</button></span>';

      return '<div class="ls-card" data-app="' + esc(a.pkg) + '">' +
        '<div style="display:flex; gap:10px; flex-wrap:wrap; align-items:baseline">' +
          "<h3 style=\"margin:0\">" + esc(a.label) + "</h3>" +
          '<span class="ls-apppkg">' + esc(a.pkg) + "</span>" +
          (a.enCatalogo ? "" : ' <span class="ls-chip">agregada desde el panel</span>') +
          (a.editada ? ' <span class="ls-chip warn">corregida</span>' : "") +
        "</div>" +
        '<div style="margin:8px 0 4px">' + modo + "</div>" +
        (a.note ? '<p class="ls-hint" style="margin:6px 0 0">' + esc(a.note) + "</p>" : "") +

        '<div class="ls-domtitle" style="color:var(--success-green)">Permitidos — y todos sus subdominios</div>' +
        (a.allow.length
          ? '<div class="ls-domlist">' + a.allow.map((x) => chip(x, "allow", "anokosher")).join("") + "</div>"
          : '<p class="ls-empty" style="padding:4px 0">Ninguno.</p>') +

        '<div class="ls-domtitle" style="color:var(--alert-red)">No kosher — bloqueados siempre</div>' +
        (a.block.length
          ? '<div class="ls-domlist">' + a.block.map((x) => chip(x, "block", "akosher")).join("") + "</div>"
          : '<p class="ls-empty" style="padding:4px 0">Ninguno. Todo lo que esté bajo los permitidos pasa.</p>') +

        (a.liberados.length
          ? '<div class="ls-domtitle">Bloqueos de fábrica que desactivaste</div><div class="ls-domlist">' +
            a.liberados.map((d) =>
              '<span class="ls-dom off">' + esc(d) +
              '<button data-accion="rehacer" data-pkg="' + esc(a.pkg) + '" data-dom="' + esc(d) +
              '" title="Volver a bloquearlo">↻</button></span>').join("") + "</div>"
          : "") +

        '<div style="display:flex; gap:8px; flex-wrap:wrap; margin-top:14px">' +
          '<input class="ls-input" data-nuevo="' + esc(a.pkg) + '" placeholder="agregar dominio…" style="flex:1 1 220px" />' +
          '<button class="ls-btn ls-btn-sm" data-add="allow" data-pkg="' + esc(a.pkg) + '">+ Permitido</button>' +
          '<button class="ls-btn ls-btn-sm ls-btn-danger" data-add="block" data-pkg="' + esc(a.pkg) + '">+ No kosher</button>' +
        "</div>" +
      "</div>";
    }).join("") +
    (n ? '<div class="ls-card" style="border-color:var(--accent)">' +
      "<b>" + n + " cambios sin guardar.</b>" +
      '<div style="margin-top:10px; display:flex; gap:8px; flex-wrap:wrap">' +
      '<button class="ls-btn ls-btn-primary" id="d-guardar">Guardar</button>' +
      '<button class="ls-btn" id="d-descartar">Descartar</button></div></div>' : "");

    const g = $("d-guardar");
    if (g) {
      g.addEventListener("click", async function () {
        this.disabled = true;
        try { await guardar(); } catch (e) { LS.toast("No se pudo guardar: " + e.message, "error"); }
        this.disabled = false;
        pintar();
      });
      $("d-descartar").addEventListener("click", () => { S.pend = {}; pintar(); });
    }
  }

  $("d-apps").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-accion], button[data-add]");
    if (!b) return;
    const pkg = b.dataset.pkg;
    if (b.dataset.add) {
      const inp = document.querySelector('[data-nuevo="' + CSS.escape(pkg) + '"]');
      if (!inp || !inp.value.trim()) return;
      if (agregar(pkg, inp.value, b.dataset.add)) inp.value = "";
      return;
    }
    if (b.dataset.accion === "anokosher") aNoKosher(pkg, b.dataset.dom, b.dataset.origen);
    else if (b.dataset.accion === "akosher") aKosher(pkg, b.dataset.dom, b.dataset.origen);
    else if (b.dataset.accion === "rehacer") deshacerLiberado(pkg, b.dataset.dom);
  });

  $("d-apps").addEventListener("keydown", (e) => {
    const inp = e.target.closest("input[data-nuevo]");
    if (!inp || e.key !== "Enter") return;
    if (agregar(inp.dataset.nuevo, inp.value, "allow")) inp.value = "";
  });

  $("d-buscar").addEventListener("input", pintar);
  $("d-filtro").addEventListener("change", pintar);

  $("d-agregar-app").addEventListener("click", () => {
    const pkg = (window.prompt("Nombre del paquete de la app (por ejemplo com.ejemplo.app):") || "").trim();
    if (!pkg) return;
    if (!/^[a-zA-Z][\w]*(\.[a-zA-Z][\w]*)+$/.test(pkg)) {
      LS.toast("Ese no parece un nombre de paquete válido.", "error");
      return;
    }
    const label = (window.prompt("¿Cómo se llama la app?", pkg) || pkg).trim();
    pend(pkg).label = label;
    $("d-buscar").value = pkg;
    pintar();
    LS.toast("Agregada. Ahora poné sus dominios y guardá.", "ok");
  });

  /* ── Enviar a la flota ───────────────────────────────────────────────── */
  $("d-enviar").addEventListener("click", async function () {
    if (cuentaPend()) {
      LS.toast("Guardá los cambios primero.", "error");
      return;
    }
    const ids = Object.keys(S.devices);
    if (!ids.length) { LS.toast("No hay celulares.", "error"); return; }
    if (!confirm("¿Avisarles a los " + ids.length + " celulares que relean el catálogo?\n\n" +
      "No cambia qué apps están permitidas en cada equipo: solo hace que apliquen los dominios " +
      "que acabás de editar.")) return;

    this.disabled = true;
    $("d-envio-card").style.display = "";
    const log = $("d-envio");
    log.innerHTML = "";
    let ok = 0, mal = 0;
    for (const id of ids) {
      const d = S.devices[id] || {};
      const nombre = d.deviceName || (d.info && d.info.deviceName) || d.model || id.slice(0, 10);
      const l = document.createElement("div");
      l.className = "wait";
      l.textContent = "… " + nombre;
      log.appendChild(l);
      const r = await LS.sendCommand(id, "SYNC_WHITELIST", null, null,
        { nombre: nombre, timeoutMs: 20000 });
      if (r.ok) { ok++; l.className = "ok"; l.textContent = "✓ " + nombre; }
      else { mal++; l.className = "bad"; l.textContent = "✗ " + nombre + " — " + (r.reason || r.status); }
    }
    this.disabled = false;
    LS.toast(ok + " aplicaron, " + mal + " no contestaron." +
      (mal ? " Los que no contestaron lo van a leer igual en su próxima sincronización." : ""),
      mal ? "warn" : "ok");
  });

  /* ── Listas fijas (solo lectura) ─────────────────────────────────────── */
  function pintarFijas() {
    const c = LS.catalogo();
    const lista = (titulo, arr, cls, nota) =>
      '<div class="ls-domtitle">' + esc(titulo) + "</div>" +
      (nota ? '<p class="ls-hint" style="margin:4px 0">' + nota + "</p>" : "") +
      '<div class="ls-domlist">' + (arr || []).map((d) =>
        '<span class="ls-dom ' + cls + '">' + esc(d) + "</span>").join("") + "</div>";
    $("d-fijas").innerHTML =
      lista("Infraestructura — pasa siempre (" + (c.infrastructure || []).length + ")",
        c.infrastructure, "allow",
        "Sin estos, un equipo deja de poder recibir un comando de rescate. Por eso no se editan.") +
      lista("Bloqueados siempre (" + (c.blockAlways || []).length + ")",
        c.blockAlways, "block",
        "Rincones no kosher de servicios que por lo demás hacen falta. Le ganan hasta a la infraestructura.") +
      lista("CDN compartidos — con interruptor por equipo (" + (c.sharedCdn || []).length + ")",
        c.sharedCdn, "",
        "Varias apps los necesitan, pero permitir uno es permitir una porción grande de internet.");
  }

  /* ── Arranque ────────────────────────────────────────────────────────── */
  LS.requireAuth(function () {
    pintarFijas();
    LS.db.ref("globalSettings/whitelist/custom").on("value", (snap) => {
      S.custom = snap.val() || {};
      pintar();
    });
    LS.db.ref("devices").on("value", (snap) => {
      S.devices = snap.val() || {};
    });
  });

  window.addEventListener("beforeunload", (e) => {
    if (cuentaPend()) { e.preventDefault(); e.returnValue = ""; }
  });
})();
