using Healdan.Helper.Core.Addin;

namespace Healdan.Helper.Windows;

/// <summary>
/// What Settings' Microsoft Office group (M3.5 spec §5.10) works with, given as delegates so the window needs no running helper.
/// </summary>
/// <param name="SetUp">Whether Set Up Microsoft Office applies and is needed (§5.13); null before the helper has made it.</param>
/// <param name="RunSetUp">Opens the Set Up window; true once the Mac trusts the certificate.</param>
/// <param name="Paired">The apps paired with the helper, with when (<see cref="Pairing.Paired"/>).</param>
/// <param name="Forget">Forgets an app's pairing (<see cref="Pairing.Forget"/>).</param>
internal sealed record OfficeSettings(
    OfficeSetUp? SetUp, Func<Task<bool>> RunSetUp, Func<IReadOnlyList<PairedApp>> Paired, Action<string> Forget);
