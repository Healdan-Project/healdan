using System.Text;

namespace Healdan.Helper.Core.Logging;

/// <summary>
/// The helper's log (plan: "The helper's files"), one line to a message. It can't grow without bound: past
/// <c>maxBytes</c> the file moves to <c>helper.log.1</c>, replacing the one before, so there are two files at most.
/// Callers never pass a token, a password, the channel secret or a file's contents. Logging never throws.
/// </summary>
public sealed class HelperLog(string path, long maxBytes = 1024 * 1024)
{
    /// <summary>A message is cut to this, so one odd input (a long link) can't fill the file.</summary>
    private const int MaxMessageLength = 2000;

    private readonly object _lock = new();

    public string Path => path;

    public void Info(string message) => Write("INFO", message);

    public void Warn(string message) => Write("WARN", message);

    /// <summary>The exception's type and message are added; never its data.</summary>
    public void Error(string message, Exception? e = null) =>
        Write("ERROR", e is null ? message : $"{message} ({e.GetType().Name}: {e.Message})");

    private void Write(string level, string message)
    {
        var line = Encoding.UTF8.GetBytes($"{DateTimeOffset.Now:yyyy-MM-ddTHH:mm:ss.fffzzz} {level} {OneLine(message)}\n");
        lock (_lock)
        {
            try
            {
                Directory.CreateDirectory(System.IO.Path.GetDirectoryName(System.IO.Path.GetFullPath(path))!);
                var current = new FileInfo(path);
                if (current.Exists && current.Length + line.Length > maxBytes)
                    File.Move(path, path + ".1", overwrite: true);
                var options = new FileStreamOptions { Mode = FileMode.Append, Access = FileAccess.Write, Share = FileShare.ReadWrite };
                if (!OperatingSystem.IsWindows())
                    options.UnixCreateMode = UnixFileMode.UserRead | UnixFileMode.UserWrite; // titles and paths are the user's
                using var stream = new FileStream(path, options);
                stream.Write(line);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                // Nowhere to write it; the helper carries on.
            }
        }
    }

    /// <summary>Control characters become spaces, so no input can forge a line of its own.</summary>
    private static string OneLine(string message)
    {
        var cut = message.Length <= MaxMessageLength ? message : message[..MaxMessageLength] + "...";
        var sb = new StringBuilder(cut.Length);
        foreach (var c in cut)
            sb.Append(char.IsControl(c) ? ' ' : c);
        return sb.ToString();
    }
}
