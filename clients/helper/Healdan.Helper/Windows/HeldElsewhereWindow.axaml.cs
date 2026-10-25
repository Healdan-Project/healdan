using Avalonia.Controls;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Windows;

/// <summary>
/// Checked Out on Another Computer (spec §5.10): the document is checked out to this user on another computer, and
/// nothing has been opened. Open Read-Only opens a read-only copy; Edit Here Instead moves the check-out to this
/// computer; Cancel, the safe choice, is the default.
/// </summary>
public partial class HeldElsewhereWindow : Window
{
    /// <summary>For the XAML loader; the helper always opens it with the warning.</summary>
    public HeldElsewhereWindow() => InitializeComponent();

    internal HeldElsewhereWindow(HeldElsewhereWarning warning) : this()
    {
        TitleText.Text = warning.Title;
        (LastSavedText.Text, LastSavedText.IsVisible) = (warning.LastSaved, warning.LastSaved is not null);
        EmphasisText.Text = warning.Emphasis;
        DetailText.Text = warning.Detail;
        ReadOnlyButton.Click += (_, _) => Finish(HeldElsewhereChoice.OpenReadOnly);
        EditHereButton.Click += (_, _) => Finish(HeldElsewhereChoice.EditHere);
        CancelButton.Click += (_, _) => Close();
        Opened += (_, _) => CancelButton.Focus();
    }

    /// <summary>What the user chose; Cancel when they closed the window.</summary>
    internal HeldElsewhereChoice Choice { get; private set; } = HeldElsewhereChoice.Cancel;

    private void Finish(HeldElsewhereChoice choice)
    {
        Choice = choice;
        Close();
    }
}
