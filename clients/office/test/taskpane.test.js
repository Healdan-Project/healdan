// The add-in's pane and message window (clients/office/web/taskpane.js and taskpane.html), run with Node's built-in
// runner and a fake page:
//
//   node --test 'clients/office/test/*.test.js'
//
// The page is a handful of elements read from the real taskpane.html (so a script that asks for an id the page doesn't
// have fails here), the timers are ours to move, and the helper is a fetch that answers as its port does.

"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const Healdan = require("../web/healdan.js");
const Pane = require("../web/taskpane.js");

const SERVER = "https://dms.example.com";
const KEY = "k".repeat(43);
const HTML = fs.readFileSync(path.join(__dirname, "..", "web", "taskpane.html"), "utf8");

// --- A fake page -----------------------------------------------------------------------------------------------------

/** The elements of taskpane.html, with the ids it gives them and whether they start hidden. */
function fakePage() {
  const elements = new Map();
  const page = { focused: null, elements };
  for (const [, attributes] of HTML.matchAll(/<[a-z0-9]+\s([^>]*\bid="[^"]+"[^>]*)>/g)) {
    const id = /\bid="([^"]+)"/.exec(attributes)[1];
    const listeners = {};
    elements.set(id, {
      id,
      hidden: /\bhidden\b/.test(attributes.replace(/"[^"]*"/g, '""')),
      textContent: "",
      className: /\bclass="([^"]*)"/.exec(attributes)?.[1] ?? "",
      value: "",
      attributes: {},
      selected: false,
      setAttribute(name, value) {
        this.attributes[name] = String(value);
      },
      addEventListener(type, listener) {
        (listeners[type] ??= []).push(listener);
      },
      submit() {
        const event = { prevented: false, preventDefault() { this.prevented = true; } };
        for (const listener of listeners.submit ?? []) {
          listener(event);
        }
        return event;
      },
      focus() {
        page.focused = this;
      },
      select() {
        this.selected = true;
      },
    });
  }
  page.getElementById = (id) => {
    if (!elements.has(id)) {
      throw new Error(`taskpane.html has no element with the id ${id}`);
    }
    return elements.get(id);
  };
  return page;
}

/** Timers that run when told to. */
function fakeClock() {
  let now = 1_000_000;
  let next = 1;
  const timers = new Map();
  return {
    now: () => now,
    setTimeout(fn, ms) {
      const id = next++;
      timers.set(id, { fn, due: now + ms });
      return id;
    },
    clearTimeout(id) {
      timers.delete(id);
    },
    pending: () => timers.size,
    /** Moves time on, running whatever falls due, in order, letting each one's promises settle first. */
    async advance(ms) {
      const until = now + ms;
      for (;;) {
        await settle();
        const due = [...timers.entries()].filter(([, timer]) => timer.due <= until).sort((a, b) => a[1].due - b[1].due);
        if (due.length === 0) {
          break;
        }
        const [id, timer] = due[0];
        timers.delete(id);
        now = Math.max(now, timer.due);
        timer.fn();
      }
      now = until;
      await settle();
    },
  };
}

const settle = async () => {
  for (let i = 0; i < 20; i += 1) {
    await new Promise((resolve) => setImmediate(resolve));
  }
};

function memoryStorage(initial = {}) {
  const map = new Map(Object.entries(initial));
  return {
    map,
    getItem: (name) => (map.has(name) ? map.get(name) : null),
    setItem: (name, value) => map.set(name, String(value)),
    removeItem: (name) => map.delete(name),
  };
}

/**
 * The pane on a fake page. `routes` are the helper's, each [status, body], a function of the request, or a promise of
 * one; the helper listens as hello answers unless `listening` is false.
 */
