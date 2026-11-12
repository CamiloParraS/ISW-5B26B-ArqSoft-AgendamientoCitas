"use strict";

/* ===================================================================== core */

const $ = (sel, root = document) => root.querySelector(sel);
const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const uuid = () => (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random());

const fmt = new Intl.DateTimeFormat("en-GB", { timeZone: "America/Bogota", weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
const fmtTime = new Intl.DateTimeFormat("en-GB", { timeZone: "America/Bogota", hour: "2-digit", minute: "2-digit", second: "2-digit" });
const when = (iso) => (iso ? fmt.format(new Date(iso)) : "—");
const clockText = (iso) => (iso ? fmt.format(new Date(iso)) : "--");

const state = { users: [], user: localStorageGet("user") || "ana", clockNow: null, health: null, provider: null };
const tokens = {};
let screenTimers = [];

function localStorageGet(k) { try { return localStorage.getItem(k); } catch { return null; } }
function localStorageSet(k, v) { try { localStorage.setItem(k, v); } catch { /* private mode */ } }

async function token(username) {
  if (tokens[username]) return tokens[username];
  const r = await fetch("/api/identity/tokens", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password: "demo" }),
  });
  if (!r.ok) throw new Error(`could not sign in as ${username}`);
  tokens[username] = (await r.json()).access_token;
  return tokens[username];
}

/** api(path, {as, method, body, headers}) -> {ok, status, data, ms} ; never throws on HTTP errors */
async function api(path, { as, method = "GET", body, headers = {} } = {}) {
  const h = { ...headers };
  if (as) h.Authorization = "Bearer " + (await token(as));
  if (body !== undefined) h["Content-Type"] = "application/json";
  const t0 = performance.now();
  try {
    const r = await fetch(path, { method, headers: h, body: body !== undefined ? JSON.stringify(body) : undefined });
    const text = await r.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = text; }
    return { ok: r.ok, status: r.status, data, ms: Math.round(performance.now() - t0) };
  } catch (e) {
    return { ok: false, status: 0, data: { error: "network", message: "The app is not answering." }, ms: Math.round(performance.now() - t0) };
  }
}

function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.classList.add("show");
  clearTimeout(toast._t);
  toast._t = setTimeout(() => t.classList.remove("show"), 3200);
}

function every(ms, fn) { fn(); screenTimers.push(setInterval(fn, ms)); }

const httpBadge = (s) => `<span class="http s${String(s)[0] || 5}">${s || "ERR"}</span>`;
const statusBadge = (s) => {
  const cls = { confirmed: "info", attended: "ok", canceled: "", no_show: "warn", sent: "ok", pending: "warn", failed: "bad" }[s] ?? "";
  return `<span class="badge ${cls}">${esc(String(s).replace("_", "-"))}</span>`;
};
const userBy = (username) => state.users.find((u) => u.username === username);
const doctors = () => state.users.filter((u) => u.role === "doctor");

/* ================================================================ top bar */

async function refreshBar() {
  const [h, c, p] = await Promise.all([api("/health"), api("/demo/clock"), api("/demo/provider")]);
  const pill = $("#health-pill");
  pill.className = "pill";
  if (h.status === 0) {
    pill.classList.add("down");
    $("#health-text").textContent = "app down";
    state.health = null;
  } else {
    state.health = h.data;
    pill.classList.add(h.data.status);
    const age = h.data.heartbeat.age_seconds;
    const hb = age == null ? "no sweep yet" : age < 90 ? `sweep ${age}s ago` : `sweep ${Math.round(age / 60)} min ago`;
    $("#health-text").textContent = `${h.data.status} · ${hb}`;
  }
  if (c.ok) {
    state.clockNow = c.data.now;
    $("#clock-now").textContent = clockText(c.data.now);
    const off = c.data.offset_seconds;
    $("#clock-offset").textContent = off ? `+${humanDuration(off)} ahead` : "real time";
  }
  if (p.ok && p.data.reachable) {
    state.provider = p.data;
    const up = p.data.state.up;
    $("#provider-switch").checked = up;
    $("#provider-text").textContent = up ? "on" : "off";
  }
}

function humanDuration(sec) {
  if (sec < 3600) return `${Math.round(sec / 60)} min`;
  const h = Math.floor(sec / 3600), m = Math.round((sec % 3600) / 60);
  return m ? `${h} h ${m} min` : `${h} h`;
}

async function advanceClock(d) {
  const r = await api("/demo/clock", { method: "POST", body: { advance: d } });
  if (r.ok) toast(`Clock moved ${d} forward. The domain sees the new time; the 24 h and 15 min rules follow it.`);
  await refreshBar();
  rerender();
}

async function tickNow() {
  const r = await api("/demo/tick", { method: "POST" });
  if (r.ok) {
    const d = r.data;
    toast(`Tick: ${d.no_shows} no-show(s), ${d.events_delivered} event(s) relayed, ${d.messages.sent} message(s) sent, ${d.messages.retried} retry(ies) scheduled.`);
  } else toast("Tick failed: " + (r.data?.message || r.status));
  await refreshBar();
  return r;
}

async function setProvider(up) {
  await api("/demo/provider", { method: "POST", body: { up } });
  await refreshBar();
  toast(up ? "Provider back on. Pending messages go out on their next attempt." : "Provider switched off. Bookings keep working; messages wait.");
}

function initBar() {
  document.querySelectorAll("[data-advance]").forEach((b) => b.addEventListener("click", () => advanceClock(b.dataset.advance)));
  $("#clock-reset").addEventListener("click", async () => { await api("/demo/clock", { method: "POST", body: { reset: true } }); await refreshBar(); rerender(); });
  $("#tick-now").addEventListener("click", async () => { await tickNow(); rerender(); });
  $("#provider-switch").addEventListener("change", (e) => setProvider(e.target.checked));
  $("#health-pill").addEventListener("click", () => go("health"));
  const sel = $("#user-select");
  sel.addEventListener("change", () => { state.user = sel.value; localStorageSet("user", sel.value); rerender(); });
  setInterval(refreshBar, 2000);
}

