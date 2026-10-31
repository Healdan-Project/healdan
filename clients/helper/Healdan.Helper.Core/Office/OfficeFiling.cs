using System.Diagnostics;
using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Office;

/// <summary>
/// Save to Healdan and Save as New Document from Word, Excel and PowerPoint (M3.5 spec §5.5), for the add-in's routes.
/// The add-in can't name a never-saved document, so <c>save-new</c> takes the program's front window as it arrives, the
/// one whose button was just pressed, before the helper's Save window opens, and gives a one-time ticket for it
/// (<see cref="FilingTickets"/>). <c>file</c> brings the document's bytes with that ticket and a mark the add-in has set in
/// the document. The bytes are filed as a new document, checked out here, and the window is saved as its copy, which
/// the cycle looks after from then on as any other.
/// <para>Review Focus 2: only the window the request came from is saved, or nothing is filed. Where the program shows
/// the add-in's mark (Word and Excel, within about a third of a second in the trial), the mark finds the window; where it
/// doesn't, or not in time, the program's front window must still be the one taken.</para>
/// <para>Review Focus 1: a fingerprint of the window's content is taken before the filing and checked as the window is
/// saved as the copy, so an edit typed meanwhile is sent as a save, never folded silently into the copy's starting point.</para>
/// Nothing here knows which platform drives the programs: that's <see cref="IOfficeScripting"/>'s.
/// </summary>
/// <param name="clock">The tickets' clock: the real one by default.</param>
/// <param name="markWait">How long <c>file</c> waits for the add-in's mark: <see cref="MarkWait"/> by default.</param>
public sealed class OfficeFiling(CopyCycle cycle, OfficeApps apps, IWindows windows, INotifier notifier, HelperPaths paths, HelperLog log,
    TimeProvider? clock = null, TimeSpan? markWait = null)
{
    /// <summary>How long <c>file</c> waits for the add-in's mark to show before it looks at the program's front window instead.</summary>
    public static readonly TimeSpan MarkWait = TimeSpan.FromSeconds(3);

    /// <summary>How often it looks for the mark meanwhile.</summary>
    public static readonly TimeSpan MarkPoll = TimeSpan.FromMilliseconds(250);

    private const string Helper = "Healdan Helper";

    private readonly FilingTickets _tickets = new(clock);
    private readonly CopiesNotice _notice = new(paths, notifier);
    private readonly TimeSpan _markWait = markWait ?? MarkWait;

    /// <summary>
    /// <c>/v1/save-new</c> from the add-in: takes the program's front window first, then shows Save to Healdan's window
    /// offering the program's own format only, named after the window, or for Save as New Document
    /// (<paramref name="asNew"/>) after the document the window's copy is of. On Save, the answer carries a ticket for
    /// <see cref="FileAsync"/> in place of a temporary file; anything else is answered as the window gave it. Save as New
    /// Document from a window that isn't a copy is Save to Healdan.
    /// </summary>
    /// <param name="url">The add-in's <c>Office.context.document.url</c>, possibly empty. A copy it names must be the window's.</param>
    /// <exception cref="OfficeRefusedException">
    /// <see cref="OfficeRefusedException.WrongWindow"/>: the program has no document open, or the add-in named another
    /// copy than the front window's for Save as New Document.
    /// </exception>
    public async Task<JsonObject> SaveNewAsync(OfficeProgram p, string? url, bool asNew)
    {
        var app = OfficePrograms.CheckOutName(p);
        var window = await Scripting().FrontDocumentAsync(p)
            ?? throw new OfficeRefusedException(OfficeRefusedException.WrongWindow, $"{app} has no document open to save.");
        CopyState? original = null;
        if (asNew)
        {
            original = KnownCopy(window.Path);
            // The window saved is the front one, so the copy released and deleted afterwards must be its own: another copy
            // the add-in named would lose its file under its open window.
            if (KnownCopy(url) is { } named && named.Path != original?.Path)
                throw new OfficeRefusedException(OfficeRefusedException.WrongWindow,
                    $"Save as New Document was asked for a copy that isn't in {app}'s front window.");
        }
        var request = new JsonObject
        {
            ["app"] = app,
            ["name"] = original?.Title ?? NameOf(window), // the window takes a copy's doc # and version off its title
            ["formats"] = WindowAnswers.OfficeFormats(p),
            ["path"] = original?.Path,
        };
        if (original is not null)
            request["docNumber"] = original.DocNumber;
        var answer = await windows.SaveNewAsync(request);
        if (answer["action"] is not JsonValue action || !action.TryGetValue<string>(out var chosen) || chosen != "save")
            return answer;
        var properties = answer["properties"] is JsonObject given ? (JsonObject)given.DeepClone() : new JsonObject();
        var document = new NewDocument(FolderId(answer), FileName(p, Text(answer, "name")), properties, true);
        return WindowAnswers.Ticketed(answer, _tickets.Issue(p, window, original?.Path, document).Id);
    }

    /// <summary>
    /// <c>/v1/file</c> from the add-in: files <paramref name="bytes"/>, the document as the add-in read it, as the new
    /// document the ticket's window chose, checked out here, then saves the ticket's window as its copy. That save is the
    /// copy's starting point, unless the window changed since the bytes were read, when it goes up as a save. PowerPoint
    /// is then closed and opened again, so its own Save saves in place (spec §5.5 step 7). The bytes go through a
    /// temporary file in the helper's Temp folder, deleted whatever happens. A failed filing is told to the user and
    /// thrown, with the window left as it was, so the add-in takes its mark off.
    /// </summary>
    /// <param name="mark">The id the add-in set in the document's custom properties after reading its bytes; null when it set none.</param>
    /// <exception cref="OfficeRefusedException">
    /// <see cref="OfficeRefusedException.NoTicket"/> or <see cref="OfficeRefusedException.WrongWindow"/>: nothing was filed.
    /// </exception>
    public async Task<CopyState> FileAsync(string ticket, string? mark, byte[] bytes)
    {
        var t = _tickets.Take(ticket)
            ?? throw new OfficeRefusedException(OfficeRefusedException.NoTicket, "Save to Healdan's ticket has been used, or has expired.");
        var p = t.Program;
        var app = OfficePrograms.CheckOutName(p);
        var name = t.Document.Name;
        var scripting = Scripting();
        mark = string.IsNullOrEmpty(mark) ? null : mark;
        var doc = new DocumentRef(t.Window.Path, mark, t.Window.WindowName);
        var tempPath = WindowAnswers.TempPath(paths, OfficePrograms.OwnFormat(p));
        CopyState state;
        string fingerprint;
        try
        {
            try
            {
                if (!await IsTheWindowAsync(scripting, t, mark))
                    throw Refused(app, $"Nothing was filed: {app} has another document in front now. Click in the one you were "
                        + "saving, and press Save to Healdan again.");
                // Taken now, as the bytes have arrived, and checked by the save as the copy (spec §5.5 step 8).
                fingerprint = await scripting.FingerprintAsync(p, doc)
                    ?? throw Refused(app, $"Nothing was filed: {app} has no document open now.");
                await WriteTemporaryAsync(tempPath, bytes);
                state = await cycle.AdoptNewAsync(tempPath, t.Document, t.Replaces, apps.MicrosoftOffice);
            }
            catch (Exception e) when (e is not OfficeRefusedException)
            {
                log.Error($"Filing {name} from {app} failed", e);
                notifier.Notify(Helper, e switch
                {
                    ApiException { Status: 401 } => $"{name} from {app} wasn't filed: sign in to Healdan again, then save it to Healdan once more.",
                    ApiException api => $"{name} from {app} couldn't be filed in Healdan: {api.Detail}",
                    OfficeScriptException => $"{name} wasn't filed, as {app} didn't answer. It's still open, so save it to Healdan again.",
                    _ => $"{name} from {app} couldn't be filed in Healdan, which may be unreachable. It's still open, so save it again."
                });
                throw;
            }
        }
        finally
        {
            DeleteTemporary(tempPath);
        }

        bool changed;
        _notice.BeforeFirstSave(p); // the program asks for access to Copies the first time it saves there (spec §5.11)
        try
        {
            var result = await scripting.SaveAsAsync(p, doc, state.Path, fingerprint);
            // Found by nothing it was given, the front window was saved, whatever it holds: it goes up rather than being
            // taken for what was filed.
            changed = result.Changed || result.FoundBy == "front";
            if (result.FoundBy == "front")
                log.Warn($"{app} saved its front window as {state.DocRef}'s copy, not finding the one Save to Healdan was pressed in");
        }
        catch (Exception e) when (File.Exists(state.Path) || apps.MicrosoftOffice?.LockExists(state.Path) == true)
        {
            // Carried out all the same, as a save whose script gave up waiting for Grant File Access is: nothing says what
            // the window held by then, so it goes up.
            log.Warn($"{app} saved the window as {state.DocRef}'s copy, though its script failed: {e.Message}");
            changed = true;
        }
        catch (Exception e)
        {
            // The new copy waits for the window's save: if the program carries it out later, its write is the starting
            // point, and only then does Save as New Document's original go.
            log.Error($"{app} didn't save the window as {state.DocRef}'s copy", e);
            notifier.Notify(Helper, $"{state.DocRef} was filed in Healdan, but {app} didn't save the window as its copy, so "
                + $"changes made there may not reach Healdan: {e.Message}");
            throw;
        }
        state = cycle.Adopted(state.Path, changed);
        if (p == OfficeProgram.PowerPoint)
            await cycle.ReopenOfficeAsync(state.Path);
        log.Info($"Filed {state.DocRef} from {app}{(t.Replaces is null ? "" : " by Save as New Document")}"
            + (changed ? "; the window changed meanwhile, so it's sent as a save" : ""));
        return cycle.StateOf(state.Path);
    }

    /// <summary>
    /// The copy a request from the add-in is about (spec §5.8): the one <paramref name="url"/> names, when that's a local
    /// copy, else the program's front window's, when that's one; null when neither is. A path names a copy when it is in
    /// the copies folder, in any spelling (<c>/tmp</c> or <c>/private/tmp</c>), and the cycle knows a copy of that name:
    /// a file elsewhere with a copy's name is never taken for it.
    /// </summary>
    public async Task<string?> CopyForAsync(OfficeProgram p, string? url) =>
        KnownCopy(url)?.Path ?? KnownCopy((await Scripting().FrontDocumentAsync(p))?.Path)?.Path;

    /// <summary>
    /// Review Focus 2: whether the ticket's window is still there to save. The add-in's mark says so where the program
    /// shows it, looked for every <see cref="MarkPoll"/> for up to <see cref="MarkWait"/>; otherwise the program's front
    /// window must still be the one taken at <c>save-new</c>, by its path and its window's name. PowerPoint never showed the
    /// mark (spec §8.5), so it isn't waited for there: the window's name decides.
    /// </summary>
    private async Task<bool> IsTheWindowAsync(IOfficeScripting scripting, FilingTicket t, string? mark)
    {
        if (mark is not null && t.Program != OfficeProgram.PowerPoint)
        {
            var waited = Stopwatch.StartNew();
            while (true)
            {
                try
                {
                    if (await scripting.HasMarkAsync(t.Program, mark))
                        return true;
                }
                catch (OfficeScriptException)
                {
                    // Looked for again, then the front window decides.
                }
                if (waited.Elapsed >= _markWait)
                    break;
                await Task.Delay(MarkPoll);
            }
        }
        var front = await scripting.FrontDocumentAsync(t.Program);
        return front is not null && front.WindowName == t.Window.WindowName && SamePath(front.Path, t.Window.Path);
    }

    /// <summary>A refusal of the window, told to the user: nothing has been filed.</summary>
    private OfficeRefusedException Refused(string app, string notice)
    {
        log.Warn($"Save to Healdan in {app} filed nothing: the window it was pressed in wasn't there to save");
        notifier.Notify(Helper, notice);
        return new OfficeRefusedException(OfficeRefusedException.WrongWindow, notice);
    }

    /// <summary>The copy at <paramref name="path"/>, when it's in the copies folder and the cycle knows it; null otherwise.</summary>
    private CopyState? KnownCopy(string? path)
    {
        if (string.IsNullOrEmpty(path) || !Path.IsPathFullyQualified(path))
            return null;
        string full;
        try
        {
            full = Resolved(path);
        }
        catch (ArgumentException)
        {
            return null; // not a path at all
        }
        if (Path.GetDirectoryName(full) != Resolved(paths.Copies))
            return null;
        var state = cycle.StateOf(Path.Combine(paths.Copies, Path.GetFileName(full)));
        return state.Known ? state : null;
    }

    private static bool SamePath(string? a, string? b) => a is null || b is null ? a == b : Resolved(a) == Resolved(b);

    /// <summary>
    /// The path in full, without <c>/private</c>: on macOS <c>/tmp</c> and <c>/private/tmp</c> are one folder, and Word
    /// reports a document's path as it was opened.
    /// </summary>
    private static string Resolved(string path)
    {
        var full = Path.TrimEndingDirectorySeparator(Path.GetFullPath(path)).Normalize(NormalizationForm.FormC);
        return full.StartsWith("/private/", StringComparison.Ordinal) ? full["/private".Length..] : full;
    }

    /// <summary>The window's name for Save to Healdan's window: the document's, else its window's, without an Office extension.</summary>
    private static string NameOf(FrontDocument window)
    {
        var name = window.Name.Length > 0 ? window.Name : window.WindowName ?? "";
        return OfficePrograms.For(name) is not null ? name[..^Path.GetExtension(name).Length] : name;
    }

    /// <summary>The document's name with the program's own format's extension, which the API wants.</summary>
    private static string FileName(OfficeProgram p, string name)
    {
        var extension = OfficePrograms.OwnFormat(p);
        return name.EndsWith(extension, StringComparison.OrdinalIgnoreCase) ? name : name + extension;
    }

    private IOfficeScripting Scripting() =>
        apps.MicrosoftOffice?.Scripting ?? throw new InvalidOperationException("Microsoft Office isn't set up for Healdan Helper on this computer.");

    /// <summary>Writes the bytes for the filing to the helper's Temp folder, which only the user can read.</summary>
    private async Task WriteTemporaryAsync(string tempPath, byte[] bytes)
    {
        if (OperatingSystem.IsWindows())
            Directory.CreateDirectory(paths.Temp);
        else
            Directory.CreateDirectory(paths.Temp, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        await File.WriteAllBytesAsync(tempPath, bytes);
    }

    private void DeleteTemporary(string tempPath)
    {
        try
        {
            File.Delete(tempPath);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            log.Warn("The temporary file couldn't be deleted: " + e.Message);
        }
    }

    private static long FolderId(JsonObject answer) =>
        answer["folderId"] is JsonValue id && id.GetValueKind() == JsonValueKind.Number
        && long.TryParse(id.ToJsonString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var folder) && folder >= 0
            ? folder
            : throw new ArgumentException("The Save window's folderId is missing or isn't a folder's id.", nameof(answer));

    private static string Text(JsonObject answer, string key) =>
        answer[key] is JsonValue v && v.TryGetValue<string>(out var s) && s.Length > 0
            ? s
            : throw new ArgumentException($"The Save window's {key} is missing or isn't text.", nameof(answer));
}
