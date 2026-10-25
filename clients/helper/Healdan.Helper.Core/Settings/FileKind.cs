namespace Healdan.Helper.Core.Settings;

/// <summary>A kind of file that opens in an office app (spec §5.11), such as Documents, with its extensions.</summary>
public sealed record FileKind(string Label, IReadOnlyList<string> Extensions);
