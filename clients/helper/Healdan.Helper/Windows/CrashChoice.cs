// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

namespace Healdan.Helper.Windows;

/// <summary>After a Crash's choices; <see cref="Later"/> when the window was just closed.</summary>
internal enum CrashChoice
{
    Later,
    Reopen,
    CheckIn
}
