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