async function open({
  search = "",
  host = "Word",
  paired = false,
  routes = {},
  listening = true,
  office = { host: "Word", platform: "Mac" },
  officeLate = false,
  stored = {},
} = {}) {
  const page = fakePage();
  const clock = fakeClock();
  const storage = memoryStorage({ ...(paired ? { [`healdan.key.${host}`]: KEY } : {}), ...stored });
  const requests = [];
  const table = {
    hello: [200, { name: "Healdan Helper", protocols: [1], server: SERVER }],
    ping: [200, { ok: true, signedIn: true }],
    ...routes,
  };
  const fetch = async (url, init = {}) => {
    const route = new URL(url).pathname.replace(/^\/v1\//, "");
    if (!listening) {
      throw new TypeError("Load failed");
    }
    requests.push({ route, body: init.body ? JSON.parse(init.body) : undefined });
    let answer = table[route] ?? [404, { error: "not-found" }];
    if (typeof answer === "function") {
      answer = answer();
    }
    const [status, body] = await answer;
    return { ok: status >= 200 && status < 300, status, json: async () => body };
  };
  const storageListeners = [];
  const Office = office
    ? { PlatformType: { OfficeOnline: "OfficeOnline", Mac: "Mac" }, onReady: () => (officeLate ? new Promise(() => {}) : Promise.resolve(office)) }
    : undefined;
  const pane = Pane.start({
    document: page,
    Healdan: { ...Healdan, storage: () => storage },
    Office,
    location: { search },
    createClient: (hostName) => Healdan.createHelperClient({ host: hostName, fetch, storage, origin: SERVER }),
    now: clock.now,
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout,
    onStorage: (listener) => storageListeners.push(listener),
  });
  // Opening waits for Office, and a check of the helper; the clock can move on (a late Office is waited for 3 s).
  if (officeLate) {
    await clock.advance(3000);
  }
  await settle();
  const started = await Promise.race([pane, settle().then(() => null)]);
  const el = (id) => page.elements.get(id);
  return { page, el, clock, storage, requests, started, pane, storageListeners, routes: table, calls: () => requests.filter((r) => r.route !== "hello") };
}

// --- The page --------------------------------------------------------------------------------------------------------

test("it loads as a classic script that starts itself only on the pane's own page", async () => {
  const source = fs.readFileSync(path.join(__dirname, "..", "web", "taskpane.js"), "utf8");
  const bare = vm.createContext({});
  assert.doesNotThrow(() => vm.runInContext(source, bare), "no document: nothing happens");

  let started = 0;
  const stub = { setAttribute() {}, addEventListener() {}, focus() {} };
  const page = vm.createContext({
    document: { getElementById: (id) => (id === "pairing" ? stub : { ...stub }) },
    Healdan: { ...Healdan, storage: () => { started += 1; return null; } },
    location: { search: "" },
    setTimeout,
    clearTimeout,
    addEventListener: () => {},
  });
  vm.runInContext(source, page);
  await settle();
  assert.equal(started, 1, "on a page with the pairing form, it starts");

  const other = vm.createContext({
    document: { getElementById: () => null },
    Healdan: { ...Healdan, storage: () => { throw new Error("started"); } },
  });
  assert.doesNotThrow(() => vm.runInContext(source, other), "on a page without it, it doesn't");
});

test("every id the script asks for is in the page, and the page loads its scripts in order", () => {
  const source = fs.readFileSync(path.join(__dirname, "..", "web", "taskpane.js"), "utf8");
  const asked = [...source.matchAll(/\$\("([a-z-]+)"\)/g)].map((match) => match[1]);
  assert.ok(asked.length > 10);
  const page = fakePage();
  for (const id of asked) {
    assert.ok(page.elements.has(id), `taskpane.html has #${id}`);
  }
  const scripts = [...HTML.matchAll(/<script src="([^"]+)"/g)].map((match) => match[1]);
  assert.deepEqual(scripts, ["https://appsforoffice.microsoft.com/lib/1/hosted/office.js", "healdan.js", "taskpane.js"]);
  assert.ok(!/<script(?![^>]*\bsrc=)/.test(HTML), "no inline script: the server's Content-Security-Policy allows none");
  assert.ok(!/\son[a-z]+=/.test(HTML), "no inline handler");
  assert.ok(!/\sstyle=/.test(HTML), "no inline style");
});

test("the commands page loads Microsoft's script first, then the add-in's, and the glue before the commands", () => {
  const commands = fs.readFileSync(path.join(__dirname, "..", "web", "commands.html"), "utf8");
  const scripts = [...commands.matchAll(/<script src="([^"]+)"/g)].map((match) => match[1]);
  assert.deepEqual(scripts, ["https://appsforoffice.microsoft.com/lib/1/hosted/office.js", "healdan.js", "office-glue.js", "commands.js"]);
  assert.ok(!/<script(?![^>]*\bsrc=)/.test(commands), "no inline script");
});

test("every file the pages load is one the server serves", () => {
  const web = path.join(__dirname, "..", "web");
  const servable = /^[A-Za-z0-9-]+\.(html|js|css)$/;
  const assets = /^assets\/[A-Za-z0-9-]+\.png$/;
  for (const name of ["taskpane.html", "commands.html"]) {
    const html = fs.readFileSync(path.join(web, name), "utf8");
    for (const [, reference] of html.matchAll(/(?:src|href)="([^"]+)"/g)) {
      if (/^https:\/\/appsforoffice\.microsoft\.com\//.test(reference)) {
        continue;
      }
      assert.ok(servable.test(reference) || assets.test(reference), `${name}: ${reference} is served`);
      assert.ok(fs.existsSync(path.join(web, reference)), `${name}: ${reference} exists`);
    }
  }
});

