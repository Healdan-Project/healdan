using System.IO.Compression;
using System.Text.RegularExpressions;
using Healdan.Helper.Core.Platform;

namespace Healdan.Helper.Core.Tests.Platform;

/// <summary>
/// The LibreOffice extension's versions (spec §5.12): the one the helper carries in its <c>.oxt</c>, the one
/// <c>unopkg list</c> says is installed, and whether the installed one needs updating.
/// </summary>
public sealed class ExtensionVersionTests : IDisposable
{
    /// <summary>
    /// <c>unopkg list</c> on the owner's Mac (macOS 27, LibreOffice 26.8.1, 2026-10-25), with the extension installed
    /// in a scratch profile by LibreOffice's own Extension Manager. Only trailing spaces are dropped.
    /// </summary>
    private const string Registered = """
        All deployed user extensions:

        Identifier: org.healdan.libreoffice
          Version: 0.1.0
          URL: vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu30093en89t7.tmp_/healdan.oxt
          is registered: yes
          Media-Type: application/vnd.sun.star.package-bundle
          Description: Open from and save to Healdan in Writer, Calc and Impress, through Healdan Helper. The title bar shows each document's doc # and version, and Insert Doc ID adds a footer that keeps up with them.

          bundled Packages: {
              URL: vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu30093en89t7.tmp_/healdan.oxt/healdan.py
              is registered: yes
              Media-Type: application/vnd.sun.star.uno-component;type=Python
              Description:

              URL: vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu30093en89t7.tmp_/healdan.oxt/Addons.xcu
              is registered: yes
              Media-Type: application/vnd.sun.star.configuration-data
              Description:

              URL: vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu30093en89t7.tmp_/healdan.oxt/ProtocolHandler.xcu
              is registered: yes
              Media-Type: application/vnd.sun.star.configuration-data
              Description:

              URL: vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu30093en89t7.tmp_/healdan.oxt/Jobs.xcu
              is registered: yes
              Media-Type: application/vnd.sun.star.configuration-data
              Description:

              URL: vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu30093en89t7.tmp_/healdan.oxt/WriterWindowState.xcu
              is registered: yes
              Media-Type: application/vnd.sun.star.configuration-data
              Description:

              URL: vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu30093en89t7.tmp_/healdan.oxt/CalcWindowState.xcu
              is registered: yes
              Media-Type: application/vnd.sun.star.configuration-data
              Description:

              URL: vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu30093en89t7.tmp_/healdan.oxt/ImpressWindowState.xcu
              is registered: yes
              Media-Type: application/vnd.sun.star.configuration-data
              Description:

          }

        """;

    /// <summary>
    /// The same Mac after <c>unopkg add</c> failed (the trial's "couldn't connect to pipe", even with
    /// <c>TMPDIR=/tmp</c>): it leaves the extension listed but unregistered, so LibreOffice doesn't load it. Cut after
    /// the first bundled package; the rest are as above, each "is registered: no".
    /// </summary>
    private const string AddFailed = """
        All deployed user extensions:

        Identifier: org.healdan.libreoffice
          Version: 0.1.0
          URL: vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu29566en4m3t.tmp_/healdan.oxt
          is registered: no
          Media-Type: application/vnd.sun.star.package-bundle
          Description: Open from and save to Healdan in Writer, Calc and Impress, through Healdan Helper. The title bar shows each document's doc # and version, and Insert Doc ID adds a footer that keeps up with them.

          bundled Packages: {
              URL: vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu29566en4m3t.tmp_/healdan.oxt/healdan.py
              is registered: no
              Media-Type: application/vnd.sun.star.uno-component;type=Python
              Description:

          }

        """;

    /// <summary>The same Mac's own profile, with no extension installed.</summary>
    private const string None = """
        All deployed user extensions:

        <none>

        """;

    private readonly string _dir = Directory.CreateTempSubdirectory("healdan-extension-").FullName;

    public void Dispose() => Directory.Delete(_dir, true);

    [Fact]
    public void OlderInstalledNeedsUpdate()
    {
        Assert.True(ExtensionVersion.NeedsUpdate("0.1.0", "0.2.0"));
        Assert.True(ExtensionVersion.NeedsUpdate("0.9.0", "0.10.0")); // numbers, not text
        Assert.True(ExtensionVersion.NeedsUpdate("0.1", "0.1.1"));
        Assert.True(ExtensionVersion.NeedsUpdate("1.9.9", "2.0.0"));
        Assert.True(ExtensionVersion.NeedsUpdate(null, "0.1.0")); // nothing installed
    }

