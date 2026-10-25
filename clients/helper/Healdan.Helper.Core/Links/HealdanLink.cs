using System.Text.RegularExpressions;

namespace Healdan.Helper.Core.Links;

/// <summary>
/// The links the web page hands the helper (spec §5.3): <c>healdan://open/&lt;docNumber&gt;?server=&lt;host:port&gt;</c>,
/// with <c>&amp;readonly=1</c> for a read-only copy. A link carries no credentials and can only open, so anything else
/// isn't one. <c>server</c> is the web page's <c>location.host</c>: the host, and the port when it isn't the default.
/// </summary>
public static partial class HealdanLink
{
    public const string Scheme = "healdan";

    /// <summary>Longer than any link the web page makes; anything longer isn't one.</summary>
    private const int MaxLength = 2048;

    /// <summary>A doc # has at most 18 digits, so it always fits a <c>long</c>.</summary>
    private const int MaxDigits = 18;

    /// <summary>
    /// Reads an open link. False for any other link: another scheme or verb, credentials or a port where the verb is,
    /// more path than the doc #, a doc # that isn't a positive whole number, or <c>server</c> given twice. A link
    /// without <c>server</c> parses, with <paramref name="server"/> null; the link handler refuses it.
    /// </summary>
    public static bool TryParse(string url, out long docNumber, out bool readOnly, out string? server)
    {
        docNumber = 0;
        readOnly = false;
        server = null;
        if (string.IsNullOrEmpty(url) || url.Length > MaxLength || !Uri.TryCreate(url, UriKind.Absolute, out var uri))
            return false;
        if (uri.Scheme != Scheme || uri.UserInfo.Length > 0 || !uri.Authority.Equals("open", StringComparison.OrdinalIgnoreCase))
            return false;

        var path = uri.AbsolutePath;
        if (path.Length < 2 || path.Length > MaxDigits + 1 || path[0] != '/' || !path[1..].All(char.IsAsciiDigit))
            return false;
        docNumber = long.Parse(path[1..], System.Globalization.CultureInfo.InvariantCulture);
        if (docNumber <= 0)
            return false;

        var seenReadOnly = false;
        foreach (var part in uri.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var eq = part.IndexOf('=');
            var key = Uri.UnescapeDataString(eq < 0 ? part : part[..eq]);
            var value = eq < 0 ? "" : Uri.UnescapeDataString(part[(eq + 1)..]);
            switch (key)
            {
                case "server":
                    if (server is not null)
                        return Refuse(out docNumber, out readOnly, out server); // which one would it be?
                    server = value;
                    break;
                case "readonly":
                    if (seenReadOnly)
                        return Refuse(out docNumber, out readOnly, out server);
                    seenReadOnly = true;
                    readOnly = value is "1" or "true";
                    break;
            }
        }
        return true;
    }

    /// <summary>
    /// Whether a link's <c>server</c> names <paramref name="signedIn"/>: the same host, compared without regard to case,
    /// and the same port, the scheme's default when the link leaves it out. Anything but a host and an optional port
    /// (a path, credentials, a scheme) never matches.
    /// </summary>
    public static bool IsServer(string? server, Uri signedIn)
    {
        if (!IsHostAndPort(server))
            return false;
        if (!Uri.TryCreate($"{signedIn.Scheme}://{server}/", UriKind.Absolute, out var given))
            return false;
        return given.IdnHost.Equals(signedIn.IdnHost, StringComparison.OrdinalIgnoreCase) && given.Port == signedIn.Port;
    }

    /// <summary>A host (a DNS name, an IPv4 address or an IPv6 one in brackets) and an optional port, and nothing else.</summary>
    public static bool IsHostAndPort(string? server) =>
        !string.IsNullOrEmpty(server) && server.Length <= 255 + 6 && HostAndOptionalPort().IsMatch(server);

    /// <summary>The server as a link names it, for messages: <c>healdan.example</c>, or <c>localhost:8096</c>.</summary>
    public static string HostAndPort(Uri server) => server.Authority;

    private static bool Refuse(out long docNumber, out bool readOnly, out string? server)
    {
        (docNumber, readOnly, server) = (0, false, null);
        return false;
    }

    /// <summary>A DNS name or IPv4 address, or an IPv6 address in brackets, then an optional port.</summary>
    [GeneratedRegex(@"^(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*|\[[0-9A-Fa-f:.]+\])(?::[0-9]{1,5})?$")]
    private static partial Regex HostAndOptionalPort();
}
