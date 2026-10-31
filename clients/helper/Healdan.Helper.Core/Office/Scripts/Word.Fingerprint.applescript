-- Word.Fingerprint: a text that summarises the document's content, to tell whether an edit landed between two
-- moments. Arguments: POSIX path, file name, mark, window name (each may be empty). Looks in that order, then at the
-- front window. Answers the text (it always starts with #), or nothing if no document was found.
on run argv
    set p to item 1 of argv
    set fname to item 2 of argv
    set wantedMark to item 3 of argv
    set windowName to item 4 of argv
    if application "Microsoft Word" is not running then return ""
    tell application "Microsoft Word"
        with timeout of 20 seconds
            set located to my locate(p, fname, wantedMark, windowName, true)
            set d to item 1 of located
            if d is missing value then return ""
            return my hdFingerprint(d)
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

-- The text that fingerprints a document: the same in every script that takes one.
on hdFingerprint(d)
    tell application "Microsoft Word"
        return "#" & (content of text object of d)
    end tell
end hdFingerprint
