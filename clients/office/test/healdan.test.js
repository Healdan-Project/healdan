// The Office add-in's logic (M3.5 spec §6), run with Node's built-in runner and nothing installed:
//
//   node --test 'clients/office/test/*.test.js'
//
// The helper and Office are fakes. FakeHelper answers like the helper's port (AddinServer, AddinHandler); the office
// object stands in for the Office.js glue that Task 10 writes. Both write into one log, so a test can say in what order
// the add-in did things.

"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const Healdan = require("../web/healdan.js");

const SERVER = "https://dms.example.com";
const KEY = "k".repeat(43);

// --- Fakes -----------------------------------------------------------------------------------------------------------

/** A Web Storage (what localStorage is) backed by a Map. */
function memoryStorage(initial = {}) {
  const map = new Map(Object.entries(initial));
  return {
    map,
    getItem: (name) => (map.has(name) ? map.get(name) : null),
    setItem: (name, value) => {
      map.set(name, String(value));
    },
    removeItem: (name) => {
      map.delete(name);
    },
  };
}

function hello(server = SERVER, protocols = [1]) {
  return [200, { name: "Healdan Helper", protocols, server }];
}

/**
 * The helper's port and a fetch that reaches it. Ports not in `listening` refuse the connection, as a closed port does:
 * fetch rejects with a TypeError, as WebKit's does ("Load failed"). A route answers [status, body], or a function of the
 * request does (which may throw); a body of undefined is an answer that isn't JSON.
 */
function fakeHelper({ listening = [47615], routes = {}, hanging = [], log = [] } = {}) {
  const requests = [];
  const table = { hello: () => hello(), ...routes };
  async function fetch(url, init = {}) {
    const parsed = new URL(url);
    const port = Number(parsed.port);
    const request = {
      url,
      port,
      method: init.method,
      route: parsed.pathname.replace(/^\/v1\//, ""),
      headers: init.headers ?? {},
      body: init.body === undefined ? undefined : JSON.parse(init.body),
    };
    requests.push(request);
    if (hanging.includes(port)) {
      return new Promise((resolve, reject) => init.signal.addEventListener("abort", () => reject(new Error("aborted"))));
    }
    if (!listening.includes(port)) {
      throw new TypeError("Load failed");
    }
    if (request.route !== "hello") {
      log.push(request.route);
    }
    const answer = table[request.route] ?? [404, { error: "not-found" }];
    const [status, body] = typeof answer === "function" ? answer(request) : answer;
    return {
      ok: status >= 200 && status < 300,
      status,
      json: async () => {
        if (body === undefined) {
          throw new SyntaxError("Unexpected end of JSON input");
        }
        return body;
      },
    };
  }
  return {
    fetch,
    routes: table,
    requests,
    /** What the add-in asked of routes other than hello, in order. */
    calls: () => requests.filter((r) => r.route !== "hello" && r.port === 47615),
    helloPorts: () => requests.filter((r) => r.route === "hello").map((r) => r.port),
  };
}

/** The Office.js glue as Task 10 will give it, in a fake that logs what it's asked and can be made to fail. */
function fakeOffice({ host = "Word", url = "", bytes = "UEsDBA==", failures = {}, log = [], onTheWeb = false } = {}) {
  const marks = [];
  async function step(name, detail) {
    log.push(detail === undefined ? name : `${name} ${detail}`);
    if (failures[name]) {
      throw failures[name];
    }
  }
  return {
    host,
    onTheWeb,
    marks,
    url: () => url,
    readBytes: async () => {
      await step("readBytes");
      return bytes;
    },
    setMark: async (id) => {
      marks.push(id);
      await step("setMark", id);
    },
    removeMark: async (id) => {
      await step("removeMark", id);
    },
    insertDocId: async (docRef) => {
      await step("insertDocId", JSON.stringify(docRef));
    },
  };
}

/** A client, its helper, its storage and an office, all sharing one log. Paired for `host`, unless `paired` is false. */
function setup({ host = "Word", paired = true, listening, routes, hanging, officeOptions = {}, clientOptions = {} } = {}) {
  const log = [];
  const helper = fakeHelper({ listening, routes, hanging, log });
  const storage = memoryStorage(paired ? { [`healdan.key.${host}`]: KEY } : {});
  const client = Healdan.createHelperClient({ fetch: helper.fetch, storage, origin: SERVER, host, ...clientOptions });
  const office = fakeOffice({ host, log, ...officeOptions });
  return { log, helper, storage, client, office };
}

/** A route that answers each of `answers` in turn, and the last one from then on. */
function inTurn(...answers) {
  let next = 0;
  return () => answers[Math.min(next++, answers.length - 1)];
}

/** The helper's answer for a route still running after its hold time. */
function pending(id = "id-1") {
  return [202, { pending: id }];
}

const SAVE = [200, { action: "save", folderId: 12, name: "Lease agreement", format: "docx", properties: {}, ticket: "ticket-1" }];
const FILED = [200, { known: true, docNumber: 1234, docRef: "#1234 v1.0", readOnly: false }];
const IN_HEALDAN = [200, { known: true, docNumber: 1234, docRef: "#1234 v1.2", readOnly: false }];
const NOT_IN_HEALDAN = [200, { known: false }];

const FLOWS = [
  "saveToHealdan",
  "saveAsNewDocument",
  "saveAsNewVersion",
  "properties",
  "openFromHealdan",
  "insertDocId",
];

/** Rejects with a HelperError having this code. */
async function failsWith(promise, code) {
  await assert.rejects(promise, (error) => {
    assert.ok(error instanceof Healdan.HelperError, `${error} should be a HelperError`);
    assert.equal(error.code, code);
    assert.equal(error.message, Healdan.messageFor(code, error.details), "the error's message is the pane's text for its code");
    return true;
  });
}

// --- The script ------------------------------------------------------------------------------------------------------

test("it loads as a classic script, with Healdan on the page's global", () => {
  const source = fs.readFileSync(path.join(__dirname, "..", "web", "healdan.js"), "utf8");
  const page = vm.createContext({});
  vm.runInContext(source, page);
  assert.equal(typeof page.Healdan.createHelperClient, "function");
  assert.equal(typeof page.Healdan.saveToHealdan, "function");
  assert.equal(typeof page.Healdan.messageFor, "function");
});

test("the ports are the helper's, in order, and the server's policy allows them", () => {
  const root = path.join(__dirname, "..", "..", "..");
  const helperPorts = fs.readFileSync(path.join(root, "clients/helper/Healdan.Helper.Core/Addin/AddinPorts.cs"), "utf8");
  const listed = /All\s*\{\s*get;\s*\}\s*=\s*\[([^\]]*)\]/.exec(helperPorts);
  assert.ok(listed, "AddinPorts.All should be found in AddinPorts.cs");
  assert.deepEqual(Healdan.PORTS, listed[1].split(",").map((port) => Number(port.trim())));
  assert.deepEqual(Healdan.PORTS, [47615, 47616, 47617, 47618]);

  const policy = fs.readFileSync(path.join(root, "healdan-api/src/main/java/com/logicaldoc/healdan/api/AddinResource.java"), "utf8");
  for (const port of Healdan.PORTS) {
    assert.ok(policy.includes(`https://127.0.0.1:${port}`), `the Content-Security-Policy should allow port ${port}`);
  }
});

// --- Finding the helper ----------------------------------------------------------------------------------------------

