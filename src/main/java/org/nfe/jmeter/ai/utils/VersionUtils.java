package org.nfe.jmeter.ai.utils;

import java.io.InputStream;
import java.net.URI;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class to access application version information.
 */
public class VersionUtils {
    private static final Logger log = LoggerFactory.getLogger(VersionUtils.class);
    // Hardcoded fallback version that matches pom.xml
    private static final String DEFAULT_VERSION = "1.0.10";
    private static final String POM_PROPERTIES_PATH = "/META-INF/maven/org.qainsights/jmeter-agent/pom.properties";
    
    private static String version = null;
    
    /**
     * Gets the application version from multiple sources in order of preference:
     * 1. Manifest file (when running from JAR)
     * 2. Maven pom.properties file (when running from JAR or development environment)
     * 3. Default hardcoded version (fallback)
     * 
     * @return The application version string
     */
    public static String getVersion() {
        if (version == null) {
            // First try to get from manifest
            try {
                Class<?> clazz = VersionUtils.class;
                String className = clazz.getSimpleName() + ".class";
                String classPath = clazz.getResource(className).toString();
                
                if (classPath.startsWith("jar")) {
                    String manifestPath = classPath.substring(0, classPath.lastIndexOf("!") + 1) + "/META-INF/MANIFEST.MF";
                    Manifest manifest = new Manifest(URI.create(manifestPath).toURL().openStream());
                    Attributes attr = manifest.getMainAttributes();
                    version = attr.getValue("Implementation-Version");
                    
                    if (version != null) {
                        log.info("Found version in manifest: {}", version);
                        return version;
                    }
                }
            } catch (Exception e) {
                log.warn("Could not read version from manifest: {}", e.getMessage());
            }
            
            // Next try to get from Maven pom.properties
            try {
                InputStream is = VersionUtils.class.getResourceAsStream(POM_PROPERTIES_PATH);
                if (is != null) {
                    Properties pomProperties = new Properties();
                    pomProperties.load(is);
                    version = pomProperties.getProperty("version");
                    is.close();
                    
                    if (version != null) {
                        log.info("Found version in pom.properties: {}", version);
                        return version;
                    }
                }
            } catch (Exception e) {
                log.warn("Could not read version from pom.properties: {}", e.getMessage());
            }
            
            // If we get here, use the default version
            log.info("Using default version: {}", DEFAULT_VERSION);
            version = DEFAULT_VERSION;
        }
        
        return version;
    }
}
