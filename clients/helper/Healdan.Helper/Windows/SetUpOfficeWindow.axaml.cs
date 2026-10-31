using Avalonia.Controls;
using Avalonia.Interactivity;
using Healdan.Helper.Core.Addin;
using Healdan.Helper.Core.Logging;

namespace Healdan.Helper.Windows;

/// <summary>
/// Set Up Microsoft Office (M3.5 spec §5.13): the three things the helper needs from the user before Word, Excel and
/// PowerPoint can use it, each once, and why. Set Up does the first (<see cref="OfficeSetUp.TrustAsync"/>): the Mac asks for
/// the user's password, and a tick shows when it is trusted. A cancelled prompt or a failure shows as a line under the rows,
/// and Set Up can be tried again. Not Now, the Escape key and a close leave it for the menu; closing while the Mac asks stops
/// the ask.
/// </summary>
public partial class SetUpOfficeWindow : Window
{
    private readonly OfficeSetUp? _setUp;
    private readonly HelperLog? _log;
    private CancellationTokenSource? _running;
    private bool _closed;

    /// <summary>For the XAML loader; the helper always opens it with its Set Up.</summary>
    public SetUpOfficeWindow() => InitializeComponent();

    internal SetUpOfficeWindow(OfficeSetUp setUp, HelperLog log) : this()
    {
        (_setUp, _log) = (setUp, log);
        SetUpButton.Click += OnSetUp;
        NotNowButton.Click += (_, _) => Close();
        Closed += (_, _) =>
        {
            _closed = true;
            _running?.Cancel();
        };
        Opened += (_, _) => SetUpButton.Focus();
    }

    /// <summary>True once the Mac trusts the certificate; false when the user chose Not Now or closed the window first.</summary>
    internal bool Trusted { get; private set; }

    private async void OnSetUp(object? sender, RoutedEventArgs e)
    {
        if (Trusted)
        {
            Close(); // the button reads Done
            return;
        }
        if (_setUp is null)
            return;
        ProblemText.IsVisible = false;
        SetUpButton.IsEnabled = false;
        SetUpButton.Content = "Setting Up…";
        using var running = _running = new CancellationTokenSource();
        SetUpResult result;
        try
        {
            result = await Task.Run(() => _setUp.TrustAsync(running.Token));
        }
        catch (OperationCanceledException)
        {
            return; // closed while the Mac was asking
        }
        finally
        {
            _running = null;
        }
        if (_closed)
            return;

        _log?.Info($"Set Up Microsoft Office: {(result.Trusted ? "trusted" : result.Problem is null ? "cancelled" : "failed: " + result.Problem)}");
        SetUpButton.IsEnabled = true;
        if (result.Trusted)
        {
            Trusted = true;
            DoneMark.IsVisible = true;
            NotNowButton.IsVisible = false;
            SetUpButton.Content = "Done";
            return;
        }
        SetUpButton.Content = "Set Up";
        ProblemText.Text = result.Problem ?? "macOS didn't trust the certificate, because the password prompt was cancelled. Choose Set Up to try again.";
        ProblemText.IsVisible = true;
    }
}
