package orbis.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Installs Minecraft Forge from its installer JAR, the way the official installer and PojavLauncher
 * do, so every Forge release the launcher can run installs on the console:
 *
 * <ul>
 * <li>The installer is downloaded from Forge's Maven and read in place: it carries the launch
 *     profile (version.json) and the install profile with the processor steps and their data.</li>
 * <li>Its libraries are downloaded into the shared library cache; artifacts with an empty URL come
 *     out of the installer itself (its maven/ folder).</li>
 * <li>The client-side processor steps (mappings, jarsplitter, renaming, binary patcher) run with the
 *     launcher's own Java, producing the patched client FML looks for in the library cache. Each
 *     step checks its recorded output hash first, so an interrupted install resumes and a finished
 *     one does no work.</li>
 * <li>Minecraft 1.17 to 1.21 launch through ForgeWrapper (MIT, pinned), so no module-path JVM
 *     options are needed and the runner's option rules stay intact. 1.14 to 1.16 and 26.1+ launch
 *     through Forge's own main class on the plain class path.</li>
 * </ul>
 */
final class ForgeInstaller {
    static final String MAVEN = "https://maven.minecraftforge.net/";
    private static final String PROMOTIONS_URL =
            "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    private static final String METADATA_URL = MAVEN + "net/minecraftforge/forge/maven-metadata.xml";

    /** ForgeWrapper 1.6.0, MIT (https://github.com/ZekerZhayard/ForgeWrapper), pinned by SHA-1. */
    static final String WRAPPER_VERSION = "1.6.0";
    static final String WRAPPER_URL = "https://github.com/ZekerZhayard/ForgeWrapper/releases/download/"
            + WRAPPER_VERSION + "/ForgeWrapper-" + WRAPPER_VERSION + ".jar";
    static final String WRAPPER_PATH = "io/github/zekerzhayard/forgewrapper/" + WRAPPER_VERSION
            + "/forgewrapper-" + WRAPPER_VERSION + ".jar";
    static final String WRAPPER_SHA1 = "035a51fe6439792a61507630d89382f621da0f1f";
    static final long WRAPPER_SIZE = 28679;
    static final String WRAPPER_MAIN = "io.github.zekerzhayard.forgewrapper.installer.Main";

    /** Forge 1.17 to 1.21 declare BootstrapLauncher with module-path options, replaced by the wrapper. */
    static final String BOOTSTRAP_LAUNCHER_MAIN = "cpw.mods.bootstraplauncher.BootstrapLauncher";
    static final String MODLAUNCHER_MAIN = "cpw.mods.modlauncher.Launcher";

    private static final long MAX_INSTALLER = 64L << 20;
    private static final Pattern COORDINATE = Pattern.compile("[A-Za-z0-9._+-]+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[A-Z_a-z]+}");

    /** The read installer: the launch profile, the install profile and the raw bytes. */
    record Installer(JsonObject version, JsonObject profile, byte[] bytes) {}

    private ForgeInstaller() {}

    // ------------------------------------------------------------------ versions

    /**
     * The Forge version for a Minecraft release: the requested one (validating it exists), or the
     * newest recommended, or the newest known. Promotions cover every release; the Maven metadata
     * answers for the rest.
     */
    static String version(String minecraft, String requested) throws IOException {
        List<String> metadata = null;
        if (requested != null) {
            String name = requested.startsWith(minecraft + "-")
                    ? requested.substring(minecraft.length() + 1) : requested;
            if (!name.matches("[0-9][0-9A-Za-z.+-]{0,31}"))
                throw new IOException("Unexpected Forge version " + requested);
            JsonObject promos = promos();
            if (!text(promos, minecraft + "-" + name).isEmpty()) return name;
            metadata = metadataVersions();
            if (metadata.contains(minecraft + "-" + name)) return name;
            throw new IOException("Forge " + name + " does not exist for Minecraft " + minecraft);
        }
        JsonObject promos = promos();
        String recommended = text(promos, minecraft + "-recommended");
        if (!recommended.isEmpty()) return recommended;
        String latest = text(promos, minecraft + "-latest");
        if (!latest.isEmpty()) return latest;
        String best = null;
        for (String candidate : metadataVersions())
            if (candidate.startsWith(minecraft + "-")
                    && (best == null || newer(candidate, best))) best = candidate;
        if (best == null) throw new IOException("No Forge release exists for Minecraft " + minecraft);
        return best.substring(minecraft.length() + 1);
    }

