using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Copies;
using Healdan.Helper.Core.Office;
using Healdan.Helper.Core.Platform;
using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Core.Cycle;

/// <summary>
/// The open-save-close cycle of local copies (spec §5.4, §5.5, §5.10). Opening checks the document out, downloads it
/// and opens it in the app. Every save the app writes is sent once it has settled, one at a time and in order, with
/// <c>ifVersion</c> and <c>ifHeld</c>, so it never lands on a version it didn't start from or on a check-out that has
/// ended. Closing checks it in and deletes the copy. Every failure keeps the copy: nothing is deleted that Healdan hasn't
/// confirmed (spec §2.4). What a later step needs is in the copy's record, written before each send, so
/// <see cref="ResumeAsync"/> carries on after a restart.
/// <para>A copy keeps the file name it was opened with while the app has it open (R19); its title comes from the
/// record's <see cref="CopyRecord.FileVersion"/>. Word and Excel take the title from the file name, so their copies are
/// saved under each confirmed version's name, and the session follows (M3.5 spec §5.4, <see cref="RenameAfterSaveAsync"/>).
/// Methods taking a path find the copy by its file name, as every copy is in the one folder. Nothing here logs but
/// <see cref="OfficeAfterSave"/>'s failed calls to Office, and no event carries a token or a file's contents.</para>
/// </summary>
public sealed class CopyCycle : IDisposable
{
    private const string Snapshot = "snapshot";
    private const string NewVersion = "version";

    /// <summary>How long PowerPoint's close and reopen may leave a presentation without its owner file (M3.5 spec §5.5).</summary>
    private static readonly TimeSpan ReopenWait = TimeSpan.FromSeconds(10);

    private readonly HealdanApi _api;
    private readonly CopyStore _store;
    private readonly OfficeApps _apps;
    private readonly string _deviceId;
    private readonly ICycleEvents _events;
    private readonly CycleTimings _timings;
    private readonly OfficeAfterSave _office;

    /// <summary>Guards the sessions and every read-modify-write of a record. Events are raised outside it.</summary>
    private readonly object _gate = new();
    private readonly Dictionary<string, Session> _sessions = new(StringComparer.Ordinal);
    private readonly HashSet<string> _waiting = new(StringComparer.Ordinal);

    /// <summary>The documents being opened now: one open of a document at a time (a double-click sends two links).</summary>
    private readonly HashSet<long> _opening = [];
    private readonly SaveWatcher _saves;
    private readonly CloseWatcher _closes;
    private readonly CancellationTokenSource _stop = new();
    private bool _signInAsked;

    /// <param name="deviceId">
    /// This computer's device id, the one its token signed in with: a hold whose <c>device.id</c> is this one is this
    /// computer's. Names aren't compared, as two computers can share one.
    /// </param>
    /// <param name="timings">The trial's timings by default (<see cref="CycleTimings.Default"/>).</param>
    /// <param name="office">
    /// What the cycle asks of Word, Excel and PowerPoint after a save and after a crash. Without one, it still renames
    /// their copies, but tells no one before a program's first save into Copies and logs nothing.
    /// </param>
    /// <param name="apps">
    /// The apps a copy opens in: a new copy in the one the chooser names for its file, an existing one in the one its record
    /// names. A file no office app edits (spec §5.11, <see cref="FileKinds"/>) opens read-only in the set's
    /// <see cref="DefaultApp"/>, never checked out; without one, every file opens in an office app.
    /// </param>
    public CopyCycle(HealdanApi api, CopyStore store, OfficeApps apps, string deviceId, ICycleEvents events, CycleTimings? timings = null,
        OfficeAfterSave? office = null)
    {
        _api = api;
        _store = store;
        _apps = apps;
        _deviceId = deviceId;
        _events = events;
        _timings = timings ?? CycleTimings.Default;
        _office = office ?? new OfficeAfterSave();
        store.EnsureFolders();
        _saves = new SaveWatcher(store.Folder, _timings, path => Guard(() => OnSettled(path)));
        _closes = new CloseWatcher(_timings, path => Guard(() => Close(path)), path => Guard(() => OnCrashed(path)),
            (path, lockBack) => Guard(() => OnReopened(path, lockBack)));
    }

    /// <summary>The apps copies open in, which the channel's filing names its app from (M3.6 spec §6.2).</summary>
    public OfficeApps Apps => _apps;

    /// <summary>An open copy: its save queue and what the cycle knows about it beyond its record.</summary>
    internal sealed class Session
    {
        public Session(string path, long docNumber, bool readOnly, Func<Session, SaveJob, CancellationToken, Task<SaveOutcome>> send,
            CycleTimings timings, Action<string, bool> waiting, Action<Session, SaveJob, Exception> failed)
        {
            Path = path;
            DocNumber = docNumber;
            ReadOnly = readOnly;
            Queue = new SaveQueue((job, ct) => send(this, job, ct), timings, w => waiting(Path, w), (job, e) => failed(this, job, e));
        }

        /// <summary>
        /// The copy's path. It changes only when Word or Excel renames the copy after a save (<see cref="Rekey"/>), under the gate.
        /// </summary>
        public string Path { get; set; }

        public long DocNumber { get; }

        public bool ReadOnly { get; }

        public SaveQueue Queue { get; }

        public string? HoldBy { get; set; }

        /// <summary>A new document's copy, whose first write is the app adopting it (its starting point), not a save.</summary>
        public bool Adopting { get; set; }

        public TaskCompletionSource Adopted { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

        /// <summary>Save as New Version was pressed: the next save goes up as <c>version</c>.</summary>
        public string? NextKind { get; set; }

        /// <summary>The hash of the newest content queued or sent, so the same bytes aren't queued twice.</summary>
        public string? LastHash { get; set; }

        /// <summary>
        /// The newest save queued or sent has its outcome, confirmed or given up (refused for good, or failed), and
        /// nothing has been queued since.
        /// </summary>
        public bool Answered { get; set; }

        /// <summary>The check-out ended here: nothing more is sent, and the copy is kept.</summary>
        public bool Ended { get; set; }

        public bool Closing { get; set; }

        public bool Crashed { get; set; }

        /// <summary>When the owner file a crash left was last written, for the watch for the program to open it again (M3.5 spec §5.14).</summary>
        public DateTime? CrashWritten { get; set; }

        /// <summary>
        /// The crashed copy's program has it open again: the save-back found its window by its path (M3.5 spec §5.14), and
        /// is saving it back, or will try again.
        /// </summary>
        public bool Reopened { get; set; }

        /// <summary>
        /// The save-back found the window, and is saving it, which takes the program's owner file away until the save is
        /// done.
        /// </summary>
        public bool SavingBack { get; set; }

        /// <summary>The save-back is in <see cref="OfficeAfterSave.SaveRecoveredAsync"/> for this copy, looking or saving.</summary>
        public bool LookingBack { get; set; }

        /// <summary>
        /// Reopen asked for the crashed copy, and its program was started rather than handed the file
        /// (<see cref="Reopen"/>): if the save-back doesn't find the copy, it's opened as last saved.
        /// </summary>
        public bool OpenIfNotFound { get; set; }

        /// <summary>
        /// The program was running when Reopen asked, so it restores little more, if anything, as it may still be
        /// restoring: the save-back looks for two <see cref="CycleTimings.CrashPoll"/>s rather than the whole
        /// <see cref="CycleTimings.RecoveryWait"/>.
        /// </summary>
        public bool LookBriefly { get; set; }

        public List<TaskCompletionSource<CopyState>> Waiters { get; } = [];
    }

    /// <summary>
    /// Opens a document (spec §5.5). It's checked out from this computer, unless the caller can't edit it, someone else
    /// holds it or <paramref name="readOnly"/> asks for read-only; then downloaded, unless this computer kept a copy of
    /// it (after a crash, or with changes Healdan hasn't confirmed), which is reopened instead; then opened in the app.
    /// A <paramref name="version"/> always opens read-only. If the document is checked out to the user on another
    /// computer, <see cref="ICycleEvents.HeldElsewhere"/> asks first and nothing is opened. Opens run in the
    /// background, so failures are reported through the events rather than thrown; a check-out the open took is
    /// released again unless this computer already had a copy of the document.
    /// </summary>
    public Task OpenAsync(long docNumber, bool readOnly, string? version = null) =>
        ReportingAsync(docNumber, () => OpenCoreAsync(docNumber, readOnly || version is not null, version, move: false));

    /// <summary>Edit Here Instead: moves the check-out from the user's other computer to this one, then opens as <see cref="OpenAsync"/>.</summary>
    public Task MoveHereAsync(long docNumber) =>
        ReportingAsync(docNumber, () => OpenCoreAsync(docNumber, false, null, move: true));

    /// <summary>
    /// Save to Healdan and Save as New Document (spec §5.5, §5.6): files <paramref name="tempPath"/> as a new document,
    /// checked out here, and returns the copy the app is to save the open document as. That first write is the copy's
    /// starting point, not a save: the extension says when it's done (<see cref="Adopted"/>), or else it's the first
    /// write that settles. Until then the filed bytes are what Healdan has confirmed, so a restart in between sends
    /// the copy unless it's those bytes (re-review): nothing tells the adoption from a save made while the helper was
    /// down, and the adoption differs from the filed file by the doc ID the extension stamps first, so it goes up as an
    /// identical version rather than a save being lost. With <paramref name="replaces"/>, the original copy's check-out
    /// is released without saving to it, and the original copy is deleted once the app has adopted the new file: its
    /// content went into the new document. A failed filing throws, so the caller can fall back to the app's own save.
    /// </summary>
    /// <param name="app">
    /// The app whose window the new document is saved from, which the copy's record names and the close watcher asks.
    /// LibreOffice by default, as the extension's route is: Settings choose the app for a copy the helper opens, and
    /// say nothing of a window the user already has.
    /// </param>
    public async Task<CopyState> AdoptNewAsync(string tempPath, NewDocument request, string? replaces, IOfficeApp? app = null)
    {
        var filed = await File.ReadAllBytesAsync(tempPath, _stop.Token); // read once: hashed here and sent as it is
        var doc = await _api.FileAsync(filed, Path.GetFileName(tempPath), request with { CheckOut = true }, _stop.Token);
        var (path, _, _) = _store.Create(doc, false, (app ?? _apps.LibreOffice).Name);
        Update(path, r => r with
        {
            CheckedOutHere = true,
            ConfirmedHash = Convert.ToHexString(SHA256.HashData(filed)),
            PendingKind = null,
            PendingHash = null
        });
        var session = Begin(path, doc.DocNumber, false, null, adopting: true);
        if (replaces is not null && Name(replaces) != Name(path))
            Replace(replaces, session.Adopted.Task);
        return StateOf(path);
    }