// --- The status ------------------------------------------------------------------------------------------------------

test("statusFor says what the helper's state is, in the words of messageFor", () => {
  const status = (state) => Pane.statusFor(Healdan, { app: "Word", ...state });
  assert.deepEqual(status({ checked: false }), { kind: "waiting", text: "Checking Healdan Helper…" });
  assert.deepEqual(status({ checked: false, slow: true }), { kind: "waiting", text: "Healdan Helper is taking a while to answer…" });
  assert.deepEqual(status({ checked: true, error: "unreachable" }), { kind: "problem", text: Healdan.messageFor("unreachable") });
  assert.deepEqual(status({ checked: true, error: "update-healdan-helper" }), { kind: "problem", text: Healdan.messageFor("update-healdan-helper") });
  assert.deepEqual(status({ checked: true, paired: false }), { kind: "ok", text: "Healdan Helper is running. Word isn't paired with it yet." });
  assert.deepEqual(status({ checked: true, paired: true, signedIn: false }), { kind: "problem", text: Healdan.messageFor("signed-out") });
  assert.deepEqual(status({ checked: true, paired: true, signedIn: true }), { kind: "ok", text: "Healdan Helper is running, and Word is paired with it." });
});

test("busyText names the button and the app, and says where the waiting is", () => {
  const text = Pane.busyText({ flow: "saveToHealdan", label: "Save to Healdan", app: "Word" });
  assert.match(text, /^Healdan is working on Save to Healdan in Word\./);
  assert.match(text, /window open, finish there/);
  assert.doesNotMatch(Pane.busyText({ flow: "properties", label: "Properties" }), / in undefined/);
});

// --- A window opened for a message -----------------------------------------------------------------------------------

test("the first press: a window with the message, the helper's code asked for, and the field in focus", async () => {
  const { el, page, calls } = await open({ search: "?message=not-paired&app=Word", routes: { "pair/start": [200, { pairingId: "p-1" }] } });

  assert.equal(el("message-section").hidden, false);
  assert.equal(el("message-title").textContent, "Message");
  assert.equal(el("message").textContent, Healdan.messageFor("not-paired"));
  assert.equal(el("message-when").hidden, true);
  assert.equal(el("status").textContent, "Healdan Helper is running. Word isn't paired with it yet.");
  assert.equal(el("pairing").hidden, false);
  assert.equal(el("pairing-app").textContent, "Word");
  assert.deepEqual(calls().map((r) => r.route), ["pair/start"]);
  assert.deepEqual(calls()[0].body, { app: "Word" });
  assert.match(el("pairing-help").textContent, /six-digit code in a window on your Mac/);
  assert.equal(page.focused, el("code"), "the user types the code next");
  assert.equal(el("main").attributes["aria-busy"], "false");
});

test("typing the code and choosing Pair pairs the app, and says to press the button again", async () => {
  const { el, storage, calls, clock } = await open({
    search: "?message=not-paired&app=Word",
    routes: { "pair/start": [200, { pairingId: "p-1" }], "pair/finish": [200, { key: KEY }] },
  });
  el("code").value = "123 456";
  const event = el("pairing").submit();
  assert.equal(event.prevented, true);
  await clock.advance(0);

  assert.deepEqual(calls().find((r) => r.route === "pair/finish").body, { pairingId: "p-1", code: "123456" });
  assert.equal(storage.getItem("healdan.key.Word"), KEY);
  assert.equal(el("done").hidden, false);
  assert.equal(el("done").textContent, "Word is paired with Healdan Helper. Close this window, then press the button again.");
  assert.equal(el("pairing").hidden, true);
  assert.equal(el("code").value, "");
  assert.equal(el("status").textContent, "Healdan Helper is running, and Word is paired with it.");
  assert.ok(calls().some((r) => r.route === "ping"), "and it asks the helper how it is, now it has a key");
});

