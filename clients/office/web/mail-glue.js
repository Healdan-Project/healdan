// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

/*
 * Healdan's Outlook add-in: the Office.js glue (M5 spec section 8).
 *
 * mail.js decides everything the pane does, with no Outlook in it. This file is the few things Outlook has to be asked:
 *
 *   { item(),                  the message being read (Office.context.mailbox.item), or null where none is open
 *     headers(item),           its internet headers, as the text getAllInternetHeadersAsync gives (Mailbox 1.8)
 *     readEml(item),           the message as Base64 EML: getAsFileAsync (Mailbox 1.14)
 *     masterCategories(),      the names in the mailbox's category list: masterCategories.getAsync (Mailbox 1.8)
 *     addMasterCategory(),     adds Healdan to that list, in COLOR: masterCategories.addAsync (needs read/write mailbox)
 *     addCategory(item),       the category Healdan, with categories.addAsync (Mailbox 1.8); Outlook refuses one the list lacks
 *     onItemChanged(fn) }      calls fn when a pinned pane's message changes (the ItemChanged event)
 *
 * Every call that asks Outlook is a promise, which rejects with an Error whose `code` is Outlook's own number for what
 * went wrong.
 *
 * It is a classic script, loaded after Microsoft's: no import, no npm. It sets `HealdanMailGlue` on the global object,
 * and `module.exports` under Node, where clients/office/test/mail-glue.test.js runs it against a fake Outlook. The Office
 * globals are looked up when they're used.
 */
(function (root) {
  "use strict";

  /** The category a filed message gets. */
  const CATEGORY = "Healdan";

  /** The colour of the category in the mailbox's list: Outlook's preset 7, blue (the trial found a colour is needed). */
  const COLOR = "Preset7";

  /**
   * @param {object} [env] the page's globals: Office
   * @returns {object} the glue mail.js takes
   */
  function create(env) {
    const globals = env || root;
    const Office = globals.Office;
    const mailbox = Office.context.mailbox;

    /** A promise of what an Office.js call with a callback gives, or its error, with Office's code. */
    function asked(start) {
      return new Promise((resolve, reject) => {
        try {
          start((result) => {
            if (result && result.status === Office.AsyncResultStatus.Succeeded) {
              resolve(result.value);
              return;
            }
            const failure = (result && result.error) || {};
            const error = new Error(failure.message || "Outlook reported an error.");
            error.code = failure.code;
            reject(error);
          });
        } catch (thrown) {
          reject(thrown);
        }
      });
    }

    return {
      item: () => mailbox.item || null,
      headers: (item) => asked((done) => item.getAllInternetHeadersAsync(done)),
      readEml: (item) => asked((done) => item.getAsFileAsync(done)),
      masterCategories: async () => {
        const entries = await asked((done) => mailbox.masterCategories.getAsync(done));
        return (entries || []).map((entry) => entry && entry.displayName);
      },
      addMasterCategory: () => asked((done) => mailbox.masterCategories.addAsync([{ displayName: CATEGORY, color: COLOR }], done)),
      addCategory: (item) => asked((done) => item.categories.addAsync([CATEGORY], done)),
      onItemChanged: (fn) =>
        asked((done) => mailbox.addHandlerAsync(Office.EventType.ItemChanged, fn, done)),
    };
  }

  const api = { create, CATEGORY, COLOR };

  root.HealdanMailGlue = api;
  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