    [Fact]
    public void TheSameOrANewerInstalledIsLeftAlone()
    {
        Assert.False(ExtensionVersion.NeedsUpdate("0.2.0", "0.2.0"));
        Assert.False(ExtensionVersion.NeedsUpdate("0.2", "0.2.0")); // a missing part is 0
        Assert.False(ExtensionVersion.NeedsUpdate("0.10.0", "0.9.0"));
        Assert.False(ExtensionVersion.NeedsUpdate("0.3.0", "0.2.0")); // never a downgrade
        Assert.False(ExtensionVersion.NeedsUpdate("0.02.0", "0.2.0")); // leading zeros, as LibreOffice compares
    }

    [Fact]
    public void ParsesUnopkgList()
    {
        var installed = ExtensionVersion.FromUnopkgList(Registered);

        Assert.NotNull(installed);
        Assert.Equal("0.1.0", installed.Version);
        Assert.True(installed.Registered);
        Assert.Equal("0.1.0", installed.UsableVersion);
        Assert.False(ExtensionVersion.NeedsUpdate(installed.UsableVersion, "0.1.0"));
        Assert.True(ExtensionVersion.NeedsUpdate(installed.UsableVersion, "0.1.1"));
    }

    [Fact]
    public void AnUnregisteredExtensionCountsAsMissing()
    {
        var installed = ExtensionVersion.FromUnopkgList(AddFailed);

        Assert.NotNull(installed);
        Assert.Equal("0.1.0", installed.Version);
        Assert.False(installed.Registered);
        Assert.Null(installed.UsableVersion);
        Assert.True(ExtensionVersion.NeedsUpdate(installed.UsableVersion, "0.1.0"));
    }

    [Fact]
    public void NothingInstalledIsNull()
    {
        Assert.Null(ExtensionVersion.FromUnopkgList(None));
        Assert.Null(ExtensionVersion.FromUnopkgList(""));
    }

    [Fact]
    public void FindsHealdansAmongOtherExtensions()
    {
        // Another extension first, in the same layout, and Windows' line ends: only Healdan's own lines count.
        var other = """
            All deployed user extensions:

            Identifier: org.example.grammar
              Version: 9.9.9
              URL: vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu1.tmp_/grammar.oxt
              is registered: yes
              Media-Type: application/vnd.sun.star.package-bundle
              Description: Another extension.

            """;
        var both = (other + Registered[Registered.IndexOf("Identifier:", StringComparison.Ordinal)..]).ReplaceLineEndings("\r\n");

        var installed = ExtensionVersion.FromUnopkgList(both);

        Assert.NotNull(installed);
        Assert.Equal("0.1.0", installed.Version);
        Assert.True(installed.Registered);
    }

    [Fact]
    public void ReadsTheBundledOxtsVersion()
    {
        var oxt = Oxt("healdan.oxt", """
            <?xml version="1.0" encoding="UTF-8"?>
            <description xmlns="http://openoffice.org/extensions/description/2006">
              <identifier value="org.healdan.libreoffice"/>
              <version value="2.3.4"/>
            </description>
            """);

        Assert.Equal("2.3.4", ExtensionVersion.OfOxt(oxt));
    }

    [Fact]
    public void ReadsTheRealExtensionsVersion()
    {
        // The extension's own description.xml, zipped as build.sh zips it: the helper reads what Task 18 writes.
        var description = File.ReadAllText(RepoFile("clients", "libreoffice", "healdan", "description.xml"));
        var expected = Regex.Match(description, "<version value=\"([^\"]+)\"").Groups[1].Value;

        Assert.Equal(expected, ExtensionVersion.OfOxt(Oxt("real.oxt", description)));
    }

    [Fact]
    public void AnOxtWithoutAVersionIsRefused()
    {
        Assert.Throws<InvalidDataException>(() => ExtensionVersion.OfOxt(Oxt("no-version.oxt", """
            <description xmlns="http://openoffice.org/extensions/description/2006">
              <identifier value="org.healdan.libreoffice"/>
            </description>
            """)));
        Assert.Throws<InvalidDataException>(() => ExtensionVersion.OfOxt(Oxt("other.oxt", """
            <description xmlns="http://openoffice.org/extensions/description/2006">
              <identifier value="org.example.other"/>
              <version value="1.0.0"/>
            </description>
            """)));
        var empty = Path.Combine(_dir, "empty.oxt");
        using (ZipFile.Open(empty, ZipArchiveMode.Create))
        {
        }
        Assert.Throws<InvalidDataException>(() => ExtensionVersion.OfOxt(empty));
    }

