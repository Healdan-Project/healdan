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
    /// If a copy of this document is already here and has changes Healdan hasn't confirmed, nothing is touched and that
    /// copy is returned instead. Copies of it with no such changes are removed first, so one document has one copy.
    /// </summary>
    public (string Path, CopyRecord Record) Create(Api.DocumentView doc, bool readOnly, string app)
    {
        EnsureFolders();
        var existing = All().Where(c => c.Record.DocNumber == doc.DocNumber).ToList();
        var unconfirmed = existing.FirstOrDefault(c => HasUnconfirmedChanges(c.Path));
        if (unconfirmed.Path is not null)
            return unconfirmed;
        foreach (var (path, _) in existing)
            Delete(path);

        var record = new CopyRecord(doc.DocNumber, doc.Name, doc.Version, doc.FileVersion, readOnly, false, null, null, app, null);
        Save(record);
        return (PathOf(record), record);
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
        if (File.Exists(path))
            File.Move(path, target, overwrite: false);
        Save(renamed);
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
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        var hash = Convert.ToHexString(SHA256.HashData(stream));
        return !hash.Equals(record.ConfirmedHash, StringComparison.OrdinalIgnoreCase);
    }

    private string PathOf(CopyRecord r) =>
        System.IO.Path.Combine(paths.Copies, CopyNames.FileName(r.DocumentName, r.DocNumber, r.FileVersion, r.ReadOnly));

    private string RecordPath(string copyPath) =>
        System.IO.Path.Combine(paths.Copies, CopyNames.RecordName(System.IO.Path.GetFileName(copyPath)));

    private static CopyRecord? Read(string recordPath)
    {
        try
        {
            return JsonSerializer.Deserialize<CopyRecord>(File.ReadAllText(recordPath), Json);
        }
        catch (Exception e) when (e is IOException or JsonException or UnauthorizedAccessException)
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
