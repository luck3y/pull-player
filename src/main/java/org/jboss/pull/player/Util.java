/**
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
            File[] children = BASE_DIR.listFiles((dri, name) -> name.endsWith("player.properties"));
            if (children != null) {
                Arrays.stream(children).sorted((o1, o2) -> {
                    // Plain player.properties goes first; other files then override
                    int res = o1.compareTo(o2);
                    return res > -1 || "player.properties".equals(o2.getName()) ? res : 1;
                }).forEach(PropertiesHolder::loadPlayerProperties);
            } else {
                throw new IllegalStateException(String.format("%s has no player.properties files", BASE_DIR));
            }
        }

        private static void loadPlayerProperties(File properties) {
            try (Reader reader = Files.newBufferedReader(properties.toPath(), StandardCharsets.UTF_8)) {
                PROPERTIES.load(reader);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
