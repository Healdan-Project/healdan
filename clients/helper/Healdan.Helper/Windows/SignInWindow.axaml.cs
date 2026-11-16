// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using Healdan.Helper.Core;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Windows;

/// <summary>
/// Sign In (spec §5.2, M4 spec §6.1), in two steps (address-first spec §3): the server's address with Continue, then
/// that server's own choices, a button for each of its sign-in providers and the password form, which is folded behind
/// the server's link when it has any, as on the web page. A server with none gets the password form at once. Signing in
/// is the prompt's, which checks the server is Healdan and keeps only the token; this window shows its refusals as they
/// are. The password box is emptied as soon as the password is sent, so the window never holds the password beyond the
/// one call. The prompt's notice, such as Healdan having ended the sign-in, stays above both steps while the window is up.
/// <para>A first sign-in opens on the address, and asks nothing until Continue, which shows a problem on the error line
/// and stays there. An address already kept opens on the choices, which come when the server answers. One that couldn't
/// be reached is asked again every <see cref="RetryEvery"/> while the window is shown, and when it's shown again; any
/// other problem only when it's shown again. Change goes back to the address, except for a server the helper is running
/// against, and drops any answer still to come. A password goes only to a server whose choices the window shows, and
/// the window never opens the browser by itself. A provider's button sends the person to the browser, and the window
/// says to finish there; Cancel, or closing the window, stops the helper waiting for it. A refusal leaves the focus on
/// the button that was used.</para>
/// </summary>
public partial class SignInWindow : Window
{
    private const string SignInLabel = "Sign In";
    private const string ContinueLabel = "Continue";

    private readonly SignInPrompt? _prompt;
    private bool _closed;

    /// <summary>The address the choices step is for, as typed or kept; null on the address step.</summary>
    private string? _choicesFor;

    /// <summary>The choices the choices step shows; null while it has none.</summary>
    private ProviderChoice? _choice;

    /// <summary>The address an ask for the choices step is under way for; null when none is.</summary>
    private string? _asking;

    /// <summary>Counts the asks, so an older one's late answer is dropped.</summary>
    private int _ask;

    /// <summary>The next ask for a server that couldn't be reached; null when none is due.</summary>
    private IDisposable? _retry;

    /// <summary>The browser sign-in under way, which Cancel and closing the window stop; null when none is.</summary>
    private CancellationTokenSource? _browser;

    /// <summary>Continue or a sign-in is under way: the fields and buttons are off, new ones included.</summary>
    private bool _busy;

    /// <summary>For the XAML loader; the helper always opens it with a prompt.</summary>
    public SignInWindow() => InitializeComponent();

    public SignInWindow(SignInPrompt prompt) : this()
    {
        _prompt = prompt;
        (NoticeText.Text, NoticeBox.IsVisible) = (prompt.Notice, prompt.Notice is not null);
        ChangeLink.IsVisible = !prompt.ServerFixed;
        AutomationProperties.SetName(ChangeLink, "Change Server Address");
        if (string.IsNullOrWhiteSpace(prompt.Server))
            ShowAddress("");
        else
            ShowChoicesStep(prompt.Server, ServerAddress.Name(prompt.Server));
        ContinueButton.Click += (_, _) => OnContinue();
        SignInButton.Click += OnSignIn;
        ChangeLink.Click += (_, _) => OnChange();
        PasswordLink.Click += (_, _) => Unfold();
        CancelButton.Click += (_, _) =>
        {
            if (_browser is { } browser)
                browser.Cancel(); // back to the choices: the window stays
            else
                Close();
        };
        Closed += (_, _) =>
        {
            _closed = true;
            StopRetry();
            _browser?.Cancel(); // the helper stops listening for the browser
        };
        Opened += (_, _) =>
        {
            Problem.IsVisible = false; // from before the window was hidden: it is stale now
            if (_choicesFor is null)
                Server.Focus();
            else if (_choice is null)
                AskChoices();
            else
                FocusChoices();
        };
    }

