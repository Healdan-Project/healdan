// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

namespace Healdan.Helper.Core.Session;

/// <summary>The person's browser, where a provider's sign-in happens. A seam so tests can play the person.</summary>
public interface IBrowser
{
    /// <summary>Opens <paramref name="address"/> in the system browser.</summary>
    /// <exception cref="IOException">Nothing on the computer opens it.</exception>
    void Open(Uri address);
}
