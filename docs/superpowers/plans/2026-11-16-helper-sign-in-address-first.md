# The Helper's Sign In Window, Address First: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Healdan Helper's Sign In window asks for the server's address first, then shows that server's own sign-in choices, so a provider's button is the first thing a person at a provider firm sees and no password is tried before the server has answered.

**Architecture:** `SignInPrompt.ProvidersAsync` returns a `ServerAnswer` (the choices, or the reason there are none, and whether trying again may help) instead of a bare `ProviderChoice?`. `SignInWindow` becomes two steps in one window: the address with Continue, and the server's choices with a Change link. A kept address opens on the choices and retries every 5 seconds while the server can't be reached.

**Tech Stack:** .NET 10, Avalonia 12 (headless tests with Skia), xUnit (`[Fact]`, and `[MacFact]` for window tests).

**Spec:** `docs/superpowers/specs/2026-11-16-helper-sign-in-address-first-design.md`

## Global Constraints

- Healdan Helper only: no server change, no upstream file, no change to `openapi.yaml`.
- The password box is emptied as soon as the password is sent; the window never holds it beyond the one call.
- The window never opens the browser by itself.
- Every label is Title Case and every message British English, as the helper's other windows. Exact copy, as this plan pins it: `Continue`, `Connecting…`, `Change`, `Connecting to {name}…`, `{problem} Trying again…`, `Enter your Healdan server's address.`
- Provider and password labels are shown as text (`TextBlock`), never markup or access keys, as today.
- Window tests are `[MacFact]`, so they run only on a Mac (CI's `macos-15` job). Run them on this Mac.
- Docs change in the same pull request (CLAUDE.md, "Docs site"); the writing rules are `site/content/developer/writing-docs.md`.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Return in the address box on a fresh install** connects, and sends no password: the bug that started this. (Task 2, `ReturnInTheAddressConnectsAndSendsNoPassword`)
2. **A late answer after Change** (the kept server answers after the person moved to another) is dropped, not shown for the new server. (Task 2, `ALateAnswerAfterChangeIsDropped`)
3. **The retry stops** when the window closes and when the person presses Change: nothing keeps asking a server nobody's looking at. (Task 2, `RetryingStopsWhenTheWindowCloses`, `RetryingStopsAtChange`)
4. **Hiding and showing the window while it retries** asks once at a time, never two asks for the same address at once. (Task 2, `ShowingTheWindowAgainWhileAskingAsksOnce`)
5. **Continue pressed twice** while connecting asks once. (Task 2, `ContinueIsOffWhileItConnects`)

---

### Task 1: The server's answer, with its reason

**Files:**
- Modify: `clients/helper/Healdan.Helper.Core/Ui/SignInPrompt.cs`
- Modify: `clients/helper/Healdan.Helper.Core/Session/SignIn.cs:118-126` (`RequireHealdanAsync`)
- Modify: `clients/helper/Healdan.Helper.Core/Session/HelperSession.cs:698-726` (`ProvidersAsync`)
- Modify (adapter only, replaced in Task 2): `clients/helper/Healdan.Helper/Windows/SignInWindow.axaml.cs:122-138`, `clients/helper/Healdan.Helper.Tests/Windows/SignInWindowTests.cs:45-61`
- Test: `clients/helper/Healdan.Helper.Core.Tests/Session/HelperSessionTests.cs:262-317`

**Interfaces:**
- Produces, in `SignInPrompt.cs`:
  ```csharp
  /// <param name="Name">The server as the window names it: its host and any port, such as healdan.example; null when the address isn't one.</param>
  /// <param name="Choice">The server's providers and password label; null when there's a problem.</param>
  /// <param name="Problem">Why there are no choices, as the window shows it; null when there are.</param>
  /// <param name="Retry">The server couldn't be reached or didn't answer in time, so asking again later may work.</param>
  public sealed record ServerAnswer(string? Name, ProviderChoice? Choice, string? Problem, bool Retry = false);
  ```
  and `SignInPrompt.ProvidersAsync` becomes `Func<string, Task<ServerAnswer>>` (same position and name). It never throws.
- Produces, in `SignIn.cs`: `internal static void RequireHealdan(ServerInfo server)`, throwing today's two `InvalidOperationException`s; `RequireHealdanAsync` calls it.

- [ ] **Step 1: Rewrite the three `ProvidersAsync` tests in `HelperSessionTests`**

`TheSignInWindowIsGivenTheServersProviders` becomes `TheSignInWindowIsGivenTheServersChoicesOrWhyNot`, with the same five calls into `List<ServerAnswer>`:

```csharp
var answer = answers[0];
Assert.Equal("healdan.test", answer.Name);
Assert.Equal([new ProviderInfo("entra", "Sign In with Microsoft"), new ProviderInfo("authentik", "Sign In")], answer.Choice!.Providers);
Assert.Equal("Sign In with a Password", answer.Choice.PasswordLabel);
Assert.Null(answer.Problem);
Assert.All(answers.Skip(1), a => Assert.Null(a.Choice));
Assert.All(answers, a => Assert.False(a.Retry));
Assert.Contains("isn't a Healdan server", answers[1].Problem);
Assert.Contains("v2", answers[2].Problem);
Assert.Contains("https", answers[3].Problem);
Assert.DoesNotContain("Parameter", answers[3].Problem);
Assert.Equal("Enter your Healdan server's address.", answers[4].Problem);
Assert.Empty(_keychain.Items);
```

`AServerWithNoProvidersGivesAnEmptyList` reads `answer.Choice!`. `AServerThatCantBeReachedGivesNoProviders` becomes `AServerThatCantBeReachedSaysSoAndCanBeRetried`:

```csharp
Assert.Null(answer.Choice);
Assert.Equal("healdan.test couldn't be reached: Connection refused (fake).", answer.Problem);
Assert.True(answer.Retry);
Assert.Equal("healdan.test", answer.Name);
```

- [ ] **Step 2: Run them to see them fail to compile**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~HelperSessionTests"`
Expected: build error, `ServerAnswer` not found.