function fillUsers() {
  const sel = $("#user-select");
  const groups = { patient: "Patients", doctor: "Doctors", staff: "Staff", dpo: "Data protection" };
  sel.innerHTML = Object.entries(groups).map(([role, label]) =>
    `<optgroup label="${label}">${state.users.filter((u) => u.role === role)
      .map((u) => `<option value="${esc(u.username)}">${esc(u.name)}</option>`).join("")}</optgroup>`).join("");
  if (!userBy(state.user)) state.user = "ana";
  sel.value = state.user;
}

/* ================================================================ routing */

const screens = {};
let current = "health";

function go(name) { location.hash = name; }
function rerender() { render(current, true); }

async function render(name, keepScroll) {
  if (!screens[name]) name = "health";
  if (!keepScroll) { screenTimers.forEach(clearInterval); screenTimers = []; }
  current = name;
  document.querySelectorAll(".steps a").forEach((a) => a.classList.toggle("active", a.dataset.step === name));
  const main = $("#main");
  if (!keepScroll) {
    main.innerHTML = "";
    await screens[name](main);
    main.focus({ preventScroll: true });
    window.scrollTo(0, 0);
  } else if (screens[name].refresh) {
    screens[name].refresh();
  }
}

function header(title, lead, tags = []) {
  return `<div class="head"><h1>${title}</h1><p class="muted">${lead}</p>
    <div class="tags">${tags.map((t) => `<span class="tag">${esc(t)}</span>`).join("")}</div></div>`;
}

/* ========================================================= 1. health */

screens.health = async (main) => {
  main.innerHTML = header("1 · Health check",
    "<code>GET /health</code> pings every module's database role and reports the reverse heartbeat: how long ago the Ticker's last Sweep ran. A silent Ticker turns the status <b>degraded</b> after 15 minutes (risk RS-2).",
    ["T10 heartbeat", "RS-2 reverse heartbeat", "E-3"]) + `<div id="h-body"></div>`;
  every(2000, async () => {
    const r = await api("/health");
    const el = $("#h-body");
    if (!el) return;
    if (r.status === 0) { el.innerHTML = `<div class="callout bad">The app is not answering. The platform restarts it (see step 6).</div>`; return; }
    const h = r.data, hb = h.heartbeat;
    el.innerHTML = `
      <div class="grid3">
        <div class="card stat ${h.status === "ok" ? "ok" : h.status === "degraded" ? "warn" : "bad"}"><span class="k">Status</span><span class="v">${esc(h.status)}</span><small>HTTP ${r.status} · ${r.ms} ms</small></div>
        <div class="card stat ${hb.stale ? "warn" : ""}"><span class="k">Last sweep</span><span class="v">${hb.age_seconds == null ? "never" : humanDuration(Math.max(hb.age_seconds, 60)).replace(/^1 min$/, hb.age_seconds + " s")}</span><small>stale after ${hb.stale_after_seconds / 60} min · ${hb.last_sweep_at ? when(hb.last_sweep_at) : "waiting for the Ticker"}</small></div>
        <div class="card stat"><span class="k">Outbox pending</span><span class="v">${h.outbox_pending}</span><small>up ${humanDuration(Math.max(h.uptime_seconds, 60))} · since ${fmtTime.format(new Date(h.started_at))}</small></div>
      </div>
      <div class="grid2">
        <div class="card"><h2>One role per module</h2>
          <table><tr><th>Database role</th><th>Ping</th></tr>
          ${Object.entries(h.database).sort().map(([k, v]) => `<tr><td class="mono">${esc(k)}</td><td>${v === "ok" ? '<span class="badge ok">ok</span>' : '<span class="badge bad">' + esc(v) + "</span>"}</td></tr>`).join("")}
          </table></div>
        <div class="card"><h2>Try the heartbeat</h2>
          <p>Move the clock <b>+16 min</b>: the last Sweep now looks 16 minutes old, as if the Ticker had stopped calling, and the pill turns amber. Press <b>Tick now</b> (or wait up to 30 s for the Ticker container) and it is healthy again.</p>
          <div class="row"><button class="btn" data-a="16m">+16 min</button><button class="btn primary" data-t>Tick now</button></div></div>
      </div>`;
    el.querySelector("[data-a]").onclick = () => advanceClock("16m");
    el.querySelector("[data-t]").onclick = tickNow;
  });
};

/* ================================================== 2. concurrency (E-1) */

screens.burst = async (main) => {
  main.innerHTML = header("2 · Fifty patients, one slot",
    "E-1: 50 booking requests for the same slot arrive at the same instant. Each one is a different patient going through Scheduling's public interface. Exactly one is confirmed; the other 49 get <i>slot taken</i> with three alternatives.",
    ["T1 conditional write", "T2 partial unique index", "T3 idempotency key", "T4 outbox", "ADR-002"]) + `
    <div class="card">
      <div class="row spread">
        <div class="row"><label for="b-slot" class="label">Slot</label><select id="b-slot" class="field"></select></div>
        <button class="btn primary" id="b-fire">Fire 50 bookings</button>
      </div>
      <div class="cells" id="b-cells">${Array.from({ length: 50 }, (_, i) => `<div class="cell">${i + 1}</div>`).join("")}</div>
      <div class="grid3" id="b-stats"></div>
    </div>
    <div class="grid2">
      <div class="card" id="b-alts"><h2>What the 49 see</h2><p class="muted">Fire the burst to see the answer.</p></div>
      <div class="card"><h2>Why exactly one</h2>
        <pre class="code">UPDATE scheduling.slots SET status = 'booked'
 WHERE id = $1 AND status = 'open' AND starts_at &gt; $now
-- 0 rows changed: someone else has it</pre>
        <p class="muted" style="margin-top:10px">The database decides, not the code. If any path skipped this write, the unique index on <code>appointments(slot_id) WHERE status &lt;&gt; 'canceled'</code> would still reject the second appointment.</p>
        <p class="muted">The browser can only open 6 connections to one host, so this button asks the server to start 50 goroutines at once. <code>go test ./internal/app -run E1</code> repeats the same test 100 times.</p>
      </div>
    </div>`;
  await loadBurstSlots();
  $("#b-fire").onclick = fireBurst;
};

