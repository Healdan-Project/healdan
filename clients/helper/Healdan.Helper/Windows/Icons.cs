using Avalonia;
using Avalonia.Controls;
using Avalonia.Data.Converters;
using Avalonia.Media;

namespace Healdan.Helper.Windows;

/// <summary>The icons in <c>HelperIcons.axaml</c> by their Tabler names (polish spec §4.4), for code and bindings.</summary>
public static class Icons
{
    /// <summary>A binding's converter: a Tabler name, such as an entry's <c>Icon</c>, to its drawing.</summary>
    public static readonly IValueConverter ByName = new FuncValueConverter<string?, Geometry?>(name => name is null ? null : Get(name));

    public static Geometry Get(string name) =>
        Application.Current!.TryFindResource("Icon." + name, out var found) && found is Geometry geometry
            ? geometry
            : throw new ArgumentException($"HelperIcons.axaml has no icon named {name}", nameof(name));

    /// <summary>An icon for a row built in code, <paramref name="size"/> points square.</summary>
    public static IconView View(string name, double size = 16) => new() { Data = Get(name), Width = size, Height = size };
}