- [ ] **Step 3: Add `ServerAnswer` and change `SignInPrompt.ProvidersAsync`'s type and its doc comment**

- [ ] **Step 4: Rewrite `HelperSession.ProvidersAsync(string address) -> Task<ServerAnswer>`**

It gives the reasons `TrySignInAsync` gives, from the same places:

| Case | `Problem` | `Retry` |
|---|---|---|
| empty or white space | `Enter your Healdan server's address.` | false |
| `BeforeSignIn(address, out server, out api)` refuses (unusable address, `http://` off localhost, another server while signed in, copies open that it would set aside) | its message | false |
| `SignIn.RequireHealdan(info)` throws | its message | false |
| `ApiException` | `Refused(e, NotHealdan)` | false |
| `JsonException` | `NotHealdan` | false |
| `OperationCanceledException` (the 30-second `SignInTimeout`) | `$"{server.Authority} didn't answer within {SignInTimeout.TotalSeconds:0} seconds."` | true |
| `HttpRequestException` | `$"{server.Authority} couldn't be reached: {e.Message}"` | true |

`Name` is `server.Authority` once the address parsed, else null. Keep the default labels and the provider filtering as they are, and keep the `_log.Info` line for an answer that failed. Factor the `TrySignInAsync` catch messages into one private helper if both use them; don't change what `TrySignInAsync` returns.

- [ ] **Step 5: Keep the app compiling with a temporary adapter**

In `SignInWindow.AskProvidersAsync`, `choice = (await Task.Run(() => prompt.ProvidersAsync(address))).Choice;`. In `SignInWindowTests.FakePrompt.Make`, wrap `OnProviders`: `async s => { ProvidersAsked.Enqueue(s); var c = await OnProviders(s); return new ServerAnswer(s, c, c is null ? "unreachable" : null, c is null); }`. Task 2 replaces both.

