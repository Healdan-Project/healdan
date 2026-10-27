using System.Xml.Linq;

namespace Healdan.Helper.Core.Tests.App;

/// <summary>
/// About's mark (#89) is Healdan's, drawn in XAML because Avalonia doesn't read SVG: the same shapes as the docs
/// site's <c>favicon.svg</c>, with the same fills and clips, in the same order. A changed favicon fails here until the
/// mark is redrawn, as the macOS icon is (<c>make-icon.swift</c>).
/// </summary>
public sealed class MarkTests
{
    private static readonly XNamespace Svg = "http://www.w3.org/2000/svg";
    private static readonly XNamespace Xaml = "https://github.com/avaloniaui";

    /// <summary>A shape: <c>path</c> or <c>polygon</c>, its path data or points, its fill, and the clip's points, if any.</summary>
    private sealed record Shape(string Kind, string Data, string Fill, string? Clip);

    [Fact]
    public void DrawsTheFaviconsShapes()
    {
        var favicon = FaviconShapes();
        Assert.NotEmpty(favicon);
        Assert.Equal(favicon, MarkShapes());
    }

    private static List<Shape> FaviconShapes()
    {
        var svg = XDocument.Load(AppFiles.Repository("site", "static", "favicon.svg"));
        var clips = svg.Descendants(Svg + "clipPath")
            .ToDictionary(c => (string)c.Attribute("id")!, c => (string)c.Element(Svg + "polygon")!.Attribute("points")!);
        return svg.Descendants()
            .Where(e => e.Name == Svg + "path" || e.Name == Svg + "polygon")
            .Where(e => !e.Ancestors(Svg + "clipPath").Any())
            .Select(e =>
            {
                var clip = e.Ancestors(Svg + "g").Select(g => (string?)g.Attribute("clip-path")).FirstOrDefault(c => c is not null);
                return new Shape(e.Name.LocalName, (string)(e.Attribute("d") ?? e.Attribute("points"))!,
                    ((string)e.Attribute("fill")!).ToLowerInvariant(), clip is null ? null : clips[clip["url(#".Length..^1]]);
            })
            .ToList();
    }

    private static List<Shape> MarkShapes()
    {
        var mark = XDocument.Load(AppFiles.Helper("Windows", "HealdanMark.axaml"));
        return mark.Descendants()
            .Where(e => e.Name == Xaml + "Path" || e.Name == Xaml + "Polygon")
            .Select(e =>
            {
                var clip = e.Ancestors(Xaml + "Canvas")
                    .Select(c => c.Element(Xaml + "Canvas.Clip")?.Element(Xaml + "PolylineGeometry")?.Attribute("Points"))
                    .FirstOrDefault(points => points is not null);
                return new Shape(e.Name.LocalName.ToLowerInvariant(), (string)(e.Attribute("Data") ?? e.Attribute("Points"))!,
                    ((string)e.Attribute("Fill")!).ToLowerInvariant(), (string?)clip);
            })
            .ToList();
    }
}
