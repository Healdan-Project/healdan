namespace Healdan.Helper.Core.Settings;

/// <summary>Spec §5.11's kinds. Each opens in LibreOffice or Microsoft Office (<see cref="AppChooser"/>); other files open read-only in the system's default app.</summary>
public static class FileKinds
{
    public static readonly FileKind Documents = new("Documents", [".doc", ".docx", ".docm", ".dotx", ".dotm", ".rtf", ".odt", ".ott"], "docx");

    public static readonly FileKind Spreadsheets = new("Spreadsheets", [".xls", ".xlsx", ".xlsm", ".ods"], "xlsx");

    public static readonly FileKind Presentations = new("Presentations", [".ppt", ".pptx", ".pptm", ".odp"], "pptx");

    /// <summary>Draw's own format only: Draw opens Visio and Publisher files but can't save them.</summary>
    public static readonly FileKind Drawings = new("Drawings", [".odg"]);

    public static readonly IReadOnlyList<FileKind> All = [Documents, Spreadsheets, Presentations, Drawings];

    /// <summary>
    /// The kind of a file by its own extension, or null. Links and the cycle go by this: a name with no extension, such
    /// as <c>docx</c>, has no kind, as in the web page's Open in App.
    /// </summary>
    public static FileKind? OfFile(string fileName) =>
        Path.GetExtension(fileName) is { Length: > 1 } ext
            ? All.FirstOrDefault(k => k.Extensions.Contains(ext, StringComparer.OrdinalIgnoreCase))
            : null;

    /// <summary>The kind of the file or extension, or null for a file no office app opens.</summary>
    public static FileKind? Of(string nameOrExtension)
    {
        var ext = Path.GetExtension(nameOrExtension);
        if (ext.Length == 0)
            ext = "." + nameOrExtension.TrimStart('.');
        return All.FirstOrDefault(k => k.Extensions.Contains(ext, StringComparer.OrdinalIgnoreCase));
    }
}
