/*
 * Healdan's Microsoft Office add-in: its logic (M3.5 spec section 6).
 *
 * The add-in is buttons and a pane; Healdan Helper, on the user's Mac, does the work. This file is everything the add-in
 * decides, with no Office and no page in it, so Node's own test runner can run it (clients/office/test/). The Office.js
 * glue (office.js) and the pages sit on top of it, and call it as `Healdan` on the page's global.
 *
 * It is a classic script: no import, no npm. It sets `Healdan` on the global object, and `module.exports` under Node.
 *
 * What it holds:
 *   - createHelperClient: a client for the helper's HTTPS port on 127.0.0.1 (fetch and storage are passed in)
 *   - messageFor, HelperError: the pane's texts, one table, for every code the helper or the add-in can answer with
 *   - six flows, one for each button: saveToHealdan, saveAsNewDocument, saveAsNewVersion, properties,
 *     openFromHealdan and insertDocId. Each takes { client, office } and resolves, never rejects, to one of:
 *
 *       { needsPairing: true }               this app isn't paired with the helper: the pane asks for a pairing code
 *       { ok: true }                         it did what was asked
 *       { ok: true, cancelled: true }        the user cancelled the helper's window
 *       { ok: true, local: true, message }   the user chose the app's own box: message says to use it
 *       { ok: true, docRef, message? }       it did, and message is a notice for the user, if there is one
 *       { ok: false, code, message }         it couldn't: message is the pane's text for code
 *
 *     docRef is the document's "#1234 v1.2" form, when the flow knows it.
 *
 * `office` is the glue's object (office.js): { host, onTheWeb, url(), readBytes(), setMark(id), removeMark(id),
 * insertDocId(docRef) }. host is "Word", "Excel" or "PowerPoint". readBytes resolves to the document as base64.
 * insertDocId gets "" for a document that isn't in Healdan yet, and inserts an empty field.
 */
