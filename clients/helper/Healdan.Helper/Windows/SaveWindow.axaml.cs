// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Text.Json.Nodes;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Media;
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
/// <para>Its email mode, File to Healdan (M5 spec §7.3), files a message from Outlook: the folder and its properties as
/// for a document, with the message's subject, sender and when it was sent shown read-only in place of the name (M1 names
/// an email after its subject), and "Also file attachments as separate documents", unticked, with a tick for each
/// attachment, in place of the format. The box ticks and unticks them all; each can be unticked while it's ticked.
/// Its Save files through the window, with Save, Cancel and closing off meanwhile: once filed (or the matter holds the
/// message already) it closes; a refusal shows in it, a property's under the field and an attachment's under its tick,
/// and it stays open to choose another folder or untick.</para>
/// </summary>
public partial class SaveWindow : Window
{
    private readonly SaveNewRequest? _request;
    private readonly SaveEmailRequest? _email;
    private readonly Func<JsonObject, Task<SubmitOutcome>>? _submit;
    private readonly List<(EmailPart Part, CheckBox Box, TextBlock Problem)> _attachments = [];
    private SubmitOutcome.Refused? _pendingRefusal;
    private bool _submitting;
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
        (_browser, _controls) = Browse(api);

        NameBox.Text = request.Name;
        NameBox.TextChanged += (_, _) => UpdateSave();
        AddFormats(request);

