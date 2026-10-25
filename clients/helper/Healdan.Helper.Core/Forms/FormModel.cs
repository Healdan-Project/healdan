using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Api;

namespace Healdan.Helper.Core.Forms;

/// <summary>
/// The properties form's rules (spec §5.8), free of windows: the Save window's right pane for a new document
/// (<c>GET /v1/folders/{id}/form</c>), and the Properties window for an open one (<c>GET …/properties</c>).
/// <list type="bullet">
/// <item>Client and Matter come from the matter, as do the other fields the server marks not editable: they're shown,
/// never sent.</item>
/// <item>A Subclass is picked from its Class's: choosing a Class narrows the list, and drops a Subclass of another.</item>
/// <item>A date is a calendar date (spec §9.10): it shows the UTC date of what the server holds, and a date set here is
/// sent as midnight UTC, so it reads the same in any time zone.</item>
/// <item>Text is at most <see cref="MaxText"/> characters, as the API takes it.</item>
/// <item><see cref="Values"/> holds only what the user changed: the server ignores a value sent as it is, but sending
/// only changes keeps a filing's inherited values inherited.</item>
/// </list>
/// </summary>
public sealed class FormModel
{
    public const int MaxText = 4000;
    public const string ClientField = "client";
    public const string MatterField = "matter";
    public const string ClassField = "docclass";

    /// <summary>A new document is a new document: the original's legacy doc # isn't its own.</summary>
    private const string LegacyDocNumberField = "legacydocnum";

    internal static readonly JsonElement Null = JsonDocument.Parse("null").RootElement.Clone();

    private readonly List<FormField> _fields;

    /// <param name="form">A <c>ProfileFieldPage</c> as the API answers it, or its <c>items</c>.</param>
    public FormModel(JsonElement form)
    {
        var items = form.ValueKind == JsonValueKind.Object && form.TryGetProperty("items", out var page) ? page : form;
        if (items.ValueKind != JsonValueKind.Array)
            throw new ArgumentException("The form has no fields.", nameof(form));
        _fields = [.. items.EnumerateArray().Select(item => new FormField(item.Deserialize<ProfileField>(HealdanApi.Json)!))];
    }

    /// <summary>Raised when a value or a problem changes, on the thread that changed it.</summary>
    public event Action? Changed;

    /// <summary>Every field, in the profile's order.</summary>
    public IReadOnlyList<FormField> Fields => _fields;

    /// <summary>
    /// The fields a form shows: every one that can be changed or has a value. A new document's email fields come
    /// from a message and can't be typed in, so they stay out of the way.
    /// </summary>
    public IReadOnlyList<FormField> Shown => [.. _fields.Where(f => f.Editable || f.HasValue)];

    public FormField? Field(string name) => _fields.FirstOrDefault(f => f.Name == name);

    /// <summary>Sets a field's value as JSON, in the form the API sends it. A field that can't be changed is left alone.</summary>
    public void Set(string name, JsonElement value)
    {
        if (Field(name) is not { Editable: true } field)
            return;
        var normal = FormField.Normal(value);
        if (field.Type == "date" && normal.ValueKind == JsonValueKind.String)
        {
            if (CalendarDate(normal) is not { } date)
            {
                Problem(field, $"{field.Label} takes a date such as 2026-10-24.");
                return;
            }
            normal = Json(Midnight(date));
        }
        else if (normal.ValueKind == JsonValueKind.String && normal.GetString()!.Trim().Length == 0)
            normal = Null;
        Put(field, normal, normal.ValueKind == JsonValueKind.String && normal.GetString()!.Length > MaxText
            ? $"{field.Label} takes at most {MaxText} characters."
            : null);
    }

    /// <summary>
    /// Sets a field from what was typed or picked, read as its type: a date as <c>2026-10-24</c>, numbers in the
    /// invariant form. What can't be read is kept as the field's <see cref="FormField.Problem"/>, and blocks saving.
    /// Blank text clears the field.
    /// </summary>
    public void SetText(string name, string? text)
    {
        if (Field(name) is not { Editable: true } field)
            return;
        if (string.IsNullOrWhiteSpace(text))
        {
            Put(field, Null, null);
            return;
        }
        var t = text.Trim();
        switch (field.Type)
        {
            case "integer" or "folder" or "document":
                if (long.TryParse(t, NumberStyles.Integer, CultureInfo.InvariantCulture, out var whole))
                    Put(field, Json(whole), null);
                else
                    Problem(field, $"{field.Label} takes a whole number.");
                break;
            case "decimal":
                if (decimal.TryParse(t, NumberStyles.Number, CultureInfo.InvariantCulture, out var number))
                    Put(field, Json(number), null);
                else
                    Problem(field, $"{field.Label} takes a number such as 12.5.");
                break;
            case "boolean":
                if (t.ToLowerInvariant() is "true" or "yes" or "false" or "no")
                    Put(field, Json(t.ToLowerInvariant() is "true" or "yes"), null);
                else
                    Problem(field, $"{field.Label} takes yes or no.");
                break;
            case "date":
                if (DateOnly.TryParseExact(t, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var date))
                    Put(field, Json(Midnight(date)), null);
                else
                    Problem(field, $"{field.Label} takes a date such as 2026-10-24.");
                break;
            case "user":
                Problem(field, $"Pick someone for {field.Label}.");
                break;
            default:
                Set(name, Json(text));
                break;
        }
    }

    /// <summary>Sets a user field to the user with <paramref name="id"/>, or clears it when that's null.</summary>
    public void SetUser(string name, long? id, string? displayName)
    {
        if (Field(name) is not { Editable: true, Type: "user" } field)
            return;
        Put(field, id is { } n ? Json(new JsonObject { ["id"] = n, ["name"] = displayName }) : Null, null);
    }

