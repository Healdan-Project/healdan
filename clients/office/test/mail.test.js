// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// The Outlook add-in's pane (clients/office/web/mail.js and mail.html), run with Node's built-in runner, a fake page, a
// fake helper client and a fake glue.

"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const Healdan = require("../web/healdan.js");
const Mail = require("../web/mail.js");

const HTML = fs.readFileSync(path.join(__dirname, "..", "web", "mail.html"), "utf8");

// --- Fakes -----------------------------------------------------------------------------------------------------------

function element(id) {
  const listeners = {};
  return {
    id,
    hidden: false,
    disabled: false,
    className: "",
    value: "",
    children: [],
    attributes: {},
    textContent: "",
    setAttribute(name, value) {
      this.attributes[name] = String(value);
    },
    addEventListener(type, listener) {
      (listeners[type] ??= []).push(listener);
    },
    fire(type, event = { preventDefault() {} }) {
      return Promise.all((listeners[type] ?? []).map((listener) => listener(event)));
    },
    appendChild(child) {
      this.children.push(child);
    },
    replaceChildren() {
      this.children = [];
    },
    focus() {},
    select() {},
  };
}

function fakePage() {
  const elements = new Map();
  for (const [, attributes] of HTML.matchAll(/<[a-z0-9]+\s([^>]*\bid="[^"]+"[^>]*)>/g)) {
    const id = /\bid="([^"]+)"/.exec(attributes)[1];
    const el = element(id);
    el.hidden = /\bhidden\b/.test(attributes.replace(/"[^"]*"/g, '""'));
    elements.set(id, el);
  }
  return {
    elements,
    getElementById(id) {
      if (!elements.has(id)) {
        throw new Error(`mail.html has no element with the id ${id}`);
      }
      return elements.get(id);
    },
    createElement: (tag) => ({ ...element(null), tag }),
  };
}

class HelperError extends Error {
  constructor(code, message) {
    super(message ?? `message for ${code}`);
    this.code = code;
  }
}

/** A client whose routes answer a value, or throw a HelperError for { error: code }; every call is logged. */
function fakeClient(routes = {}) {
  const calls = [];
  return {
    calls,
    paired: () => true,
    async call(route, body) {
      calls.push({ route, body });
      const answer = typeof routes[route] === "function" ? routes[route](body) : routes[route];
      if (answer && answer.error) {
        throw new HelperError(answer.error);
      }
      return answer;
    },
    async pairStart() {
      calls.push({ route: "pair/start" });
      return "p-1";
    },
    async pairFinish(id, code) {
      calls.push({ route: "pair/finish", body: { id, code } });
    },
  };
}

function fakeGlue({ item = { internetMessageId: "<own@x>" }, headers = "", eml = "RU1M", categoryFails = false } = {}) {
  const log = [];
  const glue = {
    log,
    current: item,
    handler: null,
    item: () => glue.current,
    headers: async () => headers,
    readEml: async () => {
      log.push("readEml");
      return eml;
    },
    addCategory: async () => {
      log.push("addCategory");
      if (categoryFails) {
        throw Object.assign(new Error("no"), { code: 5000 });
      }
    },
    onItemChanged: async (fn) => {
      glue.handler = fn;
    },
  };
  return glue;
}

function clock() {
  const timers = [];
  return {
    timers,
    setTimeout: (fn, ms) => {
      timers.push({ fn, ms });
      return timers.length;
    },
    clearTimeout: () => {},
  };
}

