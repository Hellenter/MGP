package ru.mgp.config;

import java.util.List;

/** Событие, которое срабатывает через `at` секунд после начала игры. */
public record TimelineEvent(String name, int at, List<String> actions) {}
