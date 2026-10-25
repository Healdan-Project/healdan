namespace Healdan.Helper.Core.Cycle;

/// <summary>The app a copy opens in, as far as the cycle needs it: open a file, and tell whether it's still there.</summary>
public interface IOfficeApp
{
    /// <summary>What the check-out names (<c>hold.device.app</c>), such as <c>LibreOffice</c>.</summary>
    string Name { get; }

    /// <summary>Opens the copy, or brings its window to the front if it's open already.</summary>
    void Open(string path);

    /// <summary>Whether the app's process is running; when it isn't but a copy's lock file is left, the app crashed.</summary>
    bool IsRunning();

    /// <summary>Whether the app holds its lock file on the copy, which it keeps while the copy's window is open.</summary>
    bool LockExists(string path);
}