async function loadBurstSlots() {
  const jef = doctors().find((d) => d.username === "jeferson");
  const r = await api(`/api/scheduling/slots?doctor_id=${jef.subject_id}`, { as: "staff" });
  const sel = $("#b-slot");
  if (!r.ok || !r.data.length) { sel.innerHTML = `<option>No open Cardiology slots left</option>`; return; }
  sel.innerHTML = r.data.map((s) => `<option value="${s.id}">${esc(s.doctor)} · ${esc(s.specialty)} · ${when(s.starts_at)}</option>`).join("");
  const eight = r.data.find((s) => fmt.format(new Date(s.starts_at)).includes("08:00"));
  if (eight) sel.value = eight.id;
}

async function fireBurst() {
  const btn = $("#b-fire"), slot = $("#b-slot").value;
  btn.disabled = true;
  const cells = [...document.querySelectorAll("#b-cells .cell")];
  cells.forEach((c) => (c.className = "cell pending"));
  const r = await api("/demo/burst", { method: "POST", body: { slot_id: slot, n: 50 } });
  btn.disabled = false;
  if (!r.ok) { toast(r.data?.message || "Burst failed"); cells.forEach((c) => (c.className = "cell")); return; }
  const res = r.data.results;
  for (let i = 0; i < res.length; i++) {
    cells[i].className = "cell " + res[i].outcome;
    cells[i].title = `${res[i].patient}: ${res[i].outcome} in ${Math.round(res[i].ms)} ms`;
    cells[i].textContent = res[i].outcome === "confirmed" ? "✓" : i + 1;
    if (i % 5 === 4) await sleep(18);
  }
  const c = r.data.counts;
  const winner = res.find((x) => x.outcome === "confirmed");
  $("#b-stats").innerHTML = `
    <div class="stat ok"><span class="k">Confirmed</span><span class="v">${c.confirmed || 0}</span><small>${winner ? esc(winner.patient) : "nobody (slot was already taken)"}</small></div>
    <div class="stat bad"><span class="k">Slot taken</span><span class="v">${c.slot_taken || 0}</span><small>${c.error ? c.error + " errors" : "0 errors, 0 half-written bookings"}</small></div>
    <div class="stat ${r.data.p95_ms <= 1000 ? "" : "warn"}"><span class="k">p95 latency</span><span class="v">${r.data.p95_ms.toFixed(0)} ms</span><small>budget 1 000 ms · max ${r.data.max_ms.toFixed(0)} ms</small></div>`;
  const alts = r.data.alternatives || [];
  $("#b-alts").innerHTML = `<h2>What the 49 see</h2>
    <p><span class="http s4">409</span> <b>slot_taken</b>: “That slot was just taken.” Plus three alternatives:</p>
    <table>${alts.map((s) => `<tr><td>${esc(s.doctor)}</td><td>${when(s.starts_at)}</td></tr>`).join("") || '<tr><td class="empty">No alternatives open</td></tr>'}</table>`;
  await loadBurstSlots();
}

/* ======================================================= 3. messages */

screens.messages = async (main) => {
  main.innerHTML = header("3 · The message reached the provider",
    "A booking writes <code>AppointmentBooked</code> to the outbox in the same transaction. The relay (every 2 s while the app is awake, and on every Tick) hands it to Notifications, which queues a Confirmation and a Reminder for 24 h before the appointment, then sends due messages to the fake provider.",
    ["T4 transactional outbox", "amendment: continuous drain", "amendment: idempotent inbox"]) + `
    <div class="grid2">
      <div class="card"><div class="row spread"><h2>Fake provider inbox</h2><span id="m-count" class="badge"></span></div><div id="m-provider" class="table-wrap"></div></div>
      <div class="card"><h2>Notifications queue</h2><p class="muted">What the app still has to send, and how each attempt went.</p><div id="m-queue" class="table-wrap"></div></div>
    </div>`;
  every(2000, refreshMessages);
};

async function refreshMessages() {
  const [p, q] = await Promise.all([api("/demo/provider"), api("/demo/messages")]);
  const prov = $("#m-provider");
  if (!prov) return;
  const received = p.data?.received || [];
  $("#m-count").textContent = `${received.length} received`;
  prov.innerHTML = received.length ? `<table><tr><th>Received</th><th>To</th><th>Text</th></tr>${received.slice(0, 12).map((m) =>
    `<tr><td class="num">${fmtTime.format(new Date(m.received_at))}</td><td class="mono">${esc(m.to)}</td><td>${esc(m.body)} <span class="badge">${esc(m.kind)}</span></td></tr>`).join("")}</table>`
    : `<p class="empty">Nothing yet. Book something in step 2 or in the patient view.</p>`;
  const queue = q.data || [];
  $("#m-queue").innerHTML = queue.length ? `<table><tr><th>Kind</th><th>Status</th><th>Send at</th><th>Tries</th></tr>${queue.slice(0, 14).map((m) =>
    `<tr><td>${esc(m.kind)}</td><td>${statusBadge(m.status)}${m.last_error && m.status === "pending" ? `<br><small>${esc(m.last_error)}</small>` : ""}</td><td>${when(m.send_at)}</td><td class="num">${m.attempts}</td></tr>`).join("")}</table>`
    : `<p class="empty">No messages queued.</p>`;
}

/* ================================================== 4. provider down */

