using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Layout;
using Avalonia.Media;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Windows;

/// <summary>
/// Sign In (spec §5.2, M4 spec §6.1): the server's address, then a button for each of the server's sign-in providers
/// and the password form, which is folded behind the server's link when it has any, as on the web page. A server with
/// none, or one that can't be reached, gets the password form alone, as before. Signing in is the prompt's, which
/// checks the server is Healdan and keeps only the token; this window shows its refusals as they are. The password box
/// is emptied as soon as the password is sent, so the window never holds the password beyond the one call. The
/// prompt's notice, such as Healdan having ended the sign-in, stays above the fields while the window is up.
/// <para>The providers are asked for when the window opens and each time the address is changed and left. An answer
/// that didn't come (the server couldn't be reached) is asked for again, as a fixed address can't be left by clicking
/// into it: when the window is shown again, and when Sign In is pressed. Sign In asks first and sends the password
/// only if the providers still don't come, so a password isn't tried at an account that has none. A provider's
/// button sends the person to the browser, and the window says to finish there; Cancel, or closing the window, stops
/// the helper waiting for it. A refusal leaves the focus on the button that was used.</para>
/// </summary>
public partial class SignInWindow : Window
{
    private const string SignInLabel = "Sign In";

    private readonly SignInPrompt? _prompt;
    private bool _closed;

    /// <summary>The address the latest ask for providers was for; null before any.</summary>
    private string? _askedFor;

    /// <summary>The latest ask got no answer: the server couldn't be reached, so it is asked again when it can be.</summary>
    private bool _unanswered;

    /// <summary>The address the provider buttons shown belong to; null while none are.</summary>
    private string? _shownFor;

    /// <summary>Counts the asks, so an older one's late answer is dropped.</summary>
    private int _ask;

    /// <summary>The browser sign-in under way, which Cancel and closing the window stop; null when none is.</summary>
    private CancellationTokenSource? _browser;

    /// <summary>The person opened the password form with the link, so another server's providers don't fold it again.</summary>
    private bool _opened;

    /// <summary>A sign-in is under way: the fields and buttons are off, new ones included.</summary>
    private bool _busy;

    /// <summary>For the XAML loader; the helper always opens it with a prompt.</summary>
    public SignInWindow() => InitializeComponent();

    public SignInWindow(SignInPrompt prompt) : this()
    {
        _prompt = prompt;
        (NoticeText.Text, NoticeBox.IsVisible) = (prompt.Notice, prompt.Notice is not null);
        Server.Text = prompt.Server;
        Server.IsReadOnly = prompt.ServerFixed;
        if (prompt.ServerFixed)
            Server.Classes.Add("readonly");
        SignInButton.Click += OnSignIn;
        CancelButton.Click += (_, _) =>
        {
            if (_browser is { } browser)
                browser.Cancel(); // back to the choices: the window stays
            else
                Close();
        };
        PasswordLink.Click += (_, _) =>
        {
            _opened = true;
            Unfold();
        };
        Server.LostFocus += (_, _) => AskProviders();
        Closed += (_, _) =>
        {
            _closed = true;
            _browser?.Cancel(); // the helper stops listening for the browser
        };
        Opened += (_, _) =>
        {
            Problem.IsVisible = false; // from before the window was hidden: it is stale now
            (string.IsNullOrEmpty(Server.Text) ? Server : string.IsNullOrEmpty(User.Text) ? User : Password).Focus();
            AskProviders();
        };
    }

    /// <summary>True once the user has signed in; false when they closed the window.</summary>
    public bool SignedIn { get; private set; }

    /// <summary>The latest ask for the server's providers: done once the window shows its answer. For the tests.</summary>
    public Task Loading { get; private set; } = Task.CompletedTask;

    private string Address => (Server.Text ?? "").Trim();

    /// <summary>
    /// Asks for the providers of the address typed, unless they were asked for it already (an ask under way counts), or
    /// it's empty. An address that gave no answer is asked again next time: after a refused password the address is
    /// fixed, and an account that signs in through a provider has no password, so one blip mustn't leave the window
    /// without its buttons. Done once the window shows the answer.
    /// </summary>
    private Task AskProviders()
    {
        if (_prompt is null || _closed || (Address == _askedFor && !_unanswered))
            return Task.CompletedTask;
        _askedFor = Address;
        _unanswered = false;
        if (Address.Length == 0)
        {
            Show(null, null);
            return Task.CompletedTask;
        }
        return Loading = AskProvidersAsync(_prompt, Address, ++_ask);
    }

    private async Task AskProvidersAsync(SignInPrompt prompt, string address, int ask)
    {
        ProviderChoice? choice;
        try
        {
            choice = await Task.Run(() => prompt.ProvidersAsync(address));
        }
        catch (Exception)
        {
            choice = null; // the password form, whose Sign In then says what's wrong with the address
        }
        if (ask == _ask && !_closed)
        {
            _unanswered = choice is null && _askedFor == address;
            Show(choice, address);
        }
    }

