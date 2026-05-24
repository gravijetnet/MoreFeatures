package net.gravijet.morefeatures.music;

import com.xxmicloxx.NoteBlockAPI.NoteBlockAPI;
import org.bukkit.plugin.java.JavaPlugin;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * NoteBlockAPI expects to run as its own Bukkit plugin. When shaded, its
 * static singleton is never set, so getAPI() returns null and every
 * SongPlayer constructor NPEs.
 *
 * We allocate a NoteBlockAPI instance via Unsafe (bypassing the constructor),
 * copy the JavaPlugin internals from our own plugin so getServer()/getScheduler()
 * resolve correctly, mark it as enabled so the Bukkit scheduler accepts it,
 * then inject it into NoteBlockAPI's private static "plugin" field.
 */
public final class NoteBlockAPIBootstrap {

    private NoteBlockAPIBootstrap() {}

    public static void init(JavaPlugin ourPlugin) throws Exception {
        if (NoteBlockAPI.getAPI() != null) return;

        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);

        // Allocate without calling any constructor.
        NoteBlockAPI instance = (NoteBlockAPI) unsafe.allocateInstance(NoteBlockAPI.class);

        // Copy server, logger, description, dataFolder, etc. from our plugin.
        copyFields(JavaPlugin.class, ourPlugin, instance);

        // The Bukkit scheduler rejects tasks from disabled plugins.
        setField(JavaPlugin.class, instance, "isEnabled", true);

        // NoteBlockPlayerMain has its own static that SongPlayer indirectly uses.
        new com.xxmicloxx.NoteBlockAPI.NoteBlockPlayerMain().onEnable();

        // Inject the fake instance as NoteBlockAPI's singleton.
        Field pluginField = NoteBlockAPI.class.getDeclaredField("plugin");
        pluginField.setAccessible(true);
        pluginField.set(null, instance);
    }

    private static void copyFields(Class<?> fromClass, Object src, Object dst) throws Exception {
        Class<?> cursor = fromClass;
        while (cursor != null && cursor != Object.class) {
            for (Field f : cursor.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object value = f.get(src);
                if (value != null) {
                    f.set(dst, value);
                }
            }
            cursor = cursor.getSuperclass();
        }
    }

    private static void setField(Class<?> declaringClass, Object target, String name, Object value) throws Exception {
        Field f = declaringClass.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
