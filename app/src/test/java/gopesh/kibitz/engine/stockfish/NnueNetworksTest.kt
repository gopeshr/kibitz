package gopesh.kibitz.engine.stockfish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The extraction path reads the expected length with `getValue`, which throws on a missing key.
 * These keep the two declarations from drifting, since a network listed without a size would
 * fail on the very first launch rather than anywhere a compiler could catch it.
 */
class NnueNetworksTest {

    @Test
    fun `every network has an expected size`() {
        assertEquals(
            NnueNetworks.NETWORKS.toSet(),
            NnueNetworks.EXPECTED_BYTES.keys,
        )
    }

    @Test
    fun `expected sizes match the shipped assets`() {
        // The assets are the source of truth: a size copied by hand would silently make every
        // launch re-extract a network that was already complete.
        val assets = File("src/main/assets")
        assertTrue("assets directory not found at ${assets.absolutePath}", assets.isDirectory)

        for ((name, expected) in NnueNetworks.EXPECTED_BYTES) {
            val file = File(assets, name)
            assertTrue("$name is not in src/main/assets", file.isFile)
            assertEquals("$name has the wrong expected size", file.length(), expected)
        }
    }
}
