namespace Healdan.Helper.Core.Office;

/// <summary>
/// A request from the Office add-in that the helper refuses, with the code its routes answer it with (a <c>409</c>, M3.5
/// plan: the add-in's routes). Nothing has been filed or saved when it's thrown.
/// </summary>
public sealed class OfficeRefusedException : Exception
{
    /// <summary>The program's front window isn't the one the request came from, which carries the add-in's mark where it shows.</summary>
    public const string WrongWindow = "wrong-window";

    /// <summary>The ticket Save to Healdan gave has been used, has expired, or was never given.</summary>
    public const string NoTicket = "no-ticket";

    /// <summary>The document isn't a local copy, where the request needs one.</summary>
    public const string NotACopy = "not-a-copy";

    public OfficeRefusedException(string code, string message) : base(message)
    {
        Code = code;
    }

    /// <summary><see cref="WrongWindow"/>, <see cref="NoTicket"/> or <see cref="NotACopy"/>.</summary>
    public string Code { get; }
}
