// The Office.js glue (clients/office/web/office-glue.js), run with Node's built-in runner and a fake Office:
//
//   node --test 'clients/office/test/*.test.js'
//
// The fake keeps to what the 2026-10-30 trial found Office.js doing in Word, Excel and PowerPoint 16.114 for Mac, and
// logs every call so a test can say what the glue asked of Office and in what order. What Office really does with them
// is for the owner's walkthrough: nothing here runs in Office.

"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const HealdanOffice = require("../web/office-glue.js");

const SUCCEEDED = "succeeded";
const FAILED = "failed";

// --- A fake Office ---------------------------------------------------------------------------------------------------

/**
 * Office.js as far as the glue uses it. `slices` are the document's slices as Office gives them: arrays of byte values.
 * `properties` are the document's custom properties, a Map. `footerFields` are the field codes already in Word's footer.
 */
function fakeOffice({
  host = "Word",
  platform = "Mac",
  url = "",
  slices = [[80, 75, 3, 4]],
  properties = new Map(),
  footerFields = [],
  failures = {},
  data = (bytes) => bytes,
} = {}) {
  const log = [];
  const fileRequests = [];

  const Office = {
    context: {
      host,
      platform,
      document: {
        url,
        getFileAsync(fileType, options, callback) {
          fileRequests.push({ fileType, options });
          log.push("getFileAsync");
          if (failures.getFileAsync) {
            callback({ status: FAILED, error: { name: "Error", message: failures.getFileAsync, code: 5001 } });
            return;
          }
          callback({
            status: SUCCEEDED,
            value: {
              sliceCount: slices.length,
              size: slices.reduce((total, slice) => total + slice.length, 0),
              getSliceAsync(index, done) {
                log.push(`getSliceAsync ${index}`);
                if (failures.getSliceAsync === index) {
                  done({ status: FAILED, error: { name: "Error", message: "slice failed", code: 5002 } });
                  return;
                }
                done({ status: SUCCEEDED, value: { index, data: data(slices[index]) } });
              },
              closeAsync() {
                log.push("closeAsync");
              },
            },
          });
        },
      },
    },
    FileType: { Compressed: "compressed" },
    AsyncResultStatus: { Succeeded: SUCCEEDED, Failed: FAILED },
    PlatformType: { OfficeOnline: "OfficeOnline", Mac: "Mac", PC: "PC" },
  };

  /** A collection of custom properties, the same shape in Word, Excel and PowerPoint. */
  function propertyCollection(name) {
    return {
      add(key, value) {
        log.push(`${name}.add ${key}=${JSON.stringify(value)}`);
        if (failures.add) {
          throw new Error(failures.add);
        }
        properties.set(key, value);
        return {};
      },
      getItemOrNullObject(key) {
        const item = {
          isNullObject: !properties.has(key),
          value: undefined,
          load() {
            log.push(`${name}.load ${key}`);
            item.value = properties.get(key);
          },
          delete() {
            log.push(`${name}.delete ${key}`);
            properties.delete(key);
          },
        };
        return item;
      },
    };
  }

  function run(name, build) {
    return async (batch) => {
      log.push(`${name}.run`);
      const context = build();
      return batch(context);
    };
  }

  function sync(name) {
    return async () => {
      log.push(`${name}.sync`);
      if (failures.sync) {
        throw new Error(failures.sync);
      }
    };
  }

  const word = {
    run: run("Word", () => ({
      sync: sync("Word"),
      document: {
        properties: { customProperties: propertyCollection("Word") },
        sections: {
          getFirst: () => ({
            getFooter(type) {
              log.push(`getFooter ${type}`);
              return {
                getRange(where) {
                  log.push(`getRange ${where}`);
                  return {
                    insertField(location, fieldType, text, removeFormatting) {
                      log.push(`insertField ${location} ${fieldType} ${text} ${removeFormatting}`);
                      if (failures.insertField) {
                        throw new Error(failures.insertField);
                      }
                      return {
                        updateResult() {
                          log.push("updateResult");
                        },
                      };
                    },
                  };
                },
                fields: {
                  items: footerFields.map((code, index) => ({
                    code,
                    updateResult() {
                      log.push(`updateResult existing ${index}`);
                    },
                  })),
                  load(names) {
                    log.push(`fields.load ${names}`);
                    if (failures.fieldsLoad) {
                      throw new Error(failures.fieldsLoad);
                    }
                  },
                },
              };
            },
          }),
        },
      },
    })),
  };

  const excel = { run: run("Excel", () => ({ sync: sync("Excel"), workbook: { properties: { custom: propertyCollection("Excel") } } })) };

  const powerpoint = {
    run: run("PowerPoint", () => ({
      sync: sync("PowerPoint"),
      presentation: { properties: { customProperties: propertyCollection("PowerPoint") } },
    })),
  };

  return { env: { Office, Word: word, Excel: excel, PowerPoint: powerpoint, btoa }, log, properties, fileRequests };
}