test("find tries each port in order and remembers the one that answers", async () => {
  const { client, helper, storage } = setup({ paired: false, listening: [47617, 47618] });
  assert.equal(await client.find(), 47617);
  assert.deepEqual(helper.helloPorts(), [47615, 47616, 47617], "47618 isn't probed once 47617 answered");
  assert.equal(storage.getItem("healdan.port"), "47617");
  const hello = helper.requests.at(-1);
  assert.equal(hello.method, "GET");
  assert.equal(hello.url, "https://127.0.0.1:47617/v1/hello");
  assert.equal(hello.headers["X-Healdan-Key"], undefined, "hello is asked without a key");
  assert.equal(hello.headers["X-Healdan-Protocol"], undefined);
});

test("find passes by a port where another Healdan's helper, or something else, answers", async () => {
  const routes = {
    hello: ({ port }) => {
      switch (port) {
        case 47615:
          return hello("https://other.example.com"); // another server's helper, as another user of the Mac has
        case 47616:
          return [200, { name: "Something else" }];
        case 47617:
          return [403, { error: "bad-origin" }];
        default:
          return hello("https://DMS.example.com"); // the same server, in another case
      }
    },
  };
  const { client, helper } = setup({ paired: false, listening: Healdan.PORTS, routes });
  assert.equal(await client.find(), 47618);
  assert.deepEqual(helper.helloPorts(), [47615, 47616, 47617, 47618]);
});

test("find says the helper isn't running when no port answers, or its server isn't this one", async () => {
  const none = setup({ paired: false, listening: [] });
  none.storage.setItem("healdan.port", "47616");
  await failsWith(none.client.find(), "unreachable");
  assert.equal(none.storage.getItem("healdan.port"), null, "a port that no longer answers is forgotten");
  assert.deepEqual(none.helper.helloPorts(), Healdan.PORTS);

  for (const server of [null, "https://other.example.com"]) {
    const { client } = setup({ paired: false, listening: Healdan.PORTS, routes: { hello: () => hello(server) } });
    await failsWith(client.find(), "unreachable");
  }
});

test("find gives up on a port that never answers, and goes on to the next", async () => {
  const { client } = setup({ paired: false, listening: [47616], hanging: [47615], clientOptions: { probeTimeout: 20 } });
  assert.equal(await client.find(), 47616);
});

test("find says to update the helper when it speaks no protocol the add-in does", async () => {
  const { client } = setup({ paired: false, routes: { hello: () => hello(SERVER, [2]) } });
  await failsWith(client.find(), "update-healdan-helper");
});

// --- Calls -----------------------------------------------------------------------------------------------------------

test("every call carries the protocol and the key", async () => {
  const { client, helper } = setup({ routes: { ping: [200, { ok: true, signedIn: true }], state: NOT_IN_HEALDAN } });
  assert.deepEqual(await client.call("ping", {}), { ok: true, signedIn: true });
  const [ping] = helper.calls();
  assert.equal(ping.method, "POST");
  assert.equal(ping.url, "https://127.0.0.1:47615/v1/ping");
  assert.equal(ping.headers["X-Healdan-Protocol"], "1");
  assert.equal(ping.headers["X-Healdan-Key"], KEY);
  assert.equal(ping.headers["Content-Type"], "application/json");
  assert.deepEqual(ping.body, {});

  await client.call("state", { app: "Word", url: "/tmp/a b.docx" });
  assert.deepEqual(helper.calls()[1].body, { app: "Word", url: "/tmp/a b.docx" });
  assert.equal(helper.calls()[1].headers["X-Healdan-Key"], KEY);
});

test("pairing's calls carry the protocol, and no key", async () => {
  const { client, helper } = setup({ routes: { "pair/start": [200, { pairingId: "p-1" }] } });
  await client.pairStart();
  const [start] = helper.calls();
  assert.equal(start.headers["X-Healdan-Protocol"], "1");
  assert.equal(start.headers["X-Healdan-Key"], undefined, "even when an old key is stored");
});

test("a call with no key stored asks to pair, without asking the helper", async () => {
  const { client, helper } = setup({ paired: false });
  await failsWith(client.call("ping", {}), "not-paired");
  assert.equal(helper.requests.length, 0);
});

test("a call uses the port it remembers, without probing", async () => {
  const { client, helper, storage } = setup({ listening: [47615, 47616], routes: { ping: [200, { ok: true }] } });
  storage.setItem("healdan.port", "47616");
  await client.call("ping", {});
  assert.deepEqual(helper.requests.map((r) => `${r.route}@${r.port}`), ["ping@47616"]);
});

test("a call looks for the helper again when the port it remembers stops answering", async () => {
  const { client, helper, storage } = setup({ listening: [47617], routes: { ping: [200, { ok: true }] } });
  storage.setItem("healdan.port", "47615");
  assert.deepEqual(await client.call("ping", {}), { ok: true });
  assert.deepEqual(helper.requests.map((r) => `${r.route}@${r.port}`), [
    "ping@47615",
    "hello@47615",
    "hello@47616",
    "hello@47617",
    "ping@47617",
  ]);
  assert.equal(storage.getItem("healdan.port"), "47617");
});

test("a call says the helper isn't running when none answers any more", async () => {
  const { client, storage } = setup({ listening: [] });
  storage.setItem("healdan.port", "47615");
  await failsWith(client.call("ping", {}), "unreachable");
  assert.equal(storage.getItem("healdan.port"), null);
});

test("a call isn't repeated when the helper still answers but the call failed on the way", async () => {
  let asked = 0;
  const routes = {
    ping: () => {
      asked += 1;
      throw new TypeError("Load failed");
    },
  };
  const { client } = setup({ routes });
  await failsWith(client.call("ping", {}), "unreachable");
  assert.equal(asked, 1, "a request the helper may have taken isn't sent twice");
});

test("an error answer becomes a HelperError with the helper's code and the pane's text", async () => {
  const cases = [
    [409, { error: "signed-out" }, "signed-out"],
    [426, { error: "update-healdan-helper" }, "update-healdan-helper"],
    [409, { error: "not-a-copy" }, "not-a-copy"],
    [413, { error: "too-large" }, "too-large"],
    [500, { error: "internal-error" }, "internal-error"],
    [500, undefined, "internal-error"], // not JSON at all
    [403, { error: 7 }, "internal-error"],
  ];
  for (const [status, body, code] of cases) {
    const { client } = setup({ routes: { ping: [status, body] } });
    await assert.rejects(client.call("ping", {}), (error) => {
      assert.ok(error instanceof Healdan.HelperError);
      assert.equal(error.code, code);
      assert.equal(error.status, status);
      assert.equal(error.message, Healdan.messageFor(code));
      return true;
    });
  }
});

// --- Pending answers: a route still running after the helper's hold time answers 202, and result answers it later ----

test("call follows a pending answer to its outcome", async () => {
  const routes = { "save-new": pending(), result: inTurn(pending(), SAVE) };
  const { client, helper } = setup({ routes });

  assert.deepEqual(await client.call("save-new", { app: "Word", url: "", asNew: false }), SAVE[1]);

  const calls = helper.calls();
  assert.deepEqual(calls.map((c) => c.route), ["save-new", "result", "result"]);
  for (const result of calls.slice(1)) {
    assert.equal(result.method, "POST");
    assert.deepEqual(result.body, { id: "id-1" });
    assert.equal(result.headers["X-Healdan-Key"], KEY);
    assert.equal(result.headers["X-Healdan-Protocol"], "1");
  }

  // A 202 that names no id isn't taken for an answer.
  for (const body of [{}, { pending: "" }, { pending: 7 }, undefined]) {
    const odd = setup({ routes: { "save-new": [202, body] } });
    await failsWith(odd.client.call("save-new", {}), "unexpected-answer");
    assert.deepEqual(odd.helper.calls().map((c) => c.route), ["save-new"]);
  }
});

