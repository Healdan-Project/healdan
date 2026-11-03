// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

namespace Healdan.Helper.Core.Ui;

/// <summary>After a Crash's words (polish spec §6.8): its first sentence as the headline, the rest as the body.</summary>
public sealed record CrashedWarning(string Headline, string Body)
{
    /// <summary>The whole sentence, as the crash notification says it.</summary>
    public override string ToString() => $"{Headline} {Body}";
}
