using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Platform;

/// <summary>
/// How the helper speaks up (spec §5.1): a native notification on macOS once the user has allowed them. When it can't
/// (a build run by hand, or notifications refused), the notice goes to the log and becomes the menu's status line.
/// Every notice is logged.
/// </summary>
internal sealed class Notifier(HelperLog log) : INotifier
{
    private const string Helper = "Healdan Helper";

    private volatile string? _status;

    /// <summary>The last notice that couldn't be shown as a notification, for the menu; null when there's none.</summary>
    public string? Status => _status;

    /// <summary>Raised on any thread when <see cref="Status"/> changes.</summary>
    public event Action? StatusChanged;

    /// <summary>Asks macOS for permission to notify, once, at start.</summary>
    public void Start() => MacNotifications.RequestPermission(log);

    public void Notify(string title, string message)
    {
        log.Info($"Notice: {title}: {message}");
        bool shown;
        try
        {
            shown = MacNotifications.TryPost(title, message);
        }
        catch (Exception e)
        {
            log.Error("The notification couldn't be shown", e);
            shown = false;
        }
        if (!shown)
            SetStatus(title == Helper ? message : $"{title}: {message}");
    }

    /// <summary>The menu's status line was clicked: the user has read it.</summary>
    public void ClearStatus() => SetStatus(null);

    private void SetStatus(string? status)
    {
        _status = status;
        try
        {
            StatusChanged?.Invoke();
        }
        catch (Exception e)
        {
            log.Error("The menu couldn't show the notice", e);
        }
    }
}
