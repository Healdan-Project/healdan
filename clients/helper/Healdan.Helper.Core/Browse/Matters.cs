using Healdan.Helper.Core.Api;

namespace Healdan.Helper.Core.Browse;

/// <summary>
/// How the windows name a matter with its client (owner, 2026-10-28): the matter, then its client's name in brackets in
/// smaller secondary text, as the web page's matter lists have it. A server older than <see cref="MatterRef.Client"/>
/// sends no client, and the matter reads as it did before.
/// </summary>
public static class Matters
{
    /// <summary>The client in brackets, <c>(Northwind Properties)</c>, or empty.</summary>
    public static string ClientNote(ClientRef? client) => client is null ? "" : $"({client.Name})";

    /// <summary>The whole name on one line, for a tooltip: <c>998.1 – Healdan Demo (Northwind Properties)</c>.</summary>
    public static string Line(MatterRef matter) => matter.Client is { } client ? $"{matter.Display} ({client.Name})" : matter.Display;

    /// <summary>The note after a matter's crumb: its client, then its Store, <c>(Northwind Properties · Healdan)</c>.</summary>
    public static string CrumbNote(MatterRef matter, StoreRef store) =>
        matter.Client is { } client ? $"({client.Name} · {store.Name})" : $"({store.Name})";
}
