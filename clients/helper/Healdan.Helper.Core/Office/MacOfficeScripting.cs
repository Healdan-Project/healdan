using System.Security.Cryptography;
using System.Text;

namespace Healdan.Helper.Core.Office;

/// <summary>
/// <see cref="IOfficeScripting"/> on macOS: AppleScript, run by <c>osascript</c> through an <see cref="IOsaRunner"/>
/// (M3.5 spec §5.2). Each operation has a script per program in <c>Office/Scripts</c>, embedded as fixed text. Nothing is
/// ever built into a script: every path, name and mark goes in as an argument and is read as <c>argv</c>, so a document
/// named <c>a"; do shell script "…</c> is only a name.
/// </summary>
public sealed class MacOfficeScripting(IOsaRunner runner) : IOfficeScripting
{
    private const string ResourcePrefix = "Healdan.Helper.Core.Office.Scripts.";

    /// <summary>Separates a script's answers that hold several texts: the ASCII unit separator, which no name has.</summary>
    private const char Separator = '\u001F';

    /// <summary>
    /// What a script says when it may be waiting for the user (Grant File Access), as the scripts that save or open files say it.
    /// A script that says it gets <see cref="LongTimeout"/> as a process, which is a little more than its own limit.
    /// </summary>
    private const string LongWait = "with timeout of 300 seconds";

    private static readonly TimeSpan ShortTimeout = TimeSpan.FromSeconds(20);
    private static readonly TimeSpan LongTimeout = TimeSpan.FromSeconds(330);

    private static readonly Dictionary<string, string> Texts = [];

    public async Task OpenAsync(OfficeProgram p, string path, CancellationToken ct = default)
    {
        if (p == OfficeProgram.Word)
        {
            // Word's own open takes add to recent files false. Excel and PowerPoint are handed the file by open -a, which they
            // open as for a double click.
            await RunAsync(p, "Open", [path], ct);
            return;
        }
        await runner.RunProcessAsync("/usr/bin/open", ["-a", OfficePrograms.ProcessName(p), path], ShortTimeout, ct);
    }

    public async Task<FrontDocument?> FrontDocumentAsync(OfficeProgram p, CancellationToken ct = default)
    {
        var answer = await RunAsync(p, "FrontDocument", [], ct);
        if (answer.Length == 0)
            return null;
        var parts = answer.Split(Separator);
        if (parts.Length != 3)
            throw Unexpected(p, "FrontDocument");
        return new FrontDocument(NullIfEmpty(parts[0]), parts[1], NullIfEmpty(parts[2]));
    }

    public async Task<bool> IsOpenAsync(OfficeProgram p, string path, CancellationToken ct = default) =>
        YesOrNo(p, "IsOpen", await RunAsync(p, "IsOpen", ByPath(path), ct));

    public async Task<bool?> HasUnsavedChangesAsync(OfficeProgram p, string path, CancellationToken ct = default) =>
        await RunAsync(p, "HasUnsavedChanges", ByPath(path), ct) switch
        {
            "missing" => null,
            "unsaved" => true,
            "saved" => false,
            _ => throw Unexpected(p, "HasUnsavedChanges"),
        };

    public async Task<string?> FingerprintAsync(OfficeProgram p, DocumentRef doc, CancellationToken ct = default)
    {
        var answer = await RunAsync(p, "Fingerprint", Finders(doc), ct);
        return answer.Length == 0 ? null : Hash(answer);
    }

    public async Task<bool> HasMarkAsync(OfficeProgram p, string mark, CancellationToken ct = default) =>
        mark.Length > 0 && YesOrNo(p, "HasMark", await RunAsync(p, "HasMark", [mark], ct));

    public async Task SaveAsync(OfficeProgram p, string path, CancellationToken ct = default)
    {
        if (await RunAsync(p, "Save", ByPath(path), ct) != "saved")
            throw new OfficeScriptException($"{OfficePrograms.CheckOutName(p)} doesn't have the document open, so it wasn't saved.");
    }

    public async Task<RenameResult> RenameIfUnchangedAsync(OfficeProgram p, string path, string newPath, CancellationToken ct = default)
    {
        if (!OfficePrograms.RenamesAfterSave(p))
            throw new NotSupportedException($"{OfficePrograms.CheckOutName(p)} copies keep the name they were opened with.");
        var extension = FormatOf(p, newPath);
        // renamed-but-lost: the file was written but no document carries the new name; the cycle finds the copy by that name.
        return await RunAsync(p, "Rename", [.. ByPath(path), newPath, Path.GetFileName(newPath), extension], ct) switch
        {
            "missing" => RenameResult.Missing,
            "unsaved" => RenameResult.Unsaved,
            "renamed" or "renamed-but-lost" => RenameResult.Renamed,
            "renamed-changed" => RenameResult.RenamedChanged,
            _ => throw Unexpected(p, "Rename"),
        };
    }

