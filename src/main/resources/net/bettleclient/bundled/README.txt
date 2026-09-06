This directory is where the Fabric HUD mod jar is embedded at build time.

packaging/windows/build-exe.ps1 (and the GitHub Actions workflow) build the
Gradle module in bettle-mod/ and copy the resulting jar here as:

    bettle-hud-mod.jar

It is then packaged inside the launcher jar and read back at runtime by
net.bettleclient.install.ModInstaller, which copies it into an instance's
mods/ folder when the "Fabric + Bettle HUD" profile is selected.

The jar is a build output, so it is not committed. A launcher built without it
still works: ModInstaller.isModBundled() returns false, the UI says so, and the
Fabric profile installs Fabric Loader and Fabric API without the HUD mod.