screens.provider = async (main) => {
  main.innerHTML = header("4 · The provider goes down",
    "Graceful degradation: when the messaging provider fails, the booking is confirmed anyway and the message waits. Each failed attempt is retried after 1, 2, 4, 8… minutes with random jitter, for up to 2 hours.",
    ["T12 graceful degradation", "T13 timeout 3 s + backoff with jitter"]) + `
    <div class="grid2">
      <div class="card"><ol class="steps-list" id="p-steps">
        <li id="p1"><b>Switch the provider off.</b><div class="row" style="margin-top:6px"><button class="btn" id="p-off">Provider off</button></div></li>
        <li id="p2"><b>Luis books a slot.</b> The booking is confirmed straight away.<div class="row" style="margin-top:6px"><button class="btn primary" id="p-book">Book as Luis</button><span id="p-book-out"></span></div></li>
        <li id="p3"><b>Watch the message wait.</b> The drain loop tries, the provider answers 503, and the next try is scheduled with backoff.</li>
        <li id="p4"><b>Switch the provider on</b> and move past the backoff: it goes out.<div class="row" style="margin-top:6px"><button class="btn" id="p-on">Provider on</button><button class="btn" id="p-skip">+2 min and Tick</button></div></li>
      </ol></div>
      <div class="card"><h2>Luis's messages</h2><div id="p-msgs" class="table-wrap"><p class="empty">Book first.</p></div></div>
    </div>`;
  $("#p-off").onclick = async () => { await setProvider(false); $("#p1").classList.add("done"); };
  $("#p-on").onclick = async () => { await setProvider(true); };
  $("#p-skip").onclick = async () => { await api("/demo/clock", { method: "POST", body: { advance: "2m" } }); await tickNow(); refreshProviderMsgs(); };
  $("#p-book").onclick = async () => {
    const luis = userBy("luis");
    const slots = await api("/api/scheduling/slots", { as: "luis" });
    const slot = slots.data?.find((s) => s.specialty === "Orthopedics") || slots.data?.[0];
    if (!slot) return toast("No open slots");
    const r = await api("/api/scheduling/appointments", { as: "luis", method: "POST", body: { slot_id: slot.id }, headers: { "Idempotency-Key": uuid() } });
    $("#p-book-out").innerHTML = `${httpBadge(r.status)} ${r.ok ? `confirmed in ${r.ms} ms · ${esc(slot.doctor)}, ${when(slot.starts_at)}` : esc(r.data?.message)}`;
    if (r.ok) { $("#p2").classList.add("done"); state.luisAppt = r.data.id; }
    void luis;
  };
  every(1500, refreshProviderMsgs);
};

async function refreshProviderMsgs() {
  const el = $("#p-msgs");
  if (!el) return;
  const r = await api("/api/notifications/messages", { as: "luis" });
  const ms = (r.data || []).filter((m) => !state.luisAppt || m.appointment_id === state.luisAppt);
  if (!ms.length) return;
  if (ms.some((m) => m.attempts > 0 && m.status === "pending")) $("#p3").classList.add("done");
  if (ms.some((m) => m.kind === "confirmation" && m.status === "sent") && $("#p3").classList.contains("done")) $("#p4").classList.add("done");
  el.innerHTML = `<table><tr><th>Kind</th><th>Status</th><th>Tries</th><th>Next try</th></tr>${ms.map((m) => `
    <tr><td>${esc(m.kind)}</td><td>${statusBadge(m.status)}${m.last_error && m.status === "pending" ? `<br><small>${esc(m.last_error)}</small>` : ""}</td>
    <td class="num">${m.attempts}</td><td>${m.status === "pending" ? when(m.next_attempt_at) : m.sent_at ? "sent " + when(m.sent_at) : "—"}</td></tr>`).join("")}</table>
    <p class="muted" style="margin-top:8px">The reminder is scheduled for 24 h before the appointment, so it stays pending on purpose.</p>`;
}

/* ================================================ 5. clinical access (E-2) */

screens.clinical = async (main) => {
  const ana = userBy("ana");
  main.innerHTML = header("5 · Who may read Ana's clinical record",
    "E-2: a doctor can read a patient's diagnoses only with a treatment relationship, which is fed by Scheduling's events and never by asking Scheduling. Every attempt is written to the access log <i>before</i> any data is returned. If that write fails, the read fails.",
    ["T5 signed token", "T6 role + relationship", "T7 role per module", "T8 append-only log", "T9 column encryption"]) + `
    <div class="card">
      <p><code>GET /api/clinical/patients/${esc(ana.subject_id)}/encounters</code> with three different tokens:</p>
      <div class="row">
        <button class="btn" data-as="jeferson">As Dr. Jeferson <small>(treated Ana)</small></button>
        <button class="btn" data-as="camilo">As Dr. Camilo <small>(never saw Ana)</small></button>
        <button class="btn" data-as="staff">As Staff</button>
      </div>
      <div id="c-out" style="margin-top:14px"></div>
    </div>
    <div class="grid2">
      <div class="card"><h2>A relationship is born from an event</h2>
        <p>Ana books with Dr. Camilo. <code>AppointmentBooked</code> goes through the outbox, Clinical records the relationship, and his next read is allowed. The relationship is eventually consistent, so it takes a couple of seconds.</p>
        <div class="row"><button class="btn primary" id="c-book">Book Ana with Dr. Camilo, then read</button></div>
        <div id="c-book-out" class="stack" style="margin-top:10px"></div>
      </div>
      <div class="card"><div class="row spread"><h2>Access log</h2><span class="badge">as the DPO</span></div>
        <div id="c-log" class="table-wrap"></div></div>
    </div>`;
  main.querySelectorAll("[data-as]").forEach((b) => (b.onclick = () => readAs(b.dataset.as, $("#c-out"))));
  $("#c-book").onclick = bookAnaWithCamilo;
  every(2000, refreshAccessLog);
};

