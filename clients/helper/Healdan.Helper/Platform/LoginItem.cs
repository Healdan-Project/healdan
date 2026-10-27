using System.Runtime.InteropServices;
using Healdan.Helper.Core;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Platform;

/// <summary>
/// Starting at sign-in to the computer (spec §5.1: the helper runs from sign-in until quit), as a macOS login item:
/// <c>SMAppService.mainAppService</c> (macOS 13 and later), called through the Objective-C runtime as the
/// notifications are. The helper registers once, after its first sign-in to Healdan; after that, turning it off in
/// System Settings is the user's choice and stands, but a registration macOS has lost (<c>NotFound</c>) is made again.
/// Only the app bundle registers, never a Debug build or one run by hand, and not while it runs from the disk image or
/// a translocated copy (a quarantined app opened where it was downloaded), whose path goes away. macOS starts a login
/// item once per sign-in and doesn't restart it, so it can't crash-loop; a start before the display is on waits for
/// it (<see cref="MacPlatform.HasActiveDisplay"/>).
/// </summary>
internal static unsafe class LoginItem
{
    private const string Framework = "/System/Library/Frameworks/ServiceManagement.framework/ServiceManagement";
    private const string Where = "System Settings › General › Login Items & Extensions";

    /// <summary><c>SMAppServiceStatus</c>'s values.</summary>
    private const long NotRegistered = 0, Enabled = 1, RequiresApproval = 2, NotFound = 3;

    /// <summary>Only the app bundle in a Release build: a Debug build run by hand must never become a login item.</summary>
    public static bool Available
    {
        get
        {
#if DEBUG
            return false;
#else
            return MacPlatform.InAppBundle;
#endif
        }
    }

    /// <summary>
    /// After a sign-in: registers, unless it has once before and macOS still has it. A failure is logged, and tried
    /// again at the next start.
    /// </summary>
    public static void EnsureRegistered(HelperPaths paths, HelperLog log)
    {
        if (!Available)
        {
            log.Info("Not the app bundle's Release build, so it isn't registered to start at sign-in");
            return;
        }
        if (Passing() is { } where)
        {
            log.Info($"Running from {where} ({MacPlatform.BundlePath}), so it isn't registered to start at sign-in");
            return;
        }
        try
        {
            if (HelperSettings.Load(paths).LoginItemRegistered)
            {
                var status = Status();
                if (status != NotFound)
                {
                    log.Info($"Start at sign-in: {status switch { Enabled => "on", RequiresApproval => "waiting for approval", _ => "off" }}");
                    return;
                }
                log.Warn("macOS can't find the login item any more, so it's registered again");
            }
            if (Register(out var error))
            {
                HelperSettings.Update(paths, s => s.LoginItemRegistered = true);
                log.Info($"Registered to start at sign-in (status {Status()})");
            }
            else
            {
                log.Warn("Couldn't register to start at sign-in: " + (error ?? "macOS gave no reason"));
            }
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException or IOException or UnauthorizedAccessException)
        {
            log.Error("Couldn't register to start at sign-in", e);
        }
    }

    /// <summary>Settings' read-only line.</summary>
    public static string Describe()
    {
        if (!Available)
            return "Not in this build: the installed app starts when you sign in to this computer.";
        if (Passing() is { } where)
            return $"Off while Healdan Helper runs from {where}. Drag it to Applications, then open it from there.";
        try
        {
            return Status() switch
            {
                Enabled => "On: Healdan Helper starts when you sign in to this computer.",
                RequiresApproval => $"Waiting for your approval in {Where}.",
                NotFound => "Off: macOS can't find the app. Move it to Applications and open it again.",
                _ => $"Off. To turn it on, add Healdan Helper in {Where}."
            };
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
        {
            return "Unknown: macOS didn't say.";
        }
    }

    /// <summary>Settings' row (polish spec §6.5): a short value, whether it's on, and <see cref="Describe"/> as its tooltip.</summary>
    public static LoginItemSummary Summary()
    {
        var tip = Describe();
        if (!Available)
            return new("Not in This Build", false, tip);
        if (Passing() is not null)
            return new("Off", false, tip);
        try
        {
            return Status() switch
            {
                Enabled => new("On", true, tip),
                RequiresApproval => new("Waiting for Approval", false, tip),
                _ => new("Off", false, tip)
            };
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
        {
            return new("Unknown", false, tip);
        }
    }

    /// <summary>
    /// Where the app runs from, when it's a place that goes away: the mounted disk image, or the read-only copy macOS
    /// runs a quarantined app from until it's moved (App Translocation). Null from anywhere else, such as Applications.
    /// </summary>
    private static string? Passing() => MacPlatform.BundlePath switch
    {
        { } path when path.StartsWith("/Volumes/", StringComparison.Ordinal) => "the disk image",
        { } path when path.Contains("/AppTranslocation/", StringComparison.Ordinal) => "where it was downloaded",
        _ => null
    };

    private static long Status()
    {
        var pool = MacPlatform.objc_autoreleasePoolPush();
        try
        {
            return (long)MacPlatform.Send(Service(), MacPlatform.Selector("status"));
        }
        finally
        {
            MacPlatform.objc_autoreleasePoolPop(pool);
        }
    }

    /// <summary><c>-[SMAppService registerAndReturnError:]</c>; its BOOL is the low byte of the return register.</summary>
    private static bool Register(out string? error)
    {
        var pool = MacPlatform.objc_autoreleasePoolPush();
        try
        {
            var nsError = IntPtr.Zero;
            var registered = ((long)MacPlatform.Send(Service(), MacPlatform.Selector("registerAndReturnError:"), (IntPtr)(&nsError)) & 0xFF) != 0;
            error = registered ? null : MacPlatform.ErrorText(nsError);
            return registered;
        }
        finally
        {
            MacPlatform.objc_autoreleasePoolPop(pool);
        }
    }

    private static IntPtr Service()
    {
        NativeLibrary.Load(Framework);
        var type = MacPlatform.Class("SMAppService");
        if (type == IntPtr.Zero)
            throw new EntryPointNotFoundException("SMAppService isn't available.");
        var service = MacPlatform.Send(type, MacPlatform.Selector("mainAppService"));
        return service != IntPtr.Zero ? service : throw new EntryPointNotFoundException("SMAppService has no main app service.");
    }
}

/// <summary>Start at Sign-In's row in Settings.</summary>
internal sealed record LoginItemSummary(string Value, bool On, string Tip);
