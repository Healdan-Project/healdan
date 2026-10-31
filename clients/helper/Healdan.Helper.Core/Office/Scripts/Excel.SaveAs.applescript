-- Excel.SaveAs: saves a workbook under a new path, kept off Open Recent where Excel allows.
-- Looks for the workbook as locate does, but never takes the front one, which would save another workbook as the copy.
-- Fingerprints the content just before the save and again after it, finding the workbook by its new name, as Rename does.
-- Answers how it was found (path, mark or window), character id 31, same, changed (its content changed during the save)
-- or lost (no workbook has the new name), character id 31, and the content's fingerprint text from just before the
-- save; or missing.
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
    set newName to my fileNameOf(newPath)
    if application "Microsoft Excel" is not running then return "missing"
    set sep to my separator()
    tell application "Microsoft Excel"
        with timeout of 300 seconds
            set located to my locate(p, pTwin, fname, copiesFolder, copiesTwin, wantedMark, windowName, false)
            set d to item 1 of located
            if d is missing value then return "missing"
            set howFound to item 2 of located
            set textBefore to my hdFingerprint(d)
            my saveInFormat(d, newPath, newExt)
            set d2 to missing value
            repeat with i from 1 to (count workbooks)
                set xd to workbook i
                try
                    if (name of xd) is newName then set d2 to xd
                end try
            end repeat
            set sameAfter to "lost"
            if d2 is not missing value then
                if my sameText(textBefore, my hdFingerprint(d2)) then
                    set sameAfter to "same"
                else
                    set sameAfter to "changed"
                end if
            end if
            return howFound & sep & sameAfter & sep & textBefore
        end timeout
    end tell
end run

-- Finds the workbook (findDocument), but refuses one whose name another open workbook has (spec section
-- 5.2): the apps' scripting names a workbook by its name, so of two workbooks with one name Word reached only
-- one, whichever path was asked for (the owner's by-hand run, 2026-10-31). Answers {the workbook, how it
-- was found}, {missing value, "ambiguous"} for a shared name, or {missing value, ""}.
on locate(p, pTwin, fname, copiesFolder, copiesTwin, wantedMark, windowName, useFront)
    set located to my findDocument(p, pTwin, fname, copiesFolder, copiesTwin, wantedMark, windowName, useFront)
    if (item 1 of located) is missing value then return located
    if my sharesItsName(item 1 of located) then return {missing value, "ambiguous"}
    return located
end locate

-- Finds the workbook, trying in this order (spec section 5.2: paths are compared resolved, or file names within Copies):
--   1. its exact path, in either spelling (pTwin is the same path with /private added or taken off);
--   2. its file name, but only for a workbook that is in the Copies folder (copiesFolder or copiesTwin), so a
--      workbook with the same name anywhere else is never taken for it;
--   3. the add-in's mark;
--   4. its window's name;
--   5. if useFront, the front workbook.
-- Answers {the workbook, how it was found}, or {missing value, ""}.
on findDocument(p, pTwin, fname, copiesFolder, copiesTwin, wantedMark, windowName, useFront)
    tell application "Microsoft Excel"
        if p is not "" then
            repeat with i from 1 to (count workbooks)
                set xd to workbook i
                try
                    set docPath to my posixOf(full name of xd)
                    if docPath is p then return {xd, "path"}
                    if pTwin is not "" and docPath is pTwin then return {xd, "path"}
                end try
            end repeat
            repeat with i from 1 to (count workbooks)
                set xd to workbook i
                try
                    if (name of xd) is fname then
                        set docPath to my posixOf(full name of xd)
                        if my isInFolder(docPath, copiesFolder) or my isInFolder(docPath, copiesTwin) then return {xd, "path"}
                    end if
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
end findDocument

-- Whether another open workbook has this one's name. AppleScript compares text ignoring case, so a name told apart only
-- by its case counts as shared too.
on sharesItsName(d)
    tell application "Microsoft Excel"
        set wanted to name of d
        set seen to 0
        repeat with i from 1 to (count workbooks)
            try
                if (name of workbook i) is wanted then set seen to seen + 1
            end try
        end repeat
    end tell
    return seen > 1
end sharesItsName

-- Is the file directly in the folder? Both are POSIX paths, and the folder has no closing slash.
on isInFolder(docPath, folderPath)
    if folderPath is "" then return false
    set folderPrefix to folderPath & "/"
    if (length of docPath) is less than or equal to (length of folderPrefix) then return false
    if docPath does not start with folderPrefix then return false
    return (text ((length of folderPrefix) + 1) thru -1 of docPath) does not contain "/"
end isInFolder

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

-- The file name at the end of a POSIX path.
on fileNameOf(posixPath)
    set AppleScript's text item delimiters to "/"
    set lastPart to last text item of posixPath
    set AppleScript's text item delimiters to ""
    return lastPart
end fileNameOf

-- Whether two texts are the same, case and all: AppleScript ignores case unless told not to.
on sameText(oneText, otherText)
    considering case
        return oneText is otherText
    end considering
end sameText

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
