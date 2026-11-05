// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Healdan.Helper.Core.Api;

/// <summary>The numbers the helper reads from JSON: the add-in's, the extension's and its own windows' answers.</summary>
public static class JsonNumbers
{
    /// <summary>
    /// A whole JSON number's value; null for anything else (nothing, text, a fraction, one too large for a long). Any JSON
    /// number counts: a value made in code may hold an int, which <c>TryGetValue&lt;long&gt;</c> doesn't answer. The
    /// caller says which values it takes, such as a folder's id above 0.
    /// </summary>
    public static long? Whole(JsonNode? node) =>
        node is JsonValue n && n.GetValueKind() == JsonValueKind.Number
        && long.TryParse(n.ToJsonString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var whole)
            ? whole : null;
}
