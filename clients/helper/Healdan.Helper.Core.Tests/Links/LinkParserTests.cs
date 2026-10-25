using Healdan.Helper.Core.Links;

namespace Healdan.Helper.Core.Tests.Links;

public sealed class LinkParserTests
{
    [Fact]
    public void ParsesOpen()
    {
        Assert.True(HealdanLink.TryParse("healdan://open/1234?server=healdan.example", out var n, out var readOnly, out var server));
        Assert.Equal(1234, n);
        Assert.False(readOnly);
        Assert.Equal("healdan.example", server);
    }

    [Fact]
    public void ParsesReadOnly()
    {
        Assert.True(HealdanLink.TryParse("healdan://open/1234?server=localhost:8096&readonly=1", out var n, out var readOnly, out var server));
        Assert.Equal(1234, n);
        Assert.True(readOnly);
        Assert.Equal("localhost:8096", server);

        // The parameters in either order, the colon escaped as a page may write it.
        Assert.True(HealdanLink.TryParse("healdan://open/7?readonly=1&server=localhost%3A8096", out n, out readOnly, out server));
        Assert.Equal((7L, true, "localhost:8096"), (n, readOnly, server));

        Assert.True(HealdanLink.TryParse("healdan://open/7?server=localhost&readonly=0", out _, out readOnly, out _));
        Assert.False(readOnly);
    }

    [Theory]
    [InlineData("healdan://save/1")]
    [InlineData("healdan://save/1?server=healdan.example")]
    [InlineData("healdan://checkin/1?server=healdan.example")]
    [InlineData("healdan://release/1?server=healdan.example")]
    [InlineData("healdan://open/1/save?server=healdan.example")]
    [InlineData("healdan:open/1?server=healdan.example")]
    [InlineData("https://open/1?server=healdan.example")]
    [InlineData("ldedit://open/1?server=healdan.example")]
    [InlineData("healdan://user:secret@open/1?server=healdan.example")]
    [InlineData("healdan://open:8080/1?server=healdan.example")]
    [InlineData("healdan://open/1?server=a.example&server=b.example")]
    [InlineData("")]
    [InlineData("not a link")]
    public void RejectsOtherVerbs(string url)
    {
        Assert.False(HealdanLink.TryParse(url, out _, out _, out _));
    }

    [Theory]
    [InlineData("healdan://open/abc?server=healdan.example")]
    [InlineData("healdan://open/-5?server=healdan.example")]
    [InlineData("healdan://open/+5?server=healdan.example")]
    [InlineData("healdan://open/1.5?server=healdan.example")]
    [InlineData("healdan://open/1e3?server=healdan.example")]
    [InlineData("healdan://open/0?server=healdan.example")]
    [InlineData("healdan://open/?server=healdan.example")]
    [InlineData("healdan://open?server=healdan.example")]
    [InlineData("healdan://open/99999999999999999999?server=healdan.example")]
    [InlineData("healdan://open/%D9%A3?server=healdan.example")]
    public void RejectsNonNumbers(string url)
    {
        Assert.False(HealdanLink.TryParse(url, out _, out _, out _));
    }

    [Fact]
    public void ParsesALinkWithoutServer()
    {
        // The link handler refuses it; the parser only says what it names.
        Assert.True(HealdanLink.TryParse("healdan://open/5", out var n, out var readOnly, out var server));
        Assert.Equal((5L, false, (string?)null), (n, readOnly, server));
    }

    [Theory]
    [InlineData("localhost:8096", "http://localhost:8096")]
    [InlineData("healdan.example", "https://healdan.example")]
    [InlineData("[::1]:8096", "http://[::1]:8096")]
    public void ServerMatchesHostAndPort(string server, string signedIn)
    {
        Assert.True(HealdanLink.IsServer(server, new Uri(signedIn)));
    }

    [Theory]
    [InlineData("Healdan.Example", "https://healdan.example")]
    [InlineData("healdan.example", "https://HEALDAN.EXAMPLE")]
    [InlineData("LOCALHOST:8096", "http://localhost:8096")]
    public void ServerHostIsCaseInsensitive(string server, string signedIn)
    {
        Assert.True(HealdanLink.IsServer(server, new Uri(signedIn)));
    }

    [Theory]
    [InlineData("healdan.example", "https://healdan.example:443", true)]
    [InlineData("healdan.example:443", "https://healdan.example", true)]
    [InlineData("localhost:80", "http://localhost", true)]
    [InlineData("localhost", "http://localhost", true)]
    [InlineData("localhost", "http://localhost:8096", false)]
    [InlineData("healdan.example:443", "https://healdan.example:8443", false)]
    public void ServerTakesTheDefaultPortWhenOmitted(string server, string signedIn, bool same)
    {
        Assert.Equal(same, HealdanLink.IsServer(server, new Uri(signedIn)));
    }

    [Theory]
    [InlineData("evil.example")]
    [InlineData("healdan.example:8443")]
    [InlineData("healdan.example.evil.example")]
    [InlineData("evil.example:443")]
    [InlineData("127.0.0.1")]
    public void ServerRefusesAnotherHostOrPort(string server)
    {
        Assert.False(HealdanLink.IsServer(server, new Uri("https://healdan.example")));
    }

    [Theory]
    [InlineData("healdan.example/api")]
    [InlineData("user@healdan.example")]
    [InlineData("evil.example@healdan.example")]
    [InlineData("healdan.example:443@evil.example")]
    [InlineData("healdan.example?x=1")]
    [InlineData("healdan.example#x")]
    [InlineData("healdan.example:")]
    [InlineData("healdan.example:99999")]
    [InlineData("healdan.example:+443")]
    [InlineData(" healdan.example")]
    [InlineData("https://healdan.example")]
    [InlineData("")]
    [InlineData(null)]
    public void ServerRefusesMoreThanHostAndPort(string? server)
    {
        Assert.False(HealdanLink.IsServer(server, new Uri("https://healdan.example")));
    }
}
