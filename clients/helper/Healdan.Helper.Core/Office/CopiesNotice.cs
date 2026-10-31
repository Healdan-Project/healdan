using Healdan.Helper.Core.Settings;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Office;

/// <summary>
/// The notice before a program's first save into Copies (M3.5 spec §5.11). Word, Excel and PowerPoint each ask, once,
/// for access to the folder they save in, and their chooser opens inside Copies with Grant Access greyed out, so the
/// user has to go up a level and select the folder. Each program is told once, ever: the programs told are kept in
/// <c>settings.json</c> (<see cref="HelperSettings.CopiesAnnounced"/>), so a later start, or another instance (Save to
/// Healdan's), doesn't tell it again. It never throws: a notice is never worth stopping a save for.
/// </summary>
public sealed class CopiesNotice(HelperPaths paths, INotifier notifier)
{
    private readonly HashSet<string> _told = new(StringComparer.Ordinal);

    /// <summary>Tells the user what the program is about to ask, unless it has been told before.</summary>
    public void BeforeFirstSave(OfficeProgram p)
    {
        var name = OfficePrograms.CheckOutName(p);
        lock (_told)
        {
            if (_told.Contains(name))
                return;
            _told.Add(name);
        }
        var first = true;
        try
        {
            if (HelperSettings.Load(paths).CopiesAnnounced?.Contains(name) == true)
                first = false;
            else
                HelperSettings.Update(paths, s =>
                {
                    // Decided under Update's lock, so two saves at once tell the program once.
                    s.CopiesAnnounced ??= [];
                    first = !s.CopiesAnnounced.Contains(name);
                    if (first)
                        s.CopiesAnnounced.Add(name);
                });
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            // Not kept: told again at a later start, which is better than never.
        }
        if (first)
            notifier.Notify("Healdan Helper", Message(p));
    }

    /// <summary>What the notice says: the steps the program's folder chooser needs (spec §5.11).</summary>
    public static string Message(OfficeProgram p) =>
        $"{OfficePrograms.CheckOutName(p)} will ask for access to Healdan Helper's Copies folder. Click Select…, go up one "
        + "level, select Copies, and click Grant Access.";
}
