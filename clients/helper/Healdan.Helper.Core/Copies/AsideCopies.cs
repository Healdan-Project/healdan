namespace Healdan.Helper.Core.Copies;

/// <summary>One server's copies set aside beside <c>Copies</c> (spec §5.4).</summary>
/// <param name="Server">The server as the user knows it, its host and any port, such as <c>healdan.example</c>.</param>
/// <param name="Folder">The folder holding them, the first by name when they're in several.</param>
/// <param name="Count">How many copies; files without a record that moved with them aren't counted.</param>
public sealed record AsideCopies(string Server, string Folder, int Count)
{
    /// <summary>The menu's line for them.</summary>
    public string Line => Count == 1 ? $"1 copy waiting for {Server}" : $"{Count} copies waiting for {Server}";
}

/// <summary>What <see cref="CopyStore.BringBack"/> did: how many copies came back, and which stayed aside.</summary>
public sealed record BroughtBack(int Count, IReadOnlyList<StayedAside> Stayed);

/// <summary>A set-aside copy that didn't come back (spec §5.4).</summary>
/// <param name="Title">Its title, as its window shows it, for the notice.</param>
/// <param name="DocRef">Its <c>#1234 v2.1</c>, for the log.</param>
/// <param name="Folder">The folder it stays in.</param>
/// <param name="Why">Why, as the clause the notice ends with.</param>
public sealed record StayedAside(string Title, string DocRef, string Folder, string Why);

/// <summary>A copy an app has open in <c>Copies</c>, which setting aside would move from under its window (spec §5.4).</summary>
/// <param name="Title">Its title, as its window shows it.</param>
/// <param name="FileName">Its file name in <c>Copies</c>.</param>
/// <param name="Server">The server it would be set aside for, as the user knows it, such as <c>healdan.example</c>.</param>
public sealed record OpenCopy(string Title, string FileName, string Server);
