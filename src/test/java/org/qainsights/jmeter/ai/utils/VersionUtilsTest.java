package org.qainsights.jmeter.ai.utils;

import org.junit.jupiter.api.Test;
import org.nfe.jmeter.ai.utils.VersionUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;

/**
 * Unit tests for VersionUtils class
 */
public class VersionUtilsTest {
    
    /**
     * Reset the version field before each test to ensure tests are independent
     */
    @BeforeEach
    public void setUp() throws Exception {
        // Reset the version field using reflection to ensure each test starts fresh
        Field versionField = VersionUtils.class.getDeclaredField("version");
        versionField.setAccessible(true);
        versionField.set(null, null);
    }
    
    /**
     * Clean up after each test
     */
    @AfterEach
    public void tearDown() throws Exception {
        // Reset the version field using reflection
        Field versionField = VersionUtils.class.getDeclaredField("version");
        versionField.setAccessible(true);
        versionField.set(null, null);
    }
    
    /**
     * Test that getVersion returns a non-null value
     */
    @Test
    public void testGetVersionReturnsNonNull() {
        String version = VersionUtils.getVersion();
        assertNotNull(version, "Version should not be null");
    }
    
    /**
     * Test that getVersion returns a non-empty value
     */
    @Test
    public void testGetVersionReturnsNonEmpty() {
        String version = VersionUtils.getVersion();
        assertFalse(version.isEmpty(), "Version should not be empty");
    }
    
    /**
     * Test that getVersion returns a valid version string
     * In the test environment, it should find the version from pom.properties
     * or fall back to the default version
     */
    @Test
    public void testGetVersionReturnsValidVersion() {
        String version = VersionUtils.getVersion();
        // The version should be 1.0.10 (either from pom.properties or default)
        assertEquals("1.0.10", version, "Version should be 1.0.10");
    }
    
    /**
     * Test that getVersion caches the result
     */
    @Test
    public void testGetVersionCachesResult() throws Exception {
        // First call to getVersion
        String version1 = VersionUtils.getVersion();
        
        // Get the cached version using reflection
        Field versionField = VersionUtils.class.getDeclaredField("version");
        versionField.setAccessible(true);
        String cachedVersion = (String) versionField.get(null);
        
        // Second call to getVersion
        String version2 = VersionUtils.getVersion();
        
        // All three should be equal
        assertEquals(version1, cachedVersion, "Cached version should match first call");
        assertEquals(version1, version2, "Second call should match first call");
    }
}
