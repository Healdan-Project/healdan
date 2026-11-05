// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

/*
 * Healdan's Outlook add-in: the pane (M5 spec sections 8 and 10).
 *
 * mail.html is the pane Outlook shows beside the message being read. It says whether the message is filed in Healdan
 * and where, offers the folders it was filed near before as File Here buttons, and File… for the Save window. It holds
 * no token: it talks to Healdan Helper on the user's Mac over its loopback port (healdan.js), which reads nothing from
 * Healdan itself but what the helper answers.
 *
 * Whatever the helper says (a name, a folder, an address) is put on the page as text, never as HTML; an address becomes
 * a link only when it is an http or https one. Nothing here logs a subject, an address or a name.
 *
 * Several messages at once are not handled yet: with none or more than one open, the pane says to select one.
 *
 * It works in Outlook for Mac only, where the helper is. Outlook on the web and new Outlook for Windows have Mailbox 1.14
 * too, so the button shows there, and the pane then says it works in the new Outlook for Mac only and asks nothing.
 *
 * It is a classic script: no import, no npm. Under Node (clients/office/test/mail.test.js) it sets only
 * `module.exports`; on a page it starts itself. The logic (threadIds, statusView, outcomeView, fileMessage) takes the
 * helper's client and the glue (mail-glue.js) as arguments, so the tests give it fakes.
 */
