---
title: System Information
weight: 7
---

**Administration → System → General** shows what's in Healdan and who's signed in. Beside it, under the System
menu, are Healdan's version and the installation ID; give both when you report a problem in
[Healdan's issues](https://github.com/Healdan-Project/healdan/issues).

## The Tabs

| Tab | What it shows |
|---|---|
| General | how much disk space each part of the repository takes, how many documents and pages are indexed, waiting or in the Trash, and how many folders there are. The [Statistics Collector](../scheduled-tasks/) counts them every 12 hours: **Last update** says when, and a new install shows nothing until it has first run |
| Sessions | everyone signed in to the web page or the web services, with when they signed in |
| Plugins | the parts Healdan is built from, with their versions |
| Run level | which of LogicalDOC's features run, such as saving history and running tasks. Leave it on **Default** |
| Log | the server's log, `dms.log`, with **Download the logs** |
| Environment | the settings the server runs with, the container's environment among them |

{{< callout type="warning" >}}
The **Environment** tab shows the database password from your `.env` file, in plain text. Don't share a screenshot
of it.
{{< /callout >}}

## Signing Someone Out

On the **Sessions** tab, right-click a session and choose **Kill**. Its person is signed out of it at once. That
doesn't sign out a device signed in through Healdan's API: see
[Healdan Settings](../../healdan-settings/#device-sign-ins) for that.

## Updates and Patches

**Updates and Patches**, under the System menu, isn't used in Healdan. To upgrade, follow
[Upgrading](../../../install/upgrading/).

## About Healdan

**About Healdan**, on the **?** menu in the top bar, shows the version, and credits LogicalDOC Community Edition,
which Healdan is based on.
