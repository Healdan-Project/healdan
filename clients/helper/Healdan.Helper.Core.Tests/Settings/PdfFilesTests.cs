using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Core.Tests.Settings;

public sealed class PdfFilesTests
{
    [Theory]
    [InlineData("Signed NDA.pdf", true)]
    [InlineData("SCAN.PDF", true)]
    [InlineData("Signed NDA #7 v1.0 (read-only).pdf", true)]
    [InlineData("pdf", false)] // no extension, as in the web page's Open in App
    [InlineData("a.pdf.docx", false)]
    [InlineData("a.pdfx", false)]
    [InlineData("a.", false)]
    public void APdfIsItsOwnExtension(string fileName, bool pdf) => Assert.Equal(pdf, PdfFiles.Is(fileName));
}
