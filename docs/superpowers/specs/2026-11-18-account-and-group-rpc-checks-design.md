# Who may change accounts and groups through the Classic Interface's RPCs: design

- **Date:** 2026-11-18
- **Status:** proposed, for the owner's review.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal.

## 1. The rules

`SecurityServiceImpl` serves the Classic Interface's account and group screens. These are the rules its RPCs now hold,
each checked on the server whatever the screen sends.

**Changing a password** (call)

- The RPC needs a session. The person changing the password is whoever is signed in. The `requestorUserId` the call
  carries is ignored.
- Your own password needs your current one.
- Anyone else's needs **Access Control**, as before, and no current password.
- Someone with **Access Control** can also set their own from the Users screen, whose dialog has no current-password
  field. Such a person can already change every other account, so asking them for it would protect nothing.
- A password set for someone else has to change at their next sign-in, as before. That now follows who is signed in.

**The sign-in page** keeps its own path. Someone whose password has to change can't have a session yet, so
`LoginServiceImpl.changePassword`, after its own checks, calls `SecurityServiceImpl.changePasswordAtSignIn`. That method
isn't in the `SecurityService` interface, so no RPC reaches it, and it needs the current password.

**Saving an account** (`saveUser`) needs **Access Control**, for every account, the caller's own too. Its only caller is
the Users screen. A person changes their own details through **Account → Profile**, whose `saveProfile` takes only the
profile's fields. So groups, enabled, expiry, IP lists, impersonators, type, quotas and the password flags are never
changed by the account's owner without that menu. (call)

**Saving a group and removing its members** (`saveGroup`, `removeFromGroup`) need **Access Control**, as `getGroup`,
`deleteGroup` and `addUserToGroup` already did. They needed only **Administration**. Their only callers are the Groups
screen's panels, which that menu's holders reach. (call)

## 2. Upstream edits

| File | Edit | Why |
|---|---|---|
| `logicaldoc-webapp/src/main/java/com/logicaldoc/web/service/SecurityServiceImpl.java` | `changePassword` takes its requestor from the session, and its body moves to a private overload that `changePasswordAtSignIn` shares; the current password is required without Access Control; the expiry flag follows the requestor | §1, changing a password |
| same | `saveUser` checks Access Control, and `disallowEditingOfOtherUsers` goes | §1, saving an account |
| same | `saveGroup` and `removeFromGroup` check Access Control | §1, groups |
| `logicaldoc-webapp/src/main/java/com/logicaldoc/web/service/LoginServiceImpl.java` | `changePassword` ends in `changePasswordAtSignIn` | §1, the sign-in page |

## 3. Tests

`SecurityServiceImplAuthorizationTest` and `LoginServiceImplTest`, both already in CI's `UPSTREAM_TESTS`, cover the
rules: each refusal changes nothing, and each allowed route still works, including the sign-in page's change, a person's
own change with the current password, an administrator's reset, and **Account → Profile**.

## 4. Later

- **Access Control is equal to administrator.** Its holder can add anyone to `admin`. The Admin app's design should say
  so wherever the menu can be given to a group. (call)
