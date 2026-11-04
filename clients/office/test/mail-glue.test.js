// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// The Outlook add-in's glue (clients/office/web/mail-glue.js), run against a fake Outlook.

"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

const MailGlue = require("../web/mail-glue.js");

const OK = "succeeded";

function fakeOffice({ fail } = {}) {
  const calls = [];
  const answer = (name, value) => (...args) => {
    const done = args[args.length - 1];
    calls.push([name, ...args.slice(0, -1)]);
    done(fail ? { status: "failed", error: { code: 9004, message: "No." } } : { status: OK, value });
  };
  const item = {
    getAllInternetHeadersAsync: answer("headers", "In-Reply-To: <a@b>"),
    getAsFileAsync: answer("eml", "RnJvbTog"),
    categories: { addAsync: answer("category", undefined) },
  };
  const handlers = [];
  const Office = {
    AsyncResultStatus: { Succeeded: OK },
    EventType: { ItemChanged: "olkItemSelectedChanged" },
    context: {
      mailbox: {
        item,
        addHandlerAsync: (type, fn, done) => {
          handlers.push([type, fn]);
          done({ status: OK });
        },
      },
    },
  };
  return { Office, item, calls, handlers };
}

test("readEml resolves the Base64 EML, and headers the header text", async () => {
  const { Office, item } = fakeOffice();
  const glue = MailGlue.create({ Office });
  assert.equal(glue.item(), item);
  assert.equal(await glue.readEml(item), "RnJvbTog");
  assert.equal(await glue.headers(item), "In-Reply-To: <a@b>");
});

test("addCategory adds the category Healdan", async () => {
  const { Office, item, calls } = fakeOffice();
  await MailGlue.create({ Office }).addCategory(item);
  assert.deepEqual(calls, [["category", ["Healdan"]]]);
});

test("onItemChanged registers for ItemChanged", async () => {
  const { Office, handlers } = fakeOffice();
  const fn = () => {};
  await MailGlue.create({ Office }).onItemChanged(fn);
  assert.deepEqual(handlers, [["olkItemSelectedChanged", fn]]);
});

test("a failure rejects with Outlook's code", async () => {
  const { Office, item } = fakeOffice({ fail: true });
  const glue = MailGlue.create({ Office });
  for (const ask of [glue.readEml, glue.headers, glue.addCategory]) {
    await assert.rejects(ask(item), (error) => error.code === 9004 && error.message === "No.");
  }
});

test("item is null where no message is open", () => {
  const { Office } = fakeOffice();
  Office.context.mailbox.item = null;
  assert.equal(MailGlue.create({ Office }).item(), null);
});
