namespace Healdan.Helper.Core.Ui;

/// <summary>
/// How the helper speaks up when something needs the user (spec §5.1): the system's native notifications, never
/// AppleScript. The app implements it; calls come from any thread, and a notifier never throws.
/// </summary>
public interface INotifier
{
    /// <param name="title">A few words: the copy's title, or what happened.</param>
    /// <param name="message">One or two short sentences, shown as they are.</param>
    void Notify(string title, string message);
}
