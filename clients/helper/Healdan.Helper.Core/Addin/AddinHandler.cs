using System.Text.Json;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Channel;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Office;

namespace Healdan.Helper.Core.Addin;

/// <summary>An answer on the add-in's port: its status, and its JSON (<c>{"error": "&lt;code&gt;"}</c> for a refusal).</summary>
public sealed record AddinAnswer(int Status, JsonObject Body)
{
    public static AddinAnswer Ok(JsonNode? body) => new(200, body as JsonObject ?? new JsonObject());

    public static AddinAnswer Error(int status, string code) => new(status, new JsonObject { ["error"] = code });
}

/// <summary>
/// What the add-in's document routes work on while the helper is signed in and started: its cycle, the filing (which holds
/// Save to Healdan's tickets) and the extension's handler, for the windows. The session makes new ones at each start, so a
/// ticket given before a sign-out is no use after it, to the same server or another.
/// </summary>
public sealed record AddinDocuments(CopyCycle Cycle, OfficeFiling Filing, IChannelHandler Channel);

/// <summary>
/// What the add-in's routes do (M3.5 plan: the add-in's routes), once <see cref="AddinServer"/> has let the request through.
/// The routes are the extension's (spec §5.8), for the program the request's key was paired for: the windows go to the
/// extension's handler, filing to <see cref="OfficeFiling"/>, and Save as New Version and the copy's state to the cycle.
/// <para>The add-in can't always say which document it's in, so the helper decides (spec §5.8): the copy its <c>url</c>
/// names, when that's one of the helper's copies, else the program's front window's (<see cref="OfficeFiling.CopyForAsync"/>).
/// Paths stay copies in <c>Copies</c>, as the extension's do, except for Save to Healdan on a document not in Healdan.</para>
/// <para>The port outlives sign-ins, so the document routes look up the session's <see cref="AddinDocuments"/> at each
/// request, and answer <c>409 signed-out</c> while there are none; pairing needs no sign-in.</para>
/// <para>Its log says what was asked and what came of it, never a code, a key or a document's contents.</para>
/// </summary>
/// <param name="documents">The document routes' cycle, filing and windows now; null while the helper isn't signed in and started.</param>
public sealed class AddinHandler(Pairing pairing, Func<AddinDocuments?> documents, OfficeApps apps, HelperLog log)
{
    /// <summary>The pairings, whose keys the port checks.</summary>
    public Pairing Pairing => pairing;

