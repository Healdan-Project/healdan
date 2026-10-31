/*
 * Healdan's Microsoft Office add-in: its logic (M3.5 spec section 6).
 *
 * The add-in is buttons and a pane; Healdan Helper, on the user's Mac, does the work. This file is everything the add-in
 * decides, with no Office and no page in it, so Node's own test runner can run it (clients/office/test/). The Office.js
 * glue (office-glue.js) and the pages sit on top of it, and call it as `Healdan` on the page's global.
 *
 * It is a classic script: no import, no npm. It sets `Healdan` on the global object, and `module.exports` under Node.
 *
 * What it holds:
 *   - createHelperClient: a client for the helper's HTTPS port on 127.0.0.1 (fetch and storage are passed in), which
 *     follows a route the helper is still running (202) through result, so each request stays short
 *   - messageFor, HelperError: the pane's texts, one table, for every code the helper or the add-in can answer with
 *   - messageLink, readMessageLink, windowMessage, readWindowMessage, noteOf, noteMessage, lastMessage, markBusy,
 *     clearBusy, busyNow: what the ribbon's buttons and the pane tell each other, which are pages of their own (the
 *     message window's address, its messages to and from the button, and notes in localStorage)
 *   - six flows, one for each button: saveToHealdan, saveAsNewDocument, saveAsNewVersion, properties,
 *     openFromHealdan and insertDocId. Each takes { client, office } and resolves, never rejects, to one of:
 *
 *       { needsPairing: true }                     this app isn't paired with the helper: the pane asks for a pairing code
 *       { ok: true }                               it did what was asked
 *       { ok: true, cancelled: true }              the user cancelled the helper's window
 *       { ok: true, local: true, code, message }   the user chose the app's own box: message says to use it
 *       { ok: true, docRef, code?, message? }      it did, and message is a notice for the user, if there is one
 *       { ok: false, code, message }               it couldn't: message is the pane's text for code
 *
 *     docRef is the document's "#1234 v1.2" form, when the flow knows it. A result with a message has its code, so the
 *     pane can show the text again in a window of its own, filled in for the app (see messageFor).
 *
 * `office` is the glue's object (office-glue.js): { host, onTheWeb, url(), readBytes(), setMark(id), removeMark(id),
 * insertDocId(docRef) }. host is "Word", "Excel" or "PowerPoint". readBytes resolves to the document as base64.
 * insertDocId gets "" for a document that isn't in Healdan yet, and inserts an empty field.
 */
