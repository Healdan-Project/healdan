-- Word.FrontDocument: the front document. No arguments.
-- Answers three names separated by character id 31: its POSIX path (empty if it was never saved), its own
-- name and its window's name (which finds a never-saved document). Answers nothing if no document is open.
on run argv
    if application "Microsoft Word" is not running then return ""
    set sep to my separator()
    tell application "Microsoft Word"
        with timeout of 20 seconds
            if (count documents) is 0 then return ""
            set d to active document
            set docName to name of d
            set windowName to ""
            try
                set windowName to name of active window
            end try
            set docPath to ""
            try
                if (path of d) is not "" then set docPath to posix full name of d
            end try
            return docPath & sep & docName & sep & windowName
        end timeout
    end tell
end run

on separator()
    return character id 31
end separator
