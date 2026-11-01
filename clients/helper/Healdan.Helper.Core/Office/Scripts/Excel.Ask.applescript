-- Excel.Ask: whether Excel answers the helper. No arguments. Run once before Excel's first script since the helper
-- started, as macOS may first ask the user whether Healdan Helper may control Excel (Automation), and every other
-- script but those that wait for Grant File Access gives up after 20 seconds while it asks.
-- Answers "ok", or "not-running" when Excel isn't running, which asks nothing.
on run argv
    if application "Microsoft Excel" is not running then return "not-running"
    tell application "Microsoft Excel"
        with timeout of 300 seconds
            get name
        end timeout
    end tell
    return "ok"
end run
