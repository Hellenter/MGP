package ru.mgp.config;

import org.bukkit.Material;
import ru.mgp.util.ItemSpec;

import java.util.List;

/** Набор (кит). permission может быть null — тогда доступен всем. actions — например эффекты. */
public record Kit(String id, String display, Material icon, String permission, List<String> description,
                  List<ItemSpec> items, List<String> actions) {}