    /// <summary>Save as New Version (spec §5.5): the copy's next save goes up as <c>kind: version</c>. Without a save to come, the current file goes as one.</summary>
    public void MarkNextVersion(string path)
    {
        Session? s;
        lock (_gate)
        {
            s = Find(path);
            if (s is null || s.ReadOnly || s.Closing || s.Ended)
                return;
            s.NextKind = NewVersion;
        }
        _saves.Touch(s.Path);
    }

    /// <summary>
    /// <c>/v1/saved</c>, which the extension calls once the app has written a save: the copy's state once Healdan has
    /// everything written so far, so the title never stops a version behind (final review I1). A confirmation answers
    /// only when nothing newer is still to go: no save queued after it, no Save as New Version waiting, and the file as
    /// Healdan has it. A copy with nothing on its way answers at once. After <paramref name="max"/>, it answers its
    /// state as it is, with <see cref="CopyState.Pending"/> set while a save is still to go, and the extension asks again.
    /// </summary>
    public async Task<CopyState> WaitForConfirmationAsync(string path, TimeSpan max)
    {
        var waiter = new TaskCompletionSource<CopyState>(TaskCreationOptions.RunContinuationsAsynchronously);
        Session? s;
        lock (_gate)
        {
            s = Find(path);
            s?.Waiters.Add(waiter);
        }
        if (s is null)
            return StateOf(path);
        try
        {
            // Asked once the waiter is in the list, so a confirmation that lands meanwhile either answers it or is seen here.
            if (!PendingOf(s))
                return StateOf(s.Path, pending: false);
            return await waiter.Task.WaitAsync(max, _stop.Token);
        }
        catch (Exception e) when (e is TimeoutException or OperationCanceledException)
        {
            return StateOf(path);
        }
        finally
        {
            lock (_gate)
                s.Waiters.Remove(waiter);
        }
    }

    /// <summary>
    /// An app's close event for the copy's window: checks it in (or releases it) and deletes the copy, in the background. A
    /// read-only copy opened in the default app has no session, as nothing else says when its window closes, so its report
    /// deletes it now, as the next start would (M3.6 spec §5.5); one with changes is kept. False when the cycle has nothing
    /// to do with the path, so the channel can log a report it dropped.
    /// </summary>
    public Task<bool> ClosedAsync(string path)
    {
        bool known;
        lock (_gate)
            known = Find(path) is not null;
        if (known)
        {
            Close(path);
            return Task.FromResult(true);
        }
        if (Record(path) is not { ReadOnly: true } record || !_apps.IsOtherFiles(_apps.Of(record)))
            return Task.FromResult(false);
        if (!_store.HasUnconfirmedChanges(path))
            _store.Delete(path);
        return Task.FromResult(true);
    }

    /// <summary>
    /// At start: for each copy left from before, an open one is watched again and its unconfirmed changes resent; a
    /// crashed one is offered (<see cref="ICycleEvents.Crashed"/>) and its changes resent; one whose window closed
    /// meanwhile is checked in and deleted; and a closed read-only copy is deleted. Saves wait for the server as usual.
    /// </summary>
    public Task ResumeAsync()
    {
        // A download the helper stopped in the middle of leaves its temporary file; nothing else makes these.
        foreach (var part in Directory.EnumerateFiles(_store.Folder, ".healdan-*.part"))
        {
            try
            {
                File.Delete(part);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                // Tried again at the next start.
            }
        }
        foreach (var (listed, listedRecord) in _store.All())
        {
            lock (_gate)
                if (Find(listed) is not null)
                    continue;
            var app = _apps.Of(listedRecord); // each copy with the app that has it
            var (path, record) = UnfinishedRename(listed, listedRecord, app) ?? (listed, listedRecord);
            if (!File.Exists(path))
            {
                // A download or adoption that never finished: nothing to send or lose. Release a check-out it took,
                // as a failed open does, and drop the record.
                if (record.CheckedOutHere && !record.ReadOnly && !app.LockExists(path))
                {
                    Begin(path, record.DocNumber, false, null, watchClose: false);
                    Close(path);
                }
                else if (!record.CheckedOutHere)
                    _store.Delete(path);
                continue;
            }
            var unconfirmed = _store.HasUnconfirmedChanges(path);
            if (_apps.IsOtherFiles(app) && record.ReadOnly)
            {
                // Opened in the default app, which keeps no lock file, so nothing says when it closed; its bytes
                // are Healdan's, so the copy goes now (spec §5.11). One that changed is kept, as any read-only copy is.
                if (!unconfirmed)
                    _store.Delete(path);
                continue;
            }
            if (record.ReadOnly)
            {
                if (unconfirmed)
                    continue; // not a copy the helper sends, nor one it may delete
                if (!SafeIsRunning(app, path))
                    _store.Delete(path);
                else
                    Begin(path, record.DocNumber, true, null, appSeenRunning: true);
                continue;
            }
            if (!record.CheckedOutHere)
            {
                if (unconfirmed)
                    Raise(ev => ev.CheckoutEnded(path));
                else if (!app.LockExists(path))
                    _store.Delete(path);
                continue;
            }

            var running = SafeIsRunning(app, path);
            var locked = app.LockExists(path);
            var s = Begin(path, record.DocNumber, false, null, appSeenRunning: running, watchClose: !locked || running);
            if (!locked)
            {
                Close(path, record.PendingKind); // its window closed while the helper wasn't running
                continue;
            }
            if (!running)
            {
                s.Crashed = true;
                WatchForRecovery(s);
                Raise(ev => ev.Crashed(path));
            }
            if (unconfirmed && TryRead(path) is { } content)
                Enqueue(s, new SaveJob { Content = content.Bytes, Hash = content.Hash, Kind = record.PendingKind ?? Snapshot });
        }
        return Task.CompletedTask;
    }

    /// <summary>
    /// <c>/v1/adopted</c> (final review C1): the extension has just saved the window as a new document's copy, and the
    /// window takes no input until this answers. The file as it is now is the copy's starting point, so the user's
    /// next write is a save, however soon it comes. Without this call (an older extension), the first write that
    /// settles is the starting point, and anything that differs from it later is a save.
    /// </summary>
    public CopyState Adopted(string path) => Adopted(path, changedSinceFiling: false);

    /// <summary>
    /// The app has saved a new document's window as its copy (<see cref="AdoptNewAsync"/>). Unless
    /// <paramref name="changedSinceFiling"/>, the file as it is now is the copy's starting point, as for <c>/v1/adopted</c>.
    /// With it, the window's content changed between the bytes being filed and its save as the copy: someone typed while
    /// the filing was in flight (M3.5 spec §5.5 step 8, Review Focus 1). The filed bytes then stay the starting point, and
    /// the copy goes up as a save straight away, even when its write has settled meanwhile and been taken for the starting
    /// point: an identical version at worst, never an edit folded into the copy unsent. A copy whose file isn't there yet,
    /// or is being written, goes up once its write settles.
    /// </summary>
    public CopyState Adopted(string path, bool changedSinceFiling)
    {
        Session? s;
        lock (_gate)
            s = Find(path);
        if (s is null)
            return StateOf(path);
        if (changedSinceFiling)
            return AdoptedChanged(s);
        string hash;
        try
        {
            hash = HashOf(s.Path);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return StateOf(s.Path); // not written yet: the first write that settles is the starting point
        }
        bool adopted;
        lock (_gate)
        {
            adopted = s.Adopting && !s.Closing;
            if (adopted)
            {
                s.Adopting = false;
                Update(s.Path, r => r with { ConfirmedHash = hash });
            }
        }
        if (adopted)
            s.Adopted.TrySetResult();
        return StateOf(s.Path);
    }

    /// <summary><see cref="Adopted(string, bool)"/> for a window that changed while it was being filed: its copy is sent.</summary>
    private CopyState AdoptedChanged(Session s)
    {
        var content = TryRead(s.Path);
        lock (_gate)
        {
            var wasAdopting = s.Adopting;
            // Ended even for a copy whose window has closed meanwhile, so the close's check-in sends what it wrote rather
            // than taking it for the starting point.
            s.Adopting = false;
            if (content is { } c && !s.Closing && !s.Ended && Record(s.Path) is { } record && !SameHash(c.Hash, s.LastHash)
                && !(wasAdopting && SameHash(c.Hash, record.ConfirmedHash))) // exactly the filed bytes: Healdan has them
            {
                var kind = s.NextKind;
                s.NextKind = null;
                Enqueue(s, new SaveJob { Content = c.Bytes, Hash = c.Hash, Kind = kind ?? Snapshot });
            }
        }
        s.Adopted.TrySetResult();
        if (content is null && File.Exists(s.Path))
            _saves.Touch(s.Path); // being written: it settles into a save, as the filed bytes are its starting point
        return StateOf(s.Path);
    }

    /// <summary>
    /// Spec §5.4, for the menu's Not Yet in Healdan: the copies with changes Healdan hasn't confirmed that the cycle no
    /// longer looks after, as their check-out ended or Healdan refused them, and their window has closed. The helper
    /// never deletes one: each stays listed until the user files it with Save as New Document, or sends it by opening
    /// the document again while it can still take the changes.
    /// </summary>
    public IReadOnlyList<CopyState> KeptCopies()
    {
        var kept = new List<CopyState>();
        foreach (var (path, record) in _store.All())
        {
            lock (_gate)
                if (Find(path) is not null)
                    continue;
            // The menu asks at each rebuild, on the UI thread: a copy is read again only once its size or time has
            // changed (re-review), so the default app's copies, which stay until the next start, aren't read every time.
            if (File.Exists(path) && _store.HasUnconfirmedChanges(path, cached: true))
                kept.Add(StateOf(path, record, null));
        }
        return kept;
    }

