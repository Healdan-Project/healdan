namespace Healdan.Helper.Core.Browse;

/// <summary>
/// A place in a window's left pane (spec §5.8) and the list in the API it shows. <see cref="Clients"/> lists nothing:
/// it's a search by client number or name (spec §2.3), so it sets the scope picker instead.
/// </summary>
public sealed record Place(PlaceKind Kind, string Label, string? Path)
{
    public static readonly Place RecentFolders = new(PlaceKind.RecentFolders, "Recent Folders", "v1/folders/recent");

    public static readonly Place RecentMatters = new(PlaceKind.RecentMatters, "Recent Matters", "v1/matters/recent");

    public static readonly Place MyMatters = new(PlaceKind.MyMatters, "My Matters", "v1/matters/mine");

    public static readonly Place Clients = new(PlaceKind.Clients, "Clients", null);

    /// <summary>CE's bookmarks (spec §9.3): folders only in Save, folders and documents in Open.</summary>
    public static readonly Place Favorites = new(PlaceKind.Favorites, "Favorites", "v1/me/favorites");

    public static readonly Place RecentDocuments = new(PlaceKind.RecentDocuments, "Recent Documents", "v1/documents/recent");

    public static readonly Place CheckedOutToMe = new(PlaceKind.CheckedOutToMe, "Checked Out to Me", "v1/me/checkouts");

    /// <summary>Save to Healdan's left pane.</summary>
    public static readonly IReadOnlyList<Place> ForSave = [RecentFolders, RecentMatters, MyMatters, Clients, Favorites];

    /// <summary>Open from Healdan's left pane: the documents first, then the places to browse from <see cref="OpenBrowseFrom"/>.</summary>
    public static readonly IReadOnlyList<Place> ForOpen = [RecentDocuments, CheckedOutToMe, Favorites, RecentMatters, MyMatters, RecentFolders];

    /// <summary>Where Open's "Browse" heading goes in <see cref="ForOpen"/>.</summary>
    public const int OpenBrowseFrom = 3;
}
