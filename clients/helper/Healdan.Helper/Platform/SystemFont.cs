using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using Avalonia;
using Avalonia.Harfbuzz;
using Avalonia.Media;
using Avalonia.Media.TextFormatting;
using Avalonia.Platform;
using SkiaSharp;

namespace Healdan.Helper.Platform;

/// <summary>
/// San Francisco, the Mac's system font, laid out as Skia draws it. Avalonia 12.1.4 shapes text with HarfBuzz at the
/// font file's default instance, which for San Francisco is optical size 28 (Display) and Regular. Skia draws the face
/// CoreText matched, at optical size 17 (Text). So the text was measured about 15% narrower than it was drawn (SemiBold
/// about 25%), and a trailing … ran past its box. The shaper here gives HarfBuzz the drawn instance's coordinates and
/// each run's point size, from which HarfBuzz applies the font's tracking (<c>trak</c>) as CoreText does. Up to 17 pt,
/// which covers every size the helper uses, widths then match CoreText's to within a twentieth of a point.
/// </summary>
internal static class SystemFont
{
    /// <summary>CoreText's name for the system font, which Skia resolves to San Francisco.</summary>
    public const string Family = ".AppleSystemUIFont";

    private const string HarfBuzz = "libHarfBuzzSharp";

    private const uint OpticalSize = 'o' << 24 | 'p' << 16 | 's' << 8 | 'z';

    /// <summary>
    /// Shapes text through <see cref="Shaper"/> on macOS; elsewhere the builder is unchanged. Avalonia leaves its
    /// service locator out of its reference assemblies, but binds an app's options before it sets text shaping up. So
    /// the shaper goes in as an option, and the text shaping step is left nothing to do.
    /// </summary>
    public static AppBuilder UseSystemFontShaper(this AppBuilder builder) =>
        OperatingSystem.IsMacOS()
            ? builder.With(Shaper.Create(new HarfBuzzTextShaper())).UseTextShapingSubsystem(static () => { }, "HarfBuzz")
            : builder;

    /// <summary>
    /// Avalonia's HarfBuzz shaper, with San Francisco's fonts at the drawn instance and sized for each run. Avalonia
    /// marks <see cref="ITextShaperImpl"/> as not for apps to implement (its reference assemblies add a hidden member), so
    /// this is a <see cref="DispatchProxy"/>, made at run time, that hands each call on to HarfBuzz's shaper.
    /// </summary>
    private class Shaper : DispatchProxy
    {
        private readonly ConditionalWeakTable<ITextShaperTypeface, SizedFont> _fonts = new();
        private HarfBuzzTextShaper _harfBuzz = null!;

        public static ITextShaperImpl Create(HarfBuzzTextShaper harfBuzz)
        {
            var shaper = Create<ITextShaperImpl, Shaper>();
            ((Shaper)(object)shaper)._harfBuzz = harfBuzz;
            return shaper;
        }

        protected override object? Invoke(MethodInfo? method, object?[]? args)
        {
            switch (method?.Name, args)
            {
                case (nameof(HarfBuzzTextShaper.CreateTypeface), [GlyphTypeface glyphTypeface]):
                    var typeface = _harfBuzz.CreateTypeface(glyphTypeface);
                    if (glyphTypeface.PlatformTypeface.FamilyName == Family && SizedFont.For(typeface, glyphTypeface.PlatformTypeface) is { } font)
                        _fonts.AddOrUpdate(typeface, font);
                    return typeface;
                case (nameof(HarfBuzzTextShaper.ShapeText), [ReadOnlyMemory<char> text, TextShaperOptions options]):
                    // Reading TextShaperTypeface makes a typeface's HarfBuzz font, through CreateTypeface, on its first run,
                    // so that run is sized too.
                    if (!_fonts.TryGetValue(options.GlyphTypeface.TextShaperTypeface, out var sized))
                        return _harfBuzz.ShapeText(text, options);
                    lock (sized)
                    {
                        sized.SetPointSize(options.FontRenderingEmSize);
                        return _harfBuzz.ShapeText(text, options);
                    }
                default:
                    return method?.Invoke(_harfBuzz, args);
            }
        }
    }

    /// <summary>One of HarfBuzz's San Francisco fonts, at the drawn instance. Each run sets its point size first.</summary>
    private sealed unsafe class SizedFont(HarfBuzzSharp.Font font)
    {
        private float _pointSize;

