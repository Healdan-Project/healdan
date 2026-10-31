// The ribbon's six buttons (clients/office/web/commands.js), run with Node's built-in runner:
//
//   node --test 'clients/office/test/*.test.js'
//
// The real flows (healdan.js) run over a fake helper and a fake office object; Office.js's dialog and actions are fakes
// that log what they're asked.

"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const Healdan = require("../web/healdan.js");
const Commands = require("../web/commands.js");

const SERVER = "https://dms.example.com";
const KEY = "k".repeat(43);

// --- Fakes -----------------------------------------------------------------------------------------------------------

function memoryStorage(initial = {}) {
  const map = new Map(Object.entries(initial));
  return {
    map,
    getItem: (name) => (map.has(name) ? map.get(name) : null),
    setItem: (name, value) => map.set(name, String(value)),
    removeItem: (name) => map.delete(name),
  };
}

/** A fetch that answers like the helper's port: `routes` maps a route to [status, body] or to a function of the request. */
function fakeFetch(routes, requests = []) {
  const table = { hello: [200, { name: "Healdan Helper", protocols: [1], server: SERVER }], ...routes };
  return async (url, init = {}) => {
    const route = new URL(url).pathname.replace(/^\/v1\//, "");
    requests.push({ route, body: init.body ? JSON.parse(init.body) : undefined });
    const answer = table[route] ?? [404, { error: "not-found" }];
    const [status, body] = typeof answer === "function" ? answer() : answer;
    return { ok: status >= 200 && status < 300, status, json: async () => body };
  };
}

/**
 * The commands over fakes. `answers` are the helper's routes; the dialog opens (or doesn't) as `dialog` says; `waits` is
 * a promise the helper's first keyed route waits for, as a window of its own waiting for the user.
 */
function setup({ host = "Word", paired = true, answers = {}, dialog = "succeeded", waits = null, officeFails = null, insertFails = null } = {}) {
  const storage = memoryStorage(paired ? { [`healdan.key.${host}`]: KEY } : {});
  const requests = [];
  const dialogs = [];
  const log = [];
  const intervals = [];
  let clock = 1000;

  const fetch = fakeFetch(answers, requests);
  const wrapped = waits === null ? fetch : async (url, init) => (new URL(url).pathname.endsWith("/hello") ? fetch(url, init) : (await waits, fetch(url, init)));

  const office = {
    host,
    onTheWeb: false,
    url: () => "",
    readBytes: async () => "UEsDBA==",
    setMark: async () => {},
    removeMark: async () => {},
    insertDocId: async () => {
      if (insertFails) {
        throw new Error(insertFails);
      }
    },
  };

  const page = {
    ...Healdan,
    storage: () => storage,
    createHelperClient: (options) => Healdan.createHelperClient({ ...options, fetch: wrapped, storage, origin: SERVER }),
  };

  const Office = {
    AsyncResultStatus: { Succeeded: "succeeded", Failed: "failed" },
    context: {
      ui: {
        displayDialogAsync(address, options, callback) {
          dialogs.push({ address, options });
          if (dialog === "throws") {
            throw new Error("DialogApi isn't there");
          }
          callback(dialog === "succeeded" ? { status: "succeeded", value: {} } : { status: "failed", error: { message: "Another dialog is open" } });
        },
      },
    },
  };

  const commands = Commands.createCommands({
    Healdan: page,
    makeOffice: () => {
      if (officeFails) {
        throw new Error(officeFails);
      }
      return office;
    },
    Office,
    location: { href: "https://dms.example.com/api/addin/commands.html" },
    now: () => clock,
    setInterval: (fn, ms) => {
      intervals.push({ fn, ms, cleared: false });
      return intervals.length - 1;
    },
    clearInterval: (id) => {
      intervals[id].cleared = true;
    },
    console: { warn: (text) => log.push(text) },
  });

  /** Presses a button, and says how many times Office was told it had finished. */
  async function press(name) {
    let completed = 0;
    await commands[name]({ completed: () => (completed += 1) });
    return completed;
  }

  return { commands, press, storage, requests, dialogs, log, intervals, tick: (ms) => (clock += ms) };
}

const SAVE = [200, { action: "save", folderId: 12, name: "Lease agreement", format: "docx", properties: {}, ticket: "ticket-1" }];
const FILED = [200, { known: true, docNumber: 1234, docRef: "#1234 v1.0", readOnly: false }];

// --- The manifest's names --------------------------------------------------------------------------------------------

test("the commands are the manifest's function names, each defined once", () => {
  const manifest = fs.readFileSync(path.join(__dirname, "..", "manifest.template.xml"), "utf8");
  const named = [...manifest.matchAll(/<FunctionName>([^<]+)<\/FunctionName>/g)].map((match) => match[1]);
  assert.deepEqual([...new Set(named)].sort(), [...Commands.FLOWS].sort());
  const { commands } = setup();
  assert.deepEqual(Object.keys(commands).sort(), [...Commands.FLOWS].sort());
  for (const name of Commands.FLOWS) {
    assert.equal(typeof commands[name], "function", name);
    assert.equal(typeof Healdan[name], "function", `${name} is a flow in healdan.js`);
  }
});

// --- A press ---------------------------------------------------------------------------------------------------------

test("a button that did what it was asked says nothing, and tells Office it is finished", async () => {
  const { press, dialogs, storage } = setup({ answers: { "open-dialog": [200, { action: "opened" }] } });
  assert.equal(await press("openFromHealdan"), 1);
  assert.deepEqual(dialogs, []);
  assert.equal(storage.getItem("healdan.last"), null);
});

test("the first press in an app opens the window to pair in, and Office is finished once it is open", async () => {
  const { press, dialogs, requests, storage } = setup({ paired: false });
  assert.equal(await press("saveToHealdan"), 1);
  assert.equal(dialogs.length, 1);
  assert.equal(dialogs[0].address, "https://dms.example.com/api/addin/taskpane.html?message=not-paired&app=Word");
  assert.deepEqual(dialogs[0].options, { width: 30, height: 45, promptBeforeOpen: false });
  assert.deepEqual(requests, [], "an unpaired button asks the helper nothing");
  assert.equal(Healdan.lastMessage(storage).code, "not-paired");
});

test("a button that couldn't do it opens the window with the reason, for the app it was pressed in", async () => {
  const { press, dialogs } = setup({ host: "Excel", answers: { "open-dialog": [409, { error: "signed-out" }] } });
  assert.equal(await press("openFromHealdan"), 1);
  assert.equal(dialogs.length, 1);
  assert.equal(dialogs[0].address, "https://dms.example.com/api/addin/taskpane.html?message=signed-out&app=Excel");
});

test("the app's own box is told in a window too, for the right app", async () => {
  const { press, dialogs } = setup({ host: "PowerPoint", answers: { "save-new": [200, { action: "local" }] } });
  await press("saveToHealdan");
  assert.equal(dialogs[0].address, "https://dms.example.com/api/addin/taskpane.html?message=local-save&app=PowerPoint");
  assert.equal(Healdan.readMessageLink(new URL(dialogs[0].address).search).code, "local-save");
});

test("a filed document is a success, and a notice about it is told", async () => {
  const filed = setup({ host: "PowerPoint", answers: { "save-new": SAVE, file: FILED } });
  assert.equal(await filed.press("saveToHealdan"), 1);
  assert.deepEqual(filed.dialogs, []);

  const notice = setup({ answers: { state: [200, { known: false }] } });
  await notice.press("insertDocId");
  assert.match(notice.dialogs[0].address, /message=not-filed-doc-id&app=Word$/);
});

test("what Office said when it failed goes with the message", async () => {
  const { press, dialogs } = setup({
    answers: { state: [200, { known: true, docRef: "#1 v1.0" }] },
    insertFails: "The footer can't be changed",
  });
  assert.equal(await press("insertDocId"), 1);
  assert.equal(
    dialogs[0].address,
    "https://dms.example.com/api/addin/taskpane.html?message=office-error&app=Word&detail=The+footer+can%27t+be+changed",
  );
});

test("the message is kept for the pane as the last one", async () => {
  const { press, storage, tick } = setup({ answers: { "open-dialog": [409, { error: "signed-out" }] } });
  tick(500);
  await press("openFromHealdan");
  assert.deepEqual(Healdan.lastMessage(storage), { code: "signed-out", app: "Word", at: 1500 });
});

test("Office is told it is finished even when the window won't open", async () => {
  const { press, log, dialogs } = setup({ dialog: "failed", answers: { "open-dialog": [409, { error: "signed-out" }] } });
  assert.equal(await press("openFromHealdan"), 1);
  assert.equal(dialogs.length, 1);
  assert.match(log.join("\n"), /message window didn't open: Another dialog is open/);
});

test("Office is told it is finished when the page itself fails, and the window says so", async () => {
  const { press, dialogs, storage } = setup({ officeFails: "Office.context is gone" });
  assert.equal(await press("saveToHealdan"), 1);
  assert.equal(dialogs.length, 1);
  assert.match(dialogs[0].address, /message=office-error/);
  assert.match(dialogs[0].address, /detail=Office\.context\+is\+gone/);
  assert.equal(Healdan.lastMessage(storage).code, "office-error");
});

test("Office is told it is finished once when asking for the window throws", async () => {
  const { press, log, dialogs } = setup({ dialog: "throws", answers: { "open-dialog": [409, { error: "signed-out" }] } });
  assert.equal(await press("openFromHealdan"), 1);
  assert.equal(dialogs.length, 1);
  assert.match(log.join("\n"), /DialogApi isn't there/);
});

// --- Busy ------------------------------------------------------------------------------------------------------------

test("a flow waiting on the helper is marked busy for the pane, refreshed while it waits, and cleared at the end", async () => {
  let release;
  const waits = new Promise((resolve) => {
    release = resolve;
  });
  const { press, storage, intervals, tick } = setup({ waits, answers: { "open-dialog": [200, { action: "cancel" }] } });

  const pressing = press("openFromHealdan");
  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(Healdan.busyNow(storage, 1000), { flow: "openFromHealdan", label: "Open from Healdan", app: "Word" });
  assert.equal(intervals.length, 1);
  assert.equal(intervals[0].ms, Healdan.BUSY_BEAT);

  // Long after the first note would have gone stale, the heartbeat has kept it fresh.
  tick(60000);
  intervals[0].fn();
  assert.equal(Healdan.busyNow(storage, 61000).flow, "openFromHealdan");

  release();
  assert.equal(await pressing, 1);
  assert.equal(Healdan.busyNow(storage, 61000), null);
  assert.equal(intervals[0].cleared, true);
});

test("a flow that fails clears its busy note too", async () => {
  const { press, storage, intervals } = setup({ answers: { "open-dialog": [409, { error: "signed-out" }] } });
  await press("openFromHealdan");
  assert.equal(Healdan.busyNow(storage, 1000), null);
  assert.equal(intervals[0].cleared, true);
});

// --- On the page -----------------------------------------------------------------------------------------------------

test("on a page, the commands are defined on the global object and with Office once it is ready", async () => {
  const healdanSource = fs.readFileSync(path.join(__dirname, "..", "web", "healdan.js"), "utf8");
  const commandsSource = fs.readFileSync(path.join(__dirname, "..", "web", "commands.js"), "utf8");

  const associated = [];
  let ready = null;
  const page = vm.createContext({
    document: {},
    Office: {
      onReady: (callback) => {
        ready = callback;
      },
      actions: { associate: (name, fn) => associated.push([name, fn]) },
    },
    HealdanOffice: { create: () => ({}) },
    location: { href: "https://dms.example.com/api/addin/commands.html" },
    console,
    setInterval,
    clearInterval,
  });
  vm.runInContext(healdanSource, page);
  vm.runInContext(commandsSource, page);

  for (const name of Commands.FLOWS) {
    assert.equal(typeof page[name], "function", `${name} is on the global object at once`);
  }
  assert.deepEqual(associated, [], "and associated only once Office is ready");
  ready();
  assert.deepEqual(associated.map(([name]) => name), Commands.FLOWS);
  for (const [name, fn] of associated) {
    assert.equal(fn, page[name], name);
  }
});

test("where there is no Office, loading the script wires nothing", () => {
  const commandsSource = fs.readFileSync(path.join(__dirname, "..", "web", "commands.js"), "utf8");
  const page = vm.createContext({});
  assert.doesNotThrow(() => vm.runInContext(commandsSource, page));
  assert.equal(page.saveToHealdan, undefined);
});
