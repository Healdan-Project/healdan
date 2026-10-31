using System.Globalization;

namespace Healdan.Helper.Core.Office;

/// <summary>The Microsoft Office programs Healdan Helper drives (M3.5 spec §5.1).</summary>
public enum OfficeProgram
{
    Word,
    Excel,
    PowerPoint,
}

/// <summary>What the helper knows about each <see cref="OfficeProgram"/>: its files, its process, its owner files and how it saves.</summary>
public static class OfficePrograms
{
    private static readonly Dictionary<string, OfficeProgram> ByExtension = new(StringComparer.OrdinalIgnoreCase)
    {
        [".doc"] = OfficeProgram.Word,
        [".docx"] = OfficeProgram.Word,
        [".docm"] = OfficeProgram.Word,
        [".dotx"] = OfficeProgram.Word,
        [".dotm"] = OfficeProgram.Word,
        [".rtf"] = OfficeProgram.Word,
        [".xls"] = OfficeProgram.Excel,
        [".xlsx"] = OfficeProgram.Excel,
        [".xlsm"] = OfficeProgram.Excel,
        [".ppt"] = OfficeProgram.PowerPoint,
        [".pptx"] = OfficeProgram.PowerPoint,
        [".pptm"] = OfficeProgram.PowerPoint,
    };

    /// <summary>The program that opens a file, by its extension; null when none of the three does.</summary>
    public static OfficeProgram? For(string fileName) =>
        ByExtension.TryGetValue(Path.GetExtension(fileName), out var program) ? program : null;

    /// <summary>The program's process, which is also the name <c>open -a</c> and AppleScript's <c>tell</c> know it by.</summary>
    public static string ProcessName(OfficeProgram p) => p switch
    {
        OfficeProgram.Word => "Microsoft Word",
        OfficeProgram.Excel => "Microsoft Excel",
        OfficeProgram.PowerPoint => "Microsoft PowerPoint",
        _ => throw new ArgumentOutOfRangeException(nameof(p)),
    };

    /// <summary>Where the program is installed on macOS.</summary>
    public static string BundlePath(OfficeProgram p) => $"/Applications/{ProcessName(p)}.app";

    /// <summary>The name a check-out gives the program: "#1234 is checked out to you on MacBook (Word)".</summary>
    public static string CheckOutName(OfficeProgram p) => p switch
    {
        OfficeProgram.Word => "Word",
        OfficeProgram.Excel => "Excel",
        OfficeProgram.PowerPoint => "PowerPoint",
        _ => throw new ArgumentOutOfRangeException(nameof(p)),
    };

    /// <summary>The format Healdan saves a new document from the program in, with its dot.</summary>
    public static string OwnFormat(OfficeProgram p) => p switch
    {
        OfficeProgram.Word => ".docx",
        OfficeProgram.Excel => ".xlsx",
        OfficeProgram.PowerPoint => ".pptx",
        _ => throw new ArgumentOutOfRangeException(nameof(p)),
    };

    /// <summary>
    /// Whether the copy takes the new name after each save. PowerPoint doesn't: after it saves a presentation under
    /// another name through AppleScript, its Save opens Save As instead of saving, while one it opened itself saves in
    /// place (spec §5.4, §8.5).
    /// </summary>
    public static bool RenamesAfterSave(OfficeProgram p) => p != OfficeProgram.PowerPoint;

    /// <summary>
    /// The owner files the program may keep beside a copy it has open. Excel and PowerPoint name theirs <c>~$</c> and the
    /// whole name. Word replaces the name's first two characters with <c>~$</c> for a long name and prefixes a short one, so
    /// both are given. The lock check is true when any of them exists.
    /// </summary>
    public static IEnumerable<string> OwnerNames(OfficeProgram p, string fileName)
    {
        if (p == OfficeProgram.Word)
        {
            var elements = new List<string>();
            var enumerator = StringInfo.GetTextElementEnumerator(fileName);
            while (enumerator.MoveNext())
                elements.Add((string)enumerator.Current);
            if (elements.Count > 2)
                yield return "~$" + string.Concat(elements.Skip(2));
        }
        yield return "~$" + fileName;
    }
}
