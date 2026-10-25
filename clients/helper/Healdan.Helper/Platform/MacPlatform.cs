using System.Runtime.InteropServices;

namespace Healdan.Helper.Platform;

/// <summary>
/// The few AppKit calls the helper makes, through the Objective-C runtime, so the build needs no Xcode toolchain. A
/// window that opens takes the focus (<c>activateIgnoringOtherApps:</c>), and once the last one closes the focus goes
/// back to the app the user was in (<c>[NSApp hide:nil]</c>), as the trial did (spec §5.1). Main thread only, which is
/// Avalonia's UI thread on macOS; elsewhere these do nothing.
/// </summary>
internal static class MacPlatform
{
    private const string ObjC = "/usr/lib/libobjc.A.dylib";

    private static readonly Lazy<bool> Bundled = new(ReadInAppBundle);

    /// <summary>Brings the helper to the front as a window opens.</summary>
    public static void Activate()
    {
        if (OperatingSystem.IsMacOS())
            Send(SharedApplication(), Selector("activateIgnoringOtherApps:"), 1);
    }

    /// <summary>Hands the focus back to the app the user was in, once the helper's last window has closed.</summary>
    public static void GiveBackFocus()
    {
        if (OperatingSystem.IsMacOS())
            Send(SharedApplication(), Selector("hide:"), IntPtr.Zero);
    }

    /// <summary>
    /// The helper runs from an app bundle with an identifier, as the package installs it, rather than as a bare
    /// executable (<c>dotnet run</c>). Some frameworks, notifications among them, throw without one.
    /// </summary>
    public static bool InAppBundle => OperatingSystem.IsMacOS() && Bundled.Value;

    public static IntPtr Class(string name) => objc_getClass(name);

    public static IntPtr Selector(string name) => sel_registerName(name);

    /// <summary>An <c>NSString</c> the caller releases.</summary>
    public static IntPtr NewString(string text)
    {
        var utf8 = Marshal.StringToCoTaskMemUTF8(text);
        try
        {
            return Send(Send(Class("NSString"), Selector("alloc")), Selector("initWithUTF8String:"), utf8);
        }
        finally
        {
            Marshal.FreeCoTaskMem(utf8);
        }
    }

    public static void Release(IntPtr instance)
    {
        if (instance != IntPtr.Zero)
            Send(instance, Selector("release"));
    }

    private static IntPtr SharedApplication() => Send(Class("NSApplication"), Selector("sharedApplication"));

    private static bool ReadInAppBundle()
    {
        var pool = objc_autoreleasePoolPush();
        try
        {
            var bundle = Send(Class("NSBundle"), Selector("mainBundle"));
            if (bundle == IntPtr.Zero || Send(bundle, Selector("bundleIdentifier")) == IntPtr.Zero)
                return false;
            var path = Send(bundle, Selector("bundlePath"));
            return path != IntPtr.Zero && (Marshal.PtrToStringUTF8(Send(path, Selector("UTF8String"))) ?? "").EndsWith(".app", StringComparison.Ordinal);
        }
        finally
        {
            objc_autoreleasePoolPop(pool);
        }
    }

    // objc_msgSend takes the method's own arguments; each shape the helper calls has its overload. Integers and BOOLs
    // go in the same registers as pointers on arm64 and x64.

    [DllImport(ObjC, EntryPoint = "objc_msgSend")]
    public static extern IntPtr Send(IntPtr receiver, IntPtr selector);

    [DllImport(ObjC, EntryPoint = "objc_msgSend")]
    public static extern IntPtr Send(IntPtr receiver, IntPtr selector, IntPtr a);

    [DllImport(ObjC, EntryPoint = "objc_msgSend")]
    public static extern IntPtr Send(IntPtr receiver, IntPtr selector, IntPtr a, IntPtr b);

    [DllImport(ObjC, EntryPoint = "objc_msgSend")]
    public static extern IntPtr Send(IntPtr receiver, IntPtr selector, IntPtr a, IntPtr b, IntPtr c);

    [DllImport(ObjC)]
    public static extern IntPtr objc_autoreleasePoolPush();

    [DllImport(ObjC)]
    public static extern void objc_autoreleasePoolPop(IntPtr pool);

    [DllImport(ObjC)]
    private static extern IntPtr objc_getClass([MarshalAs(UnmanagedType.LPUTF8Str)] string name);

    [DllImport(ObjC)]
    private static extern IntPtr sel_registerName([MarshalAs(UnmanagedType.LPUTF8Str)] string name);
}
