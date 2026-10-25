namespace Healdan.Helper.Core.Settings;

/// <summary>Spec §5.11's kinds. In M3 every kind opens in LibreOffice; other files open read-only in the system's default app.</summary>
public static class FileKinds
{
    public static readonly FileKind Documents = new("Documents", [".doc", ".docx", ".docm", ".dotx", ".dotm", ".rtf", ".odt", ".ott"]);

    public static readonly FileKind Spreadsheets = new("Spreadsheets", [".xls", ".xlsx", ".xlsm", ".ods"]);

    public static readonly FileKind Presentations = new("Presentations", [".ppt", ".pptx", ".pptm", ".odp"]);

    public static readonly IReadOnlyList<FileKind> All = [Documents, Spreadsheets, Presentations];

    /// <summary>The kind of the file or extension, or null for a file no office app opens.</summary>
    public static FileKind? Of(string nameOrExtension)
    {
        var ext = Path.GetExtension(nameOrExtension);
        if (ext.Length == 0)
            ext = "." + nameOrExtension.TrimStart('.');
        return All.FirstOrDefault(k => k.Extensions.Contains(ext, StringComparer.OrdinalIgnoreCase));
    }
}
