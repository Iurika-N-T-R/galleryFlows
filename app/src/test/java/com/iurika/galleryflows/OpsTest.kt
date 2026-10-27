package com.iurika.galleryflows

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class OpsTest {
    @Test fun kinds() {
        assertEquals(Kind.IMAGE, kindOf("IMG_1.JPG"))
        assertEquals(Kind.GIF, kindOf("a.gif"))
        assertEquals(Kind.VIDEO, kindOf("clip.mp4"))
        assertEquals(null, kindOf("notes.txt"))
        assertEquals(null, kindOf("noext"))
        assertEquals(Kind.RAW, kindOf("DSC_0042.NEF"))
        assertEquals(Kind.RAW, kindOf("shot.dng"))
        assertEquals(Kind.SVG, kindOf("logo.svg"))
        assertEquals(Kind.PORTRAIT, kindOf("PXL_20250101_120000000.PORTRAIT.jpg"))
        assertEquals(true, isPhoto("PXL_1.PORTRAIT.jpg"))
        assertEquals(false, isPhoto("logo.svg"))
    }

    @Test fun uniqueNames() {
        val dir = Files.createTempDirectory("gf").toFile()
        assertEquals("a.jpg", Ops.uniqueTarget(dir, "a.jpg").name)
        dir.resolve("a.jpg").createNewFile()
        dir.resolve("a (1).jpg").createNewFile()
        assertEquals("a (2).jpg", Ops.uniqueTarget(dir, "a.jpg").name)
        dir.resolve("folder").mkdir()
        assertEquals("folder (1)", Ops.uniqueTarget(dir, "folder").name)
        dir.deleteRecursively()
    }
}
