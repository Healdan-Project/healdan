// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using Healdan.Helper.Core.Platform;

namespace Healdan.Helper.Core.Session;

/// <summary>The computer's usual browser, through <see cref="SystemOpener"/>, which hands the address over as one argument.</summary>
public sealed class SystemBrowser : IBrowser
{
    public void Open(Uri address) => SystemOpener.OpenWebPage(address);
}
