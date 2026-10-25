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

    /// <summary>The Sign In window. True once the user has signed in; false when they closed it.</summary>
    Task<bool> SignInAsync(SignInPrompt prompt);

    /// <summary><c>/v1/open-dialog</c>: Open from Healdan. Answers <c>{"action": "opened" | "local" | "cancel"}</c>.</summary>
    Task<JsonObject> OpenDialogAsync(JsonObject request);

    /// <summary>
    /// <c>/v1/save-new</c>: Save to Healdan, or Save as New Document when <c>path</c> is set. Answers
    /// <c>{"action": "save" | "local" | "cancel", "folderId", "name", "format", "properties", "tempPath"}</c>.
    /// </summary>
    Task<JsonObject> SaveNewAsync(JsonObject request);

    /// <summary><c>/v1/properties</c>: the open document's properties. Answers <c>{"action": "saved" | "cancel" | "file"}</c>.</summary>
    Task<JsonObject> PropertiesAsync(JsonObject request);

    /// <summary>
    /// The document is checked out to this user on another computer (spec §5.10). The window offers Open Read-Only,
    /// Edit Here Instead (<see cref="CopyCycle.MoveHereAsync"/>) and Cancel; nothing has been opened.
    /// </summary>
    void HeldElsewhere(long docNumber, string deviceName, string? app, DateTimeOffset? lastSaved);

    /// <summary>The app went away with the copy open (spec §5.8, §5.10). The window offers Reopen and Send and Check In.</summary>
    void Crashed(CopyState copy);
}
