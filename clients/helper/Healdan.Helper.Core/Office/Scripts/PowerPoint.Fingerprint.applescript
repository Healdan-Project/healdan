-- PowerPoint.Fingerprint: a text that summarises the presentation's content, to tell whether an edit landed between two
-- moments. Arguments: POSIX path, file name, mark, window name (each may be empty). Looks in that order, then at the
-- front window. Answers the text (it always starts with #), or nothing if no presentation was found.
on run argv
    set p to item 1 of argv
    set fname to item 2 of argv
    set wantedMark to item 3 of argv
    set windowName to item 4 of argv
    if application "Microsoft PowerPoint" is not running then return ""
    tell application "Microsoft PowerPoint"
        with timeout of 20 seconds
            set located to my locate(p, fname, wantedMark, windowName, true)
            set d to item 1 of located
            if d is missing value then return ""
            return my hdFingerprint(d)
        end timeout
    end tell
end run

-- Finds the presentation: by path (or file name), by the add-in's mark, by window name, then, if useFront, the front one.
-- Answers {the presentation, how it was found}, or {missing value, ""}.
on locate(p, fname, wantedMark, windowName, useFront)
    tell application "Microsoft PowerPoint"
        if p is not "" then
            repeat with i from 1 to (count presentations)
                set xd to presentation i
                try
                    if (my posixOf(full name of xd)) is p then return {xd, "path"}
                end try
                try
                    if (name of xd) is fname then return {xd, "path"}
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
