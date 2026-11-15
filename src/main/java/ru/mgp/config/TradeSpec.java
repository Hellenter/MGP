package ru.mgp.config;

import ru.mgp.util.ItemSpec;

/** Одна сделка у торговца. cost2 может быть null. */
public record TradeSpec(ItemSpec result, ItemSpec cost, ItemSpec cost2, int maxUses) {}
