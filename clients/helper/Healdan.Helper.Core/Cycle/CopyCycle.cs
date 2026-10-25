using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Copies;

namespace Healdan.Helper.Core.Cycle;

/// <summary>
/// The open-save-close cycle of local copies (spec §5.4, §5.5, §5.10). Opening checks the document out, downloads it
/// and opens it in the app. Every save the app writes is sent once it has settled, one at a time and in order, with
/// <c>ifVersion</c> and <c>ifHeld</c>, so it never lands on a version it didn't start from or on a check-out that has
/// ended. Closing checks it in and deletes the copy. Every failure keeps the copy: nothing is deleted that Healdan hasn't
/// confirmed (spec §2.4). What a later step needs is in the copy's record, written before each send, so
/// <see cref="ResumeAsync"/> carries on after a restart.
/// <para>A copy keeps the file name it was opened with while the app has it open (R19); its title comes from the
/// record's <see cref="CopyRecord.FileVersion"/>. Methods taking a path find the copy by its file name, as every copy is
/// in the one folder. Nothing here logs, and no event carries a token or a file's contents.</para>
/// </summary>
public sealed class CopyCycle : IDisposable
{
    private const string Snapshot = "snapshot";
    private const string NewVersion = "version";

    private readonly HealdanApi _api;
    private readonly CopyStore _store;
    private readonly IOfficeApp _app;
    private readonly string _deviceName;
    private readonly ICycleEvents _events;
    private readonly CycleTimings _timings;

    /// <summary>Guards the sessions and every read-modify-write of a record. Events are raised outside it.</summary>
    private readonly object _gate = new();
    private readonly Dictionary<string, Session> _sessions = new(StringComparer.Ordinal);
    private readonly HashSet<string> _waiting = new(StringComparer.Ordinal);
    private readonly SaveWatcher _saves;
    private readonly CloseWatcher _closes;
    private readonly CancellationTokenSource _stop = new();
    private bool _signInAsked;

    /// <param name="deviceName">This computer's name as its token has it, which a hold's <c>device.name</c> shows.</param>
    /// <param name="timings">The trial's timings by default (<see cref="CycleTimings.Default"/>).</param>
    public CopyCycle(HealdanApi api, CopyStore store, IOfficeApp app, string deviceName, ICycleEvents events, CycleTimings? timings = null)
    {
        _api = api;
        _store = store;
        _app = app;
        _deviceName = deviceName;
        _events = events;
        _timings = timings ?? CycleTimings.Default;
        store.EnsureFolders();
        _saves = new SaveWatcher(store.Folder, _timings, path => Guard(() => OnSettled(path)));
        _closes = new CloseWatcher(app, _timings, path => Guard(() => Close(path)), path => Guard(() => OnCrashed(path)));
    }

    /// <summary>An open copy: its save queue and what the cycle knows about it beyond its record.</summary>
    private sealed class Session
    {
        public Session(string path, long docNumber, bool readOnly, Func<Session, SaveJob, CancellationToken, Task<SaveOutcome>> send,
            CycleTimings timings, Action<string, bool> waiting)
        {
            Path = path;
            DocNumber = docNumber;
            ReadOnly = readOnly;
            Queue = new SaveQueue((job, ct) => send(this, job, ct), timings, w => waiting(path, w));
        }

        public string Path { get; }

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

        /// <summary>The check-out ended here: nothing more is sent, and the copy is kept.</summary>
        public bool Ended { get; set; }

        public bool Closing { get; set; }