    /// <summary>
    /// Answers <c>/v1/&lt;route&gt;</c>. <paramref name="app"/> is the app the request's key was paired for (null for the
    /// pairing routes, which need no key). A body that names another app is refused: a key drives only its own app.
    /// Refusals are <c>409</c> with the filing's code; a body that isn't what the route takes is <c>400 bad-request</c>; any
    /// other failure is <c>500 internal-error</c>, logged, and the filing has told the user why where it's theirs to know.
    /// </summary>
    public async Task<AddinAnswer> HandleAsync(string route, string? app, JsonObject body)
    {
        try
        {
            if (route is "pair/start")
                return PairStart(body);
            if (route is "pair/finish")
                return PairFinish(body);
            if (documents() is not { } current)
                return AddinAnswer.Error(409, "signed-out");
            var (cycle, filing, channel) = current;
            switch (route)
            {
                case "open-dialog":
                    return AddinAnswer.Ok(await channel.HandleAsync("open-dialog", new JsonObject { ["app"] = Name(Program(app, body)) }));
                case "save-new":
                    return AddinAnswer.Ok(await filing.SaveNewAsync(Program(app, body), LocalPath(Optional(body, "url")), Flag(body, "asNew")));
                case "file":
                    var state = await filing.FileAsync(Text(body, "ticket"), Optional(body, "mark"), Bytes(body, "bytes"));
                    return AddinAnswer.Ok(ChannelHandler.Json(state));
                case "new-version":
                    return await NewVersionAsync(cycle, filing, Program(app, body), body);
                case "properties":
                    var p = Program(app, body);
                    var request = new JsonObject { ["app"] = Name(p) };
                    if (await filing.CopyForAsync(p, LocalPath(Optional(body, "url"))) is { } copy)
                        request["path"] = copy;
                    // Not in Healdan: answered {"action": "file"}, and the add-in runs Save to Healdan instead (spec §5.6).
                    return AddinAnswer.Ok(await channel.HandleAsync("properties", request));
                case "state":
                    var program = Program(app, body);
                    return AddinAnswer.Ok(await filing.CopyForAsync(program, LocalPath(Optional(body, "url"))) is { } known
                        ? ChannelHandler.Json(cycle.StateOf(known))
                        : new JsonObject { ["known"] = false });
                default:
                    return AddinAnswer.Error(404, "not-found");
            }
        }
        catch (OfficeRefusedException e)
        {
            log.Warn($"Refused /v1/{route} from the add-in: {e.Code}");
            return AddinAnswer.Error(409, e.Code);
        }
        catch (PairingRestingException)
        {
            log.Warn("Refused a pairing: too many wrong codes in a row a moment ago");
            return AddinAnswer.Error(403, "too-many-tries");
        }
        catch (Exception e) when (e is ArgumentException or FormatException)
        {
            log.Warn($"/v1/{route} from the add-in wasn't a request it takes: {e.Message}");
            return AddinAnswer.Error(400, "bad-request");
        }
        catch (Exception e)
        {
            log.Error($"/v1/{route} from the add-in failed", e);
            return AddinAnswer.Error(500, "internal-error");
        }
    }

    /// <summary><c>/v1/pair/start</c>: the helper's window shows a code for the app.</summary>
    private AddinAnswer PairStart(JsonObject body)
    {
        var app = Text(body, "app");
        var id = pairing.Start(app);
        log.Info($"{app} asked to pair: its code is shown in the helper's window");
        return AddinAnswer.Ok(new JsonObject { ["pairingId"] = id });
    }

    /// <summary><c>/v1/pair/finish</c>: the code the user typed in the add-in's pane.</summary>
    private AddinAnswer PairFinish(JsonObject body)
    {
        switch (pairing.Finish(Text(body, "pairingId"), Text(body, "code")))
        {
            case PairResult.Key key:
                log.Info($"{key.App} paired with Healdan Helper");
                return AddinAnswer.Ok(new JsonObject { ["key"] = key.Value });
            case PairResult.WrongCode wrong:
                log.Warn(wrong.TriesLeft > 0
                    ? $"A wrong pairing code was typed: {wrong.TriesLeft} {(wrong.TriesLeft == 1 ? "try" : "tries")} left"
                    : "A third wrong pairing code was typed: the pairing is cancelled");
                return new AddinAnswer(403, new JsonObject { ["error"] = "wrong-code", ["triesLeft"] = wrong.TriesLeft });
            default:
                log.Info("A pairing code came for a pairing that's over");
                return AddinAnswer.Error(403, "pairing-expired");
        }
    }

    /// <summary>
    /// <c>/v1/new-version</c>: Save as New Version (spec §5.6) on the request's copy. With unsaved changes, the program saves
    /// first, and that save goes up as the version; without, the file as it is goes up as one. The save comes before the
    /// mark, so the version settles with the save's write in it rather than going up just before it. A read-only copy has
    /// no version to give, as from the extension.
    /// </summary>
    private async Task<AddinAnswer> NewVersionAsync(CopyCycle cycle, OfficeFiling filing, OfficeProgram p, JsonObject body)
    {
        var copy = await filing.CopyForAsync(p, LocalPath(Optional(body, "url")))
            ?? throw new OfficeRefusedException(OfficeRefusedException.NotACopy, "Save as New Version needs a document opened from Healdan.");
        var state = cycle.StateOf(copy);
        if (state.ReadOnly)
        {
            log.Info($"Save as New Version on {state.DocRef}, which is open read-only: nothing to send");
            return AddinAnswer.Ok(new JsonObject());
        }
        var scripting = apps.MicrosoftOffice?.Scripting
            ?? throw new InvalidOperationException("Microsoft Office isn't set up for Healdan Helper on this computer.");
        if (await scripting.HasUnsavedChangesAsync(p, copy) == true)
            await scripting.SaveAsync(p, copy);
        cycle.MarkNextVersion(copy);
        log.Info($"Save as New Version on {state.DocRef} from {Name(p)}");
        return AddinAnswer.Ok(new JsonObject());
    }

