package com.kulwant.atvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PrayerRepoTest {

    @Test
    public void keepsJakimZoneCodes() {
        assertEquals("WLY01", PrayerRepo.normaliseZone("WLY01"));
        assertEquals("SGR01", PrayerRepo.normaliseZone("  sgr01 "));
        assertEquals("SWK08", PrayerRepo.normaliseZone("Swk08"));
    }

    @Test
    public void emptyOrInvalidMeansDetect() {
        assertEquals("", PrayerRepo.normaliseZone(""));
        assertEquals("", PrayerRepo.normaliseZone("   "));
        assertEquals("", PrayerRepo.normaliseZone(null));
        assertEquals("", PrayerRepo.normaliseZone("Kuala Lumpur"));
        assertEquals("", PrayerRepo.normaliseZone("WLY1"));
        assertEquals("", PrayerRepo.normaliseZone("WLY01&zone=SGR01"));
    }
}
