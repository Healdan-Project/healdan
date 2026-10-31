-- Excel.Rename: saves the workbook under a new name, only if it has no unsaved changes, kept off the recently used list.
-- Arguments: POSIX path, file name, the new POSIX path, the new file name, the new extension (no dot).
-- Answers missing, unsaved, renamed, renamed-changed (its content changed meanwhile) or renamed-but-lost.
on run argv
    set p to item 1 of argv
    set fname to item 2 of argv
    set newPath to item 3 of argv
    set newName to item 4 of argv
    set newExt to item 5 of argv
    if application "Microsoft Excel" is not running then return "missing"
    tell application "Microsoft Excel"
        with timeout of 300 seconds
            set located to my locate(p, fname, "", "", false)
            set d to item 1 of located
            if d is missing value then return "missing"
            if (saved of d) is false then return "unsaved"
            set fpBefore to my hdFingerprint(d)
            my saveInFormat(d, newPath, newExt)
            set d2 to missing value
            repeat with i from 1 to (count workbooks)
                set xd to workbook i
                try
                    if (name of xd) is newName then set d2 to xd
                end try
            end repeat
            if d2 is missing value then return "renamed-but-lost"
            if fpBefore is (my hdFingerprint(d2)) then return "renamed"
            return "renamed-changed"
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

-- Saves the workbook under target, in the file format its extension (newExt) says.
on saveInFormat(d, target, newExt)
    tell application "Microsoft Excel"
        if newExt is "xlsm" then
            save workbook as d filename target file format macro enabled XML file format add to most recently used list false
        else if newExt is "xls" then
            save workbook as d filename target file format Excel98to2004 file format add to most recently used list false
        else
            save workbook as d filename target file format Excel XML file format add to most recently used list false
        end if
    end tell
end saveInFormat
