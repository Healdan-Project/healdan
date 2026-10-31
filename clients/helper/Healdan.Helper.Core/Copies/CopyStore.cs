using System.Security.Cryptography;
using System.Text.Json;
using Healdan.Helper.Core.Cycle;

namespace Healdan.Helper.Core.Copies;

/// <summary>
/// The local copies in the helper's one private folder, and the record kept beside each (spec §5.4), for the server at
/// <paramref name="server"/>'s address, which each new copy's record names. A store for a server sees only that
/// server's copies, and those from before records named one (<see cref="All"/>, <see cref="Find"/>): another server's
/// copy is never taken for this one's, even when it lands in the folder while the helper runs. Never deletes the
/// folder, or anything but the files of the copy it is told to delete.
/// </summary>
public sealed class CopyStore(HelperPaths paths, string? server = null)
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
    /// A copy at the target path whose record names another server isn't this document's, though the doc # may match:
    /// it is set aside first (<see cref="MoveCopy"/>), whatever its changes, and the new copy starts as any other, with
    /// nothing carried over from it and nothing downloaded over it.
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
            CopyNames.FileName(doc.Name, doc.DocNumber, doc.FileVersion, readOnly), Server: server);
        var path = PathOf(record);
        var existing = false;
        var found = Read(RecordPath(path)); // any server's, which Find leaves out
        if (found is { } theirs && !Ours(theirs))
        {
            var name = System.IO.Path.GetFileName(path);
            MoveCopy(paths.Copies, AsideFolder(theirs.Server!, [name, CopyNames.RecordName(name)]), name);
            lock (_hashes)
                _hashes.Remove(name);
        }
        else if (found is { } old)
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
        Write(r, paths.Copies);
    }

    /// <summary>
    /// Writes the record whole into <paramref name="folder"/>, beside the copy its <see cref="CopyRecord.FileName"/>
    /// names, through a temporary file renamed over the old one, so a crash leaves either.
    /// </summary>
    private static void Write(CopyRecord r, string folder)
    {
        var target = System.IO.Path.Combine(folder, CopyNames.RecordName(r.FileName));
        var temp = System.IO.Path.Combine(folder, $".{Guid.NewGuid():N}.tmp");
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

    /// <summary>
    /// Every copy with a readable record that this store's server owns, or that names none; a damaged record is skipped.
    /// Another server's record is left out, so a copy of that server's, which can share a doc # with one of this
    /// server's, is never taken for it. A store with no server returns every record.
    /// </summary>
    public IReadOnlyList<(string Path, CopyRecord Record)> All() =>
        Directory.Exists(paths.Copies) ? Records(paths.Copies).Where(Ours).Select(r => (PathOf(r), r)).ToList() : [];

    /// <summary>Whether the record is this store's server's, or from before records named a server.</summary>
    private bool Ours(CopyRecord r) => server is null || r.Server is null || ServerAddress.Same(r.Server, server);

    /// <summary>
    /// The copy at <paramref name="path"/> with its record, when the record is readable and this store's server owns it
    /// or it names none, as <see cref="All"/>. So the cycle, which the channel's routes reach a copy through by its path,
    /// never takes another server's copy left in Copies, such as one open in an app at a start, for this one's.
    /// </summary>
    public (string, CopyRecord)? Find(string path)
    {
        var record = Read(RecordPath(path));
        return record is null || !Ours(record) ? null : (path, record);
    }

    /// <summary>
    /// Moves the copy and its record to the name for <paramref name="newFileVersion"/> and returns the new path. Only for
    /// a copy no app has open (R19); an open copy keeps its name, and its record's versions move on without it, unless
    /// the app renames it itself (<see cref="Moved"/>).
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
    /// M3.5 spec §5.4: the app has saved the open copy under a new name itself (Word's and Excel's rename after a save),
    /// so the record follows the file there. <paramref name="record"/> is written beside <paramref name="newPath"/>,
    /// with that file's name; then the old record goes, and the old name's owner and lock files, which a crash may have
    /// left. The old file goes too if it's still there, but only once the new file is: the app's owner file alone at the
    /// new name says it was saving there, not that it has, and the old file may be all there is of the copy. The new file
    /// is the caller's to have checked: nothing here reads it.
    /// </summary>
    public void Moved(string oldPath, string newPath, CopyRecord record)
    {
        var oldName = System.IO.Path.GetFileName(oldPath);
        var newName = System.IO.Path.GetFileName(newPath);
        // The record first: a crash before the old one goes leaves the renamed file with its record.
        Save(record with { FileName = newName });
        if (oldName == newName)
            return;
        File.Delete(RecordPath(oldPath));
        if (File.Exists(newPath))
            File.Delete(System.IO.Path.Combine(paths.Copies, oldName));
        File.Delete(System.IO.Path.Combine(paths.Copies, CopyNames.LibreOfficeLockName(oldName)));
        foreach (var owner in CopyNames.OfficeOwnerNames(oldName))
            File.Delete(System.IO.Path.Combine(paths.Copies, owner));
        lock (_hashes)
            _hashes.Remove(oldName);
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
        foreach (var owner in CopyNames.OfficeOwnerNames(name))
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

    /// <summary>The start of the name of each folder beside Copies that holds copies set aside (spec §5.4).</summary>
    private const string AsidePrefix = "Copies from ";

    /// <summary>
    /// Spec §5.4: moves each copy whose record doesn't name <paramref name="keep"/>, with its record, into the folder
    /// beside Copies for the server its record names (<see cref="MoveCopy"/>), and returns where each server's copies
    /// went. A record without a server, from before records named one, counts as <paramref name="unrecorded"/>'s and has
    /// it written in as it moves, so it can come back there. When <paramref name="unrecorded"/> isn't
    /// <paramref name="keep"/>, anything else in the folder goes to that server's folder too, such as a copy with no
    /// usable record, except an app's lock and owner files (<see cref="CopyNames.IsAppFile"/>), which the app deletes
    /// by the path it opened, a download's temporary part, which the resume sweeps, and Finder's <c>.DS_Store</c>, which
    /// is no one's. The copies named in <paramref name="leave"/>, such as those an app has open
    /// (<see cref="OpenToSetAside"/>), stay with their records. Copies itself stays, so an app's access to it does too.
    /// A move that fails throws; the copies moved before it stay moved. It reads every server's records, not just the
    /// store's (<see cref="All"/>).
    /// </summary>
    /// <param name="keep">The address of the server whose copies stay, such as <c>https://healdan.example</c>.</param>
    /// <param name="unrecorded">The address of the server that records without one, and files without a record, belong to.</param>
    /// <param name="leave">The file names in Copies of copies that stay wherever they belong.</param>
    public IReadOnlyList<AsideCopies> SetAside(string keep, string unrecorded, IReadOnlyCollection<string>? leave = null)
    {
        if (!Directory.Exists(paths.Copies))
            return [];
        var left = new HashSet<string>(leave ?? [], StringComparer.Ordinal);
        var (copies, strays) = ToSetAside(keep, unrecorded);
        var moved = new List<(string Server, string Folder, bool Copy)>();
        foreach (var (record, owner) in copies.Where(c => !left.Contains(c.Record.FileName)))
        {
            var name = record.FileName;
            var folder = AsideFolder(owner, [name, CopyNames.RecordName(name)]);
            MoveCopy(paths.Copies, folder, name, record.Server is null ? record with { Server = owner } : null);
            moved.Add((owner, folder, true));
        }
        strays.RemoveAll(left.Contains);
        if (strays.Count > 0)
        {
            var folder = AsideFolder(unrecorded, strays);
            foreach (var name in strays)
            {
                var from = System.IO.Path.Combine(paths.Copies, name);
                if (Directory.Exists(from))
                    Directory.Move(from, System.IO.Path.Combine(folder, name));
                else
                    File.Move(from, System.IO.Path.Combine(folder, name));
            }
            moved.Add((unrecorded, folder, false));
        }
        lock (_hashes)
            _hashes.Clear();
        return moved
            .GroupBy(m => ServerAddress.Name(m.Server), StringComparer.OrdinalIgnoreCase)
            .Select(g => new AsideCopies(g.Key, g.Select(m => m.Folder).Min(StringComparer.Ordinal)!, g.Count(m => m.Copy)))
            .OrderBy(a => a.Server, StringComparer.Ordinal)
            .ToList();
    }

    /// <summary>
    /// Spec §5.4: what <see cref="SetAside"/> with the same servers would move that an app has open in Copies, by its
    /// lock or owner file beside it, so it isn't moved from under its window, whose next save would leave a file with no
    /// record in its place. LibreOffice keeps no lock file for a read-only copy, so one open there isn't listed. Whether
    /// the app is running, and so whether a lock file is a crash's, is the caller's to tell. Nothing moves.
    /// </summary>
    public IReadOnlyList<OpenCopy> OpenToSetAside(string keep, string unrecorded)
    {
        if (!Directory.Exists(paths.Copies))
            return [];
        var (copies, strays) = ToSetAside(keep, unrecorded);
        return copies
            .Where(c => Open(c.Record.FileName))
            .Select(c => new OpenCopy(CopyNames.Title(c.Record.DocumentName, c.Record.DocNumber, c.Record.FileVersion, c.Record.ReadOnly),
                c.Record.FileName, ServerAddress.Name(c.Owner)))
            .Concat(strays.Where(Open).Select(name => new OpenCopy(System.IO.Path.GetFileNameWithoutExtension(name), name,
                ServerAddress.Name(unrecorded))))
            .ToList();

        bool Open(string name) => File.Exists(System.IO.Path.Combine(paths.Copies, name)) && IsOpen(paths.Copies, name);
    }

    /// <summary>
    /// What setting aside for <paramref name="keep"/> moves (<see cref="SetAside"/>), read before anything moves: each
    /// readable record of every server's that doesn't name it, in name order, with the server it goes to, and when
    /// <paramref name="unrecorded"/> isn't <paramref name="keep"/>, every other file in Copies by name, but an app's lock
    /// and owner files, a download's temporary part and Finder's <c>.DS_Store</c>.
    /// </summary>
    private (List<(CopyRecord Record, string Owner)> Copies, List<string> Strays) ToSetAside(string keep, string unrecorded)
    {
        var copies = new List<(CopyRecord, string)>();
        var recorded = new HashSet<string>(StringComparer.Ordinal);
        foreach (var record in Records(paths.Copies)) // every server's, which All() leaves out
        {
            recorded.Add(record.FileName);
            recorded.Add(CopyNames.RecordName(record.FileName));
            var owner = record.Server ?? unrecorded;
            if (!ServerAddress.Same(owner, keep))
                copies.Add((record, owner));
        }
        var strays = ServerAddress.Same(unrecorded, keep)
            ? []
            : Directory.EnumerateFileSystemEntries(paths.Copies)
                .Select(entry => System.IO.Path.GetFileName(entry))
                .Where(name => !recorded.Contains(name) && !CopyNames.IsAppFile(name) && !IsPart(name) && name != FinderFile)
                .Order(StringComparer.Ordinal)
                .ToList();
        return (copies, strays);
    }

    /// <summary>
    /// Moves one copy and its record from <paramref name="from"/> to <paramref name="to"/>, never over anything: the copy
    /// first, then its record, written as <paramref name="rewrite"/> when that's given. If the record can't follow, the
    /// copy moves back, so neither is left without the other. A record with no copy, from a download that never
    /// finished, moves alone.
    /// </summary>
    internal static void MoveCopy(string from, string to, string fileName, CopyRecord? rewrite = null)
    {
        var copy = System.IO.Path.Combine(from, fileName);
        var record = System.IO.Path.Combine(from, CopyNames.RecordName(fileName));
        var movedCopy = System.IO.Path.Combine(to, fileName);
        var movedRecord = System.IO.Path.Combine(to, CopyNames.RecordName(fileName));
        var hasCopy = File.Exists(copy);
        if (hasCopy)
            File.Move(copy, movedCopy);
        var written = false;
        try
        {
            if (rewrite is null)
                File.Move(record, movedRecord);
            else
            {
                Write(rewrite, to);
                written = true;
                File.Delete(record);
            }
        }
        catch
        {
            if (written)
                File.Delete(movedRecord);
            if (hasCopy)
                File.Move(movedCopy, copy);
            throw;
        }
    }

    /// <summary>
    /// The folder beside Copies for <paramref name="server"/>'s copies set aside, <c>Copies from &lt;host[-port]&gt;</c>,
    /// or the next numbered one where none of <paramref name="names"/> is taken, made private when it isn't there yet.
    /// The records say exactly which server, so servers with the same host and port share it.
    /// </summary>
    private string AsideFolder(string server, IReadOnlyCollection<string> names)
    {
        var name = AsidePrefix + string.Concat(ServerAddress.Name(server).Select(c => c is '/' or ':' or '\\' || char.IsControl(c) ? '-' : c));
        for (var n = 1; ; n++)
        {
            var folder = System.IO.Path.Combine(paths.Support, n == 1 ? name : $"{name} {n}");
            if (File.Exists(folder))
                continue;
            if (!Directory.Exists(folder))
            {
                if (OperatingSystem.IsWindows())
                    Directory.CreateDirectory(folder);
                else
                    Directory.CreateDirectory(folder, PrivateMode);
                return folder;
            }
            if (names.All(x => !System.IO.Path.Exists(System.IO.Path.Combine(folder, x))))
                return folder;
        }
    }

    /// <summary>
    /// Finder's file for a folder's view settings, which it writes into any folder the user opens, such as a
    /// <c>Copies from</c> one. It is not a copy, so it isn't set aside, and it doesn't keep a folder from counting as empty.
    /// </summary>
    private const string FinderFile = ".DS_Store";

    /// <summary>A download's temporary part (<c>.healdan-*.part</c>), which the resume sweeps.</summary>
    private static bool IsPart(string name) =>
        name.StartsWith(".healdan-", StringComparison.Ordinal) && name.EndsWith(".part", StringComparison.Ordinal);

    /// <summary>
    /// Spec §5.4: moves each set-aside copy whose record names <paramref name="server"/> back into Copies with its record
    /// (<see cref="MoveCopy"/>), from every <c>Copies from</c> folder beside it, and says how many came back and which
    /// stayed. One stays aside while an app's lock or owner file beside it says it's open there, and when Copies already
    /// has something of its name; a move that fails leaves it aside too. Each is tried again at the next start. A folder
    /// left empty is removed, and so is one left holding nothing but Finder's <c>.DS_Store</c>, which goes with it:
    /// nothing else is ever deleted. A record without a server is never brought back.
    /// </summary>
    public BroughtBack BringBack(string server)
    {
        var count = 0;
        var stayed = new List<StayedAside>();
        foreach (var folder in AsideFolders())
        {
            foreach (var record in Records(folder))
            {
                if (!ServerAddress.Same(record.Server, server))
                    continue;
                var name = record.FileName;
                var title = CopyNames.Title(record.DocumentName, record.DocNumber, record.FileVersion, record.ReadOnly);
                var docRef = CopyNames.DocRef(record.DocNumber, record.FileVersion);
                var target = System.IO.Path.Combine(paths.Copies, name);
                if (IsOpen(folder, name))
                    stayed.Add(new StayedAside(title, docRef, folder,
                        "it's open in an app. Close it, and it comes back the next time Healdan Helper starts"));
                else if (System.IO.Path.Exists(target) || System.IO.Path.Exists(RecordPath(target)))
                    stayed.Add(new StayedAside(title, docRef, folder,
                        "Healdan Helper's Copies folder already has a file with its name, so it stays there for you to file"));
                else
                {
                    try
                    {
                        EnsureFolders();
                        MoveCopy(folder, paths.Copies, name);
                        count++;
                    }
                    catch (Exception e) when (e is IOException or UnauthorizedAccessException)
                    {
                        stayed.Add(new StayedAside(title, docRef, folder, e.Message.TrimEnd('.')));
                    }
                }
            }
            try
            {
                var left = Directory.EnumerateFileSystemEntries(folder).ToList();
                if (left.All(entry => System.IO.Path.GetFileName(entry) == FinderFile && File.Exists(entry)))
                {
                    foreach (var entry in left)
                        File.Delete(entry);
                    Directory.Delete(folder);
                }
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                // Removed at a later start instead.
            }
        }
        if (count > 0)
            lock (_hashes)
                _hashes.Clear();
        return new BroughtBack(count, stayed);
    }

    /// <summary>
    /// Spec §5.4: the copies set aside, by server, with the first folder holding each server's, for the menu's line per
    /// server. Only records are read, never a copy, as the menu asks at each rebuild. A record without a server isn't
    /// counted.
    /// </summary>
    public IReadOnlyList<AsideCopies> Aside()
    {
        try
        {
            return AsideFolders()
                .SelectMany(folder => Records(folder).Where(r => r.Server is not null)
                    .Select(r => (Server: ServerAddress.Name(r.Server!), Folder: folder)))
                .GroupBy(c => c.Server, StringComparer.OrdinalIgnoreCase)
                .Select(g => new AsideCopies(g.Key, g.Select(c => c.Folder).Min(StringComparer.Ordinal)!, g.Count()))
                .OrderBy(a => a.Server, StringComparer.Ordinal)
                .ToList();
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return []; // asked again at the next rebuild
        }
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

    /// <summary>Each readable record in <paramref name="folder"/>, in name order, read before anything moves.</summary>
    private static List<CopyRecord> Records(string folder) =>
        Directory.EnumerateFiles(folder, ".*.healdan.json").Order(StringComparer.Ordinal).Select(Read).OfType<CopyRecord>().ToList();

    /// <summary>The folders beside Copies holding copies set aside, in name order.</summary>
    private List<string> AsideFolders() =>
        Directory.Exists(paths.Support)
            ? Directory.EnumerateDirectories(paths.Support, AsidePrefix + "*").Order(StringComparer.Ordinal).ToList()
            : [];

    /// <summary>Whether an app's lock or owner file beside the copy says the app has it open there: LibreOffice's, or Word's, Excel's or PowerPoint's.</summary>
    private static bool IsOpen(string folder, string name) =>
        File.Exists(System.IO.Path.Combine(folder, CopyNames.LibreOfficeLockName(name)))
        || CopyNames.OfficeOwnerNames(name).Any(owner => File.Exists(System.IO.Path.Combine(folder, owner)));

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
