using System.Runtime.InteropServices;
using Healdan.Helper.Core.Logging;

namespace Healdan.Helper.Platform;

/// <summary>
/// macOS's own notifications, <c>UNUserNotificationCenter</c>, called through the Objective-C runtime so the build
/// needs no Xcode toolchain; never AppleScript, which failed in the trial (spec §5.1). They need the signed app bundle
/// (the package's): in a bare executable, such as a <c>dotnet run</c> build, the framework raises an Objective-C
/// exception that would end the process, so nothing is called there and <see cref="Allowed"/> stays false. The one
/// callback, permission's answer, is an Objective-C block: a global block whose invoke is a C# function pointer.
/// </summary>
internal static unsafe class MacNotifications
{
    private const string Framework = "/System/Library/Frameworks/UserNotifications.framework/UserNotifications";
    private const string LibSystem = "/usr/lib/libSystem.B.dylib";

    /// <summary><c>UNAuthorizationOptionSound | UNAuthorizationOptionAlert</c>.</summary>
    private const int SoundAndAlert = (1 << 1) | (1 << 2);

    /// <summary><c>BLOCK_IS_GLOBAL</c>: the block lives for the whole run, so copying it returns it as it is.</summary>
    private const int BlockIsGlobal = 1 << 28;

    private const int NotAsked = 0, Asking = 1, Granted = 2, Unavailable = 3;

    private static int _state;
    private static HelperLog? _log;

    [StructLayout(LayoutKind.Sequential)]
    private struct BlockDescriptor
    {
        public nuint Reserved;
        public nuint Size;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BlockLiteral
    {
        public IntPtr Isa;
        public int Flags;
        public int Reserved;
        public IntPtr Invoke;
        public BlockDescriptor* Descriptor;
    }

    /// <summary>The user allowed the helper's notifications; until they answer, and if they refused, it's false.</summary>
    public static bool Allowed => Volatile.Read(ref _state) == Granted;

    /// <summary>Asks once for permission to notify. The answer comes later, on another thread.</summary>
    public static void RequestPermission(HelperLog log)
    {
        if (!OperatingSystem.IsMacOS() || Interlocked.CompareExchange(ref _state, Asking, NotAsked) != NotAsked)
            return;
        _log = log;
        if (!MacPlatform.InAppBundle)
        {
            Volatile.Write(ref _state, Unavailable);
            log.Info("Not running from the app bundle, so notices go to the log and the menu's status line");
            return;
        }
        try
        {
            NativeLibrary.Load(Framework);
            var center = Center();
            if (center == IntPtr.Zero)
            {
                Volatile.Write(ref _state, Unavailable);
                return;
            }
            MacPlatform.Send(center, MacPlatform.Selector("requestAuthorizationWithOptions:completionHandler:"), SoundAndAlert, AnswerBlock());
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
        {
            Volatile.Write(ref _state, Unavailable);
            log.Error("macOS notifications aren't available", e);
        }
    }

    /// <summary>Shows a notification; false when they aren't allowed, so the caller shows it another way.</summary>
    public static bool TryPost(string title, string body)
    {
        if (!Allowed)
            return false;
        var pool = MacPlatform.objc_autoreleasePoolPush();
        IntPtr content = IntPtr.Zero, nsTitle = IntPtr.Zero, nsBody = IntPtr.Zero, id = IntPtr.Zero;
        try
        {
            content = MacPlatform.Send(MacPlatform.Send(MacPlatform.Class("UNMutableNotificationContent"), MacPlatform.Selector("alloc")),
                MacPlatform.Selector("init"));
            nsTitle = MacPlatform.NewString(title);
            nsBody = MacPlatform.NewString(body);
            id = MacPlatform.NewString(Guid.NewGuid().ToString());
            MacPlatform.Send(content, MacPlatform.Selector("setTitle:"), nsTitle);
            MacPlatform.Send(content, MacPlatform.Selector("setBody:"), nsBody);
            var request = MacPlatform.Send(MacPlatform.Class("UNNotificationRequest"),
                MacPlatform.Selector("requestWithIdentifier:content:trigger:"), id, content, IntPtr.Zero);
            MacPlatform.Send(Center(), MacPlatform.Selector("addNotificationRequest:withCompletionHandler:"), request, IntPtr.Zero);
            return true;
        }
        finally
        {
            MacPlatform.Release(content);
            MacPlatform.Release(nsTitle);
            MacPlatform.Release(nsBody);
            MacPlatform.Release(id);
            MacPlatform.objc_autoreleasePoolPop(pool);
        }
    }

    private static IntPtr Center() =>
        MacPlatform.Send(MacPlatform.Class("UNUserNotificationCenter"), MacPlatform.Selector("currentNotificationCenter"));

    /// <summary>The <c>void (^)(BOOL granted, NSError *error)</c> block, made once and never freed.</summary>
    private static IntPtr AnswerBlock()
    {
        var descriptor = (BlockDescriptor*)NativeMemory.AllocZeroed((nuint)sizeof(BlockDescriptor));
        descriptor->Size = (nuint)sizeof(BlockLiteral);
        var block = (BlockLiteral*)NativeMemory.AllocZeroed((nuint)sizeof(BlockLiteral));
        block->Isa = NativeLibrary.GetExport(NativeLibrary.Load(LibSystem), "_NSConcreteGlobalBlock");
        block->Flags = BlockIsGlobal;
        block->Invoke = (IntPtr)(delegate* unmanaged<IntPtr, byte, IntPtr, void>)&OnAnswer;
        block->Descriptor = descriptor;
        return (IntPtr)block;
    }

    /// <summary>
    /// Called by macOS on a thread of its own. An exception leaving an <c>UnmanagedCallersOnly</c> method ends the
    /// process, so nothing may: the state is set first, and the rest is only logging.
    /// </summary>
    [UnmanagedCallersOnly]
    private static void OnAnswer(IntPtr block, byte granted, IntPtr error)
    {
        try
        {
            Volatile.Write(ref _state, granted != 0 ? Granted : Unavailable);
            _log?.Info(granted != 0
                ? "macOS notifications allowed"
                : "macOS notifications weren't allowed, so notices go to the log and the menu's status line" + Reason(error));
        }
        catch (Exception)
        {
            // Nothing can be told from here; the state is what matters.
        }
    }

    /// <summary>The <c>NSError</c>'s description, as macOS gives it (such as an unsigned bundle's refusal).</summary>
    private static string Reason(IntPtr error)
    {
        if (error == IntPtr.Zero)
            return "";
        var pool = MacPlatform.objc_autoreleasePoolPush();
        try
        {
            var text = MacPlatform.Send(MacPlatform.Send(error, MacPlatform.Selector("localizedDescription")), MacPlatform.Selector("UTF8String"));
            return text == IntPtr.Zero ? "" : $" ({Marshal.PtrToStringUTF8(text)})";
        }
        finally
        {
            MacPlatform.objc_autoreleasePoolPop(pool);
        }
    }
}
