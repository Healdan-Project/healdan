using System.Net;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Tests.Browse;
using static Healdan.Helper.Core.Tests.Browse.Views;

namespace Healdan.Helper.Core.Tests.Api;

/// <summary>The API calls the windows make (Task 17), as the contract has them.</summary>
public class WindowCallsTests
{
    private readonly FakeRoutes _routes = new();

    private HealdanApi Api() => new(new Uri("https://healdan.test"), _routes) { Token = "hd_test" };

    [Fact]
    public async Task AThumbnailNotMadeYetIsNull()
    {
        _routes.Problem("GET /v1/documents/5/thumbnail", 404, "not-found", "No thumbnail yet.");
        _routes.AnswerBytes("GET /v1/documents/6/thumbnail", [0x89, 0x50, 0x4E, 0x47]);
        _routes.Problem("GET /v1/documents/7/thumbnail", 403, "forbidden", "No download permission.");

        Assert.Null(await Api().ThumbnailAsync(5));
        Assert.Equal([0x89, 0x50, 0x4E, 0x47], await Api().ThumbnailAsync(6));
        Assert.Null(await Api().ThumbnailAsync(7));
    }

    [Fact]
    public async Task ListsReadFoldersDocumentsMattersAndFavorites()
    {
        _routes.Answer("GET /v1/me/favorites", Page(Favorite(7, Folder(12)), Favorite(8, Doc(1229, "Engagement letter.docx"))));
        _routes.Answer("GET /v1/search?scope=clients&q=998&limit=50", Page(MatterView(3, 10)));

        var favorites = await Api().ListAsync("v1/me/favorites");
        var hits = await Api().SearchAsync("clients", "998", null, 50);

        Assert.Equal((7L, 12L), (favorites.Items[0].FavoriteId!.Value, favorites.Items[0].Folder!.Id));
        Assert.Equal((8L, 1229L), (favorites.Items[1].FavoriteId!.Value, favorites.Items[1].Document!.DocNumber));
        Assert.Equal(10, hits.Items[0].Matter!.FolderId);
    }

    [Fact]
    public async Task SearchAndLookupEncodeTheirText()
    {
        _routes.Answer("GET /v1/search?scope=documents&q=%231234%20v2.1&within=10&limit=50", Page());
        _routes.Answer("GET /v1/lookup?q=%231234%20v2.1", """{"docNumber":1234,"version":"2.1"}""");

        await Api().SearchAsync("documents", "#1234 v2.1", 10, 50);
        var found = await Api().LookupAsync("#1234 v2.1");

        Assert.Equal(new LookupView(1234, "2.1"), found);
    }

    [Fact]
    public async Task NewFoldersAndFavoritesSendTheContractsBodies()
    {
        _routes.Answer("POST /v1/folders", Folder(12), HttpStatusCode.Created);
        _routes.Answer("POST /v1/me/favorites", Favorite(9, Doc(1229, "Engagement letter.docx")), HttpStatusCode.Created);

        var folder = await Api().CreateFolderAsync(11, "Drafts");
        var favorite = await Api().AddFavoriteAsync(null, 1229);

        Assert.Equal("Drafts", folder.Name);
        Assert.Equal(9, favorite.Id);
        Assert.Equal(1229, favorite.Document()!.DocNumber);
        Assert.Equal(["""{"parentId":11,"name":"Drafts"}""", """{"docNumber":1229}"""], _routes.Bodies.Select(b => b.Body));
    }

    [Fact]
    public async Task FindsPeopleForAUserField()
    {
        _routes.Answer("GET /v1/users?q=pat", Page(new JsonObject { ["id"] = 7, ["username"] = "pat", ["name"] = "Pat Example" }));

        var people = await Api().FindUsersAsync("pat");

        Assert.Equal(new UserHit(7, "pat", "Pat Example"), Assert.Single(people.Items));
    }
}