test("call throws the outcome's error", async () => {
  const { client } = setup({ routes: { file: pending(), result: inTurn(pending(), [409, { error: "wrong-window" }]) } });
  await assert.rejects(client.call("file", { ticket: "ticket-1", mark: null, bytes: "" }), (error) => {
    assert.ok(error instanceof Healdan.HelperError);
    assert.equal(error.code, "wrong-window");
    assert.equal(error.status, 409);
    assert.equal(error.message, Healdan.messageFor("wrong-window"));
    return true;
  });

  // The other answers of a route that went pending are its own, whatever they are.
  for (const [status, code] of [[409, "no-ticket"], [409, "not-a-copy"], [500, "internal-error"], [400, "bad-request"]]) {
    const { client } = setup({ routes: { file: pending(), result: inTurn(pending(), [status, { error: code }]) } });
    await failsWith(client.call("file", { ticket: "ticket-1", mark: null, bytes: "" }), code);
  }
});

test("call reports lost-result when result itself is refused or fails, once a route went pending", async () => {
  // The route may have finished what it was asked (a pending file may have filed the document), so "try again" would
  // risk doing it twice: whatever stops the add-in learning the outcome is lost-result, not the code's own text.
  const refusals = [
    [403, "not-paired"],
    [409, "signed-out"],
    [403, "bad-origin"],
    [403, "bad-host"],
    [426, "update-healdan-helper"],
  ];
  for (const [status, code] of refusals) {
    const { client, helper } = setup({ routes: { file: pending(), result: inTurn(pending(), [status, { error: code }]) } });
    await failsWith(client.call("file", { ticket: "ticket-1", mark: null, bytes: "" }), "lost-result");
    assert.equal(helper.calls().filter((c) => c.route === "result").length, 2, code);
  }

  // A key the helper forgot meanwhile is still dropped, so the next press pairs again, but this one says what it says.
  const forgotten = setup({ routes: { "open-dialog": pending(), result: [403, { error: "not-paired" }] } });
  await failsWith(forgotten.client.call("open-dialog", { app: "Word" }), "lost-result");
  assert.equal(forgotten.client.paired(), false);

  // The same through a button's flow: it is a result the pane shows, not a request to pair.
  const flow = setup({ routes: { "save-new": pending(), result: [403, { error: "not-paired" }] } });
  assert.deepEqual(await Healdan.saveToHealdan({ client: flow.client, office: flow.office }), {
    ok: false,
    code: "lost-result",
    message: Healdan.messageFor("lost-result"),
  });

  // Nor is it left as "unreachable" when the helper stops answering, or answers something odd, while it is followed.
  const gone = setup({
    routes: {
      file: pending(),
      result: () => {
        throw new TypeError("Load failed");
      },
    },
  });
  await failsWith(gone.client.call("file", { ticket: "ticket-1", mark: null, bytes: "" }), "lost-result");
  const odd = setup({ routes: { file: pending(), result: inTurn(pending(), [202, {}]) } });
  await failsWith(odd.client.call("file", { ticket: "ticket-1", mark: null, bytes: "" }), "lost-result");

  // A route that never went pending keeps its own words: nothing could have been done twice.
  const direct = setup({ routes: { file: [403, { error: "not-paired" }] } });
  await failsWith(direct.client.call("file", { ticket: "ticket-1", mark: null, bytes: "" }), "not-paired");
});

test("call reports lost-result when the helper no longer has it", async () => {
  const { client } = setup({ routes: { "save-new": pending(), result: inTurn(pending(), [404, { error: "no-result" }]) } });
  await failsWith(client.call("save-new", {}), "lost-result");
  assert.equal(
    Healdan.messageFor("lost-result"),
    "Healdan Helper finished, but its answer was lost. Check the document in Healdan before trying again.",
  );

  // The helper quit and started again, on the next port, while the route ran: the new one has no such answer.
  const listening = [47615];
  const routes = {
    "save-new": pending(),
    result: ({ port }) => {
      if (port === 47615) {
        listening.splice(0, 1, 47616);
        throw new TypeError("Load failed");
      }
      return [404, { error: "no-result" }];
    },
  };
  const restarted = setup({ listening, routes });
  restarted.storage.setItem("healdan.port", "47615");
  await failsWith(restarted.client.call("save-new", {}), "lost-result");
  assert.deepEqual(restarted.helper.requests.map((r) => `${r.route}@${r.port}`), [
    "save-new@47615",
    "result@47615",
    "hello@47615",
    "hello@47616",
    "result@47616",
  ]);
});

test("call asks for a pending answer again when asking failed on the way, as asking twice can't do anything twice", async () => {
  let failed = false;
  const routes = {
    "save-new": pending(),
    result: () => {
      if (!failed) {
        failed = true;
        throw new TypeError("Load failed");
      }
      return SAVE;
    },
  };
  const { client, helper, storage } = setup({ routes });
  storage.setItem("healdan.port", "47615");
  assert.deepEqual(await client.call("save-new", {}), SAVE[1]);
  assert.deepEqual(helper.requests.map((r) => `${r.route}@${r.port}`), ["save-new@47615", "result@47615", "hello@47615", "result@47615"]);

  // But only once: a helper that keeps failing has lost the answer, as far as the add-in can tell.
  const broken = setup({
    routes: {
      "save-new": pending(),
      result: () => {
        throw new TypeError("Load failed");
      },
    },
  });
  await failsWith(broken.client.call("save-new", {}), "lost-result");
  assert.equal(broken.helper.calls().filter((c) => c.route === "result").length, 2);
});

test("a key the helper doesn't know is dropped, so the next press pairs again", async () => {
  const { client, storage, helper, office } = setup({ routes: { "open-dialog": [403, { error: "not-paired" }] } });
  assert.ok(client.paired());
  const result = await Healdan.openFromHealdan({ client, office });
  assert.deepEqual(result, { needsPairing: true });
  assert.equal(storage.getItem("healdan.key.Word"), null);
  assert.equal(client.paired(), false);
  const before = helper.requests.length;
  assert.deepEqual(await Healdan.openFromHealdan({ client, office }), { needsPairing: true });
  assert.equal(helper.requests.length, before, "and then it asks the helper nothing");
});

// --- Pairing ---------------------------------------------------------------------------------------------------------

test("an unpaired button asks to pair first", async () => {
  for (const name of FLOWS) {
    const { client, helper, office, log } = setup({ paired: false });
    assert.equal(client.paired(), false);
    const result = await Healdan[name]({ client, office });
    assert.deepEqual(result, { needsPairing: true }, name);
    assert.equal(helper.requests.length, 0, `${name} makes no other call`);
    assert.deepEqual(log, [], `${name} touches nothing in Office`);
  }
});