- [ ] **Step 6: Run the helper's tests**

Run: `dotnet test clients/helper/Healdan.Helper.sln`
Expected: all pass, the window tests included.

- [ ] **Step 7: Commit**

```bash
git add clients/helper
git commit -m "Give the Sign In window the server's answer, or why there's none"
```

---

### Task 2: The window in two steps

**Files:**
- Modify: `clients/helper/Healdan.Helper/Windows/SignInWindow.axaml`
- Modify: `clients/helper/Healdan.Helper/Windows/SignInWindow.axaml.cs`
- Test: `clients/helper/Healdan.Helper.Tests/Windows/SignInWindowTests.cs`

**Interfaces:**
- Consumes: `ServerAnswer`, `SignInPrompt.ProvidersAsync : Func<string, Task<ServerAnswer>>` (Task 1); `ServerAddress.Name(string)` (Core, for the kept address's name before its answer).
- Produces, for the tests: `public Task Loading` (the latest ask, done once the window shows its answer, as today), `public TimeSpan RetryEvery { get; set; } = TimeSpan.FromSeconds(5)`, `public bool SignedIn`. Control names: `NoticeBox`, `NoticeText`, `AddressStep`, `Server`, `ChoicesStep`, `ServerName`, `ChangeLink`, `Status`, `ProviderList`, `PasswordLink`, `PasswordForm`, `User`, `Password`, `Waiting`, `Problem`, `CancelButton`, `ContinueButton`, `SignInButton`.

The window's rules, from spec §3 (the spec says which are the owner's):

