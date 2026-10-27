using System.Runtime.InteropServices;

namespace Healdan.Helper.Core.Ui;

/// <summary>What About says of the helper's server (#89).</summary>
/// <param name="Host">Its host and port, as the menu names it.</param>
/// <param name="SignedIn">Whether the helper is signed in to it.</param>
/// <param name="Version">The Healdan version its <c>GET /server</c> reports, or null when it couldn't be reached.</param>
public sealed record AboutServer(string Host, bool SignedIn, string? Version);

/// <summary>
/// About Healdan Helper's words (#89): the version, the copyright, the server line and the one line Copy for Support
/// puts on the clipboard. The helper contains no LogicalDOC code, so unlike the web app's About it has no credit to it.
/// </summary>
public static class About
{
    /// <summary>As <c>Info.plist</c>'s <c>NSHumanReadableCopyright</c> has it.</summary>
    public const string Copyright = "Copyright © 2026 Healdan Project";

    /// <summary>The user guide's pages for the helper.</summary>
    public static readonly Uri Docs = new("https://docs.healdan.com/user/office/");

    /// <summary>The licence, as the web app's About links it.</summary>
    public static readonly Uri Licence = new("https://github.com/Healdan-Project/healdan/blob/master/LICENSE");

    /// <summary>A Debug build's commit is shortened to this many characters.</summary>
    private const int CommitLength = 9;

    /// <summary>
    /// The version as About shows it: a release's as <c>package.sh</c> set it (<c>0.1.0</c>, <c>0.1.0-rc1</c>), and a
    /// Debug build's with its commit shortened (<c>1.0.0 (40cc299e4)</c>).
    /// </summary>
    public static string Version(string? informational)
    {
        if (string.IsNullOrEmpty(informational))
            return "unknown";
        var plus = informational.IndexOf('+');
        if (plus < 0)
            return informational;
        var commit = informational[(plus + 1)..];
        return $"{informational[..plus]} ({commit[..Math.Min(commit.Length, CommitLength)]})";
    }

    /// <summary>This computer's system and processor, such as <c>macOS 27.0.1 (arm64)</c>.</summary>
    public static string System() =>
        $"{RuntimeInformation.OSDescription} ({RuntimeInformation.OSArchitecture.ToString().ToLowerInvariant()})";

    /// <summary>"Signed in to healdan.example · Healdan 9.3.2", or why there's no version to show.</summary>
    public static string ServerLine(AboutServer? server) => server switch
    {
        null => "Not signed in",
        { Version: null } => $"{server.Host} couldn't be reached",
        _ => $"{(server.SignedIn ? "Signed in to" : "Not signed in to")} {server.Host} · Healdan {server.Version}"
    };

    /// <summary>Copy for Support's line: the helper's version, the system, and the server with its version.</summary>
    public static string SupportLine(string version, string system, AboutServer? server)
    {
        var where = server switch
        {
            null => "no server",
            { Version: null } => $"server {server.Host} (couldn't be reached)",
            _ => $"server {server.Host}, Healdan {server.Version}"
        };
        return $"Healdan Helper {version}, {system}, {where}";
    }
}
