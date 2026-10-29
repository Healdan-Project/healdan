namespace Healdan.Helper.Core.Browse;

/// <summary>
/// One step of the breadcrumb, from the matter down: <c>998.1 – Healdan Demo</c>, with its client and Store as the
/// <see cref="Note"/>, <c>(Northwind Properties · Healdan)</c>, then each folder.
/// </summary>
/// <param name="Note">The smaller text after the step, or empty.</param>
public sealed record Crumb(long FolderId, string Text, string Note = "");
