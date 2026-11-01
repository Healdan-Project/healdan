using System.Globalization;
using System.Text;
using Healdan.Helper.Core.Office;

namespace Healdan.Helper.Core.Copies;

/// <summary>The names of local copies, which are also what an app's title bar shows (spec §5.4, §5.7).</summary>
public static class CopyNames
{
    /// <summary>
    /// The longest copy file name in UTF-8 bytes. A file name holds 255, and the record's name adds 14 (<c>.</c> and
    /// <c>.healdan.json</c>), LibreOffice's lock file 8 and CoffeePDF's 13, so 241 leaves each room.
    /// </summary>
    public const int MaxBytes = 241;

    private const int MaxExtensionLength = 20;

    /// <summary><c>Lease agreement #1234 v2.1.docx</c>, or <c>… v2.1 (read-only).docx</c>.</summary>
    public static string FileName(string documentName, long docNumber, string fileVersion, bool readOnly)
    {
        var (stem, ext) = Split(Clean(documentName));
        var tail = $" {DocRef(docNumber, Clean(fileVersion))}{(readOnly ? " (read-only)" : "")}{ext}";
        var budget = MaxBytes - Encoding.UTF8.GetByteCount(tail);
        return Cut(stem, budget) + tail;
    }

    /// <summary>The title an app shows: the file name without its extension.</summary>
    public static string Title(string documentName, long docNumber, string fileVersion, bool readOnly)
    {
        var name = FileName(documentName, docNumber, fileVersion, readOnly);
        var ext = Split(Clean(documentName)).Ext;
        return name[..^ext.Length];
    }

    /// <summary><c>#1234 v2.1</c>, the part of a name that finds the document.</summary>
    public static string DocRef(long docNumber, string fileVersion) => $"#{docNumber} v{fileVersion}";

    /// <summary>The hidden record beside a copy: <c>.&lt;copy file name&gt;.healdan.json</c>.</summary>
    internal static string RecordName(string copyFileName) => $".{copyFileName}.healdan.json";

    /// <summary>LibreOffice's lock file for a copy.</summary>
    internal static string LibreOfficeLockName(string copyFileName) => $".~lock.{copyFileName}#";

    /// <summary>CoffeePDF's lock file for a copy, which it keeps while the copy's window is open (CoffeePDF spec §6.8).</summary>
    internal static string CoffeePdfLockName(string copyFileName) => $".~coffeepdf.{copyFileName}#";

    /// <summary>
    /// Every lock or owner file an app may keep beside a copy (M3.6 spec §4.5): LibreOffice's and CoffeePDF's lock files,
    /// and each owner file Word, Excel or PowerPoint may keep (<see cref="OfficeOwnerNames"/>). A copy deleted, renamed or
    /// set aside looks at these, and a new app adds its own here.
    /// </summary>
    internal static IEnumerable<string> AppFiles(string copyFileName) =>
        [LibreOfficeLockName(copyFileName), CoffeePdfLockName(copyFileName), .. OfficeOwnerNames(copyFileName)];

    private const string CoffeePdfTemporaryPrefix = ".coffeepdf-";

    private const string CoffeePdfTemporarySuffix = ".tmp";

    /// <summary>
    /// CoffeePDF's temporary file for a save (its <c>AtomicFile</c>), written beside the copy and renamed over it:
    /// <c>.coffeepdf-</c>, 32 lowercase hex digits and <c>.tmp</c>.
    /// </summary>
    internal static bool IsCoffeePdfTemporary(string fileName) =>
        fileName.Length == CoffeePdfTemporaryPrefix.Length + 32 + CoffeePdfTemporarySuffix.Length
        && fileName.StartsWith(CoffeePdfTemporaryPrefix, StringComparison.Ordinal)
        && fileName.EndsWith(CoffeePdfTemporarySuffix, StringComparison.Ordinal)
        && fileName.AsSpan(CoffeePdfTemporaryPrefix.Length, 32).IndexOfAnyExcept("0123456789abcdef") < 0;

    /// <summary>
    /// Every owner file Word, Excel or PowerPoint may keep for a copy, each once. Word's <c>~$</c> replaces the first two
    /// characters of a long name and the first one of a short one, so both candidates are given, as M3 did; Excel and
    /// PowerPoint put <c>~$</c> before the whole name (<see cref="OfficePrograms.OwnerNames"/>). Each is a name only this
    /// copy can have, as the doc # is in it.
    /// </summary>
    internal static IEnumerable<string> OfficeOwnerNames(string copyFileName)
    {
        var names = new List<string>();
        var elements = TextElements(copyFileName);
        for (var drop = 1; drop <= 2 && drop < elements.Count; drop++)
            names.Add("~$" + string.Concat(elements.Skip(drop)));
        foreach (var program in Enum.GetValues<OfficeProgram>())
            names.AddRange(OfficePrograms.OwnerNames(program, copyFileName));
        return names.Distinct(StringComparer.Ordinal);
    }

    /// <summary>
    /// Whether a file in the folder is an app's own: LibreOffice's or CoffeePDF's lock file, an owner file of Word, Excel or
    /// PowerPoint, or CoffeePDF's temporary file for a save. The app deletes it by the path it opened, so a copy set aside
    /// leaves it where it is (spec §5.4, M3.6 spec §4.5).
    /// </summary>
    internal static bool IsAppFile(string fileName) =>
        (fileName.StartsWith(".~lock.", StringComparison.Ordinal) && fileName.EndsWith('#'))
        || (fileName.StartsWith(".~coffeepdf.", StringComparison.Ordinal) && fileName.EndsWith('#'))
        || fileName.StartsWith("~$", StringComparison.Ordinal)
        || IsCoffeePdfTemporary(fileName);

    private static string Clean(string s)
    {
        var sb = new StringBuilder(s.Length);
        foreach (var c in s)
            sb.Append(c is '/' or ':' or '\\' || char.IsControl(c) ? '-' : c);
        return sb.ToString();
    }

    private static (string Stem, string Ext) Split(string name)
    {
        var dot = name.LastIndexOf('.');
        if (dot < 0 || name.Length - dot <= 1 || name.Length - dot > MaxExtensionLength || name[dot..].Contains(' '))
            return (name, "");
        return (name[..dot], name[dot..]);
    }

    private static List<string> TextElements(string s)
    {
        var list = new List<string>();
        var e = StringInfo.GetTextElementEnumerator(s);
        while (e.MoveNext())
            list.Add((string)e.Current);
        return list;
    }

    /// <summary>The longest start of <paramref name="stem"/> within the budget, cut between text elements.</summary>
    private static string Cut(string stem, int budget)
    {
        var sb = new StringBuilder();
        var used = 0;
        foreach (var element in TextElements(stem))
        {
            used += Encoding.UTF8.GetByteCount(element);
            if (used > budget)
                break;
            sb.Append(element);
        }

        // Names starting or ending in a dot or space confuse some apps (a leading dot also hides the file).
        var cut = sb.ToString().Trim(' ', '.');
        return cut.Length == 0 ? "Document" : cut;
    }
}
