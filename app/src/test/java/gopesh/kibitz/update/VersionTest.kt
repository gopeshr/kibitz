package gopesh.kibitz.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two sides of this comparison come from different places — a git tag like "v0.1.0" and the
 * app's own `versionName` like "0.1" — so the parsing has to be forgiving in exactly the right
 * ways. Get it wrong and the app either nags about an update it already has, or never offers one.
 */
class VersionTest {

    @Test
    fun aMissingComponentCountsAsZero() {
        // This is the real case: versionName "0.1" against tag "v0.1.0".
        assertEquals(0, Version.parse("0.1").compareTo(Version.parse("v0.1.0")))
        assertEquals(0, Version.parse("1").compareTo(Version.parse("1.0.0")))
    }

    @Test
    fun theTagPrefixIsIgnored() {
        assertEquals(Version.parse("1.2.3"), Version.parse("v1.2.3"))
        assertEquals(Version.parse("1.2.3"), Version.parse("V1.2.3"))
    }

    @Test
    fun ordersByComponent() {
        assertTrue(Version.parse("0.2.0") > Version.parse("0.1.9"))
        assertTrue(Version.parse("1.0.0") > Version.parse("0.99.99"))
        assertTrue(Version.parse("0.1.1") > Version.parse("0.1.0"))
        assertTrue(Version.parse("0.1.0") < Version.parse("0.10.0"))
    }

    /** Ten is not less than nine, which string comparison would get wrong. */
    @Test
    fun comparesNumericallyNotAlphabetically() {
        assertTrue(Version.parse("0.10.0") > Version.parse("0.9.0"))
        assertTrue(Version.parse("2.0.0") > Version.parse("10.0.0").let { Version.parse("1.0.0") })
    }

    @Test
    fun toleratesSuffixesAndRubbish() {
        assertEquals(Version.parse("1.2.3"), Version.parse("v1.2.3-beta"))
        assertEquals(Version.parse("1.2.3"), Version.parse("1.2.3+build7"))
        // Nothing numeric at all must not throw; it just sorts lowest.
        assertEquals(Version(listOf(0)), Version.parse("nightly"))
        assertEquals(Version(listOf(0)), Version.parse(""))
    }

    @Test
    fun printsBackCleanly() {
        assertEquals("1.2.3", Version.parse("v1.2.3").toString())
        assertEquals("0.1", Version.parse("0.1").toString())
    }

    /** The shipped release must not look like an update to the build that shipped it. */
    @Test
    fun theCurrentReleaseIsNotAnUpdateToItself() {
        val installed = Version.parse("0.1.0")
        val published = Version.parse("v0.1.0")
        assertTrue("v0.1.0 must not be newer than 0.1.0", published <= installed)
    }
}
