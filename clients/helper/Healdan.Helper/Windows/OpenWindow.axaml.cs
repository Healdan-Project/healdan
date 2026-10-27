using System.Text.Json.Nodes;
using Avalonia.Controls;
using Avalonia.Controls.Templates;
using Avalonia.Media.Imaging;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Browse;
using Healdan.Helper.Core.Settings;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Windows;

/// <summary>
/// Open from Healdan (spec §5.8): Recent Documents, Checked Out to Me and Favorites, then places to browse; who holds
/// each document and when it was last saved; the thumbnail, main properties and versions of the one selected. Typing
/// <c>#1234</c> and Enter jumps to it. An older version opens read-only. The window only chooses: the open itself runs
/// after it closes (<see cref="Chosen"/>), through the cycle, which checks the document out or opens it read-only.
/// </summary>
public partial class OpenWindow : Window
{
    private readonly HealdanApi? _api;
    private readonly Browser? _browser;
    private readonly BrowseControls? _controls;
    private DocumentDetails? _details;
    private int _detailLoad;
    private long? _loadingFor;
    private bool _readOnlyChosen;
    private bool _quiet;

    /// <summary>For the XAML loader; the helper always opens it with an API client.</summary>
    public OpenWindow() => InitializeComponent();

    /// <param name="fromApp">Asked for by the app's Open, so "Open from Local Computer" has a box to go back to.</param>
    internal OpenWindow(HealdanApi api, bool fromApp) : this()
    {
        _api = api;
        BrowserChrome.Apply(this, Toolbar, WindowTitle, [BackButton, ForwardButton, UpButton, ScopePicker, SearchBox]);
        _browser = new Browser(api, BrowseMode.Open);
        var places = new PlacesPane(BrowseMode.Open);
        PlacesHost.Child = places.View;
        _controls = new BrowseControls(_browser, places,
            new BrowseControls.Parts(BackButton, ForwardButton, UpButton, ScopePicker, SearchBox, CrumbPanel, StarButton, StarIcon, EntryList, NoticeText));
        _controls.Selected += entry => _ = ShowDetailsAsync(entry);
        _controls.DocumentActivated += _ => OpenChosen();
        _browser.Changed += ShowStar;

        VersionList.ItemTemplate = new FuncDataTemplate<VersionRow>((row, _) => new StackPanel
        {
            Children =
            {
                new TextBlock { Text = row?.Title ?? "", FontSize = 12, FontWeight = Avalonia.Media.FontWeight.SemiBold },
                new TextBlock { Text = row?.Line ?? "", FontSize = 11, Classes = { "muted" }, TextWrapping = Avalonia.Media.TextWrapping.Wrap }
            }
        });
        VersionList.SelectionChanged += (_, _) => VersionChanged();
        ReadOnlyBox.IsCheckedChanged += (_, _) =>
        {
            if (!_quiet)
                _readOnlyChosen = ReadOnlyBox.IsChecked == true;
        };
        DocStarButton.Click += (_, _) =>
        {
            if (_details?.Document is { } doc)
                _ = _browser.ToggleFavoriteAsync(doc);
        };

        LocalButton.IsVisible = fromApp;
        OpenButton.Click += (_, _) => OpenChosen();
        LocalButton.Click += (_, _) => Finish(WindowAnswers.Local());
        CancelButton.Click += (_, _) => Close();
        Opened += async (_, _) =>
        {
            SearchBox.Focus();
            await _browser.StartAsync(Place.RecentDocuments, null);
        };
        Closed += (_, _) => _browser.Dispose();
        OpenButton.IsEnabled = false;
    }

    /// <summary>What the window answers <c>/v1/open-dialog</c>; Cancel until the user chooses otherwise.</summary>
    internal JsonObject Answer { get; private set; } = WindowAnswers.Cancel();

    /// <summary>The document to open once the window has closed, read-only or not, and the older version if one was picked.</summary>
    internal (long DocNumber, bool ReadOnly, string? Version)? Chosen { get; private set; }

    private sealed record VersionRow(VersionView Save, string Title, string Line);

    private async Task ShowDetailsAsync(Entry? entry)
    {
        if (entry?.Document is not { } doc)
        {
            if (_details is not null || entry is not null)
                ClearDetails(entry is null ? "Choose a document to see its properties and versions." : "Double-click to open it.");
            return;
        }
        if (_details?.Document.DocNumber == doc.DocNumber || _loadingFor == doc.DocNumber)
            return;
        _loadingFor = doc.DocNumber;
        var load = ++_detailLoad;
        _details = null;
        OpenButton.IsEnabled = true;
        DocTitle.Text = doc.Name;
        (DetailsNote.Text, DetailsNote.IsVisible) = ("Loading…", true);
        DetailRows.ItemsSource = null;
        try
        {
            var details = await DocumentDetails.LoadAsync(_api!, doc.DocNumber, _browser!.Me, DateTimeOffset.Now);
            if (load != _detailLoad)
                return;
            _loadingFor = null;
            ShowDetails(details, ReferenceEquals(entry, _browser.Jumped) ? _browser.JumpedVersion : null);
        }
        catch (Exception e)
        {
            // Everything: an unexpected answer leaves the pane saying so, rather than loading for ever.
            if (e is not (ApiException or HttpRequestException or OperationCanceledException))
                Program.Log.Error("Open from Healdan couldn't show a document's details", e);
            if (load != _detailLoad)
                return;
            _loadingFor = null;
            (DetailsNote.Text, DetailsNote.IsVisible) = (e is ApiException { Detail.Length: > 0 } api ? api.Detail : "Its details couldn't be read.", true);
        }
    }