    public async Task<SaveAsResult> SaveAsAsync(OfficeProgram p, DocumentRef doc, string newPath, string? fingerprint, CancellationToken ct = default)
    {
        var extension = FormatOf(p, newPath);
        var answer = await RunAsync(p, "SaveAs", [.. Finders(doc), newPath, extension], ct);
        if (answer == "missing")
            throw new OfficeScriptException($"{OfficePrograms.CheckOutName(p)} has no document to save.");
        var split = answer.IndexOf(Separator);
        if (split < 0)
            throw Unexpected(p, "SaveAs");
        var changed = fingerprint is not null && !string.Equals(Hash(answer[(split + 1)..]), fingerprint, StringComparison.OrdinalIgnoreCase);
        return new SaveAsResult(answer[..split], changed);
    }

    public async Task ReopenAsync(OfficeProgram p, string path, CancellationToken ct = default)
    {
        if (p != OfficeProgram.PowerPoint)
            throw new NotSupportedException($"{OfficePrograms.CheckOutName(p)} documents don't need to be opened again after a save.");
        switch (await RunAsync(p, "Reopen", ByPath(path), ct))
        {
            case "reopened":
                return;
            case "unsaved":
                throw new OfficeScriptException("PowerPoint has unsaved changes in the presentation, so it was left open as it is.");
            case "missing":
                throw new OfficeScriptException("PowerPoint doesn't have the presentation open.");
            default:
                throw Unexpected(p, "Reopen");
        }
    }

    public async Task<bool> SetDocIdAsync(string path, string docRef, CancellationToken ct = default) =>
        await RunAsync(OfficeProgram.Word, "SetDocId", [.. ByPath(path), docRef], ct) switch
        {
            "ok" => true,
            "no-property" or "missing" => false,
            _ => throw Unexpected(OfficeProgram.Word, "SetDocId"),
        };

    /// <summary>The embedded script of a program's operation, <c>Word.Rename</c> for instance.</summary>
    internal static string ScriptText(OfficeProgram p, string operation)
    {
        var name = $"{p}.{operation}";
        lock (Texts)
        {
            if (Texts.TryGetValue(name, out var text))
                return text;
            using var stream = typeof(MacOfficeScripting).Assembly.GetManifestResourceStream($"{ResourcePrefix}{name}.applescript")
                ?? throw new InvalidOperationException($"The script {name} isn't embedded in the helper.");
            using var reader = new StreamReader(stream, Encoding.UTF8);
            return Texts[name] = reader.ReadToEnd();
        }
    }

    private Task<string> RunAsync(OfficeProgram p, string operation, IReadOnlyList<string> args, CancellationToken ct)
    {
        var script = ScriptText(p, operation);
        // The process waits a little longer than a script that waits for Grant File Access, and no longer than 20 s for any other.
        var timeout = script.Contains(LongWait, StringComparison.Ordinal) ? LongTimeout : ShortTimeout;
        return runner.RunAsync(script, args, timeout, ct);
    }

    /// <summary>A document found by its path: the path as given, and its file name, which an app that reports another spelling of the path still matches.</summary>
    private static string[] ByPath(string path) => [path, Path.GetFileName(path)];

    /// <summary>The ways to find a document, in the order the scripts try them: path, file name, mark, window name.</summary>
    private static string[] Finders(DocumentRef doc) =>
        [doc.Path ?? "", doc.Path is null ? "" : Path.GetFileName(doc.Path), doc.Mark ?? "", doc.WindowName ?? ""];

    /// <summary>The extension a document is saved under, which picks the file format; one the program doesn't save is refused.</summary>
    private static string FormatOf(OfficeProgram p, string newPath)
    {
        if (OfficePrograms.For(newPath) != p)
            throw new ArgumentException($"{OfficePrograms.CheckOutName(p)} doesn't save files with the extension of '{Path.GetFileName(newPath)}'.", nameof(newPath));
        return Path.GetExtension(newPath)[1..].ToLowerInvariant();
    }

    private static bool YesOrNo(OfficeProgram p, string operation, string answer) => answer switch
    {
        "yes" => true,
        "no" => false,
        _ => throw Unexpected(p, operation),
    };

    private static string? NullIfEmpty(string s) => s.Length == 0 ? null : s;

    private static string Hash(string text) => Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(text)));

    /// <summary>An answer the helper doesn't know. The message holds no answer: it may hold a document's content.</summary>
    private static OfficeScriptException Unexpected(OfficeProgram p, string operation) =>
        new($"{OfficePrograms.CheckOutName(p)} gave an answer to {operation} that Healdan Helper can't read.");
}
