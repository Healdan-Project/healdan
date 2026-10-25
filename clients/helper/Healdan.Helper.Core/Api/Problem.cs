using System.Text.Json;

namespace Healdan.Helper.Core.Api;

/// <summary>An RFC 9457 problem, as the API answers a refusal.</summary>
[Schema("Problem")]
public sealed record Problem(
    string Type,
    string Title,
    int Status,
    string Detail,
    string Code,
    List<FieldError>? Fields,
    HoldView? Hold,
    DateTimeOffset? LastSaved,
    DocumentView? Document);

/// <summary>One wrong field of a <c>422 validation</c>.</summary>
[Schema("FieldError")]
public sealed record FieldError(string Field, string Code, string Detail);
