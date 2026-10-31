using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Threading;

namespace Healdan.Helper.Windows;

/// <summary>
/// Pairing (M3.5 spec §5.9): Word, Excel or PowerPoint asked to connect, and this is the only place its code shows, in
/// large digits, three and three. The user types it in the app's Healdan pane. It closes once the app has paired, or a
/// newer pairing replaces it (<see cref="EndQuietly"/>), when the code expires, and at Cancel, the Escape key or a close,
/// which end the pairing (<see cref="Cancelled"/>).
/// </summary>
public partial class PairingWindow : Window
{
    private IDisposable? _expiry;
    private bool _ended;

    /// <summary>For the XAML loader; the helper always opens it with a code.</summary>
    public PairingWindow() => InitializeComponent();

    internal PairingWindow(string app, string code, DateTimeOffset expires) : this()
    {
        Code = code;
        Title = $"Pair {app}";
        HeadlineText.Text = $"{app} wants to connect to Healdan Helper";
        CodeText.Text = Spaced(code);
        AutomationProperties.SetName(CodeText, "Code " + string.Join(' ', code.ToCharArray())); // read out digit by digit
        DetailText.Text = $"Type this code in the Healdan pane in {app}. It expires in 2 minutes.";
        CancelButton.Click += (_, _) => Close();
        Opened += (_, _) =>
        {
            var left = expires - DateTimeOffset.UtcNow;
            _expiry = DispatcherTimer.RunOnce(EndQuietly, left > TimeSpan.Zero ? left : TimeSpan.Zero);
        };
        Closed += (_, _) => _expiry?.Dispose();
    }

    /// <summary>The code it shows, which its Cancel names, so a window a newer pairing replaced cancels nothing.</summary>
    internal string Code { get; } = "";

    /// <summary>The user closed it: Cancel, the Escape key or the window's close. False when the pairing ended by itself.</summary>
    internal bool Cancelled => !_ended;

    /// <summary>The pairing is over (the app paired, the code expired, or a newer pairing replaced it): closes, cancelling nothing.</summary>
    internal void EndQuietly()
    {
        _ended = true;
        Close();
    }

    /// <summary>Six digits as two groups of three, as people read them out.</summary>
    internal static string Spaced(string code) => code.Length == 6 ? $"{code[..3]} {code[3..]}" : code;
}
