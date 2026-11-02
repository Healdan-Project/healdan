using System.Collections.Concurrent;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.VisualTree;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Ui;
using Healdan.Helper.Windows;

namespace Healdan.Helper.Tests.Windows;

/// <summary>
/// The Sign In window (spec §5.2, M4 spec §6.1), as the user's clicks drive it, over the helper's own look: the server's
/// sign-in providers as buttons, the password form behind the server's link when it has any, and the wait while the
/// person signs in in the browser. The prompt is the test's, so nothing reaches a server. For a Mac, as the app's other
/// window tests are.
/// </summary>
public sealed class SignInWindowTests
{
    private const string Waiting = "Finish signing in in your browser…";

    private static readonly ProviderChoice None = new([], "Sign In with a Password");

    private static readonly ProviderChoice Two = new(
        [new ProviderInfo("entra", "Sign In with Microsoft"), new ProviderInfo("authentik", "Sign In with Authentik")],
        "Use a Healdan Password");

    /// <summary>The prompt's three calls, as the test sets them, and what each was asked.</summary>
    private sealed class FakePrompt
    {
        public Func<string, Task<ProviderChoice?>> OnProviders { get; set; } = _ => Task.FromResult<ProviderChoice?>(None);

        public Func<string, string, CancellationToken, Task<string?>> OnProvider { get; set; } = (_, _, _) => Task.FromResult<string?>(null);

        public Func<string, string, string, Task<string?>> OnPassword { get; set; } = (_, _, _) => Task.FromResult<string?>(null);

        public ConcurrentQueue<string> ProvidersAsked { get; } = new();

        public ConcurrentQueue<(string Server, string Provider)> ProviderSignIns { get; } = new();

        public ConcurrentQueue<(string Server, string User, string Password)> PasswordSignIns { get; } = new();