async function readAs(username, out) {
  const ana = userBy("ana");
  const r = await api(`/api/clinical/patients/${ana.subject_id}/encounters`, { as: username });
  const who = userBy(username)?.name || username;
  if (r.ok) {
    out.innerHTML = `<div class="callout ok">${httpBadge(r.status)} <b>${esc(who)}</b> may read it (${r.ms} ms). Decrypted on read:</div>` +
      r.data.map((e) => `<div class="encounter" style="margin-top:10px"><div class="row spread"><b>${esc(e.doctor_name)}</b><small>${when(e.occurred_at)}</small></div>
        <div><span class="label">Diagnosis</span><br>${esc(e.diagnosis)}</div><div style="margin-top:6px"><span class="label">Prescription</span><br>${esc(e.prescription)}</div></div>`).join("");
  } else {
    out.innerHTML = `<div class="callout bad">${httpBadge(r.status)} <b>${esc(who)}</b>: “${esc(r.data?.message)}” The answer says nothing about whether Ana has any records.</div>`;
  }
  refreshAccessLog();
  return r;
}

async function bookAnaWithCamilo() {
  const out = $("#c-book-out");
  const camilo = userBy("camilo");
  const slots = await api(`/api/scheduling/slots?doctor_id=${camilo.subject_id}`, { as: "ana" });
  const slot = slots.data?.[0];
  if (!slot) return toast("Dr. Camilo has no open slots");
  const b = await api("/api/scheduling/appointments", { as: "ana", method: "POST", body: { slot_id: slot.id }, headers: { "Idempotency-Key": uuid() } });
  out.innerHTML = `<div>${httpBadge(b.status)} Booked ${when(slot.starts_at)} with Dr. Camilo.</div>`;
  if (!b.ok) return;
  const t0 = performance.now();
  for (let i = 0; i < 20; i++) {
    const r = await api(`/api/clinical/patients/${userBy("ana").subject_id}/encounters`, { as: "camilo" });
    const s = ((performance.now() - t0) / 1000).toFixed(1);
    out.insertAdjacentHTML("beforeend", `<div>${httpBadge(r.status)} Dr. Camilo reads, t+${s} s ${r.ok ? "· <b>relationship arrived</b>" : "· not yet"}</div>`);
    if (r.ok) break;
    await sleep(700);
  }
  out.insertAdjacentHTML("beforeend", `<p class="muted">Cancel that appointment from the patient view and the relationship goes away again.</p>`);
  refreshAccessLog();
}

async function refreshAccessLog() {
  const el = $("#c-log");
  if (!el) return;
  const r = await api("/api/audit/access-entries?limit=12", { as: "dpo" });
  const es = r.data || [];
  el.innerHTML = es.length ? `<table><tr><th>When</th><th>Who</th><th>Result</th></tr>${es.map((e) => `
    <tr><td class="num">${fmtTime.format(new Date(e.at))}</td><td>${esc(e.user_name)} <small>${esc(e.role)} · ${esc(e.action)} · ${esc(e.source)}</small></td>
    <td>${e.allowed ? '<span class="badge ok">allowed</span>' : '<span class="badge bad">denied</span>'}<br><small>${esc(e.reason)}</small></td></tr>`).join("")}</table>`
    : `<p class="empty">No clinical access yet.</p>`;
}

/* =================================================== 6. crash (E-3) */

screens.crash = async (main) => {
  main.innerHTML = header("6 · Kill the process",
    "E-3: the app process dies during opening hours. The platform's health check notices and restarts it. Appointments confirmed before the crash are still there, because they were committed to PostgreSQL and not held in memory.",
    ["T10 heartbeat + automatic restart", "E-3: recovery ≤ 2 min, 0 lost appointments"]) + `
    <div class="card">
      <div class="row spread"><div id="x-before" class="stat"><span class="k">Confirmed appointments</span><span class="v">…</span></div>
        <button class="btn danger" id="x-crash">Crash the app (exit 1)</button></div>
      <div class="timeline" id="x-line"></div>
      <div id="x-log" class="stack"></div>
    </div>
    <p class="muted">In the report's script this is <code>docker compose kill app</code>. A manual kill counts as a deliberate stop, so Docker would not restart it. This button instead makes the process exit with an error, which is the case the restart policy covers.</p>`;
  const count = async () => {
    const r = await api("/demo/appointments");
    return r.ok ? r.data.filter((a) => a.status === "confirmed").length : null;
  };
  const before = await count();
  $("#x-before .v").textContent = before ?? "?";
  $("#x-crash").onclick = async () => {
    const btn = $("#x-crash"), log = $("#x-log"), line = $("#x-line");
    btn.disabled = true;
    const n0 = await count();
    await api("/demo/crash", { method: "POST" });
    const t0 = performance.now();
    log.innerHTML = `<div>💥 Crash requested with ${n0} confirmed appointments. Polling <code>/health</code>…</div>`;
    let downAt = null, upAt = null;
    while (performance.now() - t0 < 180000) {
      const r = await api("/health");
      const t = (performance.now() - t0) / 1000;
      if (r.status === 0 || r.status >= 500) {
        if (downAt == null) { downAt = t; log.insertAdjacentHTML("beforeend", `<div><span class="badge bad">down</span> at t+${t.toFixed(1)} s</div>`); }
      } else if (downAt != null) { upAt = t; break; }
      line.innerHTML = `<span style="width:${Math.min(100, t / 1.2)}%;background:var(--bad)"></span>`;
      await sleep(300);
    }
    if (upAt == null) { log.insertAdjacentHTML("beforeend", `<div class="callout bad">Still down after 3 minutes. Is the app running under docker compose (restart: unless-stopped)?</div>`); btn.disabled = false; return; }
    const n1 = await count();
    line.innerHTML = `<span style="width:${(downAt / upAt) * 100}%;background:var(--ok)"></span><span style="flex:1;background:var(--bad)"></span>`;
    log.insertAdjacentHTML("beforeend", `<div><span class="badge ok">back</span> at t+${upAt.toFixed(1)} s, about ${(upAt - downAt).toFixed(1)} s of downtime (budget: 2 min)</div>
      <div class="callout ${n1 === n0 ? "ok" : "bad"}">${n1} confirmed appointments after the restart, ${n0} before. ${n1 === n0 ? "Nothing was lost." : "Something changed!"}</div>`);
    $("#x-before .v").textContent = n1;
    btn.disabled = false;
    refreshBar();
  };
};

