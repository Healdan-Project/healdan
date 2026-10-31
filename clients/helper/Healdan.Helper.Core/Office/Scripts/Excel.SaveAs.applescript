-- Excel.SaveAs: saves a workbook under a new path, kept off Open Recent where Excel allows.
-- Arguments: POSIX path, file name, mark, window name (each may be empty), the new POSIX path and the new extension
-- (no dot). Looks for the workbook in that order, then takes the front one. Answers how it was found (path, mark,
-- window or front), character id 31, and the content's fingerprint text from just before the save; or missing.
on run argv
    set p to item 1 of argv
    set fname to item 2 of argv
    set wantedMark to item 3 of argv
    set windowName to item 4 of argv
    set newPath to item 5 of argv
    set newExt to item 6 of argv
    if application "Microsoft Excel" is not running then return "missing"
    set sep to my separator()
    tell application "Microsoft Excel"
        with timeout of 300 seconds
            set located to my locate(p, fname, wantedMark, windowName, true)
            set d to item 1 of located
            if d is missing value then return "missing"
            set howFound to item 2 of located
            set textBefore to my hdFingerprint(d)
            my saveInFormat(d, newPath, newExt)
            return howFound & sep & textBefore
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

on separator()
    return character id 31
end separator

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
