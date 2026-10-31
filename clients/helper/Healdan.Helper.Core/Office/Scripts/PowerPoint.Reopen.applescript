-- PowerPoint.Reopen: closes a saved presentation and opens it again from its file, so that PowerPoint saves it in
-- place afterwards. Answers reopened, unsaved (left open: closing it would lose changes) or missing. It may meet
-- Grant File Access, which waits for the user.
-- Arguments: the finder (1 POSIX path, 2 the same path with /private added or taken off, 3 file name, 4 Copies folder,
-- 5 the same with /private added or taken off, 6 mark, 7 window name; any may be empty)
on run argv
    set p to item 1 of argv
    set pTwin to item 2 of argv
    set fname to item 3 of argv
    set copiesFolder to item 4 of argv
    set copiesTwin to item 5 of argv
    if application "Microsoft PowerPoint" is not running then return "missing"
    tell application "Microsoft PowerPoint"
        with timeout of 300 seconds
            set located to my locate(p, pTwin, fname, copiesFolder, copiesTwin, "", "", false)
            set d to item 1 of located
            if d is missing value then return "missing"
            if (saved of d) is false then return "unsaved"
            close d saving no
            open (POSIX file p)
            return "reopened"
        end timeout
    end tell
end run

-- Finds the presentation (findDocument), but refuses one whose name another open presentation has (spec section
-- 5.2): the apps' scripting names a presentation by its name, so of two presentations with one name Word reached only
-- one, whichever path was asked for (the owner's by-hand run, 2026-10-31). Answers {the presentation, how it
-- was found}, {missing value, "ambiguous"} for a shared name, or {missing value, ""}.
on locate(p, pTwin, fname, copiesFolder, copiesTwin, wantedMark, windowName, useFront)
    set located to my findDocument(p, pTwin, fname, copiesFolder, copiesTwin, wantedMark, windowName, useFront)
    if (item 1 of located) is missing value then return located
    if my sharesItsName(item 1 of located) then return {missing value, "ambiguous"}
    return located
end locate

-- Finds the presentation, trying in this order (spec section 5.2: paths are compared resolved, or file names within Copies):
--   1. its exact path, in either spelling (pTwin is the same path with /private added or taken off);
--   2. its file name, but only for a presentation that is in the Copies folder (copiesFolder or copiesTwin), so a
--      presentation with the same name anywhere else is never taken for it;
--   3. the add-in's mark;
--   4. its window's name;
--   5. if useFront, the front presentation.
-- Answers {the presentation, how it was found}, or {missing value, ""}.
on findDocument(p, pTwin, fname, copiesFolder, copiesTwin, wantedMark, windowName, useFront)
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
end findDocument

-- Whether another open presentation has this one's name. AppleScript compares text ignoring case, so a name told apart only
-- by its case counts as shared too.
on sharesItsName(d)
    tell application "Microsoft PowerPoint"
        set wanted to name of d
        set seen to 0
        repeat with i from 1 to (count presentations)
            try
                if (name of presentation i) is wanted then set seen to seen + 1
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
