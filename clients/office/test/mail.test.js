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

function fakeGlue({ item = { internetMessageId: "<own@x>" }, headers = "", eml = "RU1M", categoryFails = false, listed = ["Healdan"], listFails = false, listAddFails = false, platform = "Mac" } = {}) {
  const log = [];
  const glue = {
    log,
    current: item,
    handler: null,
    platform: () => platform,
    item: () => glue.current,
    headers: async () => headers,
    readEml: async () => {
      log.push("readEml");
      return eml;
    },
    masterCategories: async () => {
      log.push("masterCategories");
      if (listFails) {
        throw Object.assign(new Error("no"), { code: 5001 });
      }
      return listed;
    },
    addMasterCategory: async () => {
      log.push("addMasterCategory");
      if (listAddFails) {
        throw Object.assign(new Error("no"), { code: 5002 });
      }
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

// --- The page --------------------------------------------------------------------------------------------------------

test("the page loads Microsoft's script first, then the add-in's, and has nothing inline", () => {
  const scripts = [...HTML.matchAll(/<script src="([^"]+)"/g)].map((match) => match[1]);
  assert.deepEqual(scripts, ["https://appsforoffice.microsoft.com/lib/1/hosted/office.js", "healdan.js", "mail-glue.js", "mail.js"]);
  assert.ok(!/<script(?![^>]*\bsrc=)/.test(HTML), "no inline script: the server's Content-Security-Policy allows none");
  assert.ok(!/\son[a-z]+=/.test(HTML), "no inline handler");
  assert.ok(!/\sstyle=/.test(HTML), "no inline style");
});

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

test("past 100 ids, threadIds leaves out the oldest References and keeps the newest, which the helper asks about", () => {
  const refs = Array.from({ length: 150 }, (_, i) => `<r${i}@x>`).join(" ");
  const ids = Mail.threadIds("own@x", `In-Reply-To: <r149@x>\r\nReferences: ${refs}`);
  assert.equal(ids.length, 100);
  assert.deepEqual(ids.slice(0, 2), ["own@x", "r149@x"]);
  assert.equal(ids[2], "r51@x");
  assert.equal(ids[99], "r148@x");
});

// --- statusView and outcomeView ----------------------------------------------------------------------------------------

test("statusView lists each copy with its address, and a copy in another matter is elsewhere", () => {
  const copy = { docNumber: 12345, name: "Lease", matter: "1.1 Smith", folder: "Documents", address: "https://dms/frontend.jsp?docId=9" };
  assert.deepEqual(Mail.statusView({ filed: true, copies: [copy], elsewhere: false, suggestions: [] }), {
    kind: "filed",
    lines: [{ text: "1.1 Smith › Documents, #12345", before: "1.1 Smith › Documents, ", label: "#12345", address: "https://dms/frontend.jsp?docId=9" }],
    suggestions: [],
  });
  // The helper says elsewhere only with filed (MailFiling.Elsewhere): filed, but not where its thread went.
  const elsewhere = Mail.statusView({ filed: true, copies: [copy], elsewhere: true, suggestions: [{ folderId: 4, path: "A/B" }] });
  assert.equal(elsewhere.kind, "elsewhere");
  assert.equal(elsewhere.lines.length, 1);
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
  assert.deepEqual(Mail.outcomeView({ outcome: "filed", email }), { kind: "ok", text: "Filed as M › F, #7.", categorise: true });
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

async function filedWith(glue) {
  const warn = console.warn;
  const logged = [];
  console.warn = (...args) => logged.push(args.join(" "));
  try {
    const view = await Mail.fileMessage(fakeClient({ "mail/file": { outcome: "filed", email: { docNumber: 1, name: "Secret subject" } } }), glue, 1);
    return { view, logged };
  } finally {
    console.warn = warn;
  }
}

test("a category the mailbox's list lacks is added to the list first, then to the message", async () => {
  const glue = fakeGlue({ listed: ["Blue category"] });
  const { view, logged } = await filedWith(glue);
  assert.equal(view.kind, "ok");
  assert.deepEqual(glue.log.filter((l) => /ategor/.test(l)), ["masterCategories", "addMasterCategory", "addCategory"]);
  assert.deepEqual(logged, []);
});

test("a category the list has already goes straight to the message", async () => {
  const glue = fakeGlue({ listed: ["Healdan"] });
  await filedWith(glue);
  assert.deepEqual(glue.log.filter((l) => /ategor/.test(l)), ["masterCategories", "addCategory"]);
});

test("when the list can't take the category, the message gets none, the filing counts and the code is logged", async () => {
  const glue = fakeGlue({ listed: [], listAddFails: true });
  const { view, logged } = await filedWith(glue);
  assert.equal(view.kind, "ok");
  assert.match(view.text, /^Filed/);
  assert.equal(glue.log.includes("addCategory"), false);
  assert.equal(logged.length, 1);
  assert.match(logged[0], /5002/);
  assert.doesNotMatch(logged[0], /Secret/);
});

test("when the list can't be read, the message gets no category and the filing counts", async () => {
  const glue = fakeGlue({ listFails: true });
  const { view, logged } = await filedWith(glue);
  assert.equal(view.kind, "ok");
  assert.equal(glue.log.includes("addCategory"), false);
  assert.match(logged[0], /5001/);
});

/** What fileMessage shows when the real client gets this answer to mail/file, and whether the message was tagged. */
async function fileAnsweredWith(status, body) {
  const answers = {
    hello: [200, { name: "Healdan Helper", protocols: [1], server: "https://dms" }],
    "mail/file": [status, body],
  };
  const fetch = async (url) => {
    const [code, json] = answers[new URL(url).pathname.replace(/^\/v1\//, "")];
    return { ok: code < 300, status: code, json: async () => json };
  };
  const storage = { getItem: (n) => (n === "healdan.key.Outlook" ? "k".repeat(43) : null), setItem() {}, removeItem() {} };
  const client = Healdan.createHelperClient({ fetch, storage, origin: "https://dms", host: "Outlook" });
  const glue = fakeGlue();
  const view = await Mail.fileMessage(client, glue, 1);
  return { view, tagged: glue.log.includes("addCategory") };
}

test("a message over the server's upload limit says so", async () => {
  const { view, tagged } = await fileAnsweredWith(409, { error: "over-upload-limit", detail: "The email is larger than the 100 MB limit." });
  assert.deepEqual(view, { kind: "problem", text: "This message is larger than Healdan accepts. An administrator can raise the upload limit." });
  assert.equal(tagged, false);
});

test("a message over the helper's own limit doesn't say to raise the upload limit", async () => {
  const { view, tagged } = await fileAnsweredWith(413, { error: "too-large" });
  assert.deepEqual(view, { kind: "problem", text: Healdan.messageFor("too-large") });
  assert.doesNotMatch(view.text, /upload limit/);
  assert.equal(tagged, false);
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
  assert.equal(first.textContent, "M › F, #1");
  assert.equal(first.children.length, 0, "an address that isn't http(s) is no link");
  assert.equal(second.children[0].textContent, "M › F, ");
  assert.equal(second.children[1].textContent, "#2", "only the doc # is the link");
  assert.equal(second.children[1].href, "https://dms/frontend.jsp?docId=2");
  assert.equal(page.getElementById("suggestions").children.length, 0);
});

test("start shows Filed in Another Matter, as the helper answers it, with the copies and the thread's folder to file in", async () => {
  const client = fakeClient({
    "mail/status": { filed: true, elsewhere: true, copies: [{ docNumber: 1, name: "N", matter: "A", folder: "F", address: "" }], suggestions: [{ folderId: 4, path: "B › Correspondence" }] },
  });
  const { page, text } = await open({ client, glue: fakeGlue() });
  assert.equal(text("status"), "Filed in Another Matter");
  assert.equal(page.getElementById("copies").children[0].textContent, "A › F, #1");
  assert.equal(page.getElementById("suggestions").children.length, 1);
  assert.equal(page.getElementById("suggestions").children[0].children[1].textContent, "B › Correspondence");
});

test("a message without a Message-ID is Not Filed once the helper is there, paired and signed in", async () => {
  const ok = fakeClient({ ping: { ok: true, signedIn: true } });
  const shown = await open({ client: ok, glue: fakeGlue({ item: { internetMessageId: "" } }) });
  assert.equal(shown.text("status"), "Not Filed");
  assert.deepEqual(ok.calls.map((c) => c.route), ["ping"]);
  assert.equal(shown.page.getElementById("actions-section").hidden, false);

  const signedOut = await open({ client: fakeClient({ ping: { ok: true, signedIn: false } }), glue: fakeGlue({ item: { internetMessageId: "" } }) });
  assert.equal(signedOut.text("status"), "message for signed-out");
  assert.equal(signedOut.timing.timers.length, 1, "it looks again");

  const unpaired = fakeClient({ ping: { error: "not-paired" } });
  const pairing = await open({ client: unpaired, glue: fakeGlue({ item: { internetMessageId: "" } }) });
  assert.equal(pairing.page.getElementById("pairing").hidden, false);
  assert.ok(unpaired.calls.some((c) => c.route === "pair/start"));
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
  assert.equal(text("outcome"), "Filed as M › F, #9.");
  assert.equal(client.calls.filter((c) => c.route === "mail/status").length, 2);
  await page.getElementById("file-other").fire("click");
  assert.deepEqual(client.calls.filter((c) => c.route === "mail/file")[1].body, { eml: "RU1M", openAt: 5 });
  assert.deepEqual(glue.log.filter((l) => l === "addCategory").length, 2);
});

test("outside the Mac, the pane says it works in the new Outlook for Mac only, and asks the helper nothing", async () => {
  // Outlook on the web and new Outlook for Windows have Mailbox 1.14 too, so the button shows there, with no helper to reach.
  for (const platform of ["OfficeOnline", "PC"]) {
    const client = fakeClient({ "mail/status": { filed: false, copies: [], elsewhere: false, suggestions: [] }, ping: { ok: true } });
    const glue = fakeGlue({ platform });
    let created = 0;
    const timing = clock();
    const page = fakePage();
    await Mail.start({ document: page, Healdan: { HelperError }, glue, createClient: () => (created++, client), ...timing });
    assert.equal(page.getElementById("status").textContent, Mail.MAC_ONLY, platform);
    assert.match(Mail.MAC_ONLY, /new Outlook for Mac only/);
    assert.equal(page.getElementById("status").className, "status problem");
    assert.equal(created, 0, "no helper client is made");
    assert.deepEqual(client.calls, [], "nothing is asked of the helper");
    assert.deepEqual(glue.log, [], "nor of Outlook");
    assert.equal(glue.handler, null);
    assert.equal(page.getElementById("actions-section").hidden, true);
    assert.equal(page.getElementById("pairing").hidden, true);
    assert.equal(timing.timers.length, 0, "and it doesn't look again");
    assert.equal(page.getElementById("main").attributes["aria-busy"], "false");
  }
});

test("in Outlook for Mac the pane goes on as before, and so where Outlook doesn't name its platform", async () => {
  for (const platform of ["Mac", "", undefined]) {
    const client = fakeClient({ "mail/status": { filed: false, copies: [], elsewhere: false, suggestions: [] } });
    const { text } = await open({ client, glue: fakeGlue({ platform }) });
    assert.equal(client.calls[0].route, "mail/status", String(platform));
    assert.equal(text("status"), "Not Filed");
  }
});

test("with no message, the pane says to select one and asks nothing", async () => {
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

// --- Staying on the message --------------------------------------------------------------------------------------------

/** A client whose mail/file waits until `release()` is called. */
function slowClient(statusAnswer, fileAnswer) {
  const client = fakeClient({ "mail/status": statusAnswer });
  let release;
  const gate = new Promise((resolve) => (release = resolve));
  const base = client.call.bind(client);
  client.call = async (route, body) => {
    if (route === "mail/file") {
      client.calls.push({ route, body });
      await gate;
      return fileAnswer;
    }
    return base(route, body);
  };
  client.release = release;
  return client;
}

const SUGGESTED = { filed: false, copies: [], elsewhere: false, suggestions: [{ folderId: 5, path: "P" }] };

test("a message change clears the buttons at once, and an old button files nothing", async () => {
  const client = fakeClient({ "mail/status": SUGGESTED });
  const glue = fakeGlue({ item: { itemId: "A", internetMessageId: "<a@x>" } });
  const { page, text } = await open({ client, glue });
  const oldButton = page.getElementById("suggestions").children[0];
  // B opens, and its status has not landed.
  let land;
  client.call = async (route, body) => {
    client.calls.push({ route, body });
    await new Promise((resolve) => (land = resolve));
    return SUGGESTED;
  };
  glue.current = { itemId: "B", internetMessageId: "<b@x>" };
  glue.handler();
  assert.equal(page.getElementById("actions-section").hidden, true);
  assert.equal(page.getElementById("suggestions").children.length, 0);
  assert.equal(text("status"), "Checking Healdan…");
  const before = client.calls.length;
  await oldButton.fire("click");
  assert.equal(client.calls.slice(before).some((c) => c.route === "mail/file"), false, "nothing is sent");
  assert.equal(glue.log.includes("readEml"), false);
  assert.equal(text("outcome"), "This message changed. Choose again.");
  land();
});

test("a message that changed under fileMessage is refused, and the .eml comes from the chosen item", async () => {
  const a = { itemId: "A" };
  const glue = fakeGlue({ item: { itemId: "B" } });
  const client = fakeClient({ "mail/file": { outcome: "filed" } });
  const view = await Mail.fileMessage(client, glue, 1, undefined, a);
  assert.equal(view.text, "This message changed. Choose again.");
  assert.equal(client.calls.length, 0);
});

test("when the open message moves on during a filing, there is no category and the outcome says so", async () => {
  for (const [outcome, start] of [["filed", "Filed as M › F, #4."], ["already-filed", "Already Filed as M › F, #4."]]) {
    const email = { docNumber: 4, name: "N", matter: "M", folder: "F" };
    const client = slowClient(SUGGESTED, { outcome, email });
    const glue = fakeGlue({ item: { itemId: "A", internetMessageId: "<a@x>" } });
    const { page } = await open({ client, glue });
    const filing = page.getElementById("suggestions").children[0].fire("click");
    await settle();
    glue.current = { itemId: "B", internetMessageId: "<b@x>" };
    client.release();
    await filing;
    assert.equal(glue.log.includes("addCategory"), false);
    // The outcome is A's, shown on B in words that say so, as nothing else tells the person A has no category.
    const moved = `The message you chose before this one: ${start} It has no Healdan category, as another message was open by then.`;
    assert.equal(page.getElementById("outcome").hidden, false);
    assert.equal(page.getElementById("outcome").textContent, moved);

    // The same through fileMessage: the open message is A when the filing starts and B when it ends.
    const opened = [{ itemId: "A" }]; // the check at the start sees A, the one after the filing sees B
    const moving = fakeGlue();
    moving.item = () => opened.shift() ?? { itemId: "B" };
    const view = await Mail.fileMessage(fakeClient({ "mail/file": { outcome, email } }), moving, 1, undefined, { itemId: "A" });
    assert.equal(view.text, moved);
    assert.equal(view.moved, true);
    assert.equal(moving.log.includes("addCategory"), false);
  }
});

test("after A, B and A again during a filing, the category goes on the A open now, not on the one the click took", async () => {
  // Office.js gives mailbox.item a new object at each ItemChanged: the one taken at the click may be stale by the end.
  const clicked = { itemId: "A", internetMessageId: "<a@x>" };
  const reopened = { itemId: "A", internetMessageId: "<a@x>" };
  const glue = fakeGlue({ item: clicked });
  const tagged = [];
  glue.addCategory = async (item) => {
    tagged.push(item);
  };
  const client = fakeClient({
    "mail/file": () => {
      glue.current = reopened; // the person went to B and back to A while the helper filed
      return { outcome: "filed", email: { docNumber: 1 } };
    },
  });
  const view = await Mail.fileMessage(client, glue, 1, undefined, clicked);
  assert.equal(view.kind, "ok");
  assert.equal(view.moved, undefined);
  assert.equal(tagged.length, 1);
  assert.equal(tagged[0], reopened);
});

test("while a filing runs, redrawn suggestions are disabled, and a throw doesn't leave the pane stuck", async () => {
  const client = slowClient(SUGGESTED, { outcome: "cancelled" });
  const glue = fakeGlue({ item: { internetMessageId: "<own@x>" } });
  const { page, pane } = await open({ client, glue });
  const filing = page.getElementById("suggestions").children[0].fire("click");
  await settle();
  await pane.refresh();
  assert.equal(page.getElementById("suggestions").children[0].disabled, true);
  client.release();
  await filing;
  // A throw out of the glue (not a rejection fileMessage knows) frees the pane.
  glue.readEml = () => {
    throw new Error("boom");
  };
  await page.getElementById("suggestions").children[0].fire("click");
  await page.getElementById("file-other").fire("click");
  assert.equal(page.getElementById("file-other").disabled, false);
});