(function (root) {
  "use strict";

  /** The helper's ports, tried in this order (AddinPorts.All in the helper, and the server's Content-Security-Policy). */
  const PORTS = [47615, 47616, 47617, 47618];

  /** A key as the helper makes them: 32 random bytes in base64url, which is 43 characters; more room is left for later. */
  const KEY_FORM = /^[A-Za-z0-9_-]{20,128}$/;

  /** The protocol this add-in speaks. The helper refuses one it doesn't, and says to update it. */
  const PROTOCOL = 1;

  const HOSTS = ["Word", "Excel", "PowerPoint"];

  /** localStorage's names: the key is kept per app (healdan.key.Word), and the port the helper answered on. */
  const KEY_PREFIX = "healdan.key.";
  const PORT_NAME = "healdan.port";

  /** How long a probe of one port waits for hello, in milliseconds. */
  const PROBE_TIMEOUT = 3000;

  /**
   * The helper's answer for a keyed route still running after its hold time (20 s), {"pending": id}: the add-in asks
   * result for the id, each request short again, until the route's own answer comes. WebKit is likely to give up on a
   * request that has had no answer for about a minute, and the helper's windows wait for the user.
   */
  const PENDING = 202;

  /**
   * The codes that, once a route went pending, mean the add-in couldn't learn its outcome, rather than the outcome: what
   * the checks in front of result answer (the helper's port, the origin, the key, the protocol, signed out), result's own
   * no-result, the add-in's unreachable and an answer it doesn't understand. A route's own errors (no-ticket,
   * wrong-window and the rest) aren't among them.
   */
  const NOT_THE_OUTCOME = new Set([
    "bad-host",
    "bad-origin",
    "not-paired",
    "update-healdan-helper",
    "signed-out",
    "no-result",
    "unreachable",
    "unexpected-answer",
  ]);

  // --- The pane's texts --------------------------------------------------------------------------------------------

  /**
   * What the pane says for each code: the helper's own codes, and the add-in's (unreachable, no-office-on-the-web, and
   * the ones it makes up itself). A function takes the details of the error (see messageFor). Plain, in British English.
   */
  const MESSAGES = {
    // The helper can't be reached.
    "unreachable":
      "Healdan Helper isn't running, or Microsoft Office isn't set up in it. If it is running, check it is signed in to this Healdan, and set up Microsoft Office from its menu if it offers to.",
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
    "too-many-pending": "Healdan Helper is still busy with this app's earlier requests. Wait for them to finish, then try again.",
    "bad-request": "Healdan Helper didn't understand the add-in. If it keeps happening, update Healdan Helper.",
    "internal-error": "Healdan Helper hit a problem. Its log has the details.",

    // The add-in's own.
    "code-format": "The code is six digits, as shown in Healdan Helper's window.",
    "storage-failed": "Microsoft Office wouldn't let the add-in keep its pairing, so this app can't be paired.",
    "unexpected-answer": "Healdan Helper answered with something the add-in didn't expect. Update Healdan Helper if it keeps happening.",
    "lost-result": "Healdan Helper finished, but its answer was lost. Check the document in Healdan before trying again.",
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

  /** The page's localStorage, or null where WebKit blocks storage and reading it throws a SecurityError. */
  function pageStorage() {
    try {
      return root.localStorage || null;
    } catch (error) {
      return null;
    }
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
    const storage = options.storage || pageStorage();
    const origin = options.origin === undefined ? root.location && root.location.origin : options.origin;
    const probeTimeout = options.probeTimeout || PROBE_TIMEOUT;
    const keyName = KEY_PREFIX + host;

    // Storage can refuse (a private window, cleared site data), or be missing altogether (null, where WebKit blocks it):
    // then nothing is remembered, and nothing is paired.
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

    /** The answer's JSON, or null when it isn't JSON. */
    async function jsonOf(response) {
      try {
        return await response.json();
      } catch (error) {
        return null; // the status alone says what happened
      }
    }

    /** The answer's JSON when it's a success; otherwise throws the HelperError for the helper's {"error": code}. */
    function answerOf(response, body, keyed) {
      if (response.ok) {
        return body !== null && typeof body === "object" ? body : {};
      }
      const code = body && typeof body.error === "string" ? body.error : "internal-error";
      if (keyed && code === "not-paired") {
        erase(keyName); // the helper doesn't know our key (Forget in its Settings, say): the next press pairs again
      }
      throw new HelperError(code, { status: response.status, triesLeft: body ? body.triesLeft : undefined });
    }

    /**
     * Sends one request and resolves to the helper's response. `repeatable` is for a request that can be sent twice
     * without doing anything twice (asking result): one that failed on the way is sent again where the helper answers.
     * `doneIfSent` is for one the helper may have carried out though its answer never came (file, which files the
     * document): one that failed on the way is lost-result, and isn't sent again.
     */
    async function request(route, body, keyed, repeatable, doneIfSent) {
      const key = keyed ? read(keyName) : null;
      if (keyed && key === null) {
        throw new HelperError("not-paired");
      }
      const port = rememberedPort() === null ? await find() : rememberedPort();
      try {
        return await send(port, route, body, key);
      } catch (error) {
        // The helper may have been restarted onto another port: look again. It's repeated there only if it moved, as a
        // helper that still answers where it was may have taken the request already (a ticket is good once), unless
        // it's repeatable: result answers an outcome once, so asking again gets it, or no-result if it was lost.
        erase(PORT_NAME);
        if (doneIfSent) {
          throw new HelperError("lost-result");
        }
        const moved = await find();
        if (moved === port && !repeatable) {
          throw new HelperError("unreachable");
        }
        try {
          return await send(moved, route, body, key);
        } catch (again) {
          erase(PORT_NAME);
          throw new HelperError("unreachable");
        }
      }
    }

    async function post(route, body, keyed) {
      const response = await request(route, body, keyed, false);
      return answerOf(response, await jsonOf(response), keyed);
    }

    /** The id a 202 names for result, or throws unexpected-answer. */
    function pendingId(answer) {
      const id = answer && answer.pending;
      if (typeof id !== "string" || id === "") {
        throw new HelperError("unexpected-answer");
      }
      return id;
    }

    /**
     * A keyed POST to /v1/<route>. A route the helper is still running after its hold time answers 202 {"pending": id}:
     * then result is asked for that id, again while it's still running, and its outcome is answered or thrown as the
     * route's own answer would have been.
     *
     * From the first 202 on, the route may have done what it was asked (a pending file may have filed the document),
     * and the add-in can't tell: "try again" could do it twice. So whatever stops it learning the outcome, as result
     * being refused, the helper signing out, or the helper not answering twice, is lost-result, which says to check the
     * document in Healdan. The route's own error answers are still its own. A file request that failed on the way is
     * lost-result too, as the helper may have filed the document before its answer was lost.
     */
    async function call(route, body) {
      const first = await request(route, body, true, false, route === "file");
      const firstAnswer = await jsonOf(first);
      if (first.status !== PENDING) {
        return answerOf(first, firstAnswer, true);
      }
      let id = pendingId(firstAnswer);
      try {
        for (;;) {
          const response = await request("result", { id }, true, true);
          const answer = await jsonOf(response);
          if (response.status !== PENDING) {
            return answerOf(response, answer, true);
          }
          id = pendingId(answer);
        }
      } catch (error) {
        throw error instanceof HelperError && NOT_THE_OUTCOME.has(error.code) ? new HelperError("lost-result") : error;
      }
    }

    return {
      host,
      find,

      /**
       * A keyed POST to /v1/<route>, answering the helper's JSON. A route still running after the helper's hold time is
       * followed through result to its outcome, so the caller sees only the route's own answer.
       * @throws {HelperError} with .code and .message for the pane
       */
      call,

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

      /**
       * The key kept for this app, or null. The message window hands it to the button that opened it (see
       * windowMessage), as the two may not share storage.
       */
      currentKey: () => read(keyName),

      /**
       * Keeps a key the message window paired with. Only one of the shape the helper makes them is taken.
       * @returns {boolean} whether it was kept
       */
      adoptKey(key) {
        return typeof key === "string" && KEY_FORM.test(key) && write(keyName, key);
      },
    };
  }

  // --- The six buttons -------------------------------------------------------------------------------------------------

  function failure(code, details) {
    const result = { ok: false, code, message: messageFor(code, details) };
    if (details && details.detail) {
      result.detail = String(details.detail); // what Office said: the window gives it again, with the same words
    }
    return result;
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
      return { ok: true, local: true, code: "local-save", message: messageFor("local-save", { host }) };
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
    return markLeft ? { ...filed, code: "mark-left", message: messageFor("mark-left") } : filed;
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
          return { ok: true, local: true, code: "local-open", message: messageFor("local-open", { host }) };
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
      return { ok: true, code: "not-filed-doc-id", message: messageFor("not-filed-doc-id") };
    });
  }

  // --- What the buttons tell the pane ---------------------------------------------------------------------------------
  //
  // A ribbon button's code runs in a page with nothing on it, so a message is shown in a window of its own: taskpane.html,
  // with the message's code and the app in its address, and nothing else: the page is fetched from the server, so its
  // address is in the server's logs, and what Office says about a failure can hold a file name. That text goes to the
  // window in a message (windowMessage). The pane and the buttons are one origin in one app, so they share its
  // localStorage, which holds two notes for the pane: the last message, and the flows still running.

  const MESSAGE_PAGE = "taskpane.html";

  /** localStorage's names for those notes: the last message, and "healdan.busy.<flow>" for each flow running. */
  const LAST_NAME = "healdan.last";
  const BUSY_PREFIX = "healdan.busy.";

  /** How often a running flow refreshes its note, and how old a note may be before the pane stops believing in it (ms). */
  const BUSY_BEAT = 5000;
  const BUSY_STALE = 15000;

  /** How much of what Office said is kept in an address or a note. */
  const DETAIL_LIMIT = 200;

  /** A code as the pane takes it from an address or a note: lower case words joined by hyphens, as every code is. */
  const CODE_FORM = /^[a-z][a-z-]{0,40}$/;

  /** What the pane calls each button's flow. */
  const FLOW_LABELS = {
    saveToHealdan: "Save to Healdan",
    openFromHealdan: "Open from Healdan",
    saveAsNewVersion: "Save as New Version",
    saveAsNewDocument: "Save as New Document",
    properties: "Properties",
    insertDocId: "Insert Doc ID",
  };

  function noteIn(storage, name) {
    try {
      const text = storage ? storage.getItem(name) : null;
      const value = text ? JSON.parse(text) : null;
      return value !== null && typeof value === "object" && !Array.isArray(value) ? value : null;
    } catch (error) {
      return null; // not there, not JSON, or storage refused
    }
  }

  function writeNote(storage, name, value) {
    try {
      if (storage) {
        storage.setItem(name, JSON.stringify(value));
      }
    } catch (error) {
      // A note the pane won't see: nothing depends on it.
    }
  }

  /** A note's fields that are for the pane to say: the code, and the app and detail when it has them. */
  function pickNote(source) {
    const note = { code: source.code };
    if (HOSTS.includes(source.app)) {
      note.app = source.app;
    }
    if (typeof source.detail === "string" && source.detail !== "") {
      note.detail = source.detail.slice(0, DETAIL_LIMIT);
    }
    return note;
  }

  /** The address of the window that shows a message: taskpane.html?message=<code>&app=<app>. Never what Office said. */
  function messageLink(note) {
    const query = new URLSearchParams();
    query.set("message", note.code);
    const picked = pickNote(note);
    if (picked.app) {
      query.set("app", picked.app);
    }
    return `${MESSAGE_PAGE}?${query}`;
  }

  /** The message an address (its location.search) asks for, or null when it asks for none. Nothing in it is trusted. */
  function readMessageLink(search) {
    const query = new URLSearchParams(String(search || ""));
    const code = query.get("message");
    if (code === null || !CODE_FORM.test(code)) {
      return null;
    }
    return pickNote({ code, app: query.get("app") }); // an address that carries a detail is not read for it
  }

  /**
   * A message between the message window and the button that opened it, as Office carries them (only strings): the
   * window says it is "ready" once it can hear the button; the button answers with the "detail" of what Office said, for
   * the message that needs it; and the window hands the button the "key" it "paired" with, as it can't count on sharing
   * storage with the button's page.
   * @param {{kind: "ready"} | {kind: "detail", detail: string} | {kind: "paired", app: string, key: string}} message
   */
  function windowMessage(message) {
    switch (message.kind) {
      case "ready":
        return JSON.stringify({ healdan: "ready" });
      case "detail":
        return JSON.stringify({ healdan: "detail", detail: message.detail });
      case "paired":
        return JSON.stringify({ healdan: "paired", app: message.app, key: message.key });
      default:
        throw new TypeError(`A window message has no kind ${message.kind}.`);
    }
  }

  /** What a window message says, as { kind, … }, or null when it is not one. Nothing in it is trusted. */
  function readWindowMessage(text) {
    let value;
    try {
      value = typeof text === "string" ? JSON.parse(text) : null;
    } catch (error) {
      return null;
    }
    if (value === null || typeof value !== "object" || Array.isArray(value)) {
      return null;
    }
    switch (value.healdan) {
      case "ready":
        return { kind: "ready" };
      case "detail":
        return typeof value.detail === "string" && value.detail !== ""
          ? { kind: "detail", detail: value.detail.slice(0, DETAIL_LIMIT) }
          : null;
      case "paired":
        return HOSTS.includes(value.app) && typeof value.key === "string" && KEY_FORM.test(value.key)
          ? { kind: "paired", app: value.app, key: value.key }
          : null;
      default:
        return null;
    }
  }

  /**
   * What the window should show for a flow's result, as { code, app, detail? }, or null when there's nothing to say: the
   * flow did what was asked, or the user cancelled.
   */
  function noteOf(result, app) {
    let code = null;
    if (result.needsPairing) {
      code = "not-paired";
    } else if (!result.ok || result.message) {
      code = result.code;
    }
    return typeof code === "string" ? pickNote({ code, app, detail: result.detail }) : null;
  }

  /** Keeps a message as the last one, for the pane. */
  function noteMessage(storage, note, now) {
    writeNote(storage, LAST_NAME, { ...pickNote(note), at: now });
  }

  /** The last message kept, as { code, app?, detail?, at }, or null. */
  function lastMessage(storage) {
    const value = noteIn(storage, LAST_NAME);
    if (value === null || typeof value.code !== "string" || !CODE_FORM.test(value.code) || !Number.isFinite(value.at)) {
      return null;
    }
    return { ...pickNote(value), at: value.at };
  }

  /** Marks a flow as running; called again every BUSY_BEAT while it is, so a page that is gone leaves a note that goes stale. */
  function markBusy(storage, flow, app, now) {
    if (Object.prototype.hasOwnProperty.call(FLOW_LABELS, flow)) {
      writeNote(storage, BUSY_PREFIX + flow, { app, at: now });
    }
  }

  function clearBusy(storage, flow) {
    try {
      if (storage) {
        storage.removeItem(BUSY_PREFIX + flow);
      }
    } catch (error) {
      // Nothing was kept there to forget.
    }
  }

  /** The flow running now, newest first, as { flow, label, app }, or null. */
  function busyNow(storage, now) {
    let found = null;
    for (const flow of Object.keys(FLOW_LABELS)) {
      const value = noteIn(storage, BUSY_PREFIX + flow);
      if (value !== null && Number.isFinite(value.at) && now - value.at <= BUSY_STALE && (found === null || value.at > found.at)) {
        found = { flow, label: FLOW_LABELS[flow], app: HOSTS.includes(value.app) ? value.app : undefined, at: value.at };
      }
    }
    return found === null ? null : { flow: found.flow, label: found.label, app: found.app };
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
    FLOW_LABELS,
    BUSY_BEAT,
    BUSY_STALE,
    messageLink,
    readMessageLink,
    windowMessage,
    readWindowMessage,
    noteOf,
    noteMessage,
    lastMessage,
    markBusy,
    clearBusy,
    busyNow,
    storage: pageStorage,
  };

  root.Healdan = api;
  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
