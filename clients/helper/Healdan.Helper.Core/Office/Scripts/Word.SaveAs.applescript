-- Word.SaveAs: saves a document under a new path, kept off Open Recent where Word allows.
-- Looks for the document as locate does, ending at the front one. Answers how it was found (path, mark, window or front),
-- character id 31, and the content's fingerprint text from just before the save; or missing.
-- Arguments: the finder (1 POSIX path, 2 the same path with /private added or taken off, 3 file name, 4 Copies folder,
-- 5 the same with /private added or taken off, 6 mark, 7 window name; any may be empty),
-- 8 the new POSIX path, 9 the new extension (no dot)
on run argv
    set p to item 1 of argv
    set pTwin to item 2 of argv
    set fname to item 3 of argv
    set copiesFolder to item 4 of argv
    set copiesTwin to item 5 of argv
    set wantedMark to item 6 of argv
    set windowName to item 7 of argv
    set newPath to item 8 of argv
    set newExt to item 9 of argv
    if application "Microsoft Word" is not running then return "missing"
    set sep to my separator()
    tell application "Microsoft Word"
        with timeout of 300 seconds
            set located to my locate(p, pTwin, fname, copiesFolder, copiesTwin, wantedMark, windowName, true)
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

-- Finds the document, trying in this order (spec section 5.2: paths are compared resolved, or file names within Copies):
--   1. its exact path, in either spelling (pTwin is the same path with /private added or taken off);
--   2. its file name, but only for a document that is in the Copies folder (copiesFolder or copiesTwin), so a
--      document with the same name anywhere else is never taken for it;
--   3. the add-in's mark;
--   4. its window's name;
--   5. if useFront, the front document.
-- Answers {the document, how it was found}, or {missing value, ""}.
on locate(p, pTwin, fname, copiesFolder, copiesTwin, wantedMark, windowName, useFront)
    tell application "Microsoft Word"
        if p is not "" then
            repeat with i from 1 to (count documents)
                set xd to document i
                try
                    set docPath to posix full name of xd
                    if docPath is p then return {xd, "path"}
                    if pTwin is not "" and docPath is pTwin then return {xd, "path"}
                end try
            end repeat
            repeat with i from 1 to (count documents)
                set xd to document i
                try
                    if (name of xd) is fname then
                        set docPath to posix full name of xd
                        if my isInFolder(docPath, copiesFolder) or my isInFolder(docPath, copiesTwin) then return {xd, "path"}
                    end if
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

-- Is the file directly in the folder? Both are POSIX paths, and the folder has no closing slash.
on isInFolder(docPath, folderPath)
    if folderPath is "" then return false
    set folderPrefix to folderPath & "/"
    if (length of docPath) is less than or equal to (length of folderPrefix) then return false
    if docPath does not start with folderPrefix then return false
    return (text ((length of folderPrefix) + 1) thru -1 of docPath) does not contain "/"
end isInFolder

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
