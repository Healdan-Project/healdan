using System.Runtime.InteropServices;

namespace Healdan.Helper.Platform;

/// <summary>
/// What macOS's LaunchServices says about apps, asked of <c>NSWorkspace</c> through the Objective-C runtime
/// (<see cref="MacPlatform"/>): which app opens a kind of file by default, and whether an app is installed, wherever it is.
/// Both answer by bundle identifier. Anywhere else, and if the frameworks can't be loaded, the answer is none and no.
/// <para>Each call keeps its own autorelease pool, since it may run on any thread, and releases the one string it makes
/// itself. The objects <c>NSWorkspace</c> hands back are autoreleased, so the pool frees them. A nil answer ends the
/// lookup before the next call: Foundation raises an Objective-C exception for a nil argument, which would end the process.</para>
/// </summary>
internal static class LaunchServices
{
    // NSWorkspace is in AppKit, which brings Foundation's NSBundle; UTType is in a framework of its own.
    private const string AppKit = "/System/Library/Frameworks/AppKit.framework/AppKit";
    private const string UniformTypeIdentifiers = "/System/Library/Frameworks/UniformTypeIdentifiers.framework/UniformTypeIdentifiers";

    // Loaded by path, once: nothing else has to have loaded them, as in a process that has no window.
    private static readonly Lazy<bool> AppKitLoaded = new(() => NativeLibrary.TryLoad(AppKit, out _));
    private static readonly Lazy<bool> TypesLoaded = new(() => NativeLibrary.TryLoad(UniformTypeIdentifiers, out _));

    /// <summary>
    /// The bundle identifier of the app macOS opens files with this extension in (given with or without its dot, such as
    /// <c>docx</c>): the user's own choice, else the system's. Null when none opens it, or the extension is empty.
    /// </summary>
    public static string? DefaultAppBundle(string extension)
    {
        var bare = extension.Trim().TrimStart('.');
        if (!OperatingSystem.IsMacOS() || bare.Length == 0 || !AppKitLoaded.Value || !TypesLoaded.Value)
            return null;
        var pool = MacPlatform.objc_autoreleasePoolPush();
        var name = IntPtr.Zero;
        try
        {
            name = MacPlatform.NewString(bare);
            // Each step's argument must not be nil: Foundation raises an Objective-C exception, which ends the process.
            var type = MacPlatform.Send(MacPlatform.Class("UTType"), MacPlatform.Selector("typeWithFilenameExtension:"), name);
            if (type == IntPtr.Zero)
                return null;
            var url = MacPlatform.Send(Workspace(), MacPlatform.Selector("URLForApplicationToOpenContentType:"), type);
            if (url == IntPtr.Zero)
                return null; // no app opens it
            var bundle = MacPlatform.Send(MacPlatform.Class("NSBundle"), MacPlatform.Selector("bundleWithURL:"), url);
            return Text(MacPlatform.Send(bundle, MacPlatform.Selector("bundleIdentifier")));
        }
        finally
        {
            MacPlatform.Release(name);
            MacPlatform.objc_autoreleasePoolPop(pool);
        }
    }

    /// <summary>Whether LaunchServices finds an app with this bundle identifier, wherever it is installed.</summary>
    public static bool IsInstalled(string bundleIdentifier)
    {
        if (!OperatingSystem.IsMacOS() || bundleIdentifier.Length == 0 || !AppKitLoaded.Value)
            return false;
        var pool = MacPlatform.objc_autoreleasePoolPush();
        var name = IntPtr.Zero;
        try
        {
            name = MacPlatform.NewString(bundleIdentifier);
            return MacPlatform.Send(Workspace(), MacPlatform.Selector("URLForApplicationWithBundleIdentifier:"), name) != IntPtr.Zero;
        }
        finally
        {
            MacPlatform.Release(name);
            MacPlatform.objc_autoreleasePoolPop(pool);
        }
    }

    private static IntPtr Workspace() => MacPlatform.Send(MacPlatform.Class("NSWorkspace"), MacPlatform.Selector("sharedWorkspace"));

    /// <summary>An <c>NSString</c>'s text, or null for nil; valid until the pool drains, so it is copied out at once.</summary>
    private static string? Text(IntPtr nsString) =>
        nsString == IntPtr.Zero ? null : Marshal.PtrToStringUTF8(MacPlatform.Send(nsString, MacPlatform.Selector("UTF8String")));
}
