using System.IO.Compression;
using System.Xml;
using System.Xml.Linq;

namespace Healdan.Helper.Core.Platform;

/// <summary>
/// The LibreOffice extension's versions (spec §5.12): the one the helper carries, read from its <c>.oxt</c>'s
/// <c>description.xml</c> so the helper never needs a second copy of it, and the one installed, read from
/// <c>unopkg list</c>. They compare as LibreOffice compares extension versions, part by part as numbers.
/// </summary>
public static class ExtensionVersion
{
    /// <summary>The extension's identifier, in its <c>description.xml</c>.</summary>
    public const string Identifier = "org.healdan.libreoffice";

    private static readonly XNamespace Description = "http://openoffice.org/extensions/description/2006";

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

    /// <summary>The version in an <c>.oxt</c>'s <c>description.xml</c>; refused unless it's Healdan's extension and has one.</summary>
    public static string OfOxt(string path)
    {
        using var oxt = ZipFile.OpenRead(path);
        var entry = oxt.GetEntry("description.xml") ?? throw new InvalidDataException($"{Path.GetFileName(path)} has no description.xml.");
        XDocument description;
        try
        {
            using var stream = entry.Open();
            description = XDocument.Load(stream);
        }
        catch (XmlException e)
        {
            throw new InvalidDataException($"{Path.GetFileName(path)}'s description.xml can't be read: {e.Message}", e);
        }
        var root = description.Root;
        var identifier = root?.Element(Description + "identifier")?.Attribute("value")?.Value;
        if (identifier != Identifier)
            throw new InvalidDataException($"{Path.GetFileName(path)} isn't Healdan's extension ({identifier ?? "no identifier"}).");
        var version = root!.Element(Description + "version")?.Attribute("value")?.Value;
        return version is { Length: > 0 } ? version : throw new InvalidDataException($"{Path.GetFileName(path)} has no version.");
    }
}
