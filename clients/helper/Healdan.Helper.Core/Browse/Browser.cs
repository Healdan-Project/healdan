using Healdan.Helper.Core.Api;

namespace Healdan.Helper.Core.Browse;

/// <summary>
/// The Save and Open windows' toolbar, left pane and middle pane, free of windows (spec §5.8): the places, a folder's
/// breadcrumb and contents, back, forward and up, the scope picker's searches (within the matter shown, spec §9.1), a
/// typed <c>#1234</c>, the favourite star and New Folder.
/// <para>A window calls it on its UI thread and listens to <see cref="Changed"/>; the calls' continuations come back to
/// that thread, so its state only ever changes there. A navigation that a newer one overtook is dropped. Nothing
/// throws: a refusal or an unreachable server becomes <see cref="Notice"/>.</para>
/// </summary>
public sealed class Browser : IDisposable
{
    /// <summary>The most of a folder's children shown; the notice says when there are more.</summary>
    public const int ChildLimit = 200;

    public const int SearchLimit = 50;

    /// <summary>How many timeline rows are read for a document's last save: the newest save is almost always among them.</summary>
    public const int VersionLimit = 20;

    /// <summary>How many documents' versions are read at once for the Edited column.</summary>
    private const int VersionReads = 4;

    private static readonly TimeSpan CallTimeout = TimeSpan.FromSeconds(30);

    private readonly HealdanApi _api;
    private readonly TimeZoneInfo? _zone;
    private readonly Func<DateTimeOffset> _now;
    private readonly CancellationTokenSource _closed = new();
    private readonly List<View> _history = [];
    private readonly Dictionary<long, long> _favoriteFolders = [];
    private readonly Dictionary<long, long> _favoriteDocuments = [];
    private int _at = -1;
    private int _generation;
    private long? _withinId;
    private string? _withinName;

    /// <param name="zone">The time zone times are shown in; the computer's by default.</param>
    /// <param name="now">The clock, for the tests.</param>
    public Browser(HealdanApi api, BrowseMode mode, TimeZoneInfo? zone = null, Func<DateTimeOffset>? now = null)
    {
        _api = api;
        Mode = mode;
        _zone = zone;
        _now = now ?? (() => DateTimeOffset.Now);
        _scope = DefaultScope;
    }

    private SearchScope DefaultScope => Mode == BrowseMode.Save ? SearchScope.Folders : SearchScope.Documents;

    /// <summary>Something shown changed. Raised on the thread that called in.</summary>
    public event Action? Changed;

    public BrowseMode Mode { get; }

    /// <summary>The scope picker's choice: Folders in Save, Documents in Open, Clients after the Clients place.</summary>
    public SearchScope Scope
    {
        get => _scope;
        set
        {
            _scope = value;
            Changed?.Invoke();
        }
    }

    private SearchScope _scope;

    /// <summary>The signed-in user, once Healdan has said who: holds by them read "checked out to you".</summary>
    public long? Me { get; private set; }

    /// <summary>The place shown, or null for a folder or search results.</summary>
    public Place? Place { get; private set; }

    /// <summary>The folder shown, or null for a place's list or search results.</summary>
    public FolderView? Folder { get; private set; }

    /// <summary>From the matter down to <see cref="Folder"/>; empty for a list.</summary>
    public IReadOnlyList<Crumb> Crumbs { get; private set; } = [];

    public IReadOnlyList<Entry> Entries { get; private set; } = [];

    /// <summary>What's shown: the place's name, the folder's, or what was searched.</summary>
    public string Heading { get; private set; } = "";

    /// <summary>Why nothing is shown, or that not everything is; null when all is well.</summary>
    public string? Notice { get; private set; }

    public bool Busy { get; private set; }

    /// <summary>The entry a typed doc # or a new folder jumped to, for the window to select.</summary>
    public Entry? Jumped { get; private set; }

    /// <summary>The version a typed doc # named, such as <c>2.1</c> for <c>#1234 v2.1</c>.</summary>
    public string? JumpedVersion { get; private set; }

    public bool CanBack => _at > 0;

    public bool CanForward => _at < _history.Count - 1;

    /// <summary>Up stops at the matter's workspace: a Store can't be browsed.</summary>
    public bool CanUp => Parent is not null;

    public string SearchPlaceholder => Scope switch
    {
        SearchScope.Clients => "Search clients by number or name",
        SearchScope.Matters => "Search matters",
        _ when _withinName is not null => $"Search {Scope.ApiName()} within {_withinName}",
        _ => $"Search {Scope.ApiName()}"
    };

