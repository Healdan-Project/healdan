using Avalonia.Controls;
using Avalonia.Interactivity;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Windows;

/// <summary>
/// Sign In (spec §5.2): the server's address, a username and a password. Signing in is the prompt's, which checks the
/// server is Healdan and keeps only the token; this window shows its refusals as they are. The password box is
/// emptied as soon as Sign In is pressed, so the window never holds the password beyond the one call.
/// </summary>
public partial class SignInWindow : Window
{
    private readonly SignInPrompt? _prompt;
    private bool _closed;

    /// <summary>For the XAML loader; the helper always opens it with a prompt.</summary>
    public SignInWindow() => InitializeComponent();

    public SignInWindow(SignInPrompt prompt) : this()
    {
        _prompt = prompt;
        Server.Text = prompt.Server;
        Server.IsReadOnly = prompt.ServerFixed;
        SignInButton.Click += OnSignIn;
        CancelButton.Click += (_, _) => Close();
        Closed += (_, _) => _closed = true;
        Opened += (_, _) => (string.IsNullOrEmpty(Server.Text) ? Server : string.IsNullOrEmpty(User.Text) ? User : Password).Focus();
    }

    /// <summary>True once the user has signed in; false when they closed the window.</summary>
    public bool SignedIn { get; private set; }

    private async void OnSignIn(object? sender, RoutedEventArgs e)
    {
        if (_prompt is null)
            return;
        var (server, user, password) = (Server.Text ?? "", User.Text ?? "", Password.Text ?? "");
        Password.Text = "";
        SetBusy(true);
        string? problem;
        try
        {
            problem = await Task.Run(() => _prompt.SignInAsync(server, user, password));
        }
        catch (Exception ex)
        {
            problem = "Signing in failed: " + ex.Message;
        }
        if (_closed)
            return; // closed while signing in: a sign-in that worked starts the helper all the same
        SetBusy(false);
        if (problem is null)
        {
            SignedIn = true;
            Close();
            return;
        }
        Problem.Text = problem;
        Problem.IsVisible = true;
        Password.Focus();
    }

    private void SetBusy(bool busy)
    {
        Server.IsEnabled = User.IsEnabled = Password.IsEnabled = SignInButton.IsEnabled = !busy;
        SignInButton.Content = busy ? "Signing In…" : "Sign In";
    }
}
