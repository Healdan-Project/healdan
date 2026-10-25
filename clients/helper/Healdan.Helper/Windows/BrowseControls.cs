using Avalonia.Controls;
using Path = Avalonia.Controls.Shapes.Path;
using Avalonia.Input;
using Avalonia.Layout;
using Healdan.Helper.Core.Browse;

namespace Healdan.Helper.Windows;

/// <summary>
/// The Save and Open windows' toolbar and middle pane, wired to a <see cref="Browser"/> (spec §5.8): back, forward and
/// up; the scope picker and the search box, where Enter searches and <c>#1234</c> jumps to the document; the
/// breadcrumb, the star and New Folder; the list, where double-click or Enter opens a folder or matter, or hands a
/// document to the window. The browsing itself is <see cref="Browser"/>'s, which the core's tests cover.
/// </summary>
internal sealed class BrowseControls
{
    private readonly Browser _browser;
    private readonly PlacesPane _places;
    private readonly Parts _parts;
    private IReadOnlyList<Entry>? _shown;
    private bool _quiet;

    /// <summary>The controls, as each window's XAML names them. New Folder's are Save's only.</summary>
    public sealed record Parts(
        Button Back,
        Button Forward,
        Button Up,
        ComboBox Scope,
        TextBox Search,
        StackPanel Crumbs,
        Button Star,
        Path StarIcon,
        ListBox List,
        TextBlock Notice,
        Button? NewFolder = null,
        Control? NewFolderBar = null,
        TextBox? NewFolderName = null,
        Button? NewFolderCreate = null,
        Button? NewFolderCancel = null);

    public BrowseControls(Browser browser, PlacesPane places, Parts parts)
    {
        (_browser, _places, _parts) = (browser, places, parts);
        parts.Back.Click += (_, _) => _ = browser.BackAsync();
        parts.Forward.Click += (_, _) => _ = browser.ForwardAsync();
        parts.Up.Click += (_, _) => _ = browser.UpAsync();
        ToolTip.SetTip(parts.Back, "Back");
        ToolTip.SetTip(parts.Forward, "Forward");
        ToolTip.SetTip(parts.Up, "Up");

        parts.Scope.ItemsSource = SearchScopes.All;
        parts.Scope.ItemTemplate = new Avalonia.Controls.Templates.FuncDataTemplate<SearchScope>((s, _) => new TextBlock { Text = s.Label() });
        parts.Scope.SelectionChanged += (_, _) =>
        {
            if (!_quiet && parts.Scope.SelectedItem is SearchScope scope)
                browser.Scope = scope;
        };
        parts.Search.KeyDown += (_, e) =>
        {
            if (e.Key != Key.Enter)
                return;
            e.Handled = true;
            _ = browser.SearchAsync(parts.Search.Text ?? "");
        };

        parts.Star.Click += (_, _) =>
        {
            if (browser.Folder is { } folder)
                _ = browser.ToggleFavoriteAsync(folder);
        };

        parts.List.SelectionChanged += (_, _) =>
        {
            if (!_quiet)
                Selected?.Invoke(parts.List.SelectedItem as Entry);
        };
        parts.List.DoubleTapped += (_, _) => Activate();
        parts.List.KeyDown += (_, e) =>
        {
            if (e.Key == Key.Enter)
            {
                e.Handled = true;
                Activate();
            }
        };

        places.Chosen += place => _ = browser.ShowAsync(place);
        if (parts.NewFolder is { } newFolder)
            WireNewFolder(newFolder);
        browser.Changed += Refresh;
        Refresh();
    }

    /// <summary>The row selected changed; null when none is.</summary>
    public event Action<Entry?>? Selected;

    /// <summary>A document was double-clicked, or Enter pressed on it.</summary>
    public event Action<Entry>? DocumentActivated;

    public Entry? SelectedEntry => _parts.List.SelectedItem as Entry;

    /// <summary>Puts the cursor in the search box, as after choosing Clients.</summary>
    public void FocusSearch() => _parts.Search.Focus();

    private void Activate()
    {
        if (SelectedEntry is not { } entry)
            return;
        if (entry.IsDocument)
            DocumentActivated?.Invoke(entry);
        else
            _ = _browser.EnterAsync(entry);
    }

