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
        string[] common = ["Ask", "FrontDocument", "IsOpen", "HasUnsavedChanges", "Fingerprint", "HasMark", "Save", "SaveAs"];
        var expected = new List<string>();
        foreach (var program in new[] { "Word", "Excel", "PowerPoint" })
            expected.AddRange(common.Select(op => $"{program}.{op}"));
        expected.AddRange(["Word.Rename", "Word.SetDocId", "Excel.Rename", "PowerPoint.Reopen"]);

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
    /// user (Grant File Access), and the script waits for the answer (spec §8.5). And each program's Ask, which waits
    /// for macOS's Automation prompt (the owner's walkthrough).
    /// </summary>
    private static readonly string[] LongScripts =
        ["Word.SaveAs", "Excel.SaveAs", "PowerPoint.SaveAs", "Word.Rename", "Excel.Rename", "PowerPoint.Reopen", "Word.Ask", "Excel.Ask", "PowerPoint.Ask"];

    [Fact]
    public void LongScriptsWaitForGrantFileAccess()
    {
        var scripts = Scripts();
        foreach (var name in LongScripts)
            Assert.Contains("with timeout of 300 seconds", scripts[name]);
    }

    /// <summary>
    /// What each Ask asks its app inside the long wait. AppleScript answers an application's name, version, id and running
    /// itself, sending the app nothing, so macOS never asks about those: rc2's Ask got the name and came back at once,
    /// and the next script met the question with its 20 seconds (the owner's walkthrough, 2026-11-01).
    /// </summary>
    [Fact]
    public void EachAskAsksItsAppSomethingOnlyTheAppAnswers()
    {
        var scripts = Scripts();
        foreach (var (program, plural) in new[] { ("Word", "documents"), ("Excel", "workbooks"), ("PowerPoint", "presentations") })
        {
            var text = scripts[$"{program}.Ask"];
            var start = text.IndexOf("with timeout of 300 seconds", StringComparison.Ordinal);
            var wait = text[start..text.IndexOf("end timeout", start, StringComparison.Ordinal)];
            Assert.Contains($"count {plural}", wait);
            Assert.False(Regex.IsMatch(wait, @"\b(name|version|id|running)\b"), $"{program}.Ask asks for something AppleScript answers itself");
        }
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
    public void EveryScriptChecksTheAppIsRunningFirst()
    {
        // A tell block starts a program that isn't running, and a poll must not. Opening, which wants it started, is open -a.
        foreach (var (name, text) in Scripts())
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
        // Fingerprint, SaveAs, Rename and Word's SetDocId each carry their own copy of the handler, and their texts have to
        // be comparable.
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
        // locate, findDocument, sharesItsName and isInFolder are copied into each script that finds a document, and
        // must not drift apart.
        foreach (var program in new[] { "Word", "Excel", "PowerPoint" })
        {
            foreach (var handler in new[] { "locate", "findDocument", "sharesItsName", "isInFolder" })
            {
                var handlers = Scripts().Where(s => s.Key.StartsWith(program + ".", StringComparison.Ordinal) && s.Value.Contains($"on {handler}(", StringComparison.Ordinal))
                    .ToDictionary(s => s.Key, s => Handler(s.Value, handler));
                Assert.True(handlers.Count >= 2, $"{program} has too few scripts with {handler}");
                Assert.Single(handlers.Values.Distinct());
            }
        }
    }

    [Fact]
    public void ADocumentWhoseNameIsSharedIsNeverTakenForTheCopy()
    {
        // The owner's by-hand run (2026-10-31): Word's scripting names a document by its name, so of two documents with
        // one name it reaches only one, whichever path is asked for. locate refuses a shared name rather than guess,
        // and IsOpen answers yes then, so a copy that may be open is never checked in from under its window (spec
        // section 5.2).
        foreach (var (name, text) in Scripts().Where(s => s.Value.Contains("on locate(", StringComparison.Ordinal)))
        {
            var locate = Handler(text, "locate");
            Assert.True(locate.Contains("my findDocument(p, pTwin, fname, copiesFolder, copiesTwin, wantedMark, windowName, useFront)", StringComparison.Ordinal)
                && locate.Contains("if my sharesItsName(item 1 of located) then return {missing value, \"ambiguous\"}", StringComparison.Ordinal),
                $"{name} takes a document whose name another one has");
            Assert.Contains("return seen > 1", Handler(text, "sharesItsName"));
        }
        var isOpen = Scripts().Where(s => s.Key.EndsWith(".IsOpen", StringComparison.Ordinal)).ToList();
        Assert.Equal(3, isOpen.Count);
        foreach (var (name, text) in isOpen)
        {
            var shared = text.IndexOf("if (item 2 of located) is \"ambiguous\" then return \"yes\"", StringComparison.Ordinal);
            var missing = text.IndexOf("if (item 1 of located) is missing value then return \"no\"", StringComparison.Ordinal);
            Assert.True(shared >= 0 && missing > shared, $"{name} takes a shared name for a closed document");
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
        Assert.Equal(22, finding.Count); // all but the three FrontDocuments

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
    public void SaveAsFingerprintsBeforeAndAfterTheSave()
    {
        // The final review's Minor 5: as Rename does, Save As fingerprints the content again after the save, finding the
        // document by its new name, and compares the two case and all (in a handler of its own, as Word's dictionary
        // takes "case"), so a line typed while the program saved counts as a change. The text from before the save goes
        // last, as it may hold the separator.
        var savesAs = Scripts().Where(s => s.Key.EndsWith(".SaveAs", StringComparison.Ordinal)).ToList();
        Assert.Equal(3, savesAs.Count);
        foreach (var (name, text) in savesAs)
        {
            InOrder(name, text,
                "set newName to my fileNameOf(newPath)",
                "set textBefore to my hdFingerprint(d)",
                "my saveInFormat(d, newPath, newExt)",
                "(name of xd) is newName then set d2 to xd",
                "set sameAfter to \"lost\"",
                "if my sameText(textBefore, my hdFingerprint(d2)) then",
                "return howFound & sep & sameAfter & sep & textBefore");
            Assert.Contains("considering case", Handler(text, "sameText"));
        }
    }

    [Fact]
    public void SetDocIdLeavesADocumentTypedInMeanwhileUnsaved()
    {
        // The final review's Important 2: Word takes keystrokes between the Apple events that update the footer's fields.
        // SetDocId fingerprints the text before it reads saved (a key between the two leaves wasSaved false), changes the
        // property and the fields, fingerprints again, and marks the document saved only if it was and the text is the
        // same, case and all. It then fingerprints once more and takes the mark back if a key came in between. A
        // keystroke meanwhile leaves the document unsaved, so Word asks about it at close.
        var text = Scripts()["Word.SetDocId"];
        InOrder("Word.SetDocId", text,
            "set textBefore to my hdFingerprint(d)",
            "set wasSaved to saved of d",
            "set value of custom document property \"Healdan Doc ID\" of d to v",
            "update field",
            "set untouched to my sameText(textBefore, my hdFingerprint(d))",
            "if wasSaved and untouched then",
            "set saved of d to true",
            "if not my sameText(textBefore, my hdFingerprint(d)) then set saved of d to false",
            "end if");
        Assert.Equal(2, Regex.Matches(text, "set saved of d").Count);
        Assert.Single(Regex.Matches(text, "set saved of d to true"));
        Assert.Contains("considering case", Handler(text, "sameText"));
    }

    [Fact]
    public void RenameFingerprintsBeforeItReadsSavedAndComparesCaseAndAll()
    {
        // The final review's residuals: a key between reading saved and fingerprinting would be taken into both
        // fingerprints and confirmed, so Rename fingerprints first and then refuses a document that isn't saved. After the
        // save it compares through sameText, as Save As does, so a letter changed in its case counts as an edit.
        var renames = Scripts().Where(s => s.Key.EndsWith(".Rename", StringComparison.Ordinal)).ToList();
        Assert.Equal(["Excel.Rename", "Word.Rename"], renames.Select(r => r.Key).Order().ToArray());
        foreach (var (name, text) in renames)
        {
            InOrder(name, text,
                "set fpBefore to my hdFingerprint(d)",
                "if (saved of d) is false then return \"unsaved\"",
                "my saveInFormat(d, newPath, newExt)",
                "(name of xd) is newName then set d2 to xd",
                "if my sameText(fpBefore, my hdFingerprint(d2)) then return \"renamed\"");
            Assert.DoesNotContain("fpBefore is", text);
            Assert.Contains("considering case", Handler(text, "sameText"));
        }
    }

    [Fact]
    public void TheComparingIsTheSameInEveryScript()
    {
        // sameText and fileNameOf are copied into each script that uses them, and must not drift apart. Every script that
        // compares through sameText has the handler (it wouldn't compile otherwise), and these are the ones that do.
        string[] comparing = ["Excel.Rename", "Excel.SaveAs", "PowerPoint.SaveAs", "Word.Rename", "Word.SaveAs", "Word.SetDocId"];
        foreach (var handler in new[] { "sameText", "fileNameOf" })
        {
            var handlers = Scripts().Where(s => s.Value.Contains($"on {handler}(", StringComparison.Ordinal))
                .ToDictionary(s => s.Key, s => Handler(s.Value, handler));
            Assert.True(handlers.Count >= 3, $"too few scripts with {handler}");
            Assert.Single(handlers.Values.Distinct());
            if (handler == "sameText")
                Assert.Equal(comparing, handlers.Keys.Order().ToArray());
        }
        Assert.Equal(comparing, Scripts().Where(s => s.Value.Contains("my sameText(", StringComparison.Ordinal)).Select(s => s.Key).Order().ToArray());
    }

    [Fact]
    public void TheHfsPathRetryPassesOnCancelsTimeoutsAndRefusals()
    {
        // The final review's Minor 13: where Word turns down a POSIX path, the open or save is tried again with an HFS one.
        // A cancel (-128), a timeout (-1712) or a refusal of Automation (-1743) is raised as it came instead: tried
        // again, it would wait up to another 300 seconds and ask for Grant File Access twice. Anything else is tried again,
        // as the path error's own number is unconfirmed without Word.
        var retrying = Scripts().Where(s => s.Value.Split('\n').Any(IsHfsRetry)).Select(s => s.Key).Order().ToList();
        Assert.Equal(["Word.Rename", "Word.SaveAs"], retrying);
        foreach (var name in retrying)
        {
            var lines = Scripts()[name].Split('\n').Select(l => l.Trim()).Where(l => !l.StartsWith("--", StringComparison.Ordinal)).ToList();
            foreach (var at in Enumerable.Range(0, lines.Count).Where(i => IsHfsRetry(lines[i])))
            {
                Assert.Equal("on error errText number errNum", lines[at - 2]);
                Assert.Equal("if errNum is in {-128, -1712, -1743} then error errText number errNum", lines[at - 1]);
            }
        }
        Assert.DoesNotContain(Scripts().Values, text => Regex.IsMatch(text, @"on error\s*\n\s*[^\n]*\(POSIX file \w+\) as text"));
    }

    /// <summary>A line that opens or saves again with the HFS form of a POSIX path.</summary>
    private static bool IsHfsRetry(string line) => line.Contains("(POSIX file ", StringComparison.Ordinal) && line.Contains(") as text)", StringComparison.Ordinal);

    /// <summary>Each line is in the script, after the one before it.</summary>
    private static void InOrder(string name, string script, params string[] lines)
    {
        var from = 0;
        foreach (var line in lines)
        {
            var at = script.IndexOf(line, from, StringComparison.Ordinal);
            Assert.True(at >= 0, $"{name} doesn't have, after what comes before it: {line}");
            from = at + line.Length;
        }
    }

    [Fact]
    public void AFileNameOnlyFindsADocumentInTheCopiesFolder()
    {
        // The exact path, in either spelling, is tried over every document before any file name is; and a file name
        // finds only a document whose own folder is Copies. A copy set aside keeps its name beside Copies, so two
        // documents of one name can be open at once (spec section 5.2).
        foreach (var (name, text) in Scripts().Where(s => s.Value.Contains("on locate(", StringComparison.Ordinal)))
        {
            var locate = Handler(text, "findDocument");
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
