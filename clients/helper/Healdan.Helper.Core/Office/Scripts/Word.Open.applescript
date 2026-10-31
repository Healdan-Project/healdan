-- Word.Open: opens a file in Word, kept off Open Recent, or brings its window to the front if it is open already.
-- Argument: the POSIX path. It may meet Grant File Access, which waits for the user.
on run argv
    set p to item 1 of argv
    tell application "Microsoft Word"
        with timeout of 300 seconds
            activate
            try
                open file name p add to recent files false revert false
            on error errText number errNum
                -- A cancel, a timeout or a refusal of Automation isn't Word turning the path down: it goes as it came.
                if errNum is in {-128, -1712, -1743} then error errText number errNum
                open file name ((POSIX file p) as text) add to recent files false revert false
            end try
            return "ok"
        end timeout
    end tell
end run
