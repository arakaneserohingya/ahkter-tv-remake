package com.kulwant.atvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SharedFilesTest {

    @Test
    public void sentNamesStayInsideTheFolder() {
        assertEquals("passwd", SharedFiles.safeName("../../etc/passwd"));
        assertEquals("evil.apk", SharedFiles.safeName("C:\\Users\\me\\evil.apk"));
        assertEquals("hidden.txt", SharedFiles.safeName("..hidden.txt"));
        assertEquals("file", SharedFiles.safeName(".."));
        assertEquals("file", SharedFiles.safeName(""));
        assertEquals("file", SharedFiles.safeName(null));
    }

    @Test
    public void sentNamesKeepWhatTheyCan() {
        assertEquals("Holiday video 2026.mp4", SharedFiles.safeName("Holiday video 2026.mp4"));
        assertEquals("ছবি.jpg", SharedFiles.safeName("ছবি.jpg"));
        assertEquals("ab.txt", SharedFiles.safeName("a\u0000b\n.txt"));
        assertEquals("what.pdf", SharedFiles.safeName("wh?a*t.pdf"));
        String longName = "x".repeat(200) + ".mkv";
        String safe = SharedFiles.safeName(longName);
        assertEquals(120, safe.length());
        assertTrue(safe.endsWith(".mkv"));
    }

    @Test
    public void takenNamesGetANumber() {
        assertEquals("photo (2).jpg", SharedFiles.numbered("photo.jpg", 2));
        assertEquals("archive.tar (3).gz", SharedFiles.numbered("archive.tar.gz", 3));
        assertEquals("README (2)", SharedFiles.numbered("README", 2));
    }

    @Test
    public void onlyPlainNamesReachFiles() {
        assertTrue(SharedFiles.isPlainName("movie.mkv"));
        assertTrue(SharedFiles.isPlainName("a:b.txt"));   // made by another app: still listed, so reachable
        assertFalse(SharedFiles.isPlainName("../secret"));
        assertFalse(SharedFiles.isPlainName("sub/file.txt"));
        assertFalse(SharedFiles.isPlainName(".upload-1234.part"));
        assertFalse(SharedFiles.isPlainName(""));
        assertFalse(SharedFiles.isPlainName(null));
    }
}
