using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Tests.Ui;

public sealed class WarningsTests
{
    [Fact]
    public void SignOutHereNamesTheServerAndAnySavesLeftBehind()
    {
        var none = Warnings.SignOutHere("healdan.example", 0);
        Assert.Equal("Healdan at healdan.example can't be reached", none.Title);
        Assert.Contains("forget the sign-in on this computer", none.Detail);
        Assert.Contains("until the sign-in expires or you change your password", none.Detail);
        Assert.Null(none.Unsent);

        Assert.Equal("1 save hasn't reached Healdan. Its copy stays on this computer, and goes up the next time you sign in to healdan.example.",
            Warnings.SignOutHere("healdan.example", 1).Unsent);
        Assert.Equal("3 saves haven't reached Healdan. Their copies stay on this computer, and go up the next time you sign in to localhost:8080.",
            Warnings.SignOutHere("localhost:8080", 3).Unsent);
    }
}
