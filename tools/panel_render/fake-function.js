// Cloud Function FALSA (sendCommandV8) para la prueba: escribe en la base falsa lo mismo
// que la real (ack "sent" + copia en el buzón) y contesta según window.__fnMode.
(function () {
  const realFetch = window.fetch;
  window.__fnMode = "fcm_old";
  window.__calls = [];
  window.fetch = async (url, opts) => {
    if (String(url).includes("sendcommandv8")) {
      const body = JSON.parse(opts.body);
      const cid = "c" + Math.random().toString(36).slice(2, 8);
      window.__lastCid = cid;
      window.__calls.push(body);
      const d = "devices/" + body.deviceId;
      __fakeDb.setAt(d + "/commandAcks/" + cid, { status: "sent", command: body.command, timestamp: Date.now() });
      __fakeDb.setAt(d + "/pendingCommands/" + cid, { data: { command: body.command, commandId: cid, timestamp: String(Date.now()), signature: "s" }, family: body.command, queuedAt: Date.now(), expiresAt: Date.now() + 86400000 });
      const m = window.__fnMode;
      let resp;
      if (m === "fcm_old") resp = { success: true, commandId: cid, queued: true };
      else if (m === "fcm_new") resp = { success: true, commandId: cid, queued: true, delivered: true, channel: "fcm" };
      else if (m === "notoken_old") resp = { success: true, commandId: cid, queued: true, fcmError: "NO_FCM_TOKEN" };
      else if (m === "notoken_new") resp = { success: true, commandId: cid, queued: true, delivered: false, channel: "buzon", fcmError: "NO_FCM_TOKEN" };
      if (m.startsWith("notoken")) __fakeDb.setAt(d + "/commandAcks/" + cid, { status: "queued", command: body.command, timestamp: Date.now(), reason: "sin token" });
      return new Response(JSON.stringify(resp), { status: 200, headers: { "Content-Type": "application/json" } });
    }
    return realFetch(url, opts);
  };
  // "El celular aplica": ack applied, se borra la copia del buzón y (opcional) reporta el estado.
  window.__deviceApplies = (deviceId, cid, campo, valor, status) => {
    const d = "devices/" + deviceId;
    const ack = __fakeDb.getAt(d + "/commandAcks/" + cid) || {};
    __fakeDb.setAt(d + "/commandAcks/" + cid, { status: status || "applied", command: ack.command, timestamp: Date.now(), reason: status === "rejected" ? "BAD_SIGNATURE" : undefined });
    __fakeDb.setAt(d + "/pendingCommands/" + cid, null);
    if (campo) __fakeDb.setAt(d + "/" + campo, valor);
  };
})();
