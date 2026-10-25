namespace Healdan.Helper.Core.Ui;

/// <summary>What the Sign In window needs (spec §5.2).</summary>
/// <param name="Server">The address to show, from the settings; null at the first run.</param>
/// <param name="ServerFixed">
/// The helper is running against <paramref name="Server"/>, so only the password can change (one server per helper,
/// spec §5.1): after a <c>401</c>, for instance.
/// </param>
/// <param name="SignInAsync">
/// Signs in with the address, username and password the user typed. Null when it worked, otherwise the reason to show
/// on the window's error line. The password is used for this one call and never kept.
/// </param>
public sealed record SignInPrompt(string? Server, bool ServerFixed, Func<string, string, string, Task<string?>> SignInAsync);