    /// <summary>
    /// The window opens: who the user is and their favourites are read, then <paramref name="folderId"/> is shown, as for
    /// Save as New Document's current folder, or else <paramref name="first"/>.
    /// </summary>
    public async Task StartAsync(Place first, long? folderId)
    {
        try
        {
            using var call = Call();
            var me = _api.MeAsync(call.Token);
            var favorites = _api.ListAsync("v1/me/favorites", call.Token);
            Me = (await me).Id;
            Remember(await favorites);
        }
        catch (Exception e) when (Expected(e))
        {
            // The lists still show; holds read as someone's and stars as off until the next window.
        }
        if (folderId is { } id)
            await OpenFolderAsync(id);
        else
            await ShowAsync(first);
    }

    public Task ShowAsync(Place place) => GoAsync(new PlaceView(place), push: true);

    public Task OpenFolderAsync(long id) => GoAsync(new FolderShown(id), push: true);

    /// <summary>Double-click or Enter on a row: a folder or a matter opens; a document is the window's.</summary>
    public Task EnterAsync(Entry entry) => entry switch
    {
        { Folder: { } folder } => OpenFolderAsync(folder.Id),
        { Matter: { } matter } => OpenFolderAsync(matter.FolderId),
        _ => Task.CompletedTask
    };

    public Task BackAsync() => CanBack ? GoAsync(_history[--_at], push: false) : Task.CompletedTask;

    public Task ForwardAsync() => CanForward ? GoAsync(_history[++_at], push: false) : Task.CompletedTask;

    public Task UpAsync() => Parent is { } parent ? OpenFolderAsync(parent.Id) : Task.CompletedTask;

    /// <summary>
    /// The search box's Enter. A doc # (<c>#1234</c>, <c>#1234 v2.1</c>) jumps to the document: in Open it's listed
    /// alone, with the version it names; in Save its folder opens. Anything else searches the scope picked, within the
    /// matter shown for folders and documents.
    /// </summary>
    public Task SearchAsync(string text)
    {
        var q = text.Trim();
        if (q.Length == 0)
            return Task.CompletedTask;
        return GoAsync(new SearchView(Scope, q, Scope.TakesWithin() ? _withinId : null, q.StartsWith('#')), push: true);
    }

    /// <summary>
    /// The folder a new document would be filed in: a folder selected in the list (as Drafts in the mockup), else the
    /// folder shown. Null when that's nothing, or a matter, whose top level takes no documents.
    /// </summary>
    public FolderView? TargetFolder(Entry? selected) => selected switch
    {
        { Kind: EntryKind.Folder, Folder: { } folder } => folder,
        { Kind: EntryKind.Matter } => null,
        _ => Folder
    };

    public bool IsFavorite(FolderView folder) => _favoriteFolders.ContainsKey(folder.Id);

    public bool IsFavorite(DocumentView doc) => _favoriteDocuments.ContainsKey(doc.DocNumber);

    /// <summary>The star: adds the folder to Favorites, or removes it.</summary>
    public Task ToggleFavoriteAsync(FolderView folder) => ToggleAsync(_favoriteFolders, folder.Id, folderId: folder.Id, docNumber: null);

    public Task ToggleFavoriteAsync(DocumentView doc) => ToggleAsync(_favoriteDocuments, doc.DocNumber, folderId: null, docNumber: doc.DocNumber);

    /// <summary>New Folder (spec §9.2), in the folder shown; the list is read again with the new folder selected.</summary>
    public async Task<FolderView?> NewFolderAsync(string name)
    {
        if (Folder is not { } parent)
            return null;
        FolderView made;
        try
        {
            using var call = Call();
            made = await _api.CreateFolderAsync(parent.Id, name.Trim(), call.Token);
        }
        catch (Exception e) when (Expected(e))
        {
            Notice = Say(e);
            Changed?.Invoke();
            return null;
        }
        await GoAsync(new FolderShown(parent.Id), push: false);
        Jumped = Entries.FirstOrDefault(entry => entry.Folder?.Id == made.Id);
        Changed?.Invoke();
        return made;
    }

    /// <summary>The window closed: calls still going stop. The source isn't disposed, as they may still ask it for a token.</summary>
    public void Dispose() => _closed.Cancel();

    // --- Views ------------------------------------------------------------------------------------------------------