    /// <summary>
    /// The menu's click on a kept copy (<see cref="KeptCopies"/>): opens it as it is, in the app it was opened in, with
    /// no check-out and no download, so Save as New Document can file it. Nothing of it is sent.
    /// </summary>
    public void OpenKept(string path)
    {
        if (Record(path) is not { } record || !File.Exists(path))
            throw new FileNotFoundException("That copy isn't on this computer any more.");
        var app = _apps.Of(record);
        if (_apps.IsOtherFiles(app))
        {
            if (DefaultApp.Refuses(path))
                throw new IOException(Refused(record.DocumentName, path));
            Quarantine.Mark(path);
        }
        app.Open(path);
    }

    /// <summary>
    /// A link's open (spec §5.3), as <see cref="OpenAsync"/>, but only of spec §5.11's kinds and PDFs (final review C2,
    /// M3.6 spec §5.1): a page can make a link to any document, and anything else opens only from the Open window, where
    /// the user chose it.
    /// </summary>
    public Task OpenLinkAsync(long docNumber, bool readOnly) =>
        ReportingAsync(docNumber, () => OpenCoreAsync(docNumber, readOnly, null, move: false, link: true));

    /// <summary>The copy's state for the channel, or an unknown one.</summary>
    public CopyState StateOf(string path) => StateOf(path, pending: null);

    /// <summary>
    /// The copy the cycle looks after for <paramref name="copy"/> now: at its path, or, once Word or Excel has renamed it
    /// (M3.5 spec §5.4), the open copy of the same document; null when there's neither. A window or a menu built before a
    /// rename holds the old path, which no file has any more.
    /// </summary>
    public CopyState? Current(CopyState copy)
    {
        Session? s;
        lock (_gate)
            s = Find(copy.Path)
                ?? _sessions.Values.FirstOrDefault(x => x.DocNumber == copy.DocNumber && x.ReadOnly == copy.ReadOnly && !x.Closing);
        return s is not null && StateOf(s.Path, pending: false) is { Known: true } state ? state : null;
    }

    /// <summary>Whether the app went away with the copy open, and the cycle is still waiting for it to be reopened or checked in.</summary>
    public bool IsCrashed(string path)
    {
        lock (_gate)
            return Find(path) is { Crashed: true };
    }

    /// <summary>
    /// Whether a crashed copy's program has it open again (M3.5 spec §5.14) while the cycle still counts it as crashed:
    /// its owner file was written since the crash, or the save-back found its window by its path, and the owner file is
    /// there now; or the save-back is in its own look after the owner file came back, or saving the window it found (the
    /// program removes the owner file while it saves a window over the copy). Checked in then, the copy would be deleted
    /// from under that window. Once the save-back gives up, a window closed before it found it no longer counts. The
    /// program merely running doesn't count, as it may not have the copy at all.
    /// </summary>
    public bool IsOpenAgain(string path)
    {
        string? reopened = null;
        lock (_gate)
            if (Find(path) is { Crashed: true, Reopened: true } s)
            {
                if (s.SavingBack)
                    return true;
                reopened = s.Path;
            }
        return reopened is not null && AppOf(reopened).LockExists(reopened);
    }

    /// <summary>The copy's state; <paramref name="pending"/> null works it out, which reads the file.</summary>
    private CopyState StateOf(string path, bool? pending)
    {
        Session? s;
        string? holdBy;
        lock (_gate)
        {
            s = Find(path);
            holdBy = s?.HoldBy;
            if (s is not null)
                path = s.Path;
        }
        var record = Record(path);
        if (record is null)
            return CopyState.Unknown(path);
        return StateOf(path, record, holdBy) with { Pending = pending ?? (s is not null && PendingOf(s)) };
    }

    /// <summary>
    /// The copies the cycle is looking after, for the menu: open in the app, crashed, or with a close still going up.
    /// A copy leaves the list once its window has closed and its check-in, release or refusal is done.
    /// </summary>
    public IReadOnlyList<CopyState> OpenCopies()
    {
        List<string> paths;
        lock (_gate)
            paths = [.. _sessions.Values.Select(s => s.Path)];
        return [.. paths.Select(p => StateOf(p, pending: false)).Where(state => state.Known)];
    }

    /// <summary>
    /// How many open copies have changes on their way to Healdan: a save queued, going up or waiting to retry, a Save
    /// as New Version still to send, or a write the app made that hasn't settled into a save yet. A copy whose changes
    /// can't go up (its check-out ended), or whose last save Healdan refused for good, isn't counted: nothing more will
    /// be sent for it. Sign Out waits for this to be 0 before it revokes the token.
    /// </summary>
    public int Unsent()
    {
        List<Session> open;
        lock (_gate)
            open = [.. _sessions.Values];
        return open.Count(s => HasUnsent(s, sendsCount: true));
    }

    /// <summary>
    /// The hash of the copy's save waiting for the one going up, or null. For the tests, which wait on it rather than
    /// guess how long a settle takes.
    /// </summary>
    internal string? QueuedHash(string path)
    {
        Session? s;
        lock (_gate)
            s = Find(path);
        return s?.Queue.Queued?.Hash;
    }

    /// <summary>The copy's <see cref="CopyState.Pending"/>: a save answered counts as gone even while its job finishes.</summary>
    private bool PendingOf(Session s) => HasUnsent(s, sendsCount: false);

    /// <summary>
    /// Whether the copy has changes on their way to Healdan (<see cref="Unsent"/>). With <paramref name="sendsCount"/>,
    /// a job still running counts even once its save is answered, as a check-in's release can follow it, and Sign Out
    /// mustn't revoke the token under it; without, an answered save counts as gone, so <c>/v1/saved</c> can answer the
    /// moment Healdan has the content, or has refused it.
    /// </summary>
    private bool HasUnsent(Session s, bool sendsCount)
    {
        string? last;
        bool version, answered;
        lock (_gate)
        {
            if (s.ReadOnly || s.Ended)
                return false;
            (last, version, answered) = (s.LastHash, s.NextKind is not null, s.Answered);
        }
        if (version || s.Queue.Queued is not null || (!s.Queue.Idle && (sendsCount || !answered)))
            return true;
        if (s.Closing || Record(s.Path) is not { } record || !File.Exists(s.Path))
            return false;
        string hash;
        try
        {
            hash = HashOf(s.Path);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return true; // being written: it settles into a save
        }
        return !SameHash(hash, record.ConfirmedHash) && !SameHash(hash, last);
    }

    /// <summary>Retries every waiting save now, as once the user has signed in again.</summary>
    public void RetryNow()
    {
        List<Session> sessions;
        lock (_gate)
            sessions = [.. _sessions.Values];
        foreach (var s in sessions)
            s.Queue.RetryNow();
    }

    /// <summary>Stops watching and sending. Copies and records stay as they are, for <see cref="ResumeAsync"/>.</summary>
    public void Dispose()
    {
        if (_stop.IsCancellationRequested)
            return;
        _stop.Cancel();
        _saves.Dispose();
        _closes.Dispose();
        lock (_gate)
        {
            foreach (var s in _sessions.Values)
                s.Queue.Dispose();
            _sessions.Clear();
        }
    }

    // --- Opening -------------------------------------------------------------------------------------------------

    private async Task ReportingAsync(long docNumber, Func<Task> open)
    {
        lock (_gate)
            if (!_opening.Add(docNumber))
                return; // opening already (final review M2): that open brings its window up, and nothing is downloaded twice
        try
        {
            await open();
        }
        catch (ApiException e) when (e.Status == 401)
        {
            // The user asked for this open, so they hear about it every time, unlike a save waiting in the background.
            lock (_gate)
                _signInAsked = true;
            Raise(ev => ev.SignInNeeded());
        }
        catch (ApiException e)
        {
            Raise(ev => ev.Error($"#{docNumber} couldn't be opened: {e.Detail}"));
        }
        catch (OperationCanceledException) when (_stop.IsCancellationRequested)
        {
            // Shutting down.
        }
        catch (Exception e)
        {
            Raise(ev => ev.Error($"#{docNumber} couldn't be opened: {e.Message}"));
        }
        finally
        {
            lock (_gate)
                _opening.Remove(docNumber);
        }
    }

    private async Task OpenCoreAsync(long n, bool readOnly, string? version, bool move, bool link = false)
    {
        var ct = _stop.Token;
        if (!readOnly && !move)
        {
            Session? live;
            lock (_gate)
                live = _sessions.Values.FirstOrDefault(s => s.DocNumber == n && !s.ReadOnly && !s.Ended);
            if (live is not null)
            {
                Reopen(live);
                return;
            }
        }

        var hadCopy = _store.All().Any(c => c.Record.DocNumber == n && c.Record.CheckedOutHere);
        var doc = await _api.DocumentAsync(n, ct);
        var app = _apps.For(doc.Name);
        if (link && FileKinds.OfFile(doc.Name) is null && !PdfFiles.Is(doc.Name))
        {
            Raise(ev => ev.Error($"{doc.Name} wasn't opened: a link opens only documents, spreadsheets, presentations, drawings "
                + "and PDFs. Open it from the web page, or from Open from Healdan."));
            return;
        }
        if (_apps.IsOtherFiles(app))
        {
            await OpenOtherAsync(doc, version, app, ct);
            return;
        }
        var checkedOut = false;
        var holdBy = doc.Hold?.By?.Name;
        if (!readOnly)
        {
            if (move || doc.CanEdit || doc.Hold?.Device is not null)
            {
                try
                {
                    doc = await _api.CheckOutAsync(n, app.CheckOutName(doc.Name), move, ct);
                    checkedOut = true;
                }
                catch (ApiException e) when (e.Code == "held-elsewhere")
                {
                    var problem = ProblemOf(e);
                    Raise(ev => ev.HeldElsewhere(null, n, problem?.Hold?.Device?.Name ?? "another computer",
                        problem?.Hold?.Device?.App, problem?.LastSaved));
                    return;
                }
                catch (ApiException e) when (e.Code is "checked-out" or "locked")
                {
                    readOnly = true;
                    holdBy = ProblemOf(e)?.Hold?.By?.Name ?? holdBy;
                }
                catch (ApiException e) when (e.Status == 403 || e.Code == "refused")
                {
                    readOnly = true;
                }
            }
            else
                readOnly = true;
        }

        string path;
        bool fresh;
        try
        {
            (path, fresh, readOnly) = await PrepareCopyAsync(doc, readOnly, version, checkedOut, app.Name, ct);
        }
        catch (Exception e)
        {
            // Shutting down mid-download releases too, within a few seconds; if that doesn't get through, the
            // record the download left makes ResumeAsync release it.
            var shuttingDown = e is OperationCanceledException && _stop.IsCancellationRequested;
            if (checkedOut && !hadCopy && await ReleaseQuietlyAsync(n, shuttingDown) && shuttingDown)
                DropRecordsWithoutFile(n); // released: nothing is left for ResumeAsync to do
            throw;
        }

        var session = Begin(path, n, readOnly, readOnly ? holdBy : null);
        try
        {
            AppOf(path, app).Open(path); // a copy this computer kept is still the app's that opened it
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            Forget(session);
            if (fresh)
                _store.Delete(path); // just downloaded: Healdan has every byte of it
            if (checkedOut && !hadCopy)
                await ReleaseQuietlyAsync(n, shuttingDown: false);
            throw;
        }
        if (!fresh && !readOnly)
            CarryOnWithKeptCopy(session, doc);
    }

