using Avalonia.Controls;
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
                    Content = new TextBlock { Text = "BROWSE", LetterSpacing = 0.6 },
                    IsEnabled = false,
                    Focusable = false,
                    Classes = { "sep" }
                });
            _list.Items.Add(new ListBoxItem { Content = places[i].Label, Tag = places[i] });
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
}
