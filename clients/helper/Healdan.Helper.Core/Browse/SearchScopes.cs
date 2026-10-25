namespace Healdan.Helper.Core.Browse;

/// <summary>The scope picker's labels and the API's names for them (<c>GET /v1/search</c>'s <c>scope</c>).</summary>
public static class SearchScopes
{
    public static readonly IReadOnlyList<SearchScope> All = [SearchScope.Folders, SearchScope.Documents, SearchScope.Matters, SearchScope.Clients];

    public static string ApiName(this SearchScope scope) => scope.ToString().ToLowerInvariant();

    public static string Label(this SearchScope scope) => scope.ToString();

    /// <summary>Only folders and documents can be searched within a place (spec §9.1).</summary>
    public static bool TakesWithin(this SearchScope scope) => scope is SearchScope.Folders or SearchScope.Documents;
}
