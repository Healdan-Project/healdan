namespace Healdan.Helper.Core.Tests;

public class ServerAddressTests
{
    [Theory]
    [InlineData("https://healdan.example/", "https://healdan.example")]
    [InlineData("http://localhost:8080", "http://localhost:8080")]
    [InlineData("https://healdan.example/dms/", "https://healdan.example/dms")]
    public void AnAddressIsTheSchemeHostPortAndPath(string typed, string address) =>
        Assert.Equal(address, ServerAddress.Of(new Uri(typed)));

    [Fact]
    public void AddressesMatchWithoutRegardToCase()
    {
        Assert.True(ServerAddress.Same("https://Healdan.Example", "https://healdan.example"));
        Assert.False(ServerAddress.Same("http://healdan.example", "https://healdan.example"));
        Assert.False(ServerAddress.Same(null, "https://healdan.example"));
        Assert.True(ServerAddress.Same(new Uri("https://healdan.example/"), new Uri("https://HEALDAN.example")));
    }

    [Theory]
    [InlineData("https://healdan.example", "healdan.example")]
    [InlineData("http://localhost:8080", "localhost:8080")]
    [InlineData("https://healdan.example/dms", "healdan.example")]
    [InlineData("not an address", "not an address")]
    public void ANameIsTheHostAndAnyPort(string address, string name) => Assert.Equal(name, ServerAddress.Name(address));

    [Theory]
    [InlineData("https://healdan.example", "https://healdan.example")]
    [InlineData("https://healdan.example/dms/", "https://healdan.example")]
    [InlineData("https://Healdan.Example:443/", "https://healdan.example")]
    [InlineData("https://healdan.example:8443/dms", "https://healdan.example:8443")]
    [InlineData("http://localhost:8080", "http://localhost:8080")]
    public void AnOriginIsTheSchemeHostAndPortAsABrowserSendsIt(string address, string origin) =>
        Assert.Equal(origin, ServerAddress.Origin(new Uri(address)));
}
