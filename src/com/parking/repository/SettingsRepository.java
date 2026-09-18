package com.parking.repository;

import java.util.Map;

/** Key/value settings (e.g. hourly rates). */
public interface SettingsRepository {
    Map<String, String> loadAll();
    void put(String key, String value);
}