    /// <summary>
    /// Shows the address's providers, with the password form folded behind the link, or the password form alone when
    /// there are none. The form stays open when the person has typed in it, or opened it with the link.
    /// </summary>
    private void Show(ProviderChoice? choice, string? address)
    {
        ProviderList.Children.Clear();
        var providers = choice?.Providers ?? [];
        _shownFor = providers.Count > 0 ? address : null;
        foreach (var provider in providers)
            ProviderList.Children.Add(ProviderButton(provider));
        ProviderList.IsVisible = providers.Count > 0;
        PasswordLink.Content = Text(choice?.PasswordLabel ?? "");
        AutomationProperties.SetName(PasswordLink, choice?.PasswordLabel);

        var typed = !string.IsNullOrEmpty(User.Text) || !string.IsNullOrEmpty(Password.Text);
        if (providers.Count == 0 || typed || _opened)
            Unfold(focus: false);
        else
            Fold();
    }

    private Button ProviderButton(ProviderInfo provider)
    {
        var button = new Button
        {
            Content = Text(provider.Label),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Center,
            Padding = new Avalonia.Thickness(12, 7),
            IsEnabled = !_busy // an answer that comes while a password sign-in runs
        };
        button.Classes.Add("accent");
        button.Tag = provider.Id; // to find it again for the focus
        AutomationProperties.SetName(button, provider.Label);
        button.Click += (_, _) => OnProvider(provider.Id);
        return button;
    }

    /// <summary>A label as it is: never markup, and an underscore isn't an access key.</summary>
    private static TextBlock Text(string label) => new() { Text = label, TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center };

    private void Fold()
    {
        var hadFocus = User.IsFocused || Password.IsFocused || SignInButton.IsFocused;
        PasswordForm.IsVisible = SignInButton.IsVisible = false;
        PasswordLink.IsVisible = true;
        if (hadFocus && ProviderList.Children.FirstOrDefault() is Button first)
            first.Focus();
    }

    /// <summary>
    /// Puts the focus on a provider's button, or on the first one, where the person can act: the button they used went
    /// off while the window waited, and took the focus with it.
    /// </summary>
    private void FocusProvider(string? id)
    {
        var buttons = ProviderList.Children.OfType<Button>().ToList();
        (buttons.FirstOrDefault(b => id is not null && b.Tag as string == id) ?? buttons.FirstOrDefault())?.Focus();
    }

    private void Unfold(bool focus = true)
    {
        PasswordForm.IsVisible = SignInButton.IsVisible = true;
        PasswordLink.IsVisible = false;
        if (focus)
            (string.IsNullOrEmpty(User.Text) ? User : Password).Focus();
    }

    private async void OnProvider(string id)
    {
        if (_prompt is null || _browser is not null)
            return;
        if (Address != _shownFor)
        {
            _ = AskProviders(); // the address changed since these buttons came: show the new server's first
            return;
        }
        var server = Server.Text ?? "";
        using var browser = new CancellationTokenSource();
        _browser = browser;
        Problem.IsVisible = false;
        SetBusy(true, inBrowser: true);
        string? problem;
        try
        {
            // Off the UI thread: opening the browser can take a while to say it couldn't.
            problem = await Task.Run(() => _prompt.SignInWithProviderAsync(server, id, browser.Token));
        }
        catch (OperationCanceledException ex) when (!browser.IsCancellationRequested)
        {
            problem = ex.Message; // the browser's time ran out: that isn't the person's cancel
        }
        catch (Exception ex)
        {
            problem = browser.IsCancellationRequested ? "" : "Signing in failed: " + ex.Message;
        }
        _browser = null;
        if (_closed)
            return; // closed while signing in: a sign-in that worked starts the helper all the same
        SetBusy(false, inBrowser: true);
        if (problem is null)
        {
            SignedIn = true;
            Close();
            return;
        }
        if (browser.IsCancellationRequested)
            return; // the person's own cancel needs no reason
        ShowProblem(problem);
        FocusProvider(id);
    }

    private async void OnSignIn(object? sender, RoutedEventArgs e)
    {
        if (_prompt is null || _busy || !SignInButton.IsVisible)
            return;
        if (_unanswered && Address == _askedFor)
        {
            // The providers never came, so the person has only the password form. Ask once more before the password
            // goes anywhere: if they come now, the person sees them, and presses Sign In again if they mean the
            // password. It stays in its box meanwhile.
            Problem.IsVisible = false;
            SetBusy(true, inBrowser: false);
            await AskProviders();
            if (_closed)
                return;
            SetBusy(false, inBrowser: false);
            if (_shownFor == Address)
            {
                if (SignInButton.IsVisible)
                    Password.Focus();
                else
                    FocusProvider(null);
                return;
            }
        }
        var (server, user, password) = (Server.Text ?? "", User.Text ?? "", Password.Text ?? "");
        Password.Text = "";
        Problem.IsVisible = false;
        SetBusy(true, inBrowser: false);
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
        SetBusy(false, inBrowser: false);
        if (problem is null)
        {
            SignedIn = true;
            Close();
            return;
        }
        ShowProblem(problem);
        Password.Focus();
    }

    private void ShowProblem(string problem)
    {
        Problem.Text = problem;
        Problem.IsVisible = true;
    }

    private void SetBusy(bool busy, bool inBrowser)
    {
        _busy = busy;
        Server.IsEnabled = User.IsEnabled = Password.IsEnabled = SignInButton.IsEnabled = PasswordLink.IsEnabled = !busy;
        foreach (var button in ProviderList.Children.OfType<Button>())
            button.IsEnabled = !busy;
        if (inBrowser)
            Waiting.IsVisible = busy;
        else
            SignInButton.Content = busy ? "Signing In…" : SignInLabel;
    }
}