    private void ShowDetails(DocumentDetails details, string? version)
    {
        _details = details;
        var doc = details.Document;
        DocTitle.Text = doc.Name;
        DetailsNote.IsVisible = FileKinds.Of(doc.Name) is null;
        DetailsNote.Text = "Only office documents can be edited from here: this one opens read-only.";
        DetailRows.ItemsSource = details.Rows;
        ShowThumbnail(details.Thumbnail);
        ShowStar();

        var now = DateTimeOffset.Now;
        var rows = details.Saves.Select(s => new VersionRow(s, $"v{s.Version}" + (s.Version == doc.FileVersion ? " (current)" : ""),
            string.Join(" · ", new[] { s.Date is { } d ? When.InSentence(d, now) : null, s.By?.Name, s.Comment }.Where(t => !string.IsNullOrWhiteSpace(t))))).ToList();
        VersionList.ItemsSource = rows;
        VersionList.IsVisible = VersionsHeading.IsVisible = rows.Count > 0;
        VersionList.SelectedItem = rows.FirstOrDefault(r => r.Save.Version == (version ?? doc.FileVersion)) ?? rows.FirstOrDefault();
        VersionChanged();
    }

    private void ClearDetails(string note)
    {
        ++_detailLoad;
        _loadingFor = null;
        _details = null;
        DocTitle.Text = "";
        (DetailsNote.Text, DetailsNote.IsVisible) = (note, true);
        DetailRows.ItemsSource = null;
        VersionList.ItemsSource = null;
        VersionList.IsVisible = VersionsHeading.IsVisible = VersionNote.IsVisible = false;
        ShowThumbnail(null);
        DocStarButton.IsVisible = false;
        OpenButton.IsEnabled = false;
        SetReadOnly(forced: false);
    }

    private void ShowThumbnail(byte[]? png)
    {
        Bitmap? image = null;
        if (png is not null)
        {
            try
            {
                image = new Bitmap(new MemoryStream(png));
            }
            catch (Exception e) when (e is ArgumentException or InvalidOperationException or NotSupportedException or IOException)
            {
                image = null; // not an image the helper can show: the placeholder instead
            }
        }
        (Thumbnail.Source as IDisposable)?.Dispose();
        Thumbnail.Source = image;
        ThumbnailNote.IsVisible = image is null;
    }

    private void ShowStar()
    {
        if (_details?.Document is not { } doc || _browser is null)
            return;
        DocStarButton.IsVisible = true;
        var favorite = _browser.IsFavorite(doc);
        DocStarIcon.Classes.Set("on", favorite);
        ToolTip.SetTip(DocStarButton, favorite ? "Remove from Favorites" : "Add to Favorites");
    }

    /// <summary>An older version opens read-only, as does a file no office app edits.</summary>
    private void VersionChanged()
    {
        var older = SelectedVersion() is not null;
        VersionNote.IsVisible = older;
        SetReadOnly(forced: older || (_details is { } d && FileKinds.Of(d.Document.Name) is null));
    }

    private void SetReadOnly(bool forced)
    {
        _quiet = true;
        try
        {
            ReadOnlyBox.IsEnabled = !forced;
            ReadOnlyBox.IsChecked = forced || _readOnlyChosen;
        }
        finally
        {
            _quiet = false;
        }
    }

    /// <summary>The version picked when it isn't the current one; null for the current.</summary>
    private string? SelectedVersion() =>
        VersionList.SelectedItem is VersionRow row && _details is { } d && row.Save.Version != d.Document.FileVersion ? row.Save.Version : null;

    private void OpenChosen()
    {
        if (_controls?.SelectedEntry?.Document is not { } doc)
            return;
        var version = _details?.Document.DocNumber == doc.DocNumber ? SelectedVersion() : null;
        var readOnly = ReadOnlyBox.IsChecked == true || version is not null || FileKinds.Of(doc.Name) is null;
        Chosen = (doc.DocNumber, readOnly, version);
        Finish(WindowAnswers.Opened());
    }

    private void Finish(JsonObject answer)
    {
        Answer = answer;
        Close();
    }
}