    private abstract record View;

    private sealed record PlaceView(Place Place) : View;

    private sealed record FolderShown(long Id) : View;

    private sealed record SearchView(SearchScope Scope, string Text, long? Within, bool DocNumber) : View;

    private FolderRef? Parent =>
        Folder is { Path: { Count: >= 2 } path } && path[^2].Kind != "store" ? path[^2] : null;

    private async Task GoAsync(View view, bool push)
    {
        if (push)
        {
            _history.RemoveRange(_at + 1, _history.Count - _at - 1);
            _history.Add(view);
            _at = _history.Count - 1;
        }
        var generation = ++_generation;
        (Busy, Notice, Jumped, JumpedVersion) = (true, null, null, null);
        Changed?.Invoke();
        try
        {
            using var call = Call();
            switch (view)
            {
                case PlaceView { Place: var place }:
                    await ShowPlaceAsync(place, generation, call.Token);
                    break;
                case FolderShown { Id: var id }:
                    await ShowFolderAsync(id, generation, call.Token);
                    break;
                case SearchView search:
                    await ShowSearchAsync(search, generation, call.Token);
                    break;
            }
        }
        catch (Exception e) when (Expected(e))
        {
            if (generation != _generation)
                return;
            (Entries, Folder, Crumbs, Notice) = ([], null, [], Say(e));
        }
        if (generation != _generation)
            return;
        Busy = false;
        Changed?.Invoke();
        await LoadEditedAsync(Entries, generation);
    }

    private async Task ShowPlaceAsync(Place place, int generation, CancellationToken ct)
    {
        (Place, Folder, Crumbs, Heading, _withinId, _withinName) = (place, null, [], place.Label, null, null);
        if (place.Kind == PlaceKind.Clients)
        {
            Entries = [];
            _scope = SearchScope.Clients;
            return;
        }
        if (_scope == SearchScope.Clients)
            _scope = DefaultScope; // Clients was a search by client, not a place to stay in
        var page = await _api.ListAsync(place.Path!, ct);
        if (generation != _generation)
            return;
        if (place.Kind == PlaceKind.Favorites)
            Remember(page);
        Entries = [.. page.Items.Where(Shows).Select(item => new Entry(item, Me))];
        if (Entries.Count == 0)
            Notice = place.Kind switch
            {
                PlaceKind.Favorites => Mode == BrowseMode.Save
                    ? "No favourite folders yet: star a folder to add it."
                    : "No favourites yet: star a folder or document to add it.",
                PlaceKind.CheckedOutToMe => "You have nothing checked out.",
                _ => $"Nothing in {place.Label} yet."
            };
    }

    private async Task ShowFolderAsync(long id, int generation, CancellationToken ct)
    {
        var folder = _api.FolderAsync(id, ct);
        var children = _api.ChildrenAsync(id, ChildLimit, ct);
        await Task.WhenAll(folder, children);
        if (generation != _generation)
            return;
        var f = await folder;
        var page = await children;
        (Place, Folder, Heading) = (null, f, f.Name);
        // From the matter down; the matter reads as people know it, with its client and Store.
        Crumbs = [.. f.Path.Where(p => p.Kind != "store")
            .Select(p => p.Kind == "matter" && f.Matter is { } m ? new Crumb(p.Id, m.Display, Matters.CrumbNote(m, f.Store)) : new Crumb(p.Id, p.Name))];
        if (f.Path.FirstOrDefault(p => p.Kind == "matter") is { } workspace)
            (_withinId, _withinName) = (workspace.Id, f.Matter?.Display ?? workspace.Name);
        else
            (_withinId, _withinName) = (f.Id, f.Name);
        Entries = [.. page.Items.Where(Shows).Select(item => new Entry(item, Me))];
        if (page.Total is { } total && total > page.Items.Count)
            Notice = $"Showing the first {page.Items.Count} of {total}: search to find the rest.";
    }