        LocalButton.Click += (_, _) => Finish(WindowAnswers.Local());
        Opened += async (_, _) =>
        {
            NameBox.Focus();
            NameBox.SelectAll();
            await StartAsync();
        };
        Notice("Choose a folder to file the document in.");
    }

    /// <summary>
    /// The email mode, for <c>mail/file</c>: opens at the request's folder, else at Recent Folders, with the request's
    /// refusal shown when it has one. Save hands its answer to <paramref name="submit"/>, which files it.
    /// </summary>
    internal SaveWindow(SaveEmailRequest request, HealdanApi api, Func<JsonObject, Task<SubmitOutcome>> submit) : this()
    {
        (_email, _api, _submit) = (request, api, submit);
        Title = "File to Healdan";
        (_browser, _controls) = Browse(api);

        PropertiesHeading.Text = "Email Properties";
        NameRow.IsVisible = FormatRow.IsVisible = LocalButton.IsVisible = false;
        ShowMessage(request);
        AddAttachments(request);
        Closing += (_, e) => e.Cancel |= _submitting; // the filing finishes first, so its outcome is the window's

        Opened += async (_, _) => await StartAsync();
        Notice("Choose a folder to file the email in.");
        if (request.Refusal is { } refusal)
        {
            // Its words show at once; its fields' wait for the folder's form, which places them.
            _pendingRefusal = refusal;
            ShowRefusal(refusal);
        }
    }

    /// <summary>The folder picker, with Save and Cancel, as both modes have them.</summary>
    private (Browser, BrowseControls) Browse(HealdanApi api)
    {
        BrowserChrome.Apply(this, Toolbar, WindowTitle, [BackButton, ForwardButton, UpButton, ScopePicker, SearchBox]);
        var browser = new Browser(api, BrowseMode.Save);
        var places = new PlacesPane(BrowseMode.Save);
        PlacesHost.Child = places.View;
        var controls = new BrowseControls(browser, places, new BrowseControls.Parts(BackButton, ForwardButton, UpButton, ScopePicker, SearchBox,
            CrumbPanel, StarButton, StarIcon, EntryList, NoticeText, NewFolderButton, NewFolderBar, NewFolderName, NewFolderCreate, NewFolderCancel));
        controls.Selected += _ => TargetChanged();
        places.Chosen += place =>
        {
            if (place.Kind == PlaceKind.Clients)
                controls.FocusSearch();
        };
        SaveButton.Click += (_, _) => Save();
        CancelButton.Click += (_, _) => Close();
        Closed += (_, _) => browser.Dispose();
        return (browser, controls);
    }

    /// <summary>The message, read-only: a line it lacks isn't shown.</summary>
    private void ShowMessage(SaveEmailRequest request)
    {
        EmailBlock.IsVisible = true;
        (EmailSubject.Text, SubjectRow.IsVisible) = (request.Subject, request.Subject is not null);
        (EmailFrom.Text, FromRow.IsVisible) = (request.From, request.From is not null);
        (EmailSent.Text, SentRow.IsVisible) = (request.Sent is { } sent ? When.DateAndTime(sent) : null, request.Sent is not null);
    }

    /// <summary>
    /// "Also file attachments as separate documents", and a tick under it for each attachment, by its name and size. They
    /// follow the box: ticked all at once with it, unticked with it, and each can be unticked while it's ticked.
    /// </summary>
    private void AddAttachments(SaveEmailRequest request)
    {
        if (request.Attachments.Count == 0)
            return;
        AttachmentsBlock.IsVisible = true;
        foreach (var part in request.Attachments)
        {
            var label = SaveEmailRequest.Label(part);
            var box = new CheckBox { Content = new TextBlock { Text = label, TextWrapping = TextWrapping.Wrap }, IsEnabled = false };
            AutomationProperties.SetName(box, label);
            var problem = new TextBlock { Classes = { "problem" }, TextWrapping = TextWrapping.Wrap, IsVisible = false, Margin = new(22, 0, 0, 2) };
            box.IsCheckedChanged += (_, _) => problem.IsVisible = false; // unticked, or ticked again, it's another try
            AttachmentList.Children.Add(new StackPanel { Children = { box, problem } });
            _attachments.Add((part, box, problem));
        }
        AttachmentsBox.IsCheckedChanged += (_, _) =>
        {
            var all = AttachmentsBox.IsChecked == true;
            foreach (var (_, box, _) in _attachments)
                (box.IsChecked, box.IsEnabled) = (all, all);
        };
    }

    /// <summary>
    /// A refusal, shown where each part belongs (<see cref="SubmitOutcome.Refused.Place"/>): a property's under its field
    /// (it stays until the field changes, as the form keeps it), an attachment's under its tick, and the rest on the
    /// problem line by Save. Before the folder's form is here, a property's waits for it.
    /// </summary>
    private void ShowRefusal(SubmitOutcome.Refused refusal)
    {
        var placed = refusal.Place(name => _form?.Field(name) is not null, index => _attachments.Any(a => a.Part.Index == index));
        foreach (var (field, detail) in placed.OnFields)
            _form!.Refused(field, detail);
        foreach (var (index, detail) in placed.OnAttachments)
        {
            var problem = _attachments.First(a => a.Part.Index == index).Problem;
            (problem.Text, problem.IsVisible) = (detail, true);
        }
        Line(placed.General, problem: true);
    }

    /// <summary>The line by Save: the wait, muted, or a problem; null hides it.</summary>
    private void Line(string? text, bool problem)
    {
        (SubmitText.Text, SubmitText.IsVisible) = (text, text is not null);
        SubmitText.Classes.Set("problem", problem);
        SubmitText.Classes.Set("muted", !problem);
    }

    /// <summary>
    /// The email mode's Save: files the answer through the window, with Save, Cancel and closing off meanwhile. Done closes
    /// the window; a refusal shows in it, and it stays open.
    /// </summary>
    private async void Submit(JsonObject answer)
    {
        _submitting = true;
        foreach (var (_, _, problem) in _attachments)
            problem.IsVisible = false;
        Line("Filing the email in Healdan…", problem: false);
        (SaveButton.Content, CancelButton.IsEnabled) = ("Filing…", false);
        UpdateSave();
        SubmitOutcome outcome;
        try
        {
            outcome = await _submit!(answer);
        }
        catch (Exception e)
        {
            // Everything, as this runs from a click: an exception would end the helper.
            Program.Log.Error("The Save window couldn't file the email", e);
            outcome = new SubmitOutcome.Refused("Healdan Helper couldn't file the email. Try again.", []);
        }
        _submitting = false;
        (SaveButton.Content, CancelButton.IsEnabled) = ("Save", true);
        if (outcome is SubmitOutcome.Refused refused)
        {
            Line(null, problem: true);
            ShowRefusal(refused);
            UpdateSave();
            return;
        }
        Finish(answer);
    }

    /// <summary>The attachments to file as separate documents, by their index: none unless the box is ticked.</summary>
    private IEnumerable<int> Ticked() => AttachmentsBox.IsChecked == true
        ? [.. _attachments.Where(a => a.Box.IsChecked == true).Select(a => a.Part.Index)]
        : [];

    /// <summary>What the window answers <c>/v1/save-new</c>, or in its email mode <c>mail/file</c>; Cancel until the user chooses otherwise.</summary>
    internal JsonObject Answer { get; private set; } = WindowAnswers.Cancel();

    /// <summary>
    /// Opens on Recent Folders, or for Save as New Document on the original's folder, with its properties, or in the email
    /// mode on the folder the request names.
    /// </summary>
    private async Task StartAsync()
    {
        long? folderId = _email?.FolderId;
        if (_request?.DocNumber is { } n)
        {
            try
            {
                var doc = _api!.DocumentAsync(n);
                var properties = _api.PropertiesAsync(n);
                var original = await doc;
                folderId = original.Folder.Id;
                // Named after the original as Healdan has it, not the window's title, unless the user has typed meanwhile.
                if (NameBox.Text == _request!.Name)
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

    /// <summary>
    /// One of the File Format pop-up's choices: the format LibreOffice saves in, and its label. Screen readers read the
    /// pop-up's value and items from <see cref="ToString"/>, so it's the label, "OpenDocument Text (.odt)", not "odt"
    /// (polish spec §9).
    /// </summary>
    private sealed record FormatChoice(string Id, string Label)
    {
        public override string ToString() => Label;
    }

    /// <summary>The formats LibreOffice can save in, as a File Format pop-up (polish spec §6.3): the same choices, order and default.</summary>
    private void AddFormats(SaveNewRequest request)
    {
        _format = request.DefaultFormat();
        if (request.Formats.Count == 0)
        {
            (FormatPicker.IsVisible, FormatProblem.IsVisible) = (false, true);
            return;
        }
        var choices = request.Formats.Select(f => new FormatChoice(f, FileFormats.Label(f))).ToList();
        FormatPicker.ItemsSource = choices;
        FormatPicker.SelectedItem = choices.FirstOrDefault(c => c.Id == _format);
        FormatPicker.SelectionChanged += (_, _) =>
        {
            if (FormatPicker.SelectedItem is FormatChoice choice)
            {
                _format = choice.Id;
                UpdateSave();
            }
        };
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
        if (_target is not null && _email is not null && !_submitting)
            Line(null, problem: true); // another folder: the last one's refusal is no longer the question
        _target = target;
        var load = ++_formLoad;
        UpdateSave();
        if (target is null)
        {
            Notice(_browser.Folder is null
                ? $"Choose a folder inside a matter to file the {What} in."
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
            // The crumbs above name the Store, so the matter's line is the client's and the matter's alone, as in Properties.
            FormHost.Content = new PropertiesForm(form, _api!, matter: folder.Matter).View;
        }
        (_form, _formFolder) = (form, folder);
        FormHost.IsVisible = true;
        FormNotice.IsVisible = false;
        if (_pendingRefusal is { } refusal && folder.Id == _email?.FolderId)
        {
            _pendingRefusal = null;
            ShowRefusal(refusal);
        }
        UpdateSave();
    }

    private void Notice(string text)
    {
        FormHost.IsVisible = false;
        (FormNotice.Text, FormNotice.IsVisible) = (text, true);
        UpdateSave();
    }

    /// <summary>What's being filed, for the notices: a document, or in the email mode an email.</summary>
    private string What => _email is null ? "document" : "email";

    /// <summary>The folder takes documents and its form is filled; and, but in the email mode, the name and format are good.</summary>
    private bool CanSave()
    {
        var ready = _target is { CanFile: true } target && _form is not null && _formFolder?.Id == target.Id && FormHost.IsVisible
            && _form.CanSave(target.CanFile);
        if (_email is not null)
            return ready && !_submitting;
        var nameProblem = DocumentNames.Problem(_request!.FinalName(NameBox.Text), _format);
        (NameProblem.Text, NameProblem.IsVisible) = (nameProblem, nameProblem is not null);
        return ready && nameProblem is null && _format is not null;
    }

    private void UpdateSave() => SaveButton.IsEnabled = CanSave();

    private void Save()
    {
        if (!CanSave())
            return;
        if (_email is not null)
            Submit(WindowAnswers.SaveEmail(_target!.Id, _form!.Values(), Ticked()));
        else
            Finish(WindowAnswers.Save(_target!.Id, _request!.FinalName(NameBox.Text), _format!, _form!.Values(), WindowAnswers.TempPath(_paths!, _format!)));
    }

    private void Finish(JsonObject answer)
    {
        Answer = answer;
        Close();
    }
}