    /// <summary>
    /// LibreOffice's user registry on the owner's Mac (LibreOffice 26.8.1, 2026-10-25): the bundle backend's
    /// <c>backenddb.xml</c> after the Extension Manager installed the extension in a scratch profile, as written.
    /// </summary>
    private const string RegistryInstalled = """
        <?xml version="1.0"?>
        <ext:extension-backend-db xmlns:ext="http://openoffice.org/extensionmanager/extension-registry/2010"><ext:extension url="vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu38484eovjwz.tmp_/healdan-0.1.0.oxt"><ext:extension-items><ext:item><ext:url>vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu38484eovjwz.tmp_/healdan-0.1.0.oxt/healdan.py</ext:url><ext:media-type>application/vnd.sun.star.uno-component;type=Python</ext:media-type></ext:item><ext:item><ext:url>vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu38484eovjwz.tmp_/healdan-0.1.0.oxt/Addons.xcu</ext:url><ext:media-type>application/vnd.sun.star.configuration-data</ext:media-type></ext:item><ext:item><ext:url>vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu38484eovjwz.tmp_/healdan-0.1.0.oxt/ProtocolHandler.xcu</ext:url><ext:media-type>application/vnd.sun.star.configuration-data</ext:media-type></ext:item><ext:item><ext:url>vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu38484eovjwz.tmp_/healdan-0.1.0.oxt/Jobs.xcu</ext:url><ext:media-type>application/vnd.sun.star.configuration-data</ext:media-type></ext:item><ext:item><ext:url>vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu38484eovjwz.tmp_/healdan-0.1.0.oxt/WriterWindowState.xcu</ext:url><ext:media-type>application/vnd.sun.star.configuration-data</ext:media-type></ext:item><ext:item><ext:url>vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu38484eovjwz.tmp_/healdan-0.1.0.oxt/CalcWindowState.xcu</ext:url><ext:media-type>application/vnd.sun.star.configuration-data</ext:media-type></ext:item><ext:item><ext:url>vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu38484eovjwz.tmp_/healdan-0.1.0.oxt/ImpressWindowState.xcu</ext:url><ext:media-type>application/vnd.sun.star.configuration-data</ext:media-type></ext:item></ext:extension-items></ext:extension></ext:extension-backend-db>
        """;

    /// <summary>The same profile's file once the extension was removed, as the owner's own profile has it too.</summary>
    private const string RegistryEmpty = """
        <?xml version="1.0"?>
        <ext:extension-backend-db xmlns:ext="http://openoffice.org/extensionmanager/extension-registry/2010"/>
        """;

    private const string InstalledFolder = "lu38484eovjwz.tmp_/healdan-0.1.0.oxt";

    [Fact]
    public void ReadsTheRegistry()
    {
        var user = Profile(RegistryInstalled, (InstalledFolder, Description("org.healdan.libreoffice", "0.1.0")));

        Assert.Equal(new InstalledExtension("0.1.0", true), ExtensionVersion.FromRegistry(user));
    }

    [Fact]
    public void ATurnedOffExtensionIsRevoked()
    {
        // Tools › Extensions › Disable marked the entry so, on the owner's Mac.
        var revoked = RegistryInstalled.Replace("healdan-0.1.0.oxt\">", "healdan-0.1.0.oxt\" revoked=\"true\">", StringComparison.Ordinal);
        var user = Profile(revoked, (InstalledFolder, Description("org.healdan.libreoffice", "0.1.0")));

        var installed = ExtensionVersion.FromRegistry(user);

        Assert.Equal(new InstalledExtension("0.1.0", false), installed);
        Assert.Null(installed!.UsableVersion);
    }

    [Fact]
    public void AFailedAddOrARemovedOneIsntInstalled()
    {
        // A failed unopkg add unpacks the folder but writes no backenddb.xml; a removal empties it and leaves the
        // folder until LibreOffice next starts.
        Assert.Null(ExtensionVersion.FromRegistry(Profile(null, (InstalledFolder, Description("org.healdan.libreoffice", "0.1.0")))));
        Assert.Null(ExtensionVersion.FromRegistry(Profile(RegistryEmpty, (InstalledFolder, Description("org.healdan.libreoffice", "0.1.0")))));
    }

