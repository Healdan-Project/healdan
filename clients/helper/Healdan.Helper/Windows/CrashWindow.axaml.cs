using Avalonia.Controls;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Windows;

/// <summary>
/// After a Crash (spec §5.8, §5.10): the app went away with the copy open. The document stays checked out and the
/// local copy is kept. Reopen opens the kept copy again, never a download over it; Send and Check In sends its changes
/// and checks it in, as closing it would have. Closing the window leaves both for later, from the menu.
/// </summary>
public partial class CrashWindow : Window
{
    /// <summary>For the XAML loader; the helper always opens it for a copy.</summary>
    public CrashWindow() => InitializeComponent();

    internal CrashWindow(CopyState copy) : this()
    {
        MessageText.Text = Warnings.Crashed(copy.Title, copy.ReadOnly);
        HintText.Text = "If you close this, the document stays in the Healdan Helper menu until you choose it.";
        if (copy.ReadOnly)
            CheckInButton.Content = "Remove Copy";
        ReopenButton.Click += (_, _) => Finish(CrashChoice.Reopen);
        CheckInButton.Click += (_, _) => Finish(CrashChoice.CheckIn);
        Opened += (_, _) => ReopenButton.Focus();
    }

    /// <summary>What the user chose; nothing when they closed the window.</summary>
    internal CrashChoice Choice { get; private set; } = CrashChoice.Later;

    private void Finish(CrashChoice choice)
    {
        Choice = choice;
        Close();
    }
}
