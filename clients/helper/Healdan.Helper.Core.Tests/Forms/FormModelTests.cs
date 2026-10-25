using System.Text.Json;
using Healdan.Helper.Core.Forms;
using static Healdan.Helper.Core.Tests.Forms.Forms;

namespace Healdan.Helper.Core.Tests.Forms;

/// <summary>
/// The properties form's rules (spec §5.8), free of windows. Its date test changes the process's time zone, so the
/// class runs on its own (<see cref="TimeZoneCollection"/>).
/// </summary>
[Collection(TimeZoneCollection.Name)]
public class FormModelTests
{
    [Fact]
    public void RequiredBlocksSave()
    {
        var form = new FormModel(Form(f => f["docclass"]["value"] = null));

        Assert.False(form.CanSave(folderCanFile: true));

        form.SetText("docclass", "Letters");
        Assert.True(form.CanSave(folderCanFile: true));
        Assert.False(form.CanSave(folderCanFile: false)); // a matter's top level, a search folder
    }

    [Fact]
    public void ReadOnlyFieldsNotSent()
    {
        var form = new FormModel(Form());

        form.Set("client", Json("\"999 – Someone Else\""));
        form.Set("matter", Json("\"999.9 – Elsewhere\""));
        form.SetText("comments", "Draft for the client");

        var values = form.Values();
        Assert.False(values.ContainsKey("client"));
        Assert.False(values.ContainsKey("matter"));
        Assert.Equal("""{"comments":"Draft for the client"}""", values.ToJsonString());
        Assert.Equal("998.1 – Healdan Demo", form.Field("matter")!.Text); // never changed on screen either
    }

    [Fact]
    public void ValuesHoldOnlyWhatChanged()
    {
        var form = new FormModel(Form());
        Assert.Equal("{}", form.Values().ToJsonString()); // inherited and default values are the server's own

        form.SetText("docclass", "Docs"); // set to what it is: not a change
        form.SetUser("author", 7, "Pat Example");
        form.SetUser("typist", 1, "Robert Example");

        Assert.Equal("""{"author":{"id":7}}""", form.Values().ToJsonString());
    }

    [Fact]
    public void SubclassNarrowedByClass()
    {
        var form = new FormModel(Form());
        Assert.Equal(["Draft", "Final"], form.OptionsFor("docsubclass").Select(o => o.Value));
        Assert.Equal(["Docs", "Letters"], form.OptionsFor("docclass").Select(o => o.Value)); // Class itself isn't narrowed

        form.SetText("docclass", "Letters");

        Assert.Equal(["Outgoing"], form.OptionsFor("docsubclass").Select(o => o.Value));
        // Draft belongs to Docs, so it can't stay once the Class is Letters.
        Assert.False(form.Field("docsubclass")!.HasValue);
    }

    [Fact]
    public void ASubclassOfTheNewClassStays()
    {
        var form = new FormModel(Form(f => f["docsubclass"]["value"] = null));
        form.SetText("docclass", "Letters");
        form.SetText("docsubclass", "Outgoing");

        Assert.Equal("""{"docclass":"Letters","docsubclass":"Outgoing"}""", form.Values().ToJsonString());
    }

    [Fact]
    public void DateIsCalendarDate()
    {
        var form = new FormModel(Form());
        form.SetText("deadline", "2026-10-24");
        Assert.Equal("""{"deadline":"2026-10-24T00:00:00Z"}""", form.Values().ToJsonString());

        foreach (var zone in new[] { "Pacific/Kiritimati", "Pacific/Pago_Pago", "Europe/London", "America/Los_Angeles" })
        {
            TimeZoneCollection.InZone(zone, () =>
            {
                var read = new FormModel(Form(f => f["deadline"]["value"] = "2026-10-24T00:00:00Z"));
                Assert.Equal("2026-10-24", read.Field("deadline")!.Text);
                Assert.Equal(new DateOnly(2026, 10, 24), read.Field("deadline")!.Date);
                // Saved later in the day in UTC, it's still that day.
                var late = new FormModel(Form(f => f["deadline"]["value"] = "2026-10-24T23:30:00.000Z"));
                Assert.Equal("2026-10-24", late.Field("deadline")!.Text);
            });
        }
    }

    [Fact]
    public void ADateSetToTheDayItHasIsNoChange()
    {
        var form = new FormModel(Form(f => f["deadline"]["value"] = "2026-10-24T09:15:00Z"));

        form.SetText("deadline", "2026-10-24");

        Assert.Equal("{}", form.Values().ToJsonString());
    }

