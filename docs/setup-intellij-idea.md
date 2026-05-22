Setup JetBrains IntelliJ IDEA
=============================

At this point the best IDE for Kotlin development is IntelliJ IDEA. So that is what we use.

There is an alternative being worked on: a proper [Kotlin language server by JetBrains](https://github.com/Kotlin/kotlin-lsp).
That would allow other IDEs (VSCode/Cursor, Emacs, Zed, etc.) to have great Kotlin support, but it is in early stages.
This guide focuses only on IntelliJ IDEA.


## Which edition

The Community Edition will do. The Ultimate Edition is noticeably better because:

* The bundled [Graphql plugin](https://plugins.jetbrains.com/plugin/8097-graphql) ships pre-installed — useful since we have a lot of `.graphql` files.
* The subscription comes with some out-of-the-box AI capabilities (multi-line autocomplete) that save time.

The Community Edition can install the Graphql plugin manually.


## How to install

A good way to install IntelliJ is with the [JetBrains Toolbox](https://www.jetbrains.com/toolbox-app).

After installing it you may want to change these settings (click the gear icon in the top-right of the app):

* `Settings >` log into your JetBrains account (if you want to use a license).
* `Settings > Appearance and Behavior` set "Launch Toolbox app at System Startup" to off, to conserve system resources.
* `Settings > Tools` set "Tools installation location" to `/opt/jetbrains`, not to put 3rd party applications in your home directory.

Then use the app to install "IntelliJ IDEA Ultimate" (or Community).


## First run

When you first start IntelliJ IDEA you are shown a dialog to start/open/import a project.

Use *Open* and point it to the root of this repository to load the project.

Trying to run the application will result in a "no JDK selected" error.
The toolchain version is in the `build.gradle.kts` file (currently **JDK 25**, Amazon Corretto is recommended for AWS container parity).
Let IntelliJ install that version (in `~/.jdks/`).

To use that JDK version also from the command line (Claude Code and humans both like to run Gradle from the CLI),
update the "alternatives" link (make sure the wildcard resolves to exactly one JDK folder):

```sh
sudo ln -f -s /home/$USER/.jdks/corretto-*/bin/java /etc/alternatives/java
```


## Setting up IntelliJ IDEA

These instructions are for the Ultimate Edition, but mostly work for the Community Edition too.

* `File > Settings > Plugins` — switch off plugins you don't expect to use (speeds up the IDE a lot). Ensure the **Graphql** plugin is enabled.
* `Help > Change Memory Settings` — set it to `4096` (also speeds up the IDE).
* Right-click the status bar and enable `Memory Indicator` — always good to know if something is running out of hand.


## Graphql plugin configuration

The bundled Graphql plugin reads schema location from `graphql.config.yml` (if present at the repo root or `src/`).
Our `graphqlIntrospectSchema` Gradle task writes the schema to `src/graphql-schema/schema.graphql` so the plugin can find it.

If schema-aware completion stops working after a Shopify API version bump:

1. Run `./gradlew graphqlIntrospectSchema graphqlGenerateClient` from the terminal.
2. In IntelliJ, restart the Graphql Language Service: `View > Tool Windows > Graphql`, then click the refresh icon.


## Troubleshooting

When command line builds work but IntelliJ does not behave as expected (red underlines on valid code, asking for SSH key repeatedly, weird Git behavior), try in order of increasing time-to-get-back-to-work:

1. In the Gradle tab (on the right), click the "Reload All Gradle Projects" button.
2. `File > Invalidate Caches / Restart` then pick `Invalidate and Restart` with no checkboxes checked.
3. Same, with *the top two checkboxes* checked.
4. Same, with *all checkboxes* checked.
5. Restart your computer.
6. `Build > Rebuild project` (takes a while).
7. Clean project-specific Gradle and build caches: `rm -rf .gradle build` then start IntelliJ again.
8. Make a clean checkout of the repository (you may need to follow the steps of this README again), copy over `.env` from the old checkout.
9. Step 8 + reinstall IntelliJ (make sure to [remove all IntelliJ caches/configs](https://www.jetbrains.com/help/idea/uninstall.html#linux) between removing and installing).

Some problems may be due to the graphics backend. On Linux you can switch between X.org and Wayland on the login screen — if you encounter graphics problems (flickering, black screens, scaling issues), try "the other one".

Common errors:

* First startup of IntelliJ after installation on OpenSUSE yields `Unable to load native GTK libraries`.
  Fix: `sudo zypper install libgthread-2_0-0` and restart the IDE.

* Trying to run/debug in IntelliJ yields `Error: Could not find or load main class …`.
  This may go away once the project is fully indexed. If waiting does not help,
  revert the `.idea/` folder to only contain files checked into Git:
  `rm -rf .idea/ && git checkout .idea/`, then restart the IDE.

* `Failed to bind to: /0.0.0.0:8080` and `Address already in use` — an instance of the web app is already running.
  Kill the process listening on port 8080 with `fuser -k 8080/tcp`.
