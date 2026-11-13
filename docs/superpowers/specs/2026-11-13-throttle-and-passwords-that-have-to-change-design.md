# The sign-in throttle and a password that has to change: design

- **Date:** 2026-11-13
- **Status:** proposed, for the owner's review in its pull request.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal.

## 1. The fault

The sign-in throttle (M2 spec §5.7, `throttle.username.max` 5 and `throttle.username.wait` 15 minutes from
`SignInThrottleSeed`) counted the right password as a failure when it had to change. Seen in the owner's walkthrough of
the User app on a test stack on 2026-11-13:

1. Four sign-ins for `webuser` with a wrong password.
2. An administrator set `webuser`'s password in the Classic Interface, which marks it as one that has to change.
3. `webuser` signed in with that password. The chain refused it as `passwordexpired`, so that the sign-in page could
   ask for a new one, and the throttle counted it as the fifth failure: the username was blocked, and the alert went
   to the administrators.
4. The sign-in page's password change worked.
5. The next sign-in, with the password just chosen, was refused as `usernameblocked`. To the person, the new password
   didn't work.

At a firm this is the usual path: someone mistypes a few times, an administrator resets their password, and their
first sign-in with it blocks them for 15 minutes. An administrator's reset didn't lift a block they were already in
either. LogicalDOC behaves the same.

## 2. What the code does

- **The refusal doesn't say whether the password was right.** `AuthenticationChain.authenticate` runs the
  authenticators, then `applyDefaultValidations`, which runs `DefaultAuthenticator.validateUser` on the account the
  username names, whatever the authenticators decided. A refusal there replaces theirs (`errors.clear()`), so a wrong
  password's `WrongPasswordException` becomes the account check's. `validateUser` refuses with
  `PasswordExpiredException` when `UserDAO.isPasswordExpired` is true, which covers both kinds of password that has to
  change: the stored flag (`ld_passwordexpired`, set by an administrator's reset, **Lost your password?**, a new account
  and a password found too weak at sign-in) and age (**Password expires** set, `password.ttl` days after the last
  change). `DefaultAuthenticator` itself checks the password before `validateUser`, but the chain's second run doesn't.
  `healdan-api`'s `AuthResource` already knows: it checks the password itself before it answers `password-expired`.
- **It's counted.** `PasswordExpiredException` doesn't override `AuthenticationException.mustRecordFailure`, unlike
  `PasswordWeakException` and `PasswordAlreadyUsedException`, so `LoginThrottle.recordFailure` counts it against the
  username, the address and any key.
- **Setting a password leaves the counts.** Every route that gives an account a password ends in `UserDAO.store`
  (`HibernateUserDAO.store`), and nothing there or in the routes touches `ld_sequence`'s `loginfail-*` rows.

So the exception alone can't stop being counted: a wrong password for an account whose password has to change would
then never count, and such an account would be outside the throttle.

## 3. The change

### 3.1 A right password that has to change isn't counted (call)

In `SessionManager.newSession(username, password, key, client)`, a refusal goes to the fork-owned
`HealdanThrottle.failed` instead of `LoginThrottle.recordFailure`. When the refusal is a `PasswordExpiredException`,
no key came, and the password given is the account's, it isn't counted, and the counts are cleared as after a sign-in
(`HealdanThrottle.succeeded`: the username's, and the address's once its last failure is older than the wait). The
refusal is still logged as a failed login (`LoginThrottle.recordFailedLogin`), which the old sign-in page reads to say
why. Anything else goes to `recordFailure` as before.

- **The password check** is `DefaultAuthenticator`'s: the SHA-256 of the password given against the stored one, for
  the account `HealdanThrottle.account` names (the name before an impersonation's `>`, trimmed, in lower case when
  sign-in ignores case), found with `UserDAO.getUser` as `DefaultAuthenticator` finds it. A provider account has no
  password, so it never matches.
- **A wrong password** for such an account is counted, as before.
- **A sign-in with a key** is counted, as before: the chain's refusal may stand for the key (a second factor or an API
  key), which a right password says nothing about, and `succeeded` clears nothing for a sign-in with a key either.
- **No alert,** since nothing is counted.
- `newSession(apikey, client)`, the sign-in with only a key, is unchanged.

### 3.2 A new password clears its username's count (call)