    /// <summary>
    /// A kept copy was reopened for editing. If Healdan has newer content than the version the copy started from, and
    /// no save of the copy's is still unanswered (which may be what moved it on), its changes must not go over that:
    /// it's open as it is, but marked ended, and <see cref="ICycleEvents.CheckoutEnded"/> lets the app offer Save as
    /// New Document. A check-out this open took stays recorded, so closing the copy releases it. Otherwise the
    /// copy's unconfirmed changes are sent now, as <see cref="ResumeAsync"/> sends them.
    /// </summary>
    private void CarryOnWithKeptCopy(Session s, DocumentView doc)
    {
        if (Record(s.Path) is not { } record)
            return;
        if (record.FileVersion != doc.FileVersion && record.PendingHash is null)
        {
            EndCheckout(s, heldHere: record.CheckedOutHere);
            return;
        }
        if (_store.HasUnconfirmedChanges(s.Path) && TryRead(s.Path) is { } content)
            Enqueue(s, new SaveJob { Content = content.Bytes, Hash = content.Hash, Kind = record.PendingKind ?? Snapshot });
    }

    /// <summary>
    /// The copy to open, whether it was downloaded now (so nothing but Healdan's bytes are in it), and whether it's
    /// read-only. A copy this computer kept is reopened, never downloaded over: one with unconfirmed changes (from
    /// <see cref="CopyStore.Create"/>), or one checked out here whose content is still the document's.
    /// </summary>
    private async Task<(string Path, bool Fresh, bool ReadOnly)> PrepareCopyAsync(DocumentView doc, bool readOnly, string? version,
        bool checkedOut, string appName, CancellationToken ct)
    {
        if (!readOnly)
        {
            foreach (var (keptPath, kept) in _store.All())
            {
                if (kept.DocNumber != doc.DocNumber || kept.ReadOnly || !kept.CheckedOutHere || kept.FileVersion != doc.FileVersion
                    || !File.Exists(keptPath) || _store.HasUnconfirmedChanges(keptPath))
                    continue;
                lock (_gate)
                    if (Find(keptPath) is not null)
                        continue;
                return (keptPath, false, false);
            }
        }

        var forCopy = version is null ? doc : doc with { Version = version, FileVersion = version };
        // A confirmed copy already at the target path holds this same version's content: its hash stands until the
        // download replaces it, so a helper stopped mid-way never takes it for unsent changes.
        var previous = Record(Path.Combine(_store.Folder, CopyNames.FileName(forCopy.Name, forCopy.DocNumber, forCopy.FileVersion, readOnly)));
        var (path, record, existing) = _store.Create(forCopy, readOnly, appName);
        if (existing)
        {
            if (checkedOut)
                Update(path, r => r with { CheckedOutHere = true });
            return (path, false, record.ReadOnly);
        }
        // The record says the check-out is held before the download starts, so if the helper stops mid-way, the record
        // (with no file) tells ResumeAsync to release it.
        var hadFile = File.Exists(path);
        Update(path, r => r with
        {
            CheckedOutHere = checkedOut,
            ConfirmedHash = hadFile ? previous?.ConfirmedHash : null,
            PendingKind = null,
            PendingHash = null
        });
        try
        {
            await _api.DownloadAsync(doc.DocNumber, path, version, ct);
            if (readOnly && !OperatingSystem.IsWindows())
                File.SetUnixFileMode(path, UnixFileMode.UserRead);
            var hash = HashOf(path);
            Update(path, r => r with { ConfirmedHash = hash });
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested && !hadFile && checkedOut)
        {
            throw; // keep the record: there's no file, and ResumeAsync releases its check-out
        }
        catch
        {
            _store.Delete(path); // the record just written, and a file Healdan had confirmed if one was there
            throw;
        }
        return (path, true, readOnly);
    }

    /// <summary>
    /// Spec §5.11: a file no office app edits, such as a PDF, opens read-only in the system's default app, with no
    /// check-out. That app keeps no lock file, so nothing watches the copy; an app's close report deletes it
    /// (<see cref="ClosedAsync"/>), or else <see cref="ResumeAsync"/> at the next start, as it's read-only and holds only
    /// Healdan's bytes. As anyone who can add files to a matter could have put it there (final review C2), a kind that
    /// can run a program is never downloaded or handed over (<see cref="DefaultApp.Refuses"/>), and the copy carries
    /// macOS's quarantine, so Gatekeeper checks it.
    /// </summary>
    private async Task OpenOtherAsync(DocumentView doc, string? version, IOfficeApp other, CancellationToken ct)
    {
        var name = CopyNames.FileName(doc.Name, doc.DocNumber, version ?? doc.FileVersion, readOnly: true);
        if (DefaultApp.Refuses(name))
        {
            Raise(ev => ev.Error(Refused(doc.Name, name)));
            return;
        }
        var (path, fresh, _) = await PrepareCopyAsync(doc, readOnly: true, version, checkedOut: false, other.Name, ct);
        try
        {
            if (DefaultApp.Refuses(path))
                throw new IOException(Refused(doc.Name, path)); // a copy kept from before, under another name
            Quarantine.Mark(path);
            other.Open(path);
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            if (fresh)
                _store.Delete(path); // just downloaded: Healdan has every byte of it
            throw;
        }
    }

    private static string Refused(string documentName, string fileName) =>
        $"{documentName} wasn't opened: Healdan Helper never opens {Path.GetExtension(fileName)} files, as they can run "
        + "programs on this computer. Download it from the web page if you need it.";

    private void Reopen(Session live)
    {
        bool closing;
        var start = false;
        var app = AppOf(live.Path);
        var running = SafeIsRunning(app, live.Path);
        lock (_gate)
        {
            closing = live.Closing;
            if (!closing && live.Crashed && OfficeAfterSave.Driver(app, live.Path) is null)
            {
                live.Crashed = false;
                _closes.Watch(live.Path, app);
            }
            else if (!closing && live.Crashed)
            {
                // Word's, Excel's or PowerPoint's (M3.5 spec §5.14): handed the file, Word opened it again beside the window
                // it had recovered, with the same name, so neither could be saved back (the owner's walkthrough). The
                // program is started instead, which restores what it recovered, and the save-back looks for the copy at
                // once; if the program doesn't have it, it's opened as last saved.
                start = true;
                live.OpenIfNotFound = true;
                live.LookBriefly = running;
            }
        }
        if (closing)
            Raise(ev => ev.Error($"#{live.DocNumber} is still being checked in. Open it again once that's done."));
        else if (start)
        {
            app.Start(live.Path);
            OnReopened(live.Path, lockBack: false);
        }
        else
            app.Open(live.Path);
    }

    /// <summary>
    /// Releases a check-out the failed open took moments ago; true when it went through. While shutting down it gets a
    /// few seconds of its own.
    /// </summary>
    private async Task<bool> ReleaseQuietlyAsync(long n, bool shuttingDown)
    {
        using var shutdown = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        try
        {
            await _api.ReleaseAsync(n, shuttingDown ? shutdown.Token : _stop.Token);
            return true;
        }
        catch (Exception e) when (e is ApiException || IsTransient(e))
        {
            if (!shuttingDown)
                Raise(ev => ev.Error($"#{n} is still checked out to you: releasing it failed."));
            return false;
        }
    }

    /// <summary>Drops the document's records that have no file: nothing is in them to lose.</summary>
    private void DropRecordsWithoutFile(long n)
    {
        foreach (var (path, record) in _store.All())
            if (record.DocNumber == n && !File.Exists(path))
                lock (_gate)
                    if (Find(path) is null)
                        _store.Delete(path);
    }

    /// <summary>Starts a session for a copy and watches it; an ended session at the same path is taken over.</summary>
    private Session Begin(string path, long n, bool readOnly, string? holdBy, bool adopting = false, bool appSeenRunning = false,
        bool watchClose = true)
    {
        Session s;
        lock (_gate)
        {
            if (Find(path) is { } old && old.ReadOnly == readOnly && !old.Closing)
                s = old;
            else
            {
                s = new Session(path, n, readOnly, SendAsync, _timings, SetWaiting, SaveFailed);
                _sessions[Name(path)] = s;
            }
            (s.HoldBy, s.Adopting, s.Ended, s.Crashed, s.Reopened, s.SavingBack, s.NextKind) = (holdBy, adopting, false, false, false, false, null);
            (s.OpenIfNotFound, s.LookBriefly) = (false, false);
        }
        if (!readOnly)
            _saves.Watch(path);
        if (watchClose)
            _closes.Watch(path, AppOf(path), appSeenRunning);
        return s;
    }

    private void Forget(Session s)
    {
        int? waiting = null;
        lock (_gate)
        {
            if (_sessions.TryGetValue(Name(s.Path), out var current) && current == s)
                _sessions.Remove(Name(s.Path));
            if (_waiting.Remove(s.Path))
                waiting = _waiting.Count;
        }
        _saves.Unwatch(s.Path);
        _closes.Unwatch(s.Path);
        s.Queue.Dispose();
        if (waiting is { } count)
            Raise(ev => ev.Waiting(count));
    }