(function (root) {
  "use strict";

  /** Milliseconds between one failed check and the next, so the pane recovers when the helper starts. */
  const POLL = 5000;

  /** At most this many ids go to mail/status (the helper's limit); it caps them itself at 21. */
  const MAX_IDS = 100;

  /** At most this many suggestions are shown. */
  const MAX_SUGGESTIONS = 5;

  /** What the pane says in an Outlook that isn't on a Mac, where there is no Healdan Helper to reach. */
  const MAC_ONLY = "For now, Healdan's Outlook pane works in the new Outlook for Mac only, with Healdan Helper on the Mac.";

  /** The words for a helper that couldn't reach Healdan, or couldn't read the message (internal-error). */
  const CANT_REACH =
    "Can't Reach Healdan. Healdan Helper couldn't get an answer from the server, or couldn't read this message. Check that it is signed in, then try again.";

  // --- Message-IDs -------------------------------------------------------------------------------------------------------

  /** The ids in a header's value: those between angle brackets, or else the words it is made of. */
  function idsIn(value) {
    const bracketed = [...value.matchAll(/<([^<>\s]+)>/g)].map((m) => m[1]);
    return bracketed.length > 0 ? bracketed : value.split(/\s+/).filter((word) => word !== "");
  }

  /**
   * The ids of the message and its thread, for mail/status: its own first, then In-Reply-To's, then References's, oldest
   * first as the header has them. Angle brackets are kept off and an id appears once. Past MAX_IDS, the oldest References
   * are left out, as the helper asks about the newest.
   * @param {string} internetMessageId the message's own, with or without angle brackets
   * @param {string} headersText the message's internet headers
   * @returns {string[]} empty when the message has no id of its own
   */
  function threadIds(internetMessageId, headersText) {
    const own = String(internetMessageId || "").trim().replace(/^<|>$/g, "").trim();
    if (own === "") {
      return [];
    }
    const unfolded = String(headersText || "").replace(/\r?\n[ \t]+/g, " ");
    const replyTo = [];
    const references = [];
    for (const line of unfolded.split(/\r?\n/)) {
      const match = /^(in-reply-to|references)[ \t]*:(.*)$/i.exec(line);
      if (match) {
        (match[1].toLowerCase() === "references" ? references : replyTo).push(...idsIn(match[2]));
      }
    }
    const ids = [...new Set([own, ...replyTo, ...references])];
    if (ids.length <= MAX_IDS) {
      return ids;
    }
    const lead = Math.min(new Set([own, ...replyTo]).size, MAX_IDS);
    return [...ids.slice(0, lead), ...ids.slice(ids.length - (MAX_IDS - lead))];
  }

  // --- What the pane says --------------------------------------------------------------------------------------------------

  /**
   * Whether the pane can work where Outlook runs: on a Mac, where the helper is. Outlook names its platform from Mailbox
   * 1.5, and the manifest asks for 1.14, so one that doesn't name it is taken for the Mac rather than turned away.
   * @param {string} platform the glue's platform(): "Mac", "PC", "OfficeOnline", …, or ""
   */
  function worksHere(platform) {
    return typeof platform !== "string" || platform === "" || platform === "Mac";
  }

  function copyLine(copy) {
    // "<matter> › <folder>, #<docNumber>" (M5 spec section 8.2), the matter as the helper gives it; the doc # is the link.
    const where = [copy.matter, copy.folder].filter((part) => typeof part === "string" && part !== "").join(" › ");
    const label = copy.docNumber === undefined || copy.docNumber === null ? "" : `#${copy.docNumber}`;
    const before = where !== "" && label !== "" ? `${where}, ` : where;
    return {
      text: `${before}${label}`,
      before,
      label,
      address: typeof copy.address === "string" ? copy.address : "",
    };
  }

  /**
   * What mail/status's answer shows.
   * @returns {{kind: "filed"|"elsewhere"|"not-filed", lines: {text: string, before: string, label: string, address: string}[],
   *   suggestions: {folderId: number, label: string}[]}}
   */
  function statusView(answer) {
    const a = answer && typeof answer === "object" ? answer : {};
    const copies = Array.isArray(a.copies) ? a.copies : [];
    const suggestions = (Array.isArray(a.suggestions) ? a.suggestions : [])
      .filter((s) => s && typeof s.folderId === "number" && typeof s.path === "string")
      .slice(0, MAX_SUGGESTIONS)
      .map((s) => ({ folderId: s.folderId, label: s.path }));
    // The helper says elsewhere only of a message that is filed: its thread went to a matter none of its copies is in.
    const kind = a.elsewhere ? "elsewhere" : a.filed ? "filed" : "not-filed";
    return { kind, lines: copies.map(copyLine), suggestions };
  }

  /** The status line's words for a kind. */
  const KIND_TITLES = { filed: "Filed", elsewhere: "Filed in Another Matter", "not-filed": "Not Filed" };

  function filedAs(email) {
    return email && typeof email === "object" ? copyLine(email).text : "";
  }

  /**
   * What mail/file's answer shows.
   * @returns {{kind: "ok"|"problem", text: string, categorise: boolean}} categorise: the message is filed, so it gets the category
   */
  function outcomeView(answer) {
    const outcome = answer && answer.outcome;
    if (outcome === "filed") {
      const what = filedAs(answer.email);
      return { kind: "ok", text: what === "" ? "Filed." : `Filed as ${what}.`, categorise: true };
    }
    if (outcome === "already-filed") {
      const what = filedAs(answer.email);
      return { kind: "ok", text: what === "" ? "Already Filed." : `Already Filed as ${what}.`, categorise: true };
    }
    if (outcome === "cancelled") {
      return { kind: "ok", text: "Nothing was filed.", categorise: false };
    }
    return { kind: "problem", text: "Healdan Helper answered with something the add-in didn't expect.", categorise: false };
  }

  /** The words for a helper error: internal-error is the helper not reaching Healdan or not reading the message. */
  function failureText(error) {
    if (error && error.code === "internal-error") {
      return CANT_REACH;
    }
    return error && typeof error.message === "string" && typeof error.code === "string"
      ? error.message
      : "Healdan Helper answered with something the add-in didn't expect.";
  }

  /** What a click on a button for a message that is no longer the open one says. */
  const CHANGED = "This message changed. Choose again.";

  /** Whether two items are the same message: the same object, or the same item id (or else the same Message-ID). */
  function sameItem(a, b) {
    if (!a || !b) {
      return false;
    }
    if (a === b) {
      return true;
    }
    if (a.itemId || b.itemId) {
      return a.itemId === b.itemId;
    }
    return Boolean(a.internetMessageId) && a.internetMessageId === b.internetMessageId;
  }

  // --- Filing --------------------------------------------------------------------------------------------------------------

  /**
   * Files the message being read.
   * @param {object} client the helper client (healdan.js)
   * @param {object} glue mail-glue.js's
   * @param {number} [folderId] File Here's folder; without it, the helper's Save window opens (File…)
   * @param {number} [openAt] where File…'s window opens: the first suggestion's folder
   * @param {object} [chosen] the message the person chose it for, taken at the click; refused when another is open now
   * @returns {Promise<{kind: "ok"|"problem", text: string, moved?: boolean}>} never rejects; moved: another message is open
   *   now, and the text says which message it is about
   */
  async function fileMessage(client, glue, folderId, openAt, chosen) {
    const item = chosen || glue.item();
    if (!item) {
      return { kind: "problem", text: "Select a message to file it." };
    }
    if (!sameItem(item, glue.item())) {
      return { kind: "problem", text: CHANGED };
    }
    let eml;
    try {
      eml = await glue.readEml(item);
    } catch (error) {
      return { kind: "problem", text: "Outlook wouldn't give Healdan this message. Try again." };
    }
    const body = { eml };
    if (typeof folderId === "number") {
      body.folderId = folderId;
    } else if (typeof openAt === "number") {
      body.openAt = openAt;
    }
    let view;
    try {
      view = outcomeView(await client.call("mail/file", body));
    } catch (error) {
      return { kind: "problem", text: failureText(error) };
    }
    const now = glue.item();
    if (view.categorise && !sameItem(item, now)) {
      // Filing can take as long as the person's own Save window: the pane may have moved on. The category would land on
      // the wrong message, so it isn't added, and the pane, showing the other message now, says which this is about.
      return {
        kind: view.kind,
        text: `The message you chose before this one: ${view.text} It has no Healdan category, as another message was open by then.`,
        moved: true,
      };
    }
    if (view.categorise) {
      // The same message, but maybe not the same object: after A, B and A again, Office.js has given mailbox.item a new
      // one, and the one taken at the click may be stale. The category goes on the one open now.
      await categorise(glue, now);
    }
    return { kind: view.kind, text: view.text };
  }

  /**
   * Gives the message the category Healdan. Outlook refuses a category the mailbox's list lacks (the trial's T5), so the
   * list gets it first when it's missing. The filing counts all the same: a failure at either step is only logged, with
   * Outlook's code and nothing else.
   */
  async function categorise(glue, item) {
    let step = "read the category list";
    try {
      const names = await glue.masterCategories();
      if (!names.includes("Healdan")) {
        step = "add the category to the list";
        await glue.addMasterCategory();
      }
      step = "add the category";
      await glue.addCategory(item);
    } catch (error) {
      if (typeof console !== "undefined") {
        console.warn(`Healdan: Outlook wouldn't ${step} (code ${error && error.code}).`);
      }
    }
  }

  // --- The page ------------------------------------------------------------------------------------------------------------

  function put(element, text) {
    if (element.textContent !== text) {
      element.textContent = text;
    }
  }

  function show(element, shown) {
    if (element.hidden === shown) {
      element.hidden = !shown;
    }
  }

  /** Whether an address is one to link to. */
  function linkable(address) {
    return /^https?:\/\/[^\s]+$/i.test(address);
  }

  /**
   * Runs the pane on a page.
   * @param {object} env what the page has, so tests can fake it
   * @param {object} env.document the page's document, with the ids mail.html has
   * @param {object} env.Healdan healdan.js's API
   * @param {object} env.glue mail-glue.js's
   * @param {Function} env.createClient () => a helper client
   * @param {Function} env.setTimeout
   * @param {Function} env.clearTimeout
   * @returns {Promise<object>} what tests look at: { refresh, stop }
   */
  async function start(env) {
    const { document, Healdan, glue } = env;
    const $ = (id) => document.getElementById(id);
    const els = {
      main: $("main"),
      status: $("status"),
      copies: $("copies"),
      busySection: $("busy-section"),
      busy: $("busy-text"),
      outcome: $("outcome"),
      actions: $("actions-section"),
      suggestions: $("suggestions"),
      other: $("file-other"),
      pairing: $("pairing"),
      help: $("pairing-help"),
      code: $("code"),
      pair: $("pair"),
      problem: $("pairing-problem"),
    };
    if (!worksHere(glue.platform())) {
      // Outlook on the web or on Windows: no helper to reach, so the pane asks it nothing, and says why it can't file.
      els.status.textContent = MAC_ONLY;
      els.status.className = "status problem";
      els.main.setAttribute("aria-busy", "false");
      return { async refresh() {}, stop() {} };
    }
    const client = env.createClient();
    let stopped = false;
    let timer = null;
    let view = null; // the last status shown, for File…'s openAt
    let shown = null; // the message that status describes
    let filing = false;

    function setStatus(kind, text) {
      put(els.status, text);
      if (els.status.className !== `status ${kind}`) {
        els.status.className = `status ${kind}`;
      }
    }

    function setBusy(text) {
      put(els.busy, text);
      show(els.busySection, text !== "");
      els.main.setAttribute("aria-busy", text === "" ? "false" : "true");
    }

    function setButtons(disabled) {
      els.other.disabled = disabled;
      for (const button of els.suggestions.children) {
        button.disabled = disabled;
      }
    }

    function renderCopies(lines) {
      els.copies.replaceChildren();
      for (const line of lines) {
        const item = document.createElement("li");
        if (linkable(line.address) && line.label !== "") {
          const before = document.createElement("span");
          before.textContent = line.before;
          const link = document.createElement("a");
          link.href = line.address;
          link.target = "_blank";
          link.rel = "noopener noreferrer";
          link.textContent = line.label;
          item.appendChild(before);
          item.appendChild(link);
        } else {
          item.textContent = line.text;
        }
        els.copies.appendChild(item);
      }
      show(els.copies, lines.length > 0);
    }

    function renderSuggestions(suggestions) {
      els.suggestions.replaceChildren();
      for (const suggestion of suggestions) {
        const button = document.createElement("button");
        button.type = "button";
        const label = document.createElement("span");
        label.textContent = "File Here";
        const where = document.createElement("span");
        where.className = "where";
        where.textContent = suggestion.label;
        button.appendChild(label);
        button.appendChild(where);
        button.disabled = filing;
        button.addEventListener("click", () => file(suggestion.folderId));
        els.suggestions.appendChild(button);
      }
    }

    /** What the pane shows when nothing about a message is known: only the status line. */
    function showOnly(kind, text) {
      view = null;
      shown = null;
      setStatus(kind, text);
      renderCopies([]);
      show(els.actions, false);
      show(els.pairing, false);
      setBusy("");
    }

    function render(next, item) {
      view = next;
      shown = item;
      setStatus(next.kind === "filed" ? "ok" : "waiting", KIND_TITLES[next.kind]);
      renderCopies(next.lines);
      renderSuggestions(next.kind === "filed" ? [] : next.suggestions);
      show(els.actions, true);
      show(els.pairing, false);
      setBusy("");
    }

    // --- The check, one at a time and the latest winning

    let running = false;
    let queued = false;

    async function once() {
      const item = glue.item();
      if (!item) {
        showOnly("waiting", "Select one message to file it.");
        return;
      }
      let ids = [];
      try {
        let headers = "";
        try {
          headers = await glue.headers(item);
        } catch (error) {
          // A thread's ids are a help, not a need.
        }
        ids = threadIds(item.internetMessageId, headers);
        if (ids.length === 0) {
          // No Message-ID to look the message up by, but File… still needs a paired, signed-in helper: ping says so.
          const ping = await client.call("ping", {});
          if (ping && ping.signedIn === false) {
            throw new Healdan.HelperError("signed-out");
          }
          if (!queued && sameItem(item, glue.item())) {
            render({ kind: "not-filed", lines: [], suggestions: [] }, item);
          }
          return;
        }
        const answer = await client.call("mail/status", { messageIds: ids });
        if (!queued && sameItem(item, glue.item())) {
          render(statusView(answer), item);
        }
      } catch (error) {
        if (queued) {
          return;
        }
        const code = error && error.code;
        if (code === "not-paired") {
          showOnly("waiting", "Outlook isn't paired with Healdan Helper yet.");
          show(els.pairing, true);
          if (!pairing) {
            await startPairing();
          }
          return;
        }
        if (code === "internal-error") {
          // The helper is there; File… may still work (spec section 10).
          setStatus("problem", CANT_REACH);
          renderCopies([]);
          renderSuggestions([]);
          view = null;
          shown = item;
          show(els.actions, true);
          show(els.pairing, false);
          setBusy("");
        } else {
          showOnly("problem", failureText(error));
        }
        schedule();
      }
    }

    async function refresh() {
      if (stopped) {
        return;
      }
      env.clearTimeout(timer);
      if (running) {
        queued = true;
        return;
      }
      running = true;
      try {
        do {
          queued = false;
          await once();
        } while (queued && !stopped);
      } finally {
        running = false;
      }
    }

    function schedule() {
      if (!stopped) {
        env.clearTimeout(timer);
        timer = env.setTimeout(refresh, POLL);
      }
    }

    // --- Filing

    async function file(folderId) {
      if (filing) {
        return;
      }
      // Buttons belong to the message they were drawn for: one pressed after another was opened files nothing.
      const item = glue.item();
      if (!item || shown === null || !sameItem(shown, item)) {
        put(els.outcome, CHANGED);
        els.outcome.className = "status problem";
        show(els.outcome, true);
        return;
      }
      filing = true;
      setButtons(true);
      els.outcome.hidden = true;
      setBusy("Filing the message… If Healdan Helper has a window open, finish there.");
      const openAt = view && view.suggestions.length > 0 ? view.suggestions[0].folderId : undefined;
      let result;
      try {
        result = await fileMessage(client, glue, folderId, openAt, item);
      } catch (error) {
        result = { kind: "problem", text: failureText(error) };
      } finally {
        filing = false;
        setBusy("");
        setButtons(false);
      }
      // The outcome belongs to the message it describes: it is not left on the next one, but for a filing that left the
      // category off because another message was open by then, whose words say which message it is about.
      if (result.moved || sameItem(item, glue.item())) {
        put(els.outcome, result.text);
        els.outcome.className = `status ${result.kind}`;
        show(els.outcome, true);
      }
      await refresh();
    }

    els.other.addEventListener("click", () => file(undefined));

    // --- Pairing

    let pairingId = null;
    let pairing = false;

    async function startPairing() {
      pairing = true;
      put(els.problem, "");
      try {
        pairingId = await client.pairStart();
        put(els.help, "Healdan Helper is showing a six-digit code in a window on your Mac. Type it here, then choose Pair.");
        els.code.focus();
      } catch (error) {
        pairingId = null;
        put(els.help, "Choose Pair to ask Healdan Helper for a code.");
        put(els.problem, failureText(error));
      }
    }

    async function finishPairing() {
      if (pairingId === null) {
        await startPairing();
        return;
      }
      put(els.problem, "");
      try {
        await client.pairFinish(pairingId, els.code.value);
      } catch (error) {
        put(els.problem, failureText(error));
        if (error.code === "pairing-expired" || (error.code === "wrong-code" && error.triesLeft === 0)) {
          pairingId = null;
          put(els.help, "Choose Pair to ask Healdan Helper for a new code.");
        }
        els.code.select();
        return;
      }
      pairingId = null;
      pairing = false;
      els.code.value = "";
      await refresh();
    }

    els.pairing.addEventListener("submit", (event) => {
      event.preventDefault();
      finishPairing();
    });

    // A pinned pane stays open while the message changes. Several selected at once come as the same event with no item.
    try {
      await glue.onItemChanged(() => {
        // The buttons and the outcome were for the message that was open: gone at once, before the new one's status lands.
        view = null;
        shown = null;
        renderCopies([]);
        renderSuggestions([]);
        show(els.actions, false);
        show(els.outcome, false);
        setStatus("waiting", "Checking Healdan…");
        refresh();
      });
    } catch (error) {
      // Without the event the pane still checks the message it opened on.
    }

    await refresh();
    els.main.setAttribute("aria-busy", "false");
    return {
      refresh,
      stop() {
        stopped = true;
        env.clearTimeout(timer);
      },
    };
  }

  const api = { threadIds, statusView, outcomeView, fileMessage, start, POLL, CANT_REACH, MAC_ONLY };

  if (root.document && typeof root.document.getElementById === "function" && root.document.getElementById("file-other")) {
    const begin = () => {
      start({
        document: root.document,
        Healdan: root.Healdan,
        glue: root.HealdanMailGlue.create(),
        createClient: () => root.Healdan.createHelperClient({ host: "Outlook" }),
        setTimeout: (...args) => root.setTimeout(...args),
        clearTimeout: (...args) => root.clearTimeout(...args),
      });
    };
    if (root.Office && typeof root.Office.onReady === "function") {
      root.Office.onReady().then(begin);
    } else {
      root.document.getElementById("status").textContent = "This page is the Healdan add-in's pane. It works inside Outlook.";
    }
  }
  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
