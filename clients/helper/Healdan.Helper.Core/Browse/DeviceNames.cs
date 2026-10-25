namespace Healdan.Helper.Core.Browse;

/// <summary>A device as people say it: the helper names its token "Healdan Helper on MacBook", and the windows say "MacBook".</summary>
public static class DeviceNames
{
    private const string HelperPrefix = "Healdan Helper on ";

    public static string Short(string? tokenName)
    {
        var name = (tokenName ?? "").Trim();
        if (name.Length == 0)
            return "another computer";
        return name.StartsWith(HelperPrefix, StringComparison.Ordinal) && name.Length > HelperPrefix.Length ? name[HelperPrefix.Length..].Trim() : name;
    }
}