    // --- Saving --------------------------------------------------------------------------------------------------

    /// <summary>A copy's file has settled after a change: queue it, unless it's what was queued or confirmed already.</summary>
    private void OnSettled(string path)
    {
        Session? s;
        lock (_gate)
            s = Find(path);
        if (s is null || s.ReadOnly || s.Closing)
            return;
        if (TryRead(s.Path) is not { } content)
        {
            if (File.Exists(s.Path))
                _saves.Touch(s.Path); // it changed while being read: let it settle again
            return;
        }

        bool ended = false, queued = false, adopted = false;
        lock (_gate)
        {
            var record = Record(s.Path);
            if (record is null || s.Closing)
                return;
            if (s.Adopting)
            {
                // No /v1/adopted came first (an older extension): the app's adoption of the new document's copy is
                // the first write to settle, its starting point, not a save (spec §5.6). Content that differs from it
                // later is a save.
                s.Adopting = false;
                Update(s.Path, r => r with { ConfirmedHash = content.Hash });
                adopted = true;
            }
            else
            {
                var kind = s.NextKind;
                var baseline = s.Queue.Idle ? record.ConfirmedHash : s.LastHash;
                if (s.Ended)
                    ended = true; // kept, not sent: the check-out ended here
                else if (kind is null && SameHash(content.Hash, baseline))
                {
                    // Nothing new to send: answered below once nothing else is on its way.
                }
                else
                {
                    s.NextKind = null;
                    Enqueue(s, new SaveJob { Content = content.Bytes, Hash = content.Hash, Kind = kind ?? Snapshot });
                    queued = true;
                }
            }
        }
        if (adopted)
            s.Adopted.TrySetResult();
        if (ended)
            CompleteWaiters(s, StateOf(s.Path, pending: false));
        else if (!queued)
            SettleWaiters(s);
    }

    /// <summary>
    /// Queues a save; its kind is written to the record before it can be sent, so a restart knows what was waiting. The
    /// kind is the one the queue settles on, as a save replacing a waiting version becomes one.
    /// </summary>
    private void Enqueue(Session s, SaveJob job)
    {
        lock (_gate)
        {
            s.LastHash = job.Hash;
            s.Answered = false;
            // Written while the queue can't hand the job to the send yet, so the send's first read of the record comes
            // after this write (Windows can refuse a read while the record file is being replaced). The write takes
            // the gate, already held here, so it waits for nobody who holds the queue's lock.
            s.Queue.Enqueue(job, job.Content is null ? null : j => Update(s.Path, r => r with { PendingKind = j.Kind }));
        }
    }

    /// <summary>Sends a job once. A failure it doesn't expect is thrown to the queue, which tells <see cref="SaveFailed"/>.</summary>
    private async Task<SaveOutcome> SendAsync(Session s, SaveJob job, CancellationToken ct)
    {
        try
        {
            if (job.ReadAtSend && !TakeClosedFile(s, job))
                return SaveOutcome.Retry; // the file is there but can't be read yet
            if (job.ReleaseOnly || (job.CheckIn && job.Content is null))
                return await ReleaseAsync(s, job, ct);
            var record = Record(s.Path);
            if (record is null)
                return SaveOutcome.Done; // the copy is gone
            if (job.CheckIn && job.SaveId is null && job.Kind == Snapshot && SameHash(job.Hash, record.ConfirmedHash))
                return await ReleaseAsync(s, job, ct); // nothing new to check in
            return await SaveAsync(s, job, record, ct);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception e) when (IsTransient(e))
        {
            return SaveOutcome.Retry;
        }
    }

    private async Task<SaveOutcome> SaveAsync(Session s, SaveJob job, CopyRecord record, CancellationToken ct)
    {
        if (job.SaveId is null)
        {
            if (record.LastSaveId is not null && record.PendingHash is not null)
            {
                // A save went up before (and before a restart) whose answer never came: repeat its saveId, so the
                // server doesn't store it twice nor refuse it as version-moved (Review Focus 1, 5).
                job.SaveId = record.LastSaveId;
                // Repeated as the kind it went as. A version waiting behind a snapshot goes after it, even with the
                // same bytes; a snapshot behind a version needs nothing more unless its bytes are newer.
                job.RepeatKind = record.SentKind ?? job.Kind;
                job.Uncertain = !SameHash(job.Hash, record.PendingHash) || (job.Kind == NewVersion && job.RepeatKind != NewVersion);
            }
            else
            {
                job.SaveId = Guid.NewGuid().ToString();
                // Written down before it's sent, so a retry after a crash repeats it. A save queued behind it keeps
                // its kind as the newest still to go.
                record = Update(s.Path, r => r with
                {
                    LastSaveId = job.SaveId,
                    PendingHash = job.Hash,
                    SentKind = job.Kind,
                    PendingKind = s.Queue.Queued?.Kind ?? job.Kind
                }) ?? record;
            }
            job.IfVersion = record.Version;
        }

        // A repeat with a new save to follow keeps the check-out, whatever this job is: the new save checks it in.
        var request = new SaveRequest(job.RepeatKind ?? job.Kind, !job.CheckIn || job.Uncertain, record.DocumentName,
            job.IfVersion!, true, job.SaveId);
        DocumentView doc;
        try
        {
            doc = await _api.SaveAsync(record.DocNumber, job.Content!, record.DocumentName, request, ct);
        }
        catch (ApiException e)
        {
            return await RefusedAsync(s, job, record, e, ct);
        }

        if (job.Uncertain)
        {
            // The answer doesn't say which bytes the server kept under that saveId, or it went as a snapshot while this
            // is a version: send these again, as a new save of this job's kind.
            var moved = Update(s.Path, r => r with
            {
                DocumentName = doc.Name, // renamed meanwhile in the web page: the resend keeps the new name
                Version = doc.Version,
                FileVersion = doc.FileVersion,
                PendingHash = null
            }) ?? record;
            (job.Uncertain, job.SaveId, job.Resent, job.RepeatKind) = (false, null, false, null);
            return await SaveAsync(s, job, moved, ct);
        }

        var confirmed = Confirm(s, job, doc);
        if (job.CheckIn && confirmed && HoldIsHere(doc.Hold))
            return await ReleaseAsync(s, job, ct); // a repeat of a save that kept it checked out to us: release it now
        if (job.CheckIn && confirmed)
            Finish(s);
        else if (confirmed)
            await RenameAfterSaveAsync(s); // Word's and Excel's titles come from the file name (M3.5 spec §5.4)
        return SaveOutcome.Done;
    }

    /// <summary>
    /// For a close's check-in: takes the file's bytes, or the waiting save's when the file is gone. False when the file
    /// is there but can't be read (or is still changing), to try again later. A copy that closed before the app's
    /// adoption had settled takes that write as its starting point, not a save.
    /// </summary>
    private bool TakeClosedFile(Session s, SaveJob job)
    {
        if (TryRead(s.Path) is { } content)
            (job.Content, job.Hash) = (content.Bytes, content.Hash);
        else if (File.Exists(s.Path))
            return false;
        else
            (job.Content, job.Hash) = (job.FallbackContent, job.FallbackHash);
        job.ReadAtSend = false;
        lock (_gate)
        {
            if (s.Adopting && job.Content is not null)
            {
                s.Adopting = false;
                Update(s.Path, r => r with { ConfirmedHash = job.Hash });
                s.Adopted.TrySetResult();
            }
        }
        return true;
    }

    /// <summary>
    /// Records the confirmed save and says so; false when the copy has gone meanwhile. <c>/v1/saved</c>'s waiters are
    /// answered only once nothing newer is still to go (final review I1).
    /// </summary>
    private bool Confirm(Session s, SaveJob job, DocumentView doc)
    {
        CopyRecord? record;
        lock (_gate)
        {
            record = Update(s.Path, r => r with
            {
                DocumentName = doc.Name,
                Version = doc.Version,
                FileVersion = doc.FileVersion,
                ConfirmedHash = job.Hash,
                PendingHash = null,
                PendingKind = s.Queue.Queued?.Kind,
                // A repeated save's answer shows the hold as it is now, which may be another computer's.
                CheckedOutHere = !job.CheckIn || HoldIsHere(doc.Hold)
            });
            _signInAsked = false;
        }
        if (record is null)
            return false;
        Answer(s, job);
        var state = StateOf(s.Path, record, s.HoldBy);
        Raise(ev => ev.Confirmed(s.Path, state));
        return true;
    }

    private async Task<SaveOutcome> RefusedAsync(Session s, SaveJob job, CopyRecord record, ApiException e, CancellationToken ct)
    {
        if (e.Status == 401)
        {
            AskSignIn();
            return SaveOutcome.Retry;
        }
        switch (e.Code)
        {
            case "held-elsewhere" or "not-held" or "checked-out" or "locked":
                EndCheckout(s, heldHere: false);
                if (IsClosing(s))
                    Forget(s); // its check-in, if one was waiting, was dropped with the check-out
                return SaveOutcome.Done;
            case "version-moved":
                var moved = ProblemOf(e)?.Document;
                var heldHere = HoldIsHere(moved?.Hold);
                if (!job.Resent && moved is not null && moved.FileVersion == record.FileVersion && heldHere)
                {
                    // Only its properties or name changed since the copy's version, so nobody stored newer content:
                    // send it once more from the version as it is now (spec §5.10), under the document's name as it
                    // is now, so the save doesn't undo a rename. Nothing was stored, so the saveId stays.
                    job.Resent = true;
                    job.IfVersion = moved.Version;
                    var again = Update(s.Path, r => r with { Version = moved.Version, DocumentName = moved.Name }) ?? record;
                    return await SaveAsync(s, job, again, ct);
                }
                // Newer content was stored: the copy's changes don't go over it. A check-out still held here stays
                // recorded, so closing the copy or Save as New Document releases it.
                EndCheckout(s, heldHere);
                // The window may have closed while this save was going up; EndCheckout dropped its check-in, so the
                // close is finished here: released if it's still this computer's, and the session let go either way.
                if (IsClosing(s))
                {
                    if (heldHere)
                        return await ReleaseAsync(s, new SaveJob { CheckIn = true }, ct);
                    Forget(s);
                }
                return SaveOutcome.Done;
        }
        if (e.Status >= 500 || e.Status is 408 or 429)
            return SaveOutcome.Retry;
        // Refused for good (403, 404, 413, 422, refused): nothing was stored, and the copy keeps the changes.
        Update(s.Path, r => r with { PendingHash = null });
        Raise(ev => ev.Error($"Healdan refused the save of {Title(record)}: {e.Detail}"));
        Answer(s, job); // nothing more goes until the next save
        if (job.CheckIn)
            Forget(s);
        return SaveOutcome.Done;
    }

