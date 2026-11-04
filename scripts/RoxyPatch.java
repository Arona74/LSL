import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipFile;

/**
 * Makes the Voxy jar the NeoForge module compiles against: Voxy as Roxy rewrites it for NeoForge
 * 1.21.1, by calling Roxy's own patcher (RoxyFabricModReader.patchJar), the same code that runs at
 * game start. LSL's Voxy hooks must match that patched code, not Voxy's source.
 *
 * <p>Usage (Java 21; the classpath needs the Roxy jar, Gson, ASM 9 (asm, tree, commons, util,
 * analysis), FancyModLoader's loader 4.x, securejarhandler, JarJarFileSystems and jetbrains
 * annotations, all in the Gradle cache once neoforge_1_21_1 has been built):
 * <pre>
 *   javac -cp "$CP" scripts/RoxyPatch.java -d build/roxypatch
 *   java -cp "$CP;build/roxypatch" RoxyPatch voxy-0.2.16-beta+1.21.11.jar libs/voxy-0.2.16-beta-roxy-1.21.1.jar
 * </pre>
 * The output is a derived Voxy jar: never commit or share it (libs/voxy-*.jar is git-ignored).
 */
public class RoxyPatch {
    public static void main(String[] args) throws Exception {
        Path voxy = Path.of(args[0]);
        JsonObject metadata;
        try (ZipFile zip = new ZipFile(voxy.toFile())) {
            metadata = JsonParser.parseString(new String(zip.getInputStream(zip.getEntry("fabric.mod.json")).readAllBytes()))
                .getAsJsonObject();
        }
        Method patch = Class.forName("net.rasanovum.roxy.loader.RoxyFabricModReader")
            .getDeclaredMethod("patchJar", Path.class, JsonObject.class);
        patch.setAccessible(true);
        Path patched = (Path) patch.invoke(null, voxy, metadata);
        Files.copy(patched, Path.of(args[1]), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Patched Voxy written to " + args[1]);
    }
}
