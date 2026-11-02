namespace Healdan.Helper.Core.Tests;

/// <summary>A test's own temporary folder, which the test class makes when it starts and deletes when it's disposed.</summary>
public static class TempFolder
{
    /// <summary>
    /// Deletes the folder and everything in it. A cycle or session disposed just before stops its background work (its
    /// save queues and watchers, a save back, an open) without waiting for it, so a record or a log line that work was
    /// already writing can land while the folder is being deleted, which then fails with "Directory not empty". The
    /// delete is tried again until nothing more lands, for up to <paramref name="timeout"/>; work still writing after
    /// that fails the test.
    /// </summary>
    public static void Delete(string path, TimeSpan? timeout = null)
    {
        var deadline = DateTime.UtcNow + (timeout ?? TimeSpan.FromSeconds(10));
        while (true)
        {
            try
            {
                if (Directory.Exists(path))
                    Directory.Delete(path, true);
                return;
            }
            catch (IOException) when (DateTime.UtcNow < deadline)
            {
                Thread.Sleep(50);
            }
        }
    }
}