        public bool Crashed { get; set; }

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
    /// starting point, not a save. With <paramref name="replaces"/>, the original copy's check-out is released without
    /// saving to it, and the original copy is deleted once the app has adopted the new file: its content went into the
    /// new document. A failed filing throws, so the caller can fall back to the app's own save.
    /// </summary>
    public async Task<CopyState> AdoptNewAsync(string tempPath, NewDocument request, string? replaces)
    {
        var doc = await _api.FileAsync(tempPath, request with { CheckOut = true }, _stop.Token);
        var (path, _, _) = _store.Create(doc, false, _app.Name);
        Update(path, r => r with { CheckedOutHere = true, ConfirmedHash = null, PendingKind = null, PendingHash = null });
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
    /// The copy's state once its next save is confirmed, or once a save has settled with nothing new to send; after
    /// <paramref name="max"/>, its state as it is.
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

    /// <summary>The extension's close event for the copy's window: checks it in (or releases it) and deletes the copy, in the background.</summary>
    public Task ClosedAsync(string path)
    {
        Close(path);
        return Task.CompletedTask;
    }

    /// <summary>
    /// At start: for each copy left from before, an open one is watched again and its unconfirmed changes resent; a
    /// crashed one is offered (<see cref="ICycleEvents.Crashed"/>) and its changes resent; one whose window closed
    /// meanwhile is checked in and deleted; and a closed read-only copy is deleted. Saves wait for the server as usual.
    /// </summary>
    public Task ResumeAsync()
    {
        var running = SafeIsRunning();
        foreach (var (path, record) in _store.All())
        {
            lock (_gate)
                if (Find(path) is not null)
                    continue;
            if (!File.Exists(path))
            {
                // A download or adoption that never finished: nothing to send or lose. Release a check-out it took,
                // as a failed open does, and drop the record.
                if (record.CheckedOutHere && !record.ReadOnly && !_app.LockExists(path))
                {
                    Begin(path, record.DocNumber, false, null, watchClose: false);
                    Close(path);
                }
                else if (!record.CheckedOutHere)
                    _store.Delete(path);
                continue;
            }
            var unconfirmed = _store.HasUnconfirmedChanges(path);
            if (record.ReadOnly)
            {
                if (unconfirmed)
                    continue; // not a copy the helper sends, nor one it may delete
                if (!running)
                    _store.Delete(path);
                else
                    Begin(path, record.DocNumber, true, null, appSeenRunning: true);
                continue;
            }
            if (!record.CheckedOutHere)
            {
                if (unconfirmed)
                    _events.CheckoutEnded(path);
                else if (!_app.LockExists(path))
                    _store.Delete(path);
                continue;
            }

            var locked = _app.LockExists(path);
            var s = Begin(path, record.DocNumber, false, null, appSeenRunning: running, watchClose: !locked || running);
            if (!locked)
            {
                Close(path); // its window closed while the helper wasn't running
                continue;
            }
            if (!running)
            {
                s.Crashed = true;
                _events.Crashed(path);
            }
            if (unconfirmed && TryRead(path) is { } content)
                Enqueue(s, new SaveJob { Content = content.Bytes, Hash = content.Hash, Kind = record.PendingKind ?? Snapshot });
        }
        return Task.CompletedTask;
    }

    /// <summary>The copy's state for the channel, or an unknown one.</summary>
    public CopyState StateOf(string path)
    {
        string? holdBy;
        lock (_gate)
        {
            var s = Find(path);
            holdBy = s?.HoldBy;
            if (s is not null)
                path = s.Path;
        }
        var record = Record(path);
        return record is null ? CopyState.Unknown(path) : StateOf(path, record, holdBy);
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
        try
        {
            await open();
        }
        catch (ApiException e) when (e.Status == 401)
        {
            // The user asked for this open, so they hear about it every time, unlike a save waiting in the background.
            lock (_gate)
                _signInAsked = true;
            _events.SignInNeeded();
        }
        catch (ApiException e)
        {
            _events.Error($"#{docNumber} couldn't be opened: {e.Detail}");
        }
        catch (OperationCanceledException) when (_stop.IsCancellationRequested)
        {
            // Shutting down.
        }
        catch (Exception e)
        {
            _events.Error($"#{docNumber} couldn't be opened: {e.Message}");
        }
    }

    private async Task OpenCoreAsync(long n, bool readOnly, string? version, bool move)
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
        var checkedOut = false;
        var holdBy = doc.Hold?.By?.Name;
        if (!readOnly)
        {
            if (move || doc.CanEdit || doc.Hold?.Device is not null)
            {
                try
                {
                    doc = await _api.CheckOutAsync(n, _app.Name, move, ct);
                    checkedOut = true;
                }
                catch (ApiException e) when (e.Code == "held-elsewhere")
                {
                    var problem = ProblemOf(e);
                    _events.HeldElsewhere(null, n, problem?.Hold?.Device?.Name ?? "another computer", problem?.Hold?.Device?.App,
                        problem?.LastSaved);
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
            (path, fresh, readOnly) = await PrepareCopyAsync(doc, readOnly, version, checkedOut, ct);
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            if (checkedOut && !hadCopy)
                await ReleaseQuietlyAsync(n);
            throw;
        }

        var session = Begin(path, n, readOnly, readOnly ? holdBy : null);
        try
        {
            _app.Open(path);
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            Forget(session);
            if (fresh)
                _store.Delete(path); // just downloaded: Healdan has every byte of it
            if (checkedOut && !hadCopy)
                await ReleaseQuietlyAsync(n);
            throw;
        }
    }

    /// <summary>
    /// The copy to open, whether it was downloaded now (so nothing but Healdan's bytes are in it), and whether it's
    /// read-only. A copy this computer kept is reopened, never downloaded over: one with unconfirmed changes (from
    /// <see cref="CopyStore.Create"/>), or one checked out here whose content is still the document's.
    /// </summary>
    private async Task<(string Path, bool Fresh, bool ReadOnly)> PrepareCopyAsync(DocumentView doc, bool readOnly, string? version,
        bool checkedOut, CancellationToken ct)
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
        var (path, record, existing) = _store.Create(forCopy, readOnly, _app.Name);
        if (existing)
        {
            if (checkedOut)
                Update(path, r => r with { CheckedOutHere = true });
            return (path, false, record.ReadOnly);
        }
        try
        {
            await _api.DownloadAsync(doc.DocNumber, path, version, ct);
            if (readOnly && !OperatingSystem.IsWindows())
                File.SetUnixFileMode(path, UnixFileMode.UserRead);
            var hash = HashOf(path);
            Update(path, r => r with { ConfirmedHash = hash, CheckedOutHere = checkedOut, PendingKind = null, PendingHash = null });
        }
        catch
        {
            _store.Delete(path); // the record just written, and a file Healdan had confirmed if one was there
            throw;
        }
        return (path, true, readOnly);
    }

    private void Reopen(Session live)
    {
        bool closing;
        lock (_gate)
        {
            closing = live.Closing;
            if (!closing && live.Crashed)
            {
                live.Crashed = false;
                _closes.Watch(live.Path);
            }
        }
        if (closing)
            _events.Error($"#{live.DocNumber} is still being checked in. Open it again once that's done.");
        else
            _app.Open(live.Path);
    }

    private async Task ReleaseQuietlyAsync(long n)
    {
        try
        {
            await _api.ReleaseAsync(n, _stop.Token);
        }
        catch (Exception e) when (e is ApiException || IsTransient(e))
        {
            _events.Error($"#{n} is still checked out to you: releasing it failed.");
        }
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
                s = new Session(path, n, readOnly, SendAsync, _timings, SetWaiting);
                _sessions[Name(path)] = s;
            }
            (s.HoldBy, s.Adopting, s.Ended, s.Crashed, s.NextKind) = (holdBy, adopting, false, false, null);
        }
        if (!readOnly)
            _saves.Watch(path);
        if (watchClose)
            _closes.Watch(path, appSeenRunning);
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
            _events.Waiting(count);
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

