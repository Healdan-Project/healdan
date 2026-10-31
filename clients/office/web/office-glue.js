/*
 * Healdan's Microsoft Office add-in: the Office.js glue (M3.5 spec section 6).
 *
 * healdan.js decides everything the add-in does, with no Office in it. This file is the other half: the `office` object
 * its six flows take, made of the few things Office.js has to be asked, which the 2026-10-30 trial proved in Word, Excel
 * and PowerPoint 16.114 for Mac:
 *
 *   { host,                    "Word", "Excel" or "PowerPoint" (Office.context.host)
 *     onTheWeb,                Office.context.platform is Office on the web
 *     url(),                   Office.context.document.url, "" for a document that was never saved
 *     readBytes(),             the document, as base64: getFileAsync(Compressed, { sliceSize: 4194304 }), every slice
 *     setMark(id),             the one-time id in a custom property (Word: document.properties.customProperties; Excel:
 *     removeMark(id),          workbook.properties.custom). PowerPoint gets none: it never shows one to AppleScript,
 *                              and one would stay in its file
 *     insertDocId(docRef) }    Word only: the property Healdan Doc ID, then a DocProperty field at the end of the
 *                              primary footer of section 1, updated
 *
 * It is named office-glue.js and not office.js, so that nothing mistakes it for Microsoft's script of that name, which
 * Office.js may look for by its file name.
 *
 * It is a classic script, loaded after Microsoft's: no import, no npm. It sets `HealdanOffice` on the global object, and
 * `module.exports` under Node, where clients/office/test/office-glue.test.js runs it against a fake Office. The Office
 * globals (Office, Word, Excel, PowerPoint) are looked up when they're used, as Word has no `Excel` and the reverse.
 */
