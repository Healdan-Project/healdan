using System.Text.Json.Nodes;
using Healdan.Helper.Core.Cycle;

namespace Healdan.Helper.Core.Ui;

/// <summary>
/// The helper's own windows (spec §5.8), as the window-free code asks for them. The app implements it; calls come from
/// any thread. Each window comes to the front over the app and hands the focus back when it closes (spec §5.1).
/// </summary>
public interface IWindows
{
    /// <summary>One of the windows is waiting for the user's answer. Links are dropped meanwhile (spec §5.3).</summary>
    bool Waiting { get; }

    /// <summary>A link was dropped because a window waits: that window comes to the front, so the user sees why.</summary>
    void BringWaiting();

    /// <summary>The Sign In window. True once the user has signed in; false when they closed it.</summary>
    Task<bool> SignInAsync(SignInPrompt prompt);

    /// <summary>
    /// Sign Out on This Computer Only? (spec §5.2): Sign Out couldn't reach <paramref name="server"/> to revoke the
    /// token. True when the user chose to forget the sign-in here; false for Stay Signed In, the default, or a close.
    /// <paramref name="unsent"/> copies have a save that never reached Healdan, and stay on this computer either way.
    /// </summary>
    Task<bool> SignOutHereAsync(string server, int unsent);

    /// <summary><c>/v1/open-dialog</c>: Open from Healdan. Answers <c>{"action": "opened" | "local" | "cancel"}</c>.</summary>
    Task<JsonObject> OpenDialogAsync(JsonObject request);

    /// <summary>
    /// <c>/v1/save-new</c>: Save to Healdan, or Save as New Document when <c>path</c> is set. Answers
    /// <c>{"action": "save" | "local" | "cancel", "folderId", "name", "format", "properties", "tempPath"}</c>, where
    /// <c>tempPath</c> is a new file in the helper's Temp folder for the extension to write the document to
    /// (<see cref="WindowAnswers.TempPath"/>). The channel has checked <c>path</c>: it's a local copy's, with the
    /// copy's <c>docNumber</c> added, or null.
    /// </summary>
    Task<JsonObject> SaveNewAsync(JsonObject request);

    /// <summary>
    /// <c>/v1/properties</c>: the open document's properties. Answers <c>{"action": "saved" | "cancel"}</c>. The
    /// channel has checked <c>path</c> is a local copy's and added its <c>docNumber</c>; for a file that isn't, it
    /// answers <c>{"action": "file"}</c> itself, without a window.
    /// </summary>
    Task<JsonObject> PropertiesAsync(JsonObject request);

    /// <summary>
    /// The document is checked out to this user on another computer (spec §5.10). The window offers Open Read-Only,
    /// Edit Here Instead (<see cref="CopyCycle.MoveHereAsync"/>) and Cancel; nothing has been opened.
    /// </summary>
    void HeldElsewhere(long docNumber, string deviceName, string? app, DateTimeOffset? lastSaved);

    /// <summary>The app went away with the copy open (spec §5.8, §5.10). The window offers Reopen and Send and Check In.</summary>
    void Crashed(CopyState copy);

    /// <summary>
    /// The crashed copy's app has it open again, and the helper has saved what it recovered (M3.5 spec §5.14): its After
    /// a Crash window goes, if it's up, as neither Reopen nor Send and Check In applies any more.
    /// </summary>
    void DismissCrashed(string path);

    /// <summary>
    /// Set Up Microsoft Office (M3.5 spec §5.13): says what the helper is about to ask for and why, and its Set Up has the
    /// Mac trust the helper's certificate. True once the certificate is trusted; false for Not Now, a close, or a Set Up the
    /// user cancelled or that failed. Asks nothing a link waits on, so it holds none back.
    /// </summary>
    Task<bool> SetUpOfficeAsync();
}