    private static JsonObject promos() throws IOException {
        return JsonParser.parseString(new String(VersionStore.fetch(PROMOTIONS_URL, 4L << 20),
                StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("promos");
    }

    private static List<String> metadataVersions() throws IOException {
        String xml = new String(VersionStore.fetch(METADATA_URL, 4L << 20), StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Compares the Forge build numbers after the Minecraft version ("47.4.28"). */
    private static boolean newer(String a, String b) {
        return VersionStore.compare(VersionStore.parts(buildOf(a)), VersionStore.parts(buildOf(b))) > 0;
    }

    private static String buildOf(String coordinate) {
        int dash = coordinate.indexOf('-');
        return dash < 0 ? coordinate : coordinate.substring(dash + 1);
    }

    /** The full Forge coordinate ("1.20.1-47.4.28") and its installer JAR's Maven path. */
    static String coordinate(String minecraft, String forge) {
        return minecraft + "-" + forge;
    }

    static String installerPath(String minecraft, String forge) {
        return "net/minecraftforge/forge/" + minecraft + "-" + forge + "/forge-" + minecraft + "-"
                + forge + "-installer.jar";
    }

    // ------------------------------------------------------------------ installer

    /** Downloads and reads the installer; its SHA-1 comes from Forge's Maven next to the JAR. */
    static Installer download(String minecraft, String forge) throws IOException {
        String url = MAVEN + installerPath(minecraft, forge);
        byte[] bytes = VersionStore.fetch(url, MAX_INSTALLER);
        String sha1 = new String(VersionStore.fetch(url + ".sha1", 1024), StandardCharsets.UTF_8)
                .trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (!VersionStore.sha1(bytes).equals(sha1))
            throw new IOException("The Forge installer does not match its published SHA-1");
        JsonObject version = null, profile = null;
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.getName().equals("version.json"))
                    version = JsonParser.parseString(read(zip)).getAsJsonObject();
                else if (entry.getName().equals("install_profile.json"))
                    profile = JsonParser.parseString(read(zip)).getAsJsonObject();
            }
        }
        if (version == null || profile == null)
            throw new IOException("The Forge installer holds no version or install profile");
        return new Installer(version, profile, bytes);
    }

    private static String read(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        for (int n; (n = zip.read(buffer)) > 0; ) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8);
    }