    /// <summary>The values to pick for the field: a Subclass's only its Class's, none until a Class is chosen.</summary>
    public IReadOnlyList<Option> OptionsFor(string name)
    {
        var options = Field(name)?.Options;
        if (options is null)
            return [];
        if (options.All(o => o.Class is null))
            return options;
        var chosen = Field(ClassField) is { HasValue: true } c && c.Value.ValueKind == JsonValueKind.String ? c.Value.GetString() : null;
        return [.. options.Where(o => o.Class is not null && o.Class == chosen)];
    }

    /// <summary>Save to Healdan: a folder that takes documents is chosen, every required field has a value, and nothing is wrong.</summary>
    public bool CanSave(bool folderCanFile) =>
        folderCanFile && _fields.All(f => (!f.Required || f.HasValue) && f.Problem is null);

    /// <summary>The Properties window's Save: something changed, nothing is wrong, and no required field changed to empty.</summary>
    public bool CanPatch()
    {
        var changed = _fields.Where(IsChanged).ToList();
        return changed.Count > 0 && _fields.All(f => f.Problem is null) && changed.All(f => !f.Required || f.HasValue);
    }

    /// <summary>
    /// What the user changed, by name, as filing's <c>properties</c> and <c>PATCH</c>'s <c>values</c> take it: a user
    /// by its id, a cleared field as null. Fields that can't be changed are never in it.
    /// </summary>
    public JsonObject Values()
    {
        var values = new JsonObject();
        foreach (var field in _fields.Where(IsChanged))
        {
            values[field.Name] = field.Value.ValueKind == JsonValueKind.Null ? null
                : field.Type == "user" && field.UserId is { } id ? new JsonObject { ["id"] = id }
                : JsonNode.Parse(field.Value.GetRawText());
        }
        return values;
    }

    /// <summary>The user moved to another folder: what they changed in <paramref name="previous"/> carries over to this form.</summary>
    public void Carry(FormModel previous)
    {
        foreach (var field in previous._fields.Where(previous.IsChanged))
            if (Field(field.Name) is { Editable: true } here)
                Put(here, field.Value, null);
    }

    /// <summary>
    /// Save as New Document (spec §5.5): the new document starts with the original's values, from its
    /// <paramref name="properties"/>. Only those that differ from what this folder gives are sent. Not its legacy doc #.
    /// </summary>
    public void Prefill(FormModel properties)
    {
        foreach (var field in properties._fields)
            if (field.Name != LegacyDocNumberField && Field(field.Name) is { Editable: true } here)
                Put(here, field.Value, null);
    }

    /// <summary>The server refused the field's value (a <c>422</c>): its words stay on the field until it's changed.</summary>
    public void Refused(string name, string detail)
    {
        if (Field(name) is { } field)
            Problem(field, detail);
    }

    /// <summary>The UTC date of a timestamp the API sent, or of a date typed as <c>2026-10-24</c>; null for anything else.</summary>
    public static DateOnly? CalendarDate(JsonElement value)
    {
        if (value.ValueKind != JsonValueKind.String)
            return null;
        var text = value.GetString()!.Trim();
        if (DateOnly.TryParseExact(text, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var plain))
            return plain;
        return DateTimeOffset.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var at)
            ? DateOnly.FromDateTime(at.UtcDateTime)
            : null;
    }

    private static string Midnight(DateOnly date) => date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) + "T00:00:00Z";

    private bool IsChanged(FormField field) => field.Editable && !Same(field, field.Original, field.Value);

    private static bool Same(FormField field, JsonElement a, JsonElement b)
    {
        var (aNull, bNull) = (a.ValueKind == JsonValueKind.Null, b.ValueKind == JsonValueKind.Null);
        if (aNull || bNull)
            return aNull && bNull;
        return field.Type switch
        {
            "user" => UserIdOf(a) == UserIdOf(b),
            "date" => CalendarDate(a) is { } x && x == CalendarDate(b),
            _ when a.ValueKind == JsonValueKind.Number && b.ValueKind == JsonValueKind.Number => a.GetDecimal() == b.GetDecimal(),
            _ => a.ValueKind == b.ValueKind && a.GetRawText() == b.GetRawText()
        };
    }

    private static long? UserIdOf(JsonElement value) =>
        value.ValueKind == JsonValueKind.Object && value.TryGetProperty("id", out var id) && id.TryGetInt64(out var n) ? n
        : value.ValueKind == JsonValueKind.Number && value.TryGetInt64(out var bare) ? bare
        : null;

    private void Put(FormField field, JsonElement value, string? problem)
    {
        field.Value = value;
        field.Problem = problem;
        if (field.Name == ClassField)
            NarrowSubclasses();
        Changed?.Invoke();
    }

    private void Problem(FormField field, string problem)
    {
        field.Problem = problem;
        Changed?.Invoke();
    }

    /// <summary>A Class was chosen: a Subclass that belongs to another Class goes.</summary>
    private void NarrowSubclasses()
    {
        foreach (var field in _fields.Where(f => f.Options?.Any(o => o.Class is not null) == true && f.HasValue))
        {
            var value = field.Value.ValueKind == JsonValueKind.String ? field.Value.GetString() : null;
            if (!OptionsFor(field.Name).Any(o => o.Value == value))
            {
                field.Value = Null;
                field.Problem = null;
            }
        }
    }

    private static JsonElement Json<T>(T value) => JsonSerializer.SerializeToElement(value, HealdanApi.Json);
}