    /// <summary>True once the user has signed in; false when they closed the window.</summary>
    public bool SignedIn { get; private set; }

    /// <summary>The latest ask for the server's choices: done once the window shows its answer. For the tests.</summary>
    public Task Loading { get; private set; } = Task.CompletedTask;

    /// <summary>How long the window waits before asking again a server that couldn't be reached.</summary>
    public TimeSpan RetryEvery { get; set; } = TimeSpan.FromSeconds(5);

    private string Address => (Server.Text ?? "").Trim();

    // --- The address --------------------------------------------------------------------------------------------------

    private void ShowAddress(string address)
    {
        (AddressStep.IsVisible, ChoicesStep.IsVisible) = (true, false);
        (ContinueButton.IsVisible, ContinueButton.IsDefault) = (true, true);
        (SignInButton.IsVisible, SignInButton.IsDefault) = (false, false);
        Server.Text = address;
    }

    private void OnContinue()
    {
        if (_prompt is null || _busy || _choicesFor is not null)
            return;
        Loading = ContinueAsync(_prompt, Address, ++_ask);
    }

    /// <summary>Asks the address's choices: the choices step once they come, else the problem, on this step.</summary>
    private async Task ContinueAsync(SignInPrompt prompt, string address, int ask)
    {
        Problem.IsVisible = false;
        SetConnecting(true);
        var answer = await AskAsync(prompt, address);
        if (_closed || ask != _ask)
            return;
        SetConnecting(false);
        if (answer.Choice is { } choice)
        {
            ShowChoicesStep(address, answer.Name ?? ServerAddress.Name(address));
            ShowChoice(choice);
            FocusChoices();
            return;
        }
        ShowProblem(answer.Problem ?? "Signing in failed.");
        Server.Focus();
    }

    private void SetConnecting(bool connecting)
    {
        _busy = connecting;
        Server.IsEnabled = ContinueButton.IsEnabled = !connecting;
        ContinueButton.Content = connecting ? "Connecting…" : ContinueLabel;
    }

    private void OnChange()
    {
        if (_prompt is null || _busy || _prompt.ServerFixed || _choicesFor is not { } address)
            return;
        ++_ask; // an answer still to come is for the old address
        _asking = null;
        StopRetry();
        (_choicesFor, _choice) = (null, null);
        User.Text = Password.Text = "";
        Problem.IsVisible = false;
        ShowAddress(address);
        Server.Focus();
        Server.SelectAll();
    }

    // --- The server's choices -----------------------------------------------------------------------------------------

    /// <summary>The choices step for the address, named as the person knows the server, waiting for its answer.</summary>
    private void ShowChoicesStep(string address, string name)
    {
        (_choicesFor, _choice) = (address, null);
        (AddressStep.IsVisible, ChoicesStep.IsVisible) = (false, true);
        (ContinueButton.IsVisible, ContinueButton.IsDefault) = (false, false);
        ServerName.Text = name;
        ProviderList.Children.Clear();
        ProviderList.IsVisible = PasswordLink.IsVisible = PasswordForm.IsVisible = false;
        (SignInButton.IsVisible, SignInButton.IsDefault) = (false, false);
        SetStatus($"Connecting to {name}…", problem: false);
    }

    /// <summary>
    /// Asks the choices step's address for its choices, unless it has them, or an ask is under way for it already.
    /// Done once the window shows the answer.
    /// </summary>
    private void AskChoices()
    {
        if (_prompt is null || _closed || _choicesFor is not { } address || _choice is not null || _asking == address)
            return;
        StopRetry();
        Loading = AskChoicesAsync(_prompt, address, ++_ask);
    }

