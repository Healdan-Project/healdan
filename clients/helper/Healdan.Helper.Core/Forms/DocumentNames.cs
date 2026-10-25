namespace Healdan.Helper.Core.Forms;

/// <summary>The name a new document is filed under, checked as the API checks it, so Save says why before it's pressed.</summary>
public static class DocumentNames
{
    /// <summary>The API's limit, with the extension.</summary>
    public const int MaxLength = 255;

    /// <summary>Why <paramref name="name"/> can't be filed with the <paramref name="format"/>'s extension, or null when it can.</summary>
    public static string? Problem(string? name, string? format)
    {
        if (string.IsNullOrWhiteSpace(name))
            return "Enter a name.";
        if (name.Any(c => c is '/' or '\\' || char.IsControl(c)))
            return "A name can't contain / or \\.";
        var length = name.Trim().Length + (string.IsNullOrEmpty(format) ? 0 : format.TrimStart('.').Length + 1);
        return length > MaxLength ? $"The name is too long: at most {MaxLength} characters with its extension." : null;
    }

    /// <summary>The name without an extension that's one of <paramref name="formats"/>, which the format choice adds.</summary>
    public static string WithoutExtension(string name, IEnumerable<string> formats)
    {
        foreach (var format in formats)
            if (name.Length > format.Length + 1 && name.EndsWith("." + format, StringComparison.OrdinalIgnoreCase))
                return name[..^(format.Length + 1)];
        return name;
    }
}
