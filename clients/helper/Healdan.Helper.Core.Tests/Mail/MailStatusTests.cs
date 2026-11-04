// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Text.Json.Nodes;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Mail;
using Healdan.Helper.Core.Tests.Cycle;
using Healdan.Helper.Core.Tests.Ui;

namespace Healdan.Helper.Core.Tests.Mail;

/// <summary>
/// <c>mail/status</c> (M5 spec §6.7, §7.2): whether a message is filed and where, whether its thread went to another
/// matter, and the folders to suggest. The facts come from the fake server's <c>GET /emails</c>; the folders from its
/// <c>GET /folders</c>.
/// </summary>
public sealed class MailStatusTests : IDisposable
{
    private static readonly DateTimeOffset Day = DateTimeOffset.Parse("2026-11-01T09:00:00Z");
    private const string ThisMatter = "1.1 – Smoke Matter";
    private const string OtherMatter = "2.1 – Other Matter";

    private readonly string _root = Directory.CreateTempSubdirectory("healdan-mail-").FullName;
    private readonly FakeApi _server = new();

    public MailStatusTests()
    {
        _server.AddFolder(11, "Correspondence");
        _server.AddFolder(12, "Drafts");
        _server.AddFolder(13, "Filings");
        _server.AddFolder(20, "Correspondence", 4, OtherMatter);
    }

    public void Dispose() => TempFolder.Delete(_root);

    private string Log => File.Exists(HelperPaths.For(_root).Log) ? File.ReadAllText(HelperPaths.For(_root).Log) : "";

    private MailFiling Mail() => new(new HealdanApi(new Uri("https://healdan.test"), _server) { Token = "hd_test" },
        new FakeWindows(), new FakeNotifier(), new HelperLog(HelperPaths.For(_root).Log));

    private static (long Id, string Path)[] Suggested(JsonObject status) =>
        [.. status["suggestions"]!.AsArray().Select(s => ((long)s!["folderId"]!, (string)s["path"]!))];

    private static long[] SuggestedIds(JsonObject status) => [.. Suggested(status).Select(s => s.Id)];

    /// <summary>The ids the one <c>GET /emails</c> asked for, as the request line carried them.</summary>
    private string[] AskedIds()
    {
        var call = Assert.Single(_server.Calls, c => c.StartsWith("GET /emails?", StringComparison.Ordinal));
        return [.. call["GET /emails?".Length..].Split('&').Select(p => Uri.UnescapeDataString(p["messageId=".Length..]))];
    }

    [Fact]
    public async Task AMessageFiledWhereItsThreadWentIsFiledWithItsCopies()
    {
        _server.AddEmailCopy("<m1@example.com>", 50, 11, Day);
        _server.AddEmailCopy("parent@example.com", 51, 12, Day.AddDays(-1));

        var status = await Mail().StatusAsync(["m1@example.com", "<parent@example.com>"]);

        Assert.Equal(["filed", "copies", "elsewhere", "suggestions"], status.Select(p => p.Key));
        Assert.Equal(true, (bool?)status["filed"]);
        Assert.Equal(false, (bool?)status["elsewhere"]);
        var copy = Assert.Single(status["copies"]!.AsArray())!.AsObject();
        Assert.Equal(["docNumber", "name", "matter", "folder", "address"], copy.Select(p => p.Key));
        Assert.Equal((50L, "Message.eml", ThisMatter, "Correspondence"), ((long)copy["docNumber"]!, (string)copy["name"]!, (string)copy["matter"]!, (string)copy["folder"]!));
        // The address is the server's, which names the document's internal id: the pane links to it as it is.
        Assert.Equal($"http://localhost:8080/frontend.jsp?docId={_server.Doc(50).Id}", (string)copy["address"]!);
        // Only the message's own copies are its copies. The parent's folder isn't offered either: it's in the matter that
        // holds the message already, where File Here could only answer already-filed.
        Assert.Empty(Suggested(status));
    }

    [Fact]
    public async Task NoFolderInAMatterThatHoldsTheMessageIsSuggested()
    {
        _server.AddFolder(21, "Drafts", 4, OtherMatter);
        _server.AddEmailCopy("m1@example.com", 50, 11, Day);                // the message is in this matter
        _server.AddEmailCopy("p1@example.com", 51, 12, Day.AddDays(2));     // so the thread's folder here isn't offered,
        _server.AddEmailCopy("p2@example.com", 52, 20, Day.AddDays(1));     // the other matter's is
        _server.SetRecentFolders(13, 21, 11);                              // and of the recent ones, only the other matter's

        var status = await Mail().StatusAsync(["m1@example.com", "p1@example.com", "p2@example.com"]);

        Assert.Equal(true, (bool?)status["filed"]);
        Assert.Equal([(20L, $"{OtherMatter} › Correspondence"), (21L, $"{OtherMatter} › Drafts")], Suggested(status));
    }

