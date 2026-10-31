using System.Diagnostics;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Logging;

namespace Healdan.Helper.Core.Office;

/// <summary>
/// What the cycle asks of Word, Excel and PowerPoint once a copy is open (M3.5 spec §5.4, §5.5 step 7, §5.7, §5.14),
/// through <see cref="IOfficeScripting"/>: renaming a copy after a confirmed save, so the window's title moves on;
/// moving Word's doc ID footer on; saving the window a program restored after a crash back over its copy; and
/// PowerPoint's close and reopen. <see cref="CopyCycle"/> decides when, and keeps the copy, its record and its watchers
/// right around each call; this class picks the program, tells the user before a program's first save into Copies
/// (<see cref="CopiesNotice"/>), and makes the call. A call that fails, as when the program is busy or a dialog is in
/// its way, is logged and answered as nothing done, so it never stops the cycle and never troubles the user.
/// </summary>
/// <param name="notice">The notice before a program's first save into Copies; none in tests that don't look for it.</param>
/// <param name="log">The helper's log, for the calls that fail.</param>
public sealed class OfficeAfterSave(CopiesNotice? notice = null, HelperLog? log = null)
{
    /// <summary>The crashed copies a save back didn't find, whose miss is logged already.</summary>
    private readonly HashSet<string> _missed = new(StringComparer.Ordinal);

    /// <summary>
    /// What drives the copy's program, or null when the cycle has nothing to do with it after a save: its app is driven
    /// through no scripting (LibreOffice, the default app), or its file is no Office program's own format. An
    /// OpenDocument file that Word has open is left alone, as LibreOffice's copies are (M3's R19).
    /// </summary>
    internal static (IOfficeScripting Scripting, OfficeProgram Program)? Driver(IOfficeApp app, string path) =>
        app.Scripting is { } scripting && OfficePrograms.For(path) is { } program ? (scripting, program) : null;

    /// <summary>
    /// Saves the copy under <paramref name="newPath"/> if its window has no unsaved changes
    /// (<see cref="IOfficeScripting.RenameIfUnchangedAsync"/>), after the notice if it's the program's first save into
    /// Copies. A call that fails is <see cref="RenameResult.Unsaved"/>: nothing moved, and the next confirmed save tries again.
    /// </summary>
    internal async Task<RenameResult> RenameAsync(IOfficeScripting scripting, OfficeProgram p, string path, string newPath)
    {
        notice?.BeforeFirstSave(p);
        try
        {
            return await scripting.RenameIfUnchangedAsync(p, path, newPath);
        }
        catch (Exception e)
        {
            log?.Warn($"{OfficePrograms.CheckOutName(p)} didn't rename {Path.GetFileName(path)}; the next save tries again: {e.Message}");
            return RenameResult.Unsaved;
        }
    }

    /// <summary>Word: moves the doc ID footer on to <paramref name="docRef"/> (spec §5.7). A document without the property is left alone.</summary>
    internal async Task MoveFooterOnAsync(IOfficeScripting scripting, OfficeProgram p, string path, string docRef)
    {
        if (p != OfficeProgram.Word)
            return;
        try
        {
            await scripting.SetDocIdAsync(path, docRef);
        }
        catch (Exception e)
        {
            log?.Warn($"Word didn't move the doc ID footer of {Path.GetFileName(path)} on to {docRef}: {e.Message}");
        }
    }

    /// <summary>
    /// Spec §5.14: the program may have the crashed copy open again, as Word's "Recovered" window, where Save opens Save
    /// As. Looks for the copy by its path, which never takes the front window (<see cref="IOfficeScripting.IsOpenAsync"/>),
    /// every <paramref name="every"/> for up to <paramref name="wait"/>, as the program writes its owner file before it
    /// lists the document; only once it's found, saves that window over the copy's own path, so the cycle's folder
    /// watcher sends what was recovered. False when the copy wasn't found, the save failed, or <paramref name="stop"/>
    /// said to: the cycle tries again later. A miss is logged once until a save back works.
    /// </summary>
    internal async Task<bool> SaveRecoveredAsync(IOfficeScripting scripting, OfficeProgram p, string path, TimeSpan wait,
        TimeSpan every, Func<bool> stop, CancellationToken ct)
    {
        var name = OfficePrograms.CheckOutName(p);
        var file = Path.GetFileName(path);
        var looking = Stopwatch.StartNew();
        while (true)
        {
            if (stop())
                return false;
            bool found;
            try
            {
                found = await scripting.IsOpenAsync(p, path, ct);
            }
            catch (Exception e) when (e is not OperationCanceledException || !ct.IsCancellationRequested)
            {
                found = false;
            }
            if (found)
                break;
            if (looking.Elapsed >= wait)
            {
                if (FirstMiss(path))
                    log?.Warn($"{name} didn't find {file} by its path within {wait.TotalSeconds:0} seconds after it closed "
                        + "unexpectedly, so nothing was saved over it; it's looked for again");
                return false;
            }
            await Task.Delay(every, ct);
        }
        if (stop())
            return false;
        log?.Info($"{name} has {file} open again after it closed unexpectedly: saving it back over its copy");
        notice?.BeforeFirstSave(p);
        try
        {
            var result = await scripting.SaveAsAsync(p, new DocumentRef(path, null, null), path, null);
            if (result.FoundBy != "path")
                log?.Warn($"{name} saved its {result.FoundBy} window over {file}, not finding the copy's own");
            lock (_missed)
                _missed.Remove(path);
            return true;
        }
        catch (Exception e)
        {
            log?.Warn($"{name} couldn't save {file} back after it closed unexpectedly, and tries again: {e.Message}");
            return false;
        }
    }

    /// <summary>True the first time since the last save back that worked that the copy wasn't found.</summary>
    private bool FirstMiss(string path)
    {
        lock (_missed)
            return _missed.Add(path);
    }

    /// <summary>
    /// PowerPoint, after it saved a presentation under a new name through the seam (spec §5.5 step 7): saves it where it
    /// is, then closes it and opens it again from its file, so that its own Save saves in place. If either fails, the
    /// presentation is left open as it was.
    /// </summary>
    internal async Task SaveAndReopenAsync(IOfficeScripting scripting, OfficeProgram p, string path)
    {
        try
        {
            await scripting.SaveAsync(p, path);
            await scripting.ReopenAsync(p, path);
        }
        catch (Exception e)
        {
            log?.Warn($"{OfficePrograms.CheckOutName(p)} didn't open {Path.GetFileName(path)} again: {e.Message}");
        }
    }
}