(function (root) {
  "use strict";

  /** The helper's ports, tried in this order (AddinPorts.All in the helper, and the server's Content-Security-Policy). */
  const PORTS = [47615, 47616, 47617, 47618];

  /** The protocol this add-in speaks. The helper refuses one it doesn't, and says to update it. */
  const PROTOCOL = 1;

  const HOSTS = ["Word", "Excel", "PowerPoint"];

  /** localStorage's names: the key is kept per app (healdan.key.Word), and the port the helper answered on. */
  const KEY_PREFIX = "healdan.key.";
  const PORT_NAME = "healdan.port";

  /** How long a probe of one port waits for hello, in milliseconds. */
  const PROBE_TIMEOUT = 3000;

  // --- The pane's texts --------------------------------------------------------------------------------------------

  /**
   * What the pane says for each code: the helper's own codes, and the add-in's (unreachable, no-office-on-the-web, and
   * the ones it makes up itself). A function takes the details of the error (see messageFor). Plain, in British English.
   */
  const MESSAGES = {
    // The helper can't be reached.
    "unreachable":
      "Healdan Helper isn't running, or Microsoft Office isn't set up in it. Start Healdan Helper, and choose Set Up Microsoft Office in its menu.",
    "no-office-on-the-web":
      "Healdan's buttons work in the desktop apps only: Word, Excel and PowerPoint on your Mac. They can't reach Healdan Helper from a web browser.",

    // What the helper answers.
    "bad-host": "Healdan Helper turned the request away, because it didn't come from this computer.",
    "bad-origin": "Healdan Helper works with a different Healdan server than this one. Sign it in to this server.",
    "not-paired": "This app isn't paired with Healdan Helper yet. Pair it to go on.",
    "update-healdan-helper": "This add-in needs a newer Healdan Helper. Update Healdan Helper, then try again.",
    "signed-out": "Healdan Helper is signed out, or still starting up. Sign in to it, or wait a moment, then try again.",
    "not-a-copy":
      "This document isn't open from Healdan, so there's no version to add to. Use Save to Healdan to file it first.",
    "wrong-window":
      "Healdan Helper couldn't find the document you pressed that in. Click in the document, then press the button again.",
    "no-ticket": "Healdan Helper no longer has that save, because it was used or took too long. Press Save to Healdan again.",
    "wrong-code": (d) =>
      typeof d.triesLeft !== "number"
        ? "That code isn't right. Check it against the one in Healdan Helper's window."
        : d.triesLeft > 0
          ? `That code isn't right. You have ${d.triesLeft} ${d.triesLeft === 1 ? "try" : "tries"} left.`
          : "That code isn't right, and pairing is cancelled. Press the button again for a new code.",
    "pairing-expired": "That code has expired, or was cancelled. Press the button again for a new one.",
    "too-many-tries": "Too many wrong codes were typed, so pairing is paused for 10 minutes. Try again after that.",
    "too-large": "This document is too large for the add-in to send to Healdan Helper.",
    "bad-request": "Healdan Helper didn't understand the add-in. If it keeps happening, update Healdan Helper.",
    "internal-error": "Healdan Helper hit a problem. Its log has the details.",

    // The add-in's own.
    "code-format": "The code is six digits, as shown in Healdan Helper's window.",
    "storage-failed": "Microsoft Office wouldn't let the add-in keep its pairing, so this app can't be paired.",
    "unexpected-answer": "Healdan Helper answered with something the add-in didn't expect. Update Healdan Helper if it keeps happening.",
    "office-error": (d) =>
      `Microsoft Office didn't do what the add-in asked${d.detail ? ` (${d.detail})` : ""}. Try again.`,
    "read-only-version": "This is a read-only copy, so it can't be saved as a new version. Use Save as New Document instead.",
    "mark-left": "The document is filed, but the add-in couldn't take its hidden mark out of the document. It does no harm.",
    "not-filed-doc-id": "This document gets its doc ID once it's saved to Healdan, and the footer shows it from then on.",
    "local-save": (d) => `Use ${d.host ? `${d.host}'s` : "the app's"} own Save As to save it on this computer.`,
    "local-open": (d) => `Use ${d.host ? `${d.host}'s` : "the app's"} own Open to open a file on this computer.`,
  };

  /**
   * The pane's text for a code.
   * @param {string} code a helper code ("signed-out"), or one of the add-in's ("unreachable", "no-office-on-the-web")
   * @param {{triesLeft?: number, host?: string, detail?: string}} [details] what the text fills in
   */
  function messageFor(code, details) {
    const message = Object.prototype.hasOwnProperty.call(MESSAGES, code) ? MESSAGES[code] : null;
    if (message === null) {
      return `Healdan Helper answered with something the add-in doesn't know${code ? ` (${code})` : ""}.`;
    }
    return typeof message === "function" ? message(details || {}) : message;
  }

  /** A failure the pane can show: .code is the helper's, or the add-in's own; .message is the pane's text for it. */
  class HelperError extends Error {
    /**
     * @param {string} code
     * @param {{status?: number, triesLeft?: number, host?: string, detail?: string}} [details]
     */
    constructor(code, details) {
      super(messageFor(code, details));
      this.name = "HelperError";
      this.code = code;
      this.details = details || {};
      this.status = this.details.status;
      this.triesLeft = this.details.triesLeft;
    }
  }

  // --- The helper's port ----------------------------------------------------------------------------------------------

  function helperUrl(port, route) {
    return `https://127.0.0.1:${port}/v1/${route}`;
  }

  /** An origin to compare: its case and a trailing slash don't tell servers apart. */
  function plainOrigin(origin) {
    return String(origin).replace(/\/+$/, "").toLowerCase();
  }

  /**
   * A client for the helper's port. It finds the port, keeps the key the helper gave in `storage` as
   * "healdan.key.<host>" and the port as "healdan.port", and sends every request with the protocol and the key.
   *
   * @param {object} options
   * @param {Function} [options.fetch] the page's fetch (a fake in tests)
   * @param {Storage} [options.storage] the page's localStorage, which Office keeps for each app and the pane and the ribbon's buttons share
   * @param {string} [options.origin] the page's origin, which the helper must be signed in to: another server's helper is passed by
   * @param {string} options.host "Word", "Excel" or "PowerPoint"
   * @param {number} [options.probeTimeout] milliseconds to wait for each port's hello
   */
  function createHelperClient(options) {
    const host = options && options.host;
    if (!HOSTS.includes(host)) {
      throw new TypeError(`The add-in runs in Word, Excel or PowerPoint, not ${host}.`);
    }
    const doFetch = options.fetch || ((...args) => root.fetch(...args));
    const storage = options.storage || root.localStorage;
    const origin = options.origin === undefined ? root.location && root.location.origin : options.origin;
    const probeTimeout = options.probeTimeout || PROBE_TIMEOUT;
    const keyName = KEY_PREFIX + host;

    // Storage can refuse (a private window, cleared site data): then nothing is remembered, and nothing is paired.
    function read(name) {
      try {
        const value = storage.getItem(name);
        return value ? value : null;
      } catch (error) {
        return null;
      }
    }

    function write(name, value) {
      try {
        storage.setItem(name, value);
        return true;
      } catch (error) {
        return false;
      }
    }

    function erase(name) {
      try {
        storage.removeItem(name);
      } catch (error) {
        // Nothing was kept there to forget.
      }
    }

    function rememberedPort() {
      const port = Number(read(PORT_NAME));
      return PORTS.includes(port) ? port : null;
    }

    /** What the port's hello says, or null when nothing answers there, or what answers isn't this server's helper. */
    async function probe(port) {
      const controller = typeof AbortController === "function" ? new AbortController() : null;
      const timer = controller ? setTimeout(() => controller.abort(), probeTimeout) : null;
      try {
        const request = controller ? { method: "GET", signal: controller.signal } : { method: "GET" };
        const response = await doFetch(helperUrl(port, "hello"), request);
        if (!response.ok) {
          return null; // bad-origin, say: a helper signed in to another server, which a browser would not even let us read
        }
        const hello = await response.json();
        if (!hello || hello.name !== "Healdan Helper") {
          return null;
        }
        if (origin && !(typeof hello.server === "string" && plainOrigin(hello.server) === plainOrigin(origin))) {
          return null; // another Healdan's helper, or one that isn't signed in to any
        }
        return hello;
      } catch (error) {
        return null; // closed (WebKit: "Load failed"), not TLS, not JSON, or too slow
      } finally {
        if (timer !== null) {
          clearTimeout(timer);
        }
      }
    }

    /**
     * Asks each port in order for hello, and remembers the first that answers as this server's helper.
     * @returns {Promise<number>} the port
     * @throws {HelperError} "unreachable" when none answers; "update-healdan-helper" when the helper speaks no protocol this add-in does
     */
    async function find() {
      for (const port of PORTS) {
        const hello = await probe(port);
        if (hello === null) {
          continue;
        }
        const protocols = Array.isArray(hello.protocols) ? hello.protocols : [];
        if (!protocols.includes(PROTOCOL)) {
          throw new HelperError("update-healdan-helper");
        }
        write(PORT_NAME, String(port));
        return port;
      }
      erase(PORT_NAME);
      throw new HelperError("unreachable");
    }

    function send(port, route, body, key) {
      const headers = { "Content-Type": "application/json", "X-Healdan-Protocol": String(PROTOCOL) };
      if (key) {
        headers["X-Healdan-Key"] = key;
      }
      return doFetch(helperUrl(port, route), { method: "POST", headers, body: JSON.stringify(body || {}) });
    }

    /** The answer's JSON when it's a success; otherwise throws the HelperError for the helper's {"error": code}. */
    async function answerOf(response, keyed) {
      let body = null;
      try {
        body = await response.json();
      } catch (error) {
        // Not JSON: the status alone says it went wrong.
      }
      if (response.ok) {
        return body !== null && typeof body === "object" ? body : {};
      }
      const code = body && typeof body.error === "string" ? body.error : "internal-error";
      if (keyed && code === "not-paired") {
        erase(keyName); // the helper doesn't know our key (Forget in its Settings, say): the next press pairs again
      }
      throw new HelperError(code, { status: response.status, triesLeft: body ? body.triesLeft : undefined });
    }

    async function post(route, body, keyed) {
      const key = keyed ? read(keyName) : null;
      if (keyed && key === null) {
        throw new HelperError("not-paired");
      }
      const port = rememberedPort() === null ? await find() : rememberedPort();
      let response;
      try {
        response = await send(port, route, body, key);
      } catch (error) {
        // The helper may have been restarted onto another port: look again. It's repeated there only if it moved, as a
        // helper that still answers where it was may have taken the request already (a ticket is good once).
        erase(PORT_NAME);
        const moved = await find();
        if (moved === port) {
          throw new HelperError("unreachable");
        }
        try {
          response = await send(moved, route, body, key);
        } catch (again) {
          erase(PORT_NAME);
          throw new HelperError("unreachable");
        }
      }
      return answerOf(response, keyed);
    }

    return {
      host,
      find,

      /**
       * A keyed POST to /v1/<route>, answering the helper's JSON.
       * @throws {HelperError} with .code and .message for the pane
       */
      call: (route, body) => post(route, body, true),

      /** Starts pairing: the helper shows a six-digit code in its window. Resolves to the pairing's id. */
      async pairStart() {
        const answer = await post("pair/start", { app: host }, false);
        if (typeof answer.pairingId !== "string" || answer.pairingId === "") {
          throw new HelperError("unexpected-answer");
        }
        return answer.pairingId;
      },

      /** Finishes pairing with the code the user typed, and keeps the key the helper gives for this app. */
      async pairFinish(pairingId, code) {
        const typed = String(code === undefined || code === null ? "" : code).replace(/\s+/g, "");
        if (!/^[0-9]{6}$/.test(typed)) {
          throw new HelperError("code-format"); // not worth one of the three tries
        }
        const answer = await post("pair/finish", { pairingId, code: typed }, false);
        if (typeof answer.key !== "string" || answer.key === "") {
          throw new HelperError("unexpected-answer");
        }
        if (!write(keyName, answer.key)) {
          throw new HelperError("storage-failed");
        }
        return true;
      },

      /** Whether a key for this app is kept. */
      paired: () => read(keyName) !== null,
    };
  }

  // --- The six buttons -------------------------------------------------------------------------------------------------

  function failure(code, details) {
    return { ok: false, code, message: messageFor(code, details) };
  }

  /**
   * Runs one button's flow once the checks every flow starts with pass: not on the web, and paired. Whatever goes wrong
   * becomes a result the pane can show, so a flow never rejects.
   */
  async function run(args, work) {
    const { client, office } = args;
    if (office.onTheWeb) {
      return failure("no-office-on-the-web");
    }
    if (!client.paired()) {
      return { needsPairing: true };
    }
    try {
      return await work(office.host || client.host);
    } catch (error) {
      if (error instanceof HelperError) {
        return error.code === "not-paired" ? { needsPairing: true } : { ok: false, code: error.code, message: error.message };
      }
      return failure("office-error", { detail: error && error.message });
    }
  }

  /** The document's own location, as Office reports it: empty for one never saved. The helper decides which document is meant. */
  function urlOf(office) {
    return office.url() || "";
  }

  /** A one-time id for a document's mark. */
  function newMarkId() {
    const crypto = root.crypto;
    if (crypto && typeof crypto.randomUUID === "function") {
      return crypto.randomUUID();
    }
    let id = "";
    for (let i = 0; i < 32; i += 1) {
      id += Math.floor(Math.random() * 16).toString(16);
    }
    return id;
  }

  /** Takes the mark off, whatever happens; false when Office wouldn't. */
  async function removeMarkQuietly(office, id) {
    try {
      await office.removeMark(id);
      return true;
    } catch (error) {
      return false;
    }
  }

  /**
   * Save to Healdan, and Save as New Document (spec 5.5). The helper takes the front window and shows its Save window; on
   * Save, the add-in reads the document's bytes, then marks the document with a one-time id, so the helper can check it
   * is saving the window the button was pressed in, and sends the bytes, the mark and the window's ticket. The mark comes
   * off in every outcome. PowerPoint never shows a mark to AppleScript, and one would stay in its file, so it gets none.
   */
  async function saveNew(args, host, asNew) {
    const { client, office } = args;
    const answer = await client.call("save-new", { app: host, url: urlOf(office), asNew });
    if (answer.action === "cancel") {
      return { ok: true, cancelled: true };
    }
    if (answer.action === "local") {
      return { ok: true, local: true, message: messageFor("local-save", { host }) };
    }
    if (answer.action !== "save" || typeof answer.ticket !== "string" || answer.ticket === "") {
      throw new HelperError("unexpected-answer");
    }

    const bytes = await office.readBytes();
    const mark = host === "PowerPoint" ? null : newMarkId();
    let state;
    let markLeft = false;
    try {
      if (mark !== null) {
        await office.setMark(mark);
      }
      state = await client.call("file", { ticket: answer.ticket, mark, bytes });
    } finally {
      markLeft = mark !== null && !(await removeMarkQuietly(office, mark));
    }
    const filed = { ok: true, docRef: state.docRef };
    return markLeft ? { ...filed, message: messageFor("mark-left") } : filed;
  }

  /** Save to Healdan: a new document goes to Healdan, with the Save window's choices. */
  function saveToHealdan(args) {
    return run(args, (host) => saveNew(args, host, false));
  }

  /** Save as New Document: a Healdan document's window becomes a new document. */
  function saveAsNewDocument(args) {
    return run(args, (host) => saveNew(args, host, true));
  }

  /** Save as New Version (spec 5.6): the helper saves the front window's document as the copy's next version. */
  function saveAsNewVersion(args) {
    return run(args, async (host) => {
      const request = { app: host, url: urlOf(args.office) };
      const state = await args.client.call("state", request);
      if (!state.known) {
        return failure("not-a-copy");
      }
      if (state.readOnly) {
        return failure("read-only-version");
      }
      await args.client.call("new-version", request);
      return { ok: true, docRef: state.docRef };
    });
  }

  /** Properties: the helper's window. A document that isn't in Healdan goes on to Save to Healdan (spec 5.6). */
  function properties(args) {
    return run(args, async (host) => {
      const answer = await args.client.call("properties", { app: host, url: urlOf(args.office) });
      switch (answer.action) {
        case "file":
          return saveNew(args, host, false);
        case "saved":
          return { ok: true };
        case "cancel":
          return { ok: true, cancelled: true };
        default:
          throw new HelperError("unexpected-answer");
      }
    });
  }

  /** Open from Healdan: the helper's Open window, which opens the document in this app itself. */
  function openFromHealdan(args) {
    return run(args, async (host) => {
      const answer = await args.client.call("open-dialog", { app: host });
      switch (answer.action) {
        case "opened":
          return { ok: true };
        case "cancel":
          return { ok: true, cancelled: true };
        case "local":
          return { ok: true, local: true, message: messageFor("local-open", { host }) };
        default:
          throw new HelperError("unexpected-answer");
      }
    });
  }

  /** Insert Doc ID (Word): the footer shows the document's doc # and version, which the helper moves on after each save. */
  function insertDocId(args) {
    return run(args, async (host) => {
      const state = await args.client.call("state", { app: host, url: urlOf(args.office) });
      if (state.known && state.docRef) {
        await args.office.insertDocId(state.docRef);
        return { ok: true, docRef: state.docRef };
      }
      await args.office.insertDocId("");
      return { ok: true, message: messageFor("not-filed-doc-id") };
    });
  }

  const api = {
    PORTS,
    PROTOCOL,
    HelperError,
    createHelperClient,
    messageFor,
    saveToHealdan,
    saveAsNewDocument,
    saveAsNewVersion,
    properties,
    openFromHealdan,
    insertDocId,
  };

  root.Healdan = api;
  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
