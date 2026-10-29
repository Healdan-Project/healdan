using System.Globalization;
using System.Text;

namespace Healdan.Helper.Core.Copies;

/// <summary>The names of local copies, which are also what an app's title bar shows (spec §5.4, §5.7).</summary>
public static class CopyNames
{
    /// <summary>
    /// The longest copy file name in UTF-8 bytes. A file name holds 255, and the record's name adds 21
    /// (<c>.</c> and <c>.healdan.json</c>) while LibreOffice's lock file adds 8, so 241 leaves both room.
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

    /// <summary>
    /// Word's owner file for a copy: <c>~$</c> replaces the first two characters of a long name and the first one
    /// of a short one, so both candidates are given. Each is a name only this copy can have, as the doc # is in it.
    /// </summary>
    internal static IEnumerable<string> WordOwnerNames(string copyFileName)
    {
        var elements = TextElements(copyFileName);
        for (var drop = 1; drop <= 2 && drop < elements.Count; drop++)
            yield return "~$" + string.Concat(elements.Skip(drop));
    }

    /// <summary>
    /// Whether a file in the folder is an app's own: LibreOffice's lock file or Word's owner file. The app deletes it by
    /// the path it opened, so a copy set aside leaves it where it is (spec §5.4).
    /// </summary>
    internal static bool IsAppFile(string fileName) =>
        (fileName.StartsWith(".~lock.", StringComparison.Ordinal) && fileName.EndsWith('#'))
        || fileName.StartsWith("~$", StringComparison.Ordinal);

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
