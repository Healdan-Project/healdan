-- Word.SaveAs: saves a document under a new path, kept off Open Recent where Word allows.
-- Arguments: POSIX path, file name, mark, window name (each may be empty), the new POSIX path and the new extension
-- (no dot). Looks for the document in that order, then takes the front one. Answers how it was found (path, mark,
-- window or front), character id 31, and the content's fingerprint text from just before the save; or missing.
on run argv
    set p to item 1 of argv
    set fname to item 2 of argv
    set wantedMark to item 3 of argv
    set windowName to item 4 of argv
    set newPath to item 5 of argv
    set newExt to item 6 of argv
    if application "Microsoft Word" is not running then return "missing"
    set sep to my separator()
    tell application "Microsoft Word"
        with timeout of 300 seconds
            set located to my locate(p, fname, wantedMark, windowName, true)
            set d to item 1 of located
            if d is missing value then return "missing"
            set howFound to item 2 of located
            set textBefore to my hdFingerprint(d)
            try
                my saveInFormat(d, newPath, newExt)
            on error
                my saveInFormat(d, ((POSIX file newPath) as text), newExt)
            end try
            return howFound & sep & textBefore
        end timeout
    end tell
end run

-- Finds the document: by path (or file name), by the add-in's mark, by window name, then, if useFront, the front one.
-- Answers {the document, how it was found}, or {missing value, ""}.
on locate(p, fname, wantedMark, windowName, useFront)
    tell application "Microsoft Word"
        if p is not "" then
            repeat with i from 1 to (count documents)
                set xd to document i
                try
                    if (posix full name of xd) is p then return {xd, "path"}
                end try
                try
                    if (name of xd) is fname then return {xd, "path"}
                end try
            end repeat
        end if
        if wantedMark is not "" then
            repeat with i from 1 to (count documents)
                set xd to document i
                try
                    if (value of custom document property "HealdanMark" of xd) is wantedMark then return {xd, "mark"}
                end try
            end repeat
        end if
        if windowName is not "" then
            repeat with i from 1 to (count windows)
                try
                    if (name of window i) is windowName then return {document of window i, "window"}
                end try
            end repeat
        end if
        if useFront then
            try
                return {active document, "front"}
            end try
        end if
        return {missing value, ""}
    end tell
end locate

on separator()
    return character id 31
end separator

-- The text that fingerprints a document: the same in every script that takes one.
on hdFingerprint(d)
    tell application "Microsoft Word"
        return "#" & (content of text object of d)
    end tell
end hdFingerprint

-- Saves the document under target, in the file format its extension (newExt) says.
on saveInFormat(d, target, newExt)
    tell application "Microsoft Word"
        if newExt is "docm" then
            save as d file name target file format format documentME add to recent files false
        else if newExt is "doc" then
            save as d file name target file format format document97 add to recent files false
        else if newExt is "dotx" then
            save as d file name target file format format template add to recent files false
        else if newExt is "dotm" then
            save as d file name target file format format templateME add to recent files false
        else if newExt is "rtf" then
            save as d file name target file format format rtf add to recent files false
        else
            save as d file name target file format format document add to recent files false
        end if
    end tell
end saveInFormat
