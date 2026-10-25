using System.Text.Json.Nodes;

namespace Healdan.Helper.Core.Api;

// Each record reads only fields its schema in openapi.yaml has; ContractTests checks it.

[Schema("Page")]
public sealed record Page<T>(List<T> Items, int Offset, int Limit, long? Total);

[Schema("ServerInfo")]
public sealed record ServerInfo(string Product, string Version, string ApiVersion, ServerLabels Labels, List<string> SignIn);

/// <summary>What the server calls a matter, for display only.</summary>
public sealed record ServerLabels(string Matter, string Matters);

[Schema("UserView")]
public sealed record UserView(long Id, string Username, string Name, string? Email, string? Language, bool Admin);

[Schema("UserRef")]
public sealed record UserRef(long Id, string? Name);

[Schema("FolderRef")]
public sealed record FolderRef(long Id, string Name, string Kind);

[Schema("MatterRef")]
public sealed record MatterRef(long Id, string Display);

[Schema("DeviceView")]
public sealed record DeviceView(string Id, string Name, string? App);

[Schema("HoldView")]
public sealed record HoldView(string Kind, UserRef? By, DateTimeOffset? Since, DeviceView? Device);

[Schema("DocumentView")]
public sealed record DocumentView(
    string Type,
    long DocNumber,
    string Name,
    long Size,
    string Version,
    string FileVersion,
    string Reference,
    FolderRef Folder,
    MatterRef? Matter,
    DateTimeOffset DateFiled,
    DateTimeOffset LastModified,
    HoldView? Hold,
    bool CanEdit);

[Schema("SignedIn")]
public sealed record SignedIn(string Token, long Id, string Name, DateTimeOffset ExpiresAt, UserView User);

/// <summary>The <c>version</c> part of a save.</summary>
[Schema("NewVersion")]
public sealed record SaveRequest(string Kind, bool KeepCheckedOut, string Name, string IfVersion, bool IfHeld, string SaveId);

/// <summary>The <c>document</c> part of a filing.</summary>
[Schema("NewDocument")]
public sealed record NewDocument(long FolderId, string Name, JsonObject Properties, bool CheckOut);

[Schema("SignIn")]
internal sealed record SignInBody(string Username, string Password, string Name, DeviceRef Device);

[Schema("DeviceRef")]
internal sealed record DeviceRef(string Id);

[Schema("CheckoutRequest")]
internal sealed record CheckoutBody(bool Move, string App);

[Schema("PropertyPatch")]
internal sealed record PropertyPatchBody(System.Text.Json.JsonElement Values);