// --- The script ------------------------------------------------------------------------------------------------------

test("it loads as a classic script, with HealdanOffice on the page's global", () => {
  const source = fs.readFileSync(path.join(__dirname, "..", "web", "office-glue.js"), "utf8");
  const page = vm.createContext({});
  vm.runInContext(source, page);
  assert.equal(typeof page.HealdanOffice.create, "function");
  assert.equal(typeof page.HealdanOffice.toBase64, "function");
});

test("the script is one the server serves: a plain name, in the pages' own folder", () => {
  const web = path.join(__dirname, "..", "web");
  const servable = /^[A-Za-z0-9-]+\.(html|js|css)$/;
  for (const name of ["office-glue.js", "healdan.js", "taskpane.js", "commands.js", "healdan.css", "taskpane.html", "commands.html"]) {
    assert.ok(servable.test(name), `${name} is served by AddinResource`);
    assert.ok(fs.existsSync(path.join(web, name)), `${name} exists`);
  }
});

// --- Base64 ----------------------------------------------------------------------------------------------------------

test("toBase64 agrees with Node's, for every length near a chunk's edge", () => {
  const sizes = [0, 1, 2, 3, 4, 5, 24575, 24576, 24577, 24578, 49152, 100001];
  for (const size of sizes) {
    const bytes = new Uint8Array(size);
    for (let i = 0; i < size; i += 1) {
      bytes[i] = (i * 31 + 7) % 256;
    }
    assert.equal(HealdanOffice.toBase64(bytes, btoa), Buffer.from(bytes).toString("base64"), `${size} bytes`);
  }
});

// --- What the office object says -------------------------------------------------------------------------------------

test("the office object names the app, the platform and the document's location", () => {
  for (const host of ["Word", "Excel", "PowerPoint"]) {
    const { env } = fakeOffice({ host, url: "/Users/me/Lease.docx" });
    const office = HealdanOffice.create(env);
    assert.equal(office.host, host);
    assert.equal(office.onTheWeb, false);
    assert.equal(office.url(), "/Users/me/Lease.docx");
  }
  // A document that was never saved has no location, and Office says undefined or "".
  for (const url of [undefined, "", null]) {
    const { env } = fakeOffice({ url });
    assert.equal(HealdanOffice.create(env).url(), "");
  }
  // The web is its own platform.
  assert.equal(HealdanOffice.create(fakeOffice({ platform: "OfficeOnline" }).env).onTheWeb, true);
});

test("an app that isn't Word, Excel or PowerPoint is refused", () => {
  assert.throws(() => HealdanOffice.create(fakeOffice({ host: "Outlook" }).env), /Word, Excel or PowerPoint/);
});

// --- The document's bytes --------------------------------------------------------------------------------------------

test("readBytes asks for the compressed file in 4 MiB slices, reads every one in order and closes the file", async () => {
  const { env, log, fileRequests } = fakeOffice({ slices: [[1, 2, 3, 4, 5], [6, 7, 8], [9]] });
  const base64 = await HealdanOffice.create(env).readBytes();
  assert.equal(base64, Buffer.from([1, 2, 3, 4, 5, 6, 7, 8, 9]).toString("base64"));
  assert.deepEqual(fileRequests, [{ fileType: "compressed", options: { sliceSize: 4194304 } }]);
  assert.deepEqual(log, ["getFileAsync", "getSliceAsync 0", "getSliceAsync 1", "getSliceAsync 2", "closeAsync"]);
});