        public SignInPrompt Make(string? server = "healdan.example", bool serverFixed = false) => new(server, serverFixed,
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
            });
    }

    /// <summary>Makes the window and shows it on the UI thread, runs <paramref name="test"/> there, and closes it.</summary>
    private static Task Run(SignInPrompt prompt, Func<SignInWindow, Task> test) => Headless.RunAsync(async () =>
    {
        var window = new SignInWindow(prompt);
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

    [MacFact]
    public Task NoProvidersShowsThePasswordForm()
    {
        var prompt = new FakePrompt();
        return Run(prompt.Make(), async window =>
        {
            await window.Loading;

            Assert.Equal(["healdan.example"], prompt.ProvidersAsked);
            Assert.True(Shown(Find<TextBox>(window, "User")));
            Assert.True(Shown(Find<TextBox>(window, "Password")));
            var signIn = Find<Button>(window, "SignInButton");
            Assert.True(Shown(signIn));
            Assert.True(signIn.IsDefault);
            Assert.Empty(ProviderButtons(window));
            Assert.False(Shown(Find<Button>(window, "PasswordLink")));
            Assert.False(Shown(Find<TextBlock>(window, "Waiting")));
        });
    }

    [MacFact]
    public Task AServerThatCantBeReachedShowsThePasswordForm()
    {
        // The password form's Sign In then says why, as it always has.
        var prompt = new FakePrompt { OnProviders = _ => Task.FromResult<ProviderChoice?>(null) };
        return Run(prompt.Make(), async window =>
        {
            await window.Loading;

            Assert.True(Shown(Find<TextBox>(window, "User")));
            Assert.True(Shown(Find<Button>(window, "SignInButton")));
            Assert.Empty(ProviderButtons(window));
            Assert.False(Shown(Find<Button>(window, "PasswordLink")));
        });
    }

    [MacFact]
    public Task NoAddressAsksNothingUntilOneIsTyped()
    {
        var prompt = new FakePrompt();
        return Run(prompt.Make(server: null), async window =>
        {
            await window.Loading;

            Assert.Empty(prompt.ProvidersAsked);
            Assert.True(Shown(Find<TextBox>(window, "User")));
        });
    }

    [MacFact]
    public Task ProvidersShowButtonsAndFoldThePassword()
    {
        var prompt = new FakePrompt { OnProviders = _ => Task.FromResult<ProviderChoice?>(Two) };
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
            Assert.True(Shown(Find<Button>(window, "SignInButton")));
            Assert.False(Shown(link));
            Assert.Equal(2, ProviderButtons(window).Count(Shown)); // the buttons stay
        });
    }

    [MacFact]
    public Task ALabelIsShownAsItIs()
    {
        // A label is the administrator's text: an underscore isn't an access key, and nothing in it is markup.
        var odd = new ProviderChoice([new ProviderInfo("odd", "Sign In with My_Firm <b>SSO</b>")], "Use a_Password");
        var prompt = new FakePrompt { OnProviders = _ => Task.FromResult<ProviderChoice?>(odd) };
        return Run(prompt.Make(), async window =>
        {
            await window.Loading;

            Assert.Equal("Sign In with My_Firm <b>SSO</b>", Label(Assert.Single(ProviderButtons(window))));
            Assert.Equal("Use a_Password", Label(Find<Button>(window, "PasswordLink")));
        });
    }

    [MacFact]
    public Task ButtonSignsIn()
    {
        var finish = new TaskCompletionSource<string?>(TaskCreationOptions.RunContinuationsAsynchronously);
        var prompt = new FakePrompt
        {
            OnProviders = _ => Task.FromResult<ProviderChoice?>(Two),
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
            Assert.False(Find<TextBox>(window, "Server").IsEnabled);
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
        const string reason = "Your account at Sign In with Microsoft doesn't give you access to Healdan. Ask an administrator.";
        var prompt = new FakePrompt
        {
            OnProviders = _ => Task.FromResult<ProviderChoice?>(Two),
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
            Assert.True(Find<TextBox>(window, "Server").IsEnabled);
        });
    }

    [MacFact]
    public Task TheBrowsersTimeOutReachesTheErrorLine()
    {
        // The prompt's 5 minutes run out without the person cancelling: the window says so rather than going quiet.
        const string late = "The sign-in in your browser didn't finish within 5 minutes.";
        var prompt = new FakePrompt
        {
            OnProviders = _ => Task.FromResult<ProviderChoice?>(Two),
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
            OnProviders = _ => Task.FromResult<ProviderChoice?>(Two),
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
            OnProviders = _ => Task.FromResult<ProviderChoice?>(Two),
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
    public Task AddressChangeReloadsProviders()
    {
        var prompt = new FakePrompt
        {
            OnProviders = s => Task.FromResult<ProviderChoice?>(s == "firm.example" ? Two : None)
        };
        return Run(prompt.Make(), async window =>
        {
            await window.Loading;
            Assert.Empty(ProviderButtons(window));
            var server = Find<TextBox>(window, "Server");
            var user = Find<TextBox>(window, "User");

            server.Focus();
            user.Focus(); // leaving the field unchanged asks nothing
            await window.Loading;
            Assert.Equal(["healdan.example"], prompt.ProvidersAsked);

            server.Focus();
            server.Text = "firm.example";
            user.Focus();
            await window.Loading;

            Assert.Equal(["healdan.example", "firm.example"], prompt.ProvidersAsked);
            Assert.Equal(2, ProviderButtons(window).Count);
            Assert.False(Shown(user)); // folded: nothing was typed in it

            server.Focus();
            server.Text = "healdan.example"; // back to a server with none
            Click(ProviderButtons(window)[0]); // leaving the field for a button of the old server's
            await window.Loading;

            Assert.Equal(["healdan.example", "firm.example", "healdan.example"], prompt.ProvidersAsked);
            Assert.Empty(prompt.ProviderSignIns); // the button belonged to another server, so it did nothing
            Assert.Empty(ProviderButtons(window));
            Assert.True(Shown(user));
            Assert.True(Shown(Find<Button>(window, "SignInButton")));
        });
    }

    [MacFact]
    public Task WhatWasTypedKeepsThePasswordFormOpen()
    {
        // Providers that arrive after the person started typing a username don't fold the form under them.
        var prompt = new FakePrompt
        {
            OnProviders = s => Task.FromResult<ProviderChoice?>(s == "firm.example" ? Two : None)
        };
        return Run(prompt.Make(server: null), async window =>
        {
            var server = Find<TextBox>(window, "Server");
            var user = Find<TextBox>(window, "User");
            server.Focus();
            server.Text = "firm.example";
            user.Focus();
            user.Text = "robert";
            await window.Loading;

            Assert.Equal(2, ProviderButtons(window).Count);
            Assert.True(Shown(user));
            Assert.True(Shown(Find<Button>(window, "SignInButton")));
            Assert.False(Shown(Find<Button>(window, "PasswordLink")));
        });
    }

    [MacFact]
    public Task AnUnreachableAnswerIsAskedAgain()
    {
        // After a refused password the address is fixed, and an account that signs in through a provider has no
        // password: one blip mustn't leave the window on the password form for good.
        var answers = new Queue<ProviderChoice?>([null, Two]);
        var prompt = new FakePrompt { OnProviders = _ => Task.FromResult(answers.Dequeue()) };
        return Run(prompt.Make(server: "https://healdan.example", serverFixed: true), async window =>
        {
            await window.Loading;
            Assert.Empty(ProviderButtons(window));
            Assert.True(Shown(Find<TextBox>(window, "User")));

            var server = Find<TextBox>(window, "Server");
            server.Focus();
            Find<TextBox>(window, "Password").Focus();
            await window.Loading;

            Assert.Equal(["https://healdan.example", "https://healdan.example"], prompt.ProvidersAsked);
            Assert.Equal(2, ProviderButtons(window).Count);

            // Answered, it isn't asked again
            server.Focus();
            Find<TextBox>(window, "Password").Focus();
            await window.Loading;
            Assert.Equal(2, prompt.ProvidersAsked.Count);
        });
    }

    [MacFact]
    public Task AFixedServerAsksForItsProvidersOnce()
    {
        var prompt = new FakePrompt { OnProviders = _ => Task.FromResult<ProviderChoice?>(Two) };
        return Run(prompt.Make(server: "https://healdan.example", serverFixed: true), async window =>
        {
            await window.Loading;

            Assert.Equal(["https://healdan.example"], prompt.ProvidersAsked);
            Assert.True(Find<TextBox>(window, "Server").IsReadOnly);
            Assert.Equal(2, ProviderButtons(window).Count);
        });
    }

    [MacFact]
    public Task ThePasswordFormSignsInAsBefore()
    {
        var prompt = new FakePrompt { OnProviders = _ => Task.FromResult<ProviderChoice?>(Two) };
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
    public Task APasswordRefusalStillShowsOnTheErrorLine()
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
        });
    }
}