test("pairing stores the key per host", async () => {
  const routes = {
    "pair/start": [200, { pairingId: "p-1" }],
    "pair/finish": [200, { key: KEY }],
  };
  const { client, helper, storage } = setup({ paired: false, routes });
  assert.equal(client.paired(), false);

  assert.equal(await client.pairStart(), "p-1");
  assert.deepEqual(helper.calls()[0].body, { app: "Word" });

  await client.pairFinish("p-1", "123456");
  assert.deepEqual(helper.calls()[1].body, { pairingId: "p-1", code: "123456" });
  assert.equal(storage.getItem("healdan.key.Word"), KEY);
  assert.equal(client.paired(), true);

  // Another app has its own storage in Office, and here its own name in the same one.
  for (const other of ["Excel", "PowerPoint"]) {
    const otherClient = Healdan.createHelperClient({ fetch: helper.fetch, storage, origin: SERVER, host: other });
    assert.equal(otherClient.paired(), false, `${other} has to pair for itself`);
  }
  const excel = Healdan.createHelperClient({ fetch: helper.fetch, storage, origin: SERVER, host: "Excel" });
  assert.deepEqual(await excel.pairStart(), "p-1");
  assert.deepEqual(helper.calls().at(-1).body, { app: "Excel" });
});

test("pairing takes the code as typed, spaces and all, and doesn't spend a try on one that can't be right", async () => {
  const routes = { "pair/finish": [200, { key: KEY }] };
  const { client, helper } = setup({ paired: false, routes });
  await client.pairFinish("p-1", " 123 456 ");
  assert.equal(helper.calls()[0].body.code, "123456");

  for (const typed of ["", "12345", "1234567", "12345a", undefined]) {
    const before = helper.requests.length;
    await failsWith(client.pairFinish("p-1", typed), "code-format");
    assert.equal(helper.requests.length, before, `"${typed}" isn't sent`);
  }
});

test("a wrong code says how many tries are left", async () => {
  let triesLeft = 2;
  const routes = { "pair/finish": () => [403, { error: "wrong-code", triesLeft }] };
  const { client, storage } = setup({ paired: false, routes });

  await assert.rejects(client.pairFinish("p-1", "000000"), (error) => {
    assert.ok(error instanceof Healdan.HelperError);
    assert.equal(error.code, "wrong-code");
    assert.equal(error.triesLeft, 2);
    assert.match(error.message, /2 tries left/);
    return true;
  });

  triesLeft = 1;
  await assert.rejects(client.pairFinish("p-1", "000000"), (error) => {
    assert.equal(error.triesLeft, 1);
    assert.match(error.message, /1 try left/);
    return true;
  });

  triesLeft = 0;
  await assert.rejects(client.pairFinish("p-1", "000000"), (error) => {
    assert.equal(error.triesLeft, 0);
    assert.match(error.message, /cancelled/);
    assert.match(error.message, /new code/);
    return true;
  });

  assert.equal(client.paired(), false, "a wrong code keeps no key");
  assert.equal(storage.getItem("healdan.key.Word"), null);
});

test("a code that expired, and pairing that's paused, are told", async () => {
  const expired = setup({ paired: false, routes: { "pair/finish": [403, { error: "pairing-expired" }] } });
  await failsWith(expired.client.pairFinish("p-1", "123456"), "pairing-expired");

  const paused = setup({ paired: false, routes: { "pair/start": [403, { error: "too-many-tries" }] } });
  await assert.rejects(paused.client.pairStart(), (error) => {
    assert.equal(error.code, "too-many-tries");
    assert.match(error.message, /10 minutes/);
    return true;
  });
});

test("a pairing the helper answers oddly isn't taken for one", async () => {
  const noId = setup({ paired: false, routes: { "pair/start": [200, {}] } });
  await failsWith(noId.client.pairStart(), "unexpected-answer");
  const noKey = setup({ paired: false, routes: { "pair/finish": [200, { key: "" }] } });
  await failsWith(noKey.client.pairFinish("p-1", "123456"), "unexpected-answer");
  assert.equal(noKey.client.paired(), false);
});

test("storage that won't keep the key, or won't be read, is told, not trusted", async () => {
  const { client, storage } = setup({ paired: false, routes: { "pair/finish": [200, { key: KEY }] } });
  storage.setItem = () => {
    throw new Error("QuotaExceededError");
  };
  await failsWith(client.pairFinish("p-1", "123456"), "storage-failed");
  assert.equal(client.paired(), false);

  storage.getItem = () => {
    throw new Error("SecurityError");
  };
  assert.equal(client.paired(), false, "storage that can't be read holds no key");
  assert.equal(await client.find(), 47615, "and finding the helper goes on without remembering the port");
});

test("a page where WebKit blocks storage, so reading localStorage itself throws, behaves as unpaired", async () => {
  const source = fs.readFileSync(path.join(__dirname, "..", "web", "healdan.js"), "utf8");
  const page = vm.createContext({});
  vm.runInContext(
    "Object.defineProperty(globalThis, 'localStorage', { get() { throw new Error('SecurityError'); } });",
    page,
  );
  vm.runInContext(source, page);
  const helper = fakeHelper({ routes: { "pair/finish": [200, { key: KEY }] } });

  let client;
  assert.doesNotThrow(() => {
    client = page.Healdan.createHelperClient({ fetch: helper.fetch, origin: SERVER, host: "Word" });
  });
  assert.equal(client.paired(), false);
  assert.equal(await client.find(), 47615, "it finds the helper, without remembering the port");
  await assert.rejects(client.pairFinish("p-1", "123456"), (error) => error.code === "storage-failed");
  const result = await page.Healdan.saveToHealdan({ client, office: fakeOffice() }); // an object of the page's, so its fields
  assert.deepEqual(Object.entries(result), [["needsPairing", true]]);
});

test("a client needs one of Office's three apps", () => {
  assert.throws(() => Healdan.createHelperClient({ fetch: async () => {}, storage: memoryStorage(), origin: SERVER, host: "Outlook" }), TypeError);
  assert.throws(() => Healdan.createHelperClient({ fetch: async () => {}, storage: memoryStorage(), origin: SERVER }), TypeError);
});

// --- Save to Healdan -------------------------------------------------------------------------------------------------

test("saveToHealdan files the bytes with the ticket and the mark, then takes the mark off", async () => {
  const { client, helper, office, log } = setup({
    routes: { "save-new": SAVE, file: FILED },
    officeOptions: { url: "/Users/me/Lease.docx" },
  });
  const result = await Healdan.saveToHealdan({ client, office });

  const [id] = office.marks;
  assert.match(id, /^[0-9a-f-]{16,}$/, "a one-time id");
  assert.deepEqual(log, ["save-new", "readBytes", `setMark ${id}`, "file", `removeMark ${id}`]);

  const [saveNew, file] = helper.calls();
  assert.deepEqual(saveNew.body, { app: "Word", url: "/Users/me/Lease.docx", asNew: false });
  assert.deepEqual(file.body, { ticket: "ticket-1", mark: id, bytes: "UEsDBA==" });
  assert.deepEqual(result, { ok: true, docRef: "#1234 v1.0" });
});

test("saveToHealdan marks each document with an id of its own", async () => {
  const { client, office } = setup({ routes: { "save-new": SAVE, file: FILED } });
  await Healdan.saveToHealdan({ client, office });
  await Healdan.saveToHealdan({ client, office });
  assert.equal(office.marks.length, 2);
  assert.notEqual(office.marks[0], office.marks[1]);
});

test("saveToHealdan takes the mark off when filing fails, and says why", async () => {
  const { client, office, log } = setup({ routes: { "save-new": SAVE, file: [409, { error: "wrong-window" }] } });
  const result = await Healdan.saveToHealdan({ client, office });
  const [id] = office.marks;
  assert.deepEqual(log, ["save-new", "readBytes", `setMark ${id}`, "file", `removeMark ${id}`]);
  assert.deepEqual(result, { ok: false, code: "wrong-window", message: Healdan.messageFor("wrong-window") });
});

