using Avalonia.Controls;
using Avalonia.Platform;

namespace Healdan.Helper.Windows;

/// <summary>
/// Open-Source Notices (#89): the notices of the open-source software the helper is built with, from the
/// <c>THIRD-PARTY-NOTICES.txt</c> built into it. <c>NoticesTests</c> checks the file names every package it restores.
/// </summary>
public partial class NoticesWindow : Window
{
    public NoticesWindow()
    {
        InitializeComponent();
        using var notices = new StreamReader(AssetLoader.Open(new Uri("avares://HealdanHelper/THIRD-PARTY-NOTICES.txt")));
        NoticesText.Text = notices.ReadToEnd();
        DoneButton.Click += (_, _) => Close();
        Opened += (_, _) => DoneButton.Focus();
    }
}