test("Pair is off while the helper is being asked, so a code isn't sent twice", async () => {
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  const { el, clock, calls } = await open({
    search: "?message=not-paired&app=Word",
    routes: { "pair/start": [200, { pairingId: "p-1" }], "pair/finish": () => gate.then(() => [200, { key: KEY }]) },
  });
  assert.equal(el("pair").disabled, false);

  el("code").value = "123456";
  el("pairing").submit();
  await clock.advance(0);
  assert.equal(el("pair").disabled, true);
  assert.equal(el("main").attributes["aria-busy"], "true");

  release();
  await clock.advance(0);
  assert.equal(el("pair").disabled, false);
  assert.equal(calls().filter((r) => r.route === "pair/finish").length, 1);
});

test("a wrong code says how many tries are left, keeps the pairing, and selects the code to type again", async () => {
  const { el, calls, clock } = await open({
    search: "?message=not-paired&app=Excel",
    host: "Excel",
    routes: { "pair/start": [200, { pairingId: "p-1" }], "pair/finish": [403, { error: "wrong-code", triesLeft: 2 }] },
  });
  el("code").value = "000000";
  el("pairing").submit();
  await clock.advance(0);

  assert.equal(el("pairing-problem").textContent, "That code isn't right. You have 2 tries left.");
  assert.equal(el("code").selected, true);
  assert.equal(el("pairing").hidden, false);
  assert.equal(calls().filter((r) => r.route === "pair/start").length, 1, "the same pairing goes on");
});

test("a code that expired, or ran out of tries, is told, and Pair then asks for a new one", async () => {
  for (const [status, answer, text] of [
    [403, { error: "pairing-expired" }, Healdan.messageFor("pairing-expired")],
    [403, { error: "wrong-code", triesLeft: 0 }, Healdan.messageFor("wrong-code", { triesLeft: 0 })],
  ]) {
    let started = 0;
    const { el, calls, clock } = await open({
      search: "?message=not-paired&app=Word",
      routes: { "pair/start": () => [200, { pairingId: `p-${++started}` }], "pair/finish": [status, answer] },
    });
    el("code").value = "000000";
    el("pairing").submit();
    await clock.advance(0);
    assert.equal(el("pairing-problem").textContent, text);
    assert.match(el("pairing-help").textContent, /Choose Pair to ask Healdan Helper for a new code/);

    el("pairing").submit(); // no pairing under way: this asks for a new code, and finishes nothing
    await clock.advance(0);
    assert.equal(calls().filter((r) => r.route === "pair/start").length, 2);
    assert.equal(calls().filter((r) => r.route === "pair/finish").length, 1);
    assert.equal(el("pairing-problem").textContent, "");
    assert.match(el("pairing-help").textContent, /six-digit code in a window/);
  }
});

test("something that isn't six digits is told without a try spent", async () => {
  const { el, calls, clock } = await open({ search: "?message=not-paired&app=Word", routes: { "pair/start": [200, { pairingId: "p-1" }] } });
  el("code").value = "12 34";
  el("pairing").submit();
  await clock.advance(0);
  assert.equal(el("pairing-problem").textContent, Healdan.messageFor("code-format"));
  assert.equal(calls().filter((r) => r.route === "pair/finish").length, 0);
});

test("pairing paused after too many wrong codes is told, and nothing is sent to type a code into", async () => {
  const { el, calls } = await open({ search: "?message=not-paired&app=Word", routes: { "pair/start": [403, { error: "too-many-tries" }] } });
  assert.equal(el("pairing-problem").textContent, Healdan.messageFor("too-many-tries"));
  assert.equal(calls().length, 1);
});

test("a message that needs the app's name or Office's words gets them from the address", async () => {
  const local = await open({ search: "?message=local-save&app=PowerPoint", host: "PowerPoint", paired: true });
  assert.equal(local.el("message").textContent, "Use PowerPoint's own Save As to save it on this computer.");

  const failed = await open({ search: "?message=office-error&app=Word&detail=The+footer+can%27t+be+changed", paired: true });
  assert.equal(failed.el("message").textContent, Healdan.messageFor("office-error", { detail: "The footer can't be changed" }));
  assert.equal(failed.page.focused, failed.el("message"), "a message is read out");
  // What the address holds is shown as text, never as markup.
  const markup = await open({ search: "?message=office-error&app=Word&detail=%3Cimg+src%3Dx+onerror%3Dalert(1)%3E", paired: true });
  assert.match(markup.el("message").textContent, /<img src=x onerror=alert\(1\)>/);
});