test("saveToHealdan takes the mark off when the helper can't be reached for the filing", async () => {
  const routes = {
    "save-new": SAVE,
    file: () => {
      throw new TypeError("Load failed");
    },
  };
  const { client, office, log } = setup({ routes });
  const result = await Healdan.saveToHealdan({ client, office });
  assert.equal(result.code, "unreachable");
  assert.ok(log.at(-1).startsWith("removeMark "), `${log} ends by taking the mark off`);
});

test("saveToHealdan still removes the mark when the file call ends in a pending error", async () => {
  const routes = { "save-new": SAVE, file: pending(), result: inTurn(pending(), [409, { error: "wrong-window" }]) };
  const { client, office, log } = setup({ routes });
  const result = await Healdan.saveToHealdan({ client, office });
  const [id] = office.marks;
  assert.deepEqual(log, ["save-new", "readBytes", `setMark ${id}`, "file", "result", "result", `removeMark ${id}`]);
  assert.deepEqual(result, { ok: false, code: "wrong-window", message: Healdan.messageFor("wrong-window") });

  // And when the answer was lost: the pane says to check the document in Healdan.
  const lost = setup({ routes: { "save-new": SAVE, file: pending(), result: [404, { error: "no-result" }] } });
  const lostResult = await Healdan.saveToHealdan({ client: lost.client, office: lost.office });
  assert.ok(lost.log.at(-1).startsWith("removeMark "), `${lost.log} ends by taking the mark off`);
  assert.deepEqual(lostResult, { ok: false, code: "lost-result", message: Healdan.messageFor("lost-result") });

  // A filing collected through result is the same as one answered at once.
  const slow = setup({ routes: { "save-new": pending("id-2"), file: pending(), result: ({ body }) => (body.id === "id-2" ? SAVE : FILED) } });
  assert.deepEqual(await Healdan.saveToHealdan({ client: slow.client, office: slow.office }), { ok: true, docRef: "#1234 v1.0" });
});

test("saveToHealdan on cancel does nothing else", async () => {
  const { client, helper, office, log } = setup({ routes: { "save-new": [200, { action: "cancel" }] } });
  const result = await Healdan.saveToHealdan({ client, office });
  assert.deepEqual(result, { ok: true, cancelled: true });
  assert.deepEqual(log, ["save-new"]);
  assert.equal(helper.calls().length, 1);
  assert.deepEqual(office.marks, []);
});

test("saveToHealdan on local says to use the app's own Save As", async () => {
  for (const host of ["Word", "Excel", "PowerPoint"]) {
    const { client, office, log } = setup({ host, routes: { "save-new": [200, { action: "local" }] } });
    const result = await Healdan.saveToHealdan({ client, office });
    assert.equal(result.ok, true);
    assert.equal(result.local, true);
    assert.equal(result.code, "local-save", "the code lets the pane say it again, for this app");
    assert.equal(result.message, Healdan.messageFor("local-save", { host }));
    assert.match(result.message, new RegExp(`${host}'s own Save As`));
    assert.deepEqual(log, ["save-new"], "no bytes are read for a document kept on this computer");
  }
});

test("saveToHealdan sets no mark in PowerPoint, which never shows one", async () => {
  const { client, helper, office, log } = setup({ host: "PowerPoint", routes: { "save-new": SAVE, file: FILED } });
  const result = await Healdan.saveToHealdan({ client, office });
  assert.deepEqual(log, ["save-new", "readBytes", "file"]);
  assert.deepEqual(helper.calls()[0].body, { app: "PowerPoint", url: "", asNew: false });
  assert.deepEqual(helper.calls()[1].body, { ticket: "ticket-1", mark: null, bytes: "UEsDBA==" });
  assert.equal(result.ok, true);
});

test("saveToHealdan marks documents in Excel as in Word", async () => {
  const { client, helper, office, log } = setup({ host: "Excel", routes: { "save-new": SAVE, file: FILED } });
  await Healdan.saveToHealdan({ client, office });
  const [id] = office.marks;
  assert.deepEqual(log, ["save-new", "readBytes", `setMark ${id}`, "file", `removeMark ${id}`]);
  assert.equal(helper.calls()[1].body.mark, id);
});

