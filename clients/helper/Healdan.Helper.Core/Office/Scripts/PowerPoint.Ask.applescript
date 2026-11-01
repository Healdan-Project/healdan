-- PowerPoint.Ask: whether PowerPoint answers the helper. No arguments. Run once before PowerPoint's first script since the helper
-- started, as macOS may first ask the user whether Healdan Helper may control PowerPoint (Automation), and every other
-- script but those that wait for Grant File Access gives up after 20 seconds while it asks.
-- Answers "ok", or "not-running" when PowerPoint isn't running, which asks nothing.
on run argv
    if application "Microsoft PowerPoint" is not running then return "not-running"
    tell application "Microsoft PowerPoint"
        with timeout of 300 seconds
            get name
        end timeout
    end tell
    return "ok"
end run
