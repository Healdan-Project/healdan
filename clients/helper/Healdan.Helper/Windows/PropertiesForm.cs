using System.Globalization;
using Avalonia.Controls;
using Avalonia.Controls.Templates;
using Avalonia.Layout;
using Avalonia.Media;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Forms;

namespace Healdan.Helper.Windows;

/// <summary>
/// The properties form's controls (spec §5.8), built from the fields the API sends (<c>/folders/{id}/form</c>, or a
/// document's properties), so a property an administrator added to the profile shows like any other. The rules are
/// <see cref="FormModel"/>'s: this only shows its fields and passes on what the user types or picks.
/// <list type="bullet">
/// <item>Required fields are marked, Client and Matter say they come from the matter, and an inherited value says
/// from which folder.</item>
/// <item>A field that can't be changed is shown as read-only text.</item>
/// <item>Picked values are a list (a Subclass's narrowed by Class), a user field searches people, a date is a calendar
/// date, and Comments is a few lines.</item>
/// </list>
/// </summary>
internal sealed class PropertiesForm
{
    private readonly FormModel _model;
    private readonly HealdanApi _api;
    private readonly StackPanel _panel = new() { Spacing = 9 };
    private readonly Dictionary<string, TextBlock> _problems = [];
    private readonly Dictionary<string, ComboBox> _lists = [];
    private bool _quiet;
    private string? _classShown;

    /// <param name="matter">
    /// The document's or folder's matter. When given, Client and Matter, whose values are only numbers, are shown as one
    /// line, "Client / Matter", as the mockup has them: the matter as people read it (<c>998.1 – Healdan Demo</c>), and
    /// its client's name under it, smaller (owner, 2026-10-28).
    /// </param>
    public PropertiesForm(FormModel model, HealdanApi api, bool readOnly = false, MatterRef? matter = null)
    {
        _model = model;
        _api = api;
        var matterShown = false;
        foreach (var field in model.Shown)
        {
            if (matter is not null && field.FromMatter)
            {
                if (!matterShown)
                    _panel.Children.Add(MatterRow(matter));
                matterShown = true;
                continue;
            }
            _panel.Children.Add(Row(field, readOnly || !field.Editable));
        }
        _classShown = model.Field(FormModel.ClassField)?.Text;
        model.Changed += Refresh;
    }

