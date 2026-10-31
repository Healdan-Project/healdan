-- Word.SetDocId: sets the document property Healdan Doc ID, updates the footer fields showing it and, if the
-- document was saved, leaves it saved. Arguments: POSIX path, file name, the value.
-- Answers ok, no-property (the document has no such property; none is made), or missing.
on run argv
    set p to item 1 of argv
    set fname to item 2 of argv
    set v to item 3 of argv
    if application "Microsoft Word" is not running then return "missing"
    tell application "Microsoft Word"
        with timeout of 20 seconds
            set located to my locate(p, fname, "", "", false)
            set d to item 1 of located
            if d is missing value then return "missing"
            try
                set value of custom document property "Healdan Doc ID" of d to v
            on error
                return "no-property"
            end try
            set wasSaved to saved of d
            repeat with k from 1 to (count sections of d)
                repeat with hf in {header footer primary, header footer first page, header footer even pages}
                    try
                        set fls to (get fields of text object of (get footer (section k of d) index (contents of hf)))
                        repeat with j from 1 to (count fls)
                            update field (item j of fls)
                        end repeat
                    end try
                end repeat
            end repeat
            if wasSaved then set saved of d to true
            return "ok"
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
