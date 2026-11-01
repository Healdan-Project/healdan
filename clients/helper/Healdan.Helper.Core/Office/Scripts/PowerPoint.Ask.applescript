-- PowerPoint.Ask: whether PowerPoint answers the helper. No arguments. Run once before PowerPoint's first script since the helper
-- started, as macOS may first ask the user whether Healdan Helper may control PowerPoint (Automation), and every other
-- script but those that wait for Grant File Access gives up after 20 seconds while it asks.
-- Answers "ok", or "not-running" when PowerPoint isn't running, which asks nothing.
-- It counts PowerPoint's presentations, which PowerPoint answers: AppleScript answers an application's name, version, id and
-- running itself, sending PowerPoint nothing, so asking for its name, as rc2 did, never brought the question up
-- (the owner's walkthrough, 2026-11-01).
on run argv
    if application "Microsoft PowerPoint" is not running then return "not-running"
    tell application "Microsoft PowerPoint"
        with timeout of 300 seconds
            count presentations
        end timeout
    end tell
    return "ok"
end run
