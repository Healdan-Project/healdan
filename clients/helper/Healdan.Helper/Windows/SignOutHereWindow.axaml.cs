using Avalonia.Controls;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Windows;

/// <summary>
/// Sign Out on This Computer Only? (spec §5.2): Sign Out couldn't reach Healdan to revoke the token. Sign Out on This
/// Computer forgets the sign-in here; Stay Signed In, the safe choice, is the default.
/// </summary>
public partial class SignOutHereWindow : Window
{
    /// <summary>For the XAML loader; the helper always opens it with the warning.</summary>
    public SignOutHereWindow() => InitializeComponent();

    internal SignOutHereWindow(SignOutHereWarning warning) : this()
    {
        TitleText.Text = warning.Title;
        DetailText.Text = warning.Detail;
        (UnsentText.Text, UnsentBox.IsVisible) = (warning.Unsent, warning.Unsent is not null);
        SignOutHereButton.Click += (_, _) =>
        {
            SignOutHere = true;
            Close();
        };
        StayButton.Click += (_, _) => Close();
        Opened += (_, _) => StayButton.Focus();
    }

    /// <summary>The user chose Sign Out on This Computer; false when they stayed signed in or closed the window.</summary>
    internal bool SignOutHere { get; private set; }
}
