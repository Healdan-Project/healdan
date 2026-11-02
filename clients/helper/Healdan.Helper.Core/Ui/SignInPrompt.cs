using Healdan.Helper.Core.Api;

namespace Healdan.Helper.Core.Ui;

/// <summary>What the Sign In window needs (spec §5.2, M4 spec §6.1).</summary>
/// <param name="Server">The address to show, from the settings; null at the first run.</param>
/// <param name="ServerFixed">
/// The helper is running against <paramref name="Server"/>, so only the password can change (one server per helper,
/// spec §5.1): after a <c>401</c>, for instance.
/// </param>
/// <param name="SignInAsync">
/// Signs in with the address, username and password the user typed. Null when it worked, otherwise the reason to show
/// on the window's error line. The password is used for this one call and never kept.
/// </param>
/// <param name="ProvidersAsync">
/// The sign-in providers of the server at the address, with its password link's label, for the window's buttons; an
/// empty list for a server with none, and null when the address isn't a Healdan server the helper can reach. Never
/// throws.
/// </param>
/// <param name="SignInWithProviderAsync">
/// Signs in through the provider with the id, at the address, in the system browser (M4 spec §6.2). Null when it
/// worked, otherwise the reason to show on the error line, the browser's 5 minutes running out included. Cancelling
/// stops the helper waiting for the browser; its answer is then not null, and the window needs no reason for it.
/// </param>
public sealed record SignInPrompt(string? Server, bool ServerFixed,
    Func<string, string, string, Task<string?>> SignInAsync,
    Func<string, Task<ProviderChoice?>> ProvidersAsync,
    Func<string, string, CancellationToken, Task<string?>> SignInWithProviderAsync);

/// <summary>A server's sign-in choices (<c>GET /server</c>): its providers, in the order of their buttons, and the password link's label.</summary>
public sealed record ProviderChoice(IReadOnlyList<ProviderInfo> Providers, string PasswordLabel);