        CopyState? answer = null;
        lock (_gate)
        {
            var record = Record(s.Path);
            if (record is null || s.Closing)
                return;
            if (s.Adopting)
            {
                // The app has adopted the new document's copy: its starting point, not a save (spec §5.6).
                s.Adopting = false;
                Update(s.Path, r => r with { ConfirmedHash = content.Hash });
                s.Adopted.TrySetResult();
                answer = StateOf(s.Path, record, s.HoldBy);
            }
            else
            {
                var kind = s.NextKind;
                var idle = s.Queue.Idle;
                var baseline = idle ? record.ConfirmedHash : s.LastHash;
                if (s.Ended)
                    answer = StateOf(s.Path, record, s.HoldBy); // kept, not sent: the check-out ended here
                else if (kind is null && SameHash(content.Hash, baseline))
                {
                    if (idle)
                        answer = StateOf(s.Path, record, s.HoldBy); // a save with nothing new to send
                }
                else
                {
                    s.NextKind = null;
                    Enqueue(s, new SaveJob { Content = content.Bytes, Hash = content.Hash, Kind = kind ?? Snapshot });
                }
            }
        }
        if (answer is not null)
            CompleteWaiters(s, answer);
    }

    /// <summary>Queues a save; its kind is written to the record first, so a restart knows what was waiting.</summary>
    private void Enqueue(Session s, SaveJob job)
    {
        lock (_gate)
        {
            s.LastHash = job.Hash;
            if (job.Content is not null)
                Update(s.Path, r => r with { PendingKind = job.Kind });
            s.Queue.Enqueue(job);
        }
    }