/* ================================================ 7. no-show sweep (E-4) */

screens.sweep = async (main) => {
  main.innerHTML = header("7 · The no-show nobody had to mark",
    "E-4: an appointment passes 15 minutes without a check-in while the free hosting tier has the process suspended. The next Tick wakes the app, and the Sweep marks it as a no-show exactly once. A second Sweep finds nothing new.",
    ["T14 idempotent recovery sweep", "ADR-003 external Ticker", "injectable clock"]) + `
    <div class="grid2">
      <div class="card"><ol class="steps-list">
        <li id="s1"><b>Staff opens a slot</b> with Dr. Juan Diego, starting in 5 minutes on the demo clock.<div class="row" style="margin-top:6px"><button class="btn" id="s-open">Open slot</button><span id="s-open-out"></span></div></li>
        <li id="s2"><b>Luis books it</b>, then never shows up.<div class="row" style="margin-top:6px"><button class="btn" id="s-book" disabled>Book as Luis</button><span id="s-book-out"></span></div></li>
        <li id="s3"><b>Time passes</b>: 5 minutes until the start, plus the 15 minute grace, plus 1.<div class="row" style="margin-top:6px"><button class="btn" id="s-adv" disabled>Clock +21 min</button></div></li>
        <li id="s4"><b>The Ticker calls twice in a row.</b><div class="row" style="margin-top:6px"><button class="btn primary" id="s-tick" disabled>Tick, Tick</button></div></li>
      </ol></div>
      <div class="card"><h2>Result</h2><div id="s-result" class="stack"><p class="empty">Run the steps.</p></div></div>
    </div>`;
  let slotId = null, apptId = null;
  $("#s-open").onclick = async () => {
    const jd = userBy("juandiego");
    const now = new Date(state.clockNow || Date.now());
    const start = new Date(Math.ceil((now.getTime() + 5 * 60000) / 60000) * 60000);
    const r = await api("/api/scheduling/slots", { as: "staff", method: "POST", body: { doctor_id: jd.subject_id, starts_at: start.toISOString() } });
    $("#s-open-out").innerHTML = `${httpBadge(r.status)} ${r.ok ? when(r.data.starts_at) : esc(r.data?.message)}`;
    if (r.ok) { slotId = r.data.id; $("#s1").classList.add("done"); $("#s-book").disabled = false; }
  };
  $("#s-book").onclick = async () => {
    const r = await api("/api/scheduling/appointments", { as: "luis", method: "POST", body: { slot_id: slotId }, headers: { "Idempotency-Key": uuid() } });
    $("#s-book-out").innerHTML = `${httpBadge(r.status)} ${r.ok ? statusBadge(r.data.status) : esc(r.data?.message)}`;
    if (r.ok) { apptId = r.data.id; $("#s2").classList.add("done"); $("#s-adv").disabled = false; }
  };
  $("#s-adv").onclick = async () => { await advanceClock("21m"); $("#s3").classList.add("done"); $("#s-tick").disabled = false; };
  $("#s-tick").onclick = async () => {
    const a = await api("/demo/tick", { method: "POST" });
    const b = await api("/demo/tick", { method: "POST" });
    $("#s4").classList.add("done");
    await sleep(300);
    const appts = await api("/api/scheduling/appointments", { as: "luis" });
    const appt = appts.data?.find((x) => x.id === apptId);
    const msgs = await api("/api/notifications/messages", { as: "luis" });
    const noShowMsgs = (msgs.data || []).filter((m) => m.appointment_id === apptId && m.kind === "no_show");
    $("#s-result").innerHTML = `
      <table><tr><th>Tick</th><th>No-shows marked</th><th>Messages sent</th></tr>
        <tr><td>first</td><td class="num"><b>${a.data?.no_shows ?? "?"}</b></td><td class="num">${a.data?.messages?.sent ?? "?"}</td></tr>
        <tr><td>second</td><td class="num"><b>${b.data?.no_shows ?? "?"}</b></td><td class="num">${b.data?.messages?.sent ?? "?"}</td></tr></table>
      <div>Appointment: ${appt ? statusBadge(appt.status) : "?"}</div>
      <div class="callout ${noShowMsgs.length === 1 ? "ok" : "bad"}">${noShowMsgs.length} no-show message for this appointment, even though the Sweep ran twice. The conditional write <code>WHERE status = 'confirmed'</code> changes 0 rows the second time.</div>
      <p class="muted">Reset the clock in the top bar when you are done.</p>`;
    refreshBar();
  };
};

/* ================================================ 8. fitness functions */

