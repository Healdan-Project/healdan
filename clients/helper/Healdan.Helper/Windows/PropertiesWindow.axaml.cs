using System.Text.Json;
using System.Text.Json.Nodes;
using Avalonia.Controls;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Forms;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Windows;

/// <summary>
/// Properties (spec §5.5, §5.8): the open document's properties (<c>GET …/properties</c>), saved with <c>PATCH</c>.
/// Only what the user changed is sent. A refusal stays in the window, on the fields it names; when nothing can be
/// changed (someone else has it, or the user may not), the form is shown read-only.
/// </summary>
public partial class PropertiesWindow : Window
{
    private readonly HealdanApi? _api;
    private readonly long _docNumber;
    private FormModel? _form;

    /// <summary>For the XAML loader; the helper always opens it for a document.</summary>
    public PropertiesWindow() => InitializeComponent();

    internal PropertiesWindow(HealdanApi api, long docNumber, string title) : this()
    {
        (_api, _docNumber) = (api, docNumber);
        DocTitle.Text = title;
        SaveButton.IsEnabled = false;
        SaveButton.Click += async (_, _) => await SaveAsync();
        CancelButton.Click += (_, _) => Close();
        Opened += async (_, _) => await LoadAsync();
    }

    /// <summary>What the window answers <c>/v1/properties</c>; Cancel until saved.</summary>
    internal JsonObject Answer { get; private set; } = WindowAnswers.Cancel();

    private async Task LoadAsync()
    {
        try
        {
            var doc = _api!.DocumentAsync(_docNumber);
            var form = new FormModel(await _api.PropertiesAsync(_docNumber));
            var matter = (await doc).Matter?.Display;
            var editable = form.Fields.Any(f => f.Editable);
            NoteText.IsVisible = !editable;
            NoteText.Text = "These properties can't be changed now: you may not edit this document, or it's checked out to someone else.";
            FormHost.Content = new PropertiesForm(form, _api, readOnly: !editable, matter: matter).View;
            SaveButton.IsVisible = editable;
            CancelButton.Content = editable ? "Cancel" : "Close";
            form.Changed += () => SaveButton.IsEnabled = form.CanPatch();
            _form = form;
        }
        catch (Exception e)
        {
            // Everything, as this runs from a UI event: an exception would end the helper.
            if (e is not (ApiException or HttpRequestException or OperationCanceledException))
                Program.Log.Error("Properties couldn't show the document's form", e);
            NoteText.Text = e is ApiException { Detail.Length: > 0 } api ? api.Detail : "The properties couldn't be read from Healdan. Try again in a moment.";
        }
    }

    private async Task SaveAsync()
    {
        if (_form is null || !_form.CanPatch())
            return;
        SaveButton.IsEnabled = false;
        ProblemText.IsVisible = false;
        try
        {
            await _api!.PatchPropertiesAsync(_docNumber, JsonSerializer.SerializeToElement(_form.Values()));
            Answer = WindowAnswers.Saved();
            Close();
            return;
        }
        catch (ApiException e) when (e.Code == "validation")
        {
            foreach (var field in Fields(e.Body))
                _form.Refused(field.Field, field.Detail);
            Say("Healdan didn't take some of the values: see the fields marked.");
        }
        catch (ApiException e)
        {
            Say(e.Detail.Length > 0 ? e.Detail : $"Healdan refused the change ({e.Status}).");
        }
        catch (Exception e) when (e is HttpRequestException or OperationCanceledException)
        {
            Say("Healdan can't be reached, so nothing was changed. Try again in a moment.");
        }
        catch (Exception e)
        {
            Program.Log.Error("Properties' Save failed", e);
            Say("The change couldn't be sent. Try again in a moment.");
        }
        SaveButton.IsEnabled = _form.CanPatch();
    }

    private static IEnumerable<FieldError> Fields(JsonElement problem) =>
        problem.ValueKind == JsonValueKind.Object && problem.TryGetProperty("fields", out var fields) && fields.ValueKind == JsonValueKind.Array
            ? fields.Deserialize<List<FieldError>>(new JsonSerializerOptions(JsonSerializerDefaults.Web)) ?? []
            : [];

    private void Say(string problem) => (ProblemText.Text, ProblemText.IsVisible) = (problem, true);
}