test("readBytes takes slices as Office gives them: arrays, typed arrays or buffers", async () => {
  for (const data of [(bytes) => bytes, (bytes) => Uint8Array.from(bytes), (bytes) => Uint8Array.from(bytes).buffer]) {
    const { env } = fakeOffice({ slices: [[250, 251, 252], [0, 1]], data });
    assert.equal(await HealdanOffice.create(env).readBytes(), Buffer.from([250, 251, 252, 0, 1]).toString("base64"));
  }
});

test("readBytes gives a document of several megabytes whole", async () => {
  const slice = (seed) => Array.from({ length: 4194304 }, (_, i) => (i + seed) % 251);
  const slices = [slice(1), slice(2), [9, 8, 7]];
  const { env } = fakeOffice({ slices });
  const expected = Buffer.concat(slices.map((s) => Buffer.from(s))).toString("base64");
  assert.equal(await HealdanOffice.create(env).readBytes(), expected);
});

test("readBytes says what Office said when it can't open the file, and when a slice fails (and still closes it)", async () => {
  const refused = fakeOffice({ failures: { getFileAsync: "The file can't be read" } });
  await assert.rejects(HealdanOffice.create(refused.env).readBytes(), { message: "The file can't be read" });

  const broken = fakeOffice({ slices: [[1], [2], [3]], failures: { getSliceAsync: 1 } });
  await assert.rejects(HealdanOffice.create(broken.env).readBytes(), { message: "slice failed" });
  assert.deepEqual(broken.log, ["getFileAsync", "getSliceAsync 0", "getSliceAsync 1", "closeAsync"]);
});

test("readBytes refuses a slice it can't read as bytes", async () => {
  const { env, log } = fakeOffice({ data: () => "text, not bytes" });
  await assert.rejects(HealdanOffice.create(env).readBytes(), /bytes/);
  assert.ok(log.includes("closeAsync"));
});

// --- The mark --------------------------------------------------------------------------------------------------------

test("setMark puts the id in a custom property, and removeMark takes it out, in Word", async () => {
  const { env, log, properties } = fakeOffice({ host: "Word" });
  const office = HealdanOffice.create(env);
  await office.setMark("mark-1");
  assert.equal(properties.get("HealdanMark"), "mark-1");
  assert.deepEqual(log, ["Word.run", 'Word.add HealdanMark="mark-1"', "Word.sync"]);

  log.length = 0;
  await office.removeMark("mark-1");
  assert.equal(properties.has("HealdanMark"), false);
  assert.deepEqual(log, ["Word.run", "Word.load HealdanMark", "Word.sync", "Word.delete HealdanMark", "Word.sync"]);
});

test("setMark and removeMark do the same in Excel, through the workbook's custom properties", async () => {
  const { env, log, properties } = fakeOffice({ host: "Excel" });
  const office = HealdanOffice.create(env);
  await office.setMark("mark-2");
  assert.equal(properties.get("HealdanMark"), "mark-2");
  await office.removeMark("mark-2");
  assert.equal(properties.has("HealdanMark"), false);
  assert.deepEqual(log.filter((entry) => !entry.endsWith("sync")), [
    "Excel.run",
    'Excel.add HealdanMark="mark-2"',
    "Excel.run",
    "Excel.load HealdanMark",
    "Excel.delete HealdanMark",
  ]);
});

test("PowerPoint gets no mark at all: it never shows one, and a mark would stay in the file", async () => {
  const { env, log, properties } = fakeOffice({ host: "PowerPoint" });
  const office = HealdanOffice.create(env);
  await office.setMark("mark-3");
  await office.removeMark("mark-3");
  assert.deepEqual(log, []);
  assert.equal(properties.size, 0);
});

test("removeMark leaves a mark that isn't the one it was given, and does nothing when there is none", async () => {
  const other = fakeOffice({ properties: new Map([["HealdanMark", "someone-else's"]]) });
  await HealdanOffice.create(other.env).removeMark("mark-1");
  assert.equal(other.properties.get("HealdanMark"), "someone-else's");
  assert.ok(!other.log.some((entry) => entry.includes("delete")));

  const none = fakeOffice();
  await HealdanOffice.create(none.env).removeMark("mark-1");
  assert.ok(!none.log.some((entry) => entry.includes("delete")));
});

