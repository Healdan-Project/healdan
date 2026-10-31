/*
 * Healdan's Microsoft Office add-in: the pane (M3.5 spec section 6).
 *
 * taskpane.html is two things. Opened by Office as the add-in's task pane, it shows the last message the buttons gave,
 * Healdan Helper's status and, until the app has paired, the field for the pairing code. Opened by a button as a window
 * (taskpane.html?message=<code>&app=<app>, through displayDialogAsync: the manifest declares no shared runtime, so a
 * button can't open the pane itself), it shows that message first, and the same pairing field when the message is that
 * the app isn't paired. It has no buttons but Pair.
 *
 * Pairing: the helper shows a six-digit code in a window of its own when the page asks (pair/start), the user types it
 * here, and Pair finishes it (pair/finish). Pressing Pair with no pairing under way, after a code expired or ran out of
 * tries, asks for a new code instead.
 *
 * The status is checked every few seconds, one check at a time and the next only after the last has answered, so a slow
 * helper (it can be busy with a window of its own) is waited for, not piled on. Only hello and ping are asked: state
 * would have the helper drive Word by AppleScript at every check. While a button's flow is waiting on the helper, the
 * ribbon's page leaves a note in localStorage (healdan.js, markBusy), and the pane says so.
 *
 * It is a classic script: no import, no npm. Under Node (clients/office/test/taskpane.test.js) it sets only
 * `module.exports`; on a page it starts itself.
 */
