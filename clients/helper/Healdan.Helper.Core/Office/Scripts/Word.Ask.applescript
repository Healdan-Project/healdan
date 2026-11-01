-- Word.Ask: whether Word answers the helper. No arguments. Run once before Word's first script since the helper
-- started, as macOS may first ask the user whether Healdan Helper may control Word (Automation), and every other
-- script but those that wait for Grant File Access gives up after 20 seconds while it asks.
-- Answers "ok", or "not-running" when Word isn't running, which asks nothing.
on run argv
    if application "Microsoft Word" is not running then return "not-running"
    tell application "Microsoft Word"
        with timeout of 300 seconds
            get name
        end timeout
    end tell
    return "ok"
end run
