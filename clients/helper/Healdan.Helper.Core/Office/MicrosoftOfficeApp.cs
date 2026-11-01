using Healdan.Helper.Core.Cycle;

namespace Healdan.Helper.Core.Office;

/// <summary>
/// Microsoft Office as the cycle's app (M3.5 spec §5.1): Word, Excel and PowerPoint, each opening the files it opens,
/// driven through <see cref="IOfficeScripting"/>. A copy's record names it <see cref="AppName"/>, whichever of the three
/// holds it, and the check-out names the program (<c>Word</c>, <c>Excel</c> or <c>PowerPoint</c>). Each program keeps an
/// owner file (<c>~$…</c>) beside a copy it has open (<see cref="OfficePrograms.OwnerNames"/>), which the cycle watches as
/// it watches LibreOffice's lock file, and is a process of its own to the crash check.
/// </summary>
/// <param name="scripting">Opens the copies.</param>
/// <param name="processRunning">Whether a process of that name runs (<see cref="OfficePrograms.ProcessName"/>), as the platform sees it.</param>
public sealed class MicrosoftOfficeApp(IOfficeScripting scripting, Func<string, bool> processRunning) : IOfficeApp
{
    /// <summary>What a copy's record names as its app.</summary>
    public const string AppName = "Microsoft Office";

    public string Name => AppName;

    /// <summary>
    /// What drives the programs: for the cycle's rename after each save and its crash recovery
    /// (<see cref="OfficeAfterSave"/>), and for the add-in's routes and the filing, which use the same seam.
    /// </summary>
    public IOfficeScripting Scripting => scripting;

    /// <summary>
    /// The program that opens a file: by <see cref="OfficePrograms.For"/>, else, for an OpenDocument file that LibreOffice
    /// isn't there to open (Settings leave those to LibreOffice while it is), the program for its kind: Word for text,
    /// Excel for a spreadsheet and PowerPoint for a presentation. Null for a drawing and anything else.
    /// </summary>
    public static OfficeProgram? ProgramFor(string fileName) =>
        OfficePrograms.For(fileName) ?? Path.GetExtension(fileName).ToLowerInvariant() switch
        {
            ".odt" or ".ott" => OfficeProgram.Word,
            ".ods" => OfficeProgram.Excel,
            ".odp" => OfficeProgram.PowerPoint,
            _ => null,
        };

    public string CheckOutName(string fileName) =>
        ProgramFor(fileName) is { } program ? OfficePrograms.CheckOutName(program) : Name;

    public void Open(string path)
    {
        var program = ProgramFor(path) ?? throw new IOException($"Microsoft Office doesn't open {Path.GetExtension(path)} files.");
        // Off the caller's context, so a window's thread that asked can't deadlock on the script's answer.
        Task.Run(() => scripting.OpenAsync(program, path)).GetAwaiter().GetResult();
    }

    public void Start(string path)
    {
        var program = ProgramFor(path) ?? throw new IOException($"Microsoft Office doesn't open {Path.GetExtension(path)} files.");
        Task.Run(() => scripting.StartAsync(program)).GetAwaiter().GetResult();
    }

    /// <summary>Whether any of the three programs is running.</summary>
    public bool IsRunning() => Enum.GetValues<OfficeProgram>().Any(p => processRunning(OfficePrograms.ProcessName(p)));

    public bool IsRunningFor(string path) =>
        ProgramFor(path) is { } program ? processRunning(OfficePrograms.ProcessName(program)) : IsRunning();

    public bool LockExists(string path) => OwnerFiles(path).Any(File.Exists);

    /// <summary>When the newest of the program's owner files beside the copy was last written; null when there's none.</summary>
    public DateTime? LockWritten(string path)
    {
        DateTime? newest = null;
        foreach (var owner in OwnerFiles(path))
        {
            try
            {
                var info = new FileInfo(owner);
                if (info.Exists && (newest is null || info.LastWriteTimeUtc > newest))
                    newest = info.LastWriteTimeUtc;
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                // Looked at again at the next poll.
            }
        }
        return newest;
    }

    /// <summary>Where the program that opens the file may keep its owner file (<see cref="OfficePrograms.OwnerNames"/>).</summary>
    private static IEnumerable<string> OwnerFiles(string path)
    {
        if (ProgramFor(path) is not { } program)
            return [];
        var folder = Path.GetDirectoryName(path)!;
        return OfficePrograms.OwnerNames(program, Path.GetFileName(path)).Select(owner => Path.Combine(folder, owner));
    }
}