        /// <summary>
        /// The typeface's HarfBuzz font, set to the instance Skia draws, or null when Avalonia doesn't keep the font or
        /// Skia's face where 12.1.4 does: <c>HarfBuzzTypeface.HBFont</c> and <c>SkiaTypeface.SKTypeface</c>, public
        /// properties of internal classes.
        /// </summary>
        public static SizedFont? For(ITextShaperTypeface typeface, IPlatformTypeface platform)
        {
            if (typeface.GetType().GetProperty("HBFont")?.GetValue(typeface) is not HarfBuzzSharp.Font font
                || platform.GetType().GetProperty("SKTypeface")?.GetValue(platform) is not SKTypeface face)
                return null;
            SetDrawnInstance(font.Handle, face.PostScriptName);
            return new SizedFont(font);
        }

        /// <summary>Sets the size HarfBuzz looks the font's tracking up by. Avalonia's units are points on macOS.</summary>
        public void SetPointSize(double size)
        {
            if ((float)size == _pointSize)
                return;
            _pointSize = (float)size;
            hb_font_set_ptem(font.Handle, _pointSize);
        }

        /// <summary>
        /// Skia draws the named instance CoreText matched, which has the face's PostScript name. It draws it at the
        /// optical size CoreText gave the face at 12 pt, the size Skia makes it at, and keeps that at every size: 12
        /// within the axis's range, so 17 for San Francisco. Axes without a matched instance keep their defaults.
        /// </summary>
        private static void SetDrawnInstance(IntPtr font, string postScriptName)
        {
            var face = hb_font_get_face(font);
            var axisCount = hb_ot_var_get_axis_count(face);
            var axes = new AxisInfo[axisCount];
            var coords = new float[axisCount];
            fixed (AxisInfo* axesPtr = axes)
                hb_ot_var_get_axis_infos(face, 0, &axisCount, axesPtr);
            for (var i = 0; i < axisCount; i++)
                coords[i] = axes[i].Default;
            fixed (float* coordsPtr = coords)
            {
                for (uint i = 0, count = hb_ot_var_get_named_instance_count(face); i < count; i++)
                {
                    if (Name(face, hb_ot_var_named_instance_get_postscript_name_id(face, i)) != postScriptName)
                        continue;
                    var length = axisCount;
                    hb_ot_var_named_instance_get_design_coords(face, i, &length, coordsPtr);
                    break;
                }
                for (var i = 0; i < axisCount; i++)
                    if (axes[i].Tag == OpticalSize)
                        coords[i] = Math.Clamp(12f, axes[i].Min, axes[i].Max);
                hb_font_set_var_coords_design(font, coordsPtr, axisCount);
            }
        }

        /// <summary>An entry in the font's <c>name</c> table, in English, or null for none.</summary>
        private static string? Name(IntPtr face, uint nameId)
        {
            uint size = 0;
            var length = hb_ot_name_get_utf16(face, nameId, IntPtr.Zero, &size, null);
            if (length == 0)
                return null;
            var text = new char[length + 1];
            size = length + 1;
            fixed (char* textPtr = text)
                hb_ot_name_get_utf16(face, nameId, IntPtr.Zero, &size, textPtr);
            return new string(text, 0, (int)size);
        }
    }

    /// <summary><c>hb_ot_var_axis_info_t</c>.</summary>
    [StructLayout(LayoutKind.Sequential)]
    private struct AxisInfo
    {
        public uint Index;
        public uint Tag;
        public uint NameId;
        public uint Flags;
        public float Min;
        public float Default;
        public float Max;
        public uint Reserved;
    }

    // HarfBuzzSharp's own native library, which exports all of HarfBuzz's C API; its managed API has none of these.

    [DllImport(HarfBuzz)]
    private static extern IntPtr hb_font_get_face(IntPtr font);

    [DllImport(HarfBuzz)]
    private static extern void hb_font_set_ptem(IntPtr font, float ptem);

    [DllImport(HarfBuzz)]
    private static extern unsafe void hb_font_set_var_coords_design(IntPtr font, float* coords, uint length);

    [DllImport(HarfBuzz)]
    private static extern uint hb_ot_var_get_axis_count(IntPtr face);

    [DllImport(HarfBuzz)]
    private static extern unsafe uint hb_ot_var_get_axis_infos(IntPtr face, uint start, uint* count, AxisInfo* axes);

    [DllImport(HarfBuzz)]
    private static extern uint hb_ot_var_get_named_instance_count(IntPtr face);

    [DllImport(HarfBuzz)]
    private static extern uint hb_ot_var_named_instance_get_postscript_name_id(IntPtr face, uint instance);

    [DllImport(HarfBuzz)]
    private static extern unsafe uint hb_ot_var_named_instance_get_design_coords(IntPtr face, uint instance, uint* length, float* coords);

    [DllImport(HarfBuzz)]
    private static extern unsafe uint hb_ot_name_get_utf16(IntPtr face, uint nameId, IntPtr language, uint* size, char* text);
}