    [Fact]
    public void CommentsUpTo4000()
    {
        var form = new FormModel(Form());

        form.SetText("comments", new string('a', 4000));
        Assert.True(form.CanSave(true));
        Assert.Null(form.Field("comments")!.Problem);
        Assert.Equal(4000, ((string?)form.Values()["comments"])!.Length);

        form.SetText("comments", new string('a', 4001));
        Assert.False(form.CanSave(true));
        Assert.Equal("Comments takes at most 4000 characters.", form.Field("comments")!.Problem);
    }

    [Fact]
    public void ClearingTextClearsTheValue()
    {
        var form = new FormModel(Form(f => f["comments"]["value"] = "Old note"));

        form.SetText("comments", "  ");

        Assert.Equal("""{"comments":null}""", form.Values().ToJsonString());
    }

    [Fact]
    public void NumbersAreCheckedAsTyped()
    {
        var form = new FormModel(Form());

        form.SetText("pages", "12a");
        Assert.Equal("Pages takes a whole number.", form.Field("pages")!.Problem);
        Assert.False(form.CanSave(true));

        form.SetText("pages", "12");
        Assert.Null(form.Field("pages")!.Problem);
        Assert.Equal("""{"pages":12}""", form.Values().ToJsonString());
    }

    [Fact]
    public void ADateMustBeADate()
    {
        var form = new FormModel(Form());

        form.SetText("deadline", "24/10/2026");

        Assert.Equal("Deadline takes a date such as 2026-10-24.", form.Field("deadline")!.Problem);
        Assert.False(form.CanSave(true));
    }

    [Fact]
    public void FieldsSayWhereTheirValuesComeFrom()
    {
        var form = new FormModel(Form());

        Assert.Equal("Drafts", form.Field("docclass")!.InheritedFrom);
        Assert.True(form.Field("client")!.FromMatter);
        Assert.False(form.Field("docclass")!.FromMatter);
        Assert.Equal("Robert Example", form.Field("author")!.Text);
        Assert.Equal(1, form.Field("author")!.UserId);
    }

    [Fact]
    public void OnlyFieldsWithSomethingToShowAreShown()
    {
        var form = new FormModel(Form());

        // The email fields come from a message: a new document's are empty and can't be typed in.
        Assert.DoesNotContain("emailfrom", form.Shown.Select(f => f.Name));
        Assert.Equal(["client", "matter", "author", "typist", "docclass", "docsubclass", "comments", "deadline", "pages"],
            form.Shown.Select(f => f.Name));
    }

    [Fact]
    public void CarriesWhatTheUserChangedToAnotherFolder()
    {
        var first = new FormModel(Form());
        first.SetText("comments", "Carry me");
        first.SetUser("author", 7, "Pat Example");

        var next = new FormModel(Form(f => f["docclass"]["value"] = "Letters"));
        next.Carry(first);

        Assert.Equal("Letters", next.Field("docclass")!.Text); // the new folder's own Class
        Assert.Equal("Pat Example", next.Field("author")!.Text);
        Assert.Equal("""{"author":{"id":7},"comments":"Carry me"}""", next.Values().ToJsonString());
    }

    [Fact]
    public void PropertiesSaveOnlyWhenSomethingChanged()
    {
        var form = new FormModel(Form(f => f["docclass"]["value"] = null));
        // A document whose required Class is empty can still have its Comments changed: only values sent are checked.
        Assert.False(form.CanPatch());

        form.SetText("comments", "Signed");
        Assert.True(form.CanPatch());

        form.SetUser("author", null, null);
        Assert.False(form.CanPatch()); // a required field sent empty
    }

    [Fact]
    public void ShowsTheServersRefusalOnItsField()
    {
        var form = new FormModel(Form());

        form.Refused("docsubclass", "Subclass doesn't belong to Class.");

        Assert.Equal("Subclass doesn't belong to Class.", form.Field("docsubclass")!.Problem);
        form.SetText("docsubclass", "Final");
        Assert.Null(form.Field("docsubclass")!.Problem);
    }

    [Fact]
    public void ReadsThePageOrItsItems()
    {
        var page = Form();
        var items = new FormModel(page.GetProperty("items"));

        Assert.Equal(new FormModel(page).Fields.Select(f => f.Name), items.Fields.Select(f => f.Name));
    }

    [Fact]
    public void ADocumentsOwnValuesAreItsPrefill()
    {
        var form = new FormModel(Form());
        var properties = Form(f =>
        {
            f["comments"]["value"] = "From the original";
            f["docclass"]["value"] = "Letters";
            f["docsubclass"]["value"] = "Outgoing";
        });

        form.Prefill(new FormModel(properties));

        Assert.Equal("""{"docclass":"Letters","docsubclass":"Outgoing","comments":"From the original"}""", form.Values().ToJsonString());
    }
}
