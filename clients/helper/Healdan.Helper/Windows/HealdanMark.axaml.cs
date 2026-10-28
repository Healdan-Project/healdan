using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Media;
using Path = Avalonia.Controls.Shapes.Path;

namespace Healdan.Helper.Windows;

/// <summary>
/// Healdan's mark, as the docs site's favicon draws it, for About (#89), Sign In and the alerts; and, from the same
/// shapes, in one colour for the menu bar and badges (polish spec §7.1, §8.2), so MarkTests covers those too (call 6).
/// </summary>
public partial class HealdanMark : UserControl
{
    public HealdanMark() => InitializeComponent();

    /// <summary>
    /// The mark in one colour, its pages cut out: the coloured shapes united, each white page taken away, and a clipped
    /// group's shapes put back inside its clip, in the XAML's order. In the 260-point square.
    /// </summary>
    public Geometry Silhouette()
    {
        Geometry? mark = null;
        foreach (var child in Shapes.Children)
        {
            switch (child)
            {
                case Path path:
                    mark = Union(mark, path.Data!);
                    break;
                case Polygon page:
                    mark = new CombinedGeometry(GeometryCombineMode.Exclude, mark!, new PolylineGeometry(page.Points, true));
                    break;
                case Canvas { Clip: { } clip } group:
                    foreach (var part in group.Children.OfType<Path>())
                        mark = Union(mark, new CombinedGeometry(GeometryCombineMode.Intersect, part.Data!, clip));
                    break;
            }
        }
        return Placed(mark!);
    }

    /// <summary>The shield's outline alone, its halves united, for badges. In the 260-point square.</summary>
    public Geometry Shield() => Placed(Shapes.Children.OfType<Path>().Select(p => p.Data!).Aggregate((a, b) => Union(a, b)));

    private static Geometry Union(Geometry? a, Geometry b) => a is null ? b : new CombinedGeometry(GeometryCombineMode.Union, a, b);

    /// <summary>The shapes moved to where the XAML sets them in the mark's 260-point square.</summary>
    private Geometry Placed(Geometry shape) =>
        new GeometryGroup { Children = { shape }, Transform = new TranslateTransform(Canvas.GetLeft(Shapes), Canvas.GetTop(Shapes)) };
}