test("a window for a message with no app in its address asks Office which app it is", async () => {
  const { el } = await open({ search: "?message=signed-out", paired: true, office: { host: "Excel", platform: "Mac" }, host: "Excel" });
  assert.equal(el("pairing-app").textContent, "Excel");
  assert.equal(el("message").textContent, Healdan.messageFor("signed-out"));
});

// --- The pane --------------------------------------------------------------------------------------------------------

test("the pane shows the last message, and when it was given", async () => {
  const storage = memoryStorage();
  Healdan.noteMessage(storage, { code: "lost-result", app: "Word" }, Date.UTC(2026, 9, 31, 14, 32));
  const { el } = await open({ paired: true, stored: { "healdan.last": storage.getItem("healdan.last") } });
  assert.equal(el("message-section").hidden, false);
  assert.equal(el("message-title").textContent, "Last Message");
  assert.equal(el("message").textContent, Healdan.messageFor("lost-result"));
  assert.match(el("message-when").textContent, /^Given .*2026.*\.$/);
  assert.equal(el("message-when").hidden, false);
});

test("the pane with no message shows none", async () => {
  const { el } = await open({ paired: true });
  assert.equal(el("message-section").hidden, true);
});

test("the pane says the helper is running and the app is paired, from ping", async () => {
  const { el, calls } = await open({ paired: true });
  assert.equal(el("status").textContent, "Healdan Helper is running, and Word is paired with it.");
  assert.equal(el("status").className, "status ok");
  assert.equal(el("pairing").hidden, true);
  assert.deepEqual(calls().map((r) => r.route), ["ping"], "only ping: state would have the helper drive Word at every check");
});

test("the pane says when the helper is signed out", async () => {
  const { el } = await open({ paired: true, routes: { ping: [200, { ok: true, signedIn: false }] } });
  assert.equal(el("status").textContent, Healdan.messageFor("signed-out"));
  assert.equal(el("status").className, "status problem");
});

test("the pane says when the helper isn't running, shows no field to type a code in, and finds it when it starts", async () => {
  const dead = await open({ paired: true, listening: false });
  assert.equal(dead.el("status").textContent, Healdan.messageFor("unreachable"));
  assert.equal(dead.el("status").className, "status problem");
  assert.equal(dead.el("pairing").hidden, true);
  assert.equal(dead.el("main").attributes["aria-busy"], "false");

  // Not running, then running: the next check, five seconds on, finds it.
  let running = false;
  const routes = {
    hello: () => (running ? [200, { name: "Healdan Helper", protocols: [1], server: SERVER }] : Promise.reject(new TypeError("Load failed"))),
  };
  const late = await open({ paired: true, routes });
  assert.equal(late.el("status").textContent, Healdan.messageFor("unreachable"));
  running = true;
  await late.clock.advance(Pane.POLL);
  assert.equal(late.el("status").textContent, "Healdan Helper is running, and Word is paired with it.");
});

test("the pane says to update the helper when it speaks no protocol the add-in does", async () => {
  const { el } = await open({ paired: true, routes: { hello: [200, { name: "Healdan Helper", protocols: [2], server: SERVER }] } });
  assert.equal(el("status").textContent, Healdan.messageFor("update-healdan-helper"));
});

test("the pane asks to pair when the helper has forgotten its key", async () => {
  const { el, storage, calls } = await open({
    paired: true,
    routes: { ping: [403, { error: "not-paired" }], "pair/start": [200, { pairingId: "p-1" }] },
  });
  assert.equal(storage.getItem("healdan.key.Word"), null, "the key the helper doesn't know is dropped");
  assert.equal(el("pairing").hidden, false);
  assert.equal(el("status").textContent, "Healdan Helper is running. Word isn't paired with it yet.");
  assert.deepEqual(calls().map((r) => r.route), ["ping", "pair/start"]);
});

test("the pane checks again every five seconds, and asks for one code only", async () => {
  const { clock, calls } = await open({ routes: { "pair/start": [200, { pairingId: "p-1" }] } });
  await clock.advance(Pane.POLL);
  await clock.advance(Pane.POLL);
  assert.equal(calls().filter((r) => r.route === "pair/start").length, 1, "an unpaired app's code is asked for once");
  assert.equal(clock.pending() > 0, true);
});

// --- Waiting ---------------------------------------------------------------------------------------------------------

