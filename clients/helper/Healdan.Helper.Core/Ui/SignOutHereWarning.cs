namespace Healdan.Helper.Core.Ui;

/// <summary>Sign Out on This Computer Only?'s words: Sign Out couldn't reach Healdan to revoke the token.</summary>
/// <param name="Title">"Healdan at healdan.example can't be reached".</param>
/// <param name="Detail">What forgetting the sign-in here does, and what it leaves on the server.</param>
/// <param name="Unsent">"1 save hasn't reached Healdan. …", or null when every save has.</param>
public sealed record SignOutHereWarning(string Title, string Detail, string? Unsent);
