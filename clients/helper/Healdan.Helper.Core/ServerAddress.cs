namespace Healdan.Helper.Core;

/// <summary>
/// A server's address as <c>settings.json</c> and each copy's record hold it, such as <c>https://healdan.example</c>:
/// the scheme, host, any port and any path, with no trailing slash. Two addresses name the same server when they match
/// without regard to case (spec §5.4).
/// </summary>
public static class ServerAddress
{
    public static string Of(Uri server) => server.GetLeftPart(UriPartial.Path).TrimEnd('/');

    public static bool Same(Uri a, Uri b) => Same(Of(a), Of(b));

    public static bool Same(string? a, string? b) =>
        a is not null && b is not null && string.Equals(a, b, StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// The server as the user knows it, its host and any port, such as <c>localhost:8080</c>, as links and notices name
    /// it; the address itself when it isn't one.
    /// </summary>
    public static string Name(string address) => Uri.TryCreate(address, UriKind.Absolute, out var uri) ? uri.Authority : address;

    /// <summary>
    /// The server's origin, as a browser sends it in <c>Origin</c> from the server's pages: the scheme, the host in lower
    /// case and any port that isn't the scheme's own, without the path, such as <c>https://healdan.example</c>. The add-in's
    /// port answers pages from it alone (M3.5 spec §5.8).
    /// </summary>
    public static string Origin(Uri server) => server.GetComponents(UriComponents.SchemeAndServer, UriFormat.UriEscaped).ToLowerInvariant();
}
