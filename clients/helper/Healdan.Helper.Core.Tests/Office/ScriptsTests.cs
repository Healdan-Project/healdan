using System.Text.RegularExpressions;
using Healdan.Helper.Core.Office;

namespace Healdan.Helper.Core.Tests.Office;

/// <summary>
/// The AppleScript files the helper drives Word, Excel and PowerPoint with (spec §5.2). They are fixed text: every
/// value goes in through <c>on run argv</c>, and what the trial lost rounds to (loops over an app's collections,
/// words an app's dictionary takes) is checked here, where no Office is needed. Whether they compile needs the apps'
/// dictionaries, so <c>MacOfficeTests</c> does that, by hand.
/// </summary>
public sealed partial class ScriptsTests
{
    private const string Prefix = "Healdan.Helper.Core.Office.Scripts.";

    /// <summary><c>{{</c> or <c>{0}</c>: a placeholder a format string would fill. AppleScript's own lists use single braces.</summary>
    private static readonly Regex Placeholder = PlaceholderPattern();

    private static readonly Regex LoopWithIn = LoopWithInPattern();

    private static readonly Regex TakenName = TakenNamePattern();

    /// <summary>Every embedded script, by its name without the folder and extension: <c>Word.Rename</c>.</summary>
    internal static Dictionary<string, string> Scripts()
    {
        var assembly = typeof(OfficePrograms).Assembly;
        var scripts = new Dictionary<string, string>();
        foreach (var resource in assembly.GetManifestResourceNames().Where(n => n.StartsWith(Prefix, StringComparison.Ordinal) && n.EndsWith(".applescript", StringComparison.Ordinal)))
        {
            using var stream = assembly.GetManifestResourceStream(resource)!;
            using var reader = new StreamReader(stream);
            scripts[resource[Prefix.Length..^".applescript".Length]] = reader.ReadToEnd();
        }
        return scripts;
    }

    [Fact]
    public void TheScriptsAreTheOnesTheSeamNeeds()
    {
        string[] common = ["FrontDocument", "IsOpen", "HasUnsavedChanges", "Fingerprint", "HasMark", "Save", "SaveAs"];
        var expected = new List<string>();
        foreach (var program in new[] { "Word", "Excel", "PowerPoint" })
            expected.AddRange(common.Select(op => $"{program}.{op}"));
        expected.AddRange(["Word.Open", "Word.Rename", "Word.SetDocId", "Excel.Rename", "PowerPoint.Reopen"]);

        Assert.Equal(expected.Order().ToArray(), Scripts().Keys.Order().ToArray());
    }

    [Fact]
    public void EveryScriptTakesItsValuesAsArguments()
    {
        foreach (var (name, text) in Scripts())
        {
            Assert.True(text.Contains("on run argv", StringComparison.Ordinal), $"{name} doesn't take its values from argv");
            Assert.False(Placeholder.IsMatch(text), $"{name} has a placeholder, which would make it built text");
        }
    }

    [Fact]
    public void NoScriptLoopsOverACollectionWithIn()
    {
        foreach (var (name, text) in Scripts())
            Assert.False(LoopWithIn.IsMatch(text), $"{name} loops over an app's collection with 'in', which fails or hangs in Word: loop by index");
    }

    [Fact]
    public void NoScriptUsesANameTheAppsTake()
    {
        foreach (var (name, text) in Scripts())
            Assert.False(TakenName.IsMatch(text), $"{name} names a variable with a word the apps' dictionaries use");
    }

    [Fact]
    public void NoScriptRunsAnythingElse()
    {
        foreach (var (name, text) in Scripts())
        {
            Assert.DoesNotContain("do shell script", text);
            Assert.DoesNotContain("run script", text);
            Assert.False(Regex.IsMatch(text, @"\bevaluate\b"), $"{name} evaluates text");
        }
    }

    [Fact]
    public void ChecksSeeWhatTheyLookFor()
    {
        // Not vacuous: each pattern matches the thing it is there to catch.
        Assert.Matches(Placeholder, "set v to {0}");
        Assert.Matches(Placeholder, "tell application \"{{app}}\"");
        Assert.DoesNotMatch(Placeholder, "repeat with hf in {header footer primary, header footer first page}");
        Assert.Matches(LoopWithIn, "repeat with xd in documents");
        Assert.Matches(LoopWithIn, "repeat with s in slides of d");
        Assert.DoesNotMatch(LoopWithIn, "repeat with hf in {header footer primary}");
        Assert.Matches(TakenName, "set found to 1");
        Assert.Matches(TakenName, "repeat with x from 1 to 3");
        Assert.DoesNotMatch(TakenName, "set foundIt to 1");
        Assert.DoesNotMatch(TakenName, "set xd to document i");
    }

