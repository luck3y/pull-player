/*
 * Internal Use Only
 *
 * Copyright 2011 Red Hat, Inc. All rights reserved.
 */
package org.jboss.pull.player;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * @author Jason T. Greene
 */
class Util {
    static final File BASE_DIR;

    static {
        String home = System.getProperty("user.home");
        if (home != null) {
            BASE_DIR = new File(new File(home), ".pull-player");
            BASE_DIR.mkdirs();
        } else {
            BASE_DIR = new File(".");
        }
    }

    static void dumpInputStream(InputStream stream) {
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(stream));
        String line;
        try {
            while ((line = bufferedReader.readLine()) != null) {
                System.out.println(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    static void safeClose(Closeable closeable) {
        if (closeable != null) try {
            closeable.close();
        } catch (Throwable e) {
            // ignored
        }
    }

    static Map<String, String> map(String... args) {
        if (args == null || args.length == 0) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new HashMap<String, String>();
        for (int i = 0; i < args.length;) {
            map.put(args[i++], args[i++]);
        }

        return map;
    }

    static Properties getProperties() {
        return PropertiesHolder.PROPERTIES;
    }

    static String require(final String name) {
        final String result = PropertiesHolder.PROPERTIES.getProperty(name);
        if (result == null)
            throw new RuntimeException(name + " must be specified in player.properties");
        return result;
    }

    static boolean optionalBoolean(final String name, final boolean defaultValue) {
        final String result = PropertiesHolder.PROPERTIES.getProperty(name);
        if (result == null)
            return defaultValue;
        return Boolean.parseBoolean(result.trim());
    }

    private static class PropertiesHolder {
        static final Properties PROPERTIES = new Properties();
        static {
            boolean found = false;
            // Look for at least one of the two valid property files. The private
            // file's settings can override or add to the properties in the non-private one.
            // Either can provide any property; the 'private' name is just a nod to
            // the expected use case of using a separate file for sensitive settings.
            for (String file : Arrays.asList("player.properties", "player.private.properties")) {
                if (loadPlayerProperties(file)) {
                    found = true;
                }
            }
            if (!found) {
                throw new IllegalStateException(String.format("%s has no player.properties or player.private.properties file", BASE_DIR));
            }
        }

        private static boolean loadPlayerProperties(String fileName) {
            Path propertyFile = BASE_DIR.toPath().resolve(fileName);
            if (propertyFile.toFile().exists()) {
                try (Reader reader = Files.newBufferedReader(propertyFile, StandardCharsets.UTF_8)) {
                    PROPERTIES.load(reader);
                    return true;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                return false;
            }
        }
    }
}