    private async Task<SaveOutcome> SendAsync(Session s, SaveJob job, CancellationToken ct)
    {
        try
        {
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
        catch (Exception e)
        {
            // Not expected; the copy and its record stay, and the next save tries again.
            _events.Error($"#{s.DocNumber} couldn't be saved to Healdan: {e.Message}");
            return SaveOutcome.Done;
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
                job.Uncertain = !SameHash(job.Hash, record.PendingHash);
            }
            else
            {
                job.SaveId = Guid.NewGuid().ToString();
                // Written down before it's sent, so a retry after a crash repeats it.
                record = Update(s.Path, r => r with { LastSaveId = job.SaveId, PendingHash = job.Hash, PendingKind = job.Kind }) ?? record;
            }
            job.IfVersion = record.Version;
        }

        var request = new SaveRequest(job.Kind, !job.CheckIn, record.DocumentName, job.IfVersion!, true, job.SaveId);
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
            // The answer doesn't say which bytes the server kept under that saveId: send these again, as a new save.
            var moved = Update(s.Path, r => r with { Version = doc.Version, FileVersion = doc.FileVersion, PendingHash = null }) ?? record;
            (job.Uncertain, job.SaveId, job.Resent) = (false, null, false);
            return await SaveAsync(s, job, moved, ct);
        }