async function open({ client, glue, page = fakePage() } = {}) {
  const timing = clock();
  const pane = await Mail.start({
    document: page,
    Healdan: { HelperError },
    glue,
    createClient: () => client,
    ...timing,
  });
  return { pane, page, timing, text: (id) => page.getElementById(id).textContent };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

// --- threadIds -------------------------------------------------------------------------------------------------------

test("threadIds puts the message's own id first, then In-Reply-To, then References, unfolded and without repeats", () => {
  const headers = [
    "Subject: Re: lease",
    "References: <a@x>",
    "\t<b@x>   <own@x>",
    " <c@x>",
    "in-reply-to: <c@x>",
    "Message-ID: <own@x>",
  ].join("\r\n");
  assert.deepEqual(Mail.threadIds("<own@x>", headers), ["own@x", "c@x", "a@x", "b@x"]);
});

test("threadIds works without headers, and without an id of its own", () => {
  assert.deepEqual(Mail.threadIds("own@x", ""), ["own@x"]);
  assert.deepEqual(Mail.threadIds("own@x", undefined), ["own@x"]);
  assert.deepEqual(Mail.threadIds("", "References: <a@x>"), []);
  assert.deepEqual(Mail.threadIds(undefined, "References: <a@x>"), []);
});

test("threadIds sends at most 100 ids", () => {
  const refs = Array.from({ length: 150 }, (_, i) => `<r${i}@x>`).join(" ");
  assert.equal(Mail.threadIds("own@x", `References: ${refs}`).length, 100);
});

// --- statusView and outcomeView ----------------------------------------------------------------------------------------

test("statusView lists each copy with its address, and a copy in another matter is elsewhere", () => {
  const copy = { docNumber: 12345, name: "Lease", matter: "1.1 Smith", folder: "Documents", address: "https://dms/frontend.jsp?docId=9" };
  assert.deepEqual(Mail.statusView({ filed: true, copies: [copy], elsewhere: false, suggestions: [] }), {
    kind: "filed",
    lines: [{ text: "#12345 Lease in 1.1 Smith / Documents", address: "https://dms/frontend.jsp?docId=9" }],
    suggestions: [],
  });
  const elsewhere = Mail.statusView({ filed: false, copies: [], elsewhere: true, suggestions: [{ folderId: 4, path: "A/B" }] });
  assert.equal(elsewhere.kind, "elsewhere");
  assert.deepEqual(elsewhere.suggestions, [{ folderId: 4, label: "A/B" }]);
  assert.equal(Mail.statusView({ filed: false, copies: [], elsewhere: false, suggestions: [] }).kind, "not-filed");
});

test("statusView keeps five suggestions and drops malformed ones", () => {
  const suggestions = Array.from({ length: 8 }, (_, i) => ({ folderId: i + 1, path: `P${i}` }));
  suggestions.unshift({ folderId: "x", path: 1 });
  assert.equal(Mail.statusView({ suggestions }).suggestions.length, 5);
});

test("outcomeView says what happened, and only a filing is categorised", () => {
  const email = { docNumber: 7, name: "N", matter: "M", folder: "F" };
  assert.deepEqual(Mail.outcomeView({ outcome: "filed", email }), { kind: "ok", text: "Filed as #7 N in M / F.", categorise: true });
  assert.equal(Mail.outcomeView({ outcome: "already-filed", email }).categorise, true);
  assert.equal(Mail.outcomeView({ outcome: "cancelled" }).categorise, false);
  assert.equal(Mail.outcomeView({ outcome: "cancelled" }).kind, "ok");
  assert.equal(Mail.outcomeView({ nonsense: 1 }).kind, "problem");
});

// --- fileMessage -------------------------------------------------------------------------------------------------------

test("File Here sends the .eml and the folder; File... sends openAt instead", async () => {
  const client = fakeClient({ "mail/file": { outcome: "filed", email: { docNumber: 1 } } });
  const glue = fakeGlue();
  await Mail.fileMessage(client, glue, 42);
  await Mail.fileMessage(client, glue, undefined, 7);
  await Mail.fileMessage(client, glue, undefined);
  assert.deepEqual(client.calls.map((c) => c.body), [{ eml: "RU1M", folderId: 42 }, { eml: "RU1M", openAt: 7 }, { eml: "RU1M" }]);
});

test("a 202 is followed through result by the real client", async () => {
  const seen = [];
  const answers = {
    hello: [200, { name: "Healdan Helper", protocols: [1], server: "https://dms" }],
    "mail/file": [202, { pending: "r-1" }],
    result: [200, { outcome: "filed", email: { docNumber: 3 } }],
  };
  const fetch = async (url, init = {}) => {
    const route = new URL(url).pathname.replace(/^\/v1\//, "");
    seen.push(route);
    const [status, body] = answers[route];
    return { ok: status < 300, status, json: async () => body };
  };
  const storage = { getItem: (n) => (n === "healdan.key.Outlook" ? "k".repeat(43) : null), setItem() {}, removeItem() {} };
  const client = Healdan.createHelperClient({ fetch, storage, origin: "https://dms", host: "Outlook" });
  const view = await Mail.fileMessage(client, fakeGlue(), 1);
  assert.equal(view.kind, "ok");
  assert.ok(seen.includes("result"));
});

test("the category is added after filed and already-filed, never after cancelled or a failure", async () => {
  for (const [answer, added] of [
    [{ outcome: "filed" }, true],
    [{ outcome: "already-filed" }, true],
    [{ outcome: "cancelled" }, false],
    [{ error: "internal-error" }, false],
    [{ error: "signed-out" }, false],
  ]) {
    const glue = fakeGlue();
    await Mail.fileMessage(fakeClient({ "mail/file": answer }), glue, 1);
    assert.equal(glue.log.includes("addCategory"), added, JSON.stringify(answer));
  }
});

test("a category that can't be added leaves the filing filed", async () => {
  const warn = console.warn;
  const logged = [];
  console.warn = (...args) => logged.push(args.join(" "));
  try {
    const view = await Mail.fileMessage(fakeClient({ "mail/file": { outcome: "filed", email: { docNumber: 1, name: "Secret subject" } } }), fakeGlue({ categoryFails: true }), 1);
    assert.equal(view.kind, "ok");
    assert.match(view.text, /^Filed/);
  } finally {
    console.warn = warn;
  }
  assert.equal(logged.length, 1);
  assert.doesNotMatch(logged[0], /Secret/);
});

test("a message Outlook won't give, or an outage, is a problem and not filed", async () => {
  const glue = fakeGlue();
  glue.readEml = async () => {
    throw new Error("x");
  };
  const view = await Mail.fileMessage(fakeClient({ "mail/file": { outcome: "filed" } }), glue, 1);
  assert.equal(view.kind, "problem");
  const outage = await Mail.fileMessage(fakeClient({ "mail/file": { error: "internal-error" } }), fakeGlue(), 1);
  assert.equal(outage.kind, "problem");
  assert.match(outage.text, /^Can't Reach Healdan/);
});

// --- start -------------------------------------------------------------------------------------------------------------

test("start asks mail/status with the thread's ids and shows Not Filed with its suggestions", async () => {
  const client = fakeClient({ "mail/status": { filed: false, copies: [], elsewhere: false, suggestions: [{ folderId: 5, path: "Smith/Documents" }] } });
  const { page, text } = await open({ client, glue: fakeGlue({ headers: "In-Reply-To: <p@x>" }) });
  assert.deepEqual(client.calls[0], { route: "mail/status", body: { messageIds: ["own@x", "p@x"] } });
  assert.equal(text("status"), "Not Filed");
  assert.equal(page.getElementById("actions-section").hidden, false);
  assert.equal(page.getElementById("suggestions").children.length, 1);
});

test("start shows Filed with a link, and the helper's text only as text", async () => {
  const evil = "<img src=x onerror=alert(1)>";
  const client = fakeClient({
    "mail/status": { filed: true, elsewhere: false, suggestions: [], copies: [{ docNumber: 1, name: evil, matter: "M", folder: "F", address: "javascript:alert(1)" }, { docNumber: 2, name: "B", matter: "M", folder: "F", address: "https://dms/frontend.jsp?docId=2" }] },
  });
  const { page, text } = await open({ client, glue: fakeGlue() });
  assert.equal(text("status"), "Filed");
  const [first, second] = page.getElementById("copies").children;
  assert.equal(first.textContent, `#1 ${evil} in M / F`);
  assert.equal(first.children.length, 0, "an address that isn't http(s) is no link");
  assert.equal(second.children[0].href, "https://dms/frontend.jsp?docId=2");
  assert.equal(page.getElementById("suggestions").children.length, 0);
});

test("File Here and File... file, show the outcome, and check the status again", async () => {
  const client = fakeClient({
    "mail/status": { filed: false, copies: [], elsewhere: false, suggestions: [{ folderId: 5, path: "P" }] },
    "mail/file": { outcome: "filed", email: { docNumber: 9, name: "N", matter: "M", folder: "F" } },
  });
  const glue = fakeGlue();
  const { page, text } = await open({ client, glue });
  await page.getElementById("suggestions").children[0].fire("click");
  assert.deepEqual(client.calls.find((c) => c.route === "mail/file").body, { eml: "RU1M", folderId: 5 });
  assert.equal(text("outcome"), "Filed as #9 N in M / F.");
  assert.equal(client.calls.filter((c) => c.route === "mail/status").length, 2);
  await page.getElementById("file-other").fire("click");
  assert.deepEqual(client.calls.filter((c) => c.route === "mail/file")[1].body, { eml: "RU1M", openAt: 5 });
  assert.deepEqual(glue.log.filter((l) => l === "addCategory").length, 2);
});

test("with no message, or several selected, the pane says to select one and asks nothing", async () => {
  const client = fakeClient();
  const glue = fakeGlue({ item: null });
  const { text, page } = await open({ client, glue });
  assert.equal(text("status"), "Select one message to file it.");
  assert.equal(client.calls.length, 0);
  assert.equal(page.getElementById("actions-section").hidden, true);
});

test("an ItemChanged checks the new message, the latest winning", async () => {
  const client = fakeClient({ "mail/status": (body) => ({ filed: body.messageIds[0] === "two@x", copies: [], elsewhere: false, suggestions: [] }) });
  const glue = fakeGlue();
  const { text } = await open({ client, glue });
  assert.equal(text("status"), "Not Filed");
  glue.current = { internetMessageId: "<two@x>" };
  glue.handler();
  await settle();
  await settle();
  assert.equal(text("status"), "Filed");
});

test("the helper not running, signed out or not paired show the words the pane has", async () => {
  for (const code of ["unreachable", "signed-out"]) {
    const { text, page, timing } = await open({ client: fakeClient({ "mail/status": { error: code } }), glue: fakeGlue() });
    assert.equal(text("status"), `message for ${code}`);
    assert.equal(page.getElementById("actions-section").hidden, true);
    assert.equal(timing.timers.length, 1, "it looks again");
  }
  const client = fakeClient({ "mail/status": { error: "not-paired" } });
  const { page } = await open({ client, glue: fakeGlue() });
  assert.equal(page.getElementById("pairing").hidden, false);
  assert.ok(client.calls.some((c) => c.route === "pair/start"));
  page.getElementById("code").value = "123456";
  await page.getElementById("pairing").fire("submit");
  assert.ok(client.calls.some((c) => c.route === "pair/finish" && c.body.code === "123456"));
});

test("mail/status answering internal-error says Can't Reach Healdan and keeps File...", async () => {
  const { text, page } = await open({ client: fakeClient({ "mail/status": { error: "internal-error" } }), glue: fakeGlue() });
  assert.match(text("status"), /^Can't Reach Healdan/);
  assert.equal(page.getElementById("actions-section").hidden, false);
  assert.equal(page.getElementById("suggestions").children.length, 0);
});
