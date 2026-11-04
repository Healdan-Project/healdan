// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Text.Json.Nodes;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Media;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Browse;
using Healdan.Helper.Core.Forms;

namespace Healdan.Helper.Windows;

/// <summary>
/// The Save window's email mode, File to Healdan (M5 spec §7.3), for Outlook's <c>mail/file</c>: the message read-only in
/// place of the name, its attachments in place of the format, and a Save that files through the window. The folder
/// picker, the properties form and the rest are the Save window's own (<c>SaveWindow.axaml.cs</c>).
/// </summary>
public partial class SaveWindow
{
    private readonly SaveEmailRequest? _email;
    private readonly Func<JsonObject, Task<SubmitOutcome>>? _submit;
    private readonly List<(EmailPart Part, CheckBox Box, TextBlock Problem)> _attachments = [];
    private SubmitOutcome.Refused? _pendingRefusal;
    private bool _submitting;

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
}
