using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Core.Browse;

/// <summary>A document's icon by its file's kind (polish spec §4.4), as Tabler names it.</summary>
public static class FileIcons
{
    private static readonly HashSet<string> Images = new([".png", ".jpg", ".jpeg", ".gif", ".tif", ".tiff", ".heic", ".webp", ".bmp"],
        StringComparer.OrdinalIgnoreCase);

    public static string For(string name)
    {
        if (FileKinds.OfFile(name) is { } kind)
            return kind == FileKinds.Spreadsheets ? "file-spreadsheet" : kind == FileKinds.Presentations ? "presentation" : "file-text";
        var ext = Path.GetExtension(name);
        return ext.Equals(".pdf", StringComparison.OrdinalIgnoreCase) ? "file-type-pdf" : Images.Contains(ext) ? "photo" : "file";
    }
}
