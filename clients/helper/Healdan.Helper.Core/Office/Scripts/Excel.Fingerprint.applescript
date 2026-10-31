-- Excel.Fingerprint: a text that summarises the workbook's content, to tell whether an edit landed between two
-- moments. Arguments: POSIX path, file name, mark, window name (each may be empty). Looks in that order, then at the
-- front window. Answers the text (it always starts with #), or nothing if no workbook was found.
on run argv
    set p to item 1 of argv
    set fname to item 2 of argv
    set wantedMark to item 3 of argv
    set windowName to item 4 of argv
    if application "Microsoft Excel" is not running then return ""
    tell application "Microsoft Excel"
        with timeout of 20 seconds
            set located to my locate(p, fname, wantedMark, windowName, true)
            set d to item 1 of located
            if d is missing value then return ""
            return my hdFingerprint(d)
        end timeout
    end tell
end run

-- Finds the workbook: by path (or file name), by the add-in's mark, by window name, then, if useFront, the front one.
-- Answers {the workbook, how it was found}, or {missing value, ""}.
on locate(p, fname, wantedMark, windowName, useFront)
    tell application "Microsoft Excel"
        if p is not "" then
            repeat with i from 1 to (count workbooks)
                set xd to workbook i
                try
                    if (my posixOf(full name of xd)) is p then return {xd, "path"}
                end try
                try
                    if (name of xd) is fname then return {xd, "path"}
                end try
            end repeat
        end if
        if wantedMark is not "" then
            repeat with i from 1 to (count workbooks)
                set xd to workbook i
                try
                    if (value of custom document property "HealdanMark" of xd) is wantedMark then return {xd, "mark"}
                end try
            end repeat
        end if
        if windowName is not "" then
            repeat with i from 1 to (count workbooks)
                set xd to workbook i
                try
                    if (name of xd) is windowName then return {xd, "window"}
                end try
            end repeat
        end if
        if useFront then
            try
                return {active workbook, "front"}
            end try
        end if
        return {missing value, ""}
    end tell
end locate

on posixOf(fullName)
    if fullName starts with "/" then return fullName
    try
        return POSIX path of fullName
    on error
        return fullName
    end try
end posixOf

-- The text that fingerprints a document: the same in every script that takes one.
on hdFingerprint(d)
    tell application "Microsoft Excel"
        set acc to "#"
        repeat with k from 1 to (count worksheets of d)
            set acc to acc & my hdFlat(value of (get used range of (worksheet k of d))) & "#"
        end repeat
        return acc
    end tell
end hdFingerprint

on hdFlat(cellValues)
    set AppleScript's text item delimiters to "|"
    try
        set t to cellValues as text
    on error
        set t to "?"
    end try
    set AppleScript's text item delimiters to ""
    return t
end hdFlat
