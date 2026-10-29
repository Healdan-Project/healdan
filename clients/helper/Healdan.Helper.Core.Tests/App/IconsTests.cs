using System.Xml.Linq;

namespace Healdan.Helper.Core.Tests.App;

/// <summary>
/// The helper's icons (polish spec §4.4) are Tabler's: <c>HelperIcons.axaml</c> holds each SVG in
/// <c>clients/icons/tabler/</c> as one geometry, which <c>clients/icons/write-helper-icons.py</c> writes. An icon added
/// or changed fails here until the script has run.
/// </summary>
public sealed class IconsTests
{
    private static readonly XNamespace Svg = "http://www.w3.org/2000/svg";
    private static readonly XNamespace Xaml = "https://github.com/avaloniaui";
    private static readonly XNamespace X = "http://schemas.microsoft.com/winfx/2006/xaml";

    /// <summary>Tabler's invisible 24-unit box, which every icon starts with.</summary>
    private const string Box = "M0 0h24v24H0z";

    private static readonly string[] SpecNames =
    [
        "alert-triangle", "briefcase", "building", "chevron-left", "chevron-right", "chevron-up", "clock", "device-floppy",
        "eye", "file", "file-plus", "file-spreadsheet", "file-text", "file-type-pdf", "folder", "folder-open", "folder-plus",
        "hash", "history", "list-details", "lock", "photo", "power", "presentation", "puzzle", "search", "selector", "server",
        "star", "triangle-square-circle", "versions"
    ];

    [Fact]
    public void EachIconIsItsTablerDrawing()
    {
        var sources = Directory.GetFiles(AppFiles.Repository("clients", "icons", "tabler"), "*.svg")
            .ToDictionary(file => Path.GetFileNameWithoutExtension(file)!, Drawing);
        var icons = XDocument.Load(AppFiles.Helper("Windows", "HelperIcons.axaml")).Root!
            .Elements(Xaml + "StreamGeometry")
            .ToDictionary(e => ((string)e.Attribute(X + "Key")!)["Icon.".Length..], e => e.Value.Trim());

        Assert.NotEmpty(sources);
        Assert.Equal(sources.OrderBy(p => p.Key, StringComparer.Ordinal), icons.OrderBy(p => p.Key, StringComparer.Ordinal));
    }

    [Fact]
    public void TheSpecsIconsAreAllThere()
    {
        var names = Directory.GetFiles(AppFiles.Repository("clients", "icons", "tabler"), "*.svg").Select(Path.GetFileNameWithoutExtension);
        Assert.Equal(SpecNames, names.Order(StringComparer.Ordinal));
    }

    /// <summary>An SVG's drawing: its paths' data in order, without the box. Tabler's outline set draws only paths.</summary>
    private static string Drawing(string file)
    {
        var shapes = XDocument.Load(file).Root!.Elements().ToList();
        Assert.All(shapes, s => Assert.Equal(Svg + "path", s.Name));
        return string.Join(" ", shapes.Select(s => (string)s.Attribute("d")!).Where(d => d != Box));
    }
}
