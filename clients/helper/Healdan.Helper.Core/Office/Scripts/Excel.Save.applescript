-- Excel.Save: saves the workbook where it is. Arguments: its POSIX path, its file name. Answers saved or missing.
on run argv
    set p to item 1 of argv
    set fname to item 2 of argv
    if application "Microsoft Excel" is not running then return "missing"
    tell application "Microsoft Excel"
        with timeout of 20 seconds
            set located to my locate(p, fname, "", "", false)
            set d to item 1 of located
            if d is missing value then return "missing"
            save d
            return "saved"
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
