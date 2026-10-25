using System.Text.Json.Nodes;
using Avalonia.Controls;
using Healdan.Helper.Core;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Browse;
using Healdan.Helper.Core.Forms;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Windows;

/// <summary>
/// Save to Healdan, and Save as New Document (spec §5.5, §5.6, §5.8). The user picks a folder (one selected in the
/// list, or the folder shown), fills in the properties and picks the format, the app's own first. Save turns on only
/// once the folder can take documents and every required field has a value. The answer goes back on
/// <c>/v1/save-new</c>: the extension then writes the document to the answer's temporary file and files it.
/// </summary>
public partial class SaveWindow : Window
{
    private readonly SaveNewRequest? _request;
    private readonly HealdanApi? _api;
    private readonly HelperPaths? _paths;
    private readonly Browser? _browser;
    private readonly BrowseControls? _controls;
    private FormModel? _form;
    private FolderView? _formFolder;
    private FormModel? _original;
    private FolderView? _target;
    private string? _format;
    private int _formLoad;

    /// <summary>For the XAML loader; the helper always opens it with a request.</summary>
    public SaveWindow() => InitializeComponent();

    internal SaveWindow(SaveNewRequest request, HealdanApi api, HelperPaths paths) : this()
    {
        (_request, _api, _paths) = (request, api, paths);
        if (request.DocNumber is not null)
            Title = "Save as New Document";
        _browser = new Browser(api, BrowseMode.Save);
        var places = new PlacesPane(BrowseMode.Save);
        PlacesHost.Child = places.View;
        _controls = new BrowseControls(_browser, places, new BrowseControls.Parts(BackButton, ForwardButton, UpButton, ScopePicker, SearchBox,
            CrumbPanel, StarButton, StarIcon, EntryList, NoticeText, NewFolderButton, NewFolderBar, NewFolderName, NewFolderCreate, NewFolderCancel));
        _controls.Selected += _ => TargetChanged();
        places.Chosen += place =>
        {
            if (place.Kind == PlaceKind.Clients)
                _controls.FocusSearch();
        };

        NameBox.Text = request.Name;
        NameBox.TextChanged += (_, _) => UpdateSave();
        AddFormats(request);

        SaveButton.Click += (_, _) => Save();
        LocalButton.Click += (_, _) => Finish(WindowAnswers.Local());
        CancelButton.Click += (_, _) => Close();
        Opened += async (_, _) =>
        {
            NameBox.Focus();
            NameBox.SelectAll();
            await StartAsync();
        };
        Closed += (_, _) => _browser.Dispose();
        Notice("Choose a folder to file the document in.");
    }

    /// <summary>What the window answers <c>/v1/save-new</c>; Cancel until the user chooses otherwise.</summary>
    internal JsonObject Answer { get; private set; } = WindowAnswers.Cancel();

    /// <summary>Opens on Recent Folders, or for Save as New Document on the original's folder, with its properties.</summary>
    private async Task StartAsync()
    {
        long? folderId = null;
        if (_request!.DocNumber is { } n)
        {
            try
            {
                var doc = _api!.DocumentAsync(n);
                var properties = _api.PropertiesAsync(n);
                var original = await doc;
                folderId = original.Folder.Id;
                // Named after the original as Healdan has it, not the window's title, unless the user has typed meanwhile.
                if (NameBox.Text == _request.Name)
                {
                    NameBox.Text = _request.StartingName(original.Name);
                    NameBox.SelectAll();
                }
                _original = new FormModel(await properties);
            }
            catch (Exception e)
            {
                // It starts on Recent Folders with an empty form instead.
                Program.Log.Warn("Save as New Document couldn't read the original: " + e.Message);
            }
        }
        await _browser!.StartAsync(Place.RecentFolders, folderId);
    }

    private void AddFormats(SaveNewRequest request)
    {
        _format = request.DefaultFormat();
        if (request.Formats.Count == 0)
        {
            FormatPanel.Children.Add(new TextBlock { Text = "LibreOffice didn't say which formats it can save in.", Classes = { "problem" } });
            return;
        }
        foreach (var format in request.Formats)
        {
            var radio = new RadioButton { Content = FileFormats.Label(format), GroupName = "format", IsChecked = format == _format };
            radio.IsCheckedChanged += (_, _) =>
            {
                if (radio.IsChecked == true)
                {
                    _format = format;
                    UpdateSave();
                }
            };
            FormatPanel.Children.Add(radio);
        }
    }

    /// <summary>
    /// The folder a new document would go in changed: its form is read, with what the user had changed carried over
    /// (or, the first time, the original's values for Save as New Document).
    /// </summary>
    private async void TargetChanged()
    {
        var target = _browser!.TargetFolder(_controls!.SelectedEntry);
        if (target?.Id == _target?.Id)
            return;
        _target = target;
        var load = ++_formLoad;
        UpdateSave();
        if (target is null)
        {
            Notice(_browser.Folder is null
                ? "Choose a folder inside a matter to file the document in."
                : "Choose a folder inside the matter: its top level takes no documents.");
            return;
        }
        if (!target.CanFile)
        {
            Notice($"Documents can't be filed in {target.Name}. Choose a folder inside a matter that you can add to.");
            return;
        }
        if (target.Id == _formFolder?.Id && _form is not null)
        {
            Show(_form, target);
            return;
        }
        Notice("Loading the properties…");
        try
        {
            var form = new FormModel(await _api!.FormAsync(target.Id));
            if (load != _formLoad)
                return;
            if (_form is not null)
                form.Carry(_form);
            else if (_original is not null)
                form.Prefill(_original);
            Show(form, target);
        }
        catch (Exception e)
        {
            // Everything, as this runs from a UI event: an exception would end the helper.
            if (load == _formLoad)
                Notice(e is ApiException { Detail.Length: > 0 } api ? api.Detail : "The properties couldn't be read from Healdan. Try again in a moment.");
            if (e is not (ApiException or HttpRequestException or OperationCanceledException))
                Program.Log.Error("Save to Healdan couldn't show the folder's form", e);
        }
    }

    private void Show(FormModel form, FolderView folder)
    {
        if (!ReferenceEquals(form, _form))
        {
            form.Changed += UpdateSave;
            var matter = folder.Matter is { } m ? $"{m.Display} ({folder.Store.Name})" : null;
            FormHost.Content = new PropertiesForm(form, _api!, matter: matter).View;
        }
        (_form, _formFolder) = (form, folder);
        FormHost.IsVisible = true;
        FormNotice.IsVisible = false;
        UpdateSave();
    }

    private void Notice(string text)
    {
        FormHost.IsVisible = false;
        (FormNotice.Text, FormNotice.IsVisible) = (text, true);
        UpdateSave();
    }

    private bool CanSave()
    {
        var nameProblem = DocumentNames.Problem(_request!.FinalName(NameBox.Text), _format);
        (NameProblem.Text, NameProblem.IsVisible) = (nameProblem, nameProblem is not null);
        return _target is { CanFile: true } target && _form is not null && _formFolder?.Id == target.Id && FormHost.IsVisible
            && _form.CanSave(target.CanFile) && nameProblem is null && _format is not null;
    }

    private void UpdateSave() => SaveButton.IsEnabled = CanSave();

    private void Save()
    {
        if (!CanSave())
            return;
        Finish(WindowAnswers.Save(_target!.Id, _request!.FinalName(NameBox.Text), _format!, _form!.Values(), WindowAnswers.TempPath(_paths!, _format!)));
    }

    private void Finish(JsonObject answer)
    {
        Answer = answer;
        Close();
    }
}