- **Header** (mark, title, keychain line, `NoticeBox`) above both steps.
- **`AddressStep`:** the Server Address label and `Server`. `ContinueButton` ("Continue", accent) shows in the footer only on this step and is the only `IsDefault` button there. Continue asks `ProvidersAsync(Address)`, with `Server` and Continue off and Continue saying "Connecting…". An answer with a choice goes to `ChoicesStep`; one with a problem shows it on `Problem` and stays, Continue on again, never retried.
- **`ChoicesStep`:** `ServerName` (the answer's `Name`) and `ChangeLink` ("Change", the `link` class) on one row; `Status`; `ProviderList`; `PasswordLink`; `PasswordForm`; `Waiting`. `SignInButton` shows in the footer only while `PasswordForm` does, and is then the `IsDefault` button.
  - Providers: a button each, the password form folded behind `PasswordLink`, the first button focused. None: `PasswordForm` and `SignInButton` at once, `User` focused.
  - Provider and password sign-ins use the address that answered. `SignInAsync` is called with no ask first. A refusal stays on this step, on `Problem`.
  - `ChangeLink` is hidden when `ServerFixed`, and off while a sign-in runs. It goes to `AddressStep`: the kept or answered address in `Server`, all selected and focused, `User` and `Password` emptied, `Problem` hidden, any retry stopped, and any answer still to come dropped (`_ask` counts asks, as today). Coming back folds the password form again.
- **Opening:** `prompt.Server` null or empty opens `AddressStep` with `Server` focused and asks nothing. Otherwise `ChoicesStep` at once, `ServerName` set to `ServerAddress.Name(prompt.Server)`, and `Status` saying `Connecting to {name}…` (class `muted`) while it asks.
  - An answer with `Retry` shows `{Problem} Trying again…` on `Status` (class `problem`), and the window asks again after `RetryEvery`, while it's open, visible and still on this step with no choices. `Status` keeps that text while it asks again, rather than going back to `Connecting to {name}…`. One with another problem shows `{Problem}` there and isn't retried. An answer with choices hides `Status`.
  - `Opened` (Avalonia raises it on every `Show`) hides `Problem`, and, on `ChoicesStep` with no choices and no ask under way, asks again. An ask under way is never repeated for the same address.
- `Closed` stops the browser's wait, as today, and any retry.

- [ ] **Step 1: Rewrite `SignInWindowTests`**

`FakePrompt.OnProviders` becomes `Func<string, Task<ServerAnswer>>`, recording each ask in `ProvidersAsked` as today, and the Task 1 adapter goes. Add helpers:

```csharp
private static ServerAnswer Answer(ProviderChoice choice, string name = "healdan.example") => new(name, choice, null);
private static ServerAnswer Unreachable(string name = "healdan.example") =>
    new(name, null, $"{name} couldn't be reached: Connection refused.", Retry: true);
private static ServerAnswer NotHealdan(string name = "healdan.example") => new(name, null, "That address isn't a Healdan server.");
```

`Run` sets `window.RetryEvery = TimeSpan.FromMilliseconds(50)` before `Show`. Delete `AServerThatCantBeReachedShowsThePasswordForm`, `NoAddressAsksNothingUntilOneIsTyped`, `AddressChangeReloadsProviders`, `WhatWasTypedKeepsThePasswordFormOpen`, `AnUnreachableAnswerIsAskedAgain`, `ShowingTheWindowAgainAsksAgainAfterAnAnswerThatDidntCome`, `SignInAsksAgainFirstAndShowsTheProvidersWithoutSendingThePassword`, `SignInSendsThePasswordWhenTheProvidersStillDontComeBack` and `SignInPressedWhileItAsksAgainAsksOnce`: each tests a behaviour the steps replace. Keep the rest on the new prompt. In `ButtonSignsIn`, assert `ChangeLink` is off where it asserted `Server`; in `RefusalShowsTheReason`, assert `ChangeLink` is on again; in `AFailedProviderSignInPutsTheFocusOnItsButtonWhereverItWas`, focus `CancelButton` where it focused `Server`.

New tests:

- `AFreshInstallOpensOnTheAddress`: `Make(server: null)`. `AddressStep`, `Server` and `ContinueButton` shown, `ContinueButton.IsDefault`; `ChoicesStep`, `User` and `SignInButton` not shown; `Server.IsFocused`; `ProvidersAsked` empty.
- `ReturnInTheAddressConnectsAndSendsNoPassword`: `Make(server: null)`, `OnProviders = _ => Task.FromResult(Answer(Two))`. Set `Server.Text = "healdan.example"`, focus it, `window.KeyPressQwerty(PhysicalKey.Enter, RawInputModifiers.None)` (Avalonia.Headless; use its `KeyPress` overload if Avalonia 12 names it so), `await Until(() => ProviderButtons(window).Count == 2)`. `ProvidersAsked == ["healdan.example"]`, `PasswordSignIns` empty, `ChoicesStep` shown, `AddressStep` not, `ServerName.Text == "healdan.example"`, `Problem` not shown, the first provider button focused.
- `ContinueShowsWhyOnTheAddress`: `Make(server: null)`, `OnProviders = _ => Task.FromResult(Unreachable())`. Type an address, click Continue, await `Loading`. `Problem.Text == "healdan.example couldn't be reached: Connection refused."`; `AddressStep` still shown; `ContinueButton.Content == "Continue"` and on; `Server` on. `await Task.Delay(300)`: `ProvidersAsked.Count == 1` (the address step never retries).
- `ContinueIsOffWhileItConnects`: `Make(server: null)`, a pending `TaskCompletionSource<ServerAnswer>`. Type an address, click Continue twice. `Until(() => ProvidersAsked.Count == 1)`; `ContinueButton.Content == "Connecting…"`, Continue and `Server` off. Set `Answer(None)`: `User` and `SignInButton` shown, `User.IsFocused`, `ProvidersAsked.Count == 1`.
- `AKeptAddressOpensOnTheChoices`: `Make(server: "https://healdan.example")`, a pending answer. `ChoicesStep` shown, `AddressStep` and `ContinueButton` not; `ServerName.Text == "healdan.example"`; `Status.Text == "Connecting to healdan.example…"`; `ChangeLink` shown; no provider buttons; `PasswordForm` not shown. Set `Answer(Two)`: two buttons, `Status` not shown, `PasswordLink` shown.
- `NoProvidersShowsThePasswordForm` (rewrite of the existing one): `Answer(None)`. `User`, `Password` and `SignInButton` shown, `SignInButton.IsDefault`, `ContinueButton` not shown, `User.IsFocused`.
- `ChangeGoesBackToTheAddress`: kept address, `Answer(Two)`. Click `PasswordLink`, type `admin` and a password, click `ChangeLink`. `AddressStep` shown, `ChoicesStep` not; `Server.Text == "https://healdan.example"`, `Server.IsFocused`, `Server.SelectionStart == 0`, `Server.SelectionEnd == Server.Text.Length`; `User.Text` and `Password.Text` empty. Then `Server.Text = "firm.example"`, Continue, await `Loading`: `ServerName.Text == "firm.example"`, `PasswordLink` shown and `User` not (folded again).
- `AFixedServerHasNoChange`: `Make(server: "https://healdan.example", serverFixed: true)`, `Answer(Two)`. `ChangeLink` not shown, `AddressStep` not shown, `ProvidersAsked.Count == 1`.
- `AnUnreachableKeptServerIsAskedAgain`: answers queue `[Unreachable(), Unreachable(), Answer(Two)]`. First `Until(() => Status.Text == "healdan.example couldn't be reached: Connection refused. Trying again…")` and `Status.Classes.Contains("problem")`; then `Until(() => ProviderButtons(window).Count == 2)`; `ProvidersAsked.Count == 3`; `Status` not shown; `await Task.Delay(300)` and the count is still 3.
- `AnotherProblemOnTheChoicesIsntRetried`: kept address, `NotHealdan()` always. `Until(() => Status.Text == "That address isn't a Healdan server.")`; `await Task.Delay(300)`; `ProvidersAsked.Count == 1`; `ChangeLink` shown and on.
- `RetryingStopsWhenTheWindowCloses`: kept address, `Unreachable()` always. `Until(() => ProvidersAsked.Count >= 2)`, `window.Close()`, note the count, `await Task.Delay(300)`, the count unchanged.
- `RetryingStopsAtChange`: the same, clicking `ChangeLink` instead of closing; `AddressStep` shown.
- `ALateAnswerAfterChangeIsDropped`: kept address, whose answer is a pending `TaskCompletionSource`; `firm.example` answers `Answer(None, "firm.example")` at once. Click `ChangeLink`, set `firm.example`, Continue, await `Loading`. Then set the first answer to `Answer(Two)` and `await Task.Delay(100)`: no provider buttons, `ServerName.Text == "firm.example"`, `User` shown.
- `ShowingTheWindowAgainAsksAgainWithoutChoices`: fixed kept address, answers `[NotHealdan(), Answer(Two)]`. Await `Loading`, `Hide()`, `Show()`, await `Loading`: two buttons, `ProvidersAsked.Count == 2`. `Hide()`, `Show()` again: still 2.
- `ShowingTheWindowAgainWhileAskingAsksOnce`: as today, on the new prompt, with the pending answer then set to `NotHealdan()`, which isn't retried, so the count stays 1.
- `APasswordRefusalStaysOnTheChoices`: extend `APasswordRefusalStillShowsOnTheErrorLine`: `ChoicesStep` still shown and `ProvidersAsked.Count == 1` after the refusal.

- [ ] **Step 2: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~SignInWindowTests"`
Expected: build errors on the controls `AddressStep`, `ContinueButton`, `ChangeLink` and the rest, and on `RetryEvery`.

- [ ] **Step 3: Lay out the two steps in `SignInWindow.axaml`**

The header as it is; `AddressStep` and `ChoicesStep` as two `StackPanel`s in the fields' place; `Problem` under both; the footer with `CancelButton`, `ContinueButton` and `SignInButton`, the last two accent. Update the file's opening comment for the steps.

- [ ] **Step 4: Rewrite `SignInWindow.axaml.cs` to the rules above**

Keep the class comment's style, rewritten for the steps. Schedule the retry with `DispatcherTimer.RunOnce(…, RetryEvery)`, kept so `Closed` and Change can dispose it. Set `IsDefault` in code as each step and the fold change, since only one button may be the default. Today's `_unanswered`, `_shownFor` checks in `OnProvider`, and `OnSignIn`'s ask-first branch go.

- [ ] **Step 5: Run the window's tests**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~SignInWindowTests"`
Expected: all pass.

- [ ] **Step 6: Run the helper's whole suite**

Run: `dotnet test clients/helper/Healdan.Helper.sln`
Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add clients/helper
git commit -m "Ask for the server's address first in the helper's Sign In window"
```

---

### Task 3: The docs and their pictures

**Files:**
- Modify: `site/content/user/office/installing-the-helper/index.md:63-100`
- Replace: `site/content/user/office/installing-the-helper/sign-in.png`, `sign-in-provider.png`
- Modify: `site/content/developer/desktop-clients.md:351-363` and `:834-837`
- Modify: `docs/superpowers/specs/2026-11-01-m4-oidc-sign-in-design.md` §6.1

**Interfaces:**
- Consumes: the window as Task 2 leaves it, and its copy.

- [ ] **Step 1: Rewrite the User Guide's The Sign In Window and Signing In Through the Browser**

The Sign In Window: type the address and choose **Continue**; the window then shows the server's own way of signing in: a button for each provider, with **Sign In with a Password** under them, or the username and password for a server with none. **Change** goes back to the address. Problems with the address ("That address isn't a Healdan server", couldn't be reached) show under it. Once the helper knows the address it opens straight on the second step, and if it can't reach the server, it says so and tries again every few seconds. Keep the bullets on the address, the password not being kept, wrong passwords, and the sign-in's lifetime, moving "That address isn't a Healdan server" to say it shows when you choose Continue. Signing In Through the Browser: the buttons show once you've chosen Continue, not once the address is left.

- [ ] **Step 2: Redraw the two pictures**

`sign-in.png` is step 1 with `healdan.example` typed; `sign-in-provider.png` is step 2 for a server with one provider, **Sign In with Microsoft**, and the password link folded, as now. Step 2 names the server that answered, so a demo stack on `localhost` would show `localhost:<port>`: render the window instead in the headless harness of `Healdan.Helper.Tests` (Skia, the helper's styles and font shaper, as `Headless` sets them up) with a fake prompt naming `healdan.example`, light mode, at 1×, 420 points wide, in a scratch test that isn't committed (`window.CaptureRenderedFrame()`, saved as PNG). Check its header is pixel for pixel the current `sign-in.png`'s before replacing either. Update both pictures' alt text to what they show.

- [ ] **Step 3: Update the Developer Guide's Desktop Clients page**

Replace the paragraph from "The Sign In window offers it" to "which is a stale one by then": `ProvidersAsync` gives a `ServerAnswer`; the window's two steps; a kept address opening on the choices and its 5-second retry on `Retry` only; a password going only to a server whose choices the window shows. Rewrite the `SignInWindowTests` bullet for the tests Task 2 left.

- [ ] **Step 4: Point M4's spec here**

At the end of §6.1's first bullet: `(changed later: the address first, then the server's choices; docs/superpowers/specs/2026-11-16-helper-sign-in-address-first-design.md.)`

- [ ] **Step 5: Build and check the site**

Run: `hugo --source site --gc --minify --panicOnWarning && python3 .github/scripts/check-site-sources.py site && python3 .github/scripts/check-site-links.py site/public`
Expected: the build finishes with no warning, and both checks pass.

- [ ] **Step 6: Commit**

```bash
git add site docs/superpowers/specs/2026-11-01-m4-oidc-sign-in-design.md
git commit -m "Describe the helper's address-first Sign In window"
```
