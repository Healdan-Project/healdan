// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Text.Json;
using System.Text.Json.Nodes;

namespace Healdan.Helper.Core.Api;

// Each record reads only fields its schema in openapi.yaml has; ContractTests checks it.

[Schema("Page")]
public sealed record Page<T>(List<T> Items, int Offset, int Limit, long? Total);

/// <summary>
/// What <c>GET /server</c> says. <see cref="Providers"/> and <see cref="PasswordLabel"/> are null from a server older
/// than the sign-in providers (M4): it has only the password.
/// </summary>
[Schema("ServerInfo")]
public sealed record ServerInfo(string Product, string Version, string ApiVersion, ServerLabels Labels,
    List<string> SignIn, List<ProviderInfo>? Providers = null, string? PasswordLabel = null);

/// <summary>A sign-in provider people can sign in through: its id, as in <c>/api/signin/&lt;id&gt;</c>, and its button's label.</summary>
[Schema("ProviderInfo")]
public sealed record ProviderInfo(string Id, string Label);

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

/// <summary>
/// A document. <see cref="CanOpen"/> is false when Healdan won't give the helper its file (#146: no Download permission,
/// or protected by a password); true from a server older than it, which opened as before.
/// </summary>
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
    bool CanEdit,
    bool CanOpen = true);

[Schema("SignedIn")]
public sealed record SignedIn(string Token, long Id, string Name, DateTimeOffset ExpiresAt, UserView User);

/// <summary>The <c>version</c> part of a save.</summary>
[Schema("NewVersion")]
public sealed record SaveRequest(string Kind, bool KeepCheckedOut, string Name, string IfVersion, bool IfHeld, string SaveId);

/// <summary>The <c>document</c> part of a filing.</summary>
[Schema("NewDocument")]
public sealed record NewDocument(long FolderId, string Name, JsonObject Properties, bool CheckOut);

// Email (M5).

/// <summary>An attachment or an inline part of an email, as <c>inspectEmail</c> numbers them.</summary>
[Schema("EmailPart")]
public sealed record EmailPart(int Index, string Name, string Type, long Size, bool Inline, bool Message);

/// <summary>
/// What <c>inspectEmail</c> found in an email. Every field the message lacks, or has damaged, is null.
/// <see cref="MessageId"/> has no angle brackets. <see cref="To"/> and <see cref="Cc"/> are the recipients, joined with
/// ", ". <see cref="Copies"/> are the documents the user may read.
/// </summary>
[Schema("EmailInspection")]
public sealed record EmailInspection(
    string? MessageId,
    string? Subject,
    string? From,
    DateTimeOffset? Sent,
    List<EmailPart> Attachments,
    List<DocumentView> Copies,
    string? To = null,
    string? Cc = null,
    DateTimeOffset? Received = null);

/// <summary>The <c>email</c> part of a filing: the folder, the properties, and the attachments to file by <see cref="EmailPart.Index"/>.</summary>
[Schema("NewEmail")]
public sealed record NewEmail(long FolderId, JsonObject Properties, List<int> Attachments);

/// <summary>What <c>fileEmail</c> filed: the email, its attachments, and the ticked emails the matter already held.</summary>
[Schema("FiledEmail")]
public sealed record FiledEmail(DocumentView Email, List<DocumentView> Attachments, List<FiledEmail.SkippedPart> Skipped)
{
    /// <summary>An attachment ticked but not filed, because it's an email the matter already holds (<see cref="Document"/>).</summary>
    public sealed record SkippedPart(int Index, string Code, DocumentView Document);
}

/// <summary>The copies of one message, with <see cref="MessageId"/> as the server normalised it (without angle brackets).</summary>
[Schema("EmailCopy")]
public sealed record EmailCopy(string MessageId, List<EmailCopy.Copy> Copies)
{
    /// <summary>A copy, and <see cref="Address"/>, where the web page shows it.</summary>
    public sealed record Copy(DocumentView Document, string Address);
}

[Schema("EmailCopies")]
public sealed record EmailCopies(List<EmailCopy> Items);

[Schema("PasswordSignIn")]
internal sealed record SignInBody(string Username, string Password, string Name, DeviceRef Device);

/// <summary>The helper's one-time code from a sign-in through a provider, with the proof key that goes with it (RFC 7636).</summary>
[Schema("CodeSignIn")]
internal sealed record CodeSignInBody(string Code, string CodeVerifier, string RedirectUri, string Name, DeviceRef Device);

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