    [Fact]
    public void AnUpdateIsReadFromTheFolderListed()
    {
        // After 0.1.0 → 0.2.0, both folders are there until LibreOffice next starts; the registry names the new one.
        var updated = RegistryInstalled.Replace("lu38484eovjwz.tmp_/healdan-0.1.0.oxt", "lu38405eovc7r.tmp_/healdan-0.2.0.oxt", StringComparison.Ordinal);
        var user = Profile(updated,
            (InstalledFolder, Description("org.healdan.libreoffice", "0.1.0")),
            ("lu38405eovc7r.tmp_/healdan-0.2.0.oxt", Description("org.healdan.libreoffice", "0.2.0")));

        Assert.Equal(new InstalledExtension("0.2.0", true), ExtensionVersion.FromRegistry(user));
    }

    [Fact]
    public void OnlyHealdansOwnEntryInsideTheCacheCounts()
    {
        var two = """
            <?xml version="1.0"?>
            <ext:extension-backend-db xmlns:ext="http://openoffice.org/extensionmanager/extension-registry/2010">
              <ext:extension url="vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/../../../evil/healdan.oxt"/>
              <ext:extension url="file:///tmp/healdan.oxt"/>
              <ext:extension url="vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu1.tmp_/grammar.oxt"/>
              <ext:extension url="vnd.sun.star.expand:$UNO_USER_PACKAGES_CACHE/uno_packages/lu2.tmp_/Healdan%20Extension.oxt"/>
            </ext:extension-backend-db>
            """;
        var user = Profile(two,
            ("lu1.tmp_/grammar.oxt", Description("org.example.grammar", "9.9.9")),
            ("lu2.tmp_/Healdan Extension.oxt", Description("org.healdan.libreoffice", "0.3.0")));
        Directory.CreateDirectory(Path.Combine(_dir, "evil", "healdan.oxt"));
        File.WriteAllText(Path.Combine(_dir, "evil", "healdan.oxt", "description.xml"), Description("org.healdan.libreoffice", "9.0.0"));

        Assert.Equal(new InstalledExtension("0.3.0", true), ExtensionVersion.FromRegistry(user));
    }

    [Fact]
    public void AMissingOrUnreadableRegistryIsNothingInstalled()
    {
        Assert.Null(ExtensionVersion.FromRegistry(Path.Combine(_dir, "no-profile")));
        Assert.Null(ExtensionVersion.FromRegistry(Profile("<not xml", (InstalledFolder, Description("org.healdan.libreoffice", "0.1.0")))));
        Assert.Null(ExtensionVersion.FromRegistry(Profile(RegistryInstalled, (InstalledFolder, "<not xml"))));
    }

    /// <summary>A LibreOffice user profile with the bundle backend's registry and the unpacked extensions' folders.</summary>
    private string Profile(string? backendDb, params (string Folder, string Description)[] extensions)
    {
        var user = Path.Combine(_dir, $"user-{Guid.NewGuid():N}");
        var cache = Path.Combine(user, "uno_packages", "cache");
        if (backendDb is not null)
        {
            var registry = Path.Combine(cache, "registry", "com.sun.star.comp.deployment.bundle.PackageRegistryBackend");
            Directory.CreateDirectory(registry);
            File.WriteAllText(Path.Combine(registry, "backenddb.xml"), backendDb);
        }
        foreach (var (folder, description) in extensions)
        {
            var unpacked = Path.Combine(cache, "uno_packages", folder);
            Directory.CreateDirectory(unpacked);
            File.WriteAllText(Path.Combine(unpacked, "description.xml"), description);
        }
        return user;
    }

    private static string Description(string identifier, string version) => $"""
        <?xml version="1.0" encoding="UTF-8"?>
        <description xmlns="http://openoffice.org/extensions/description/2006">
          <identifier value="{identifier}"/>
          <version value="{version}"/>
        </description>
        """;

    private string Oxt(string name, string description)
    {
        var path = Path.Combine(_dir, name);
        using var zip = ZipFile.Open(path, ZipArchiveMode.Create);
        using (var writer = new StreamWriter(zip.CreateEntry("description.xml").Open()))
            writer.Write(description);
        zip.CreateEntry("healdan.py");
        return path;
    }

    private static string RepoFile(params string[] parts)
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "clients", "libreoffice")))
            dir = dir.Parent;
        Assert.NotNull(dir);
        return Path.Combine([dir.FullName, .. parts]);
    }
}
