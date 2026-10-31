namespace Healdan.Helper.Core.Settings;

/// <summary>
/// A kind of file that opens in an office app (spec §5.11), such as Documents, with its extensions.
/// <paramref name="MainFormat"/> is the extension, without its dot, whose default app the system's choice for the kind goes
/// by; null for a kind Microsoft Office doesn't open.
/// </summary>
public sealed record FileKind(string Label, IReadOnlyList<string> Extensions, string? MainFormat = null);
