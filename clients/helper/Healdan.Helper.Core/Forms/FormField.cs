using System.Globalization;
using System.Text.Json;
using Healdan.Helper.Core.Api;

namespace Healdan.Helper.Core.Forms;

/// <summary>
/// One property in the properties form (spec §5.8), with its value as the user has it now. Its rules are
/// <see cref="FormModel"/>'s; this is what a window shows.
/// </summary>
public sealed class FormField
{
    internal FormField(ProfileField field)
    {
        Name = field.Name;
        Label = field.Label;
        Type = field.Type;
        Required = field.Required;
        Editable = field.Editable;
        Source = field.Source;
        InheritedFrom = field.InheritedFrom?.Name;
        Options = field.Options;
        Original = Normal(field.Value);
        Value = Original;
    }

    /// <summary>The property's name, as <c>PATCH</c> and filing name it, such as <c>docclass</c>.</summary>
    public string Name { get; }

    public string Label { get; }

    /// <summary><c>string</c>, <c>integer</c>, <c>decimal</c>, <c>date</c>, <c>boolean</c>, <c>user</c>, <c>folder</c> or <c>document</c>.</summary>
    public string Type { get; }

    public bool Required { get; }

    /// <summary>False for Client and Matter, the email fields and read-only properties; then it's shown, never sent.</summary>
    public bool Editable { get; }

    /// <summary><c>inherited</c>, <c>local</c> or <c>default</c>, as the server sent the value; null when it had none.</summary>
    public string? Source { get; }

    /// <summary>The folder an inherited value comes from, such as <c>Drafts</c>.</summary>
    public string? InheritedFrom { get; }

    /// <summary>Client and Matter: they come from the matter, and the form marks them so.</summary>
    public bool FromMatter => Name is FormModel.ClientField or FormModel.MatterField;

    /// <summary>Every value to pick from, unnarrowed (<see cref="FormModel.OptionsFor"/> narrows a Subclass's).</summary>
    public IReadOnlyList<Option>? Options { get; }

    /// <summary>The value as the server sent it.</summary>
    internal JsonElement Original { get; }

    /// <summary>The value now: as the server sent it, or as the user set it. <c>Null</c> when there's none.</summary>
    public JsonElement Value { get; internal set; }

    /// <summary>Why the value can't be sent, in a sentence to show under the field; null when it can.</summary>
    public string? Problem { get; internal set; }

    public bool HasValue => Value.ValueKind switch
    {
        JsonValueKind.Null or JsonValueKind.Undefined => false,
        JsonValueKind.String => Value.GetString()!.Trim().Length > 0,
        _ => true
    };

    /// <summary>A user field's user, by id; null otherwise.</summary>
    public long? UserId => Type == "user" && Value.ValueKind == JsonValueKind.Object && Value.TryGetProperty("id", out var id)
        && id.TryGetInt64(out var n) ? n : null;

    /// <summary>A date field's calendar date: the UTC date of the timestamp (spec §9.10), the same in any time zone.</summary>
    public DateOnly? Date => Type == "date" ? FormModel.CalendarDate(Value) : null;

    /// <summary>The value as the form shows it: text as it is, a date as <c>2026-10-24</c>, a user by name.</summary>
    public string Text => Value.ValueKind switch
    {
        JsonValueKind.Null or JsonValueKind.Undefined => "",
        _ when Type == "date" => Date?.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) ?? Value.ToString(),
        _ when Type == "user" && Value.ValueKind == JsonValueKind.Object =>
            Value.TryGetProperty("name", out var name) && name.ValueKind == JsonValueKind.String ? name.GetString()! : $"User {UserId}",
        JsonValueKind.True => "Yes",
        JsonValueKind.False => "No",
        JsonValueKind.Number => Value.GetRawText(),
        _ => Value.ToString()
    };

    /// <summary>A label's text for a picked value: its option's label when it has one.</summary>
    public string LabelOf(string value) => Options?.FirstOrDefault(o => o.Value == value)?.Label ?? value;

    /// <summary>JSON's null and a missing value are the same here.</summary>
    internal static JsonElement Normal(JsonElement value) =>
        value.ValueKind == JsonValueKind.Undefined ? FormModel.Null : value.Clone();
}
