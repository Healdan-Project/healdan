using System.Text.Json;
using System.Text.Json.Nodes;

namespace Healdan.Helper.Core.Tests.Forms;

/// <summary>
/// The properties form as <c>GET /v1/folders/{id}/form</c> answers it for a folder inside matter 998.1, in the profile's
/// order: Client and Matter from the matter, Author and Typist the caller, Class inherited from Drafts, Subclass picked
/// by Class, Comments, an administrator's own date property, and an email field that comes from the message.
/// </summary>
public static class Forms
{
    private const string Matter = """{"id":10,"name":"998.1 – Healdan Demo","kind":"matter"}""";
    private const string Drafts = """{"id":12,"name":"Drafts","kind":"folder"}""";

    public static readonly string Page = $$"""
        {"items":[
          {"name":"client","label":"Client","type":"string","required":true,"editable":false,"value":"998 – Healdan","source":"inherited","inheritedFrom":{{Matter}},"options":null},
          {"name":"matter","label":"Matter","type":"string","required":true,"editable":false,"value":"998.1 – Healdan Demo","source":"inherited","inheritedFrom":{{Matter}},"options":null},
          {"name":"author","label":"Author","type":"user","required":true,"editable":true,"value":{"id":1,"name":"Robert Example"},"source":"default","inheritedFrom":null,"options":null},
          {"name":"typist","label":"Typist","type":"user","required":false,"editable":true,"value":{"id":1,"name":"Robert Example"},"source":"default","inheritedFrom":null,"options":null},
          {"name":"docclass","label":"Class","type":"string","required":true,"editable":true,"value":"Docs","source":"inherited","inheritedFrom":{{Drafts}},
           "options":[{"value":"Docs","label":"Docs","class":null},{"value":"Letters","label":"Letters","class":null}]},
          {"name":"docsubclass","label":"Subclass","type":"string","required":false,"editable":true,"value":"Draft","source":"inherited","inheritedFrom":{{Drafts}},
           "options":[{"value":"Draft","label":"Draft","class":"Docs"},{"value":"Final","label":"Final","class":"Docs"},{"value":"Outgoing","label":"Outgoing","class":"Letters"}]},
          {"name":"comments","label":"Comments","type":"string","required":false,"editable":true,"value":null,"source":null,"inheritedFrom":null,"options":null},
          {"name":"deadline","label":"Deadline","type":"date","required":false,"editable":true,"value":null,"source":null,"inheritedFrom":null,"options":null},
          {"name":"pages","label":"Pages","type":"integer","required":false,"editable":true,"value":null,"source":null,"inheritedFrom":null,"options":null},
          {"name":"emailfrom","label":"From","type":"string","required":false,"editable":false,"value":null,"source":null,"inheritedFrom":null,"options":null}
        ],"offset":0,"limit":10,"total":10}
        """;

    /// <summary>The form, with <paramref name="change"/> made to its fields first (by name).</summary>
    public static JsonElement Form(Action<Dictionary<string, JsonObject>>? change = null)
    {
        var page = JsonNode.Parse(Page)!.AsObject();
        var fields = page["items"]!.AsArray().Select(f => f!.AsObject()).ToDictionary(f => (string)f["name"]!);
        change?.Invoke(fields);
        return JsonDocument.Parse(page.ToJsonString()).RootElement.Clone();
    }

    public static JsonElement Json(string json) => JsonDocument.Parse(json).RootElement.Clone();
}