    /// <summary>
    /// The job has its outcome: confirmed, or given up (refused for good, or failed), when the copy keeps it.
    /// <c>/v1/saved</c>'s waiters are answered unless something newer is still to go, such as a save queued behind
    /// this one (a Save as New Version of the same bytes included), whose outcome answers them instead (final review
    /// I1, re-review).
    /// </summary>
    private void Answer(Session s, SaveJob job)
    {
        lock (_gate)
            if (SameHash(job.Hash, s.LastHash) && s.Queue.Queued is null)
                s.Answered = true;
        SettleWaiters(s);
    }

    private bool IsClosing(Session s)
    {
        lock (_gate)
            return s.Closing;
    }

    /// <summary>
    /// The copy's changes can't go to the document any more (spec §5.10): its check-out ended here, or newer content
    /// was stored. Nothing more is sent, and the copy is kept for Save as New Document. With
    /// <paramref name="heldHere"/>, the check-out is still this computer's, and stays recorded so it's released later.
    /// </summary>
    private void EndCheckout(Session s, bool heldHere)
    {
        lock (_gate)
        {
            s.Ended = true;
            s.Queue.DropQueued();
            Update(s.Path, r => r with { CheckedOutHere = heldHere, PendingHash = null });
        }
        Raise(ev => ev.CheckoutEnded(s.Path));
        CompleteWaiters(s, StateOf(s.Path, pending: false));
    }

    // --- Closing -------------------------------------------------------------------------------------------------

    /// <summary>The copy's window has closed: its check-in goes, carrying the newest content, or its check-out is released.</summary>
    /// <param name="recorded">
    /// At a restart, the kind the record says is still to go (its <see cref="CopyRecord.PendingKind"/>), as nothing is
    /// queued yet to say it: a Save as New Version waiting when the helper stopped is checked in as the version.
    /// </param>
    private void Close(string path, string? recorded = null)
    {
        if (FollowedRename(path))
            return; // not closed: Word or Excel saved it under its next name
        Session? s;
        SaveJob? waiting;
        string kind;
        lock (_gate)
        {
            s = Find(path);
            if (s is null || s.Closing)
                return;
            s.Closing = true;
            waiting = s.Queue.Queued;
            s.Queue.DropQueued(); // the check-in carries the newest content
            kind = s.NextKind ?? waiting?.Kind ?? recorded ?? Snapshot;
        }
        _saves.Unwatch(s.Path);
        _closes.Unwatch(s.Path);
        CompleteWaiters(s, StateOf(s.Path, pending: false));

        var record = Record(s.Path);
        if (record is { ReadOnly: false, CheckedOutHere: true } && !s.ReadOnly && s.Ended)
        {
            // Its changes can't go up, but this computer still holds the check-out: release it, and keep the copy.
            Enqueue(s, new SaveJob { CheckIn = true });
            return;
        }
        if (record is null || s.ReadOnly || record.ReadOnly || s.Ended)
        {
            Forget(s);
            if (!_store.HasUnconfirmedChanges(s.Path))
                _store.Delete(s.Path);
            return;
        }
        // The file is read when the check-in is sent: if it can't be read then, it's tried again; if it's gone, the
        // save that was waiting goes up instead of being lost with the release.
        Enqueue(s, new SaveJob
        {
            Kind = kind,
            CheckIn = true,
            ReadAtSend = true,
            FallbackContent = waiting?.Content,
            FallbackHash = waiting?.Hash
        });
    }

    /// <summary>
    /// Releases the check-out: on closing with nothing new to send, for a copy whose changes can't go up, for a record
    /// a stopped open left, or for Save as New Document's original. The record says what this computer held, which may
    /// be long ago (a restart): the document is read first, and released only while its hold is still this
    /// computer's. A plain release would free a check-out taken since, in the web page or on another computer, and
    /// for an administrator, anyone's.
    /// </summary>
    private async Task<SaveOutcome> ReleaseAsync(Session s, SaveJob job, CancellationToken ct)
    {
        var record = Record(s.Path);
        var heldByOther = false;
        if (record is { CheckedOutHere: true })
        {
            try
            {
                var doc = await _api.DocumentAsync(s.DocNumber, ct);
                if (HoldIsHere(doc.Hold))
                    await _api.ReleaseAsync(s.DocNumber, ct);
                else
                    heldByOther = doc.Hold is not null;
            }
            catch (ApiException e) when (e.Status == 401)
            {
                AskSignIn();
                return SaveOutcome.Retry;
            }
            catch (ApiException e) when (e.Status >= 500 || e.Status is 408 or 429)
            {
                return SaveOutcome.Retry;
            }
            catch (ApiException e) when (e.Code is "held-elsewhere" or "not-held" or "checked-out" or "locked")
            {
                heldByOther = true; // taken in the moment between reading and releasing
            }
            catch (ApiException e) when (e.Code == "not-found")
            {
                // The document can't be read any more: there's nothing of this computer's to release.
            }
            catch (ApiException e) when (!job.ReleaseOnly)
            {
                Raise(ev => ev.Error($"Healdan refused to release {Title(record)}: {e.Detail}"));
                Forget(s);
                return SaveOutcome.Done;
            }
            catch (ApiException)
            {
                // Save as New Document: the original keeps its last saved version whatever its check-out does.
            }
            Update(s.Path, r => r with { CheckedOutHere = false });
        }

        if (job.ReleaseOnly)
            _ = DeleteOnceAdoptedAsync(s, job.AfterAdoption ?? Task.CompletedTask);
        else if (heldByOther && File.Exists(s.Path))
        {
            // The check-out ended here (spec §5.10): the copy is kept, and the app offers Save as New Document.
            EndCheckout(s, heldHere: false);
            Forget(s);
        }
        else
            Finish(s); // a record with no file goes with it
        return SaveOutcome.Done;
    }

    /// <summary>The copy's window closed and Healdan has its content: the copy goes, unless it changed meanwhile.</summary>
    private void Finish(Session s)
    {
        Forget(s);
        if (!_store.HasUnconfirmedChanges(s.Path))
            _store.Delete(s.Path);
        else if (!s.Ended) // an ended copy's changes were never going up, and CheckoutEnded has said so
            Raise(ev => ev.Error($"#{s.DocNumber} changed after it was checked in; its copy is kept."));
    }

    private void Replace(string path, Task adopted)
    {
        Session? old;
        lock (_gate)
        {
            old = Find(path);
            if (old is not null)
            {
                old.Closing = true;
                old.Queue.DropQueued(); // nothing more is saved to the original
            }
        }
        if (old is null)
        {
            if (Record(path) is not { } record)
                return;
            old = new Session(path, record.DocNumber, record.ReadOnly, SendAsync, _timings, SetWaiting, SaveFailed) { Closing = true };
            lock (_gate)
                _sessions[Name(path)] = old;
        }
        _saves.Unwatch(old.Path);
        _closes.Unwatch(old.Path);
        old.Queue.Enqueue(new SaveJob { ReleaseOnly = true, AfterAdoption = adopted });
    }

    private async Task DeleteOnceAdoptedAsync(Session original, Task adopted)
    {
        try
        {
            await adopted.WaitAsync(_stop.Token);
            Forget(original);
            _store.Delete(original.Path);
        }
        catch (OperationCanceledException) when (_stop.IsCancellationRequested)
        {
            // Shutting down: the original copy stays, and ResumeAsync tidies it.
        }
        catch (Exception e)
        {
            Raise(ev => ev.Error($"#{original.DocNumber}'s old copy couldn't be removed: {e.Message}"));
        }
    }

    private void OnCrashed(string path)
    {
        Session? s;
        lock (_gate)
        {
            s = Find(path);
            if (s is null)
                return;
            s.Crashed = true;
        }
        WatchForRecovery(s);
        Raise(ev => ev.Crashed(s.Path));
    }

    // --- Word, Excel and PowerPoint (M3.5) -------------------------------------------------------------------------