    private async Task AskChoicesAsync(SignInPrompt prompt, string address, int ask)
    {
        _asking = address;
        var answer = await AskAsync(prompt, address);
        if (ask != _ask)
            return; // Change, or a newer ask: this one is someone else's now
        _asking = null;
        if (_closed)
            return;
        if (answer.Name is { } name)
            ServerName.Text = name;
        if (answer.Choice is { } choice)
        {
            ShowChoice(choice);
            FocusChoices();
            return;
        }
        // The status keeps this text while it asks again, rather than going back to connecting.
        SetStatus(answer.Retry ? $"{answer.Problem} Trying again…" : answer.Problem, problem: true);
        if (answer.Retry)
            _retry = DispatcherTimer.RunOnce(() =>
            {
                _retry = null;
                if (IsVisible) // hidden, it asks when it's shown again
                    AskChoices();
            }, RetryEvery);
    }

    private void StopRetry()
    {
        _retry?.Dispose();
        _retry = null;
    }

    /// <summary>The prompt's answer, off the UI thread; it never throws, and the window doesn't either.</summary>
    private static async Task<ServerAnswer> AskAsync(SignInPrompt prompt, string address)
    {
        try
        {
            return await Task.Run(() => prompt.ProvidersAsync(address));
        }
        catch (Exception ex)
        {
            return new ServerAnswer(null, null, "Signing in failed: " + ex.Message);
        }
    }

    private void SetStatus(string? text, bool problem)
    {
        Status.Text = text;
        Status.Classes.Set("muted", !problem);
        Status.Classes.Set("problem", problem);
        Status.IsVisible = text is not null;
    }

    /// <summary>The server's providers, with the password form folded behind the link, or the password form alone when there are none.</summary>
    private void ShowChoice(ProviderChoice choice)
    {
        _choice = choice;
        Status.IsVisible = false;
        ProviderList.Children.Clear();
        foreach (var provider in choice.Providers)
            ProviderList.Children.Add(ProviderButton(provider));
        ProviderList.IsVisible = choice.Providers.Count > 0;
        PasswordLink.Content = Text(choice.PasswordLabel);
        AutomationProperties.SetName(PasswordLink, choice.PasswordLabel);
        if (choice.Providers.Count == 0)
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
            IsEnabled = !_busy
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
        PasswordForm.IsVisible = false;
        (SignInButton.IsVisible, SignInButton.IsDefault) = (false, false);
        PasswordLink.IsVisible = true;
    }

    private void Unfold(bool focus = true)
    {
        PasswordForm.IsVisible = true;
        (SignInButton.IsVisible, SignInButton.IsDefault) = (true, true);
        PasswordLink.IsVisible = false;
        if (focus)
            (string.IsNullOrEmpty(User.Text) ? User : Password).Focus();
    }

    /// <summary>Where the person acts on the choices: the first provider's button, or the password form.</summary>
    private void FocusChoices()
    {
        if (ProviderList.Children.FirstOrDefault() is Button first)
            first.Focus();
        else if (PasswordForm.IsVisible)
            (string.IsNullOrEmpty(User.Text) ? User : Password).Focus();
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

    // --- Signing in ---------------------------------------------------------------------------------------------------

    private async void OnProvider(string id)
    {
        if (_prompt is null || _browser is not null || _busy || _choice is null || _choicesFor is not { } server)
            return;
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
        // Only from the choices step's form: a password goes only to a server whose choices the window shows.
        if (_prompt is null || _busy || !SignInButton.IsVisible || _choice is null || _choicesFor is not { } server)
            return;
        var (user, password) = (User.Text ?? "", Password.Text ?? "");
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
        User.IsEnabled = Password.IsEnabled = SignInButton.IsEnabled = PasswordLink.IsEnabled = ChangeLink.IsEnabled = !busy;
        foreach (var button in ProviderList.Children.OfType<Button>())
            button.IsEnabled = !busy;
        if (inBrowser)
            Waiting.IsVisible = busy;
        else
            SignInButton.Content = busy ? "Signing In…" : SignInLabel;
    }
}