test("a slow helper is waited for: the status says so, and no second check starts meanwhile", async () => {
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  const { el, clock, calls } = await open({ paired: true, routes: { ping: () => gate.then(() => [200, { ok: true, signedIn: true }]) } });
  // The first check is still waiting for ping.
  assert.equal(el("status").textContent, "Checking Healdan Helper…");
  assert.equal(el("main").attributes["aria-busy"], "true");

  await clock.advance(Pane.SLOW);
  assert.equal(el("status").textContent, "Healdan Helper is taking a while to answer…");
  await clock.advance(60000);
  assert.equal(calls().filter((r) => r.route === "ping").length, 1, "one check at a time, however long it takes");

  release();
  await clock.advance(0);
  assert.equal(el("status").textContent, "Healdan Helper is running, and Word is paired with it.");
  assert.equal(el("main").attributes["aria-busy"], "false");
  await clock.advance(Pane.POLL);
  assert.equal(calls().filter((r) => r.route === "ping").length, 2, "and the next check begins five seconds after the last ended");
});

test("pairing while a check is still waiting starts no second check, and the next one follows at once", async () => {
  const HELLO = [200, { name: "Healdan Helper", protocols: [1], server: SERVER }];
  let gate = null;
  const { el, clock, requests, calls } = await open({
    search: "?message=not-paired&app=Word",
    routes: {
      hello: () => (gate === null ? HELLO : gate.then(() => HELLO)),
      "pair/start": [200, { pairingId: "p-1" }],
      "pair/finish": [200, { key: KEY }],
    },
  });
  const hellos = () => requests.filter((r) => r.route === "hello").length;
  assert.equal(calls().filter((r) => r.route === "ping").length, 0);

  let release;
  gate = new Promise((resolve) => {
    release = resolve;
  });
  await clock.advance(Pane.POLL); // the next check begins, and waits for the helper to say hello
  const waiting = hellos();

  el("code").value = "123456";
  el("pairing").submit();
  await clock.advance(0);
  assert.equal(el("done").hidden, false, "the app is paired at once");
  assert.equal(hellos(), waiting, "and no second check begins while the first waits");

  gate = null;
  release();
  await clock.advance(0);
  assert.equal(el("status").textContent, "Healdan Helper is running, and Word is paired with it.");
  assert.ok(calls().filter((r) => r.route === "ping").length >= 1);
  assert.ok(hellos() > waiting, "the check wanted after pairing follows the one that was waiting");
});

test("a button's flow waiting on the helper shows in the pane, and ends when it does", async () => {
  const { el, clock, storage, storageListeners } = await open({ paired: true });
  assert.equal(el("busy-section").hidden, true);

  Healdan.markBusy(storage, "saveToHealdan", "Word", clock.now());
  storageListeners.forEach((listener) => listener()); // another page changed localStorage
  assert.equal(el("busy-section").hidden, false);
  assert.match(el("busy-text").textContent, /Healdan is working on Save to Healdan in Word\./);

  Healdan.clearBusy(storage, "saveToHealdan");
  await clock.advance(2000); // or the pane looks again by itself
  assert.equal(el("busy-section").hidden, true);
  assert.equal(el("busy-text").textContent, "");
});

test("a note that a flow is busy goes stale if the page that left it is gone", async () => {
  const { el, clock, storage } = await open({ paired: true });
  Healdan.markBusy(storage, "openFromHealdan", "Word", clock.now());
  await clock.advance(2000);
  assert.equal(el("busy-section").hidden, false);
  await clock.advance(Healdan.BUSY_STALE + 2000);
  assert.equal(el("busy-section").hidden, true);
});

// --- Not in an app ---------------------------------------------------------------------------------------------------

test("on the web, the pane says the buttons work in the desktop apps, and asks the helper nothing", async () => {
  const { el, requests } = await open({ office: { host: "Word", platform: "OfficeOnline" }, paired: true });
  assert.match(el("web").textContent, /Healdan's buttons work in the desktop apps/);
  assert.equal(el("web").hidden, false);
  assert.equal(el("status-section").hidden, true);
  assert.equal(el("pairing").hidden, true);
  assert.deepEqual(requests, []);
});

test("opened in a browser, outside Word, Excel and PowerPoint, the page says what it is for", async () => {
  const { el, requests } = await open({ office: null });
  assert.match(el("web").textContent, /works inside Word, Excel and PowerPoint/);
  assert.deepEqual(requests, []);

  // Office.js that never says which app it is isn't waited for forever.
  const late = await open({ officeLate: true });
  assert.match(late.el("web").textContent, /works inside Word, Excel and PowerPoint/);
});
