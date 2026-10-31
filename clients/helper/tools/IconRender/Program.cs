using Avalonia;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Markup.Xaml;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using Healdan.Helper.Windows;

// The LibreOffice extension's images (polish spec §8.2, §8.4), rendered from the helper's Tabler icons and Healdan's
// mark into clients/libreoffice/healdan/icons/, and the Office add-in's (M3.5 spec §6.1), the same icons at the ribbon's
// sizes, into clients/office/web/assets/. Run by hand when an icon changes, and commit what it writes.

AppBuilder.Configure<Application>().UseSkia().UseHeadless(new AvaloniaHeadlessPlatformOptions { UseHeadlessDrawing = false }).SetupWithoutStarting();

var root = FindRoot();
var output = Path.Combine(root, "clients", "libreoffice", "healdan", "icons");
var officeOutput = Path.Combine(root, "clients", "office", "web", "assets");
Directory.CreateDirectory(output);
Directory.CreateDirectory(officeOutput);
var icons = (ResourceDictionary)AvaloniaXamlLoader.Load(new Uri("avares://HealdanHelper/Windows/HelperIcons.axaml"));

(string File, string Icon, bool Shield)[] commands =
[
    ("save-to-healdan", "device-floppy", true),
    ("open-from-healdan", "folder-open", true),
    ("save-as-new-version", "versions", false),
    ("save-as-new-document", "file-plus", false),
    ("properties", "list-details", false),
    ("insert-doc-id", "hash", false)
];

var grey = Color.Parse("#7C7C80");
var shieldBlue = Color.Parse("#1A74C4");
Dispatcher.UIThread.Invoke(() =>
{
    var mark = new HealdanMark();
    foreach (var (file, icon, shield) in commands)
    {
        var drawing = (Geometry)icons["Icon." + icon]!;
        foreach (var size in new[] { 16, 26 })
        {
            Save(Icon(drawing, size, grey, shield ? (mark.Shield(), shieldBlue, Colors.White) : null), output, $"{file}-{size}.png");
            Save(Icon(drawing, size, Colors.White, shield ? (mark.Shield(), Colors.White, Colors.Black) : null), output, $"{file}-{size}-hc.png");
        }
        // The Office ribbon's sizes: the group and the buttons at 16, 32 and 80 px, in the extension's colours
        foreach (var size in new[] { 16, 32, 80 })
            Save(Icon(drawing, size, grey, shield ? (mark.Shield(), shieldBlue, Colors.White) : null), officeOutput, $"{file}-{size}.png");
    }
    Save(Mark(42, null), output, "healdan-42.png");
    Save(Mark(42, Colors.White), output, "healdan-42-hc.png");
    // The manifest's icon is 32 px and its high-resolution icon 64; the ribbon's group takes 16, 32 and 80
    foreach (var size in new[] { 16, 32, 64, 80 })
        Save(Mark(size, null), officeOutput, $"healdan-{size}.png");
});
Console.WriteLine($"Wrote the extension's images to {output}");
Console.WriteLine($"Wrote the Office add-in's images to {officeOutput}");

void Save(RenderTargetBitmap bitmap, string folder, string name)
{
    bitmap.Save(Path.Combine(folder, name), PngBitmapEncoderOptions.Default);
    bitmap.Dispose();
}

// One toolbar image: the 24-unit icon stroked 1.5 px at 16 and 2 px at 26 (about 1/13 of the size above that), and the
// shield at its lower right, 7 and 11 px across (about 3/7 of the size above that), inside a ring 1 px wide that keeps it
// clear of the strokes (1 px per 26 above that).
static RenderTargetBitmap Icon(Geometry drawing, int size, Color stroke, (Geometry Shape, Color Fill, Color Ring)? shield)
{
    var bitmap = new RenderTargetBitmap(new PixelSize(size, size), new Vector(96, 96));
    using var context = bitmap.CreateDrawingContext();
    var scale = size / 24.0;
    var width = size <= 16 ? 1.5 : size <= 26 ? 2 : Math.Round(size * 0.078 * 2) / 2;
    using (context.PushTransform(Matrix.CreateScale(scale, scale)))
        context.DrawGeometry(null, new Pen(new SolidColorBrush(stroke), width / scale, lineCap: PenLineCap.Round, lineJoin: PenLineJoin.Round), drawing);
    if (shield is { } s)
    {
        var across = size <= 16 ? 7 : size <= 26 ? 11 : (int)Math.Round(size * 0.43);
        var ring = size <= 26 ? 1 : Math.Max(1, Math.Round(size / 26.0));
        var badge = Matrix.CreateScale(across / 260.0, across / 260.0) * Matrix.CreateTranslation(size - across, size - across);
        using (context.PushTransform(badge))
            context.DrawGeometry(new SolidColorBrush(s.Fill), new Pen(new SolidColorBrush(s.Ring), ring * 260.0 / across), s.Shape);
    }
    return bitmap;
}

// The Extension Manager's icon: the mark in its colours, or in one colour for high contrast. RenderTargetBitmap.Render comes
// out blank for a control with no visual root, and a Window's captured frame has no alpha, so the colours are drawn by walking
// the mark's shapes with their fills, as HealdanMark.Silhouette() walks them for one colour.
static RenderTargetBitmap Mark(int size, Color? one)
{
    var bitmap = new RenderTargetBitmap(new PixelSize(size, size), new Vector(96, 96));
    using var context = bitmap.CreateDrawingContext();
    var mark = new HealdanMark();
    using (context.PushTransform(Matrix.CreateScale(size / 260.0, size / 260.0)))
    {
        if (one is { } color)
            context.DrawGeometry(new SolidColorBrush(color), null, mark.Silhouette());
        else
            DrawShapes(context, (Control)((Viewbox)mark.Content!).Child!);
    }
    return bitmap;
}

// A canvas puts its children at its offset and inside its clip; a path draws its geometry and a polygon its points, in their fills.
static void DrawShapes(DrawingContext context, Control control)
{
    switch (control)
    {
        case Canvas canvas:
            var left = Canvas.GetLeft(canvas);
            var top = Canvas.GetTop(canvas);
            using (context.PushTransform(Matrix.CreateTranslation(double.IsNaN(left) ? 0 : left, double.IsNaN(top) ? 0 : top)))
            {
                if (canvas.Clip is { } clip)
                {
                    using (context.PushGeometryClip(clip))
                        DrawChildren(context, canvas);
                }
                else
                    DrawChildren(context, canvas);
            }
            break;
        case Avalonia.Controls.Shapes.Path path:
            context.DrawGeometry(path.Fill, null, path.Data!);
            break;
        case Avalonia.Controls.Shapes.Polygon polygon:
            context.DrawGeometry(polygon.Fill, null, new PolylineGeometry(polygon.Points, true));
            break;
    }
}

static void DrawChildren(DrawingContext context, Canvas canvas)
{
    foreach (var child in canvas.Children)
        DrawShapes(context, child);
}

static string FindRoot()
{
    var dir = new DirectoryInfo(AppContext.BaseDirectory);
    while (dir is not null && !File.Exists(Path.Combine(dir.FullName, "clients", "helper", "Healdan.Helper.sln")))
        dir = dir.Parent;
    return dir?.FullName ?? throw new InvalidOperationException("Run it from inside the repository.");
}
