namespace Healdan.Helper.Core.Forms;

/// <summary>The formats a new document can be saved in (spec §5.6), by the names people know them by.</summary>
public static class FileFormats
{
    private static readonly Dictionary<string, string> Labels = new(StringComparer.OrdinalIgnoreCase)
    {
        ["odt"] = "OpenDocument Text",
        ["docx"] = "Word Document",
        ["ods"] = "OpenDocument Spreadsheet",
        ["xlsx"] = "Excel Workbook",
        ["odp"] = "OpenDocument Presentation",
        ["pptx"] = "PowerPoint Presentation",
    };

    /// <summary>Such as "OpenDocument Text (.odt)"; just ".rtf" for a format without a name here.</summary>
    public static string Label(string format)
    {
        var ext = "." + format.TrimStart('.').ToLowerInvariant();
        return Labels.TryGetValue(ext[1..], out var name) ? $"{name} ({ext})" : ext;
    }
}
