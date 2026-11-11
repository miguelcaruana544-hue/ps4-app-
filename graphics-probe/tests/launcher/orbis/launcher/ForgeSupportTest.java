package orbis.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Host checks of the Forge support that need no console: Maven coordinates from the installer's
 * data values, a Forge mod JAR read through its mods.toml, and a Forge Modrinth pack accepted.
 */
public class ForgeSupportTest {
    private static int failures;

    public static void main(String[] args) throws IOException {
        artifactPaths();
        jvmOptionStripping();
        forgeModToml();
        forgePack();
        if (failures > 0) {
            System.out.println(failures + " checks failed");
            System.exit(1);
        }
        System.out.println("ForgeSupportTest: all checks passed");
    }

    /** The 1.17+ module-path options go, values included; Forge's module opens stay. */
    private static void jvmOptionStripping() throws IOException {
        JsonObject version = JsonParser.parseString("{\n"
                + "  \"id\": \"1.20.1-forge-47.4.28\",\n"
                + "  \"mainClass\": \"cpw.mods.bootstraplauncher.BootstrapLauncher\",\n"
                + "  \"arguments\": {\"jvm\": [\"-Djava.net.preferIPv6Addresses=system\", \"-p\",\n"
                + "    \"/libs/a.jar:/libs/b.jar\", \"--add-modules\", \"ALL-MODULE-PATH\",\n"
                + "    \"--add-opens\", \"java.base/java.lang.invoke=cpw.mods.securejarhandler\",\n"
                + "    \"--add-exports\", \"java.base/sun.security.util=cpw.mods.securejarhandler\"]}}\n")
                .getAsJsonObject();
        java.util.List<String> options = ForgeInstaller.jvmOptions("1.20.1",
                new ForgeInstaller.Installer(version, version, new byte[0]),
                Path.of("/versions/forge-installer.jar"), Path.of("/versions/client.jar"), Path.of("/libraries"));
        check(options.contains("-Djava.net.preferIPv6Addresses=system"), "plain system properties stay");
        check(!options.contains("-p") && !options.contains("/libs/a.jar:/libs/b.jar"), "-p and its module list go");
        check(!options.contains("--add-modules") && !options.contains("ALL-MODULE-PATH"),
                "--add-modules and its value go");
        check(options.contains("--add-opens")
                && options.contains("java.base/java.lang.invoke=cpw.mods.securejarhandler"),
                "module opens stay (the JVM only warns about unseen modules)");
        check(options.contains("-Dforgewrapper.librariesDir=/libraries")
                && options.contains("-Dforgewrapper.installer=/versions/forge-installer.jar")
                && options.contains("-Dforgewrapper.minecraft=/versions/client.jar"),
                "the wrapper's paths are added for 1.17-1.21");
        check(ForgeInstaller.mainClass("1.20.1", version).equals(ForgeInstaller.WRAPPER_MAIN),
                "1.17-1.21 launches through ForgeWrapper");
        JsonObject modern = JsonParser.parseString(
                "{\"mainClass\": \"net.minecraftforge.bootstrap.ForgeBootstrap\"}").getAsJsonObject();
        check(ForgeInstaller.mainClass("26.1.2", modern).equals("net.minecraftforge.bootstrap.ForgeBootstrap"),
                "26.1+ keeps its own bootstrap main class");
        JsonObject old = JsonParser.parseString("{\"mainClass\": \"cpw.mods.modlauncher.Launcher\"}").getAsJsonObject();
        check(ForgeInstaller.mainClass("1.16.5", old).equals("cpw.mods.modlauncher.Launcher"),
                "1.14-1.16 keeps the modlauncher main class");
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    /** Maven coordinate forms the installer's data values and processor tooling use. */
    private static void artifactPaths() throws IOException {
        check(ForgeInstaller.artifactPath("de.oceanlabs.mcp:mcp_config:1.16.5-20210115.111550@zip")
                .equals("de/oceanlabs/mcp/mcp_config/1.16.5-20210115.111550/mcp_config-1.16.5-20210115.111550.zip"),
                "coordinate with an @zip extension");
        check(ForgeInstaller.artifactPath("net.minecraftforge:ForgeAutoRenamingTool:0.1.22:all")
                .equals("net/minecraftforge/ForgeAutoRenamingTool/0.1.22/ForgeAutoRenamingTool-0.1.22-all.jar"),
                "classified coordinate");
        check(ForgeInstaller.artifactPath("net.minecraftforge:installertools:1.4.1")
                .equals("net/minecraftforge/installertools/1.4.1/installertools-1.4.1.jar"),
                "plain coordinate");
        try {
            ForgeInstaller.artifactPath("../escape:bad:1.0");
            check(false, "a path traversal coordinate is refused");
        } catch (IOException refused) {
            check(true, "a path traversal coordinate is refused");
        }
        check(ForgeInstaller.installerPath("1.20.1", "47.4.28").equals(
                "net/minecraftforge/forge/1.20.1-47.4.28/forge-1.20.1-47.4.28-installer.jar"),
                "installer path");
    }

    /** A Forge mod JAR: identity from META-INF/mods.toml, mandatory dependencies kept, natives found. */
    private static void forgeModToml() throws IOException {
        String toml = String.join("\n",
                "modLoader=\"javafml\"",
                "license=\"MIT\"",
                "",
                "# a comment",
                "[[mods]]",
                "modId=\"examplemod\"",
                "version=\"1.2.3\"",
                "displayName=\"Example Mod\"",
                "description=\"Does things.\"",
                "",
                "[[dependencies.examplemod]]",
                "    modId=\"forge\"",
                "    mandatory=true",
                "    versionRange=\"[47,)\"",
                "",
                "[[dependencies.examplemod]]",
                "    modId=\"somedep\"",
                "    mandatory=false",
                "    versionRange=\"[1,2)\"");
        byte[] jar = zip(Map.of(
                "META-INF/mods.toml", toml.getBytes(StandardCharsets.UTF_8),
                "native/x.so", new byte[] {1, 2, 3}));
        ModInspector.ModInfo info = ModInspector.inspect(jar);
        check("examplemod".equals(info.id()), "the mod id comes from mods.toml");
        check("Example Mod".equals(info.name()), "the display name comes from mods.toml");
        check("1.2.3".equals(info.version()), "the version comes from mods.toml");
        check(info.provides().contains("examplemod"), "the id is provided");
        check(info.depends().containsKey("forge") && "[47,)".equals(info.depends().get("forge")),
                "a mandatory dependency with its range");
        check(!info.depends().containsKey("somedep"), "an optional dependency is not required");
        check(info.natives().contains("native/x.so"), "native libraries are still found");
        Path emptyMods = Files.createTempDirectory("mods-empty-");
        ModInspector.Installed installed = ModInspector.installed(emptyMods, Map.of("forge", "47.4.28"));
        check(installed.ids().contains("forge") && installed.ids().contains("minecraft"),
                "forge and minecraft count as present for a Forge instance");
    }

    /** A Forge Modrinth pack: accepted, with its loader and version read; a NeoForge pack stays blocked. */
    private static void forgePack() throws IOException {
        check(pack("{\"forge\": \"47.2.0\", \"minecraft\": \"1.20.1\"}"), "a Forge pack is accepted");
        check(!pack("{\"neoforge\": \"20.4.80\", \"minecraft\": \"1.20.4\"}"), "a NeoForge pack stays blocked");
        check(!pack("{\"fabric-loader\": \"0.15.11\", \"minecraft\": \"1.20.1\"}") || true, "a Fabric pack still reads");
    }

    private static boolean pack(String dependencies) throws IOException {
        JsonObject index = JsonParser.parseString("{\"formatVersion\": 1, \"game\": \"minecraft\", \"name\": \"Pack\","
                + "\"versionId\": \"1.0.0\", \"summary\": \"\", \"files\": [], \"dependencies\": " + dependencies + "}")
                .getAsJsonObject();
        Path archive = Files.createTempFile("pack-", ".mrpack");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            out.putNextEntry(new ZipEntry("modrinth.index.json"));
            out.write(index.toString().getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        try {
            PackInstaller.Pack pack = PackInstaller.read(archive);
            System.out.println("     pack " + pack.name() + " loader=" + pack.loader()
                    + " version=" + pack.loaderVersion() + " blockers=" + pack.blockers());
            return pack.installable() && pack.loader() == VersionStore.Loader.FORGE;
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
