#if DEBUG
using System.Text.Json;
using System.Text.Json.Nodes;
using Healdan.Helper.Core;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Forms;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Windows;

/// <summary>
/// A Debug build's stand-in for the user at the window routes, so the channel's wiring can be checked against a stack
/// with nobody at the screen. <c>HEALDAN_HELPER_DEV_ANSWERS</c> names a JSON file, read at each request:
/// <code>{"save-new": {"folderId": 7, "values": {"comments": "…"}}, "properties": {"values": {…}}, "open-dialog": {"action": "cancel"}}</code>
/// The answers go through the windows' own rules (<see cref="FormModel"/>, <see cref="WindowAnswers"/>) against the
/// server: Save loads the folder's form and answers only what <c>CanSave</c> allows; Properties loads the document's,
/// applies the values and sends them with <c>PATCH</c>. Release builds have none of this.
/// </summary>
internal sealed class DevAnswers(JsonObject answers, HelperLog log)
{
    public static DevAnswers? Load(HelperLog log)
    {
        if (Environment.GetEnvironmentVariable("HEALDAN_HELPER_DEV_ANSWERS") is not { Length: > 0 } file)
            return null;
        try
        {
            return new DevAnswers(JsonNode.Parse(File.ReadAllText(file))!.AsObject(), log);
        }
        catch (Exception e) when (e is IOException or JsonException or InvalidOperationException or UnauthorizedAccessException)
        {
            log.Warn("Debug build: HEALDAN_HELPER_DEV_ANSWERS couldn't be read: " + e.Message);
            return null;
        }
    }

    public JsonObject OpenDialog() => Action(answers["open-dialog"] as JsonObject) switch
    {
        "local" => WindowAnswers.Local(),
        _ => WindowAnswers.Cancel()
    };

    public async Task<JsonObject> SaveNewAsync(JsonObject body, HealdanApi api, HelperPaths paths)
    {
        var spec = answers["save-new"] as JsonObject ?? [];
        if (Action(spec) is "local")
            return WindowAnswers.Local();
        if (Action(spec) is not "save" || spec["folderId"] is not JsonValue id)
            return WindowAnswers.Cancel();
        var request = SaveNewRequest.From(body);
        var folderId = id.GetValue<long>();
        var folder = await api.FolderAsync(folderId);
        var form = new FormModel(await api.FormAsync(folderId));
        var name = request.Name;
        if (request.DocNumber is { } original)
        {
            name = request.StartingName((await api.DocumentAsync(original)).Name);
            form.Prefill(new FormModel(await api.PropertiesAsync(original)));
        }
        Apply(form, spec["values"] as JsonObject);
        name = request.FinalName((string?)spec["name"] ?? name);
        var format = (string?)spec["format"] ?? request.DefaultFormat();
        if (!form.CanSave(folder.CanFile) || DocumentNames.Problem(name, format) is { } problem && problem.Length > 0 || format is null)
        {
            log.Warn($"Debug build: Save to Healdan wouldn't be enabled for folder {folderId} (canFile {folder.CanFile})");
            return WindowAnswers.Cancel();
        }
        log.Info($"Debug build: answering Save to Healdan for folder {folderId} without the window");
        return WindowAnswers.Save(folderId, name, format, form.Values(), WindowAnswers.TempPath(paths, format));
    }

    public async Task<JsonObject> PropertiesAsync(long docNumber, HealdanApi api)
    {
        var spec = answers["properties"] as JsonObject ?? [];
        if (Action(spec) is "cancel")
            return WindowAnswers.Cancel();
        var form = new FormModel(await api.PropertiesAsync(docNumber));
        Apply(form, spec["values"] as JsonObject);
        if (!form.CanPatch())
        {
            log.Warn($"Debug build: Properties' Save wouldn't be enabled for #{docNumber}");
            return WindowAnswers.Cancel();
        }
        await api.PatchPropertiesAsync(docNumber, JsonSerializer.SerializeToElement(form.Values()));
        log.Info($"Debug build: saved #{docNumber}'s properties without the window");
        return WindowAnswers.Saved();
    }

    private static string Action(JsonObject? spec) => (string?)spec?["action"] ?? "save";

    /// <summary>Each value as the form would take it from the user: text as typed, anything else as JSON.</summary>
    private static void Apply(FormModel form, JsonObject? values)
    {
        foreach (var (name, value) in values ?? [])
        {
            if (value is JsonValue v && v.TryGetValue<string>(out var text))
                form.SetText(name, text);
            else
                form.Set(name, JsonSerializer.SerializeToElement(value));
        }
    }
}
#endif