    /// <summary>
    /// M3.5 spec §5.4: Word and Excel take the window's title from the file name, so once Healdan has confirmed a save
    /// of one of their copies, the program saves the copy under the confirmed version's name, and the copy's record,
    /// its session and its watchers follow. It runs in the confirmed save's job, so nothing else is sent for the copy
    /// meanwhile, and only when no newer save or Save as New Version waits, whose own confirmation renames it instead.
    /// With unsaved changes in the window, or when the program can't be asked, nothing moves, and the next confirmed
    /// save tries again. The renamed file is the confirmed content from then on, so it isn't sent again.
    /// <para>Review Focus 1: nothing the user saved may be taken for that content. The copy isn't watched while the
    /// program renames it, so the rename doesn't start if the copy has changed since its confirmation; and the renamed
    /// file goes up as the next save when the program saw an edit land between its check and its save
    /// (<see cref="RenameResult.RenamedChanged"/>), when the file it was saved from changed as the rename began, or when
    /// it changes before it's watched again.</para>
    /// </summary>
    internal async Task RenameAfterSaveAsync(Session s)
    {
        string old;
        CopyRecord record;
        lock (_gate)
        {
            if (s.ReadOnly || s.Closing || s.Ended || s.Crashed || s.NextKind is not null || s.Queue.Queued is not null
                || Find(s.Path) != s || Record(s.Path) is not { ReadOnly: false } found)
                return;
            (old, record) = (s.Path, found);
        }
        var app = _apps.Of(record);
        if (OfficeAfterSave.Driver(app, old) is not { } driver || !OfficePrograms.RenamesAfterSave(driver.Program))
            return;
        var newPath = Path.Combine(_store.Folder, CopyNames.FileName(record.DocumentName, record.DocNumber, record.FileVersion, false));
        // The name it has already, or another copy's, which the program would save over; or the program's owner file is
        // there without its document, as when it's saving there itself, and the old file mustn't go for a new one that
        // may never come.
        if (Name(newPath) == Name(old) || Path.Exists(newPath) || Record(newPath) is not null || app.LockExists(newPath))
            return;

        var lockSeen = _closes.LockSeen(old);
        _saves.Unwatch(old); // the program's writes under either name are its own, not saves
        _closes.Unwatch(old); // its owner file goes to the new name, which isn't the window closing
        var moved = false;
        try
        {
            if (!SameHash(HashOrNull(old), record.ConfirmedHash))
                return; // saved again since the confirmation: that save goes up, and its confirmation renames the copy
            var result = await _office.RenameAsync(driver.Scripting, driver.Program, old, newPath);
            var there = File.Exists(newPath) || app.LockExists(newPath);
            // A call that gave up (a Grant File Access prompt left open past its limit, say) or failed may still have
            // been carried out: the copy follows the program, and what it saved goes up, as nothing says what it is.
            if (result is not (RenameResult.Renamed or RenameResult.RenamedChanged) && there)
                result = RenameResult.RenamedChanged;
            if (result is not (RenameResult.Renamed or RenameResult.RenamedChanged) || !there)
                return;
            // Word's Save As leaves the file it saved from as it was, so that file changed means a save landed as the
            // rename began, and the renamed file holds it.
            var oldNow = HashOrNull(old);
            var written = Settled(newPath);
            var changed = result == RenameResult.RenamedChanged || written is null
                || (oldNow is not null && !SameHash(oldNow, record.ConfirmedHash));
            bool closing;
            lock (_gate)
            {
                var current = Record(old) ?? record;
                _store.Moved(old, newPath, changed ? current : current with { ConfirmedHash = written!.Value.Hash });
                Rekey(s, newPath);
                if (!changed)
                    s.LastHash = written!.Value.Hash; // so a look at it before this job ends doesn't queue it either
                moved = true;
                closing = s.Closing;
            }
            if (closing)
                return; // its check-in, queued meanwhile, reads the renamed file
            _saves.Watch(newPath);
            // Its owner file there now means its going at the first look is a close, as after any pause in the watch.
            _closes.Watch(newPath, app, appSeenRunning: true, lockSeen: app.LockExists(newPath));
            bool versionWaits;
            lock (_gate)
                versionWaits = s.NextKind is not null;
            if (changed || versionWaits || FileStamp.Of(newPath) != written!.Value.Stamp)
                _saves.Touch(newPath);
            await _office.MoveFooterOnAsync(driver.Scripting, driver.Program, newPath,
                CopyNames.DocRef(record.DocNumber, record.FileVersion));
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            // Only the record's move writes here, once the program has saved the copy under its new name: the next
            // start finds that file and carries on with it (UnfinishedRename).
            Raise(ev => ev.Error($"{Title(record)} was saved under its new name, but the helper couldn't move its record: "
                + $"{e.Message} Quit Healdan Helper and start it again to carry on with it."));
        }
        finally
        {
            bool stays;
            lock (_gate)
                stays = !moved && !s.Closing && s.Path == old; // a rename carried out late may have moved it (FollowedRename)
            if (stays)
            {
                _saves.Watch(old);
                _saves.Touch(old); // a save written while it wasn't watched still goes up
                _closes.Watch(old, app, appSeenRunning: true, lockSeen);
            }
        }
    }

    /// <summary>
    /// The session follows its copy to <paramref name="newPath"/>, which its app saved it under, keyed by the new file name.
    /// </summary>
    internal void Rekey(Session s, string newPath)
    {
        lock (_gate)
        {
            var old = s.Path;
            if (_sessions.TryGetValue(Name(old), out var current) && current == s)
                _sessions.Remove(Name(old));
            s.Path = newPath;
            _sessions[Name(newPath)] = s;
            if (_waiting.Remove(old))
                _waiting.Add(newPath); // the queue's own retry, still waiting, ends under the new path
        }
    }

    /// <summary>
    /// At start: a rename (<see cref="RenameAfterSaveAsync"/>) the helper stopped in the middle of, after the program
    /// saved the copy under the confirmed version's name but before the record followed. That file, which no record
    /// names, is the copy now, and the window under the old name is gone. As nothing says what Healdan has of it, it
    /// counts as changed, so it goes up rather than the old window being taken for closed and checked in.
    /// </summary>
    private (string Path, CopyRecord Record)? UnfinishedRename(string path, CopyRecord record, IOfficeApp app)
    {
        if (record.ReadOnly || !record.CheckedOutHere || OfficeAfterSave.Driver(app, path) is not { } driver
            || !OfficePrograms.RenamesAfterSave(driver.Program))
            return null;
        var renamed = Path.Combine(_store.Folder, CopyNames.FileName(record.DocumentName, record.DocNumber, record.FileVersion, false));
        if (Name(renamed) == Name(path) || !(File.Exists(renamed) || app.LockExists(renamed)) || Record(renamed) is not null
            || app.LockExists(path))
            return null;
        _store.Moved(path, renamed, record);
        return Record(renamed) is { } moved ? (renamed, moved) : null;
    }

    /// <summary>
    /// Before a close is acted on: the old name's owner file went because the program saved the copy under its next name
    /// (<see cref="UnfinishedRename"/>), as Word does with a rename whose script gave up, once the user grants access, not
    /// because the window closed. The copy follows it there, and the file goes up, rather than being checked in while
    /// the program has its window open. False when that isn't so, and the close goes on.
    /// </summary>
    private bool FollowedRename(string path)
    {
        Session? s;
        lock (_gate)
            s = Find(path);
        if (s is null || s.ReadOnly || IsClosing(s) || Record(s.Path) is not { } record)
            return false;
        var app = _apps.Of(record);
        string old, renamed;
        lock (_gate)
        {
            old = s.Path;
            if (s.Closing || s.Ended || Find(old) != s || UnfinishedRename(old, record, app) is not { } moved)
                return false;
            renamed = moved.Path;
            Rekey(s, renamed);
        }
        _saves.Unwatch(old);
        _closes.Unwatch(old);
        _saves.Watch(renamed);
        _saves.Touch(renamed);
        _closes.Watch(renamed, app, appSeenRunning: true, lockSeen: app.LockExists(renamed));
        return true;
    }

    /// <summary>
    /// M3.5 spec §5.14: a crashed copy of Word's, Excel's or PowerPoint's is watched for its program to have it open again.
    /// </summary>
    private void WatchForRecovery(Session s)
    {
        var app = AppOf(s.Path);
        if (s.ReadOnly || OfficeAfterSave.Driver(app, s.Path) is null)
            return;
        lock (_gate)
            s.CrashWritten = app.LockWritten(s.Path);
        _closes.WatchCrashed(s.Path, app, s.CrashWritten);
    }

    /// <summary>
    /// M3.5 spec §5.14, Review Focus 4: the program may have a crashed copy open again by itself, as Word restores a
    /// "Recovered" window at the copy's own path, whose Save opens Save As. The close watcher has let go of the copy, so
    /// the window's owner file coming and going while it's saved isn't read as anything, and the window is saved back
    /// over the copy (<see cref="SaveBackAsync"/>).
    /// </summary>
    /// <param name="lockBack">
    /// The program's owner file was written again since the crash, which says the program has the copy open again, as
    /// Word did in the trial; false when it's only running again.
    /// </param>
    private void OnReopened(string path, bool lockBack)
    {
        Session? s;
        lock (_gate)
        {
            s = Find(path);
            if (s is null || !s.Crashed || s.Closing)
                return;
            if (lockBack)
                s.Reopened = true; // from now on, the crash window's Send and Check In would delete it from under the window
            if (s.LookingBack)
                return;
            s.LookingBack = true;
        }
        _ = Task.Run(() => SaveBackAsync(s));
    }

    /// <summary>
    /// Saves a crashed copy's restored window back over it, once the program lists it by its path, so it's never another
    /// window (<see cref="OfficeAfterSave.SaveRecoveredAsync"/>), and the folder watcher sends what was recovered. Only
    /// then is the copy looked after as an open one again (<see cref="ICycleEvents.Reopened"/>), and PowerPoint closed and
    /// opened again, as after any save under a name through the seam, so its own Save saves in place. Until then the copy
    /// stays crashed, the crash window's Reopen and Send and Check In stand, and a failure is tried again once the close
    /// watcher looks again.
    /// </summary>
    private async Task SaveBackAsync(Session s)
    {
        var app = AppOf(s.Path);
        if (OfficeAfterSave.Driver(app, s.Path) is not { } driver)
        {
            lock (_gate)
                s.LookingBack = false;
            return;
        }
        try
        {
            Recovery outcome;
            var open = false;
            TimeSpan wait;
            lock (_gate)
            {
                wait = s.LookBriefly ? _timings.CrashPoll * 2 : _timings.RecoveryWait;
                // Its owner file written again says the program has it open: until the look returns, that owner file says
                // nothing, as the program removes it while it saves a window over the copy, so Send and Check In is refused.
                s.SavingBack = s.Reopened;
            }
            try
            {
                outcome = await _office.SaveRecoveredAsync(driver.Scripting, driver.Program, s.Path, wait, _timings.CrashPoll,
                    () => IsClosing(s), () =>
                    {
                        // Found: from now on the crash window's Send and Check In would delete it from under the window, and
                        // until the save returns, the program's owner file says nothing.
                        lock (_gate)
                            (s.Reopened, s.SavingBack) = (true, true);
                    }, _stop.Token);
                lock (_gate)
                {
                    if (s.Closing || !s.Crashed || Find(s.Path) != s)
                        return; // checked in meanwhile, from the crash window
                    if (outcome == Recovery.Saved)
                    {
                        (s.Crashed, s.Reopened, s.SavingBack, s.OpenIfNotFound, s.LookBriefly) = (false, false, false, false, false);
                        _closes.Watch(s.Path, app, appSeenRunning: true, lockSeen: app.LockExists(s.Path));
                    }
                    else if (outcome == Recovery.NotFound && s.OpenIfNotFound)
                    {
                        // Reopen asked, and the program brought nothing back: opened as last saved, as any open copy.
                        (s.Crashed, s.Reopened, s.OpenIfNotFound, s.LookBriefly) = (false, false, false, false);
                        _closes.Watch(s.Path, app);
                        open = true;
                    }
                }
            }
            finally
            {
                lock (_gate)
                    (s.SavingBack, s.LookingBack) = (false, false);
            }
            if (outcome == Recovery.Saved)
            {
                Raise(ev => ev.Reopened(s.Path));
                if (driver.Program == OfficeProgram.PowerPoint)
                    await ReopenOfficeAsync(s.Path);
                return;
            }
            if (open)
            {
                app.Open(s.Path);
                return;
            }
            if (outcome == Recovery.Failed)
            {
                // Found, but not saved: tried again at the next look.
                await Task.Delay(_timings.CrashPoll, _stop.Token);
                lock (_gate)
                    if (s.Crashed && !s.Closing && Find(s.Path) == s)
                        _closes.WatchCrashed(s.Path, app, s.CrashWritten);
                return;
            }
            // Not found: looked for again as soon as the program starts again or writes its owner file, and while it runs
            // only after a while, so it isn't asked every few seconds while it runs without the copy.
            lock (_gate)
                if (s.Crashed && !s.Closing && Find(s.Path) == s)
                    _closes.WatchCrashed(s.Path, app, s.CrashWritten, lookIfRunning: false);
            await Task.Delay(_timings.RecoveryWait, _stop.Token);
            lock (_gate)
                if (s.Crashed && !s.Closing && !s.LookingBack && Find(s.Path) == s)
                    _closes.WatchCrashed(s.Path, app, s.CrashWritten);
        }
        catch (OperationCanceledException) when (_stop.IsCancellationRequested)
        {
            // Shutting down: the next start finds the crash again.
        }
    }

