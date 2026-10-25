using Healdan.Helper.Core.Api;

namespace Healdan.Helper.Core.Browse;

/// <summary>
/// A document's saves, from its timeline (<c>GET …/versions</c>, newest first). A change of properties, a move or a
/// rename adds a row too but keeps the file: the saves are the rows whose <c>fileVersion</c> is their <c>version</c>.
/// </summary>
public static class Versions
{
    public static IReadOnlyList<VersionView> Saves(IEnumerable<VersionView> timeline) => [.. timeline.Where(v => v.FileVersion == v.Version)];

    /// <summary>When the document's content was last saved: the date of the save its <paramref name="fileVersion"/> names.</summary>
    public static DateTimeOffset? LastSaved(IEnumerable<VersionView> timeline, string fileVersion) =>
        timeline.FirstOrDefault(v => v.Version == fileVersion && v.FileVersion == fileVersion)?.Date;
}
