namespace Healdan.Helper.Core.Session;

/// <summary>
/// The provider sign-in was refused. <see cref="Code"/> is one of the server's refusal codes (<c>no-group</c>,
/// <c>email-taken</c>, <c>username-taken</c>, <c>account</c>, <c>provider</c> or <c>expired</c>), so a window can say
/// what the web page does.
/// </summary>
public sealed class ProviderRefusedException(string code) : Exception($"The sign-in was refused ({code}).")
{
    public string Code { get; } = code;
}