    private static StackPanel MatterRow(MatterRef matter)
    {
        var label = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Avalonia.Thickness(0, 0, 0, 2) };
        label.Children.Add(new TextBlock { Text = "Client / Matter", Classes = { "label" } });
        label.Children.Add(new Border { Classes = { "chip" }, Child = new TextBlock { Text = "from the matter" } });
        var value = new SelectableTextBlock { Text = matter.Display, Classes = { "value" }, IsTabStop = false };
        var row = new StackPanel { Children = { label, value } };
        // A server older than the client in a matter's reference sends none.
        if (matter.Client is { } client)
            row.Children.Add(new TextBlock { Text = client.Name, Classes = { "label" }, TextWrapping = TextWrapping.Wrap });
        return row;
    }

    /// <summary>The form's controls, top to bottom.</summary>
    public Control View => _panel;

    // --- One field ------------------------------------------------------------------------------------------------

    private StackPanel Row(FormField field, bool readOnly)
    {
        var label = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Avalonia.Thickness(0, 0, 0, 2) };
        label.Children.Add(new TextBlock { Text = field.Label, Classes = { "label" } });
        if (field.Required && !readOnly)
            label.Children.Add(new TextBlock { Text = " *", Classes = { "label", "required" } });
        if (Chip(field) is { } chip)
            label.Children.Add(new Border { Classes = { "chip" }, Child = new TextBlock { Text = chip } });

        var problem = new TextBlock { Classes = { "problem" }, IsVisible = false };
        _problems[field.Name] = problem;
        return new StackPanel { Children = { label, readOnly ? ReadOnly(field) : Editor(field), problem } };
    }

    /// <summary>Where a value comes from: Client and Matter from the matter, an inherited value from its folder.</summary>
    private static string? Chip(FormField field) =>
        field.FromMatter ? "from the matter"
        : field.Source == "inherited" && field.InheritedFrom is { } folder ? $"from {folder}"
        : null;

    /// <summary>
    /// Not a Tab stop, as the Mac's selectable labels aren't (a focused one would also draw Fluent's black rectangle). It stays
    /// focusable, so a click still selects it and Ctrl+C copies the selection; <c>Focusable = false</c> would lose Ctrl+C.
    /// </summary>
    private static Control ReadOnly(FormField field) => new SelectableTextBlock
    {
        Text = field.Options is null ? field.Text : field.LabelOf(field.Text),
        Classes = { "value" },
        IsTabStop = false
    };

    private Control Editor(FormField field)
    {
        if (field.Options is not null)
            return List(field);
        return field.Type switch
        {
            "user" => PersonPicker(field),
            "date" => Date(field),
            "boolean" => Check(field),
            _ when field.Name == "comments" => Text(field, lines: true),
            _ => Text(field, lines: false)
        };
    }

    /// <summary>A value to type. Comments takes a few lines, and text stops at the API's limit.</summary>
    private Control Text(FormField field, bool lines)
    {
        var box = new TextBox
        {
            Text = field.Text,
            MaxLength = field.Type == "string" ? FormModel.MaxText : 0,
            AcceptsReturn = lines,
            TextWrapping = lines ? TextWrapping.Wrap : TextWrapping.NoWrap,
            Height = lines ? 64 : double.NaN,
            // Comments' text starts at the top: the text-field style centres a single line, which would float it in the middle.
            VerticalContentAlignment = lines ? VerticalAlignment.Top : VerticalAlignment.Center,
            PlaceholderText = field.Type switch { "integer" => "A whole number", "decimal" => "A number", _ => null }
        };
        box.TextChanged += (_, _) => Quietly(() => _model.SetText(field.Name, box.Text));
        return box;
    }

    /// <summary>A choice of the field's options; one that isn't required can be left empty.</summary>
    private ComboBox List(FormField field)
    {
        var combo = new ComboBox
        {
            HorizontalAlignment = HorizontalAlignment.Stretch,
            ItemTemplate = new FuncDataTemplate<Choice>((choice, _) => new TextBlock { Text = choice?.Label ?? "" })
        };
        _lists[field.Name] = combo;
        Fill(field, combo);
        combo.SelectionChanged += (_, _) =>
        {
            if (combo.SelectedItem is Choice choice)
                Quietly(() => _model.SetText(field.Name, choice.Value));
        };
        return combo;
    }

    private void Fill(FormField field, ComboBox combo)
    {
        var choices = new List<Choice>();
        if (!field.Required)
            choices.Add(new Choice(null, ""));
        choices.AddRange(_model.OptionsFor(field.Name).Select(o => new Choice(o.Value, o.Label)));
        // Options are text; a value the server sent as a number is matched by its digits.
        var current = !field.HasValue ? null
            : field.Value.ValueKind == System.Text.Json.JsonValueKind.String ? field.Value.GetString() : field.Value.GetRawText();
        // A value the list no longer offers is still shown, as the server has it.
        if (current is not null && choices.All(c => c.Value != current))
            choices.Add(new Choice(current, field.LabelOf(current)));
        _quiet = true;
        try
        {
            combo.ItemsSource = choices;
            combo.SelectedItem = choices.FirstOrDefault(c => c.Value == current);
        }
        finally
        {
            _quiet = false;
        }
    }

    /// <summary>A user field (Author, Typist): type part of a name and pick the person.</summary>
    private Control PersonPicker(FormField field)
    {
        var box = new AutoCompleteBox
        {
            Text = field.Text,
            MinimumPrefixLength = 1,
            MinimumPopulateDelay = TimeSpan.FromMilliseconds(250),
            FilterMode = AutoCompleteFilterMode.None,
            PlaceholderText = "Type a name",
            HorizontalAlignment = HorizontalAlignment.Stretch,
            AsyncPopulator = async (text, ct) =>
            {
                if (string.IsNullOrWhiteSpace(text))
                    return [];
                try
                {
                    var people = await _api.FindUsersAsync(text.Trim(), ct);
                    return people.Items.Select(p => (object)new Person(p.Id, p.Name)).ToList();
                }
                catch (Exception e) when (e is ApiException or HttpRequestException or OperationCanceledException)
                {
                    return [];
                }
            }
        };
        box.SelectionChanged += (_, _) =>
        {
            if (box.SelectedItem is Person person)
                Quietly(() => _model.SetUser(field.Name, person.Id, person.Name));
        };
        box.LostFocus += (_, _) =>
        {
            // Text that isn't a person picked is put back, or clears the field when emptied.
            if (string.IsNullOrWhiteSpace(box.Text))
                _model.SetUser(field.Name, null, null);
            else if (box.Text != _model.Field(field.Name)!.Text)
                box.Text = _model.Field(field.Name)!.Text;
        };
        return box;
    }

    /// <summary>A calendar date: shown as the server's UTC date and sent as that day at midnight UTC (spec §9.10).</summary>
    private Control Date(FormField field)
    {
        var picker = new CalendarDatePicker
        {
            SelectedDate = field.Date?.ToDateTime(TimeOnly.MinValue),
            SelectedDateFormat = CalendarDatePickerFormat.Custom,
            CustomDateFormatString = "d MMM yyyy",
            PlaceholderText = "Choose a date",
            HorizontalAlignment = HorizontalAlignment.Stretch,
            FirstDayOfWeek = DayOfWeek.Monday
        };
        picker.SelectedDateChanged += (_, _) => Quietly(() =>
            _model.SetText(field.Name, picker.SelectedDate is { } d ? DateOnly.FromDateTime(d).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) : null));
        return picker;
    }

    private Control Check(FormField field)
    {
        var check = new CheckBox { IsChecked = field.Value.ValueKind == System.Text.Json.JsonValueKind.True };
        check.IsCheckedChanged += (_, _) => Quietly(() => _model.SetText(field.Name, check.IsChecked == true ? "true" : "false"));
        return check;
    }

    // --- Keeping up with the model --------------------------------------------------------------------------------

    /// <summary>Passes on a change the user made.</summary>
    private void Quietly(Action change)
    {
        if (!_quiet)
            change();
    }

    /// <summary>The problems under each field, and once the Class changes, the lists it narrows.</summary>
    private void Refresh()
    {
        foreach (var field in _model.Fields)
            if (_problems.TryGetValue(field.Name, out var problem))
                (problem.Text, problem.IsVisible) = (field.Problem, field.Problem is not null);
        var chosen = _model.Field(FormModel.ClassField)?.Text;
        if (chosen == _classShown)
            return;
        _classShown = chosen;
        foreach (var (name, combo) in _lists)
            if (_model.Field(name) is { } field && field.Options?.Any(o => o.Class is not null) == true)
                Fill(field, combo);
    }

    /// <summary>An item of a list: a value and its label; a null value leaves the field empty.</summary>
    private sealed record Choice(string? Value, string Label);

    /// <summary>A person found for a user field; its name is what the box shows once picked.</summary>
    private sealed record Person(long Id, string Name)
    {
        public override string ToString() => Name;
    }
}