screens.fitness = async (main) => {
  main.innerHTML = header("8 · Fitness functions",
    "Five rules guard the decisions that are expensive to undo. Two are enforced by the Go compiler; the rest are tests. They all run with <code>make check</code> and fail when someone takes a shortcut.",
    ["S10 walking skeleton", "ADR-001"]) + `
    <div class="card"><table>
      <tr><th>Rule</th><th>Who checks it</th><th>Protects</th></tr>
      <tr><td>A module imports only another module's <code>api.go</code>, never its internal layers</td><td>The Go compiler (<code>internal/</code> packages)</td><td>ADR-001</td></tr>
      <tr><td>No import cycles between modules</td><td>The Go compiler</td><td>ADR-001</td></tr>
      <tr><td>Presentation → application → domain; infrastructure → domain; domain never imports <code>net/http</code> or <code>pgx</code></td><td><code>internal/archtest</code> reads <code>go list</code></td><td>ADR-001</td></tr>
      <tr><td>The <code>scheduling_app</code> role cannot read the clinical schema</td><td><code>TestDBRoleBoundaries</code></td><td>E-2, T7</td></tr>
      <tr><td>50 concurrent bookings on one slot produce exactly one appointment (×100)</td><td><code>TestE1ConcurrentBooking</code></td><td>E-1, ADR-002</td></tr>
    </table></div>
    <div class="grid2">
      <div class="card"><h2>Run them</h2>
        <pre class="code">docker compose up -d postgres
mingw32-make check     # or: make check</pre>
        <p class="muted" style="margin-top:10px">The database tests use the separate <code>clinic_test</code> database, so they never touch the demo data.</p></div>
      <div class="card"><h2>Break one on purpose</h2>
        <p>Add this to <code>scheduling/internal/domain/domain.go</code>:</p>
        <pre class="code">import _ "github.com/jackc/pgx/v5"</pre>
        <p class="muted" style="margin-top:10px"><code>make check</code> fails: <i>scheduling/internal/domain must not import github.com/jackc/pgx/v5</i>. Importing <code>clinic/clinical/internal/domain</code> from Scheduling does not even compile.</p></div>
    </div>`;
};

/* ==================================================== patient view */

screens.patient = async (main) => {
  const u = userBy(state.user);
  if (!u || u.role !== "patient") {
    main.innerHTML = header("Patient view", "Pick a patient in <b>Signed in as</b> (Ana or Luis).") +
      `<div class="row"><button class="btn" data-u="ana">Sign in as Ana</button><button class="btn" data-u="luis">Sign in as Luis</button></div>`;
    main.querySelectorAll("[data-u]").forEach((b) => (b.onclick = () => { state.user = b.dataset.u; $("#user-select").value = b.dataset.u; localStorageSet("user", b.dataset.u); render("patient"); }));
    return;
  }
  main.innerHTML = header(`Hi, ${esc(u.name.split(" ")[0])}`, "Book, cancel or reschedule. Cancellations and reschedules need at least 24 hours' notice on the demo clock.") + `
    <div class="grid2">
      <div class="card"><div class="row spread"><h2>Book an appointment</h2><select id="pt-doc" class="field"></select></div><div id="pt-slots" class="table-wrap"></div></div>
      <div class="card"><h2>My appointments</h2><div id="pt-appts" class="table-wrap"></div>
        <h2 style="margin-top:16px">My messages</h2><div id="pt-msgs" class="table-wrap"></div></div>
    </div>`;
  $("#pt-doc").innerHTML = `<option value="">All doctors</option>` + doctors().map((d) => `<option value="${d.subject_id}">${esc(d.name)}</option>`).join("");
  $("#pt-doc").onchange = loadPatientSlots;
  state.rescheduling = null;
  await Promise.all([loadPatientSlots(), loadPatientAppts()]);
  screens.patient.refresh = () => { loadPatientSlots(); loadPatientAppts(); };
};

async function loadPatientSlots() {
  const doc = $("#pt-doc")?.value || "";
  const r = await api(`/api/scheduling/slots${doc ? "?doctor_id=" + doc : ""}`, { as: state.user });
  const el = $("#pt-slots");
  if (!el) return;
  const banner = state.rescheduling ? `<div class="callout">Pick the new time for your appointment. <button class="btn sm ghost" id="pt-cancel-r">Never mind</button></div>` : "";
  el.innerHTML = banner + ((r.data || []).length ? `<table>${r.data.slice(0, 15).map((s) => `<tr><td>${when(s.starts_at)}</td><td>${esc(s.doctor)}<br><small>${esc(s.specialty)}</small></td>
    <td><button class="btn sm ${state.rescheduling ? "" : "primary"}" data-slot="${s.id}">${state.rescheduling ? "Move here" : "Book"}</button></td></tr>`).join("")}</table>` : `<p class="empty">No open slots.</p>`);
  el.querySelectorAll("[data-slot]").forEach((b) => (b.onclick = () => (state.rescheduling ? doReschedule(b.dataset.slot) : doBook(b.dataset.slot))));
  const cr = $("#pt-cancel-r");
  if (cr) cr.onclick = () => { state.rescheduling = null; loadPatientSlots(); };
}

async function doBook(slotId) {
  const key = uuid();
  const r = await api("/api/scheduling/appointments", { as: state.user, method: "POST", body: { slot_id: slotId }, headers: { "Idempotency-Key": key } });
  if (r.ok) {
    toast(`Booked (${r.status}). Confirmation on its way.`);
    setTimeout(loadPatientAppts, 3000); // the outbox drain sends it within ~2 s
  }
  else if (r.status === 409) toast(`That slot was just taken. Try ${r.data.alternatives?.[0] ? when(r.data.alternatives[0].starts_at) : "another one"}.`);
  else toast(r.data?.message || "Could not book");
  await Promise.all([loadPatientSlots(), loadPatientAppts()]);
}

async function doReschedule(slotId) {
  const r = await api(`/api/scheduling/appointments/${state.rescheduling}/reschedule`, { as: state.user, method: "POST", body: { slot_id: slotId } });
  toast(r.ok ? "Rescheduled: the old slot is free again." : r.data?.message || "Could not reschedule");
  state.rescheduling = null;
  await Promise.all([loadPatientSlots(), loadPatientAppts()]);
}

