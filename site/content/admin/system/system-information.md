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
| Environment | the settings the server runs with, the container's environment among them, with secrets masked |

The **Environment** tab shows `********` in place of anything that looks secret: a variable or setting whose name
has *password*, *secret*, *token*, *credential*, *API key*, *access key*, *private key*, *security key* or
*licence key* in it, such as `DB_PASSWORD`, a `-D` option named that way inside another value, such as
`CATALINA_OPTS`, and the password in an address, such as a proxy's. An empty value still shows as empty, so you can
see whether it's set.

{{< callout type="warning" >}}
A secret under some other name still shows in plain text. Check a screenshot of the **Environment** tab before you
share it.
{{< /callout >}}

## Downloading the Logs

**Download the logs**, on the **Log** tab, makes a zip for whoever helps you with a problem. It holds:

- the server's logs and Tomcat's
- the server's settings, as it runs with them and as `context.properties` and the other files in its
  configuration folder hold them, and Tomcat's settings
- the environment, as the **Environment** tab lists it
- the database's details, the updates and patches applied, the statistics, and the legal notices people have
  confirmed

The zip masks what the **Environment** tab masks, in the environment and in every `.properties` file, such as the
database, email and proxy passwords in `context.properties`. Those files go in rewritten with their settings in
alphabetical order and without their comments.

{{< callout type="warning" >}}
The logs and the other settings files, Tomcat's `server.xml` among them, go in as they are, and so does a secret
under some other name. Look through the zip before you send it.
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
