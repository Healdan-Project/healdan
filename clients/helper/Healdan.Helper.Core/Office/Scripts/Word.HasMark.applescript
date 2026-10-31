-- Word.HasMark: does an open document carry the add-in's mark (the custom document property HealdanMark)?
-- Answers yes or no.
-- Arguments: the finder (1 POSIX path, 2 the same path with /private added or taken off, 3 file name, 4 Copies folder,
-- 5 the same with /private added or taken off, 6 mark, 7 window name; any may be empty);
-- only the mark is used
on run argv
    set wantedMark to item 6 of argv
    if application "Microsoft Word" is not running then return "no"
    tell application "Microsoft Word"
        with timeout of 20 seconds
            set located to my locate("", "", "", "", "", wantedMark, "", false)
            if (item 1 of located) is missing value then return "no"
            return "yes"
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