    /** A file embedded in the installer (data/client.lzma, maven/&lt;path&gt;), or null. */
    static byte[] embedded(Path installerFile, String entry) throws IOException {
        try (ZipFile zip = new ZipFile(installerFile.toFile())) {
            ZipEntry e = zip.getEntry(entry);
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
                return out.toByteArray();
            }
        }
    }

    // ------------------------------------------------------------------ launch pieces

    /** The main class the installed version runs: the wrapper for 1.17-1.21, else Forge's own. */
    static String mainClass(String minecraft, JsonObject version) {
        int[] v = VersionStore.parts(minecraft);
        if (VersionStore.compare(v, new int[] {1, 17}) >= 0 && VersionStore.compare(v, new int[] {26}) < 0)
            return WRAPPER_MAIN;
        String declared = text(version, "mainClass");
        if (declared.equals(BOOTSTRAP_LAUNCHER_MAIN)) return MODLAUNCHER_MAIN;
        return declared;
    }

    /** The version id the installer declares ("1.20.1-forge-47.4.28"). */
    static String declaredId(JsonObject version) {
        return text(version, "id");
    }

    /**
     * The JVM options of the installed version. Module-path options go (the wrapper and 26.x's
     * bootstrap build the module layer themselves); the module opens stay, the JVM only warns about
     * modules it cannot see yet. Adds the wrapper's paths; the controller agent and settings come
     * from the launcher at launch.
     */
    static List<String> jvmOptions(String minecraft, Installer installer, Path installerFile,
                                   Path clientJar, Path libraries) {
        JsonObject version = installer.version();
        List<String> out = new ArrayList<>();
        JsonArray jvm = arguments(version, "jvm");
        boolean wrapper = mainClass(minecraft, version).equals(WRAPPER_MAIN);
        for (int i = 0; i < jvm.size(); i++) {
            String option = jvm.get(i).getAsString();
            // Module-path options go: the wrapper and 26.x's bootstrap build the module layer
            // themselves. -p carries its module list as the next argument; --add-modules carries
            // its module list inline after it, which would otherwise stay behind as a stray token.
            if (option.equals("-p") || option.equals("--module-path")
                    || option.equals("--upgrade-module-path") || option.equals("--add-modules")) {
                i++;
                continue;
            }
            out.add(option.replace("${version_name}", declaredId(version)));
        }
        if (wrapper) {
            out.add("-Dforgewrapper.librariesDir=" + libraries);
            out.add("-Dforgewrapper.installer=" + installerFile);
            out.add("-Dforgewrapper.minecraft=" + clientJar);
        }
        return out;
    }

    /** The game arguments the version declares (--launchTarget forgeclient and friends). */
    static List<String> gameArgs(JsonObject version) {
        List<String> out = new ArrayList<>();
        JsonArray game = arguments(version, "game");
        for (int i = 0; i < game.size(); i++) out.add(game.get(i).getAsString());
        return out;
    }

    private static JsonArray arguments(JsonObject version, String kind) {
        if (!version.has("arguments")) return new JsonArray();
        JsonObject all = version.getAsJsonObject("arguments");
        if (!all.has(kind) || !all.get(kind).isJsonArray()) return new JsonArray();
        return all.getAsJsonArray(kind);
    }

    // ------------------------------------------------------------------ processors

    /**
     * Runs the installer's client-side processor steps with the launcher's own Java. Each step's
     * recorded output path and SHA-1 is checked first, so an interrupted install resumes and a
     * finished one does no work.
     */
    static void runProcessors(Installer installer, Path installerFile, Path libraries, Path clientJar,
                              InstanceStore.Operation op) throws IOException {
        JsonObject profile = installer.profile();
        if (!profile.has("processors")) return;
        Path temp = Files.createTempDirectory(installerFile.getParent(), "forge-data-");
        try {
            JsonObject data = profile.has("data") ? profile.getAsJsonObject("data") : new JsonObject();
            Map<String, String> values = resolveData(data, installerFile, libraries, temp);
            values.put("INSTALLER", installerFile.toString());
            values.put("MINECRAFT_JAR", clientJar.toString());
            values.put("SIDE", "client");
            values.put("ROOT", libraries.getParent() != null ? libraries.getParent().toString()
                    : libraries.toString());
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            if (!Files.isRegularFile(java, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("The launcher's Java (" + java + ") cannot run the installer's steps");
            JsonArray processors = profile.getAsJsonArray("processors");
            for (int i = 0; i < processors.size(); i++) {
                JsonObject processor = processors.get(i).getAsJsonObject();
                if (skipsClient(processor)) continue;
                Map<String, String> outputs = outputs(processor, values);
                if (done(outputs)) continue;
                String where = "Forge installer step " + processor.get("jar").getAsString();
                op.progress(where + "...");
                List<String> command = new ArrayList<>();
                command.add(java.toString());
                command.add("-Xmx768M");
                command.add("-XX:+UseSerialGC");
                command.add("-cp");
                command.add(classpath(processor, libraries));
                command.add(mainClassOf(libraries.resolve(artifactPath(processor.get("jar").getAsString()))));
                for (JsonElement arg : processor.getAsJsonArray("args"))
                    command.add(substitute(arg.getAsString(), values, installerFile, temp));
                run(command, libraries, where);
            }
        } finally {
            cleanup(temp);
        }
    }

    /** Steps marked server-only do not run here. */
    private static boolean skipsClient(JsonObject processor) {
        if (!processor.has("sides")) return false;
        for (JsonElement side : processor.getAsJsonArray("sides"))
            if (side.getAsString().equals("client")) return false;
        return true;
    }

    /** Data values for the client: artifact references become library-cache paths, /data/ files come out of the installer. */
    private static Map<String, String> resolveData(JsonObject data, Path installerFile, Path libraries,
                                                   Path temp) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
            String value;
            if (entry.getValue().isJsonObject()) {
                JsonObject sides = entry.getValue().getAsJsonObject();
                value = sides.has("client") ? sides.get("client").getAsString() : "";
            } else if (entry.getValue().isJsonPrimitive()) {
                value = entry.getValue().getAsString();
            } else value = "";
            values.put(entry.getKey(), materialize(value, installerFile, libraries, temp));
        }
        return values;
    }

    /** Turns one data value (artifact reference, installer path or quoted literal) into a usable path or string. */
    private static String materialize(String value, Path installerFile, Path libraries, Path temp)
            throws IOException {
        if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2)
            return value.substring(1, value.length() - 1);
        if (value.startsWith("["))
            return libraries.resolve(artifactPath(value.substring(1, value.length() - 1))).toString();
        if (value.startsWith("/data/")) {
            byte[] bytes = embedded(installerFile, value.substring(1));
            if (bytes == null) throw new IOException("The Forge installer does not carry " + value);
            Path target = temp.resolve(value.substring("/data/".length()).replace('/', '_'));
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            return target.toString();
        }
        return value;
    }

    /** The step's outputs: placeholder to expected SHA-1, both resolved through the data. */
    private static Map<String, String> outputs(JsonObject processor, Map<String, String> values) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!processor.has("outputs")) return out;
        for (Map.Entry<String, JsonElement> e : processor.getAsJsonObject("outputs").entrySet()) {
            String path = values.getOrDefault(e.getKey(), e.getKey());
            String sha = values.getOrDefault(e.getValue().getAsString(), "");
            out.put(path, sha);
        }
        return out;
    }

    /** True when every output exists with the recorded hash. */
    private static boolean done(Map<String, String> outputs) {
        if (outputs.isEmpty()) return false;
        for (Map.Entry<String, String> output : outputs.entrySet()) {
            if (output.getValue().isEmpty()) return false;
            Path file = Path.of(output.getKey());
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
            try {
                if (!VersionStore.sha1(Files.readAllBytes(file)).equals(output.getValue())) return false;
            } catch (IOException unreadable) {
                return false;
            }
        }
        return true;
    }

    /** Maven coordinates to a relative file path, with the @extension and classifier forms. */
    static String artifactPath(String coordinate) throws IOException {
        String extension = "jar";
        int at = coordinate.indexOf('@');
        if (at >= 0) {
            extension = coordinate.substring(at + 1);
            coordinate = coordinate.substring(0, at);
        }
        String[] p = coordinate.split(":");
        if (p.length < 3 || p.length > 4) throw new IOException("Unsupported Maven coordinate " + coordinate);
        for (String part : p)
            if (!COORDINATE.matcher(part).matches())
                throw new IOException("Unsupported Maven coordinate " + coordinate);
        String file = p[1] + "-" + p[2] + (p.length == 4 ? "-" + p[3] : "") + "." + extension;
        return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + file;
    }

    /** The step's class path: its own JAR plus every class-path coordinate, all from Forge's Maven. */
    private static String classpath(JsonObject processor, Path libraries) throws IOException {
        StringBuilder out = new StringBuilder();
        append(out, libraries.resolve(artifactPath(processor.get("jar").getAsString())));
        JsonArray classpath = processor.has("classpath") ? processor.getAsJsonArray("classpath") : new JsonArray();
        for (JsonElement entry : classpath)
            append(out, libraries.resolve(artifactPath(entry.getAsString())));
        return out.toString();
    }

    private static void append(StringBuilder out, Path file) {
        if (out.length() > 0) out.append(':');
        out.append(file);
    }

    private static String mainClassOf(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry manifest = zip.getEntry("META-INF/MANIFEST.MF");
            if (manifest == null) throw new IOException(jar + " has no manifest");
            String text = new String(zip.getInputStream(manifest).readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("Main-Class:\\s*([^\\r\\n]+)").matcher(text);
            if (!m.find()) throw new IOException(jar + " names no Main-Class");
            return m.group(1).trim();
        }
    }

    /** Replaces {PLACEHOLDER} references with their data values. */
    private static String substitute(String value, Map<String, String> values, Path installerFile, Path temp)
            throws IOException {
        Matcher m = PLACEHOLDER.matcher(value);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(value, last, m.start());
            String key = m.group().substring(1, m.group().length() - 1);
            String resolved = values.get(key);
            if (resolved == null) throw new IOException("The Forge installer references unknown data " + key);
            out.append(resolved);
            last = m.end();
        }
        out.append(value.substring(last));
        return out.toString();
    }

    private static void run(List<String> command, Path directory, String where) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) > 0; ) {
                output.write(buffer, 0, n);
                if (output.size() > (1 << 20)) output.reset(); // keep the tail on long steps
            }
        }
        try {
            if (process.waitFor() != 0) {
                String tail = output.toString(StandardCharsets.UTF_8);
                tail = tail.length() > 4000 ? tail.substring(tail.length() - 4000) : tail;
                throw new IOException(where + " failed: " + tail.trim());
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Operation cancelled");
        }
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    /** Cleans a temp folder made for processor data. */
    private static void cleanup(Path temp) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(temp)) {
            for (Path file : files) Files.deleteIfExists(file);
            Files.deleteIfExists(temp);
        } catch (IOException ignored) {
            // a temp folder left behind is harmless
        }
    }
}
