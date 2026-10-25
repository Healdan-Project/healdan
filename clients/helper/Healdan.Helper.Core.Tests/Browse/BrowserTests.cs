using System.Text.Json.Nodes;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Browse;
using static Healdan.Helper.Core.Tests.Browse.Views;

namespace Healdan.Helper.Core.Tests.Browse;

/// <summary>The Save and Open windows' toolbar, left pane and middle pane, free of windows (spec §5.8).</summary>
public class BrowserTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-10-25T12:00:00Z");

    private readonly FakeRoutes _api = new();

    public BrowserTests()
    {
        _api.Answer("GET /v1/me", User());
        _api.Answer("GET /v1/me/favorites", Page());
        foreach (var id in new long[] { 10, 11, 12 })
            _api.Answer($"GET /v1/folders/{id}", Folder(id));
        _api.Answer("GET /v1/folders/10/children?limit=200", Page(Folder(11)));
        _api.Answer("GET /v1/folders/11/children?limit=200", Page(Folder(12), Doc(1229, "Engagement letter.docx")));
        _api.Answer("GET /v1/folders/12/children?limit=200", Page());
        _api.Answer("GET /v1/documents/1229/versions?limit=20", Page(Version("1.0", "1.0", "2026-10-21T15:00:00Z")));
    }

    private Browser Make(BrowseMode mode) =>
        new(new HealdanApi(new Uri("https://healdan.test"), _api) { Token = "hd_test" }, mode, TimeZoneInfo.Utc, () => Now);

    [Theory]
    [InlineData(PlaceKind.RecentFolders, "GET /v1/folders/recent")]
    [InlineData(PlaceKind.RecentMatters, "GET /v1/matters/recent")]
    [InlineData(PlaceKind.MyMatters, "GET /v1/matters/mine")]
    [InlineData(PlaceKind.Favorites, "GET /v1/me/favorites")]
    [InlineData(PlaceKind.RecentDocuments, "GET /v1/documents/recent")]
    [InlineData(PlaceKind.CheckedOutToMe, "GET /v1/me/checkouts")]
    public async Task EachPlaceIsItsListInTheApi(PlaceKind kind, string call)
    {
        _api.Answer(call, Page());
        var browser = Make(BrowseMode.Open);
        var place = Place.ForOpen.Concat(Place.ForSave).First(p => p.Kind == kind);

        await browser.ShowAsync(place);

        Assert.Equal(call, _api.Calls.Last());
        Assert.Equal(place.Label, browser.Heading);
        Assert.Empty(browser.Entries);
        Assert.NotNull(browser.Notice); // an empty list says so
    }

    [Fact]
    public void TheLeftPanesAreTheSpecs()
    {
        Assert.Equal(["Recent Folders", "Recent Matters", "My Matters", "Clients", "Favorites"], Place.ForSave.Select(p => p.Label));
        Assert.Equal(["Recent Documents", "Checked Out to Me", "Favorites", "Recent Matters", "My Matters", "Recent Folders"],
            Place.ForOpen.Select(p => p.Label));
        Assert.Equal(3, Place.OpenBrowseFrom); // "Browse" heads the last three
    }

    [Fact]
    public async Task ClientsIsASearchByClientNotAList()
    {
        var browser = Make(BrowseMode.Save);
        await browser.StartAsync(Place.RecentFolders with { }, null);
        _api.Answer("GET /v1/folders/recent", Page());
        var before = _api.Calls.Count;

        await browser.ShowAsync(Place.Clients);

        Assert.Equal(before, _api.Calls.Count); // nothing listed
        Assert.Equal(SearchScope.Clients, browser.Scope);
        Assert.Empty(browser.Entries);
        Assert.Equal("Search clients by number or name", browser.SearchPlaceholder);

        _api.Answer("GET /v1/search?scope=clients&q=998&limit=50", Page(MatterView(3, 10)));
        await browser.SearchAsync("998");
        var matter = Assert.Single(browser.Entries);
        Assert.Equal(EntryKind.Matter, matter.Kind);
        Assert.Equal("998.1 – Healdan Demo", matter.Title);

        // Another place goes back to the window's own scope.
        await browser.ShowAsync(Place.RecentFolders);
        Assert.Equal(SearchScope.Folders, browser.Scope);
    }

    [Fact]
    public async Task FavoritesInSaveAreFoldersOnly()
    {
        _api.Answer("GET /v1/me/favorites", Page(Favorite(7, Folder(12)), Favorite(8, Doc(1229, "Engagement letter.docx"))));
        var save = Make(BrowseMode.Save);
        var open = Make(BrowseMode.Open);

        await save.ShowAsync(Place.Favorites);
        await open.ShowAsync(Place.Favorites);

        Assert.Equal(["Drafts"], save.Entries.Select(e => e.Title));
        Assert.Equal(["Drafts", "Engagement letter.docx"], open.Entries.Select(e => e.Title));
    }

    [Fact]
    public async Task AFolderShowsItsBreadcrumbAndChildren()
    {
        var browser = Make(BrowseMode.Save);

        await browser.OpenFolderAsync(11);

        Assert.Equal(["998.1 – Healdan Demo (Healdan)", "Documents"], browser.Crumbs.Select(c => c.Text));
        Assert.Equal([10L, 11L], browser.Crumbs.Select(c => c.FolderId));
        Assert.Equal(["Drafts", "Engagement letter.docx"], browser.Entries.Select(e => e.Title));
        var doc = browser.Entries[1];
        Assert.Equal(("1.0", "1229"), (doc.Version, doc.DocNumber));
        Assert.Equal("Wednesday", doc.Edited); // from its versions
        Assert.Equal("Search folders within 998.1 – Healdan Demo", browser.SearchPlaceholder);
    }

    [Fact]
    public async Task AMatterOpensItsWorkspace()
    {
        _api.Answer("GET /v1/matters/recent", Page(MatterView(3, 10, "2026-10-25T09:40:00Z")));
        var browser = Make(BrowseMode.Save);
        await browser.ShowAsync(Place.RecentMatters);
        var matter = Assert.Single(browser.Entries);
        Assert.Equal("09:40", matter.Edited); // Recent Matters knows the last change

        await browser.EnterAsync(matter);

        Assert.Equal(10, browser.Folder!.Id);
        Assert.Equal(["Documents"], browser.Entries.Select(e => e.Title));
    }

    [Fact]
    public async Task BackForwardAndUp()
    {
        var browser = Make(BrowseMode.Save);
        _api.Answer("GET /v1/folders/recent", Page(Folder(12)));
        await browser.ShowAsync(Place.RecentFolders);
        Assert.False(browser.CanBack);
        Assert.False(browser.CanUp);

        await browser.EnterAsync(browser.Entries[0]); // Drafts
        Assert.True(browser.CanUp);
        await browser.UpAsync(); // Documents
        Assert.Equal(11, browser.Folder!.Id);
        await browser.UpAsync(); // the matter's workspace
        Assert.Equal(10, browser.Folder!.Id);
        Assert.False(browser.CanUp); // a Store can't be browsed

        await browser.BackAsync();
        Assert.Equal(11, browser.Folder!.Id);
        await browser.BackAsync();
        await browser.BackAsync();
        Assert.Null(browser.Folder);
        Assert.Equal("Recent Folders", browser.Heading);
        Assert.False(browser.CanBack);
        Assert.True(browser.CanForward);

        await browser.ForwardAsync();
        Assert.Equal(12, browser.Folder!.Id);
    }

    [Fact]
    public async Task SearchesWithinTheMatterShown()
    {
        var browser = Make(BrowseMode.Save);
        await browser.OpenFolderAsync(12);
        _api.Answer("GET /v1/search?scope=folders&q=draft&within=10&limit=50", Page(Folder(12)));

        await browser.SearchAsync("draft");

        Assert.Equal(["Drafts"], browser.Entries.Select(e => e.Title));
        Assert.Null(browser.Folder);
        // Searching again refines within the same matter.
        _api.Answer("GET /v1/search?scope=documents&q=lease&within=10&limit=50", Page());
        browser.Scope = SearchScope.Documents;
        Assert.Equal("Search documents within 998.1 – Healdan Demo", browser.SearchPlaceholder);
        await browser.SearchAsync("lease");
        Assert.Equal("GET /v1/search?scope=documents&q=lease&within=10&limit=50", _api.Calls.Last());

        // Matters are never searched within a matter.
        _api.Answer("GET /v1/search?scope=matters&q=demo&limit=50", Page(MatterView(3, 10)));
        browser.Scope = SearchScope.Matters;
        await browser.SearchAsync("demo");
        Assert.Equal("GET /v1/search?scope=matters&q=demo&limit=50", _api.Calls.Last());
    }

    [Fact]
    public async Task APlaceListSearchesEverywhere()
    {
        var browser = Make(BrowseMode.Open);
        _api.Answer("GET /v1/documents/recent", Page());
        await browser.OpenFolderAsync(11);
        await browser.ShowAsync(Place.RecentDocuments);
        _api.Answer("GET /v1/search?scope=documents&q=lease&limit=50", Page());

        await browser.SearchAsync("lease");

        Assert.Equal("GET /v1/search?scope=documents&q=lease&limit=50", _api.Calls.Last());
    }

    [Fact]
    public async Task DocNumberOnEnterJumpsToTheDocument()
    {
        _api.Answer("GET /v1/lookup?q=%231234%20v2.1", """{"docNumber":1234,"version":"2.1"}""");
        _api.Answer("GET /v1/documents/1234", Doc(1234, "Lease agreement.docx", "2.2"));
        _api.Answer("GET /v1/documents/1234/versions?limit=20", Page(Version("2.2", "2.2", "2026-10-25T10:42:00Z")));
        var browser = Make(BrowseMode.Open);

        await browser.SearchAsync("#1234 v2.1");

        var jumped = Assert.Single(browser.Entries);
        Assert.Same(jumped, browser.Jumped);
        Assert.Equal(1234, jumped.Document!.DocNumber);
        Assert.Equal("2.1", browser.JumpedVersion); // an older version, which opens read-only
        Assert.Equal("10:42", jumped.Edited);
        Assert.DoesNotContain(_api.Calls, c => c.StartsWith("GET /v1/search", StringComparison.Ordinal));
    }

    [Fact]
    public async Task DocNumberInSaveOpensTheFolderItsIn()
    {
        _api.Answer("GET /v1/lookup?q=%231229", """{"docNumber":1229,"version":"1.0"}""");
        _api.Answer("GET /v1/documents/1229", Doc(1229, "Engagement letter.docx"));
        var browser = Make(BrowseMode.Save);

        await browser.SearchAsync("#1229");

        Assert.Equal(11, browser.Folder!.Id);
        Assert.Equal(1229, browser.Jumped!.Document!.DocNumber);
    }

    [Fact]
    public async Task AnUnknownDocNumberSaysSo()
    {
        _api.Problem("GET /v1/lookup?q=%239999", 404, "not-found", "No document #9999");
        var browser = Make(BrowseMode.Open);

        await browser.SearchAsync("#9999");

        Assert.Equal("No document #9999", browser.Notice);
        Assert.Null(browser.Jumped);
    }

    [Fact]
    public async Task TheTargetIsTheSelectedFolderOrTheOneShown()
    {
        var browser = Make(BrowseMode.Save);
        await browser.OpenFolderAsync(11);
        var drafts = browser.Entries[0];
        var doc = browser.Entries[1];

        Assert.Equal(12, browser.TargetFolder(drafts)!.Id); // a subfolder selected, as in the mockup
        Assert.Equal(11, browser.TargetFolder(doc)!.Id);
        Assert.Equal(11, browser.TargetFolder(null)!.Id);

        _api.Answer("GET /v1/matters/mine", Page(MatterView(3, 10)));
        await browser.ShowAsync(Place.MyMatters);
        Assert.Null(browser.TargetFolder(browser.Entries[0])); // a matter's top level takes no documents
        Assert.Null(browser.TargetFolder(null));
    }

    [Fact]
    public async Task TheStarAddsAndRemovesAFavorite()
    {
        _api.Answer("POST /v1/me/favorites", Favorite(7, Folder(11)), System.Net.HttpStatusCode.Created);
        _api.Answer("DELETE /v1/me/favorites/7", "", System.Net.HttpStatusCode.NoContent);
        var browser = Make(BrowseMode.Save);
        await browser.StartAsync(Place.RecentFolders, 11);
        Assert.False(browser.IsFavorite(browser.Folder!));

        await browser.ToggleFavoriteAsync(browser.Folder!);
        Assert.True(browser.IsFavorite(browser.Folder!));
        Assert.Equal(("POST /v1/me/favorites", """{"folderId":11}"""), _api.Bodies.Last());

        await browser.ToggleFavoriteAsync(browser.Folder!);
        Assert.False(browser.IsFavorite(browser.Folder!));
        Assert.Equal("DELETE /v1/me/favorites/7", _api.Calls.Last());
    }

    [Fact]
    public async Task ADocumentCanBeAFavoriteToo()
    {
        _api.Answer("GET /v1/me/favorites", Page(Favorite(8, Doc(1229, "Engagement letter.docx"))));
        var browser = Make(BrowseMode.Open);
        await browser.StartAsync(Place.RecentFolders, 11);

        Assert.True(browser.IsFavorite(browser.Entries[1].Document!));
    }

    [Fact]
    public async Task NewFolderIsMadeInTheFolderShown()
    {
        _api.Answer("POST /v1/folders", Folder(12), System.Net.HttpStatusCode.Created);
        var browser = Make(BrowseMode.Save);
        await browser.OpenFolderAsync(11);

        var made = await browser.NewFolderAsync("Drafts");

        Assert.Equal(12, made!.Id);
        Assert.Equal(("POST /v1/folders", """{"parentId":11,"name":"Drafts"}"""), _api.Bodies.Last());
        Assert.Equal(12, browser.Jumped!.Folder!.Id); // selected in the reloaded list
        Assert.Equal(11, browser.Folder!.Id);
    }

    [Fact]
    public async Task ARefusedNewFolderSaysWhy()
    {
        _api.Problem("POST /v1/folders", 403, "forbidden", "You can't add folders here.");
        var browser = Make(BrowseMode.Save);
        await browser.OpenFolderAsync(11);

        Assert.Null(await browser.NewFolderAsync("Mine"));
        Assert.Equal("You can't add folders here.", browser.Notice);
    }

    [Fact]
    public async Task WhoHoldsEachDocument()
    {
        _api.Answer("GET /v1/documents/recent", Page(
            Doc(1234, "Lease agreement.docx", hold: Hold(Me, "Robert Example", "MacBook", "LibreOffice")),
            Doc(1207, "Board minutes.docx", hold: Hold(2, "Jane Smith")),
            Doc(1231, "Heads of terms.docx")));
        foreach (var n in new[] { 1234, 1207, 1231 })
            _api.Answer($"GET /v1/documents/{n}/versions?limit=20", Page(Version("1.0", "1.0", "2026-10-19T08:00:00Z")));
        var browser = Make(BrowseMode.Open);
        await browser.StartAsync(Place.RecentDocuments, null);

        Assert.Equal(["checked out to you on MacBook", "checked out to Jane Smith", ""], browser.Entries.Select(e => e.Holder));
        Assert.Equal([true, false, false], browser.Entries.Select(e => e.HeldByMe));
        Assert.All(browser.Entries, e => Assert.Equal("Monday", e.Edited)); // the last save, never the check-out
    }

    [Fact]
    public async Task ARefusalIsShownNotThrown()
    {
        _api.Problem("GET /v1/folders/1", 409, "not-browsable", "A Store can't be browsed: find a matter by search.");
        var browser = Make(BrowseMode.Save);

        await browser.OpenFolderAsync(1);

        Assert.Equal("A Store can't be browsed: find a matter by search.", browser.Notice);
        Assert.Empty(browser.Entries);
    }
}