    private async Task ShowSearchAsync(SearchView search, int generation, CancellationToken ct)
    {
        (Place, Folder, Crumbs) = (null, null, []);
        if (search.DocNumber)
        {
            var found = await _api.LookupAsync(search.Text, ct);
            var doc = await _api.DocumentAsync(found.DocNumber, ct);
            if (generation != _generation)
                return;
            if (Mode == BrowseMode.Save)
            {
                await ShowFolderAsync(doc.Folder.Id, generation, ct);
                Jumped = Entries.FirstOrDefault(e => e.Document?.DocNumber == doc.DocNumber);
                return;
            }
            Heading = search.Text;
            Entries = [new Entry(new ListItem(null, doc, null, null), Me)];
            (Jumped, JumpedVersion) = (Entries[0], found.Version);
            return;
        }
        var page = await _api.SearchAsync(search.Scope.ApiName(), search.Text, search.Within, SearchLimit, ct);
        if (generation != _generation)
            return;
        Heading = $"{search.Scope.Label()} matching “{search.Text}”";
        Entries = [.. page.Items.Where(Shows).Select(item => new Entry(item, Me))];
        Notice = Entries.Count == 0 ? "Nothing found."
            : page.Items.Count >= SearchLimit ? $"Showing the first {SearchLimit}: add words to narrow the search." : null;
    }

    /// <summary>Save picks a folder, so its lists leave out favourite documents; everything else is shown.</summary>
    private bool Shows(ListItem item) => Mode == BrowseMode.Open || item.FavoriteId is null || item.Folder is not null;

    /// <summary>
    /// The Edited column: a document's last save, from its versions, never its check-out (spec §9.10); a matter's last
    /// change. A few documents are read at a time, and a newer navigation stops it.
    /// </summary>
    private async Task LoadEditedAsync(IReadOnlyList<Entry> entries, int generation)
    {
        foreach (var entry in entries.Where(e => e.Matter?.LastChange is not null))
            entry.Edited = When.Short(entry.Matter!.LastChange, _now(), _zone);
        using var gate = new SemaphoreSlim(VersionReads);
        await Task.WhenAll(entries.Where(e => e.Document is not null).Select(async entry =>
        {
            await gate.WaitAsync();
            try
            {
                if (generation != _generation || _closed.IsCancellationRequested)
                    return;
                using var call = Call();
                var doc = entry.Document!;
                var versions = await _api.VersionsAsync(doc.DocNumber, VersionLimit, call.Token);
                if (generation == _generation)
                    entry.Edited = When.Short(Versions.LastSaved(versions.Items, doc.FileVersion), _now(), _zone);
            }
            catch (Exception e) when (Expected(e))
            {
                // Left blank: the column only helps to tell documents apart.
            }
            finally
            {
                gate.Release();
            }
        }));
    }

    private async Task ToggleAsync(Dictionary<long, long> favorites, long key, long? folderId, long? docNumber)
    {
        try
        {
            using var call = Call();
            if (favorites.TryGetValue(key, out var id))
            {
                await _api.RemoveFavoriteAsync(id, call.Token);
                favorites.Remove(key);
            }
            else
                favorites[key] = (await _api.AddFavoriteAsync(folderId, docNumber, call.Token)).Id;
        }
        catch (Exception e) when (Expected(e))
        {
            Notice = Say(e);
        }
        Changed?.Invoke();
    }

    private void Remember(Page<ListItem> favorites)
    {
        _favoriteFolders.Clear();
        _favoriteDocuments.Clear();
        foreach (var item in favorites.Items)
        {
            if (item.FavoriteId is not { } id)
                continue;
            if (item.Folder is { } folder)
                _favoriteFolders[folder.Id] = id;
            else if (item.Document is { } doc)
                _favoriteDocuments[doc.DocNumber] = id;
        }
    }

    /// <summary>A call's token: cancelled when the window closes, or after <see cref="CallTimeout"/>.</summary>
    private CancellationTokenSource Call()
    {
        var call = CancellationTokenSource.CreateLinkedTokenSource(_closed.Token);
        call.CancelAfter(CallTimeout);
        return call;
    }

    /// <summary>
    /// Every failure is shown, never thrown: the windows call in from UI events, where an exception would end the
    /// helper, and with it the saves it's sending.
    /// </summary>
    private static bool Expected(Exception e) => e is not OutOfMemoryException;

    /// <summary>A failure in a sentence for the window.</summary>
    private static string Say(Exception e) => e switch
    {
        ApiException { Status: 401 } => "Sign in to Healdan again, then try once more.",
        ApiException { Detail.Length: > 0 } api => api.Detail,
        ApiException api => $"Healdan refused ({api.Status}).",
        OperationCanceledException => "Healdan didn't answer in time. Try again in a moment.",
        HttpRequestException => "Healdan can't be reached. Try again in a moment.",
        _ => $"Healdan's answer couldn't be read ({e.GetType().Name}). Try again in a moment."
    };
}
