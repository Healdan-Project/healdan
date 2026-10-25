using System.Text.Json;

namespace Healdan.Helper.Core.Api;

/// <summary>The server refused or failed a call. <see cref="Code"/> is the problem's stable code, which callers switch on.</summary>
public sealed class ApiException(int status, string code, string detail, JsonElement body) : Exception(detail)
{
    public int Status { get; } = status;

    public string Code { get; } = code;

    public string Detail { get; } = detail;

    /// <summary>The whole problem, for members such as <c>hold</c>, <c>lastSaved</c> and <c>document</c>.</summary>
    public JsonElement Body { get; } = body;
}