test("saveToHealdan files nothing when Office can't give the document's bytes", async () => {
  const failures = { readBytes: new Error("The document can't be read") };
  const { client, helper, office, log } = setup({ routes: { "save-new": SAVE, file: FILED }, officeOptions: { failures } });
  const result = await Healdan.saveToHealdan({ client, office });
  assert.deepEqual(log, ["save-new", "readBytes"], "no mark was set, so none is taken off");
  assert.equal(helper.calls().length, 1);
  assert.equal(result.ok, false);
  assert.equal(result.code, "office-error");
  assert.match(result.message, /The document can't be read/);
});

test("saveToHealdan takes the mark off even when setting it failed, and files nothing", async () => {
  const failures = { setMark: new Error("The property can't be set") };
  const { client, helper, office, log } = setup({ routes: { "save-new": SAVE, file: FILED }, officeOptions: { failures } });
  const result = await Healdan.saveToHealdan({ client, office });
  const [id] = office.marks;
  assert.deepEqual(log, ["save-new", "readBytes", `setMark ${id}`, `removeMark ${id}`]);
  assert.equal(helper.calls().length, 1);
  assert.equal(result.code, "office-error");
});

test("saveToHealdan says when the mark couldn't be taken off a filed document", async () => {
  const failures = { removeMark: new Error("nope") };
  const { client, office } = setup({ routes: { "save-new": SAVE, file: FILED }, officeOptions: { failures } });
  const result = await Healdan.saveToHealdan({ client, office });
  assert.deepEqual(result, { ok: true, docRef: "#1234 v1.0", code: "mark-left", message: Healdan.messageFor("mark-left") });

  // A filing that failed is told as that, and the mark that stayed isn't the news.
  const failed = setup({ routes: { "save-new": SAVE, file: [409, { error: "no-ticket" }] }, officeOptions: { failures } });
  const result2 = await Healdan.saveToHealdan({ client: failed.client, office: failed.office });
  assert.equal(result2.code, "no-ticket");
});

test("saveToHealdan takes an answer it doesn't know for a problem", async () => {
  const { client, office, log } = setup({ routes: { "save-new": [200, { action: "dance" }] } });
  const result = await Healdan.saveToHealdan({ client, office });
  assert.equal(result.ok, false);
  assert.equal(result.code, "unexpected-answer");
  assert.deepEqual(log, ["save-new"]);

  const noTicket = setup({ routes: { "save-new": [200, { action: "save" }] } });
  assert.equal((await Healdan.saveToHealdan({ client: noTicket.client, office: noTicket.office })).code, "unexpected-answer");
  assert.deepEqual(noTicket.log, ["save-new"]);
});

test("saveToHealdan tells why the helper wouldn't take the window", async () => {
  const { client, office, log } = setup({ routes: { "save-new": [409, { error: "wrong-window" }] } });
  const result = await Healdan.saveToHealdan({ client, office });
  assert.deepEqual(log, ["save-new"]);
  assert.equal(result.code, "wrong-window");
  assert.equal(result.message, Healdan.messageFor("wrong-window"));
});

test("saveAsNewDocument is Save to Healdan from a Healdan document's window", async () => {
  const { client, helper, office, log } = setup({ routes: { "save-new": SAVE, file: FILED } });
  const result = await Healdan.saveAsNewDocument({ client, office });
  const [id] = office.marks;
  assert.deepEqual(log, ["save-new", "readBytes", `setMark ${id}`, "file", `removeMark ${id}`]);
  assert.equal(helper.calls()[0].body.asNew, true);
  assert.equal(result.ok, true);
});

// --- Save as New Version ---------------------------------------------------------------------------------------------

test("saveAsNewVersion asks the helper for the next version of a document from Healdan", async () => {
  const { client, helper, office } = setup({
    routes: { state: IN_HEALDAN, "new-version": [200, {}] },
    officeOptions: { url: "/Users/me/Lease.docx" },
  });
  const result = await Healdan.saveAsNewVersion({ client, office });
  assert.deepEqual(helper.calls().map((c) => c.route), ["state", "new-version"]);
  assert.deepEqual(helper.calls()[1].body, { app: "Word", url: "/Users/me/Lease.docx" });
  assert.deepEqual(result, { ok: true, docRef: "#1234 v1.2" });
});

test("saveAsNewVersion says a document that isn't from Healdan has no version to add to", async () => {
  const { client, helper, office } = setup({ routes: { state: NOT_IN_HEALDAN, "new-version": [200, {}] } });
  const result = await Healdan.saveAsNewVersion({ client, office });
  assert.deepEqual(helper.calls().map((c) => c.route), ["state"]);
  assert.deepEqual(result, { ok: false, code: "not-a-copy", message: Healdan.messageFor("not-a-copy") });
});

test("saveAsNewVersion says a read-only copy can't be saved as a new version", async () => {
  const readOnly = [200, { known: true, docRef: "#1234 v1.2", readOnly: true }];
  const { client, helper, office } = setup({ routes: { state: readOnly, "new-version": [200, {}] } });
  const result = await Healdan.saveAsNewVersion({ client, office });
  assert.deepEqual(helper.calls().map((c) => c.route), ["state"]);
  assert.equal(result.ok, false);
  assert.equal(result.code, "read-only-version");
  assert.match(result.message, /Save as New Document/);
});

test("saveAsNewVersion tells what the helper refused", async () => {
  const { client, office } = setup({ routes: { state: IN_HEALDAN, "new-version": [409, { error: "not-a-copy" }] } });
  assert.equal((await Healdan.saveAsNewVersion({ client, office })).code, "not-a-copy");
});

// --- Properties ------------------------------------------------------------------------------------------------------

test("properties shows the helper's window for a document from Healdan", async () => {
  const { client, helper, office } = setup({ routes: { properties: [200, { action: "saved" }] } });
  assert.deepEqual(await Healdan.properties({ client, office }), { ok: true });
  assert.deepEqual(helper.calls()[0].body, { app: "Word", url: "" });

  const cancelled = setup({ routes: { properties: [200, { action: "cancel" }] } });
  assert.deepEqual(await Healdan.properties({ client: cancelled.client, office: cancelled.office }), { ok: true, cancelled: true });
});

test("properties on a document that isn't in Healdan goes on to Save to Healdan", async () => {
  const { client, helper, office, log } = setup({
    routes: { properties: [200, { action: "file" }], "save-new": SAVE, file: FILED },
  });
  const result = await Healdan.properties({ client, office });
  const [id] = office.marks;
  assert.deepEqual(log, ["properties", "save-new", "readBytes", `setMark ${id}`, "file", `removeMark ${id}`]);
  assert.equal(helper.calls()[1].body.asNew, false);
  assert.deepEqual(result, { ok: true, docRef: "#1234 v1.0" });
});

// --- Open from Healdan -----------------------------------------------------------------------------------------------

test("openFromHealdan shows the helper's Open window", async () => {
  for (const host of ["Word", "Excel", "PowerPoint"]) {
    const { client, helper, office } = setup({ host, routes: { "open-dialog": [200, { action: "opened" }] } });
    assert.deepEqual(await Healdan.openFromHealdan({ client, office }), { ok: true });
    assert.deepEqual(helper.calls()[0].body, { app: host });
  }

  const cancelled = setup({ routes: { "open-dialog": [200, { action: "cancel" }] } });
  assert.deepEqual(await Healdan.openFromHealdan({ client: cancelled.client, office: cancelled.office }), { ok: true, cancelled: true });
});

test("openFromHealdan on local says to use the app's own Open", async () => {
  const { client, office } = setup({ host: "Excel", routes: { "open-dialog": [200, { action: "local" }] } });
  const result = await Healdan.openFromHealdan({ client, office });
  assert.equal(result.ok, true);
  assert.equal(result.local, true);
  assert.equal(result.code, "local-open");
  assert.equal(result.message, Healdan.messageFor("local-open", { host: "Excel" }));
  assert.match(result.message, /Excel's own Open/);
});

test("openFromHealdan tells when the helper is signed out", async () => {
  const { client, office } = setup({ routes: { "open-dialog": [409, { error: "signed-out" }] } });
  assert.deepEqual(await Healdan.openFromHealdan({ client, office }), {
    ok: false,
    code: "signed-out",
    message: Healdan.messageFor("signed-out"),
  });
});

// --- Insert Doc ID ---------------------------------------------------------------------------------------------------

test("insertDocId puts the document's doc # and version in the footer", async () => {
  const { client, helper, office, log } = setup({
    routes: { state: IN_HEALDAN },
    officeOptions: { url: "/Users/me/Lease.docx" },
  });
  const result = await Healdan.insertDocId({ client, office });
  assert.deepEqual(log, ["state", 'insertDocId "#1234 v1.2"']);
  assert.deepEqual(helper.calls()[0].body, { app: "Word", url: "/Users/me/Lease.docx" });
  assert.deepEqual(result, { ok: true, docRef: "#1234 v1.2" });
});

test("insertDocId on a document that isn't in Healdan inserts an empty field, and says so", async () => {
  const { client, office, log } = setup({ routes: { state: NOT_IN_HEALDAN } });
  const result = await Healdan.insertDocId({ client, office });
  assert.deepEqual(log, ["state", 'insertDocId ""']);
  assert.deepEqual(result, { ok: true, code: "not-filed-doc-id", message: Healdan.messageFor("not-filed-doc-id") });
  assert.match(result.message, /once it's saved to Healdan/);
});

test("insertDocId tells when Office wouldn't insert the field", async () => {
  const failures = { insertDocId: new Error("The footer can't be changed") };
  const { client, office } = setup({ routes: { state: IN_HEALDAN }, officeOptions: { failures } });
  const result = await Healdan.insertDocId({ client, office });
  assert.equal(result.ok, false);
  assert.equal(result.code, "office-error");
  assert.match(result.message, /The footer can't be changed/);
});

// --- Word for the web ------------------------------------------------------------------------------------------------

test("on the web the buttons say they work in the desktop apps only, and ask nothing", async () => {
  for (const name of FLOWS) {
    const { client, helper, office, log } = setup({ officeOptions: { onTheWeb: true } });
    const result = await Healdan[name]({ client, office });
    assert.deepEqual(result, { ok: false, code: "no-office-on-the-web", message: Healdan.messageFor("no-office-on-the-web") }, name);
    assert.equal(helper.requests.length, 0, name);
    assert.deepEqual(log, [], name);
  }
});

// --- What the buttons tell the pane -----------------------------------------------------------------------------------
//
// The ribbon's code runs without a page of its own, so it shows a message in a window (taskpane.html?message=<code>), and
// leaves notes in the app's localStorage, which the pane reads: the last message, and the flows still running.

test("a message's link carries its code and the app, and reads back", () => {
  assert.equal(Healdan.messageLink({ code: "not-paired", app: "Word" }), "taskpane.html?message=not-paired&app=Word");
  assert.deepEqual(Healdan.readMessageLink("?message=not-paired&app=Word"), { code: "not-paired", app: "Word" });
  assert.deepEqual(Healdan.readMessageLink("message=lost-result"), { code: "lost-result" });
});

test("a message's link never carries what Office said: the page is fetched from the server, so its address is logged", () => {
  // Office's text can hold a file name. It goes to the window in a message (windowMessage), not in the address.
  const note = { code: "office-error", app: "Excel", detail: "Can't save Lease agreement - Draft.xlsx" };
  const link = Healdan.messageLink(note);
  assert.equal(link, "taskpane.html?message=office-error&app=Excel");
  assert.ok(!/detail|Lease|xlsx/i.test(link));
  // And an address that carries one anyway, an old link or a made-up one, is not read for it.
  assert.deepEqual(Healdan.readMessageLink("?message=office-error&app=Excel&detail=Lease+agreement"), { code: "office-error", app: "Excel" });
});

test("a message's link is read only when it is one", () => {
  for (const search of ["", "?", "?app=Word", "?message=", "?message=Not%20Paired", "?message=%3Cscript%3E", "?message=" + "a".repeat(60)]) {
    assert.equal(Healdan.readMessageLink(search), null, search);
  }
  // An app that isn't one of Office's three is left out, not trusted.
  assert.deepEqual(Healdan.readMessageLink("?message=not-paired&app=Outlook"), { code: "not-paired" });
  // A code nobody knows is still a message: messageFor words it.
  assert.deepEqual(Healdan.readMessageLink("?message=something-new"), { code: "something-new" });
});

// --- What the window and the button tell each other -------------------------------------------------------------------
//
// The window is opened by the button's code, with Office.context.ui.displayDialogAsync, and talks to it with
// messageParent (to the button) and messageChild (to the window). Only strings travel, so they are JSON.

test("the window's messages are JSON of three kinds, and read back whole", () => {
  assert.equal(Healdan.windowMessage({ kind: "ready" }), '{"healdan":"ready"}');
  assert.deepEqual(Healdan.readWindowMessage(Healdan.windowMessage({ kind: "ready" })), { kind: "ready" });

  const paired = Healdan.windowMessage({ kind: "paired", app: "Word", key: KEY });
  assert.deepEqual(JSON.parse(paired), { healdan: "paired", app: "Word", key: KEY });
  assert.deepEqual(Healdan.readWindowMessage(paired), { kind: "paired", app: "Word", key: KEY });

  const detail = Healdan.windowMessage({ kind: "detail", detail: "Can't save Lease agreement - Draft.xlsx" });
  assert.deepEqual(Healdan.readWindowMessage(detail), { kind: "detail", detail: "Can't save Lease agreement - Draft.xlsx" });
  assert.equal(Healdan.readWindowMessage(Healdan.windowMessage({ kind: "detail", detail: "x".repeat(1000) })).detail.length, 200);
});

test("a message that isn't one of the window's is not read, and nothing in it is trusted", () => {
  const junk = [
    undefined,
    null,
    "",
    "ready",
    "not json",
    "null",
    "[]",
    "7",
    '{"healdan":"dance"}',
    '{"kind":"ready"}',
    '{"healdan":"paired","app":"Outlook","key":"' + KEY + '"}',
    '{"healdan":"paired","app":"Word"}',
    '{"healdan":"paired","app":"Word","key":""}',
    '{"healdan":"paired","app":"Word","key":7}',
    '{"healdan":"paired","app":"Word","key":"short"}',
    '{"healdan":"paired","app":"Word","key":"has spaces and <b>marks</b> in it ........"}',
    '{"healdan":"paired","app":"Word","key":"' + "k".repeat(300) + '"}',
    '{"healdan":"detail"}',
    '{"healdan":"detail","detail":7}',
    '{"healdan":"detail","detail":""}',
  ];
  for (const text of junk) {
    assert.equal(Healdan.readWindowMessage(text), null, String(text));
  }
  assert.throws(() => Healdan.windowMessage({ kind: "dance" }), /kind/);
});

test("a client gives the key it holds and takes one handed to it, for the button when the window paired", async () => {
  const { client, storage } = setup({ paired: false });
  assert.equal(client.currentKey(), null);
  assert.equal(client.adoptKey(KEY), true);
  assert.equal(storage.getItem("healdan.key.Word"), KEY);
  assert.equal(client.paired(), true);
  assert.equal(client.currentKey(), KEY);

  // Only a key as the helper makes them (32 random bytes in base64url): not anything a message happens to hold.
  for (const odd of ["", "short", "has spaces", 7, null, undefined, "k".repeat(300)]) {
    const fresh = setup({ paired: false });
    assert.equal(fresh.client.adoptKey(odd), false, String(odd));
    assert.equal(fresh.client.paired(), false, String(odd));
  }

  // Storage that won't keep it says so, and nothing is left half done.
  const refusing = Healdan.createHelperClient({
    fetch: async () => {
      throw new TypeError("Load failed");
    },
    storage: { getItem: () => null, setItem: () => { throw new Error("QuotaExceeded"); }, removeItem: () => {} },
    origin: SERVER,
    host: "Word",
  });
  assert.equal(refusing.adoptKey(KEY), false);
  assert.equal(refusing.currentKey(), null);

  // The key pairing gave is the one it hands on.
  const pairing = setup({
    paired: false,
    routes: { "pair/start": [200, { pairingId: "p-1" }], "pair/finish": [200, { key: KEY }] },
  });
  await pairing.client.pairFinish(await pairing.client.pairStart(), "123456");
  assert.equal(pairing.client.currentKey(), KEY);
});

test("noteOf says what the window should show for a flow's result", () => {
  assert.deepEqual(Healdan.noteOf({ needsPairing: true }, "Word"), { code: "not-paired", app: "Word" });
  assert.deepEqual(Healdan.noteOf({ ok: false, code: "lost-result", message: "…" }, "Excel"), { code: "lost-result", app: "Excel" });
  assert.deepEqual(Healdan.noteOf({ ok: false, code: "office-error", message: "…", detail: "no footer" }, "Word"), {
    code: "office-error",
    app: "Word",
    detail: "no footer",
  });
  assert.deepEqual(Healdan.noteOf({ ok: true, local: true, code: "local-save", message: "…" }, "PowerPoint"), {
    code: "local-save",
    app: "PowerPoint",
  });
  assert.deepEqual(Healdan.noteOf({ ok: true, docRef: "#1 v1.0", code: "mark-left", message: "…" }, "Word"), { code: "mark-left", app: "Word" });
  // Success, and the user's own Cancel, say nothing.
  assert.equal(Healdan.noteOf({ ok: true }, "Word"), null);
  assert.equal(Healdan.noteOf({ ok: true, docRef: "#1 v1.0" }, "Word"), null);
  assert.equal(Healdan.noteOf({ ok: true, cancelled: true }, "Word"), null);
});

test("a failure from Office keeps what Office said, for the window", async () => {
  const { client, office } = setup({
    routes: { state: IN_HEALDAN },
    officeOptions: { failures: { insertDocId: new Error("The footer can't be changed") } },
  });
  const result = await Healdan.insertDocId({ client, office });
  assert.equal(result.code, "office-error");
  assert.equal(result.detail, "The footer can't be changed");
  assert.equal(Healdan.messageFor("office-error", { detail: result.detail }), result.message);
});

test("the last message is kept for the pane, and read back when it is whole", () => {
  const storage = memoryStorage();
  assert.equal(Healdan.lastMessage(storage), null);
  Healdan.noteMessage(storage, { code: "signed-out", app: "Word" }, 1000);
  assert.deepEqual(Healdan.lastMessage(storage), { code: "signed-out", app: "Word", at: 1000 });
  Healdan.noteMessage(storage, { code: "office-error", app: "Excel", detail: "no footer" }, 2000);
  assert.deepEqual(Healdan.lastMessage(storage), { code: "office-error", app: "Excel", detail: "no footer", at: 2000 });

  // Anything else in that place is not a message.
  for (const junk of ["", "not json", "null", "[]", '{"code": 7, "at": 1}', '{"code": "x y", "at": 1}', '{"code": "fine"}', '{"code": "fine", "at": "soon"}']) {
    storage.setItem("healdan.last", junk);
    assert.equal(Healdan.lastMessage(storage), null, junk);
  }
  // Storage that refuses, or isn't there, remembers nothing and says nothing.
  const refusing = { getItem: () => { throw new Error("SecurityError"); }, setItem: () => { throw new Error("SecurityError"); }, removeItem: () => {} };
  assert.doesNotThrow(() => Healdan.noteMessage(refusing, { code: "signed-out", app: "Word" }, 1));
  assert.equal(Healdan.lastMessage(refusing), null);
  assert.doesNotThrow(() => Healdan.noteMessage(null, { code: "signed-out", app: "Word" }, 1));
  assert.equal(Healdan.lastMessage(null), null);
});

test("a flow still running is marked busy while it runs, and the mark goes stale if its page is gone", () => {
  const storage = memoryStorage();
  assert.equal(Healdan.busyNow(storage, 0), null);

  Healdan.markBusy(storage, "saveToHealdan", "Word", 1000);
  assert.deepEqual(Healdan.busyNow(storage, 1000), { flow: "saveToHealdan", label: "Save to Healdan", app: "Word" });
  Healdan.markBusy(storage, "saveToHealdan", "Word", 1000 + Healdan.BUSY_BEAT); // the heartbeat
  assert.equal(Healdan.busyNow(storage, 1000 + 2 * Healdan.BUSY_BEAT).flow, "saveToHealdan");

  // A page that was closed or crashed stops the heartbeat, and the pane stops waiting for it.
  assert.equal(Healdan.busyNow(storage, 1000 + Healdan.BUSY_BEAT + Healdan.BUSY_STALE + 1), null);

  // Two at once: the newer shows, and one ending doesn't end the other.
  Healdan.markBusy(storage, "saveToHealdan", "Word", 5000);
  Healdan.markBusy(storage, "openFromHealdan", "Word", 6000);
  assert.equal(Healdan.busyNow(storage, 6000).flow, "openFromHealdan");
  Healdan.clearBusy(storage, "openFromHealdan");
  assert.equal(Healdan.busyNow(storage, 6000).flow, "saveToHealdan");
  Healdan.clearBusy(storage, "saveToHealdan");
  assert.equal(Healdan.busyNow(storage, 6000), null);

  // A flow nobody knows isn't marked, and nothing here throws without storage.
  Healdan.markBusy(storage, "dance", "Word", 1);
  assert.equal(Healdan.busyNow(storage, 1), null);
  assert.doesNotThrow(() => Healdan.markBusy(null, "saveToHealdan", "Word", 1));
  assert.equal(Healdan.busyNow(null, 1), null);
  assert.doesNotThrow(() => Healdan.clearBusy(null, "saveToHealdan"));
});

test("every button has a name for the pane to say", () => {
  assert.deepEqual(Healdan.FLOW_LABELS, {
    saveToHealdan: "Save to Healdan",
    openFromHealdan: "Open from Healdan",
    saveAsNewVersion: "Save as New Version",
    saveAsNewDocument: "Save as New Document",
    properties: "Properties",
    insertDocId: "Insert Doc ID",
  });
  for (const name of FLOWS) {
    assert.equal(typeof Healdan.FLOW_LABELS[name], "string", name);
  }
});

// --- Messages --------------------------------------------------------------------------------------------------------

const HELPER_CODES = [
  "bad-host",
  "bad-origin",
  "not-paired",
  "update-healdan-helper",
  "signed-out",
  "not-a-copy",
  "wrong-window",
  "no-ticket",
  "wrong-code",
  "pairing-expired",
  "too-many-tries",
  "too-large",
  "too-many-pending",
  "bad-request",
  "internal-error",
  // "no-result" answers only result, which call turns into the add-in's own lost-result.
];

test("messages: every code the helper answers has a text of its own", () => {
  const seen = new Map();
  for (const code of [
    ...HELPER_CODES,
    "unreachable",
    "no-office-on-the-web",
    "code-format",
    "storage-failed",
    "unexpected-answer",
    "office-error",
    "lost-result",
  ]) {
    const message = Healdan.messageFor(code, { triesLeft: 2 });
    assert.equal(typeof message, "string", code);
    assert.ok(message.length > 20, `${code} has a real message`);
    assert.ok(!/doesn't know/.test(message), `${code} has a text of its own, not the one for a code nobody knows`);
    assert.ok(!seen.has(message), `${code} and ${seen.get(message)} say the same`);
    seen.set(message, code);
    assert.ok(message.endsWith("."), `${code}: ${message}`);
    assert.ok(!/[^\x20-\x7e]/.test(message), `${code} uses plain ASCII`);
  }
});

test("messages: the ones the pane shows most", () => {
  assert.equal(
    Healdan.messageFor("unreachable"),
    "Healdan Helper isn't running, or Microsoft Office isn't set up in it. If it is running, check it is signed in to this Healdan, and set up Microsoft Office from its menu if it offers to.",
  );
  assert.match(Healdan.messageFor("signed-out"), /^Healdan Helper is signed out, or still starting up\./);
  assert.match(Healdan.messageFor("not-paired"), /pair/i);
  assert.match(Healdan.messageFor("update-healdan-helper"), /Update Healdan Helper/);
  assert.match(Healdan.messageFor("not-a-copy"), /Save to Healdan/);
  assert.match(Healdan.messageFor("no-office-on-the-web"), /desktop apps/);
  assert.match(Healdan.messageFor("too-many-tries"), /10 minutes/);
});

test("messages: a code nobody knows still gets a text, which names it", () => {
  const message = Healdan.messageFor("something-new");
  assert.equal(typeof message, "string");
  assert.match(message, /something-new/);
  assert.equal(typeof Healdan.messageFor(undefined), "string");
});

test("messages: a HelperError is an Error with the code, the status and the text", () => {
  const error = new Healdan.HelperError("wrong-code", { status: 403, triesLeft: 1 });
  assert.ok(error instanceof Error);
  assert.equal(error.name, "HelperError");
  assert.equal(error.code, "wrong-code");
  assert.equal(error.status, 403);
  assert.equal(error.triesLeft, 1);
  assert.match(error.message, /1 try left/);
});
