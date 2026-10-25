using System.Runtime.InteropServices;
using System.Text;

namespace Healdan.Helper.Core.Platform;

/// <summary>
/// macOS's quarantine attribute, which a browser puts on what it downloads so that Gatekeeper checks the file before it
/// runs or opens anything (final review C2). The helper puts it on every copy it hands the system's default app: such
/// a file comes from a matter anyone with access could have added it to. Elsewhere this does nothing, as Linux has no
/// Gatekeeper.
/// </summary>
public static class Quarantine
{
    public const string Attribute = "com.apple.quarantine";

    private const string Agent = "Healdan Helper";

    /// <summary>
    /// The attribute's value in macOS's form: its flags (downloaded, not yet approved), the time in hex seconds, the
    /// app, and no event.
    /// </summary>
    public static string Value(DateTimeOffset when) => $"0081;{when.ToUnixTimeSeconds():x8};{Agent};";

    /// <summary>Puts the attribute on the file, a read-only copy included; throws <see cref="IOException"/> if it can't.</summary>
    public static void Mark(string path)
    {
        if (!OperatingSystem.IsMacOS())
            return;
        // macOS sets an attribute only on a file its owner may write, so a read-only copy is opened up for a moment.
        var mode = File.GetUnixFileMode(path);
        var readOnly = (mode & UnixFileMode.UserWrite) == 0;
        if (readOnly)
            File.SetUnixFileMode(path, mode | UnixFileMode.UserWrite);
        try
        {
            var value = Encoding.UTF8.GetBytes(Value(DateTimeOffset.UtcNow));
            if (setxattr(path, Attribute, value, (nuint)value.Length, 0, 0) != 0)
                throw new IOException(
                    $"The copy couldn't be marked as downloaded, so it wasn't opened (error {Marshal.GetLastPInvokeError()}).");
        }
        finally
        {
            if (readOnly)
                File.SetUnixFileMode(path, mode);
        }
    }

    [DllImport("libc", SetLastError = true)]
    private static extern int setxattr([MarshalAs(UnmanagedType.LPUTF8Str)] string path, [MarshalAs(UnmanagedType.LPUTF8Str)] string name,
        byte[] value, nuint size, uint position, int options);
}
