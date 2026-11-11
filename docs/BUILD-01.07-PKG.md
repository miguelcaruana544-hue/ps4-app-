# Building the 01.07 PKG (controller + Forge release)

This tree is Cyrene Launcher **source 1.04 / console version 01.07**. The PKG itself must be
assembled and signed with the PS4 toolchain, exactly as upstream does — the GitHub Actions
workflow in this repository does all of it on GitHub's machines, so you do not need a local SDK.

## What changed against 1.03 (01.06)

| Area | Files |
| --- | --- |
| Built-in controller (no Controlify needed) | `graphics-probe/launcher/compat/java/orbis/compat/ControllerBridge.java` (new), `ControllerAgent.java` (new), `mixin/ClientTickMixin.java`, `mixin/ClientTickMixin26.java`, `compat/resources/fabric.mod.json` |
| Controller injected per launch | `graphics-probe/launcher/java/orbis/launcher/LaunchPreparation.java` (extracts the compat JAR for Forge too, adds `-javaagent` and `-Dorbis.controller.*`) |
| Controller settings UI | `LauncherScreen.java` (settings rows 5-7), `LauncherConfig.java` (`controllerEnabled`/`Sensitivity`/`Deadzone`, saved in `launcher-settings.json`) |
| Forge loader | `VersionStore.java` (`FORGE` loader, installer-based install, embedded maven artifacts), `ForgeInstaller.java` (new: promotions/metadata version lookup, installer download+verify, processor steps, ForgeWrapper pinning) |
| Forge launch | `LaunchArguments.java` (version game arguments, e.g. `--launchTarget forgeclient`), ForgeWrapper 1.6.0 downloaded from its GitHub release (SHA-1 pinned in `ForgeInstaller`) |
| Forge mods + packs | `ModInspector.java` (`META-INF/mods.toml` / `neoforge.mods.toml`), `ModInstaller.java` (loader-aware messages), `ModrinthClient.java` (forge facets), `PackInstaller.java` (Forge packs accepted, NeoForge still blocked), `LauncherScreen.java` (loader colours, Modrinth loaders) |
| Version bump | `orbis-java-app/Makefile` (`VERSION ?= 01.07`), `tools/cyrene.py` default, `graphics-probe/tools/build_launcher.py` (Premain manifest on `orbis-compat.jar`) |
| New host tests | `graphics-probe/tests/test_controller_bridge.py`, `graphics-probe/tests/test_forge_support.py` |

No native (C) or runner changes were needed: every JVM option the new paths use
(`-javaagent:`, `-Dforgewrapper.*`, `--add-opens`, `--add-exports`, `-XX:...`) is accepted by the
runner's option rules, and the module-path options Forge declares for 1.17+ are replaced by
ForgeWrapper, so `runner_option_reserved` stays intact.

## Route A - GitHub Actions (recommended)

1. Push this tree to your fork/repo of CyreneLauncher (same layout: repository root =
   `orbis-launcher` checkout name for the CLI, the workflows handle it).
2. Repository secrets, same as upstream:
   - `ORBIS_MSA_CLIENT_ID` - your Microsoft app (client) ID for sign-in.
   - `CYRENE_RELEASES_TOKEN` - a token that can read the private port repositories
     (mesa-orbis, lwjgl-orbis, openal-soft-orbis, sdl-orbis) and their releases.
3. Run **Actions → Build Cyrene Launcher → Run workflow** with `version=01.07` and
   `draft_release=true`:

   ```bash
   gh workflow run release.yml -R <you>/CyreneLauncher -f version=01.07 -F draft_release=true
   gh run list -R <you>/CyreneLauncher --workflow release.yml
   ```

4. The package job uploads `cyrene-release-01.07` (the PKG, `cyrene-components.tar.gz`,
   checksums, manifests). Download it:

   ```bash
   gh run download RUN_ID -R <you>/CyreneLauncher --name cyrene-release-01.07 --dir dist
   ```

   or find it on the draft release. The first build compiles everything from source and takes a
   few hours; later builds reuse the cached components. Install the PKG on the jailbroken console
   the usual way (it carries the same content ID, so it installs over 01.06 and keeps
   `/data/orbis-jdk`).

## Route B - local Linux host (already has the toolchain)

```bash
git clone <this-repo>.git orbis-launcher
cd orbis-launcher
export ORBIS_SDK=/path/to/ps4-payload-sdk
export PS4_TOOLCHAIN=/path/to/PS4Toolchain
export BOOT_JDK17=/path/to/host-jdk17
export BOOT_JDK25=/path/to/host-jdk25
export PKGTOOL=/path/to/PkgTool.Core.dll
export ORBIS_MSA_CLIENT_ID=...        # or put it in untracked secrets.env
./orbis doctor
./orbis build                          # everything, first run takes hours
# or, reusing a previous release's verified components:
gh release download v01.06 -R CyreneLauncher/CyreneLauncher --pattern 'cyrene-components.tar.gz*' --dir /tmp/cc
./orbis import --archive /tmp/cc/cyrene-components.tar.gz
./orbis package --version 01.07
```

The PKG appears in `dist/`.

## Host checks you can run before the long build

```bash
# controller bridge against fake Minecraft in all three mapping sets + the Forge agent path
python3 graphics-probe/tests/test_controller_bridge.py
# Forge coordinate/mods.toml/modpack parsing
python3 graphics-probe/tests/test_forge_support.py
# pre-existing checks (TextEntry book writing)
python3 graphics-probe/tests/test_compat_text_entry.py
python3 -m unittest discover -s tools/tests
```

(`test_forge_support.py` wants the Gson 2.8.9 JAR and, like `test_launcher.py`, the LWJGL 3.2.2
audit JARs; set `GSON_JAR`/`LWJGL_AUDIT`/`ORBIS_STUBS` when running outside the prepared tree.)

## Console test plan (before publishing the draft)

1. Update over 01.06; confirm existing instances, accounts and downloads survive.
2. Versions page → Loader: Forge → install 1.20.1 (or 1.16.5). Watch the installer steps run;
   the second install of the same Forge version skips them.
3. Play the Forge instance; the controller should drive the game without any controller mod
   (Settings → Controller in Minecraft: On). Confirm hold-to-mine on L2 and menu navigation.
4. Install a Forge mod from the Mods screen (Modrinth, loaders=forge) and relaunch.
5. Install a Forge modpack from Modrinth (e.g. a 1.20.1 pack) as a new instance.
6. Regress a Fabric instance (controller must still work there via the compat mod, Controlify
   still takes precedence when installed) and a vanilla instance (no in-game controller there -
   expected, documented).

## Notes and limits

- Vanilla instances keep their old behaviour: the compatibility mod only loads under Fabric,
  Quilt and Forge, so pure vanilla has launcher-menu controller support but not in-game pad play.
- Forge processor steps run in a second JVM next to the launcher (`-Xmx768M`); big Forge versions
  (1.20.1+) need the console's free memory for a minute or two.
- Forge 26.1+ boots through its own `ForgeBootstrap` main class on the plain class path - no
  wrapper needed there.
- ForgeWrapper 1.6.0 is pinned by SHA-1 (`035a51fe6439792a61507630d89382f621da0f1f`, MIT licence;
  https://github.com/ZekerZhayard/ForgeWrapper) and downloaded from its GitHub release into the
  shared library cache at first Forge install.
