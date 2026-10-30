package clack.core

import clack.core.layout.LayoutEditing
import clack.core.sympad.K
import clack.core.sympad.SymKeyTracker
import clack.core.sympad.SymPadAction
import clack.core.sympad.SymPadMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SymPadTest {
    @Test fun defaultSchemeHasTheTwoPadsAndFreeMiddle() {
        val m = SymPadMap.DEFAULT
        assertEquals(SymPadAction.UP, m["KEYCODE_W"])
        assertEquals(SymPadAction.DOWN, m["KEYCODE_X"])
        assertEquals(SymPadAction.LEFT, m["KEYCODE_A"])
        assertEquals(SymPadAction.RIGHT, m["KEYCODE_D"])
        assertEquals(SymPadAction.LINE_START, m["KEYCODE_Z"])
        assertEquals(SymPadAction.LINE_END, m["KEYCODE_C"])
        assertEquals(SymPadAction.MEDIA_PLAY_PAUSE, m["KEYCODE_K"])
        for (free in listOf("S", "R", "T", "F", "G", "V", "B")) assertEquals(free, SymPadAction.NONE, m["KEYCODE_$free"])
        // every key in the scheme exists on the device
        val keys = LayoutEditing.TITAN2_ROWS.flatten().toSet()
        assertTrue(m.actions.keys.all { it in keys })
    }

    @Test fun actionsMapToAndroidKeys() {
        assertEquals(K.DPAD_LEFT, SymPadAction.WORD_LEFT.key)
        assertTrue(SymPadAction.WORD_LEFT.ctrl)
        assertEquals(K.MOVE_HOME, SymPadAction.DOC_START.key)
        assertTrue(SymPadAction.DOC_START.ctrl)
        assertFalse(SymPadAction.LINE_START.ctrl)
        assertTrue(SymPadAction.UP.isMovement)
        assertFalse(SymPadAction.MEDIA_NEXT.isMovement)
        assertTrue(SymPadAction.MEDIA_NEXT.isMedia)
        assertEquals(K.MEDIA_FAST_FORWARD, SymPadAction.MEDIA_NEXT.seekKey)
        assertEquals(null, SymPadAction.MEDIA_PLAY_PAUSE.seekKey)
        assertEquals(142, SymPadAction.F12.key)
        assertEquals(120, SymPadAction.PRINT_SCREEN.key)
        assertTrue(SymPadAction.REDO.ctrl && SymPadAction.REDO.shift)
        assertTrue(SymPadAction.COPY.isClipboard)
        assertTrue(SymPadAction.SWITCH_LAYOUT.isInternal)
        assertFalse(SymPadAction.VOLUME_UP.isMedia)
        assertTrue(SymPadAction.MEDIA_REWIND.isMedia)
        // every non-internal, non-volume action sends a key
        for (a in SymPadAction.values()) if (a != SymPadAction.NONE && !a.isInternal && !a.isVolume) assertTrue(a.id, a.key > 0)
        // ids are unique (they are stored in files)
        assertEquals(SymPadAction.values().size, SymPadAction.values().map { it.id }.toSet().size)
    }

    @Test fun schemeJsonRoundTripAndEdits() {
        val edited = SymPadMap.DEFAULT.with("KEYCODE_S", SymPadAction.SELECT).with("KEYCODE_K", SymPadAction.NONE)
        val back = SymPadMap.parse(edited.toJson())
        assertEquals(edited, back)
        assertEquals(SymPadAction.SELECT, back["KEYCODE_S"])
        assertEquals(SymPadAction.NONE, back["KEYCODE_K"])
        // unknown actions from a newer version are ignored, not fatal
        val m = SymPadMap.parse("""{"mappings":{"KEYCODE_Q":"teleport","KEYCODE_W":"up"}}""")
        assertEquals(mapOf("KEYCODE_W" to SymPadAction.UP), m.actions)
    }

    @Test fun symTapLongPressAndChord() {
        val t = SymKeyTracker(longPressMs = 500)
        t.onDown(0); assertEquals(SymKeyTracker.Release.TAP, t.onUp(120))

        t.onDown(1000); t.onOtherKey(); assertEquals(SymKeyTracker.Release.CHORD, t.onUp(1900))

        t.onDown(2000)
        assertFalse(t.checkLong(2300))
        assertTrue(t.checkLong(2500))
        assertFalse("fires once", t.checkLong(2600))
        assertEquals(SymKeyTracker.Release.LONG, t.onUp(2700))

        // a long hold used as a modifier is a chord, not a lock
        t.onDown(3000); t.onOtherKey(); assertFalse(t.checkLong(3600)); assertEquals(SymKeyTracker.Release.CHORD, t.onUp(3700))

        // released late without the timer having fired still counts as long
        t.onDown(4000); assertEquals(SymKeyTracker.Release.LONG, t.onUp(4800))
    }
}
