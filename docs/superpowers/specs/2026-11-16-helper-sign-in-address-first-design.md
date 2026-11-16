# The helper's Sign In window, address first: design

- **Date:** 2026-11-16
- **Status:** approved (owner, 2026-11-16). Healdan Helper only: no server change and no upstream file.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal.

## 1. Why

On a fresh install the helper's Sign In window opens on the server's address and the username and password form. It
reads the server's providers (`GET /server`, M4 spec §6.1) only when it opens with an address already kept, and when
the address box loses focus. Return in the box, or a click on **Sign In**, presses **Sign In** (the default button)
while the box still has the focus, so the window tries the password first. With the boxes empty it says "Enter your
username and password." and moves the cursor to Password, and only that move sends the ask: the provider buttons then
appear under the error. The owner's helper log (2026-11-15 and 16) shows no ask that failed. Even when the ask comes in
time, the window opens on the password form and folds it a moment later.

Someone whose firm signs in through a provider should see its button first, and never be shown a password form for
an account that has no password. (owner)

## 2. Approaches

1. **Fix the one window.** Ask while the address is typed, after a pause, and make Return in the address box ask
   rather than sign in. The smallest change, but a fresh install still opens on the password boxes, and the form
   still folds when the answer comes.
2. **The address first, then the server's choices.** (owner, Claude's pick) As iManage Work's, Teams' and Slack's
   desktop apps do. Nothing about signing in shows until the server has answered, so nothing jumps and no password
   form shows for a server that leads with providers. More work: the window and its tests are redone.

## 3. The window

One window with two steps. Healdan's mark, the title, the keychain line and the notice ("Your sign-in ended. Sign in
again.") stay above both. (call)

```
 Step 1                               Step 2, a server with a provider
 Server Address                       healdan.rjl.zone            Change
 [ healdan.rjl.zone            ]      [   Sign In with Authentik    ]
                                            Sign In with a Password
                [Cancel] [Continue]                          [Cancel]
```

### 3.1 Step 1: the address (owner)

- The Server Address box, **Cancel**, and **Continue**, the default button, so Return in the box continues.
- **Continue** checks the address as a sign-in does today (`BeforeSignIn`: `https://` added when it's left out,
  `http://` refused but for `localhost`, another server refused while the helper is signed in to one) and reads
  `GET /server`. Meanwhile the box and **Continue** are off and **Continue** says "Connecting…". (call)
- **A problem** shows on the error line under the box, with its reason: the address isn't usable, the server
  couldn't be reached or didn't answer within 30 seconds, it isn't Healdan, or it speaks an API this helper doesn't.
  These are the reasons `TrySignInAsync` gives today. `SignInPrompt.ProvidersAsync` returns null for all of them;
  it returns the reason instead. **Continue** tries again. (call)

### 3.2 Step 2: the server's choices

- **A line names the server,** by its host (and port, when not the default), as the helper's menu does, with a
  **Change** link that goes back to step 1 with the address selected in its box. (owner; call on the look)
  - **No Change** when the helper is running against that server (`ServerFixed`, after a `401`): one server per
    helper (spec §5.1). (owner)
- **A server with providers** gets a button for each, in its order, then the password link with its label, which
  opens the username and password boxes and **Sign In**, as today's fold. The first button has the focus. (owner;
  call on the focus)
- **A server with none,** or a Healdan older than M4, gets the username and password boxes and **Sign In** at once,
  with the cursor in Username. (owner)
- **The browser's wait,** **Cancel** during it, refusals on the error line and the focus they leave stay as today.
  **Change** is off while a sign-in runs. (call)
- **A password goes only to a server whose choices step 2 shows,** so today's "Sign In asks for the providers again
  first" goes. (call)
- **The window never opens the browser by itself,** even for a server with one provider and nothing else. (call)

### 3.3 Which step the window opens on

- **No address kept** (a fresh install, or the helper's files deleted): step 1, empty, with the cursor in it. (owner)
- **An address kept** (after signing out, a `401`, or a sign-in made for another copy of the helper): step 2 at once.
  (owner) Until the server answers, a "Connecting to healdan.rjl.zone…" line stands where the choices go. (call)
  - **If it can't be reached,** that line says so and the window asks again every 5 seconds, showing the choices
    once it answers. The helper starts at the Mac's login, sometimes before the network is up. (call)
  - **Any other problem** (not Healdan, an API this helper doesn't speak) shows its reason there, with no retry.
    (call)
  - **Change** leads to step 1 throughout, except when the server is fixed (§3.2).

## 4. What it touches

- **The window:** `Windows/SignInWindow.axaml` and `.axaml.cs`.
- **The prompt:** `SignInPrompt.ProvidersAsync` returns the choices or the reason, and `HelperSession.ProvidersAsync`
  makes the address checks before it asks.
- **Tests:** `SignInWindowTests` redone for the steps. At least: a fresh install opens on step 1; Return in the box
  connects and sends no password; step 2 with providers and with none; **Change** and its absence when fixed; a kept
  address opens on step 2; the retry while the server can't be reached; each reason on step 1. `HelperSessionTests`
  cover the reasons `ProvidersAsync` gives.
- **Docs, in the same pull request:**
  - The User Guide's Installing the Helper (`site/content/user/office/installing-the-helper/`): The Sign In Window
    and Signing In Through the Browser rewritten for the steps, with their pictures redrawn (step 1, and step 2 for a
    server with a provider), rendered offscreen as before.
  - The Developer Guide's Desktop Clients page: the paragraph on how the window reads `GET /server`, and the
    `SignInWindowTests` line.
  - M4's spec §6.1: a "changed later" note pointing here.

## 5. Left to the builder

- The exact wording of the connecting and problem lines, in the helper's style.
- How the window resizes between the steps, and the focus where §3 doesn't say.
- Whether the docs need a third picture, for step 2 with the password form.
