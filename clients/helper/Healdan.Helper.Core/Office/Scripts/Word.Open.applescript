-- Word.Open: opens a file in Word, kept off Open Recent, or brings its window to the front if it is open already.
-- Argument: the POSIX path. It may meet Grant File Access, which waits for the user.
on run argv
    set p to item 1 of argv
    tell application "Microsoft Word"
        with timeout of 300 seconds
            activate
            try
                open file name p add to recent files false revert false
            on error
                open file name ((POSIX file p) as text) add to recent files false revert false
            end try
            return "ok"
        end timeout
    end tell
end run