        var confirmed = Confirm(s, job, doc);
        if (job.CheckIn && confirmed && doc.Hold?.Device is not null)
            return await ReleaseAsync(s, job, ct); // a repeat of a save that kept it checked out to us: release it now
        if (job.CheckIn && confirmed)
            Finish(s);
        return SaveOutcome.Done;
    }

    /// <summary>Records the confirmed save and says so; false when the copy has gone meanwhile.</summary>
    private bool Confirm(Session s, SaveJob job, DocumentView doc)
    {
        CopyRecord? record;
        lock (_gate)
        {
            record = Update(s.Path, r => r with
            {
                Version = doc.Version,
                FileVersion = doc.FileVersion,
                ConfirmedHash = job.Hash,
                PendingHash = null,
                PendingKind = s.Queue.Queued?.Kind,
                CheckedOutHere = !job.CheckIn || doc.Hold?.Device is not null // a hold's device shows only to its holder
            });
            _signInAsked = false;
        }
        if (record is null)
            return false;
        var state = StateOf(s.Path, record, s.HoldBy);
        _events.Confirmed(s.Path, state);
        CompleteWaiters(s, state);
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
                EndCheckout(s);
                return SaveOutcome.Done;
            case "version-moved":
                var moved = ProblemOf(e)?.Document;
                if (!job.Resent && moved is not null && moved.FileVersion == record.FileVersion && HoldIsHere(moved.Hold))
                {
                    // Only its properties or name changed since the copy's version, so nobody stored newer content:
                    // send it once more from the version as it is now (spec §5.10). Nothing was stored, so the saveId stays.
                    job.Resent = true;
                    job.IfVersion = moved.Version;
                    var again = Update(s.Path, r => r with { Version = moved.Version }) ?? record;
                    return await SaveAsync(s, job, again, ct);
                }
                EndCheckout(s);
                return SaveOutcome.Done;
        }
        if (e.Status >= 500 || e.Status is 408 or 429)
            return SaveOutcome.Retry;
        // Refused for good (403, 404, 413, 422, refused): nothing was stored, and the copy keeps the changes.
        Update(s.Path, r => r with { PendingHash = null });
        _events.Error($"Healdan refused the save of {Title(record)}: {e.Detail}");
        if (job.CheckIn)
            Forget(s);
        return SaveOutcome.Done;
    }

    /// <summary>The check-out ended here (spec §5.10): nothing more is sent, and the copy is kept for Save as New Document.</summary>
    private void EndCheckout(Session s)
    {
        lock (_gate)
        {
            s.Ended = true;
            s.Queue.DropQueued();
            Update(s.Path, r => r with { CheckedOutHere = false, PendingHash = null });
        }
        _events.CheckoutEnded(s.Path);
        CompleteWaiters(s, StateOf(s.Path));
        if (s.Closing)
            Forget(s);
    }

    // --- Closing -------------------------------------------------------------------------------------------------

    private void Close(string path)
    {
        Session? s;
        lock (_gate)
        {
            s = Find(path);
            if (s is null || s.Closing)
                return;
            s.Closing = true;
            s.Queue.DropQueued(); // the check-in carries the newest content
        }
        _saves.Unwatch(s.Path);
        _closes.Unwatch(s.Path);
        CompleteWaiters(s, StateOf(s.Path));

        var record = Record(s.Path);
        if (record is null || s.ReadOnly || record.ReadOnly || s.Ended)
        {
            Forget(s);
            if (!_store.HasUnconfirmedChanges(s.Path))
                _store.Delete(s.Path);
            return;
        }
        var content = TryRead(s.Path);
        string kind;
        lock (_gate)
        {
            kind = s.NextKind ?? Snapshot;
            if (s.Adopting && content is { } adopted)
            {
                // Closed before the adoption had settled: that write is still the starting point, not a save.
                s.Adopting = false;
                Update(s.Path, r => r with { ConfirmedHash = adopted.Hash });
                s.Adopted.TrySetResult();
            }
        }
        Enqueue(s, new SaveJob { Content = content?.Bytes, Hash = content?.Hash, Kind = kind, CheckIn = true });
    }

    /// <summary>Releases the check-out: on closing with nothing new to send, or for Save as New Document's original.</summary>
    private async Task<SaveOutcome> ReleaseAsync(Session s, SaveJob job, CancellationToken ct)
    {
        var record = Record(s.Path);
        if (record is { CheckedOutHere: true })
        {
            try
            {
                await _api.ReleaseAsync(s.DocNumber, ct);
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
            catch (ApiException e) when (!job.ReleaseOnly)
            {
                if (e.Code is "held-elsewhere" or "not-held" or "checked-out" or "locked")
                    EndCheckout(s);
                else
                {
                    _events.Error($"Healdan refused to release {Title(record)}: {e.Detail}");
                    Forget(s);
                }
                return SaveOutcome.Done;
            }
            catch (ApiException)
            {
                // Save as New Document: the original's check-out had ended anyway; it keeps its last saved version.
            }
            Update(s.Path, r => r with { CheckedOutHere = false });
        }
        if (job.ReleaseOnly)
            _ = DeleteOnceAdoptedAsync(s, job.AfterAdoption ?? Task.CompletedTask);
        else
            Finish(s);
        return SaveOutcome.Done;
    }

    /// <summary>The copy's window closed and Healdan has its content: the copy goes, unless it changed meanwhile.</summary>
    private void Finish(Session s)
    {
        Forget(s);
        if (_store.HasUnconfirmedChanges(s.Path))
            _events.Error($"#{s.DocNumber} changed after it was checked in; its copy is kept.");
        else
            _store.Delete(s.Path);
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
            old = new Session(path, record.DocNumber, record.ReadOnly, SendAsync, _timings, SetWaiting) { Closing = true };
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
        }
        catch (OperationCanceledException)
        {
            return;
        }
        Forget(original);
        _store.Delete(original.Path);
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
        _events.Crashed(s.Path);
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
        _events.SignInNeeded();
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
        _events.Waiting(count);
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

    private bool HoldIsHere(HoldView? hold) =>
        hold is { Kind: "checkout" } && (hold.Device is null || hold.Device.Name == _deviceName);

    private bool SafeIsRunning()
    {
        try
        {
            return _app.IsRunning();
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
            _events.Error("The helper hit a problem watching its copies: " + e.Message);
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
