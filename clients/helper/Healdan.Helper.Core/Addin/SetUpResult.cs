namespace Healdan.Helper.Core.Addin;

/// <summary>How Set Up Microsoft Office's trusting of the certificate ended (<see cref="OfficeSetUp.TrustAsync"/>).</summary>
/// <param name="Trusted">The computer trusts the certificate now.</param>
/// <param name="Problem">Why it doesn't, in the user's words; null when they cancelled, which is theirs to choose.</param>
public sealed record SetUpResult(bool Trusted, string? Problem)
{
    public static readonly SetUpResult Done = new(true, null);

    public static readonly SetUpResult Cancelled = new(false, null);

    public static SetUpResult Failed(string problem) => new(false, problem);
}
