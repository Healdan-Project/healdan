using System.Security.Cryptography;
using System.Text.Json;
using Healdan.Helper.Core.Cycle;

namespace Healdan.Helper.Core.Copies;

/// <summary>
/// The local copies in the helper's one private folder, and the record kept beside each (spec §5.4).
/// Never deletes the folder, or anything but the files of the copy it is told to delete.
/// </summary>
public sealed class CopyStore(HelperPaths paths)
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { WriteIndented = true };

    private const UnixFileMode PrivateMode = UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute;

    /// <summary>Each copy's hash as <see cref="HasUnconfirmedChanges"/> last read it, by file name, with the file's stamp then.</summary>
    private readonly Dictionary<string, (FileStamp Stamp, string Hash)> _hashes = new(StringComparer.Ordinal);

    /// <summary>
    /// Writes the record for a new copy and returns the path the copy will have; the content is the caller's to put there.
    /// It never deletes anything: removing a copy is the caller's decision. If this document already has a copy of the
    /// same kind (read-only, or for editing) with changes Healdan hasn't confirmed, that copy is returned untouched; a
    /// read-only open never hands back the user's edited copy. If a file already sits at the target path with
    /// no usable record, a record with no confirmed hash is written for it, so it counts as unconfirmed and is kept.
    /// Otherwise the record is written, carrying <c>CheckedOutHere</c> and <c>LastSaveId</c> over from a record already
    /// at that path. Other copies of the document, at other paths, are left alone.
    /// <para><c>Existing</c> is true when the path already holds a copy the helper must keep (one with unconfirmed
    /// changes, or an unrecorded file): the caller reopens it and must not download over it. It is false for a new copy,
    /// and for a path whose copy Healdan had confirmed, which is safe to refresh.</para>
    /// </summary>
    public (string Path, CopyRecord Record, bool Existing) Create(Api.DocumentView doc, bool readOnly, string app)
    {
        EnsureFolders();
        var unconfirmed = All().FirstOrDefault(c =>
            c.Record.DocNumber == doc.DocNumber && c.Record.ReadOnly == readOnly && HasUnconfirmedChanges(c.Path));
        if (unconfirmed.Path is not null)
            return (unconfirmed.Path, unconfirmed.Record, true);

        var record = new CopyRecord(doc.DocNumber, doc.Name, doc.Version, doc.FileVersion, readOnly, false, null, null, app, null,
            CopyNames.FileName(doc.Name, doc.DocNumber, doc.FileVersion, readOnly));
        var path = PathOf(record);
        var existing = false;
        if (Find(path) is (_, var old))
            record = record with { CheckedOutHere = old.CheckedOutHere, LastSaveId = old.LastSaveId };
        else
            existing = File.Exists(path); // a file with no usable record: protect it, report it
        Save(record);
        return (path, record, existing);
    }

    /// <summary>
    /// Writes the record whole, through a temporary file renamed over the old one, so a crash leaves either. It goes
    /// beside the copy named by <see cref="CopyRecord.FileName"/>, whatever the record's versions say.
    /// </summary>
    public void Save(CopyRecord r)
    {
        if (string.IsNullOrEmpty(r.FileName) || r.FileName != System.IO.Path.GetFileName(r.FileName)
            || r.FileName is "." or ".." || r.FileName.IndexOfAny(['/', '\\', '\0']) >= 0)
            throw new ArgumentException("A copy's file name is a plain file name.", nameof(r));
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

    /// <summary>
    /// Moves the copy and its record to the name for <paramref name="newFileVersion"/> and returns the new path. Only for
    /// a copy no app has open (R19); an open copy keeps its name, and its record's versions move on without it.
    /// </summary>
    public string Rename(string path, string newFileVersion)
    {
        var (_, record) = Find(path) ?? throw new FileNotFoundException("No record for this copy.");
        var renamed = record with
        {
            FileVersion = newFileVersion,
            FileName = CopyNames.FileName(record.DocumentName, record.DocNumber, newFileVersion, record.ReadOnly)
        };
        var target = PathOf(renamed);
        if (target == path)
            return path;
        if (File.Exists(target) || Directory.Exists(target) || File.Exists(RecordPath(target)))
            throw new IOException("Another copy already has that name.");
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
        lock (_hashes)
            _hashes.Remove(System.IO.Path.GetFileName(path));
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
        lock (_hashes)
            _hashes.Remove(name);
    }

    /// <summary>
    /// True when the copy differs from what Healdan last confirmed. A copy with no confirmed content counts as changed.
    /// With <paramref name="cached"/>, a file whose size and time haven't changed since it was last read here isn't
    /// read again, for a list asked for often, such as the menu's Not Yet in Healdan (re-review). A write that keeps
    /// both goes unseen then, so a decision to delete a copy or download over it never uses it.
    /// </summary>
    public bool HasUnconfirmedChanges(string path, bool cached = false)
    {
        if (!File.Exists(path))
            return false;
        var record = Read(RecordPath(path));
        if (record?.ConfirmedHash is null)
            return true;
        var name = System.IO.Path.GetFileName(path);
        var stamp = FileStamp.Of(path); // before the read, so a change during it is read again next time
        (FileStamp Stamp, string Hash) last;
        bool known;
        lock (_hashes)
            known = _hashes.TryGetValue(name, out last) && cached && last.Stamp == stamp;
        if (!known)
        {
            try
            {
                using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                last = (stamp, Convert.ToHexString(SHA256.HashData(stream)));
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                return true; // unreadable: keep it
            }
            lock (_hashes)
                _hashes[name] = last;
        }
        return !last.Hash.Equals(record.ConfirmedHash, StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>The folder holding every copy.</summary>
    internal string Folder => paths.Copies;

    private string PathOf(CopyRecord r) => System.IO.Path.Combine(paths.Copies, r.FileName);

    private string RecordPath(string copyPath) =>
        System.IO.Path.Combine(paths.Copies, CopyNames.RecordName(System.IO.Path.GetFileName(copyPath)));

    /// <summary>
    /// Reads a record. The copy it belongs to is the one its own file name names (<c>.&lt;copy&gt;.healdan.json</c>),
    /// which can't point outside the folder, so that wins over the <c>fileName</c> written inside it.
    /// </summary>
    private static CopyRecord? Read(string recordPath)
    {
        try
        {
            var r = JsonSerializer.Deserialize<CopyRecord>(File.ReadAllText(recordPath), Json);
            // A body like {} parses but leaves the fields the copy's name needs null.
            if (r is null || r.DocumentName is null || r.FileVersion is null || r.Version is null || r.App is null)
                return null;
            _ = CopyNames.FileName(r.DocumentName, r.DocNumber, r.FileVersion, r.ReadOnly);
            var recordName = System.IO.Path.GetFileName(recordPath);
            const string suffix = ".healdan.json";
            if (recordName.Length <= suffix.Length + 1 || !recordName.EndsWith(suffix, StringComparison.Ordinal))
                return null;
            return r with { FileName = recordName[1..^suffix.Length] };
        }
        catch (Exception e) when (e is IOException or JsonException or UnauthorizedAccessException or ArgumentException or NullReferenceException)
        {
            return null;
        }
    }

    /// <summary>Creates the folders private from the start, rather than narrowing them after.</summary>
    internal void EnsureFolders()
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
