using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Platform;

/// <summary>
/// The app the system opens a kind of file with, named as the helper names its office apps (M3.5 spec §5.10): the default
/// for a new copy's kind is Microsoft Office when it is what the system opens the kind's main format with. On macOS it
/// is the app LaunchServices would open a file of that extension with.
/// </summary>
internal static class DefaultApps
{
    /// <summary>
    /// <c>Microsoft Office</c> when Word, Excel or PowerPoint opens files with this extension (given without its dot, such
    /// as <c>docx</c>), <c>LibreOffice</c> when LibreOffice does, and null for any other app, none, or a computer that isn't
    /// a Mac. A failure to ask is no answer, so the default falls back to LibreOffice.
    /// </summary>
    public static string? For(string extension)
    {
        try
        {
            return AppBundles.NameOf(LaunchServices.DefaultAppBundle(extension));
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
        {
            return null;
        }
    }
}