    /// <summary>
    /// The program the request is for: the one its key was paired for. A body naming another is refused, as a key drives
    /// only its own app.
    /// </summary>
    private static OfficeProgram Program(string? keyApp, JsonObject body)
    {
        var program = Enum.GetValues<OfficeProgram>().Cast<OfficeProgram?>().FirstOrDefault(p => OfficePrograms.CheckOutName(p!.Value) == keyApp)
            ?? throw new ArgumentException("The key isn't Word's, Excel's or PowerPoint's.", nameof(keyApp));
        if (Optional(body, "app") is { } named && named != keyApp)
            throw new ArgumentException($"A key paired for {keyApp} was sent for {named}.", nameof(body));
        return program;
    }

    private static string Name(OfficeProgram p) => OfficePrograms.CheckOutName(p);

    /// <summary>
    /// The add-in's <c>url</c> (its <c>Office.context.document.url</c>) as a local path, or null when it names none: empty,
    /// a document in the cloud, or a file on another computer. Office may give a path or a <c>file://</c> URL, whose
    /// escapes are undone here, so a copy's name with a space or a <c>#</c> in it is found either way.
    /// </summary>
    internal static string? LocalPath(string? url)
    {
        if (string.IsNullOrEmpty(url))
            return null;
        const string scheme = "file://";
        if (url.StartsWith(scheme, StringComparison.OrdinalIgnoreCase))
        {
            var rest = url[scheme.Length..];
            if (rest.StartsWith("localhost/", StringComparison.OrdinalIgnoreCase))
                rest = rest["localhost".Length..];
            if (!rest.StartsWith('/'))
                return null; // file://another-computer/…
            url = Uri.UnescapeDataString(rest);
        }
        return Path.IsPathFullyQualified(url) ? url : null;
    }

    /// <summary>
    /// The document's bytes, sent as base64: decoded straight from the request's UTF-8 where it came from the port, so a
    /// large document isn't held a third time as text.
    /// </summary>
    private static byte[] Bytes(JsonObject body, string key)
    {
        if (body[key] is JsonValue value)
        {
            if (value.TryGetValue<JsonElement>(out var element) && element.ValueKind == JsonValueKind.String)
                return element.TryGetBytesFromBase64(out var decoded) ? decoded : throw new FormatException($"{key} isn't base64.");
            if (value.TryGetValue<string>(out var text))
                return Convert.FromBase64String(text);
        }
        throw new ArgumentException($"{key} is missing or isn't text.", key);
    }

    private static bool Flag(JsonObject body, string key) => body[key] switch
    {
        null => false,
        JsonValue v when v.TryGetValue<bool>(out var b) => b,
        _ => throw new ArgumentException($"{key} isn't true or false.", key),
    };

    private static string Text(JsonObject body, string key) =>
        body[key] is JsonValue v && v.TryGetValue<string>(out var s) && s.Length > 0
            ? s
            : throw new ArgumentException($"{key} is missing or isn't text.", key);

    private static string? Optional(JsonObject body, string key) => body[key] switch
    {
        null => null,
        JsonValue v when v.TryGetValue<string>(out var s) => s.Length == 0 ? null : s,
        _ => throw new ArgumentException($"{key} isn't text.", key),
    };
}
