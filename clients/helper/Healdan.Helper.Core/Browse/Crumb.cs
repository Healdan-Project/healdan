namespace Healdan.Helper.Core.Browse;

/// <summary>One step of the breadcrumb, from the matter down: <c>998.1 – Healdan Demo (Healdan)</c>, then each folder.</summary>
public sealed record Crumb(long FolderId, string Text);
