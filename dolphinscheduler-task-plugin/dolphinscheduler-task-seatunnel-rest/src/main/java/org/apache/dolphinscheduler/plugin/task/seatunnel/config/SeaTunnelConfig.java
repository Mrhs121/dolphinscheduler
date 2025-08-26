package org.apache.dolphinscheduler.plugin.task.seatunnel.config;

import org.apache.dolphinscheduler.common.utils.PropertyUtils;

import java.io.File;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

public class SeaTunnelConfig {

    private static final Properties YAML_PROPS = new Properties();
    private static final AtomicBoolean YAML_LOADED = new AtomicBoolean(false);

    private SeaTunnelConfig() {
    }

    // ===== API =====
    public static String getString(String key, String envKey, String defVal) {
        String v = firstNonEmpty(
                System.getProperty(key),
                getenv(envKey),
                PropertyUtils.getString(key, null),
                fromYaml(key));
        return v != null ? v : defVal;
    }
    public static int getInt(String key, String envKey, int defVal) {
        String v = getString(key, envKey, null);
        try {
            return (v == null || v.isEmpty()) ? defVal : Integer.parseInt(v.trim());
        } catch (Exception ignore) {
            return defVal;
        }
    }
    public static long getLong(String key, String envKey, long defVal) {
        String v = getString(key, envKey, null);
        try {
            return (v == null || v.isEmpty()) ? defVal : Long.parseLong(v.trim());
        } catch (Exception ignore) {
            return defVal;
        }
    }

    // ===== YAML =====
    private static String fromYaml(String key) {
        ensureYamlLoaded();
        return YAML_PROPS.getProperty(key);
    }

    private static void ensureYamlLoaded() {
        if (YAML_LOADED.get())
            return;
        synchronized (YAML_LOADED) {
            if (YAML_LOADED.get())
                return;

            String[] dirs = new String[]{
                    System.getProperty("dolphinscheduler.conf.dir"),
                    System.getenv("DOLPHINSCHEDULER_CONF_DIR"),
                    "/opt/dolphinscheduler/conf",
                    "conf"
            };
            for (String d : dirs) {
                loadYaml(YAML_PROPS, d, "application.yaml");
                loadYaml(YAML_PROPS, d, "application.yml");
            }

            String profiles = firstNonEmpty(
                    System.getProperty("spring.profiles.active"),
                    System.getenv("SPRING_PROFILES_ACTIVE"),
                    YAML_PROPS.getProperty("spring.profiles.active"));
            if (profiles != null && !profiles.isEmpty()) {
                for (String p : profiles.split(",")) {
                    String prof = p.trim();
                    for (String d : dirs) {
                        loadYaml(YAML_PROPS, d, "application-" + prof + ".yaml");
                        loadYaml(YAML_PROPS, d, "application-" + prof + ".yml");
                    }
                }
            }
            YAML_LOADED.set(true);
        }
    }

    private static void loadYaml(Properties target, String dir, String file) {
        if (dir == null || dir.isEmpty())
            return;
        File f = new File(dir, file);
        if (!f.exists() || !f.isFile())
            return;
        try {
            YamlPropertiesFactoryBean y = new YamlPropertiesFactoryBean();
            y.setResources(new FileSystemResource(f));
            Properties p = y.getObject();
            if (p != null)
                target.putAll(p);
        } catch (Throwable ignore) {
        }
    }

    private static String getenv(String k) {
        try {
            return System.getenv(k);
        } catch (Throwable ignore) {
            return null;
        }
    }
    private static String firstNonEmpty(String... vals) {
        return Stream.of(vals).filter(v -> v != null && !v.isEmpty()).findFirst().orElse(null);
    }
}
