---
title: WebDAV, CMIS and Web Services
weight: 5
---

Besides the web page and Healdan's own [API](../../../developer/api/), LogicalDOC has three other ways in: WebDAV,
CMIS, and its SOAP and REST web services. They're set under **Administration → Settings → Protocols**, one tab
each. All three are on to begin with.

## Their Addresses

For a server at `https://healdan.example`:

| Protocol | Address | Signs in with |
|---|---|---|
| WebDAV | `https://healdan.example/webdav/store` | a username and password |
| CMIS 1.0, AtomPub binding | `https://healdan.example/service/cmis` | a username and password |
| SOAP web services | `https://healdan.example/services` | a username and password, or a LogicalDOC API key |
| REST web services | `https://healdan.example/services/rest` | a username and password, or a LogicalDOC API key |

Each tab shows its address as your server has it.

## Turning One Off

Clear the tab's **Enabled** and click **Save**. If nobody at your firm uses a protocol, turn it off: it's one less
way in. The change takes effect at once, though the screen says the application has to be restarted.

## The Other Settings

| Tab | Setting | What it does |
|---|---|---|
| Web Service | Enable Basic Authentication | lets each web service call sign in with a username and password. On to begin with |
| Web Service | Record calls, Record calls payload | keeps a record of each call, and of what it sent. Off to begin with. See [Recording Calls](#recording-calls) |
| Web Service | Time to live | how many days a recorded call is kept. 90 to begin with |
| CMIS | Changelog | lets CMIS clients ask what has changed since they last looked. On to begin with |
| CMIS | Max items | the most items one CMIS answer lists. 200 to begin with |
| WebDAV | Depth | how many folder levels one WebDAV listing goes down. 1 to begin with |
| WebDAV | Use cache | keeps folder sizes in memory, for WebDAV listings. On to begin with |

## Recording Calls

To see the web service calls in the **API Calls** tab and the [API Calls report](../reports/), two switches must
be on:

1. **Record calls**, on the **Web Service** tab;
2. **Save API calls** for the **Default** run level, on the **Run level** tab of **Administration → System →
   General**. It's off to begin with.

Calls to Healdan's own API aren't recorded there.

## Healdan's Rules Apply on Every Route

Whichever way a change comes in, Healdan checks it as it checks the web page's:

- **Documents go only inside a matter's folders,** and only admins change a workspace's top-level folders. See
  [Top-Level Folders](../../top-level-folders/).
- **Permissions are checked on everything a request touches,** not only the folder it names: deleting or copying a
  folder needs the permission on each document and folder inside it.
- **The sign-in throttle** counts wrong passwords on WebDAV, CMIS and the web services as it does on the sign-in
  page. See [the sign-in throttle](../../healdan-settings/#the-sign-in-throttle).

## Device Tokens Work Only in the API

The Healdan Helper signs in to Healdan's own API, under `/api`, and keeps a device token instead of the password.
WebDAV, CMIS and the web services refuse a device token. The other way round, once a device has signed in, the API
takes nothing but its device token: not a LogicalDOC API key, a password on each request, or the web page's
session. A LogicalDOC API key, which each person makes under **Account** in the top bar, **Security → API Keys**,
works only in the web services.

## WebDAV and Office Apps

LogicalDOC's WebDAV can't lock a file the way Word, LibreOffice and macOS's Finder expect. Expect them to open
documents read-only, or to fail to save. To edit documents in LibreOffice, Word, Excel or PowerPoint on a Mac, use the
[Healdan Helper](../../../user/office/).
