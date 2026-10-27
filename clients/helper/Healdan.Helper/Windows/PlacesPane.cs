using Avalonia.Controls;
using Avalonia.Layout;
using Healdan.Helper.Core.Browse;

namespace Healdan.Helper.Windows;

/// <summary>
/// A window's left pane (spec §5.8): the places to start from. Save's are Recent Folders, Recent Matters, My Matters,
/// Clients and Favorites; Open's are its documents' lists, then a Browse heading over the places to browse.
/// </summary>
internal sealed class PlacesPane
{
    private readonly ListBox _list = new() { Classes = { "places" }, SelectionMode = SelectionMode.Single };
    private bool _quiet;

    public PlacesPane(BrowseMode mode)
    {
        var places = mode == BrowseMode.Save ? Place.ForSave : Place.ForOpen;
        for (var i = 0; i < places.Count; i++)
        {
            if (mode == BrowseMode.Open && i == Place.OpenBrowseFrom)
                _list.Items.Add(new ListBoxItem
                {
                    Content = new TextBlock { Text = "Browse" },
                    IsEnabled = false,
                    Focusable = false,
                    Classes = { "group" }
                });
            _list.Items.Add(new ListBoxItem
            {
                Content = new StackPanel
                {
                    Orientation = Orientation.Horizontal,
                    Spacing = 7,
                    Children = { Icons.View(Icon(places[i].Kind), 15), new TextBlock { Text = places[i].Label, VerticalAlignment = VerticalAlignment.Center } }
                },
                Tag = places[i]
            });
        }
        _list.SelectionChanged += (_, _) =>
        {
            if (!_quiet && _list.SelectedItem is ListBoxItem { Tag: Place place })
                Chosen?.Invoke(place);
        };
    }

    /// <summary>The user chose a place.</summary>
    public event Action<Place>? Chosen;

    public Control View => _list;

    /// <summary>Marks the place shown, or none for a folder or a search, without choosing it again.</summary>
    public void Show(Place? place)
    {
        _quiet = true;
        try
        {
            _list.SelectedItem = place is null ? null : _list.Items.OfType<ListBoxItem>().FirstOrDefault(i => Equals(i.Tag, place));
        }
        finally
        {
            _quiet = false;
        }
    }

    /// <summary>Each place's icon (polish spec §4.4).</summary>
    private static string Icon(PlaceKind kind) => kind switch
    {
        PlaceKind.RecentDocuments => "clock",
        PlaceKind.CheckedOutToMe => "lock",
        PlaceKind.Favorites => "star",
        PlaceKind.RecentMatters => "history",
        PlaceKind.MyMatters => "briefcase",
        PlaceKind.Clients => "building",
        _ => "folder"
    };
}
