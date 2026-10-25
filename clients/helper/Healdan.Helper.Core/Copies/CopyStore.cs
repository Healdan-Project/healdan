using System.Security.Cryptography;
using System.Text.Json;

namespace Healdan.Helper.Core.Copies;

/// <summary>
/// The local copies in the helper's one private folder, and the record kept beside each (spec §5.4).
/// Never deletes the folder, or anything but the files of the copy it is told to delete.
/// </summary>
public sealed class CopyStore(HelperPaths paths)
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { WriteIndented = true };

    private const UnixFileMode PrivateMode = UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute;

    /// <summary>
    /// Writes the record for a new copy and returns the path the copy will have; the content is the caller's to put there.
    /// It never deletes anything: removing a copy is the caller's decision. If this document already has a copy with
    /// changes Healdan hasn't confirmed, that copy is returned untouched. If a file already sits at the target path with
    /// no usable record, a record with no confirmed hash is written for it, so it counts as unconfirmed and is kept.
    /// Otherwise the record is written, carrying <c>CheckedOutHere</c> and <c>LastSaveId</c> over from a record already
    /// at that path. Other copies of the document, at other paths, are left alone.
    /// </summary>
    public (string Path, CopyRecord Record) Create(Api.DocumentView doc, bool readOnly, string app)
    {
        EnsureFolders();
        var unconfirmed = All().FirstOrDefault(c => c.Record.DocNumber == doc.DocNumber && HasUnconfirmedChanges(c.Path));
        if (unconfirmed.Path is not null)
            return unconfirmed;

        var record = new CopyRecord(doc.DocNumber, doc.Name, doc.Version, doc.FileVersion, readOnly, false, null, null, app, null);
        var path = PathOf(record);
        if (Find(path) is (_, var old))
            record = record with { CheckedOutHere = old.CheckedOutHere, LastSaveId = old.LastSaveId };
        Save(record);
        return (path, record);
    }

    /// <summary>Writes the record whole, through a temporary file renamed over the old one, so a crash leaves either.</summary>
    public void Save(CopyRecord r)
    {
        EnsureFolders();
        var target = RecordPath(PathOf(r));
        var temp = System.IO.Path.Combine(paths.Copies, $".{Guid.NewGuid():N}.tmp");
        try
        {
            File.WriteAllText(temp, JsonSerializer.Serialize(r, Json));
            if (!OperatingSystem.IsWindows())
                File.SetUnixFileMode(temp, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            File.Move(temp, target, overwrite: true);
        }
        finally
        {
            File.Delete(temp);
        }
    }

    /// <summary>Every copy with a readable record; a damaged record is skipped.</summary>
    public IReadOnlyList<(string Path, CopyRecord Record)> All()
    {
        if (!Directory.Exists(paths.Copies))
            return [];
        var list = new List<(string, CopyRecord)>();
        foreach (var file in Directory.EnumerateFiles(paths.Copies, ".*.healdan.json").Order(StringComparer.Ordinal))
        {
            var record = Read(file);
            if (record is not null)
                list.Add((PathOf(record), record));
        }
        return list;
    }

    public (string, CopyRecord)? Find(string path)
    {
        var record = Read(RecordPath(path));
        return record is null ? null : (path, record);
    }

    /// <summary>Moves the copy and its record to the name for <paramref name="newFileVersion"/> and returns the new path.</summary>
    public string Rename(string path, string newFileVersion)
    {
        var (_, record) = Find(path) ?? throw new FileNotFoundException("No record for this copy.");
        var renamed = record with { FileVersion = newFileVersion };
        var target = PathOf(renamed);
        if (target == path)
            return path;
        // The record first: a crash between the two steps must never leave the file under a name with no record.
        Save(renamed);
        try
        {
            if (File.Exists(path))
                File.Move(path, target, overwrite: false);
        }
        catch
        {
            File.Delete(RecordPath(target));
            throw;
        }
        File.Delete(RecordPath(path));
        return target;
    }

    /// <summary>
    /// Removes this copy, its record and its lock and owner files, nothing else. It doesn't ask whether the copy has
    /// changes Healdan hasn't confirmed: the caller checks <see cref="HasUnconfirmedChanges"/> and only discards on the user's say.
    /// </summary>
    public void Delete(string path)
    {
        var name = System.IO.Path.GetFileName(path);
        File.Delete(path);
        File.Delete(RecordPath(path));
        File.Delete(System.IO.Path.Combine(paths.Copies, CopyNames.LibreOfficeLockName(name)));
        foreach (var owner in CopyNames.WordOwnerNames(name))
            File.Delete(System.IO.Path.Combine(paths.Copies, owner));
    }

    /// <summary>True when the copy differs from what Healdan last confirmed. A copy with no confirmed content counts as changed.</summary>
    public bool HasUnconfirmedChanges(string path)
    {
        if (!File.Exists(path))
            return false;
        var record = Read(RecordPath(path));
        if (record?.ConfirmedHash is null)
            return true;
        try
        {
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            var hash = Convert.ToHexString(SHA256.HashData(stream));
            return !hash.Equals(record.ConfirmedHash, StringComparison.OrdinalIgnoreCase);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return true; // unreadable: keep it
        }
    }

    private string PathOf(CopyRecord r) =>
        System.IO.Path.Combine(paths.Copies, CopyNames.FileName(r.DocumentName, r.DocNumber, r.FileVersion, r.ReadOnly));

    private string RecordPath(string copyPath) =>
        System.IO.Path.Combine(paths.Copies, CopyNames.RecordName(System.IO.Path.GetFileName(copyPath)));

    private static CopyRecord? Read(string recordPath)
    {
        try
        {
            var r = JsonSerializer.Deserialize<CopyRecord>(File.ReadAllText(recordPath), Json);
            // A body like {} parses but leaves the fields the copy's name needs null.
            if (r is null || r.DocumentName is null || r.FileVersion is null || r.Version is null || r.App is null)
                return null;
            _ = CopyNames.FileName(r.DocumentName, r.DocNumber, r.FileVersion, r.ReadOnly);
            return r;
        }
        catch (Exception e) when (e is IOException or JsonException or UnauthorizedAccessException or ArgumentException or NullReferenceException)
        {
            return null;
        }
    }

    /// <summary>Creates the folders private from the start, rather than narrowing them after.</summary>
    private void EnsureFolders()
    {
        if (OperatingSystem.IsWindows())
            Directory.CreateDirectory(paths.Copies);
        else
        {
            Directory.CreateDirectory(paths.Support, PrivateMode);
            Directory.CreateDirectory(paths.Copies, PrivateMode);
        }
    }
}
