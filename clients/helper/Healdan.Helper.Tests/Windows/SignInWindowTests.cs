// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Collections.Concurrent;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.VisualTree;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Ui;
using Healdan.Helper.Windows;

namespace Healdan.Helper.Tests.Windows;

/// <summary>
/// The Sign In window (spec §5.2, M4 spec §6.1, address-first spec §3), as the user's clicks drive it, over the helper's
/// own look: the address first, then the server's own choices, its providers as buttons and the password form behind
/// its link when it has any, and the wait while the person signs in in the browser. The prompt is the test's, so nothing
/// reaches a server. For a Mac, as the app's other window tests are.
/// </summary>
public sealed class SignInWindowTests
{
    private const string Waiting = "Finish signing in in your browser…";

    private static readonly ProviderChoice None = new([], "Sign In with a Password");

    private static readonly ProviderChoice Two = new(
        [new ProviderInfo("entra", "Sign In with Microsoft"), new ProviderInfo("authentik", "Sign In with Authentik")],
        "Use a Healdan Password");

    private static ServerAnswer Answer(ProviderChoice choice, string name = "healdan.example") => new(name, choice, null);

    private static ServerAnswer Unreachable(string name = "healdan.example") =>
        new(name, null, $"{name} couldn't be reached: Connection refused.", Retry: true);

    private static ServerAnswer NotHealdan(string name = "healdan.example") => new(name, null, "That address isn't a Healdan server.");

    /// <summary>The prompt's three calls, as the test sets them, and what each was asked.</summary>
    private sealed class FakePrompt
    {
        public Func<string, Task<ServerAnswer>> OnProviders { get; set; } = _ => Task.FromResult(Answer(None));

        public Func<string, string, CancellationToken, Task<string?>> OnProvider { get; set; } = (_, _, _) => Task.FromResult<string?>(null);

        public Func<string, string, string, Task<string?>> OnPassword { get; set; } = (_, _, _) => Task.FromResult<string?>(null);

        public ConcurrentQueue<string> ProvidersAsked { get; } = new();

        public ConcurrentQueue<(string Server, string Provider)> ProviderSignIns { get; } = new();

        public ConcurrentQueue<(string Server, string User, string Password)> PasswordSignIns { get; } = new();

        public SignInPrompt Make(string? server = "healdan.example", bool serverFixed = false, string? notice = null) => new(server, serverFixed,
            (s, u, p) =>
            {
                PasswordSignIns.Enqueue((s, u, p));
                return OnPassword(s, u, p);
            },
            s =>
            {
                ProvidersAsked.Enqueue(s);
                return OnProviders(s);
            },
            (s, id, ct) =>
            {
                ProviderSignIns.Enqueue((s, id));
                return OnProvider(s, id, ct);
            },
            notice);
    }

    /// <summary>
    /// Makes the window and shows it on the UI thread, runs <paramref name="test"/> there, and closes it. The retry waits
    /// 50 ms rather than 5 seconds.
    /// </summary>
    private static Task Run(SignInPrompt prompt, Func<SignInWindow, Task> test) => Headless.RunAsync(async () =>
    {
        var window = new SignInWindow(prompt) { RetryEvery = TimeSpan.FromMilliseconds(50) };
        window.Show();
        try
        {
            await test(window);
        }
        finally
        {
            window.Close();
        }
        return 0;
    });

    private static T Find<T>(Window window, string name) where T : Control => window.FindControl<T>(name)!;

