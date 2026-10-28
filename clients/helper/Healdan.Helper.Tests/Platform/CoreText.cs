using System.Runtime.InteropServices;

namespace Healdan.Helper.Tests.Platform;

/// <summary>CoreText's own layout of the system font, which an AppKit label's text has: the widths the helper's should match.</summary>
internal static unsafe class CoreText
{
    private const string CoreFoundationPath = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";
    private const string CoreTextPath = "/System/Library/Frameworks/CoreText.framework/CoreText";

    /// <summary><c>kCFNumberDoubleType</c>.</summary>
    private const nint NumberDouble = 13;

    private static readonly IntPtr CoreFoundationLibrary = NativeLibrary.Load(CoreFoundationPath);
    private static readonly IntPtr CoreTextLibrary = NativeLibrary.Load(CoreTextPath);

    /// <summary>
    /// The width of <paramref name="text"/> on one line, in the font Skia makes for the system font: CoreText's match
    /// for <paramref name="family"/> at a weight trait (0 is Regular, 0.3 Semibold, 0.4 Bold), as an AppKit label sets it.
    /// </summary>
    public static double LineWidth(string family, double weight, double size, string text)
    {
        var made = new List<IntPtr>();
        IntPtr Made(IntPtr reference)
        {
            made.Add(reference);
            return reference;
        }
        try
        {
            var traits = Made(Dictionary([Constant("kCTFontWeightTrait")], [Made(CFNumberCreate(IntPtr.Zero, NumberDouble, &weight))]));
            var attributes = Made(Dictionary([Constant("kCTFontFamilyNameAttribute"), Constant("kCTFontTraitsAttribute")], [Made(String(family)), traits]));
            var font = Made(CTFontCreateWithFontDescriptor(Made(CTFontDescriptorCreateWithAttributes(attributes)), size, IntPtr.Zero));
            var line = Made(CTLineCreateWithAttributedString(Made(CFAttributedStringCreate(IntPtr.Zero, Made(String(text)),
                Made(Dictionary([Constant("kCTFontAttributeName")], [font]))))));
            return CTLineGetTypographicBounds(line, null, null, null);
        }
        finally
        {
            foreach (var reference in made)
                CFRelease(reference);
        }
    }

    /// <summary>One of CoreText's <c>CFStringRef</c> constants, such as <c>kCTFontAttributeName</c>.</summary>
    private static IntPtr Constant(string name) => Marshal.ReadIntPtr(NativeLibrary.GetExport(CoreTextLibrary, name));

    private static IntPtr String(string text)
    {
        fixed (char* chars = text)
            return CFStringCreateWithCharacters(IntPtr.Zero, chars, text.Length);
    }

    private static IntPtr Dictionary(IntPtr[] keys, IntPtr[] values)
    {
        fixed (IntPtr* keysPtr = keys)
        fixed (IntPtr* valuesPtr = values)
            return CFDictionaryCreate(IntPtr.Zero, keysPtr, valuesPtr, keys.Length,
                NativeLibrary.GetExport(CoreFoundationLibrary, "kCFTypeDictionaryKeyCallBacks"),
                NativeLibrary.GetExport(CoreFoundationLibrary, "kCFTypeDictionaryValueCallBacks"));
    }

    [DllImport(CoreFoundationPath)]
    private static extern IntPtr CFStringCreateWithCharacters(IntPtr allocator, char* chars, nint length);

    [DllImport(CoreFoundationPath)]
    private static extern IntPtr CFNumberCreate(IntPtr allocator, nint type, void* value);

    [DllImport(CoreFoundationPath)]
    private static extern IntPtr CFDictionaryCreate(IntPtr allocator, IntPtr* keys, IntPtr* values, nint count, IntPtr keyCallBacks, IntPtr valueCallBacks);

    [DllImport(CoreFoundationPath)]
    private static extern IntPtr CFAttributedStringCreate(IntPtr allocator, IntPtr text, IntPtr attributes);

    [DllImport(CoreFoundationPath)]
    private static extern void CFRelease(IntPtr reference);

    [DllImport(CoreTextPath)]
    private static extern IntPtr CTFontDescriptorCreateWithAttributes(IntPtr attributes);

    [DllImport(CoreTextPath)]
    private static extern IntPtr CTFontCreateWithFontDescriptor(IntPtr descriptor, double size, IntPtr matrix);

    [DllImport(CoreTextPath)]
    private static extern IntPtr CTLineCreateWithAttributedString(IntPtr text);

    [DllImport(CoreTextPath)]
    private static extern double CTLineGetTypographicBounds(IntPtr line, double* ascent, double* descent, double* leading);
}