(function (root) {
  "use strict";

  const HOSTS = ["Word", "Excel", "PowerPoint"];

  /** Office gives a file in slices of this many bytes: the most it takes, 4 MiB. */
  const SLICE_SIZE = 4194304;

  /** The custom property the mark goes in. The helper looks for it by this name (Word.HasMark.applescript and the others). */
  const MARK_PROPERTY = "HealdanMark";

  /** The custom property the footer's field shows (M3 section 5.9), and the field's code. */
  const DOC_ID_PROPERTY = "Healdan Doc ID";
  const DOC_ID_FIELD = '"Healdan Doc ID"';

  /** The footer's fields that show the doc ID, by their code: DOCPROPERTY "Healdan Doc ID" and what follows it. */
  const DOC_ID_FIELD_CODE = /DOCPROPERTY\s+"?Healdan Doc ID"?/i;

  // --- Base64 ----------------------------------------------------------------------------------------------------------

  /** A multiple of 3, so that each chunk's base64 stands alone, and under what String.fromCharCode takes at once. */
  const CHUNK = 24576;

  /**
   * Bytes as base64.
   * @param {Uint8Array} bytes
   * @param {Function} encode btoa
   */
  function toBase64(bytes, encode) {
    let base64 = "";
    for (let start = 0; start < bytes.length; start += CHUNK) {
      base64 += encode(String.fromCharCode.apply(null, bytes.subarray(start, Math.min(start + CHUNK, bytes.length))));
    }
    return base64;
  }

  /** A slice's data as bytes: Office gives the compressed file as an array of byte values. */
  function bytesOf(data) {
    if (data instanceof Uint8Array) {
      return data;
    }
    if (data instanceof ArrayBuffer) {
      return new Uint8Array(data);
    }
    if (Array.isArray(data)) {
      return Uint8Array.from(data);
    }
    throw new Error("Microsoft Office gave the document as something that isn't bytes.");
  }

  /** An Error for what Office reported in an AsyncResult. */
  function officeError(error) {
    return new Error(error && error.message ? error.message : "Microsoft Office reported an error.");
  }

  // --- The office object ------------------------------------------------------------------------------------------------

  /**
   * @param {object} [env] the page's globals: Office, and Word, Excel and PowerPoint where the app has them, and btoa
   * @returns {object} the office object Healdan's flows take
   */
  function create(env) {
    const globals = env || root;
    const Office = globals.Office;
    const host = Office.context.host;
    if (!HOSTS.includes(host)) {
      throw new TypeError(`The add-in runs in Word, Excel or PowerPoint, not ${host}.`);
    }

    /** Runs `work(context)` in the app's own batch (Word.run, Excel.run) and resolves when it has finished. */
    function inApp(work) {
      return globals[host].run(work);
    }

    function readBytes() {
      return new Promise((resolve, reject) => {
        Office.context.document.getFileAsync(Office.FileType.Compressed, { sliceSize: SLICE_SIZE }, (opened) => {
          if (opened.status !== Office.AsyncResultStatus.Succeeded) {
            reject(officeError(opened.error));
            return;
          }
          const file = opened.value;
          const parts = [];
          let total = 0;

          // The bytes are in memory by now, so the file is closed without waiting for it to say it is.
          const finish = (error) => {
            try {
              file.closeAsync(() => {});
            } catch (ignored) {
              // A file that won't close has nothing more to give.
            }
            if (error) {
              reject(error);
              return;
            }
            const bytes = new Uint8Array(total);
            let offset = 0;
            for (const part of parts) {
              bytes.set(part, offset);
              offset += part.length;
            }
            resolve(toBase64(bytes, globals.btoa || root.btoa));
          };

          const next = (index) => {
            if (index >= file.sliceCount) {
              finish(null);
              return;
            }
            file.getSliceAsync(index, (slice) => {
              if (slice.status !== Office.AsyncResultStatus.Succeeded) {
                finish(officeError(slice.error));
                return;
              }
              try {
                const part = bytesOf(slice.value.data);
                parts.push(part);
                total += part.length;
              } catch (error) {
                finish(error);
                return;
              }
              next(index + 1);
            });
          };
          next(0);
        });
      });
    }

    async function setMark(id) {
      if (host === "Word") {
        await inApp(async (context) => {
          context.document.properties.customProperties.add(MARK_PROPERTY, id);
          await context.sync();
        });
      } else if (host === "Excel") {
        await inApp(async (context) => {
          context.workbook.properties.custom.add(MARK_PROPERTY, id);
          await context.sync();
        });
      }
      // PowerPoint: none.
    }

    /** Takes the mark out, if it is the one given: the property holds one id at a time, and another save's isn't this one's. */
    async function removeMark(id) {
      if (host === "PowerPoint") {
        return;
      }
      await inApp(async (context) => {
        const properties = host === "Word" ? context.document.properties.customProperties : context.workbook.properties.custom;
        const property = properties.getItemOrNullObject(MARK_PROPERTY);
        property.load("value");
        await context.sync();
        if (!property.isNullObject && property.value === id) {
          property.delete();
          await context.sync();
        }
      });
    }

    /** The footer's fields that show the doc ID. Word may not say what a footer holds: then none, and one is added. */
    async function fieldsShowingDocId(context, footer) {
      try {
        footer.fields.load("items/code");
        await context.sync();
        return footer.fields.items.filter((field) => DOC_ID_FIELD_CODE.test(field.code));
      } catch (error) {
        return [];
      }
    }

    async function insertDocId(docRef) {
      if (host !== "Word") {
        throw new Error("Insert Doc ID is for Word only.");
      }
      await inApp(async (context) => {
        context.document.properties.customProperties.add(DOC_ID_PROPERTY, String(docRef));
        await context.sync();

        const footer = context.document.sections.getFirst().getFooter("Primary");
        const shown = await fieldsShowingDocId(context, footer);
        if (shown.length > 0) {
          // Pressed again: the footer has its field, and now it has the property's new value to show.
          shown.forEach((field) => field.updateResult());
        } else {
          const field = footer.getRange("End").insertField("End", "DocProperty", DOC_ID_FIELD, true);
          field.updateResult();
        }
        await context.sync();
      });
    }

    return {
      host,
      onTheWeb: Office.context.platform === Office.PlatformType.OfficeOnline,
      url: () => (Office.context.document && Office.context.document.url) || "",
      readBytes,
      setMark,
      removeMark,
      insertDocId,
    };
  }

  const api = { create, toBase64, MARK_PROPERTY, DOC_ID_PROPERTY, SLICE_SIZE };

  root.HealdanOffice = api;
  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
