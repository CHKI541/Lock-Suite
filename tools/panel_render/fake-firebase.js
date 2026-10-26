// Firebase compat FALSO, en memoria, para renderizar el panel sin red (receta de B.65).
(function () {
  const TS = { ".sv": "timestamp" };
  const root = { v: (window.__SEED__ && JSON.parse(JSON.stringify(window.__SEED__))) || {} };
  const listeners = []; // {path, cb, event}
  const split = p => String(p || "").split("/").filter(Boolean);
  function getAt(path) {
    let n = root.v;
    for (const k of split(path)) { if (n == null || typeof n !== "object") return null; n = n[k]; }
    return n === undefined ? null : n;
  }
  function resolveTs(v) {
    if (v && typeof v === "object") {
      if (v[".sv"] === "timestamp") return Date.now();
      const o = Array.isArray(v) ? [] : {};
      for (const k of Object.keys(v)) o[k] = resolveTs(v[k]);
      return o;
    }
    return v;
  }
  function setAt(path, val) {
    const ks = split(path);
    if (!ks.length) { root.v = resolveTs(val) || {}; return; }
    let n = root.v;
    for (let i = 0; i < ks.length - 1; i++) {
      if (n[ks[i]] == null || typeof n[ks[i]] !== "object") n[ks[i]] = {};
      n = n[ks[i]];
    }
    const last = ks[ks.length - 1];
    if (val === null || val === undefined) delete n[last]; else n[last] = resolveTs(val);
  }
  function snap(path) {
    const v = getAt(path);
    const ks = split(path);
    return {
      key: ks.length ? ks[ks.length - 1] : null,
      val: () => (v == null ? null : JSON.parse(JSON.stringify(v))),
      exists: () => v != null,
      forEach: (fn) => { if (v && typeof v === "object") for (const k of Object.keys(v)) if (fn(snap(path + "/" + k)) === true) return true; return false; },
      child: (p) => snap(path + "/" + p),
      hasChildren: () => !!(v && typeof v === "object" && Object.keys(v).length)
    };
  }
  function notify(changedPath) {
    const c = split(changedPath).join("/");
    for (const l of listeners.slice()) {
      const p = split(l.path).join("/");
      if (c === p || c.startsWith(p + "/") || p.startsWith(c + "/") || p === "" ) {
        try { l.cb(snap(l.path)); } catch (e) { console.error("listener error", e); }
      }
    }
  }
  function ref(path) {
    path = split(path).join("/");
    const r = {
      key: split(path).slice(-1)[0] || null,
      path,
      child: (p) => ref(path + "/" + p),
      on: (ev, cb, err) => { listeners.push({ path, cb, ev }); setTimeout(() => cb(snap(path)), 0); return cb; },
      off: (ev, cb) => { for (let i = listeners.length - 1; i >= 0; i--) if (listeners[i].path === path && (!cb || listeners[i].cb === cb)) listeners.splice(i, 1); },
      once: (ev) => Promise.resolve(snap(path)),
      set: (v) => { setAt(path, v); notify(path); return Promise.resolve(); },
      update: (obj) => { for (const k of Object.keys(obj)) setAt(path + "/" + k, obj[k]); notify(path); return Promise.resolve(); },
      remove: () => { setAt(path, null); notify(path); return Promise.resolve(); },
      push: (v) => { const k = "p" + Math.random().toString(36).slice(2, 10); setAt(path + "/" + k, v); notify(path); return Promise.resolve(ref(path + "/" + k)); },
      orderByChild: () => r, limitToLast: () => r, equalTo: () => r
    };
    return r;
  }
  const user = { uid: "admin1", email: "admin@test", getIdToken: () => Promise.resolve("TOKEN") };
  const authObj = {
    currentUser: user,
    onAuthStateChanged: (cb) => { setTimeout(() => cb(user), 0); return () => {}; },
    signOut: () => Promise.resolve(), signInWithEmailAndPassword: () => Promise.resolve(),
    getRedirectResult: () => Promise.resolve(null), signInWithPopup: () => Promise.resolve(), signInWithRedirect: () => Promise.resolve()
  };
  const db = { ref };
  window.firebase = {
    initializeApp: () => ({}),
    apps: [1],
    auth: Object.assign(() => authObj, { GoogleAuthProvider: function () {} }),
    database: Object.assign(() => db, { ServerValue: { TIMESTAMP: TS } }),
    functions: () => ({ httpsCallable: () => () => Promise.resolve({ data: {} }) })
  };
  // Acceso para la prueba
  window.__fakeDb = { getAt, setAt: (p, v) => { setAt(p, v); notify(p); }, listeners };
})();