    [Fact]
    public async Task AFolderThatCantBeReadForNowLeavesOutTheSuggestionsButNotTheStatus()
    {
        _server.AddEmailCopy("m1@example.com", 50, 20, Day);
        _server.AddEmailCopy("p1@example.com", 51, 12, Day.AddDays(1));
        _server.BreakFolder(12); // the server is in trouble: not gone, nor refused

        var status = await Mail().StatusAsync(["m1@example.com", "p1@example.com"]);

        Assert.Equal(true, (bool?)status["filed"]);
        Assert.Equal(50L, (long?)status["copies"]![0]!["docNumber"]);
        Assert.Equal(true, (bool?)status["elsewhere"]);
        Assert.Empty(Suggested(status));
    }

    [Fact]
    public async Task ANewMessageIsNotFiledAndIsOfferedTheRecentFolders()
    {
        _server.SetRecentFolders(12, 11);

        var status = await Mail().StatusAsync(["new@example.com"]);

        Assert.Equal(false, (bool?)status["filed"]);
        Assert.Empty(status["copies"]!.AsArray());
        Assert.Equal(false, (bool?)status["elsewhere"]);
        Assert.Equal([12L, 11L], SuggestedIds(status));
    }

    [Fact]
    public async Task AMessageFiledOnlyInAnotherMatterThanItsThreadsIsElsewhere()
    {
        _server.AddEmailCopy("m1@example.com", 50, 20, Day);
        _server.AddEmailCopy("parent@example.com", 51, 11, Day.AddDays(-1));

        var status = await Mail().StatusAsync(["m1@example.com", "parent@example.com"]);

        Assert.Equal(true, (bool?)status["filed"]); // it is filed, in the other matter
        Assert.Equal(true, (bool?)status["elsewhere"]);
        Assert.Equal(OtherMatter, (string?)status["copies"]![0]!["matter"]);
        Assert.Equal([11L], SuggestedIds(status)); // and the thread's folder is there to file it again
    }

    [Fact]
    public async Task ElsewhereNeedsACopyAndAThreadCopyInAMatterNoneOfTheMessagesCopiesIsIn()
    {
        // Filed in both matters: the thread's matter is one of its.
        _server.AddEmailCopy("both@example.com", 50, 11, Day);
        _server.AddEmailCopy("both@example.com", 52, 20, Day);
        _server.AddEmailCopy("p1@example.com", 51, 20, Day);
        Assert.Equal(false, (bool?)(await Mail().StatusAsync(["both@example.com", "p1@example.com"]))["elsewhere"]);

        // Not filed at all: nothing to be elsewhere, however far the thread went.
        Assert.Equal((false, false), Flags(await Mail().StatusAsync(["none@example.com", "p1@example.com"])));

        // Filed, with no copy of the thread anywhere: the same matter as far as anyone knows.
        _server.AddEmailCopy("alone@example.com", 53, 11, Day);
        Assert.Equal((true, false), Flags(await Mail().StatusAsync(["alone@example.com", "unfiled-parent@example.com"])));

        // The thread's newest copy decides, not an older one: it went to the other matter, then came back.
        _server.AddEmailCopy("back@example.com", 54, 11, Day);
        _server.AddEmailCopy("old-parent@example.com", 55, 20, Day.AddDays(-5));
        _server.AddEmailCopy("new-parent@example.com", 56, 11, Day.AddDays(-1));
        Assert.Equal((true, false), Flags(await Mail().StatusAsync(["back@example.com", "old-parent@example.com", "new-parent@example.com"])));
        // ... and the other way about: it was in this matter once, and the thread has moved on.
        _server.AddEmailCopy("left@example.com", 57, 11, Day);
        _server.AddEmailCopy("newer-parent@example.com", 58, 20, Day.AddDays(2));
        Assert.Equal((true, true), Flags(await Mail().StatusAsync(["left@example.com", "new-parent@example.com", "newer-parent@example.com"])));
    }