    /// <summary>
    /// The scripts that write or open files in a folder the app may not have been granted yet. The app then asks the
    /// user (Grant File Access), and the script waits for the answer (spec §8.5).
    /// </summary>
    private static readonly string[] LongScripts =
        ["Word.SaveAs", "Excel.SaveAs", "PowerPoint.SaveAs", "Word.Rename", "Excel.Rename", "PowerPoint.Reopen", "Word.Open"];

    [Fact]
    public void LongScriptsWaitForGrantFileAccess()
    {
        var scripts = Scripts();
        foreach (var name in LongScripts)
            Assert.Contains("with timeout of 300 seconds", scripts[name]);
    }

    [Fact]
    public void EveryOtherScriptWaitsTwentySeconds()
    {
        foreach (var (name, text) in Scripts().Where(s => !LongScripts.Contains(s.Key)))
        {
            Assert.Contains("with timeout of 20 seconds", text);
            Assert.DoesNotContain("300 seconds", text);
        }
    }

    [Fact]
    public void EachScriptTalksToItsOwnProgramOnly()
    {
        var apps = new Dictionary<string, string>
        {
            ["Word"] = "Microsoft Word", ["Excel"] = "Microsoft Excel", ["PowerPoint"] = "Microsoft PowerPoint",
        };
        foreach (var (name, text) in Scripts())
        {
            var own = apps[name[..name.IndexOf('.')]];
            var told = Regex.Matches(text, "application \"([^\"]+)\"").Select(m => m.Groups[1].Value).Distinct().ToList();
            Assert.Equal([own], told);
        }
    }

    [Fact]
    public void EveryScriptButOpenChecksTheAppIsRunningFirst()
    {
        // A tell block starts a program that isn't running, and a poll must not. Open, which wants it started, is the exception.
        foreach (var (name, text) in Scripts().Where(s => s.Key != "Word.Open"))
        {
            var app = Regex.Match(text, "application \"([^\"]+)\"").Groups[1].Value;
            Assert.Contains($"if application \"{app}\" is not running then return", text);
            Assert.True(text.IndexOf("is not running", StringComparison.Ordinal) < text.IndexOf($"tell application \"{app}\"", StringComparison.Ordinal),
                $"{name} checks that the app is running after it has told it something");
        }
    }

    [Fact]
    public void TheFingerprintIsTheSameInEveryScriptOfAProgram()
    {
        // Fingerprint, SaveAs and Rename each carry their own copy of the handler, and their texts have to be comparable.
        foreach (var program in new[] { "Word", "Excel", "PowerPoint" })
        {
            var handlers = Scripts().Where(s => s.Key.StartsWith(program + ".", StringComparison.Ordinal) && s.Value.Contains("on hdFingerprint(", StringComparison.Ordinal))
                .ToDictionary(s => s.Key, s => Handler(s.Value, "hdFingerprint"));
            Assert.True(handlers.Count >= 2, $"{program} has too few fingerprinting scripts");
            Assert.Single(handlers.Values.Distinct());
        }
    }

    [Fact]
    public void TheFindingIsTheSameInEveryScriptOfAProgram()
    {
        // locate and isInFolder are copied into each script that finds a document, and must not drift apart.
        foreach (var program in new[] { "Word", "Excel", "PowerPoint" })
        {
            foreach (var handler in new[] { "locate", "isInFolder" })
            {
                var handlers = Scripts().Where(s => s.Key.StartsWith(program + ".", StringComparison.Ordinal) && s.Value.Contains($"on {handler}(", StringComparison.Ordinal))
                    .ToDictionary(s => s.Key, s => Handler(s.Value, handler));
                Assert.True(handlers.Count >= 2, $"{program} has too few scripts with {handler}");
                Assert.Single(handlers.Values.Distinct());
            }
        }
    }

