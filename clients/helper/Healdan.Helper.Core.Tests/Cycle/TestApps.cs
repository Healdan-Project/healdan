using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>The set of apps a test builds a cycle or a session with.</summary>
internal static class TestApps
{
    /// <summary>
    /// What the helper did before Microsoft Office: every kind of file in <paramref name="libreOffice"/>, and anything
    /// else in <paramref name="otherFiles"/> (in <paramref name="libreOffice"/> too when there is none).
    /// </summary>
    public static OfficeApps Of(IOfficeApp libreOffice, IOfficeApp? otherFiles = null, IOfficeApp? microsoftOffice = null,
        Func<string, string>? appFor = null) =>
        new(libreOffice, microsoftOffice, otherFiles, appFor ?? (name => FileKinds.OfFile(name) is null ? DefaultApp.AppName : "LibreOffice"));
}