    private static (bool Filed, bool Elsewhere) Flags(JsonObject status) => ((bool)status["filed"]!, (bool)status["elsewhere"]!);

    [Fact]
    public async Task TheThreadsFoldersComeBeforeRecentOnesAndNeitherRepeats()
    {
        _server.AddFolder(14, "Closed", canFile: false);
        _server.AddEmailCopy("p1@example.com", 51, 11, Day);
        _server.AddEmailCopy("p2@example.com", 52, 12, Day.AddDays(2));
        _server.AddEmailCopy("p3@example.com", 53, 14, Day.AddDays(3)); // newest, but no filing there
        _server.SetRecentFolders(11, 13, 20, 14);

        var status = await Mail().StatusAsync(["m1@example.com", "p1@example.com", "p2@example.com", "p3@example.com"]);

        Assert.Equal([12L, 11L, 13L, 20L], SuggestedIds(status));
        Assert.Equal($"{ThisMatter} › Filings", Suggested(status)[2].Path);
        Assert.Equal($"{OtherMatter} › Correspondence", Suggested(status)[3].Path);
    }

    [Fact]
    public async Task EachThreadFolderIsReadOnceAndRecentFoldersAreLeftUnaskedWhenTheThreadFillsTheList()
    {
        var ids = new List<string> { "m1@example.com" };
        for (var i = 0; i < 6; i++)
        {
            _server.AddFolder(30 + i, $"Folder {i}");
            _server.AddEmailCopy($"p{i}@example.com", 60 + i, 30 + i, Day.AddDays(i));
            _server.AddEmailCopy($"q{i}@example.com", 70 + i, 30 + i, Day.AddDays(i + 10)); // a second copy in the same folder
            ids.Add($"p{i}@example.com");
            ids.Add($"q{i}@example.com");
        }

        var status = await Mail().StatusAsync(ids);

        Assert.Equal(5, SuggestedIds(status).Length);
        Assert.All(_server.Calls.Where(c => c.StartsWith("GET /folders/", StringComparison.Ordinal)), c => Assert.Equal(1, _server.Count(c)));
        Assert.DoesNotContain("GET /folders/recent", _server.Calls); // five from the thread: nothing more is needed
    }

    [Fact]
    public async Task AThreadFolderTheServerWontShowIsSkipped()
    {
        _server.AddEmailCopy("p1@example.com", 51, 99, Day.AddDays(5)); // folder 99 is gone, or isn't the person's to see
        _server.AddEmailCopy("p2@example.com", 52, 11, Day);

        var status = await Mail().StatusAsync(["m1@example.com", "p1@example.com", "p2@example.com"]);

        Assert.Equal([11L], SuggestedIds(status));
    }

    [Fact]
    public async Task AnswersAreMatchedToIdsByNormalisedIdNotByPosition()
    {
        _server.AddEmailCopy("m1@example.com", 50, 11, Day);

        // The own id comes with brackets and spaces, again without them, and after a thread id; the server answers each once, bare.
        var status = await Mail().StatusAsync([" <m1@example.com> ", "<p1@example.com>", "m1@example.com", "<p1@example.com>", "  "]);

        Assert.Equal(["m1@example.com", "p1@example.com"], AskedIds());
        Assert.Equal(true, (bool?)status["filed"]);
        Assert.Equal(50L, (long?)status["copies"]![0]!["docNumber"]);
    }

    [Fact]
    public async Task OnlyTheNewestIdsOfALongThreadAreAsked()
    {
        // Own, In-Reply-To, then References oldest first: 60 of them.
        var ids = new List<string> { "m1@example.com", "parent@example.com" };
        ids.AddRange(Enumerable.Range(1, 60).Select(i => $"r{i:D2}@example.com"));
        _server.AddEmailCopy("m1@example.com", 50, 20, Day); // in the other matter, so this one's folders are offered
        _server.AddEmailCopy("r01@example.com", 51, 12, Day.AddDays(9)); // the oldest reference: not asked, so unknown
        _server.AddEmailCopy("r60@example.com", 52, 13, Day);

        var status = await Mail().StatusAsync(ids);

        var asked = AskedIds();
        Assert.Equal(21, asked.Length);
        Assert.Equal(["m1@example.com", "parent@example.com"], asked[..2]);
        Assert.Equal(Enumerable.Range(42, 19).Select(i => $"r{i:D2}@example.com"), asked.Skip(2).Order()); // the last 19
        Assert.Equal([13L], SuggestedIds(status));
    }

