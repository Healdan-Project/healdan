-- PowerPoint.SaveAs: saves a presentation under a new path, kept off Open Recent where PowerPoint allows.
-- Looks for the presentation as locate does, but never takes the front one, which would save another presentation as the copy.
-- Fingerprints the content just before the save and again after it, finding the presentation by its new name, as Rename does.
-- Answers how it was found (path, mark or window), character id 31, same, changed (its content changed during the save)
-- or lost (no presentation has the new name), character id 31, and the content's fingerprint text from just before the
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
    if application "Microsoft PowerPoint" is not running then return "missing"
    set sep to my separator()
    tell application "Microsoft PowerPoint"
        with timeout of 300 seconds
            set located to my locate(p, pTwin, fname, copiesFolder, copiesTwin, wantedMark, windowName, false)
            set d to item 1 of located
            if d is missing value then return "missing"
            set howFound to item 2 of located
            set textBefore to my hdFingerprint(d)
            my saveInFormat(d, newPath, newExt)
            set d2 to missing value
            repeat with i from 1 to (count presentations)
                set xd to presentation i
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

-- Finds the presentation, trying in this order (spec section 5.2: paths are compared resolved, or file names within Copies):
--   1. its exact path, in either spelling (pTwin is the same path with /private added or taken off);
--   2. its file name, but only for a presentation that is in the Copies folder (copiesFolder or copiesTwin), so a
--      presentation with the same name anywhere else is never taken for it;
--   3. the add-in's mark;
--   4. its window's name;
--   5. if useFront, the front presentation.
-- Answers {the presentation, how it was found}, or {missing value, ""}.
on locate(p, pTwin, fname, copiesFolder, copiesTwin, wantedMark, windowName, useFront)
    tell application "Microsoft PowerPoint"
        if p is not "" then
            repeat with i from 1 to (count presentations)
                set xd to presentation i
                try
                    set docPath to my posixOf(full name of xd)
                    if docPath is p then return {xd, "path"}
                    if pTwin is not "" and docPath is pTwin then return {xd, "path"}
                end try
            end repeat
            repeat with i from 1 to (count presentations)
                set xd to presentation i
                try
                    if (name of xd) is fname then
                        set docPath to my posixOf(full name of xd)
                        if my isInFolder(docPath, copiesFolder) or my isInFolder(docPath, copiesTwin) then return {xd, "path"}
                    end if
                end try
            end repeat
        end if
        if wantedMark is not "" then
            repeat with i from 1 to (count presentations)
                set xd to presentation i
                try
                    if (value of custom document property "HealdanMark" of xd) is wantedMark then return {xd, "mark"}
                end try
            end repeat
        end if
        if windowName is not "" then
            repeat with i from 1 to (count presentations)
                set xd to presentation i
                try
                    if (name of xd) is windowName then return {xd, "window"}
                end try
            end repeat
        end if
        if useFront then
            try
                return {active presentation, "front"}
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
    tell application "Microsoft PowerPoint"
        set acc to "#"
        repeat with k from 1 to (count slides of d)
            set sl to slide k of d
            repeat with j from 1 to (count shapes of sl)
                try
                    set acc to acc & (content of text range of text frame of (shape j of sl)) & "|"
                end try
            end repeat
        end repeat
        return acc
    end tell
end hdFingerprint

-- Saves the presentation under target, in the file format its extension (newExt) says.
on saveInFormat(d, target, newExt)
    tell application "Microsoft PowerPoint"
        if newExt is "pptm" then
            save d in (POSIX file target) as save as Open XML presentation macro enabled
        else if newExt is "ppt" then
            save d in (POSIX file target) as save as presentation
        else
            save d in (POSIX file target) as save as Open XML presentation
        end if
    end tell
end saveInFormat