    [Fact]
    public void EveryScriptThatFindsADocumentIsGivenTheSameFinder()
    {
        // Arguments 1 to 5 are the same in every one, so MacOfficeScripting builds them once (spec section 5.2).
        string[] reads =
        [
            "set p to item 1 of argv", "set pTwin to item 2 of argv", "set fname to item 3 of argv",
            "set copiesFolder to item 4 of argv", "set copiesTwin to item 5 of argv",
        ];
        var finding = Scripts().Where(s => s.Value.Contains("my locate(", StringComparison.Ordinal)).ToList();
        Assert.Equal(22, finding.Count); // all but the three FrontDocuments and Word.Open

        foreach (var (name, text) in finding)
        {
            if (name.EndsWith(".HasMark", StringComparison.Ordinal))
            {
                Assert.Contains("set wantedMark to item 6 of argv", text);
                Assert.Contains("my locate(\"\", \"\", \"\", \"\", \"\", wantedMark, \"\", false)", text);
                continue;
            }
            foreach (var read in reads)
                Assert.True(text.Contains(read, StringComparison.Ordinal), $"{name} doesn't read: {read}");
            Assert.Matches(@"my locate\(p, pTwin, fname, copiesFolder, copiesTwin, (""""|wantedMark), (""""|windowName), (false|true)\)", text);
        }
    }

    [Fact]
    public void SaveAsNeverTakesTheFrontWindow()
    {
        // Review Focus 2: Save As saving the front document under a copy's name would make another document that copy,
        // and send its content up as the copy's. It finds the document it was given, or answers missing.
        var savesAs = Scripts().Where(s => s.Key.EndsWith(".SaveAs", StringComparison.Ordinal)).ToList();
        Assert.Equal(3, savesAs.Count);
        foreach (var (name, text) in savesAs)
            Assert.True(text.Contains("my locate(p, pTwin, fname, copiesFolder, copiesTwin, wantedMark, windowName, false)", StringComparison.Ordinal),
                $"{name} falls back to the front window");
    }

    [Fact]
    public void AFileNameOnlyFindsADocumentInTheCopiesFolder()
    {
        // The exact path, in either spelling, is tried over every document before any file name is; and a file name
        // finds only a document whose own folder is Copies. A copy set aside keeps its name beside Copies, so two
        // documents of one name can be open at once (spec section 5.2).
        foreach (var (name, text) in Scripts().Where(s => s.Value.Contains("on locate(", StringComparison.Ordinal)))
        {
            var locate = Handler(text, "locate");
            var exact = locate.IndexOf("docPath is p then return", StringComparison.Ordinal);
            var twin = locate.IndexOf("docPath is pTwin then return", StringComparison.Ordinal);
            var byName = locate.IndexOf("(name of xd) is fname then", StringComparison.Ordinal);
            var inCopies = locate.IndexOf("my isInFolder(docPath, copiesFolder) or my isInFolder(docPath, copiesTwin)", StringComparison.Ordinal);
            Assert.True(exact >= 0 && twin > exact && byName > twin && inCopies > byName, $"{name}: the exact path must come before the file name, which must be checked against Copies");
            Assert.Single(Regex.Matches(locate, @"is fname")); // the file name is used nowhere else
            Assert.True(locate.IndexOf("then return {xd, \"path\"}", byName, StringComparison.Ordinal) > inCopies, $"{name}: the file name returns without the Copies check");
        }
    }

    /// <summary>A handler's text, from its <c>on</c> line to its <c>end</c> line.</summary>
    private static string Handler(string script, string name)
    {
        var start = script.IndexOf($"on {name}(", StringComparison.Ordinal);
        var end = script.IndexOf($"end {name}", start, StringComparison.Ordinal);
        Assert.True(start >= 0 && end > start, $"no handler {name}");
        return script[start..end];
    }

    [GeneratedRegex(@"\{\{|\{\d+\}")]
    private static partial Regex PlaceholderPattern();

    [GeneratedRegex(@"repeat with \w+ in (documents|sections|fields|workbooks|worksheets|presentations|slides|shapes)\b")]
    private static partial Regex LoopWithInPattern();

    [GeneratedRegex(@"(?:\bset|\brepeat with) (flat|x|out|before|after|found|mark) (?:to|from|in)\b")]
    private static partial Regex TakenNamePattern();
}