    private void WireNewFolder(Button newFolder)
    {
        var (bar, name, create, cancel) = (_parts.NewFolderBar!, _parts.NewFolderName!, _parts.NewFolderCreate!, _parts.NewFolderCancel!);
        newFolder.Click += (_, _) =>
        {
            (bar.IsVisible, name.Text) = (true, "");
            name.Focus();
        };
        cancel.Click += (_, _) => bar.IsVisible = false;
        async Task CreateAsync()
        {
            if (string.IsNullOrWhiteSpace(name.Text))
                return;
            create.IsEnabled = false;
            var made = await _browser.NewFolderAsync(name.Text);
            create.IsEnabled = true;
            if (made is not null)
                bar.IsVisible = false;
        }
        create.Click += (_, _) => _ = CreateAsync();
        name.KeyDown += (_, e) =>
        {
            if (e.Key == Key.Enter)
            {
                e.Handled = true;
                _ = CreateAsync();
            }
            else if (e.Key == Key.Escape)
            {
                e.Handled = true;
                bar.IsVisible = false;
            }
        };
    }

    /// <summary>Shows the browser's state: run on each change, on the UI thread. It never throws into the browser.</summary>
    private void Refresh()
    {
        try
        {
            Show();
        }
        catch (Exception e)
        {
            Program.Log.Error("A window couldn't show what it browsed", e);
        }
    }

    private void Show()
    {
        var p = _parts;
        _quiet = true;
        try
        {
            p.Back.IsEnabled = _browser.CanBack;
            p.Forward.IsEnabled = _browser.CanForward;
            p.Up.IsEnabled = _browser.CanUp;
            p.Scope.SelectedItem = _browser.Scope;
            p.Search.PlaceholderText = _browser.SearchPlaceholder;

            p.Crumbs.Children.Clear();
            if (_browser.Folder is null)
                p.Crumbs.Children.Add(new TextBlock { Text = _browser.Heading, Classes = { "heading" }, VerticalAlignment = VerticalAlignment.Center });
            else
                for (var i = 0; i < _browser.Crumbs.Count; i++)
                    p.Crumbs.Children.Add(CrumbControl(_browser.Crumbs[i], last: i == _browser.Crumbs.Count - 1));

            p.Star.IsVisible = _browser.Folder is not null;
            var favorite = _browser.Folder is { } folder && _browser.IsFavorite(folder);
            p.StarIcon.Classes.Set("on", favorite);
            ToolTip.SetTip(p.Star, favorite ? "Remove from Favorites" : "Add to Favorites");
            if (p.NewFolder is { } newFolder)
            {
                newFolder.IsEnabled = _browser.Folder is { Kind: not "searchFolder" };
                if (_browser.Folder is null && p.NewFolderBar is { } bar)
                    bar.IsVisible = false;
            }

            if (!ReferenceEquals(_shown, _browser.Entries))
            {
                _shown = _browser.Entries;
                p.List.ItemsSource = _browser.Entries;
                p.List.SelectedItem = null;
            }
            if (_browser.Jumped is { } jumped && !ReferenceEquals(p.List.SelectedItem, jumped))
            {
                p.List.SelectedItem = jumped;
                p.List.ScrollIntoView(jumped);
            }

            var notice = _browser.Busy && _browser.Entries.Count == 0 ? "Loading…" : _browser.Notice;
            (p.Notice.Text, p.Notice.IsVisible) = (notice, notice is not null);
            _places.Show(_browser.Place);
        }
        finally
        {
            _quiet = false;
        }
        Selected?.Invoke(SelectedEntry);
    }

    private Control CrumbControl(Crumb crumb, bool last)
    {
        if (last)
            return new TextBlock { Text = crumb.Text, Classes = { "heading" }, VerticalAlignment = VerticalAlignment.Center };
        var step = new StackPanel { Orientation = Orientation.Horizontal };
        var button = new Button { Content = crumb.Text, Classes = { "link" } };
        button.Click += (_, _) => _ = _browser.OpenFolderAsync(crumb.FolderId);
        step.Children.Add(button);
        step.Children.Add(new TextBlock { Text = "  ›  ", Classes = { "muted" }, VerticalAlignment = VerticalAlignment.Center });
        return step;
    }
}
