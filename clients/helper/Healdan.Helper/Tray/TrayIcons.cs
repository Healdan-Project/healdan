using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Healdan.Helper.Windows;

namespace Healdan.Helper.Tray;

/// <summary>
/// The menu bar icon (polish spec §7.1, §7.2): the mark in one colour, 22 points at 2x, with or without the attention dot
/// at its top right, which a ring cuts free of the shield.
/// </summary>
internal static class TrayIcons
{
    private const int Pixels = 44;
    private const double Points = 22;
    private const double Square = 260;

    /// <summary>
    /// The dot's centre and radii in the mark's square: at the shield's top right corner, about 7 points across with a ring
    /// about 1.1 points wide, over the shield's shoulder as a badge is. macOS draws the icon at about 17 points, where the
    /// first dot, about 5 points across and clear of the back page, was too small to notice (Task 14); this one covers the
    /// back page's corner, too thin to show at that size (checked by rendering at 34 and 44 pixels and enlarged).
    /// </summary>
    private static readonly Point Dot = new(216, 44);
    private const double DotRadius = 44;
    private const double RingRadius = 57;

    /// <param name="color">Black on macOS, which tints it as a template image; white on Linux, for GNOME's dark top bar.</param>
    public static WindowIcon Draw(bool dot, Color color)
    {
        var mark = new HealdanMark().Silhouette();
        if (dot)
            mark = new CombinedGeometry(GeometryCombineMode.Union,
                new CombinedGeometry(GeometryCombineMode.Exclude, mark, Circle(RingRadius)), Circle(DotRadius));
        using var bitmap = new RenderTargetBitmap(new PixelSize(Pixels, Pixels), new Vector(96 * Pixels / Points, 96 * Pixels / Points));
        using (var context = bitmap.CreateDrawingContext())
        using (context.PushTransform(Matrix.CreateScale(Points / Square, Points / Square)))
            context.DrawGeometry(new SolidColorBrush(color), null, mark);
        var png = new MemoryStream();
        bitmap.Save(png, PngBitmapEncoderOptions.Default);
        png.Position = 0;
        return new WindowIcon(png);
    }

    private static EllipseGeometry Circle(double radius) => new(new Rect(Dot.X - radius, Dot.Y - radius, radius * 2, radius * 2));
}
