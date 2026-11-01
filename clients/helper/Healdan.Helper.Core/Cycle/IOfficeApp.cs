namespace Healdan.Helper.Core.Cycle;

/// <summary>The app a copy opens in, as far as the cycle needs it: open a file, and tell whether it's still there.</summary>
public interface IOfficeApp
{
    /// <summary>What a copy's record names as its app, such as <c>LibreOffice</c> or <c>Microsoft Office</c>.</summary>
    string Name { get; }

    /// <summary>Opens the copy, or brings its window to the front if it's open already.</summary>
    void Open(string path);

    /// <summary>
    /// Starts the program the copy opens in without opening it, for a crashed copy the program restores by itself (M3.5
    /// spec §5.14). An app that restores nothing opens the copy.
    /// </summary>
    void Start(string path) => Open(path);

    /// <summary>Whether the app's process is running; when it isn't but a copy's lock file is left, the app crashed.</summary>
    bool IsRunning();

    /// <summary>Whether the app holds its lock file on the copy, which it keeps while the copy's window is open.</summary>
    bool LockExists(string path);

    /// <summary>
    /// What the check-out names (<c>hold.device.app</c>) for a file opened in this app, such as <c>Word</c>: the app's own
    /// <see cref="Name"/> unless it is several programs, as Microsoft Office is.
    /// </summary>
    string CheckOutName(string fileName) => Name;

    /// <summary>
    /// Whether the process that has this copy open is running: the crash check asks it of each copy, as one app may be
    /// several programs (Word running says nothing of an Excel copy). The app's <see cref="IsRunning"/> unless it knows better.
    /// </summary>
    bool IsRunningFor(string path) => IsRunning();

    /// <summary>
    /// When the app's lock file on the copy was last written, or null when it has none, or can't tell: a crashed copy's
    /// lock file written again means the app has the copy open again (M3.5 spec §5.14).
    /// </summary>
    DateTime? LockWritten(string path) => null;

    /// <summary>
    /// What drives the app's programs, for an app the cycle does more with than open copies: Microsoft Office, whose
    /// copies are renamed after each save and whose recovered windows are saved back (M3.5 spec §5.4, §5.14). Null for
    /// an app that only opens them.
    /// </summary>
    Office.IOfficeScripting? Scripting => null;
}