    /// <summary>
    /// M3.5 spec §5.5 step 7: after PowerPoint saved a presentation under a name through the seam, its own Save opens
    /// Save As, so the presentation is saved, closed and opened again from its file. Its owner file goes meanwhile, which
    /// must never read as the window closing (Review Focus 3): the close watcher lets go of the copy until the owner file
    /// is back, or for <see cref="ReopenWait"/>, then watches it as one PowerPoint has open. Failures are logged, and
    /// the presentation stays open as it was. Anything but an open PowerPoint copy is left alone.
    /// </summary>
    internal async Task ReopenOfficeAsync(string path)
    {
        Session? s;
        lock (_gate)
            s = Find(path);
        if (s is null || s.ReadOnly || IsClosing(s))
            return;
        var app = AppOf(s.Path);
        if (OfficeAfterSave.Driver(app, s.Path) is not { Program: OfficeProgram.PowerPoint } driver)
            return;
        _closes.Unwatch(s.Path);
        try
        {
            await _office.SaveAndReopenAsync(driver.Scripting, driver.Program, s.Path);
            var waited = Stopwatch.StartNew();
            while (!app.LockExists(s.Path) && waited.Elapsed < ReopenWait)
                await Task.Delay(_timings.LockPoll, _stop.Token);
        }
        catch (OperationCanceledException) when (_stop.IsCancellationRequested)
        {
            // Shutting down.
        }
        finally
        {
            lock (_gate)
                if (!s.Closing && Find(s.Path) == s)
                    _closes.Watch(s.Path, app, appSeenRunning: true, lockSeen: app.LockExists(s.Path));
        }
    }

    /// <summary>The file's stamp and hash, read while it didn't change; null when it's missing, unreadable or changing.</summary>
    private static (FileStamp Stamp, string Hash)? Settled(string path)
    {
        var before = FileStamp.Of(path);
        if (!before.Exists || HashOrNull(path) is not { } hash)
            return null;
        return FileStamp.Of(path) == before ? (before, hash) : null;
    }

    private static string? HashOrNull(string path)
    {
        try
        {
            return File.Exists(path) ? HashOf(path) : null;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    // --- Helpers -------------------------------------------------------------------------------------------------

    private void AskSignIn()
    {
        lock (_gate)
        {
            if (_signInAsked)
                return;
            _signInAsked = true;
        }
        Raise(ev => ev.SignInNeeded());
    }

    private void SetWaiting(string path, bool waiting)
    {
        int count;
        lock (_gate)
        {
            if (waiting ? !_waiting.Add(path) : !_waiting.Remove(path))
                return;
            count = _waiting.Count;
        }
        Raise(ev => ev.Waiting(count));
    }

    /// <summary>Answers <c>/v1/saved</c>'s waiters, unless something newer is still on its way to Healdan.</summary>
    private void SettleWaiters(Session s)
    {
        lock (_gate)
            if (s.Waiters.Count == 0)
                return;
        if (!PendingOf(s))
            CompleteWaiters(s, StateOf(s.Path, pending: false));
    }

    private void CompleteWaiters(Session s, CopyState state)
    {
        List<TaskCompletionSource<CopyState>> waiters;
        lock (_gate)
        {
            waiters = [.. s.Waiters];
            s.Waiters.Clear();
        }
        foreach (var waiter in waiters)
            waiter.TrySetResult(state);
    }

    /// <summary>Under the gate: the session for a copy, found by its file name.</summary>
    private Session? Find(string path) => _sessions.GetValueOrDefault(Name(path));

    private static string Name(string path) => Path.GetFileName(path).Normalize(NormalizationForm.FormC);

    private CopyRecord? Record(string path) => _store.Find(path)?.Item2;

    private CopyRecord? Update(string path, Func<CopyRecord, CopyRecord> change)
    {
        lock (_gate)
        {
            if (_store.Find(path) is not (_, var record))
                return null;
            var next = change(record);
            _store.Save(next);
            return next;
        }
    }

    /// <summary>The hold is this computer's check-out. A hold with no device can't be told from someone else's, so it isn't.</summary>
    private bool HoldIsHere(HoldView? hold) => hold is { Kind: "checkout", Device: { } device } && device.Id == _deviceId;

    /// <summary>The app that has a copy open, by its record. LibreOffice for a copy with none.</summary>
    public IOfficeApp AppOf(string path) => AppOf(path, _apps.LibreOffice);

    /// <summary>The app a copy was opened in, by its record: <paramref name="fallback"/> when it has none.</summary>
    private IOfficeApp AppOf(string path, IOfficeApp fallback) =>
        Record(path) is { } record ? _apps.Of(record) : fallback;

    private static bool SafeIsRunning(IOfficeApp app, string path)
    {
        try
        {
            return app.IsRunningFor(path);
        }
        catch (Exception e) when (e is InvalidOperationException or System.ComponentModel.Win32Exception or IOException or UnauthorizedAccessException or PlatformNotSupportedException)
        {
            return true;
        }
    }

    /// <summary>Runs a watcher's callback so that a failure is reported rather than stopping the watcher.</summary>
    private void Guard(Action action)
    {
        try
        {
            action();
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            Raise(ev => ev.Error("The helper hit a problem watching its copies: " + e.Message));
        }
    }

    /// <summary>
    /// A send that threw what it doesn't expect: the copy and its record stay, and the next save tries again. The
    /// record keeps the saveId it was sent with, as nothing says whether Healdan stored it, so the next save resolves
    /// that as a restart does: an identical version at worst, never a save lost or a check-out ended by its own save.
    /// A close's check-in or release has nothing after it to finish the session, so it's let go, as a refused one is;
    /// the copy is kept, and the next start closes it again.
    /// </summary>
    private void SaveFailed(Session s, SaveJob job, Exception e)
    {
        Raise(ev => ev.Error(job.ReleaseOnly || (job.CheckIn && job.Content is null)
            ? $"#{s.DocNumber}'s check-out couldn't be released: {e.Message}"
            : $"#{s.DocNumber} couldn't be saved to Healdan, and its copy keeps it: {e.Message}"));
        Answer(s, job);
        if (job.CheckIn || job.ReleaseOnly)
            Forget(s);
    }

    /// <summary>
    /// Tells the app something. A handler that throws is reported through <see cref="ICycleEvents.Error"/> once, and
    /// never stops a copy's saves, its watchers or a close.
    /// </summary>
    private void Raise(Action<ICycleEvents> raise)
    {
        try
        {
            raise(_events);
        }
        catch (Exception e)
        {
            try
            {
                _events.Error("The helper couldn't show an update: " + e.Message);
            }
            catch (Exception)
            {
                // The app can't be told anything; the cycle goes on regardless.
            }
        }
    }

    private static bool IsTransient(Exception e) =>
        e is HttpRequestException or IOException or TaskCanceledException or TimeoutException or JsonException;

    private static Problem? ProblemOf(ApiException e)
    {
        if (e.Body.ValueKind != JsonValueKind.Object)
            return null;
        try
        {
            return e.Body.Deserialize<Problem>(HealdanApi.Json);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static CopyState StateOf(string path, CopyRecord r, string? holdBy) =>
        new(true, r.DocNumber, Title(r), CopyNames.DocRef(r.DocNumber, r.FileVersion), r.ReadOnly, path, holdBy);

    private static string Title(CopyRecord r) => CopyNames.Title(r.DocumentName, r.DocNumber, r.FileVersion, r.ReadOnly);

    private static bool SameHash(string? a, string? b) => a is not null && b is not null && a.Equals(b, StringComparison.OrdinalIgnoreCase);

    private static string HashOf(string path)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        return Convert.ToHexString(SHA256.HashData(stream));
    }

    /// <summary>The file's bytes and their hash, or null if it's missing or changed while it was read.</summary>
    private static (byte[] Bytes, string Hash)? TryRead(string path)
    {
        var before = FileStamp.Of(path);
        if (!before.Exists)
            return null;
        byte[] bytes;
        try
        {
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            using var buffer = new MemoryStream();
            stream.CopyTo(buffer);
            bytes = buffer.ToArray();
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return null;
        }
        return FileStamp.Of(path) == before ? (bytes, Convert.ToHexString(SHA256.HashData(bytes))) : null;
    }
}
