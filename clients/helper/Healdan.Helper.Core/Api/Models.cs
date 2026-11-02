using System.Text.Json;
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

/// <summary>A matter named in a document or folder. <see cref="Client"/> is null from a server older than it.</summary>
[Schema("MatterRef")]
public sealed record MatterRef(long Id, string Display, ClientRef? Client = null);

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

[Schema("PasswordSignIn")]
internal sealed record SignInBody(string Username, string Password, string Name, DeviceRef Device);

[Schema("DeviceRef")]
internal sealed record DeviceRef(string Id);

[Schema("CheckoutRequest")]
internal sealed record CheckoutBody(bool Move, string App);

[Schema("PropertyPatch")]
internal sealed record PropertyPatchBody(JsonElement Values);

// The windows' (Task 17).

[Schema("StoreRef")]
public sealed record StoreRef(long Id, string Name);

[Schema("ClientRef")]
public sealed record ClientRef(long Id, long Number, string Name);

[Schema("FolderView")]
public sealed record FolderView(string Type, long Id, string Name, string Kind, MatterRef? Matter, StoreRef Store, List<FolderRef> Path, bool CanFile);

[Schema("MatterView")]
public sealed record MatterView(
    string Type,
    long Id,
    long Number,
    string Display,
    string Name,
    string? Description,
    ClientRef Client,
    StoreRef Store,
    long FolderId,
    DateTimeOffset? LastChange);

/// <summary>A favourite: a folder or a document (<see cref="Type"/>), with the bookmark's id for removing it.</summary>
[Schema("FavoriteView")]
public sealed record FavoriteView(long Id, string Type, JsonElement Item)
{
    public FolderView? Folder() => Type == "folder" ? Item.Deserialize<FolderView>(HealdanApi.Json) : null;

    public DocumentView? Document() => Type == "document" ? Item.Deserialize<DocumentView>(HealdanApi.Json) : null;
}

[Schema("VersionView")]
public sealed record VersionView(string Version, string FileVersion, string Kind, DateTimeOffset? Date, UserRef? By, string? Comment, string? Event);

[Schema("LookupView")]
public sealed record LookupView(long DocNumber, string Version);

[Schema("UserHit")]
public sealed record UserHit(long Id, string Username, string Name);

/// <summary>One property of a document or of the filing form; <see cref="Value"/> is as the API sends it.</summary>
[Schema("ProfileField")]
public sealed record ProfileField(
    string Name,
    string Label,
    string Type,
    bool Required,
    bool Editable,
    JsonElement Value,
    string? Source,
    FolderRef? InheritedFrom,
    List<Option>? Options);

/// <summary>A value to pick; a Subclass's names the Class it belongs to.</summary>
[Schema("Option")]
public sealed record Option(string Value, string Label, string? Class);

[Schema("NewFavorite")]
internal sealed record NewFavoriteBody(long? FolderId, long? DocNumber);

[Schema("NewFolder")]
internal sealed record NewFolderBody(long ParentId, string Name);
