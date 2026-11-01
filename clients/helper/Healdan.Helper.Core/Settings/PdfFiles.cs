namespace Healdan.Helper.Core.Settings;

/// <summary>
/// PDFs (M3.6 spec §4.3), which CoffeePDF opens: not a <see cref="FileKind"/>, as kinds drive Settings' office choices, the
/// files' icons and LibreOffice as the fallback, and none of those take PDFs in.
/// </summary>
public static class PdfFiles
{
    /// <summary>Whether the file is a PDF, by its own extension in any case. A name with no extension, such as <c>pdf</c>, isn't.</summary>
    public static bool Is(string fileName) => Path.GetExtension(fileName).Equals(".pdf", StringComparison.OrdinalIgnoreCase);
}
