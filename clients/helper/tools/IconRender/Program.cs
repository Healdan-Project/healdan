using Avalonia;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Markup.Xaml;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using Healdan.Helper.Windows;

// The LibreOffice extension's images (polish spec §8.2, §8.4), rendered from the helper's Tabler icons and Healdan's
// mark into clients/libreoffice/healdan/icons/. Run by hand when an icon changes, and commit what it writes.

AppBuilder.Configure<Application>().UseSkia().UseHeadless(new AvaloniaHeadlessPlatformOptions { UseHeadlessDrawing = false }).SetupWithoutStarting();

var root = FindRoot();
var output = Path.Combine(root, "clients", "libreoffice", "healdan", "icons");
Directory.CreateDirectory(output);
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
            Save(Icon(drawing, size, grey, shield ? (mark.Shield(), shieldBlue, Colors.White) : null), $"{file}-{size}.png");
            Save(Icon(drawing, size, Colors.White, shield ? (mark.Shield(), Colors.White, Colors.Black) : null), $"{file}-{size}-hc.png");
        }
    }
    Save(Mark(42, null), "healdan-42.png");
    Save(Mark(42, Colors.White), "healdan-42-hc.png");
});
Console.WriteLine($"Wrote the extension's images to {output}");

void Save(RenderTargetBitmap bitmap, string name)
{
    bitmap.Save(Path.Combine(output, name), PngBitmapEncoderOptions.Default);
    bitmap.Dispose();
}

// One toolbar image: the 24-unit icon stroked 1.5 px at 16 and 2 px at 26, and the shield at its lower right, 7 and 11 px
// across, inside a 1 px ring that keeps it clear of the strokes.
static RenderTargetBitmap Icon(Geometry drawing, int size, Color stroke, (Geometry Shape, Color Fill, Color Ring)? shield)
{
    var bitmap = new RenderTargetBitmap(new PixelSize(size, size), new Vector(96, 96));
    using var context = bitmap.CreateDrawingContext();
    var scale = size / 24.0;
    var width = size == 16 ? 1.5 : 2;
    using (context.PushTransform(Matrix.CreateScale(scale, scale)))
        context.DrawGeometry(null, new Pen(new SolidColorBrush(stroke), width / scale, lineCap: PenLineCap.Round, lineJoin: PenLineJoin.Round), drawing);
    if (shield is { } s)
    {
        var across = size == 16 ? 7 : 11;
        var badge = Matrix.CreateScale(across / 260.0, across / 260.0) * Matrix.CreateTranslation(size - across, size - across);
        using (context.PushTransform(badge))
            context.DrawGeometry(new SolidColorBrush(s.Fill), new Pen(new SolidColorBrush(s.Ring), 260.0 / across), s.Shape);
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