test("a mark Office won't set or take out is an error, for the flow to tell", async () => {
  const set = fakeOffice({ failures: { add: "Custom properties are read-only" } });
  await assert.rejects(HealdanOffice.create(set.env).setMark("m"), { message: "Custom properties are read-only" });

  const sync = fakeOffice({ properties: new Map([["HealdanMark", "m"]]), failures: { sync: "Office is busy" } });
  await assert.rejects(HealdanOffice.create(sync.env).removeMark("m"), { message: "Office is busy" });
});

// --- Insert Doc ID ---------------------------------------------------------------------------------------------------

test("insertDocId sets the property, then adds a DocProperty field at the end of the primary footer and updates it", async () => {
  const { env, log, properties } = fakeOffice({ host: "Word" });
  await HealdanOffice.create(env).insertDocId("#1234 v1.2");
  assert.equal(properties.get("Healdan Doc ID"), "#1234 v1.2");
  assert.deepEqual(log, [
    "Word.run",
    'Word.add Healdan Doc ID="#1234 v1.2"',
    "Word.sync",
    "getFooter Primary",
    "fields.load items/code",
    "Word.sync",
    "getRange End",
    'insertField End DocProperty "Healdan Doc ID" true',
    "updateResult",
    "Word.sync",
  ]);
});

test('insertDocId with "" (a document not in Healdan yet) sets the property to an empty string and inserts the field', async () => {
  const { env, log, properties } = fakeOffice();
  await HealdanOffice.create(env).insertDocId("");
  assert.equal(properties.get("Healdan Doc ID"), "");
  assert.ok(log.includes('Word.add Healdan Doc ID=""'));
  assert.ok(log.includes('insertField End DocProperty "Healdan Doc ID" true'));
});

test("insertDocId again adds no second field: it updates the one the footer has", async () => {
  const existing = [' PAGE ', ' DOCPROPERTY "Healdan Doc ID" \\* MERGEFORMAT '];
  const { env, log, properties } = fakeOffice({ footerFields: existing });
  await HealdanOffice.create(env).insertDocId("#1234 v1.3");
  assert.equal(properties.get("Healdan Doc ID"), "#1234 v1.3");
  assert.ok(!log.some((entry) => entry.startsWith("insertField")), "no second field");
  assert.ok(log.includes("updateResult existing 1"), "the field that shows it is updated");
  assert.ok(!log.includes("updateResult existing 0"), "and not the page number");
});

test("insertDocId still inserts the field when Word can't say what the footer holds", async () => {
  const { env, log } = fakeOffice({ failures: { fieldsLoad: "ApiNotFound" } });
  await HealdanOffice.create(env).insertDocId("#1234 v1.2");
  assert.ok(log.includes('insertField End DocProperty "Healdan Doc ID" true'));
});

test("insertDocId says what Word said when it can't", async () => {
  const set = fakeOffice({ failures: { add: "The property can't be set" } });
  await assert.rejects(HealdanOffice.create(set.env).insertDocId("#1 v1.0"), { message: "The property can't be set" });

  const field = fakeOffice({ failures: { insertField: "The footer can't be changed" } });
  await assert.rejects(HealdanOffice.create(field.env).insertDocId("#1 v1.0"), { message: "The footer can't be changed" });
});

test("insertDocId is Word's only", async () => {
  for (const host of ["Excel", "PowerPoint"]) {
    const { env, log } = fakeOffice({ host });
    await assert.rejects(HealdanOffice.create(env).insertDocId("#1 v1.0"), /Word/);
    assert.deepEqual(log, []);
  }
});

// --- With the flows --------------------------------------------------------------------------------------------------

test("the office object is what Healdan's flows take: all six members, in the shapes they use", () => {
  const office = HealdanOffice.create(fakeOffice().env);
  assert.deepEqual(Object.keys(office).sort(), ["host", "insertDocId", "onTheWeb", "readBytes", "removeMark", "setMark", "url"]);
  for (const name of ["url", "readBytes", "setMark", "removeMark", "insertDocId"]) {
    assert.equal(typeof office[name], "function", name);
  }
});
