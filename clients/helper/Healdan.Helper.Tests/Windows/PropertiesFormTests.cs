// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Globalization;
using System.Text.Json;
using Avalonia.Controls;
using Avalonia.VisualTree;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Forms;
using Healdan.Helper.Windows;

namespace Healdan.Helper.Tests.Windows;

/// <summary>The properties form's controls (spec §5.5), for a Mac, as the app's other window tests are.</summary>
public sealed class PropertiesFormTests
{
    private const string DateForm = """
        {"items":[
          {"name":"emailsent","label":"Sent","type":"date","required":false,"editable":true,"value":"2026-03-03T00:00:00Z","source":null,"inheritedFrom":null,"options":null}
        ],"offset":0,"limit":10,"total":1}
        """;

    /// <summary>Builds the form under <paramref name="culture"/>, as the computer's region, and returns its date picker's format.</summary>
    private static string PickerFormat(string culture) => Headless.Run(() =>
    {
        var was = CultureInfo.CurrentCulture;
        CultureInfo.CurrentCulture = new CultureInfo(culture);
        try
        {
            var form = new PropertiesForm(new FormModel(JsonDocument.Parse(DateForm).RootElement.Clone()), new HealdanApi(new Uri("https://healdan.test")));
            var window = new Window { Content = form.View };
            window.Show();
            var picker = window.GetVisualDescendants().OfType<CalendarDatePicker>().Single();
            window.Close();
            return picker.CustomDateFormatString;
        }
        finally
        {
            CultureInfo.CurrentCulture = was;
        }
    });

    [MacFact]
    public void ADatePropertyIsWrittenInTheRegionsOrder()
    {
        Assert.Equal("MMM d, yyyy", PickerFormat("en-US"));
        Assert.Equal("d MMM yyyy", PickerFormat("en-GB"));
    }
}
