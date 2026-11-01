-- Word.Ask: whether Word answers the helper. No arguments. Run once before Word's first script since the helper
-- started, as macOS may first ask the user whether Healdan Helper may control Word (Automation), and every other
-- script but those that wait for Grant File Access gives up after 20 seconds while it asks.
-- Answers "ok", or "not-running" when Word isn't running, which asks nothing.
-- It counts Word's documents, which Word answers: AppleScript answers an application's name, version, id and
-- running itself, sending Word nothing, so asking for its name, as rc2 did, never brought the question up
-- (the owner's walkthrough, 2026-11-01).
on run argv
    if application "Microsoft Word" is not running then return "not-running"
    tell application "Microsoft Word"
        with timeout of 300 seconds
            count documents
        end timeout
    end tell
    return "ok"
end run
