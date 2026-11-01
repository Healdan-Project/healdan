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
 * The commands over fakes. `answers` are the helper's routes; the dialog opens (or doesn't) as `dialog` says, and the
 * user closes it as soon as it is open unless `closes` is false; `waits` is a promise the helper's first keyed route
 * waits for, as a window of its own waiting for the user.
 */
function setup({
  host = "Word",
  paired = true,
  answers = {},
  dialog = "succeeded",
  closes = true,
  waits = null,
  officeFails = null,
  insertFails = null,
} = {}) {
  const storage = memoryStorage(paired ? { [`healdan.key.${host}`]: KEY } : {});
  const requests = [];
  const dialogs = [];
  const log = [];
  const intervals = [];
  const timeouts = [];
  const escaped = []; // what Office's callback threw back at it
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

  const ORIGIN = "https://dms.example.com";
  const Office = {
    AsyncResultStatus: { Succeeded: "succeeded", Failed: "failed" },
    EventType: { DialogMessageReceived: "dialogMessageReceived", DialogEventReceived: "dialogEventReceived" },
    context: {
      ui: {
        displayDialogAsync(address, options, callback) {
          const entry = { address, options, handlers: {}, sent: [], closedByCode: false };
          dialogs.push(entry);
          if (dialog === "throws") {
            throw new Error("DialogApi isn't there");
          }
          if (dialog === "silent") {
            return; // Office never calls back, as in the owner's walkthrough
          }
          if (dialog === "handler-throws") {
            // Office calls back later, as it does, with a dialog whose handlers can't be added.
            const broken = {
              addEventHandler() {
                throw new Error("The dialog went away");
              },
            };
            setImmediate(() => {
              try {
                callback({ status: "succeeded", value: broken });
              } catch (error) {
                escaped.push(error);
              }
            });
            return;
          }
          if (dialog !== "succeeded") {
            callback({ status: "failed", error: { message: "Another dialog is open" } });
            return;
          }
          const handle = {
            addEventHandler(type, handler) {
              (entry.handlers[type] ??= []).push(handler);
            },
            messageChild(text, messageOptions) {
              entry.sent.push({ text, options: messageOptions });
            },
            close() {
              entry.closedByCode = true;
            },
          };
          /** What the window says to the button (messageParent), from this origin unless told. */
          entry.say = (text, origin = ORIGIN) => (entry.handlers.dialogMessageReceived ?? []).forEach((h) => h({ message: text, origin }));
          /** The user closes the window (12006), or Office reports another event. */
          entry.event = (error = 12006) => (entry.handlers.dialogEventReceived ?? []).forEach((h) => h({ error }));
          callback({ status: "succeeded", value: handle });
          if (closes) {
            setImmediate(() => entry.event());
          }
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
    setTimeout: (fn, ms) => {
      timeouts.push({ fn, ms, cleared: false });
      return timeouts.length - 1;
    },
    clearTimeout: (id) => {
      if (id !== null && id !== undefined) {
        timeouts[id].cleared = true;
      }
    },
    console: { warn: (text) => log.push(text) },
  });

  /** Starts a press. `completed()` is how many times Office has been told it is finished; `finished` ends with the press. */
  function begin(name) {
    let completed = 0;
    const finished = commands[name]({ completed: () => (completed += 1) });
    return { finished, completed: () => completed };
  }

  /** Presses a button and waits for it to end, saying how many times Office was told it had finished. */
  async function press(name) {
    const pressing = begin(name);
    await pressing.finished;
    return pressing.completed();
  }

  /** The timers still set, for `ms` if given. */
  const pending = (ms) => timeouts.filter((t) => !t.cleared && (ms === undefined || t.ms === ms));

  return { commands, begin, press, storage, requests, dialogs, log, intervals, timeouts, pending, escaped, tick: (ms) => (clock += ms) };
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
  const { press, dialogs, storage, timeouts, pending } = setup({ answers: { "open-dialog": [200, { action: "opened" }] } });
  assert.equal(await press("openFromHealdan"), 1);
  assert.deepEqual(dialogs, []);
  assert.equal(storage.getItem("healdan.last"), null);
  assert.deepEqual(timeouts.map((t) => t.ms), [Commands.PRESS_LIMIT]);
  assert.deepEqual(pending(), [], "no timer is left behind");
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

test("what Office said when it failed goes to the window in a message, never in its address", async () => {
  const { begin, dialogs, storage } = setup({
    closes: false,
    answers: { state: [200, { known: true, docRef: "#1 v1.0" }] },
    insertFails: "Can't change the footer of Lease agreement - Draft.docx",
  });
  const pressing = begin("insertDocId");
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(dialogs.length, 1);
  assert.equal(dialogs[0].address, "https://dms.example.com/api/addin/taskpane.html?message=office-error&app=Word");
  assert.ok(!/Lease|footer|detail/.test(dialogs[0].address), "nothing Office said is in a page's address, which the server logs");
  assert.deepEqual(dialogs[0].sent, [], "and the window hears nothing until it says it is ready");

  dialogs[0].say(Healdan.windowMessage({ kind: "ready" }));
  assert.equal(dialogs[0].sent.length, 1);
  assert.deepEqual(Healdan.readWindowMessage(dialogs[0].sent[0].text), {
    kind: "detail",
    detail: "Can't change the footer of Lease agreement - Draft.docx",
  });
  assert.equal(dialogs[0].sent[0].options.targetOrigin, "https://dms.example.com");

  // It is kept for the pane, on this Mac only.
  assert.equal(Healdan.lastMessage(storage).detail, "Can't change the footer of Lease agreement - Draft.docx");
  dialogs[0].event();
  await pressing.finished;
});

test("a window with nothing more to say gets nothing when it is ready", async () => {
  const { begin, dialogs } = setup({ closes: false, answers: { "open-dialog": [409, { error: "signed-out" }] } });
  const pressing = begin("openFromHealdan");
  await new Promise((resolve) => setImmediate(resolve));
  dialogs[0].say(Healdan.windowMessage({ kind: "ready" }));
  assert.deepEqual(dialogs[0].sent, []);
  dialogs[0].event();
  await pressing.finished;
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
  assert.doesNotMatch(dialogs[0].address, /detail|gone/);
  assert.equal(Healdan.lastMessage(storage).code, "office-error");
});

test("Office is told it is finished once when asking for the window throws", async () => {
  const { press, log, dialogs } = setup({ dialog: "throws", answers: { "open-dialog": [409, { error: "signed-out" }] } });
  assert.equal(await press("openFromHealdan"), 1);
  assert.equal(dialogs.length, 1);
  assert.match(log.join("\n"), /DialogApi isn't there/);
});

// --- Until the window closes -----------------------------------------------------------------------------------------

test("Office isn't told a button is finished until the window it opened is closed, so the button is there to hear it", async () => {
  const { begin, dialogs } = setup({ closes: false, paired: false });
  const pressing = begin("saveToHealdan");
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(dialogs.length, 1);
  assert.equal(pressing.completed(), 0, "the window is open");

  dialogs[0].event(12006); // the user closed it
  await pressing.finished;
  assert.equal(pressing.completed(), 1);
});

test("any event from the window ends the wait, once: Office reports a closed window, and others that end it", async () => {
  for (const code of [12006, 12002, 12003, 12009, 12011]) {
    const { begin, dialogs } = setup({ closes: false, paired: false });
    const pressing = begin("saveToHealdan");
    await new Promise((resolve) => setImmediate(resolve));
    dialogs[0].event(code);
    dialogs[0].event(12006); // and a second one, as Office may send
    await pressing.finished;
    assert.equal(pressing.completed(), 1, String(code));
  }
});

test("a window whose handlers can't be added ends the press, and once", async () => {
  // Office calls displayDialogAsync's callback later, outside the call's try: a throw there must still end the press.
  const { begin, log, escaped } = setup({ dialog: "handler-throws", closes: false, paired: false });
  const pressing = begin("saveToHealdan");
  await Promise.race([pressing.finished, new Promise((resolve) => setTimeout(resolve, 500))]);
  assert.equal(pressing.completed(), 1);
  assert.deepEqual(escaped, [], "nothing is thrown back at Office");
  assert.match(log.join("\n"), /message window didn't open: The dialog went away/);
});

test("a window that won't open ends the press at once, and once", async () => {
  for (const dialog of ["failed", "throws"]) {
    const { press } = setup({ dialog, closes: false, paired: false });
    assert.equal(await press("saveToHealdan"), 1, dialog);
  }
});

// --- Pairing in the window: the key comes back to the button ------------------------------------------------------------

test("a window Office never answers for ends the press after a while, and once", async () => {
  // The owner's walkthrough (2026-10-31): the first press's window never came, and the press ran on until Office stopped
  // it with an error of its own ("UI less Add-in command timed out").
  const { begin, dialogs, log, pending } = setup({ dialog: "silent", closes: false, paired: false });
  const pressing = begin("saveToHealdan");
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(dialogs.length, 1);
  assert.equal(pressing.completed(), 0, "Office may still answer");

  const [wait] = pending(Commands.WINDOW_WAIT);
  wait.fn();
  await pressing.finished;
  assert.equal(pressing.completed(), 1);
  assert.match(log.at(-1), /didn't answer within 15 seconds/);
  assert.deepEqual(pending(), []);
});

test("a press still going near Office's limit tells Office it is finished, and only once", async () => {
  let release;
  const waits = new Promise((resolve) => {
    release = resolve;
  });
  const { begin, log, pending } = setup({ waits, answers: { "open-dialog": [200, { action: "cancel" }] } });
  const pressing = begin("openFromHealdan");
  await new Promise((resolve) => setImmediate(resolve));

  const [limit] = pending(Commands.PRESS_LIMIT);
  assert.ok(Commands.PRESS_LIMIT < 300000, "before Office's five minutes");
  limit.fn();
  assert.equal(pressing.completed(), 1);
  assert.match(log.at(-1), /openFromHealdan was still running near Office's limit/);

  release(); // the flow ends later, as the user closes the helper's window
  await pressing.finished;
  assert.equal(pressing.completed(), 1);
});

test("the key the window paired with is kept by the button too, as the two may not share storage", async () => {
  const { begin, dialogs, storage } = setup({ closes: false, paired: false });
  const pressing = begin("saveToHealdan");
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(storage.getItem("healdan.key.Word"), null);

  dialogs[0].say(Healdan.windowMessage({ kind: "paired", app: "Word", key: KEY }));
  assert.equal(storage.getItem("healdan.key.Word"), KEY);

  dialogs[0].event();
  await pressing.finished;
  assert.equal(pressing.completed(), 1);
});

test("a key is kept only from the window's own page, for this app, and as the helper makes them", async () => {
  const { begin, dialogs, storage } = setup({ closes: false, paired: false, host: "Excel" });
  const pressing = begin("saveToHealdan");
  await new Promise((resolve) => setImmediate(resolve));

  const paired = (app, key) => Healdan.windowMessage({ kind: "paired", app, key });
  dialogs[0].say(paired("Word", KEY)); // another app's
  dialogs[0].say(paired("Excel", KEY), "https://evil.example"); // from another origin
  dialogs[0].say(paired("Excel", "short"));
  dialogs[0].say("Excel is paired");
  dialogs[0].say('{"healdan":"paired","app":"Excel"}');
  assert.equal(storage.getItem("healdan.key.Excel"), null);
  assert.equal(storage.getItem("healdan.key.Word"), null);

  dialogs[0].say(paired("Excel", KEY));
  assert.equal(storage.getItem("healdan.key.Excel"), KEY);
  dialogs[0].event();
  await pressing.finished;
});

test("a window whose message never comes still ends the press when it is closed", async () => {
  const { press, dialogs } = setup({ paired: false });
  assert.equal(await press("saveToHealdan"), 1);
  assert.deepEqual(dialogs[0].sent, []);
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
    setTimeout,
    clearTimeout,
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
