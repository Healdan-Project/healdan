using Avalonia.Media;
using Avalonia.Media.TextFormatting;
using Healdan.Helper.Platform;

namespace Healdan.Helper.Tests.Platform;

/// <summary>
/// San Francisco's text is laid out at the widths CoreText gives it. Without <see cref="SystemFont"/>'s shaper, Avalonia
/// measures it at the font's Display size, about 15% narrower than Skia draws it, and 25% for SemiBold. A first run in
/// each weight is among them, as the shaper sizes a typeface's font only once it has made it.
/// </summary>
public sealed class SystemFontTests
{
    /// <summary>Every size the helper's styles and windows use.</summary>
    private static readonly double[] Sizes = [10, 11, 12, 13, 15];

    private static readonly (FontWeight Weight, double Trait)[] Weights = [(FontWeight.Normal, 0), (FontWeight.SemiBold, 0.3)];

    private static readonly string[] Texts = ["Install…", "Save to Local Computer…", "Responsible Partner", "The quick brown fox jumps over the lazy dog"];

    [MacFact]
    public void LaysSanFranciscoOutAtCoreTextsWidths()
    {
        var wrong = Headless.Run(() => (
            from size in Sizes
            from weight in Weights
            from text in Texts
            let width = new TextLayout(text, new Typeface(SystemFont.Family, weight: weight.Weight), size, Brushes.Black).WidthIncludingTrailingWhitespace
            let coreText = CoreText.LineWidth(SystemFont.Family, weight.Trait, size, text)
            where Math.Abs(width - coreText) > coreText * 0.002
            select $"'{text}' at {size} pt, {weight.Weight}: {width:0.00} pt, CoreText's {coreText:0.00} pt").ToList());

        Assert.Empty(wrong);
    }
}
