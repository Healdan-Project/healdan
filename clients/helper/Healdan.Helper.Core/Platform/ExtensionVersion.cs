using System.IO.Compression;
using System.Xml;
using System.Xml.Linq;

namespace Healdan.Helper.Core.Platform;

/// <summary>
/// The LibreOffice extension's versions (spec §5.12): the one the helper carries, read from its <c>.oxt</c>'s
/// <c>description.xml</c> so the helper never needs a second copy of it, and the one installed, read from LibreOffice's
/// user registry (macOS) or <c>unopkg list</c> (Linux). They compare as LibreOffice compares extension versions, part
/// by part as numbers.
/// </summary>
public static class ExtensionVersion
{
    /// <summary>The extension's identifier, in its <c>description.xml</c>.</summary>
    public const string Identifier = "org.healdan.libreoffice";

    private static readonly XNamespace Description = "http://openoffice.org/extensions/description/2006";
    private static readonly XNamespace Registry = "http://openoffice.org/extensionmanager/extension-registry/2010";

    /// <summary>How the registry names a folder in the user's extension cache.</summary>
    private const string CacheUrl = "vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/";

    /// <summary>Whether the installed version, if any, is older than the bundled one. A newer one is never replaced.</summary>
    public static bool NeedsUpdate(string? installed, string bundled) => installed is null || Compare(installed, bundled) < 0;

    /// <summary>
    /// LibreOffice's order (<c>dp_misc::compareVersions</c>): dot-separated parts, leading zeros ignored, a missing
    /// part counting as 0, and a longer number the greater, so <c>0.10</c> is newer than <c>0.9</c>.
    /// </summary>
    public static int Compare(string a, string b)
    {
        var x = a.Trim().Split('.');
        var y = b.Trim().Split('.');
        for (var i = 0; i < Math.Max(x.Length, y.Length); i++)
        {
            var p = i < x.Length ? x[i].TrimStart('0') : "";
            var q = i < y.Length ? y[i].TrimStart('0') : "";
            if (p.Length != q.Length)
                return p.Length < q.Length ? -1 : 1;
            var order = string.CompareOrdinal(p, q);
            if (order != 0)
                return order < 0 ? -1 : 1;
        }
        return 0;
    }

    /// <summary>
    /// Healdan's extension in <c>unopkg list</c>'s output, or null when it isn't listed. Each extension starts with an
    /// unindented <c>Identifier:</c> line; its own <c>Version:</c> and <c>is registered:</c> lines are indented two
    /// spaces, and its bundled parts' lines further.
    /// </summary>
    public static InstalledExtension? FromUnopkgList(string output)
    {
        const string id = "Identifier: ", version = "  Version: ", registered = "  is registered: ";
        var ours = false;
        string? found = null;
        bool? isRegistered = null;
        foreach (var raw in output.Split('\n'))
        {
            var line = raw.TrimEnd('\r');
            if (line.StartsWith(id, StringComparison.Ordinal))
            {
                if (ours)
                    break;
                ours = line[id.Length..].Trim() == Identifier;
                continue;
            }
            if (!ours)
                continue;
            if (line.StartsWith(version, StringComparison.Ordinal))
                found = line[version.Length..].Trim();
            else if (line.StartsWith(registered, StringComparison.Ordinal))
                isRegistered = line[registered.Length..].Trim() == "yes";
        }
        return ours ? new InstalledExtension(found is { Length: > 0 } ? found : null, isRegistered == true) : null;
    }

    /// <summary>
    /// Healdan's extension as LibreOffice's user registry records it, read from its files, so nothing runs beside
    /// LibreOffice (on macOS, unopkg failed to install and misbehaved beside a running LibreOffice). The bundle
    /// backend's <c>backenddb.xml</c> lists each extension LibreOffice registered, by its unpacked folder in the cache,
    /// marked <c>revoked="true"</c> while it's turned off; a failed unopkg add, and a removed extension, aren't listed.
    /// The version is in that folder's <c>description.xml</c>. Null when Healdan's isn't listed, or the files can't be
    /// read. LibreOffice 26.8.1 on the owner's Mac writes them so; the layout dates from LibreOffice 3.3.
    /// </summary>
    /// <param name="userDir">LibreOffice's user profile, such as <c>~/Library/Application Support/LibreOffice/4/user</c>.</param>
    public static InstalledExtension? FromRegistry(string userDir)
    {
        var cache = Path.GetFullPath(Path.Combine(userDir, "uno_packages", "cache"));
        var db = Path.Combine(cache, "registry", "com.sun.star.comp.deployment.bundle.PackageRegistryBackend", "backenddb.xml");
        XDocument registry;
        try
        {
            if (!File.Exists(db))
                return null;
            registry = XDocument.Load(db);
        }
        catch (Exception e) when (e is XmlException or IOException or UnauthorizedAccessException)
        {
            return null;
        }
        InstalledExtension? found = null;
        foreach (var entry in registry.Root?.Elements(Registry + "extension") ?? [])
        {
            var url = entry.Attribute("url")?.Value;
            if (url is null || !url.StartsWith(CacheUrl, StringComparison.Ordinal))
                continue;
            var folder = Path.GetFullPath(Path.Combine(cache, Uri.UnescapeDataString(url[CacheUrl.Length..])));
            if (!folder.StartsWith(cache + Path.DirectorySeparatorChar, StringComparison.Ordinal))
                continue; // only the cache's own folders
            (string? Identifier, string? Version) description;
            try
            {
                description = Describe(XDocument.Load(Path.Combine(folder, "description.xml")));
            }
            catch (Exception e) when (e is XmlException or IOException or UnauthorizedAccessException)
            {
                continue;
            }
            if (description.Identifier != Identifier)
                continue;
            found = new InstalledExtension(description.Version is { Length: > 0 } v ? v : null, entry.Attribute("revoked")?.Value != "true");
            if (found.Registered)
                return found;
        }
        return found;
    }

    /// <summary>The version in an <c>.oxt</c>'s <c>description.xml</c>; refused unless it's Healdan's extension and has one.</summary>
    public static string OfOxt(string path)
    {
        using var oxt = ZipFile.OpenRead(path);
        var entry = oxt.GetEntry("description.xml") ?? throw new InvalidDataException($"{Path.GetFileName(path)} has no description.xml.");
        (string? Identifier, string? Version) description;
        try
        {
            using var stream = entry.Open();
            description = Describe(XDocument.Load(stream));
        }
        catch (XmlException e)
        {
            throw new InvalidDataException($"{Path.GetFileName(path)}'s description.xml can't be read: {e.Message}", e);
        }
        if (description.Identifier != Identifier)
            throw new InvalidDataException($"{Path.GetFileName(path)} isn't Healdan's extension ({description.Identifier ?? "no identifier"}).");
        return description.Version is { Length: > 0 } version ? version : throw new InvalidDataException($"{Path.GetFileName(path)} has no version.");
    }

    private static (string? Identifier, string? Version) Describe(XDocument description) =>
        (description.Root?.Element(Description + "identifier")?.Attribute("value")?.Value,
            description.Root?.Element(Description + "version")?.Attribute("value")?.Value);
}