    [Fact]
    public async Task ARequestOfLongIdsStaysUnderTheLimitOfServersAndProxies()
    {
        var ids = new List<string> { "m1@example.com" + new string('o', 900), "parent@example.com" + new string('p', 900) };
        ids.AddRange(Enumerable.Range(1, 30).Select(i => $"{i:D2}" + new string('r', 960)));
        _server.AddEmailCopy(ids[0], 50, 11, Day);

        var status = await Mail().StatusAsync(ids);

        var call = Assert.Single(_server.Calls, c => c.StartsWith("GET /emails?", StringComparison.Ordinal));
        Assert.True(call.Length < 7500, $"the request line was {call.Length} characters");
        Assert.Contains(Uri.EscapeDataString(ids[0]), call); // the message's own and its parent's always go
        Assert.Contains(Uri.EscapeDataString(ids[1]), call);
        Assert.Equal(true, (bool?)status["filed"]);
    }

    [Fact]
    public async Task AnIdNoServerKeepsIsNotAsked()
    {
        var status = await Mail().StatusAsync(["<>", new string('x', 999), "p1@example.com"]);

        Assert.Equal(["p1@example.com"], AskedIds()); // empty, or over 998 characters: Message-IDs the server counts as none
        Assert.Equal(false, (bool?)status["filed"]);

        // Nothing usable at all asks nothing.
        _server.SetRecentFolders(12);
        var none = await Mail().StatusAsync(["<>"]);
        Assert.Equal(1, _server.Calls.Count(c => c.StartsWith("GET /emails?", StringComparison.Ordinal)));
        Assert.Equal([12L], SuggestedIds(none));
    }

    [Fact]
    public async Task AnIdTooLongOnceEncodedIsLeftOutRatherThanBreakTheRequestLine()
    {
        // 998 characters, each three bytes in UTF-8 and so nine in the query: more than the request line can spare.
        var status = await Mail().StatusAsync([new string('–', 998), "p1@example.com"]);

        Assert.Equal(["p1@example.com"], AskedIds());
        Assert.Equal(false, (bool?)status["filed"]);
    }

    [Fact]
    public async Task AServerThatCantBeReachedOrRefusesIsAnExceptionNotAStatus()
    {
        _server.Down = true;
        await Assert.ThrowsAsync<HttpRequestException>(() => Mail().StatusAsync(["m1@example.com"]));

        _server.Down = false;
        _server.Unauthorized = true;
        var e = await Assert.ThrowsAsync<ApiException>(() => Mail().StatusAsync(["m1@example.com"]));
        Assert.Equal(401, e.Status);
    }

    [Fact]
    public async Task NoMessageIdOrSubjectIsLogged()
    {
        _server.AddEmailCopy("secret-id@example.com", 50, 11, Day);

        await Mail().StatusAsync(["secret-id@example.com", "secret-parent@example.com"]);
        await Assert.ThrowsAsync<HttpRequestException>(async () =>
        {
            _server.Down = true;
            await Mail().StatusAsync(["secret-id@example.com"]);
        });

        Assert.DoesNotContain("secret", Log);
        Assert.DoesNotContain("example.com", Log);
    }

    [Theory]
    [InlineData("<abc@example.com>", "abc@example.com")]
    [InlineData("  <abc@example.com>\r\n", "abc@example.com")]
    [InlineData("abc@example.com", "abc@example.com")]
    [InlineData("< abc@example.com >", "abc@example.com")]
    [InlineData("<abc@example.com", "<abc@example.com")] // brackets go as a pair, as the server's do
    [InlineData("<>", null)]
    [InlineData("   ", null)]
    [InlineData("", null)]
    [InlineData(null, null)]
    public void AMessageIdIsTheHeaderWithoutItsBracketsOrSpace(string? header, string? expected) =>
        Assert.Equal(expected, MessageIds.Normalise(header));

    [Fact]
    public void AMessageIdOfMoreThan998CharactersCountsAsNone()
    {
        Assert.Equal(new string('a', 998), MessageIds.Normalise(new string('a', 998)));
        Assert.Null(MessageIds.Normalise(new string('a', 999)));
        Assert.Null(MessageIds.Normalise("<" + new string('a', 999) + ">"));
        Assert.Equal(new string('a', 998), MessageIds.Normalise("<" + new string('a', 998) + ">"));
    }
}