    private static void Click(Button button) => button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));

    /// <summary>The provider buttons, in the window's order.</summary>
    private static List<Button> ProviderButtons(Window window) => Find<StackPanel>(window, "ProviderList").Children.OfType<Button>().ToList();

    /// <summary>What a button says: its text, never read as markup or with access keys.</summary>
    private static string? Label(Button button) => button.Content is TextBlock text ? text.Text : button.Content as string;

    private static bool Shown(Control control) => control.IsVisible && control.GetVisualAncestors().OfType<Control>().All(c => c.IsVisible);

    private static async Task Until(Func<bool> condition)
    {
        var until = DateTime.UtcNow.AddSeconds(15);
        while (!condition())
        {
            Assert.True(DateTime.UtcNow < until, "It didn't happen within 15 seconds.");
            await Task.Delay(10);
        }
    }

    /// <summary>Types an address on the first step and chooses Continue.</summary>
    private static void Continue(Window window, string address)
    {
        Find<TextBox>(window, "Server").Text = address;
        Click(Find<Button>(window, "ContinueButton"));
    }

    // --- The address --------------------------------------------------------------------------------------------------

    [MacFact]
    public Task AFreshInstallOpensOnTheAddress()
    {
        var prompt = new FakePrompt();
        return Run(prompt.Make(server: null), async window =>
        {
            await window.Loading;

            Assert.True(Shown(Find<StackPanel>(window, "AddressStep")));
            var server = Find<TextBox>(window, "Server");
            Assert.True(Shown(server));
            Assert.True(server.IsFocused);
            var next = Find<Button>(window, "ContinueButton");
            Assert.True(Shown(next));
            Assert.True(next.IsDefault);
            Assert.False(Shown(Find<StackPanel>(window, "ChoicesStep")));
            Assert.False(Shown(Find<TextBox>(window, "User")));
            var signIn = Find<Button>(window, "SignInButton");
            Assert.False(Shown(signIn));
            Assert.False(signIn.IsDefault); // Return can't try a password before the server has answered
            Assert.Empty(prompt.ProvidersAsked);
        });
    }

    [MacFact]
    public Task ReturnInTheAddressConnectsAndSendsNoPassword()
    {
        // The bug this window's steps fix: Return pressed Sign In, which tried a password before the buttons came.
        var prompt = new FakePrompt { OnProviders = _ => Task.FromResult(Answer(Two)) };
        return Run(prompt.Make(server: null), async window =>
        {
            var server = Find<TextBox>(window, "Server");
            server.Text = "healdan.example";
            server.Focus();

            window.KeyPressQwerty(PhysicalKey.Enter, RawInputModifiers.None);
            await Until(() => ProviderButtons(window).Count == 2);

            Assert.Equal(["healdan.example"], prompt.ProvidersAsked);
            Assert.Empty(prompt.PasswordSignIns);
            Assert.True(Shown(Find<StackPanel>(window, "ChoicesStep")));
            Assert.False(Shown(Find<StackPanel>(window, "AddressStep")));
            Assert.Equal("healdan.example", Find<TextBlock>(window, "ServerName").Text);
            Assert.False(Shown(Find<TextBlock>(window, "Problem")));
            Assert.True(ProviderButtons(window)[0].IsFocused);
        });
    }

    [MacFact]
    public Task ContinueShowsWhyOnTheAddress()
    {
        var prompt = new FakePrompt { OnProviders = _ => Task.FromResult(Unreachable()) };
        return Run(prompt.Make(server: null), async window =>
        {
            Continue(window, "healdan.example");
            await window.Loading;

            var problem = Find<TextBlock>(window, "Problem");
            Assert.True(Shown(problem));
            Assert.Equal("healdan.example couldn't be reached: Connection refused.", problem.Text);
            Assert.True(Shown(Find<StackPanel>(window, "AddressStep")));
            Assert.False(Shown(Find<StackPanel>(window, "ChoicesStep")));
            var next = Find<Button>(window, "ContinueButton");
            Assert.Equal("Continue", next.Content);
            Assert.True(next.IsEnabled);
            var server = Find<TextBox>(window, "Server");
            Assert.True(server.IsEnabled);
            Assert.True(server.IsFocused);

            await Task.Delay(300);
            Assert.Single(prompt.ProvidersAsked); // the address step never asks again by itself
        });
    }

    [MacFact]
    public Task ContinueIsOffWhileItConnects()
    {
        var answer = new TaskCompletionSource<ServerAnswer>(TaskCreationOptions.RunContinuationsAsynchronously);
        var prompt = new FakePrompt { OnProviders = _ => answer.Task };
        return Run(prompt.Make(server: null), async window =>
        {
            Continue(window, "healdan.example");
            Click(Find<Button>(window, "ContinueButton"));

            await Until(() => prompt.ProvidersAsked.Count == 1);
            var next = Find<Button>(window, "ContinueButton");
            Assert.Equal("Connecting…", next.Content);
            Assert.False(next.IsEnabled);
            Assert.False(Find<TextBox>(window, "Server").IsEnabled);

            answer.SetResult(Answer(None));
            await window.Loading;

            var user = Find<TextBox>(window, "User");
            Assert.True(Shown(user));
            Assert.True(user.IsFocused);
            Assert.True(Shown(Find<Button>(window, "SignInButton")));
            Assert.Single(prompt.ProvidersAsked);
        });
    }

    // --- The server's choices -----------------------------------------------------------------------------------------

    [MacFact]
    public Task AKeptAddressOpensOnTheChoices()
    {
        var answer = new TaskCompletionSource<ServerAnswer>(TaskCreationOptions.RunContinuationsAsynchronously);
        var prompt = new FakePrompt { OnProviders = _ => answer.Task };
        return Run(prompt.Make(server: "https://healdan.example"), async window =>
        {
            Assert.True(Shown(Find<StackPanel>(window, "ChoicesStep")));
            Assert.False(Shown(Find<StackPanel>(window, "AddressStep")));
            Assert.False(Shown(Find<Button>(window, "ContinueButton")));
            Assert.Equal("healdan.example", Find<TextBlock>(window, "ServerName").Text);
            var status = Find<TextBlock>(window, "Status");
            Assert.True(Shown(status));
            Assert.Equal("Connecting to healdan.example…", status.Text);
            Assert.Contains("muted", status.Classes);
            Assert.True(Shown(Find<Button>(window, "ChangeLink")));
            Assert.Empty(ProviderButtons(window));
            Assert.False(Shown(Find<StackPanel>(window, "PasswordForm")));

            answer.SetResult(Answer(Two));
            await window.Loading;

            Assert.Equal(2, ProviderButtons(window).Count);
            Assert.False(Shown(status));
            Assert.True(Shown(Find<Button>(window, "PasswordLink")));
            Assert.True(ProviderButtons(window)[0].IsFocused);
        });
    }

    [MacFact]
    public Task NoProvidersShowsThePasswordForm()
    {
        var prompt = new FakePrompt();
        return Run(prompt.Make(), async window =>
        {
            await window.Loading;

            Assert.Equal(["healdan.example"], prompt.ProvidersAsked);
            var user = Find<TextBox>(window, "User");
            Assert.True(Shown(user));
            Assert.True(user.IsFocused);
            Assert.True(Shown(Find<TextBox>(window, "Password")));
            var signIn = Find<Button>(window, "SignInButton");
            Assert.True(Shown(signIn));
            Assert.True(signIn.IsDefault);
            Assert.False(Shown(Find<Button>(window, "ContinueButton")));
            Assert.Empty(ProviderButtons(window));
            Assert.False(Shown(Find<Button>(window, "PasswordLink")));
            Assert.False(Shown(Find<TextBlock>(window, "Waiting")));
        });
    }

    [MacFact]
    public Task ProvidersShowButtonsAndFoldThePassword()
    {
        var prompt = new FakePrompt { OnProviders = _ => Task.FromResult(Answer(Two)) };
        return Run(prompt.Make(), async window =>
        {
            await window.Loading;

            var buttons = ProviderButtons(window);
            Assert.Equal(["Sign In with Microsoft", "Sign In with Authentik"], buttons.Select(Label));
            Assert.All(buttons, b => Assert.True(Shown(b) && b.IsEnabled));
            var link = Find<Button>(window, "PasswordLink");
            Assert.True(Shown(link));
            Assert.Equal("Use a Healdan Password", Label(link));
            Assert.False(Shown(Find<TextBox>(window, "User")));
            Assert.False(Shown(Find<TextBox>(window, "Password")));
            Assert.False(Shown(Find<Button>(window, "SignInButton"))); // nothing to sign in with until the form unfolds

            Click(link);

            Assert.True(Shown(Find<TextBox>(window, "User")));
            Assert.True(Shown(Find<TextBox>(window, "Password")));
            var signIn = Find<Button>(window, "SignInButton");
            Assert.True(Shown(signIn));
            Assert.True(signIn.IsDefault);
            Assert.False(Shown(link));
            Assert.Equal(2, ProviderButtons(window).Count(Shown)); // the buttons stay
        });
    }

    [MacFact]
    public Task ALabelIsShownAsItIs()
    {
        // A label is the administrator's text: an underscore isn't an access key, and nothing in it is markup.
        var odd = new ProviderChoice([new ProviderInfo("odd", "Sign In with My_Firm <b>SSO</b>")], "Use a_Password");
        var prompt = new FakePrompt { OnProviders = _ => Task.FromResult(Answer(odd)) };
        return Run(prompt.Make(), async window =>
        {
            await window.Loading;

            Assert.Equal("Sign In with My_Firm <b>SSO</b>", Label(Assert.Single(ProviderButtons(window))));
            Assert.Equal("Use a_Password", Label(Find<Button>(window, "PasswordLink")));
        });
    }

    [MacFact]
    public Task ChangeGoesBackToTheAddress()
    {
        var prompt = new FakePrompt { OnProviders = s => Task.FromResult(Answer(Two, s == "firm.example" ? "firm.example" : "healdan.example")) };
        return Run(prompt.Make(server: "https://healdan.example"), async window =>
        {
            await window.Loading;
            Click(Find<Button>(window, "PasswordLink"));
            Find<TextBox>(window, "User").Text = "admin";
            Find<TextBox>(window, "Password").Text = "pa55-w0rd";

            Click(Find<Button>(window, "ChangeLink"));

            Assert.True(Shown(Find<StackPanel>(window, "AddressStep")));
            Assert.False(Shown(Find<StackPanel>(window, "ChoicesStep")));
            var server = Find<TextBox>(window, "Server");
            Assert.Equal("https://healdan.example", server.Text);
            Assert.True(server.IsFocused);
            Assert.Equal(0, server.SelectionStart);
            Assert.Equal(server.Text!.Length, server.SelectionEnd);
            Assert.Equal("", Find<TextBox>(window, "User").Text ?? "");
            Assert.Equal("", Find<TextBox>(window, "Password").Text ?? "");

            Continue(window, "firm.example");
            await window.Loading;

            Assert.Equal("firm.example", Find<TextBlock>(window, "ServerName").Text);
            Assert.True(Shown(Find<Button>(window, "PasswordLink"))); // folded again
            Assert.False(Shown(Find<TextBox>(window, "User")));
        });
    }

    [MacFact]
    public Task AFixedServerHasNoChange()
    {
        var prompt = new FakePrompt { OnProviders = _ => Task.FromResult(Answer(Two)) };
        return Run(prompt.Make(server: "https://healdan.example", serverFixed: true), async window =>
        {
            await window.Loading;

            Assert.False(Shown(Find<Button>(window, "ChangeLink")));
            Assert.False(Shown(Find<StackPanel>(window, "AddressStep")));
            Assert.Equal(["https://healdan.example"], prompt.ProvidersAsked);
            Assert.Equal(2, ProviderButtons(window).Count);
        });
    }

    // --- A kept server that doesn't answer ----------------------------------------------------------------------------

    [MacFact]
    public Task AnUnreachableKeptServerIsAskedAgain()
    {
        // The helper starts at the Mac's login, sometimes before the network: the window waits for the server.
        var release = new TaskCompletionSource<ServerAnswer>(TaskCreationOptions.RunContinuationsAsynchronously);
        var asked = 0;
        var prompt = new FakePrompt { OnProviders = _ => Interlocked.Increment(ref asked) < 3 ? Task.FromResult(Unreachable()) : release.Task };
        return Run(prompt.Make(server: "https://healdan.example"), async window =>
        {
            await Until(() => prompt.ProvidersAsked.Count == 3);

            var status = Find<TextBlock>(window, "Status");
            Assert.True(Shown(status));
            Assert.Equal("healdan.example couldn't be reached: Connection refused. Trying again…", status.Text); // kept while it asks
            Assert.Contains("problem", status.Classes);
            Assert.Empty(ProviderButtons(window));

            release.SetResult(Answer(Two));
            await Until(() => ProviderButtons(window).Count == 2);

            Assert.False(Shown(status));
            await Task.Delay(300);
            Assert.Equal(3, prompt.ProvidersAsked.Count); // answered, so not asked again
        });
    }

    [MacFact]
    public Task AnotherProblemOnTheChoicesIsntRetried()
    {
        var prompt = new FakePrompt { OnProviders = _ => Task.FromResult(NotHealdan()) };
        return Run(prompt.Make(server: "https://healdan.example"), async window =>
        {
            await window.Loading;

            var status = Find<TextBlock>(window, "Status");
            Assert.Equal("That address isn't a Healdan server.", status.Text);
            Assert.Contains("problem", status.Classes);
            await Task.Delay(300);
            Assert.Single(prompt.ProvidersAsked);
            var change = Find<Button>(window, "ChangeLink");
            Assert.True(Shown(change));
            Assert.True(change.IsEnabled);
        });
    }

    [MacFact]
    public Task RetryingStopsWhenTheWindowCloses()
    {
        var prompt = new FakePrompt { OnProviders = _ => Task.FromResult(Unreachable()) };
        return Run(prompt.Make(server: "https://healdan.example"), async window =>
        {
            await Until(() => prompt.ProvidersAsked.Count >= 2);

            window.Close();
            await Task.Delay(100); // an ask begun before the close is recorded on the thread pool
            var asked = prompt.ProvidersAsked.Count;
            await Task.Delay(300);

            Assert.Equal(asked, prompt.ProvidersAsked.Count);
        });
    }

    [MacFact]
    public Task RetryingStopsAtChange()
    {
        var prompt = new FakePrompt { OnProviders = _ => Task.FromResult(Unreachable()) };
        return Run(prompt.Make(server: "https://healdan.example"), async window =>
        {
            await Until(() => prompt.ProvidersAsked.Count >= 2);

            Click(Find<Button>(window, "ChangeLink"));
            await Task.Delay(100);
            var asked = prompt.ProvidersAsked.Count;
            await Task.Delay(300);

            Assert.Equal(asked, prompt.ProvidersAsked.Count);
            Assert.True(Shown(Find<StackPanel>(window, "AddressStep")));
        });
    }

    [MacFact]
    public Task ALateAnswerAfterChangeIsDropped()
    {
        var first = new TaskCompletionSource<ServerAnswer>(TaskCreationOptions.RunContinuationsAsynchronously);
        var prompt = new FakePrompt
        {
            OnProviders = s => s == "firm.example" ? Task.FromResult(Answer(None, "firm.example")) : first.Task
        };
        return Run(prompt.Make(server: "https://healdan.example"), async window =>
        {
            await Until(() => !prompt.ProvidersAsked.IsEmpty);
            Click(Find<Button>(window, "ChangeLink"));
            Continue(window, "firm.example");
            await window.Loading;
            Assert.True(Shown(Find<TextBox>(window, "User")));

            first.SetResult(Answer(Two)); // the kept server answers at last
            await Task.Delay(100);

            Assert.Empty(ProviderButtons(window));
            Assert.Equal("firm.example", Find<TextBlock>(window, "ServerName").Text);
            Assert.True(Shown(Find<TextBox>(window, "User")));
        });
    }

    [MacFact]
    public Task ShowingTheWindowAgainAsksAgainWithoutChoices()
    {
        // A fixed address has no Change, so showing the window is how a server that answered wrongly is asked again.
        var answers = new ConcurrentQueue<ServerAnswer>([NotHealdan(), Answer(Two)]);
        var prompt = new FakePrompt { OnProviders = _ => Task.FromResult(answers.TryDequeue(out var a) ? a : Answer(Two)) };
        return Run(prompt.Make(server: "https://healdan.example", serverFixed: true), async window =>
        {
            await window.Loading;
            Assert.Empty(ProviderButtons(window));

            window.Hide();
            window.Show();
            await window.Loading;

            Assert.Equal(["https://healdan.example", "https://healdan.example"], prompt.ProvidersAsked);
            Assert.Equal(2, ProviderButtons(window).Count);

            // Answered, it isn't asked again
            window.Hide();
            window.Show();
            await window.Loading;
            Assert.Equal(2, prompt.ProvidersAsked.Count);
        });
    }

    [MacFact]
    public Task ShowingTheWindowAgainWhileAskingAsksOnce()
    {
        var answer = new TaskCompletionSource<ServerAnswer>(TaskCreationOptions.RunContinuationsAsynchronously);
        var prompt = new FakePrompt { OnProviders = _ => answer.Task };
        return Run(prompt.Make(server: "https://healdan.example", serverFixed: true), async window =>
        {
            window.Hide();
            window.Show();
            window.Hide();
            window.Show();

            // The window asks on the thread pool, so the ask is recorded a moment later
            await Until(() => !prompt.ProvidersAsked.IsEmpty);
            Assert.Equal(["https://healdan.example"], prompt.ProvidersAsked); // the one answer is still to come

            answer.SetResult(NotHealdan()); // not one to retry
            await window.Loading;
            Assert.Equal(["https://healdan.example"], prompt.ProvidersAsked);
        });
    }

    // --- Signing in ---------------------------------------------------------------------------------------------------

    [MacFact]
    public Task ButtonSignsIn()
    {
        var finish = new TaskCompletionSource<string?>(TaskCreationOptions.RunContinuationsAsynchronously);
        var prompt = new FakePrompt
        {
            OnProviders = _ => Task.FromResult(Answer(Two)),
            OnProvider = (_, _, _) => finish.Task
        };
        return Run(prompt.Make(), async window =>
        {
            await window.Loading;
            var buttons = ProviderButtons(window);

            Click(buttons[1]);
            await Until(() => !prompt.ProviderSignIns.IsEmpty);

            Assert.Equal([("healdan.example", "authentik")], prompt.ProviderSignIns);
            var waiting = Find<TextBlock>(window, "Waiting");
            Assert.True(Shown(waiting));
            Assert.Equal(Waiting, waiting.Text);
            Assert.All(buttons, b => Assert.False(b.IsEnabled));
            Assert.False(Find<Button>(window, "PasswordLink").IsEnabled);
            Assert.False(Find<Button>(window, "ChangeLink").IsEnabled);
            Assert.True(Find<Button>(window, "CancelButton").IsEnabled); // the way out of the wait
            Assert.False(Shown(Find<TextBlock>(window, "Problem")));
            Assert.True(window.IsVisible);

            finish.SetResult(null); // signed in

            await Until(() => !window.IsVisible);
            Assert.True(window.SignedIn);
            Assert.Empty(prompt.PasswordSignIns);
        });
    }

    [MacFact]
    public Task RefusalShowsTheReason()
    {
        const string reason = "Your account doesn't give you access to Healdan. Ask an administrator.";
        var prompt = new FakePrompt
        {
            OnProviders = _ => Task.FromResult(Answer(Two)),
            OnProvider = (_, _, _) => Task.FromResult<string?>(reason)
        };
        return Run(prompt.Make(), async window =>
        {
            await window.Loading;

            Click(ProviderButtons(window)[0]);
            var problem = Find<TextBlock>(window, "Problem");
            await Until(() => Shown(problem));

            Assert.Equal(reason, problem.Text);
            Assert.True(window.IsVisible);
            Assert.False(window.SignedIn);
            Assert.False(Shown(Find<TextBlock>(window, "Waiting")));
            Assert.All(ProviderButtons(window), b => Assert.True(b.IsEnabled)); // it can be tried again
            Assert.True(Find<Button>(window, "ChangeLink").IsEnabled);
        });
    }

    [MacFact]
    public Task AFailedProviderSignInPutsTheFocusOnItsButton()
    {
        // The button the person pressed went off while the browser was waited for, and took the focus with it.
        var prompt = new FakePrompt
        {
            OnProviders = _ => Task.FromResult(Answer(Two)),
            OnProvider = (_, _, _) => Task.FromResult<string?>("The sign-in provider couldn't sign you in. Try again, or ask an administrator.")
        };
        return Run(prompt.Make(), async window =>
        {
            await window.Loading;
            var buttons = ProviderButtons(window);
            buttons[1].Focus(); // where a click leaves it

            Click(buttons[1]);
            var problem = Find<TextBlock>(window, "Problem");
            await Until(() => Shown(problem));

            Assert.True(buttons[1].IsEnabled);
            Assert.True(buttons[1].IsFocused);
            Assert.Same(buttons[1], window.FocusManager?.GetFocusedElement());
        });
    }

    [MacFact]
    public Task AFailedProviderSignInPutsTheFocusOnItsButtonWhereverItWas()
    {
        // Even on a control the person had left.
        var prompt = new FakePrompt
        {
            OnProviders = _ => Task.FromResult(Answer(Two)),
            OnProvider = (_, _, _) => Task.FromResult<string?>("The sign-in provider couldn't sign you in. Try again, or ask an administrator.")
        };
        return Run(prompt.Make(), async window =>
        {
            await window.Loading;
            var buttons = ProviderButtons(window);
            Find<Button>(window, "CancelButton").Focus();

            Click(buttons[0]);
            var problem = Find<TextBlock>(window, "Problem");
            await Until(() => Shown(problem));

            Assert.Same(buttons[0], window.FocusManager?.GetFocusedElement());
        });
    }

    [MacFact]
    public Task AStaleProblemGoesWhenTheWindowIsShownAgain()
    {
        var prompt = new FakePrompt
        {
            OnProviders = _ => Task.FromResult(Answer(Two)),
            OnProvider = (_, _, _) => Task.FromResult<string?>("Your account doesn't give you access to Healdan. Ask an administrator.")
        };
        return Run(prompt.Make(), async window =>
        {
            await window.Loading;
            Click(ProviderButtons(window)[0]);
            var problem = Find<TextBlock>(window, "Problem");
            await Until(() => Shown(problem));

            window.Hide();
            Assert.True(problem.IsVisible); // hidden is not shown again
            window.Show();

            Assert.False(Shown(problem));
            Assert.All(ProviderButtons(window), b => Assert.True(Shown(b) && b.IsEnabled));
        });
    }

    [MacFact]
    public Task TheBrowsersTimeOutReachesTheErrorLine()
    {
        // The prompt's 5 minutes run out without the person cancelling: the window says so rather than going quiet.
        const string late = "The sign-in in your browser didn't finish within 5 minutes.";
        var prompt = new FakePrompt
        {
            OnProviders = _ => Task.FromResult(Answer(Two)),
            OnProvider = (_, _, _) => Task.FromException<string?>(new OperationCanceledException(late))
        };
        return Run(prompt.Make(), async window =>
        {
            await window.Loading;

            Click(ProviderButtons(window)[0]);
            var problem = Find<TextBlock>(window, "Problem");
            await Until(() => Shown(problem));

            Assert.Equal(late, problem.Text);
            Assert.False(Shown(Find<TextBlock>(window, "Waiting")));
        });
    }

    [MacFact]
    public Task CancelStopsWaitingForTheBrowser()
    {
        var cancelled = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var prompt = new FakePrompt
        {
            OnProviders = _ => Task.FromResult(Answer(Two)),
            OnProvider = async (_, _, ct) =>
            {
                await Task.Delay(Timeout.Infinite, ct).ContinueWith(_ => { }, TaskScheduler.Default);
                cancelled.TrySetResult();
                return "The sign-in was cancelled.";
            }
        };
        return Run(prompt.Make(), async window =>
        {
            await window.Loading;
            Click(ProviderButtons(window)[0]);
            await Until(() => !prompt.ProviderSignIns.IsEmpty);

            Click(Find<Button>(window, "CancelButton"));

            await cancelled.Task.WaitAsync(TimeSpan.FromSeconds(15));
            await Until(() => !Shown(Find<TextBlock>(window, "Waiting")));
            Assert.True(window.IsVisible); // back to the choices, not closed
            Assert.False(window.SignedIn);
            Assert.False(Shown(Find<TextBlock>(window, "Problem"))); // the person's own cancel needs no reason
            Assert.All(ProviderButtons(window), b => Assert.True(b.IsEnabled));

            Click(Find<Button>(window, "CancelButton")); // and now Cancel closes it
            Assert.False(window.IsVisible);
        });
    }

    [MacFact]
    public Task ClosingTheWindowStopsWaitingForTheBrowser()
    {
        var cancelled = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var prompt = new FakePrompt
        {
            OnProviders = _ => Task.FromResult(Answer(Two)),
            OnProvider = async (_, _, ct) =>
            {
                await Task.Delay(Timeout.Infinite, ct).ContinueWith(_ => { }, TaskScheduler.Default);
                cancelled.TrySetResult();
                return "The sign-in was cancelled.";
            }
        };
        return Run(prompt.Make(), async window =>
        {
            await window.Loading;
            Click(ProviderButtons(window)[0]);
            await Until(() => !prompt.ProviderSignIns.IsEmpty);

            window.Close();

            await cancelled.Task.WaitAsync(TimeSpan.FromSeconds(15)); // the helper stops listening for the browser
            Assert.False(window.SignedIn);
        });
    }

    [MacFact]
    public Task SignInDoesntAskAgainAfterAnAnswer()
    {
        var prompt = new FakePrompt();
        return Run(prompt.Make(), async window =>
        {
            await window.Loading;
            Find<TextBox>(window, "User").Text = "admin";
            Find<TextBox>(window, "Password").Text = "pa55-w0rd";

            Click(Find<Button>(window, "SignInButton"));
            await Until(() => !window.IsVisible);

            Assert.Equal(["healdan.example"], prompt.ProvidersAsked);
            Assert.Equal([("healdan.example", "admin", "pa55-w0rd")], prompt.PasswordSignIns);
        });
    }

    [MacFact]
    public Task ThePasswordFormSignsInAsBefore()
    {
        var prompt = new FakePrompt { OnProviders = _ => Task.FromResult(Answer(Two)) };
        return Run(prompt.Make(), async window =>
        {
            await window.Loading;
            Click(Find<Button>(window, "PasswordLink"));
            Find<TextBox>(window, "User").Text = "admin";
            var password = Find<TextBox>(window, "Password");
            password.Text = "pa55-w0rd";

            Click(Find<Button>(window, "SignInButton"));

            Assert.Equal("", password.Text); // emptied at once: the window never holds it beyond the one call
            await Until(() => !window.IsVisible);
            Assert.Equal([("healdan.example", "admin", "pa55-w0rd")], prompt.PasswordSignIns);
            Assert.True(window.SignedIn);
            Assert.Empty(prompt.ProviderSignIns);
        });
    }

    [MacFact]
    public Task APasswordRefusalStaysOnTheChoices()
    {
        var prompt = new FakePrompt { OnPassword = (_, _, _) => Task.FromResult<string?>("The username or password is wrong.") };
        return Run(prompt.Make(), async window =>
        {
            await window.Loading;
            Find<TextBox>(window, "User").Text = "admin";
            Find<TextBox>(window, "Password").Text = "wrong";

            Click(Find<Button>(window, "SignInButton"));
            var problem = Find<TextBlock>(window, "Problem");
            await Until(() => Shown(problem));

            Assert.Equal("The username or password is wrong.", problem.Text);
            Assert.True(window.IsVisible);
            Assert.Equal("Sign In", Find<Button>(window, "SignInButton").Content);
            Assert.True(Shown(Find<StackPanel>(window, "ChoicesStep")));
            Assert.Single(prompt.ProvidersAsked);
        });
    }

    // --- The notice ---------------------------------------------------------------------------------------------------

    [MacFact]
    public Task ANoticeSaysWhyTheWindowAsks()
    {
        var prompt = new FakePrompt { OnPassword = (_, _, _) => Task.FromResult<string?>("The username or password is wrong.") };
        return Run(prompt.Make(serverFixed: true, notice: SignInPrompt.Ended), async window =>
        {
            await window.Loading;
            var notice = Find<TextBlock>(window, "NoticeText");
            Assert.True(Shown(notice));
            Assert.Equal("Your sign-in ended. Sign in again.", notice.Text); // the User Guide quotes it

            // It stays up through a refusal, which has the error line, and through the window being shown again.
            Find<TextBox>(window, "User").Text = "admin";
            Find<TextBox>(window, "Password").Text = "wrong";
            Click(Find<Button>(window, "SignInButton"));
            await Until(() => Shown(Find<TextBlock>(window, "Problem")));
            Assert.True(Shown(notice));
            window.Hide();
            window.Show();
            Assert.True(Shown(notice));
        });
    }

    [MacFact]
    public Task NoNoticeShowsNoLine()
    {
        var prompt = new FakePrompt();
        return Run(prompt.Make(), async window =>
        {
            await window.Loading;
            Assert.False(Shown(Find<Border>(window, "NoticeBox")));
        });
    }
}
