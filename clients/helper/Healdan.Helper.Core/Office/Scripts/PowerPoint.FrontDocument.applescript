-- PowerPoint.FrontDocument: the front presentation. No arguments.
-- Answers three names separated by character id 31: its POSIX path (empty if it was never saved), its own
-- name and its window's name (the same until the presentation is saved). Answers nothing if no presentation is open.
on run argv
    if application "Microsoft PowerPoint" is not running then return ""
    set sep to my separator()
    tell application "Microsoft PowerPoint"
        with timeout of 20 seconds
            if (count presentations) is 0 then return ""
            set d to active presentation
            set docName to name of d
            set windowName to docName
            set docPath to ""
            try
                if (path of d) is not "" then set docPath to my posixOf(full name of d)
            end try
            return docPath & sep & docName & sep & windowName
        end timeout
    end tell
end run

on separator()
    return character id 31
end separator

on posixOf(fullName)
    if fullName starts with "/" then return fullName
    try
        return POSIX path of fullName
    on error
        return fullName
    end try
end posixOf