The question was whether an administrator setting a person's password should clear that person's username count.
Yes, and so should every route that gives an account a new password, not only an administrator's:

| Route | Who can use it |
|---|---|
| An administrator's **Change Password** (`SecurityServiceImpl.changePassword` for another account) | an administrator with Access Control |
| The person's own **Change password** (the same method, for their own account) | someone signed in as them, with the current password |
| The sign-in page's password change (`LoginServiceImpl.changePassword`) | someone with the current password, for an account whose password has to change |
| **Lost your password?** (`PswRecovery`) | someone who can read the account's email |
| A new account (`SecurityServiceImpl.saveUser`) | an administrator with Access Control |
| Unlinking from a sign-in provider (`SignInAdmin`) | an administrator |

The reasons:

- **The count is of guesses at a password the account no longer has.** Once it's replaced, they say nothing about the
  new one, and guesses at the new one are counted afresh.
- **None of the routes is open to someone who is only guessing.** Each needs an administrator, the current password or
  the account's email.
- **A reset is usually the answer to a block.** Someone who can't sign in asks for a reset or uses **Lost your
  password?**, and the block they're in would refuse the new password for up to 15 minutes, which looks as if the
  reset didn't work. With §3.1 alone, only a reset given before the fifth mistake helps.
- **Only the username's count.** The address may be shared, and its count may be of attempts on other accounts, as
  `succeeded` keeps a recent one. The alert already sent and the failed logins in the account's history stay.
- **A new account** clears its username's count too: attempts on a name nobody had were guesses at no password.

`healdan-core`'s new `ThrottleUserListener` (a `UserListener` at position 115, after the device tokens' at 110) notes
in `beforeSave` whether the account's password differs from the stored one, as `TokenUserListener` does, a new account
counting as a change. In `afterSave` it calls `HealdanThrottle.passwordSet` with the stored username, which clears the
count `HealdanThrottle.account` names for it. A failure is logged and never stops the save. `HealdanThrottle` becomes a
public class for that one public method; its other methods stay package-private. No upstream file changes for §3.2.

## 4. Upstream edits

| File | Edit | Why |
|---|---|---|
| `logicaldoc-core/src/main/java/com/logicaldoc/core/security/SessionManager.java` | in `newSession(username, password, key, client)`'s `catch`, `HealdanThrottle.failed(account, password, key, client, e)` instead of `LoginThrottle.recordFailure(account, key, client, e)`, marked `Healdan:` | §3.1 |

Fork-owned files it changes: `logicaldoc-core`'s `HealdanThrottle.java` (public, with `failed` and `passwordSet`) and
`SessionManagerThrottleTest.java`, `healdan-core`'s `ThrottleUserListener.java` (new), its `plugin.xml` (the listener's
extension, `healdanThrottle`) and `ThrottleUserListenerTest.java` (new), and the docs pages in §6.

## 5. Tests

`SessionManagerThrottleTest`, already in `UPSTREAM_TESTS`:

- **the walkthrough:** four wrong passwords, a new password that has to change, a sign-in with it (refused as
  `passwordexpired`, and logged as that), then the person's own new password signs in. This one failed before the
  edit, with `usernameblocked`;
- the right password that has to change clears a count of four, for the stored flag and for a password that expired
  by age, and sends no alert;
- a wrong password for such an account is still counted, five of them block the username, and so is the right one
  with a key.

`ThrottleUserListenerTest` (`healdan-core`):

- an administrator's reset lifts a block, and the reset password then gets as far as `passwordexpired`;
- a reset clears the username's count and keeps the address's;
- a save that changes something else, or sets the same password again, keeps the count;
- a new account's username starts with no count;
- with `login.ignorecase`, the count cleared is the lower-case one the throttle keeps.

## 6. Docs

- The Admin Guide's Healdan Settings page, The Sign-In Throttle: both rules.
- The User Guide's Signing In (the blocked username) and Your Profile and Password (Five Wrong Passwords) pages: a new
  password lifts a block.
- The Developer Guide's API page, The Sign-In Throttle: `401 password-expired` with the right password isn't counted.

## 7. Later

- **Tell LogicalDOC,** with the case in §1. (call)
- **`AuthResource.passwordMatches`** checks the password the same way for its answer. It could ask `HealdanThrottle`,
  but that's another module's public surface for one line, so it stays. (call)
