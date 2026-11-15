package ru.mgp.world;

import org.bukkit.configuration.file.YamlConfiguration;
import ru.mgp.MGPPlugin;
import ru.mgp.config.ConfigRegistry;
import ru.mgp.config.MapConfig;
import ru.mgp.session.GameSession;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Переименование карты: maps/<старый>.yml -> maps/<новый>.yml, папка шаблона templates/<старый> -> templates/<новый>
 * (если шаблон принадлежит только этой карте), название по умолчанию тоже меняется.
 * Спавны, сундуки и прочие настройки остаются в файле как есть.
 */
public final class MapRenamer {
    private MapRenamer() {}

    /** Запускать в основном потоке. Возвращает текст для игрока; ошибки — IllegalArgumentException/IllegalStateException. */
    public static String rename(MGPPlugin plugin, String oldId, String newIdRaw, String displayName) throws IOException {
        MapConfig m = plugin.registry().map(oldId);
        if (m == null) throw new IllegalArgumentException(plugin.registry().mapNotFound(oldId));
        String newId = ConfigRegistry.cleanId(newIdRaw).toLowerCase(Locale.ROOT);
        if (!newId.matches("[a-z0-9_]{1,32}")) throw new IllegalArgumentException("Новый id карты: только a-z, 0-9 и _ (до 32 символов)");
        if (newId.equals(m.id)) throw new IllegalArgumentException("У карты уже такой id");
        File newFile = plugin.registry().mapFile(newId);
        if (plugin.registry().map(newId) != null || newFile.exists()) throw new IllegalArgumentException("Карта " + newId + " уже есть");
        for (GameSession s : plugin.sessions().sessions()) {
            if (m.playableIn(s.mode.id))
                throw new IllegalStateException("Сейчас идёт игра режима " + s.mode.id + " — переименуй карту, когда игр этого режима не будет");
        }

        YamlConfiguration y = YamlConfiguration.loadConfiguration(m.file);
        String note = "";
        String template = y.getString("template", m.id);
        boolean shared = plugin.registry().maps().stream().anyMatch(o -> o != m && template.equals(o.template));
        Path templates = plugin.worlds().templatesDir();
        if (template.equals(m.id) && !shared && Files.isDirectory(templates.resolve(m.id))) {
            if (Files.exists(templates.resolve(newId)))
                throw new IllegalArgumentException("Папка templates/" + newId + " уже занята");
            Files.move(templates.resolve(m.id), templates.resolve(newId));
            y.set("template", newId);
            note = ", шаблон templates/" + newId;
        } else if (!template.equals(m.id) || shared) {
            note = ", шаблон остался templates/" + template;
        }
        if (displayName != null && !displayName.isBlank()) y.set("display-name", displayName);
        else if (("<yellow>" + m.id).equals(y.getString("display-name"))) y.set("display-name", "<yellow>" + newId);

        newFile.getParentFile().mkdirs();
        y.save(newFile);
        Files.delete(m.file.toPath());
        plugin.registry().load();
        return "Карта " + m.id + " → " + newId + note + ". Спавны и сундуки сохранены.";
    }
}