(function (root) {
  "use strict";

  /** Milliseconds between one status check ending and the next beginning. */
  const POLL = 5000;

  /** A check or a pairing request this slow says so. */
  const SLOW = 8000;

  /** How often the note that a flow is busy is looked at again. */
  const BUSY_LOOK = 2000;

  /** How long to wait for Office.js to say which app this is, before going on without it. */
  const OFFICE_WAIT = 3000;

  const HOSTS = ["Word", "Excel", "PowerPoint"];

  // --- What the pane says ----------------------------------------------------------------------------------------------

  /**
   * The status line.
   * @param {object} Healdan healdan.js's API
   * @param {{app: string, checked: boolean, slow?: boolean, error?: string, paired?: boolean, signedIn?: boolean}} state
   * @returns {{kind: "waiting"|"ok"|"problem", text: string}}
   */
  function statusFor(Healdan, state) {
    if (!state.checked) {
      return { kind: "waiting", text: state.slow ? "Healdan Helper is taking a while to answer…" : "Checking Healdan Helper…" };
    }
    if (state.error) {
      return { kind: "problem", text: Healdan.messageFor(state.error) };
    }
    if (!state.paired) {
      return { kind: "ok", text: `Healdan Helper is running. ${state.app} isn't paired with it yet.` };
    }
    if (state.signedIn === false) {
      return { kind: "problem", text: Healdan.messageFor("signed-out") };
    }
    return { kind: "ok", text: `Healdan Helper is running, and ${state.app} is paired with it.` };
  }

  /** The line shown while a flow waits on the helper. */
  function busyText(busy) {
    return `Healdan is working on ${busy.label}${busy.app ? ` in ${busy.app}` : ""}. If Healdan Helper has a window open, finish there, and this goes on when you do.`;
  }

  /** When a message was given, as the user's computer writes a date and time. */
  function whenText(at, locale) {
    try {
      return new Date(at).toLocaleString(locale, { dateStyle: "medium", timeStyle: "short" });
    } catch (error) {
      return new Date(at).toISOString();
    }
  }

  /** The text of a message note (what messageLink or a last message holds). */
  function noteText(Healdan, note, app) {
    return Healdan.messageFor(note.code, { host: note.app || app, detail: note.detail });
  }

  /** What a failure from the client says: a HelperError its own text, anything else as an answer nobody expected. */
  function failureText(Healdan, error) {
    return error instanceof Healdan.HelperError ? error.message : Healdan.messageFor("unexpected-answer");
  }

  // --- Office ------------------------------------------------------------------------------------------------------------

  /** Office.js's word on the app and the platform, or null if it doesn't give it in time (a browser tab, say). */
  function officeInfo(env) {
    const Office = env.Office;
    if (!Office || typeof Office.onReady !== "function") {
      return Promise.resolve(null);
    }
    return new Promise((resolve) => {
      const timer = env.setTimeout(() => resolve(null), OFFICE_WAIT);
      Promise.resolve(Office.onReady()).then(
        (info) => {
          env.clearTimeout(timer);
          resolve(info || null);
        },
        () => {
          env.clearTimeout(timer);
          resolve(null);
        },
      );
    });
  }

  // --- The page ----------------------------------------------------------------------------------------------------------

  /**
   * Runs the pane on a page.
   * @param {object} env what the page has, so tests can fake it
   * @param {object} env.document the page's document, with the ids taskpane.html has
   * @param {object} env.Healdan healdan.js's API
   * @param {object} [env.Office] Office.js's global
   * @param {{search: string}} env.location
   * @param {Function} env.createClient (host) => a helper client
   * @param {Function} env.now milliseconds
   * @param {Function} env.setTimeout
   * @param {Function} env.clearTimeout
   * @param {Function} env.onStorage called with a function to run when another page changes localStorage
   * @returns {Promise<object>} what tests look at: { stop }
   */
  async function start(env) {
    const { document, Healdan } = env;
    const $ = (id) => document.getElementById(id);
    const els = {
      main: $("main"),
      web: $("web"),
      messageSection: $("message-section"),
      messageTitle: $("message-title"),
      message: $("message"),
      messageWhen: $("message-when"),
      busySection: $("busy-section"),
      busy: $("busy-text"),
      statusSection: $("status-section"),
      status: $("status"),
      done: $("done"),
      pairing: $("pairing"),
      pairingApp: $("pairing-app"),
      help: $("pairing-help"),
      code: $("code"),
      pair: $("pair"),
      problem: $("pairing-problem"),
    };
    const storage = Healdan.storage();
    const link = Healdan.readMessageLink(env.location.search);

    // Which app: the window's address says (Office may not, in a window of its own), and otherwise Office does.
    let app = link && link.app;
    let onTheWeb = false;
    if (!app) {
      const info = await officeInfo(env);
      app = info && HOSTS.includes(info.host) ? info.host : undefined;
      onTheWeb = Boolean(info && env.Office && env.Office.PlatformType && info.platform === env.Office.PlatformType.OfficeOnline);
    }

    function show(element, shown) {
      element.hidden = !shown;
    }

    // --- The message, or the last one

    function showMessage(note, title, when) {
      els.messageTitle.textContent = title;
      els.message.textContent = noteText(Healdan, note, app);
      els.messageWhen.textContent = when ? `Given ${when}.` : "";
      show(els.messageWhen, Boolean(when));
      show(els.messageSection, true);
    }

    if (link !== null) {
      showMessage(link, "Message", "");
    } else {
      const last = Healdan.lastMessage(storage);
      if (last !== null) {
        showMessage(last, "Last Message", whenText(last.at));
      }
    }

    if (onTheWeb) {
      els.web.textContent = Healdan.messageFor("no-office-on-the-web");
      show(els.web, true);
      show(els.statusSection, false);
      els.main.setAttribute("aria-busy", "false");
      return { stop() {} };
    }
    if (!app) {
      els.web.textContent = "This page is the Healdan add-in's pane. It works inside Word, Excel and PowerPoint.";
      show(els.web, true);
      show(els.statusSection, false);
      els.main.setAttribute("aria-busy", "false");
      return { stop() {} };
    }

    els.pairingApp.textContent = app;
    const client = env.createClient(app);
    let stopped = false;

    // --- What is being waited for

    let requests = 0;

    /** Runs a request, with the page marked busy while it's in flight, and `onSlow` called if it takes a while. */
    async function waiting(work, onSlow) {
      requests += 1;
      els.main.setAttribute("aria-busy", "true");
      els.pair.disabled = true; // one request for the code at a time
      const slow = onSlow ? env.setTimeout(onSlow, SLOW) : null;
      try {
        return await work();
      } finally {
        env.clearTimeout(slow);
        requests -= 1;
        if (requests === 0) {
          els.main.setAttribute("aria-busy", "false");
          els.pair.disabled = false;
        }
      }
    }

    function renderBusy() {
      const busy = Healdan.busyNow(storage, env.now());
      els.busy.textContent = busy === null ? "" : busyText(busy);
      show(els.busySection, busy !== null);
    }

    let busyTimer = null;
    function lookAtBusy() {
      renderBusy();
      if (!stopped) {
        busyTimer = env.setTimeout(lookAtBusy, BUSY_LOOK);
      }
    }

    // --- The status, and pairing

    let state = { app, checked: false };
    let pairingId = null;
    let pairingStarted = false;

    function renderStatus() {
      const status = statusFor(Healdan, state);
      els.status.textContent = status.text;
      els.status.className = `status ${status.kind}`;
      show(els.pairing, state.paired === false && !state.error);
    }

    function setProblem(text) {
      els.problem.textContent = text;
    }

    async function startPairing() {
      setProblem("");
      els.help.textContent = "Asking Healdan Helper for a code…";
      try {
        pairingId = await waiting(
          () => client.pairStart(),
          () => {
            els.help.textContent = "Healdan Helper is taking a while to answer…";
          },
        );
        els.help.textContent = "Healdan Helper is showing a six-digit code in a window on your Mac. Type it here, then choose Pair.";
        els.code.focus();
      } catch (error) {
        pairingId = null;
        els.help.textContent = "Choose Pair to ask Healdan Helper for a code.";
        setProblem(failureText(Healdan, error));
      }
    }

    async function finishPairing() {
      if (pairingId === null) {
        await startPairing(); // no pairing under way (it expired, or ran out of tries): this asks for a new code
        return;
      }
      setProblem("");
      try {
        await waiting(() => client.pairFinish(pairingId, els.code.value));
      } catch (error) {
        setProblem(failureText(Healdan, error));
        if (error.code === "pairing-expired" || (error.code === "wrong-code" && error.triesLeft === 0)) {
          pairingId = null;
          els.help.textContent = "Choose Pair to ask Healdan Helper for a new code.";
        }
        els.code.select();
        return;
      }
      pairingId = null;
      pairingStarted = false;
      els.code.value = "";
      els.done.textContent =
        link !== null
          ? `${app} is paired with Healdan Helper. Close this window, then press the button again.`
          : `${app} is paired with Healdan Helper. Its Healdan buttons will work now.`;
      show(els.done, true);
      state = { app, checked: true, paired: true };
      renderStatus();
      await check();
    }

    // One check at a time, and the next only once this one has an answer: a helper slow to answer is waited for.
    let timer = null;
    let checking = false;
    let again = false;
    async function check() {
      if (stopped) {
        return;
      }
      if (checking) {
        again = true;
        return;
      }
      checking = true;
      env.clearTimeout(timer);
      try {
        const slow = env.setTimeout(() => {
          state = { ...state, checked: false, slow: true };
          renderStatus();
        }, SLOW);
        const next = { app, checked: true };
        try {
          await client.find();
          next.paired = client.paired();
          if (next.paired) {
            const answer = await client.call("ping", {});
            next.signedIn = !answer || answer.signedIn !== false;
            next.paired = client.paired(); // a key the helper forgot is dropped by the call
          }
        } catch (error) {
          if (error instanceof Healdan.HelperError && error.code === "not-paired") {
            next.paired = false;
          } else {
            next.error = error instanceof Healdan.HelperError ? error.code : "unexpected-answer";
          }
        } finally {
          env.clearTimeout(slow);
        }
        state = next;
        if (state.paired) {
          pairingId = null;
          pairingStarted = false;
        }
        renderStatus();
        if (!state.error && !state.paired && !pairingStarted && !stopped) {
          pairingStarted = true;
          await startPairing();
        }
      } finally {
        checking = false;
        if (!stopped) {
          timer = env.setTimeout(check, again ? 0 : POLL);
        }
        again = false;
      }
    }

    els.pairing.addEventListener("submit", (event) => {
      event.preventDefault();
      finishPairing();
    });
    env.onStorage(renderBusy);

    renderStatus();
    lookAtBusy();
    if (link !== null) {
      els.message.focus();
    }
    await waiting(check);

    return {
      stop() {
        stopped = true;
        env.clearTimeout(timer);
        env.clearTimeout(busyTimer);
      },
    };
  }

  const api = { statusFor, busyText, whenText, noteText, failureText, start, POLL, SLOW, BUSY_LOOK };

  if (root.document && typeof root.document.getElementById === "function" && root.document.getElementById("pairing")) {
    start({
      document: root.document,
      Healdan: root.Healdan,
      Office: root.Office,
      location: root.location,
      createClient: (host) => root.Healdan.createHelperClient({ host }),
      now: () => Date.now(),
      setTimeout: (...args) => root.setTimeout(...args),
      clearTimeout: (...args) => root.clearTimeout(...args),
      onStorage: (listener) => root.addEventListener("storage", listener),
    });
  }
  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
