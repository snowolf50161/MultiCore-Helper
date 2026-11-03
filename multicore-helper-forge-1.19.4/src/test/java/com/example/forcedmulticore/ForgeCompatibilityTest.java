package com.example.forcedmulticore;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.junit.Test;
import static org.junit.Assert.*;

public class ForgeCompatibilityTest {
    @Test public void packForge4510IsAcceptedWithoutAcceptingOtherMinecraftBranches() throws Exception {
        String metadata;
        try (var input = getClass().getResourceAsStream("/META-INF/mods.toml")) {
            assertNotNull("Packaged metadata missing", input);
            metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var matcher = Pattern.compile("modId=\"forge\"\\s+mandatory=true\\s+versionRange=\"([^\"]+)\"").matcher(metadata);
        assertTrue("Forge dependency missing", matcher.find());
        VersionRange range = VersionRange.createFromVersionSpec(matcher.group(1));
        assertTrue("The reported modpack uses Forge 45.1.0", range.containsVersion(new DefaultArtifactVersion("45.1.0")));
        assertTrue(range.containsVersion(new DefaultArtifactVersion("45.4.0")));
        assertFalse(range.containsVersion(new DefaultArtifactVersion("44.1.0")));
        assertFalse(range.containsVersion(new DefaultArtifactVersion("46.0.0")));
    }
}
