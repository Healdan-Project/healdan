using Healdan.Helper.Core.Security;
using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Core.Addin;

/// <summary>
/// Set Up Microsoft Office (M3.5 spec §5.13): when it applies, when the helper offers it, and what its Set Up does,
/// which is to make the helper's own certificate and have the computer trust it. The other two things the window explains,
/// each app's Automation and its Grant File Access, are asked by macOS when each app first needs them.
/// <para>It applies only to a Mac with Word, Excel or PowerPoint on it. Microsoft Office is a Mac app here (Windows comes
/// later), so off macOS it is never needed, offered or done, whatever <see cref="InstalledApps"/> says.</para>
/// </summary>
/// <param name="installed">The installed apps, read at each call, so an Office installed later counts.</param>
/// <param name="macOS">Whether this is a Mac; only the tests say otherwise.</param>
public sealed class OfficeSetUp(
    HelperPaths paths, AddinCertificate certificate, ICertificateTrust trust, Func<InstalledApps> installed, bool? macOS = null)
{
    private readonly bool _macOS = macOS ?? OperatingSystem.IsMacOS();

    /// <summary>This is a Mac with Word, Excel or PowerPoint on it.</summary>
    public bool Applies => _macOS && installed().MicrosoftOffice;

    /// <summary>
    /// <see cref="Applies"/>, and the certificate is missing, nearly out of days, or not trusted: the menu offers Set Up
    /// Microsoft Office. It makes nothing.
    /// </summary>
    public bool Needed => Applies && certificate.NeedsSetUp(trust);

    /// <summary>
    /// True the first time it is called while Set Up is <see cref="Needed"/>, and never again (the helper offers it once,
    /// at its first start that finds Office, and the user finds it in the menu after). Remembered in
    /// <see cref="HelperSettings.OfficeSetUpOffered"/>.
    /// </summary>
    public bool TakeFirstOffer()
    {
        if (!Needed)
            return false;
        var first = false;
        HelperSettings.Update(paths, s =>
        {
            first = !s.OfficeSetUpOffered;
            s.OfficeSetUpOffered = true;
        });
        return first;
    }

    /// <summary>
    /// The window's Set Up: makes the certificate if there isn't a good one, and asks the computer to trust it, which asks
    /// the user for their password. A cancelled password prompt isn't a failure. Cancelling <paramref name="ct"/> stops the
    /// ask and throws <see cref="OperationCanceledException"/>.
    /// </summary>
    public async Task<SetUpResult> TrustAsync(CancellationToken ct)
    {
        if (!_macOS)
            return SetUpResult.Failed("Microsoft Office is set up on a Mac only.");
        try
        {
            certificate.LoadOrCreate().Dispose();
            if (!await trust.TrustAsync(paths.AddinCertificate, ct))
                return SetUpResult.Cancelled;
            // Believe what the computer says now, not that it said yes: a certificate it doesn't trust is no use to the add-in.
            return certificate.NeedsSetUp(trust)
                ? SetUpResult.Failed("macOS took the certificate, but doesn't trust it. Choose Set Up to try again.")
                : SetUpResult.Done;
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            return SetUpResult.Failed("Set Up failed: " + e.Message);
        }
    }
}
