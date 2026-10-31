/*
 * Healdan's Microsoft Office add-in: the ribbon's six buttons (M3.5 spec section 6).
 *
 * Each button in the Healdan tab runs one of these functions in commands.html, a page with nothing on it. It runs its
 * flow (healdan.js) over the Office.js glue (office-glue.js), and if the flow has something to tell, opens a window for
 * it: taskpane.html?message=<code>&app=<app>, through Office.context.ui.displayDialogAsync. The manifest declares no
 * shared runtime, so Office.addin.showAsTaskpane isn't there to open the pane with, and a dialog is. The first press in
 * an app is such a message ("not-paired"), and the window it opens is where the app pairs.
 *
 * The window and this page talk by messages (healdan.js, windowMessage): the window says it is ready, and is sent what
 * Office said about a failure, which isn't in the address because the server logs addresses; and when it pairs, it hands
 * back the key, which this page keeps as well, as the two may not share storage.
 *
 * The function names are the manifest's (manifest.template.xml's <FunctionName>s). Each is defined on the global object,
 * and again with Office.actions.associate once Office is ready, as the trial did. Each calls event.completed() once,
 * whatever happened: when its flow has finished and, if it opened a window, the window has closed, so that this page is
 * still there to hear it.
 *
 * While a flow runs, a note in localStorage says so, refreshed every few seconds, so the pane can show that Healdan
 * Helper is working on it: a flow waits for as long as a window of the helper's stays open.
 *
 * It is a classic script: no import, no npm. Under Node (clients/office/test/commands.test.js) it sets only
 * `module.exports`: the page is wired up only where Office, Healdan and the glue are on the global object.
 */
(function (root) {
  "use strict";

  /** The manifest's function names, one for each button. */
  const FLOWS = ["saveToHealdan", "openFromHealdan", "saveAsNewVersion", "saveAsNewDocument", "properties", "insertDocId"];

  /** The size of the message window, as a percentage of the screen's. */
  const WINDOW = { width: 30, height: 45 };

  /**
   * @param {object} env what the commands run on, so tests can fake it
   * @param {object} env.Healdan healdan.js's API
   * @param {Function} env.makeOffice makes the office object for this app (HealdanOffice.create over the page's globals)
   * @param {object} env.Office Office.js's global
   * @param {{href: string}} env.location the page's location, which the message window's address is relative to
   * @param {Function} env.now milliseconds, as Date.now
   * @param {Function} env.setInterval
   * @param {Function} env.clearInterval
   * @param {object} [env.console]
   * @returns {object} the six functions, by name
   */
  function createCommands(env) {
    const { Healdan, Office } = env;

    /**
     * Opens the message window, and resolves once it has closed (or at once, if it won't open: Office says no when another
     * dialog is already open). The button waits for that, so that its page is still there to hear the window:
     *   - when the window says it is ready, it is sent what Office said about a failure, if there is anything, which
     *     isn't in the window's address because the server logs addresses;
     *   - when the window says it paired, the key it was given is kept here too, as the window may not share storage
     *     with this page.
     * Office reports the window's closing, by the user or in error, as a DialogEventReceived.
     */
    function showWindow(note, client, app) {
      return new Promise((resolve) => {
        const address = new URL(Healdan.messageLink(note), env.location.href).href;
        const origin = new URL(env.location.href).origin;
        const failed = (error) => {
          if (env.console) {
            env.console.warn(`Healdan: the message window didn't open: ${error && error.message}`);
          }
          resolve();
        };
        try {
          Office.context.ui.displayDialogAsync(address, { ...WINDOW, promptBeforeOpen: false }, (opened) => {
            if (opened.status !== Office.AsyncResultStatus.Succeeded) {
              failed(opened.error);
              return;
            }
            const dialog = opened.value;
            dialog.addEventHandler(Office.EventType.DialogMessageReceived, (arrived) => {
              if (!arrived || (typeof arrived.origin === "string" && arrived.origin !== origin)) {
                return; // only the page this one opened
              }
              const message = Healdan.readWindowMessage(arrived.message);
              if (message === null) {
                return;
              }
              if (message.kind === "ready" && note.detail) {
                dialog.messageChild(Healdan.windowMessage({ kind: "detail", detail: note.detail }), { targetOrigin: origin });
              } else if (message.kind === "paired" && message.app === app && client) {
                client.adoptKey(message.key);
              }
            });
            dialog.addEventHandler(Office.EventType.DialogEventReceived, () => resolve());
          });
        } catch (error) {
          failed(error);
        }
      });
    }

    async function show(note, client, app) {
      Healdan.noteMessage(Healdan.storage(), note, env.now());
      await showWindow(note, client, app);
    }

    /** Marks the flow busy, and again every beat. Returns what ends it. */
    function beginBusy(flow, app) {
      const storage = Healdan.storage();
      Healdan.markBusy(storage, flow, app, env.now());
      const timer = env.setInterval(() => Healdan.markBusy(storage, flow, app, env.now()), Healdan.BUSY_BEAT);
      return () => {
        env.clearInterval(timer);
        Healdan.clearBusy(storage, flow);
      };
    }

    function press(flow) {
      return async function (event) {
        let app;
        let client = null;
        try {
          const office = env.makeOffice();
          app = office.host;
          client = Healdan.createHelperClient({ host: app });
          const end = beginBusy(flow, app);
          let result;
          try {
            result = await Healdan[flow]({ client, office });
          } finally {
            end();
          }
          const note = Healdan.noteOf(result, app);
          if (note !== null) {
            await show(note, client, app);
          }
        } catch (error) {
          // The flows resolve whatever happens in them; this is the glue or the page failing.
          try {
            await show({ code: "office-error", app, detail: error && error.message }, client, app);
          } catch (again) {
            // Nothing left to tell it with.
          }
        } finally {
          event.completed();
        }
      };
    }

    const commands = {};
    for (const flow of FLOWS) {
      commands[flow] = press(flow);
    }
    return commands;
  }

  /** Defines the commands on the page's global object, and with Office once it is ready. */
  function install(page) {
    const commands = createCommands({
      Healdan: page.Healdan,
      makeOffice: () => page.HealdanOffice.create(page),
      Office: page.Office,
      location: page.location,
      now: () => Date.now(),
      setInterval: (...args) => page.setInterval(...args),
      clearInterval: (...args) => page.clearInterval(...args),
      console: page.console,
    });
    for (const flow of FLOWS) {
      page[flow] = commands[flow];
    }
    page.Office.onReady(() => {
      if (page.Office.actions && typeof page.Office.actions.associate === "function") {
        for (const flow of FLOWS) {
          page.Office.actions.associate(flow, commands[flow]);
        }
      }
    });
    return commands;
  }

  const api = { FLOWS, createCommands, install };

  if (root.Office && root.document && root.Healdan && root.HealdanOffice) {
    install(root);
  }
  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
