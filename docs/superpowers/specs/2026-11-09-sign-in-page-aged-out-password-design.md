# A password that expired by age, changed on the sign-in page: design

- **Date:** 2026-11-09
- **Status:** approved (owner, 2026-11-09), as one small upstream edit and a test.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal.

## 1. The change

The sign-in page offers a person whose password has to change a form to change it before they're in. The form calls
CE's `LoginServiceImpl.changePassword`. Upstream's `0490ffedc` (in the sync of 2026-11-09) has it check, before it
changes anything, that the current password was given, that the account exists, and that the account's stored
`passwordExpired` flag is set. `SecurityServiceImpl.changePassword` then checks the current password against the
account's.

A password can have to change in two ways. An administrator, a password recovery or a too-weak password at sign-in sets
the flag. A password that expires by age (an account with **Password expires** set, `password.ttl` days after its last
change, 90 by default) doesn't: sign-in works the expiry out with `UserDAO.isPasswordExpired` and refuses it, and
nothing stores the account, so the flag stays unset. The page's `getUser` works it out the same way and offers the
form, which upstream's check then refuses, so that person can't change their password or sign in until an
administrator steps in. The User Guide's Your Profile and Password page says Healdan asks for a new password then.

**Upstream's check works the expiry out as sign-in does.** (owner) It asks `UserDAO.isPasswordExpired` with the
account's username, which is true when the flag is set or the password has expired by age. Upstream's other checks are
unchanged.

## 2. Upstream edits

| File | Edit | Why |
|---|---|---|
| `logicaldoc-webapp/src/main/java/com/logicaldoc/web/service/LoginServiceImpl.java` | `changePassword` asks `UserDAO.isPasswordExpired(username)` instead of reading the stored flag, marked `Healdan:` | A password that expired by age can be changed on the sign-in page, as sign-in asks for |

## 3. Tests

`LoginServiceImplTest` (in `UPSTREAM_TESTS`) calls the service as the sign-in page does, with no session:

- with no current password, an empty one or a wrong one, nothing changes, nor for an account that doesn't exist;
- with the right one, nothing changes while the password doesn't have to;
- an account marked to change its password can change it;
- a password that expired by age, with the flag unset, can be changed. This one failed before the edit.

A refusal is either upstream's `ServerException` or an answer other than `0`. CE's `SecurityServiceImplTest` still
passes.

## 4. Later

- **Report the edit upstream,** with the case in §1. (call)
- **When the account lookup fails,** upstream's check logs and goes on to the change, which still checks the current
  password. Mention it in the same report. (call)