async function loadPatientAppts() {
  const [r, m] = await Promise.all([api("/api/scheduling/appointments", { as: state.user }), api("/api/notifications/messages", { as: state.user })]);
  const el = $("#pt-appts");
  if (!el) return;
  el.innerHTML = (r.data || []).length ? `<table>${r.data.map((a) => `<tr><td>${when(a.starts_at)}<br><small>${esc(a.doctor)}</small></td><td>${statusBadge(a.status)}</td>
    <td>${a.status === "confirmed" ? `<div class="row"><button class="btn sm" data-r="${a.id}">Reschedule</button><button class="btn sm ghost" data-c="${a.id}">Cancel</button></div>` : ""}</td></tr>`).join("")}</table>` : `<p class="empty">No appointments yet.</p>`;
  el.querySelectorAll("[data-c]").forEach((b) => (b.onclick = async () => {
    const x = await api(`/api/scheduling/appointments/${b.dataset.c}/cancel`, { as: state.user, method: "POST" });
    toast(x.ok ? "Canceled. The slot is open again for others." : `${x.status}: ${x.data?.message}`);
    loadPatientAppts(); loadPatientSlots();
  }));
  el.querySelectorAll("[data-r]").forEach((b) => (b.onclick = () => { state.rescheduling = b.dataset.r; loadPatientSlots(); toast("Pick the new time on the left."); }));
  const msgs = m.data || [];
  $("#pt-msgs").innerHTML = msgs.length ? `<table>${msgs.slice(0, 6).map((x) => `<tr><td>${esc(x.body)}</td><td>${statusBadge(x.status)}</td></tr>`).join("")}</table>` : `<p class="empty">No messages.</p>`;
}

/* ===================================================== doctor view */

screens.doctor = async (main) => {
  const u = userBy(state.user);
  if (!u || u.role !== "doctor") {
    main.innerHTML = header("Doctor view", "Pick a doctor in <b>Signed in as</b>.") +
      `<div class="row">${doctors().map((d) => `<button class="btn" data-u="${d.username}">Sign in as ${esc(d.name)}</button>`).join("")}</div>`;
    main.querySelectorAll("[data-u]").forEach((b) => (b.onclick = () => { state.user = b.dataset.u; $("#user-select").value = b.dataset.u; localStorageSet("user", b.dataset.u); render("doctor"); }));
    return;
  }
  main.innerHTML = header(esc(u.name), "Your schedule. Check patients in, read their records (audited) and record diagnosis and prescription.") + `
    <div class="grid2"><div class="card"><h2>Schedule</h2><div id="dr-appts" class="table-wrap"></div></div>
    <div class="card" id="dr-record"><h2>Clinical record</h2><p class="empty">Pick a patient.</p></div></div>`;
  await loadDoctorAppts();
  screens.doctor.refresh = loadDoctorAppts;
};

async function loadDoctorAppts() {
  const r = await api("/api/scheduling/appointments", { as: state.user });
  const el = $("#dr-appts");
  if (!el) return;
  const rows = (r.data || []).filter((a) => a.status !== "canceled");
  el.innerHTML = rows.length ? `<table>${rows.map((a) => `<tr><td>${when(a.starts_at)}</td><td>${esc(a.patient)}</td><td>${statusBadge(a.status)}</td>
    <td><div class="row">${a.status === "confirmed" ? `<button class="btn sm" data-ci="${a.id}">Check in</button>` : ""}<button class="btn sm ghost" data-rec="${a.patient_id}" data-name="${esc(a.patient)}">Record</button></div></td></tr>`).join("")}</table>`
    : `<p class="empty">No appointments.</p>`;
  el.querySelectorAll("[data-ci]").forEach((b) => (b.onclick = async () => {
    const x = await api(`/api/scheduling/appointments/${b.dataset.ci}/check-in`, { as: state.user, method: "POST" });
    toast(x.ok ? "Checked in." : x.data?.message); loadDoctorAppts();
  }));
  el.querySelectorAll("[data-rec]").forEach((b) => (b.onclick = () => openRecord(b.dataset.rec, b.dataset.name)));
}

async function openRecord(patientId, name) {
  const box = $("#dr-record");
  const r = await api(`/api/clinical/patients/${patientId}/encounters`, { as: state.user });
  if (!r.ok) {
    box.innerHTML = `<h2>${esc(name)}</h2><div class="callout bad">${httpBadge(r.status)} ${esc(r.data?.message)} ${r.status === 403 ? "The treatment relationship arrives a moment after a booking; try again in 2 s." : ""}</div>`;
    return;
  }
  box.innerHTML = `<h2>${esc(name)}</h2>${r.data.map((e) => `<div class="encounter"><div class="row spread"><b>${esc(e.doctor_name)}</b><small>${when(e.occurred_at)}</small></div>
      <div><span class="label">Diagnosis</span><br>${esc(e.diagnosis)}</div><div style="margin-top:6px"><span class="label">Prescription</span><br>${esc(e.prescription)}</div></div>`).join("") || '<p class="empty">No encounters yet.</p>'}
    <h2 style="margin-top:16px">New encounter</h2>
    <div class="stack"><textarea class="field" id="dr-dx" rows="2" placeholder="Diagnosis"></textarea><textarea class="field" id="dr-rx" rows="2" placeholder="Prescription"></textarea>
    <button class="btn primary" id="dr-save">Save (encrypted at rest)</button></div>`;
  $("#dr-save").onclick = async () => {
    const x = await api(`/api/clinical/patients/${patientId}/encounters`, { as: state.user, method: "POST", body: { diagnosis: $("#dr-dx").value, prescription: $("#dr-rx").value } });
    toast(x.ok ? "Encounter saved and audited." : x.data?.message);
    if (x.ok) openRecord(patientId, name);
  };
}

/* ======================================================== start */

async function start() {
  initBar();
  const r = await api("/api/identity/demo-users");
  if (!r.ok) {
    $("#main").innerHTML = `<div class="callout bad">Cannot load the demo users. Is the app running with DEMO_MODE=true and seeded (<code>migrate -seed</code>)?</div>`;
    return;
  }
  state.users = r.data;
  fillUsers();
  await refreshBar();
  window.addEventListener("hashchange", () => render(location.hash.slice(1)));
  render(location.hash.slice(1) || "health");
}

start();
