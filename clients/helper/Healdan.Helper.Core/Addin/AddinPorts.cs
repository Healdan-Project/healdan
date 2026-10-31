namespace Healdan.Helper.Core.Addin;

/// <summary>
/// The ports the add-in's port listens on (M3.5 spec §5.8), on <c>127.0.0.1</c>: the first of them it can take. The add-in
/// tries them in the same order, so another user of the Mac with a helper on the first doesn't stop this one. The add-in's
/// pages on the server allow connections to these alone (their Content Security Policy), so they change together.
/// </summary>
public static class AddinPorts
{
    public static IReadOnlyList<int> All { get; } = [47615, 47616, 47617, 47618];
}
