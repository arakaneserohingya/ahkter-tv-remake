package com.kulwant.atvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WallpapersTest {

    @Test
    public void acceptsTheUrlsThePhoneAndTvBuild() {
        assertTrue(Wallpapers.isUnsplashImage("https://images.unsplash.com/photo-1518837695005-2083093ee35b?w=1920&q=85"));
        assertTrue(Wallpapers.isUnsplashImage("https://IMAGES.unsplash.com/photo-abc"));
        assertTrue(Wallpapers.isUnsplashImage("https://images.unsplash.com:443/photo-abc"));
    }

    @Test
    public void rejectsOtherHostsAndSchemes() {
        assertFalse(Wallpapers.isUnsplashImage("http://images.unsplash.com/photo-abc"));
        assertFalse(Wallpapers.isUnsplashImage("https://unsplash.com/photos/abc"));
        assertFalse(Wallpapers.isUnsplashImage("https://images.unsplash.com.example.com/photo-abc"));
        assertFalse(Wallpapers.isUnsplashImage("https://images.unsplash.com@192.168.0.1/photo-abc"));
        assertFalse(Wallpapers.isUnsplashImage("https://192.168.0.1/photo-abc"));
        assertFalse(Wallpapers.isUnsplashImage("https://images.unsplash.com:8080/photo-abc"));
        assertFalse(Wallpapers.isUnsplashImage("file:///data/data/com.kulwant.atvhome/files/adbkey"));
        assertFalse(Wallpapers.isUnsplashImage("not a url"));
        assertFalse(Wallpapers.isUnsplashImage(""));
        assertFalse(Wallpapers.isUnsplashImage(null));
    }
}
