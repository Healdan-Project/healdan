namespace Healdan.Helper.Core.Cycle;

/// <summary>
/// What the cycle tells the app, which shows it (Task 16). Calls come from background threads, and nothing the cycle
/// passes contains a token or a file's contents.
/// </summary>
public interface ICycleEvents
{
    /// <summary>Healdan confirmed a save of the copy; <paramref name="state"/> has the new title.</summary>
    void Confirmed(string path, CopyState state);

    /// <summary>
    /// The document is checked out to this user on another computer (spec §5.10): the app offers Open Read-Only, Edit
    /// Here Instead (<see cref="CopyCycle.MoveHereAsync"/>) and Cancel. <paramref name="path"/> is null from an open.
    /// </summary>
    void HeldElsewhere(string? path, long docNumber, string deviceName, string? app, DateTimeOffset? lastSaved);

    /// <summary>The check-out ended here, so the copy's saves are refused; the copy is kept, and the app offers Save as New Document.</summary>
    void CheckoutEnded(string path);

    /// <summary>The app went away with the copy open; the copy and its check-out are kept, and the app offers Reopen.</summary>
    void Crashed(string path);

    /// <summary>How many copies have a save waiting for the server; 0 once everything has gone up.</summary>
    void Waiting(int pending);

    /// <summary>The token was refused; saves wait until the user signs in again.</summary>
    void SignInNeeded();

    /// <summary>Something failed that the user should hear about, in a sentence to show as it is.</summary>
    void Error(string message);
}
