package com.termux.view;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.text.InputType;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Exercises the real Android input-connection contract; no source-text assertions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class TerminalViewImeTest {
    @Test
    public void optInAdvertisesNonPasswordTextForCjkIme() {
        TestClient client = new TestClient();
        client.enforceCharBasedInput = true;
        TerminalView view = new TerminalView(RuntimeEnvironment.getApplication(), null);
        view.setTerminalViewClient(client);
        EditorInfo info = new EditorInfo();

        assertTrue(view.onCheckIsTextEditor());
        assertNotNull(view.onCreateInputConnection(info));
        assertEquals("CJK IMEs need a text editor, not the TYPE_NULL/key-event contract",
            InputType.TYPE_CLASS_TEXT, info.inputType & InputType.TYPE_MASK_CLASS);
        assertEquals("Password variations can disable CJK composition",
            InputType.TYPE_TEXT_VARIATION_NORMAL, info.inputType & InputType.TYPE_MASK_VARIATION);
        assertEquals(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            info.inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        assertEquals(EditorInfo.IME_FLAG_NO_FULLSCREEN, info.imeOptions);
    }

    @Test
    public void defaultKeepsLegacyKeyEventContract() {
        TerminalView view = createView(new TestClient());
        EditorInfo info = new EditorInfo();
        assertNotNull(view.onCreateInputConnection(info));
        assertEquals(InputType.TYPE_NULL, info.inputType);
        assertEquals(EditorInfo.IME_FLAG_NO_FULLSCREEN, info.imeOptions);
    }

    @Test
    public void alternateViewKeepsNormalTextContract() {
        TestClient client = new TestClient();
        client.enforceCharBasedInput = true;
        client.terminalViewSelected = false;
        TerminalView view = createView(client);
        EditorInfo info = new EditorInfo();
        assertNotNull(view.onCreateInputConnection(info));
        assertEquals(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL, info.inputType);
    }

    @Test
    public void commitsCjkAndSupplementaryUnicodeAsUtf8ExactlyOnce() throws Exception {
        TerminalView view = createViewWithSession();
        InputConnection connection = view.onCreateInputConnection(new EditorInfo());
        String text = "\u4e2d\u6587\u65e5\u672c\u8a9e\ud55c\uae00\ud840\udc00\ud83d\ude00";
        assertTrue(connection.commitText(text, 1));
        assertTerminalInput(view, text);
        assertTrue(connection.finishComposingText());
        assertTerminalInput(view, "");
    }

    @Test
    public void composingRevisionsDoNotLeakToTerminalBeforeCommit() throws Exception {
        TerminalView view = createViewWithSession();
        InputConnection connection = view.onCreateInputConnection(new EditorInfo());
        assertTrue(connection.setComposingText("zhong", 1));
        assertTerminalInput(view, "");
        assertTrue(connection.setComposingText("\u4e2d", 1));
        assertTerminalInput(view, "");
        assertTrue(connection.commitText("\u4e2d", 1));
        assertTerminalInput(view, "\u4e2d");
        assertTrue(connection.finishComposingText());
        assertTerminalInput(view, "");
    }

    @Test
    public void finishingCompositionFlushesPendingTextExactlyOnce() throws Exception {
        TerminalView view = createViewWithSession();
        InputConnection connection = view.onCreateInputConnection(new EditorInfo());
        assertTrue(connection.setComposingText("\u4e2d\u6587", 1));
        assertTerminalInput(view, "");
        assertTrue(connection.finishComposingText());
        assertTerminalInput(view, "\u4e2d\u6587");
        assertTrue(connection.finishComposingText());
        assertTerminalInput(view, "");
    }

    @Test
    public void hardwareCtrlCSendsEtx() throws Exception {
        assertHardwareInput(KeyEvent.KEYCODE_C,
            KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON, "\u0003");
    }

    @Test
    public void hardwareLeftAltBSendsEscapePrefix() throws Exception {
        assertHardwareInput(KeyEvent.KEYCODE_B,
            KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON, "\u001bb");
    }

    @Test
    public void hardwareBackspaceSendsDel() throws Exception {
        assertHardwareInput(KeyEvent.KEYCODE_DEL, 0, "\u007f");
    }

    @Test
    public void hardwareEnterSendsCarriageReturn() throws Exception {
        assertHardwareInput(KeyEvent.KEYCODE_ENTER, 0, "\r");
    }

    @Test
    public void imeNewlineSendsCarriageReturn() throws Exception {
        TerminalView view = createViewWithSession();
        InputConnection connection = view.onCreateInputConnection(new EditorInfo());
        assertTrue(connection.commitText("\n", 1));
        assertTerminalInput(view, "\r");
    }

    @Test
    public void physicalEscapePreImeWritesOnceAndConsumesDownAndUp() throws Exception {
        TerminalView view = createViewWithSession();
        KeyEvent down = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE,
            0, 0, 7, 1, 0, InputDevice.SOURCE_KEYBOARD);
        KeyEvent up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP);
        boolean downHandled = view.onKeyPreIme(KeyEvent.KEYCODE_ESCAPE, down);
        boolean upHandled = view.onKeyPreIme(KeyEvent.KEYCODE_ESCAPE, up);
        System.out.println("physical Esc pre-IME: downHandled=" + downHandled + ", upHandled=" + upHandled);
        assertTerminalInput(view, "\u001b");
        assertTrue("Physical Esc DOWN must not reach the IME", downHandled);
        assertTrue("Physical Esc UP must not reach the IME", upHandled);
        assertTerminalInput(view, "");
    }

    @Test
    public void physicalEscapePreImeRepeatsOncePerDownNotUp() throws Exception {
        TerminalView view = createViewWithSession();
        KeyEvent down = escapeEvent(KeyEvent.ACTION_DOWN, 0, 0, 7, 0, InputDevice.SOURCE_KEYBOARD);
        assertTrue(view.onKeyPreIme(KeyEvent.KEYCODE_ESCAPE, down));
        assertTerminalInput(view, "\u001b");
        assertTrue(view.onKeyPreIme(KeyEvent.KEYCODE_ESCAPE,
            escapeEvent(KeyEvent.ACTION_DOWN, 1, 0, 7, KeyEvent.FLAG_LONG_PRESS, InputDevice.SOURCE_KEYBOARD)));
        assertTerminalInput(view, "\u001b");
        assertTrue(view.onKeyPreIme(KeyEvent.KEYCODE_ESCAPE, KeyEvent.changeAction(down, KeyEvent.ACTION_UP)));
        assertTerminalInput(view, "");
    }

    @Test
    public void physicalEscapePreImeWithNoSessionOrEmulatorIsConsumed() {
        TerminalView view = createView(new TestClient());
        KeyEvent down = escapeEvent(KeyEvent.ACTION_DOWN, 0, 0, 7, 0, InputDevice.SOURCE_KEYBOARD);
        assertTrue(view.onKeyPreIme(KeyEvent.KEYCODE_ESCAPE, down));
        assertTrue(view.onKeyPreIme(KeyEvent.KEYCODE_ESCAPE, KeyEvent.changeAction(down, KeyEvent.ACTION_UP)));
    }

    @Test
    public void physicalEscapePreImeWithStoppedSessionDoesNotWrite() throws Exception {
        TerminalView view = createViewWithSession();
        Field pid = TerminalSession.class.getDeclaredField("mShellPid");
        pid.setAccessible(true);
        pid.setInt(view.mTermSession, 0);
        KeyEvent down = escapeEvent(KeyEvent.ACTION_DOWN, 0, 0, 7, 0, InputDevice.SOURCE_KEYBOARD);
        assertTrue(view.onKeyPreIme(KeyEvent.KEYCODE_ESCAPE, down));
        assertTrue(view.onKeyPreIme(KeyEvent.KEYCODE_ESCAPE, KeyEvent.changeAction(down, KeyEvent.ACTION_UP)));
        assertTerminalInput(view, "");
    }

    @Test
    public void physicalEscapePreImeWithDetachedEmulatorDoesNotWrite() throws Exception {
        TerminalView view = createViewWithSession();
        view.mEmulator = null;
        KeyEvent down = escapeEvent(KeyEvent.ACTION_DOWN, 0, 0, 7, 0, InputDevice.SOURCE_KEYBOARD);
        assertTrue(view.onKeyPreIme(KeyEvent.KEYCODE_ESCAPE, down));
        assertTrue(view.onKeyPreIme(KeyEvent.KEYCODE_ESCAPE, KeyEvent.changeAction(down, KeyEvent.ACTION_UP)));
        assertTerminalInput(view, "");
    }

    @Test
    public void virtualAndSoftEscapeKeepExistingPostImePath() throws Exception {
        TerminalView view = createViewWithSession();
        KeyEvent[] events = {
            escapeEvent(KeyEvent.ACTION_DOWN, 0, 0, -1, 0, InputDevice.SOURCE_KEYBOARD),
            escapeEvent(KeyEvent.ACTION_DOWN, 0, 0, 0, 0, InputDevice.SOURCE_KEYBOARD),
            escapeEvent(KeyEvent.ACTION_DOWN, 0, 0, 7, KeyEvent.FLAG_SOFT_KEYBOARD, InputDevice.SOURCE_KEYBOARD),
            escapeEvent(KeyEvent.ACTION_DOWN, 0, 0, 7, 0, InputDevice.SOURCE_UNKNOWN)
        };
        for (KeyEvent down : events) {
            assertEquals(false, view.onKeyPreIme(KeyEvent.KEYCODE_ESCAPE, down));
            assertEquals(false, view.onKeyPreIme(KeyEvent.KEYCODE_ESCAPE, KeyEvent.changeAction(down, KeyEvent.ACTION_UP)));
            assertTerminalInput(view, "");
            assertTrue(view.onKeyDown(KeyEvent.KEYCODE_ESCAPE, down));
            assertTrue(view.onKeyUp(KeyEvent.KEYCODE_ESCAPE, KeyEvent.changeAction(down, KeyEvent.ACTION_UP)));
            assertTerminalInput(view, "\u001b");
        }
    }

    @Test
    public void realBackPreImeStillFollowsExistingProperty() throws Exception {
        TerminalView view = createViewWithSession();
        TestClient client = new TestClient();
        view.setTerminalViewClient(client);
        KeyEvent down = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK,
            0, 0, 7, 158, 0, InputDevice.SOURCE_KEYBOARD);
        KeyEvent up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP);
        assertEquals(false, view.onKeyPreIme(KeyEvent.KEYCODE_BACK, down));
        assertEquals(false, view.onKeyPreIme(KeyEvent.KEYCODE_BACK, up));
        assertTerminalInput(view, "");
        client.mapBackToEscape = true;
        assertTrue(view.onKeyPreIme(KeyEvent.KEYCODE_BACK, down));
        // Existing system-BACK release still reaches the client/framework (activity navigation).
        assertEquals(false, view.onKeyPreIme(KeyEvent.KEYCODE_BACK, up));
        assertTerminalInput(view, "\u001b");
    }

    @Test
    public void physicalEscapePreImeReusesClientHooks() throws Exception {
        TerminalView view = createViewWithSession();
        TestClient client = new TestClient();
        client.consumeKeys = true;
        view.setTerminalViewClient(client);
        KeyEvent down = escapeEvent(KeyEvent.ACTION_DOWN, 0, 0, 7, 0, InputDevice.SOURCE_KEYBOARD);
        assertTrue(view.onKeyPreIme(KeyEvent.KEYCODE_ESCAPE, down));
        assertTrue(view.onKeyPreIme(KeyEvent.KEYCODE_ESCAPE, KeyEvent.changeAction(down, KeyEvent.ACTION_UP)));
        assertEquals(1, client.keyDownCount);
        assertEquals(1, client.keyUpCount);
        assertTerminalInput(view, "");
    }

    @Test
    public void modifiedPhysicalEscapeKeepsExistingEncoding() throws Exception {
        TerminalView view = createViewWithSession();
        int[] modifiers = {KeyEvent.META_CTRL_ON, KeyEvent.META_SHIFT_ON,
            KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON};
        for (int meta : modifiers) {
            KeyEvent down = escapeEvent(KeyEvent.ACTION_DOWN, 0, meta, 9, 0, InputDevice.SOURCE_KEYBOARD);
            assertTrue(view.onKeyPreIme(KeyEvent.KEYCODE_ESCAPE, down));
            assertTrue(view.onKeyPreIme(KeyEvent.KEYCODE_ESCAPE, KeyEvent.changeAction(down, KeyEvent.ACTION_UP)));
            assertTerminalInput(view, "\u001b");
        }
    }

    @Test
    public void ctrlLeftBracketKeepsExistingPostImeEscapeEncoding() throws Exception {
        TerminalView view = createViewWithSession();
        KeyEvent down = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_LEFT_BRACKET,
            0, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON, 7, 26, 0, InputDevice.SOURCE_KEYBOARD);
        assertEquals(false, view.onKeyPreIme(KeyEvent.KEYCODE_LEFT_BRACKET, down));
        assertTerminalInput(view, "");
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_LEFT_BRACKET, down));
        assertTerminalInput(view, "\u001b");
    }

    @Test
    public void physicalEscapeDoesNotFlushOrDiscardCjkComposition() throws Exception {
        TerminalView view = createViewWithSession();
        InputConnection connection = view.onCreateInputConnection(new EditorInfo());
        assertTrue(connection.setComposingText("zhong", 1));
        KeyEvent down = escapeEvent(KeyEvent.ACTION_DOWN, 0, 0, 7, 0, InputDevice.SOURCE_KEYBOARD);
        assertTrue(view.onKeyPreIme(KeyEvent.KEYCODE_ESCAPE, down));
        assertTrue(view.onKeyPreIme(KeyEvent.KEYCODE_ESCAPE, KeyEvent.changeAction(down, KeyEvent.ACTION_UP)));
        assertTerminalInput(view, "\u001b");
        assertTrue(connection.commitText("\u4e2d", 1));
        assertTerminalInput(view, "\u4e2d");
        assertTrue(connection.finishComposingText());
        assertTerminalInput(view, "");
    }

    @Test
    public void scanOneGravePreImeWritesEscapeNotPrintableText() throws Exception {
        TerminalView view = createViewWithSession();
        KeyEvent down = new KeyEvent(10, 20, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_GRAVE,
            0, 0, 26, 1, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        boolean handled = view.onKeyPreIme(down.getKeyCode(), down);
        System.out.println("scan1 GRAVE pre-IME handled=" + handled);
        assertTerminalInput(view, "\u001b");
        assertTrue(handled);
        assertTrue(view.onKeyPreIme(down.getKeyCode(), KeyEvent.changeAction(down, KeyEvent.ACTION_UP)));
        assertTerminalInput(view, "");
    }

    @Test
    public void scanOneGravePostImeWritesEscapeNotPrintableText() throws Exception {
        TerminalView view = createViewWithSession();
        KeyEvent down = new KeyEvent(10, 20, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_GRAVE,
            0, 0, 26, 1, 0, InputDevice.SOURCE_KEYBOARD);
        assertTrue(view.onKeyDown(down.getKeyCode(), down));
        assertTerminalInput(view, "\u001b");
        assertTrue(view.onKeyUp(down.getKeyCode(), KeyEvent.changeAction(down, KeyEvent.ACTION_UP)));
        assertTerminalInput(view, "");
    }

    @Test
    public void scanOneGraveRepeatsAndModifiersReuseEscapeEncodingOnBothPaths() throws Exception {
        int[] modifiers = {0, KeyEvent.META_CTRL_ON, KeyEvent.META_SHIFT_ON,
            KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON,
            KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON,
            KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON};
        for (boolean preIme : new boolean[] {true, false}) {
            for (int meta : modifiers) {
                TerminalView view = createViewWithSession();
                for (int repeat = 0; repeat < 3; repeat++) {
                    KeyEvent down = graveEvent(KeyEvent.ACTION_DOWN, repeat, meta, 9, 1,
                        KeyEvent.FLAG_FROM_SYSTEM | (repeat > 0 ? KeyEvent.FLAG_LONG_PRESS : 0),
                        InputDevice.SOURCE_KEYBOARD);
                    assertTrue(dispatch(view, down, preIme));
                    assertTerminalInput(view, "\u001b");
                    assertTrue(dispatch(view, KeyEvent.changeAction(down, KeyEvent.ACTION_UP), preIme));
                    assertTerminalInput(view, "");
                }
            }
        }
    }

    @Test
    public void realGraveAndNonPhysicalScanOneGraveAreNotNormalized() throws Exception {
        KeyEvent[] events = {
            graveEvent(0, 0, 0, 7, 41, 0, InputDevice.SOURCE_KEYBOARD),
            graveEvent(0, 0, 0, -1, 1, 0, InputDevice.SOURCE_KEYBOARD),
            graveEvent(0, 0, 0, 0, 1, 0, InputDevice.SOURCE_KEYBOARD),
            graveEvent(0, 0, 0, 7, 1, KeyEvent.FLAG_SOFT_KEYBOARD, InputDevice.SOURCE_KEYBOARD),
            graveEvent(0, 0, 0, 7, 1, 0, InputDevice.SOURCE_UNKNOWN),
            graveEvent(0, 0, 0, 7, 1, 0, InputDevice.SOURCE_MOUSE),
            graveEvent(0, 0, 0, 7, 1, 0, InputDevice.SOURCE_CLASS_BUTTON),
            graveEvent(0, 0, 0, 7, 2, 0, InputDevice.SOURCE_KEYBOARD)
        };
        for (KeyEvent down : events) {
            TerminalView view = createViewWithSession();
            assertEquals(false, dispatch(view, down, true));
            assertEquals(false, dispatch(view, KeyEvent.changeAction(down, KeyEvent.ACTION_UP), true));
            assertTerminalInput(view, "");
            assertTrue(dispatch(view, down, false));
            assertTerminalInput(view, "`");
            assertTrue(dispatch(view, KeyEvent.changeAction(down, KeyEvent.ACTION_UP), false));
            assertTerminalInput(view, "");
        }
    }

    @Test
    public void scanOneGraveHooksReceiveCanonicalCodeAndUnmodifiedEvent() throws Exception {
        for (boolean preIme : new boolean[] {true, false}) {
            TerminalView view = createViewWithSession();
            TestClient client = new TestClient();
            client.consumeKeys = true;
            view.setTerminalViewClient(client);
            KeyEvent down = graveEvent(0, 2, KeyEvent.META_SHIFT_ON, 19, 1,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_LONG_PRESS,
                InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD);
            KeyEvent up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP);
            assertTrue(dispatch(view, down, preIme));
            assertTrue(dispatch(view, up, preIme));
            assertEquals(1, client.keyDownCount);
            assertEquals(1, client.keyUpCount);
            assertEquals(KeyEvent.KEYCODE_ESCAPE, client.lastDownCode);
            assertEquals(KeyEvent.KEYCODE_ESCAPE, client.lastUpCode);
            // Identity proves displayId, times, scan/source/device/flags/meta/repeat survive unchanged.
            assertTrue(down == client.lastDownEvent);
            assertTrue(up == client.lastUpEvent);
            assertEquals(KeyEvent.KEYCODE_GRAVE, client.lastDownEvent.getKeyCode());
            assertTerminalInput(view, "");
        }
    }

    @Test
    public void scanOneGravePreservesClientCtrlAltShortcutConsumption() throws Exception {
        for (boolean preIme : new boolean[] {true, false}) {
            TerminalView view = createViewWithSession();
            TestClient client = new TestClient();
            client.consumeCtrlAlt = true; // Mirrors TermuxTerminalViewClient's unconditional Ctrl+Alt branch.
            view.setTerminalViewClient(client);
            KeyEvent down = graveEvent(0, 0, KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON,
                7, 1, 0, InputDevice.SOURCE_KEYBOARD);
            assertTrue(dispatch(view, down, preIme));
            assertEquals(1, client.keyDownCount);
            assertEquals(KeyEvent.KEYCODE_ESCAPE, client.lastDownCode);
            assertTerminalInput(view, "");
        }
    }

    @Test
    public void hardwareFnScanOneGraveKeepsKcmFallbackAndOriginalHookCode() throws Exception {
        TerminalView view = createViewWithSession();
        TerminalView reference = createViewWithSession();
        TestClient client = new TestClient();
        view.setTerminalViewClient(client);
        KeyEvent down = graveEvent(0, 0, KeyEvent.META_FUNCTION_ON, 7, 1, 0, InputDevice.SOURCE_KEYBOARD);
        assertEquals(false, dispatch(view, down, true));
        assertEquals(false, dispatch(view, KeyEvent.changeAction(down, KeyEvent.ACTION_UP), true));
        KeyEvent ordinary = graveEvent(0, 0, KeyEvent.META_FUNCTION_ON, 7, 41, 0, InputDevice.SOURCE_KEYBOARD);
        assertEquals(dispatch(reference, ordinary, false), dispatch(view, down, false));
        assertArrayEquals(readTerminalInput(reference), readTerminalInput(view));
        assertEquals(KeyEvent.KEYCODE_GRAVE, client.lastDownCode);
    }

    @Test
    public void scanOneGraveWithExtraKeysFnStillMatchesCanonicalEscape() throws Exception {
        TerminalView view = createViewWithSession();
        TestClient client = new TestClient();
        client.fnKey = true;
        view.setTerminalViewClient(client);
        assertTrue(dispatch(view, graveEvent(0, 0, 0, 7, 1, 0, InputDevice.SOURCE_KEYBOARD), true));
        assertTerminalInput(view, "\u001b");
    }

    @Test
    public void scanOneGraveWithoutEmulatorOrRunningSessionDoesNotWrite() throws Exception {
        for (boolean preIme : new boolean[] {true, false}) {
            KeyEvent down = graveEvent(0, 0, 0, 7, 1, 0, InputDevice.SOURCE_KEYBOARD);
            TerminalView empty = createView(new TestClient());
            assertTrue(dispatch(empty, down, preIme));
            assertTrue(dispatch(empty, KeyEvent.changeAction(down, KeyEvent.ACTION_UP), preIme));
            TerminalView stopped = createViewWithSession();
            Field pid = TerminalSession.class.getDeclaredField("mShellPid");
            pid.setAccessible(true);
            pid.setInt(stopped.mTermSession, 0);
            assertTrue(dispatch(stopped, down, preIme));
            assertTerminalInput(stopped, "");
            stopped.mEmulator = null;
            assertTrue(dispatch(stopped, down, preIme));
            assertTerminalInput(stopped, "");
        }
    }

    @Test
    public void punctuationCommitsAndCjkCompositionRemainLiteralAroundScanOneEscape() throws Exception {
        TerminalView view = createViewWithSession();
        InputConnection connection = view.onCreateInputConnection(new EditorInfo());
        assertTrue(connection.commitText("`\u00b7", 1));
        assertTerminalInput(view, "`\u00b7");
        assertTrue(connection.setComposingText("zhong", 1));
        assertTerminalInput(view, "");
        assertTrue(dispatch(view, graveEvent(0, 0, 0, 7, 1, 0, InputDevice.SOURCE_KEYBOARD), true));
        assertTerminalInput(view, "\u001b");
        assertTrue(connection.commitText("\u4e2d`\u00b7", 1));
        assertTerminalInput(view, "\u4e2d`\u00b7");
        assertTrue(connection.finishComposingText());
        assertTerminalInput(view, "");
    }

    @Test
    public void systemBackCallbackStillUsesExistingPropertyNotScanNormalization() throws Exception {
        TerminalView view = createViewWithSession();
        TestClient client = new TestClient();
        view.setTerminalViewClient(client);
        KeyEvent back = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK,
            0, 0, 0, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_UNKNOWN);
        assertEquals(false, dispatch(view, back, true));
        assertEquals(false, dispatch(view, KeyEvent.changeAction(back, KeyEvent.ACTION_UP), true));
        assertTerminalInput(view, "");
        client.mapBackToEscape = true;
        assertTrue(dispatch(view, back, true));
        assertEquals(false, dispatch(view, KeyEvent.changeAction(back, KeyEvent.ACTION_UP), true));
        assertTerminalInput(view, "\u001b");
    }

    @Test
    public void scanOneBackPreImeWritesEscapeAndConsumesSystemBackRelease() throws Exception {
        TerminalView view = createViewWithSession();
        KeyEvent down = new KeyEvent(10, 20, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK,
            0, 0, 29, 1, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        assertTrue("The original BACK event must exercise the system-key branch", down.isSystem());
        boolean downHandled = view.onKeyPreIme(down.getKeyCode(), down);
        boolean upHandled = view.onKeyPreIme(down.getKeyCode(), KeyEvent.changeAction(down, KeyEvent.ACTION_UP));
        System.out.println("scan1 BACK pre-IME isSystem=" + down.isSystem()
            + ", downHandled=" + downHandled + ", upHandled=" + upHandled);
        assertTerminalInput(view, "\u001b");
        assertTrue(downHandled);
        assertTrue("Scan1 BACK release must not leak to system navigation", upHandled);
        assertTerminalInput(view, "");
    }

    @Test
    public void scanOneBackPostImeWritesEscapeAndConsumesSystemBackRelease() throws Exception {
        TerminalView view = createViewWithSession();
        KeyEvent down = new KeyEvent(10, 20, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK,
            0, 0, 29, 1, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        assertTrue("The original BACK event must exercise the system-key branch", down.isSystem());
        boolean downHandled = view.onKeyDown(down.getKeyCode(), down);
        boolean upHandled = view.onKeyUp(down.getKeyCode(), KeyEvent.changeAction(down, KeyEvent.ACTION_UP));
        System.out.println("scan1 BACK post-IME isSystem=" + down.isSystem()
            + ", downHandled=" + downHandled + ", upHandled=" + upHandled);
        assertTerminalInput(view, "\u001b");
        assertTrue(downHandled);
        assertTrue("Scan1 BACK release must not leak to system navigation", upHandled);
        assertTerminalInput(view, "");
    }

    @Test
    public void scanOneBackRepeatsAndModifiersReuseEscapeEncodingOnBothPaths() throws Exception {
        int[] modifiers = {0, KeyEvent.META_CTRL_ON, KeyEvent.META_SHIFT_ON,
            KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON,
            KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON,
            KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON};
        for (boolean preIme : new boolean[] {true, false}) {
            for (int meta : modifiers) {
                TerminalView view = createViewWithSession();
                for (int repeat = 0; repeat < 3; repeat++) {
                    KeyEvent down = backEvent(KeyEvent.ACTION_DOWN, repeat, meta, 9, 1,
                        KeyEvent.FLAG_FROM_SYSTEM | (repeat > 0 ? KeyEvent.FLAG_LONG_PRESS : 0),
                        InputDevice.SOURCE_KEYBOARD);
                    assertTrue(dispatch(view, down, preIme));
                    assertTerminalInput(view, "\u001b");
                    assertTrue(dispatch(view, KeyEvent.changeAction(down, KeyEvent.ACTION_UP), preIme));
                    assertTerminalInput(view, "");
                }
            }
        }
    }

    @Test
    public void scanOneBackHooksReceiveCanonicalCodeAndUnmodifiedEvent() throws Exception {
        for (boolean preIme : new boolean[] {true, false}) {
            TerminalView view = createViewWithSession();
            TestClient client = new TestClient();
            client.consumeKeys = true;
            view.setTerminalViewClient(client);
            KeyEvent down = backEvent(0, 2, KeyEvent.META_SHIFT_ON, 19, 1,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_LONG_PRESS,
                InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD);
            KeyEvent up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP);
            assertTrue(dispatch(view, down, preIme));
            assertTrue(dispatch(view, up, preIme));
            assertEquals(1, client.keyDownCount);
            assertEquals(1, client.keyUpCount);
            assertEquals(KeyEvent.KEYCODE_ESCAPE, client.lastDownCode);
            assertEquals(KeyEvent.KEYCODE_ESCAPE, client.lastUpCode);
            // Identity proves displayId, times, scan/source/device/flags/meta/repeat survive unchanged.
            assertTrue(down == client.lastDownEvent);
            assertTrue(up == client.lastUpEvent);
            assertEquals(KeyEvent.KEYCODE_BACK, client.lastDownEvent.getKeyCode());
            assertTerminalInput(view, "");
        }
    }

    @Test
    public void scanOneBackPreservesClientCtrlAltShortcutConsumption() throws Exception {
        for (boolean preIme : new boolean[] {true, false}) {
            TerminalView view = createViewWithSession();
            TestClient client = new TestClient();
            client.consumeCtrlAlt = true; // Mirrors TermuxTerminalViewClient's unconditional Ctrl+Alt branch.
            view.setTerminalViewClient(client);
            KeyEvent down = backEvent(0, 0, KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON,
                7, 1, 0, InputDevice.SOURCE_KEYBOARD);
            assertTrue(dispatch(view, down, preIme));
            assertEquals(1, client.keyDownCount);
            assertEquals(KeyEvent.KEYCODE_ESCAPE, client.lastDownCode);
            assertTerminalInput(view, "");
        }
    }

    @Test
    public void scanOneBackWithExtraKeysFnStillMatchesCanonicalEscape() throws Exception {
        TerminalView view = createViewWithSession();
        TestClient client = new TestClient();
        client.fnKey = true;
        view.setTerminalViewClient(client);
        assertTrue(dispatch(view, backEvent(0, 0, 0, 7, 1, 0, InputDevice.SOURCE_KEYBOARD), true));
        assertTerminalInput(view, "\u001b");
    }

    @Test
    public void scanOneBackWithoutEmulatorOrRunningSessionDoesNotWrite() throws Exception {
        for (boolean preIme : new boolean[] {true, false}) {
            KeyEvent down = backEvent(0, 0, 0, 7, 1, 0, InputDevice.SOURCE_KEYBOARD);
            TerminalView empty = createView(new TestClient());
            assertTrue(dispatch(empty, down, preIme));
            assertTrue(dispatch(empty, KeyEvent.changeAction(down, KeyEvent.ACTION_UP), preIme));
            TerminalView stopped = createViewWithSession();
            Field pid = TerminalSession.class.getDeclaredField("mShellPid");
            pid.setAccessible(true);
            pid.setInt(stopped.mTermSession, 0);
            assertTrue(dispatch(stopped, down, preIme));
            assertTerminalInput(stopped, "");
            stopped.mEmulator = null;
            assertTrue(dispatch(stopped, down, preIme));
            assertTerminalInput(stopped, "");
        }
    }

    @Test
    public void punctuationCommitsAndCjkCompositionRemainLiteralAroundBackScanOneEscape() throws Exception {
        TerminalView view = createViewWithSession();
        InputConnection connection = view.onCreateInputConnection(new EditorInfo());
        assertTrue(connection.commitText("`\u00b7", 1));
        assertTerminalInput(view, "`\u00b7");
        assertTrue(connection.setComposingText("zhong", 1));
        assertTerminalInput(view, "");
        assertTrue(dispatch(view, backEvent(0, 0, 0, 7, 1, 0, InputDevice.SOURCE_KEYBOARD), true));
        assertTerminalInput(view, "\u001b");
        assertTrue(connection.commitText("\u4e2d`\u00b7", 1));
        assertTerminalInput(view, "\u4e2d`\u00b7");
        assertTrue(connection.finishComposingText());
        assertTerminalInput(view, "");
    }

    @Test
    public void realBackAndNonPhysicalScanOneBackKeepSystemRoutingAndProperty() throws Exception {
        KeyEvent[] events = {
            backEvent(0, 0, 0, 7, 158, 0, InputDevice.SOURCE_KEYBOARD),
            backEvent(0, 0, 0, 7, 0, 0, InputDevice.SOURCE_KEYBOARD),
            backEvent(0, 0, 0, 0, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_UNKNOWN),
            backEvent(0, 0, 0, -1, 1, 0, InputDevice.SOURCE_KEYBOARD),
            backEvent(0, 0, 0, 0, 1, 0, InputDevice.SOURCE_KEYBOARD),
            backEvent(0, 0, 0, 7, 1, KeyEvent.FLAG_SOFT_KEYBOARD, InputDevice.SOURCE_KEYBOARD),
            backEvent(0, 0, 0, 7, 1, 0, InputDevice.SOURCE_UNKNOWN),
            backEvent(0, 0, 0, 7, 1, 0, InputDevice.SOURCE_MOUSE),
            backEvent(0, 0, 0, 7, 1, 0, InputDevice.SOURCE_CLASS_BUTTON),
            backEvent(0, 0, 0, 7, 2, 0, InputDevice.SOURCE_KEYBOARD),
            backEvent(0, 0, KeyEvent.META_FUNCTION_ON, 7, 1, 0, InputDevice.SOURCE_KEYBOARD)
        };
        for (boolean preIme : new boolean[] {true, false}) {
            for (boolean mapBack : new boolean[] {false, true}) {
                for (KeyEvent down : events) {
                    TerminalView view = createViewWithSession();
                    TestClient client = new TestClient();
                    client.mapBackToEscape = mapBack;
                    view.setTerminalViewClient(client);
                    assertTrue(down.isSystem());
                    // Hardware Fn still takes the original KCM/fallback path (no ESC encoding).
                    boolean expectedDown = mapBack && !down.isFunctionPressed();
                    assertEquals(expectedDown, dispatch(view, down, preIme));
                    assertTerminalInput(view, expectedDown ? "\u001b" : "");
                    assertEquals(false, dispatch(view, KeyEvent.changeAction(down, KeyEvent.ACTION_UP), preIme));
                    assertTerminalInput(view, "");
                    if (!preIme || mapBack) assertEquals(KeyEvent.KEYCODE_BACK, client.lastDownCode);
                }
            }
        }
    }

    @Test
    public void scanOneBackActionMultipleDoesNotBecomeEscape() throws Exception {
        for (boolean preIme : new boolean[] {true, false}) {
            TerminalView view = createViewWithSession();
            KeyEvent event = backEvent(KeyEvent.ACTION_MULTIPLE, 2, 0, 29, 1,
                KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
            assertEquals(false, dispatch(view, event, preIme));
            assertTerminalInput(view, "");
        }
    }

    @Test
    public void canonicalEscapeArgumentWithOriginalBackRechecksPhysicalBoundary() throws Exception {
        for (int scan : new int[] {1, 158, 0}) {
            TerminalView view = createViewWithSession();
            KeyEvent down = backEvent(0, 0, 0, 29, scan, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
            assertTrue(down.isSystem());
            assertEquals(scan == 1, view.onKeyDown(KeyEvent.KEYCODE_ESCAPE, down));
            assertTerminalInput(view, scan == 1 ? "\u001b" : "");
            assertEquals(scan == 1, view.onKeyUp(KeyEvent.KEYCODE_ESCAPE, KeyEvent.changeAction(down, KeyEvent.ACTION_UP)));
            assertTerminalInput(view, "");
        }
    }

    @Test
    public void physicalBackAliasDoesNotReachActivityBackCallbackButRealBackStillDoes() throws Exception {
        for (boolean preIme : new boolean[] {true, false}) {
            for (boolean mapBack : new boolean[] {false, true}) {
                try (org.robolectric.android.controller.ActivityController<BackNavigationActivity> controller =
                        org.robolectric.Robolectric.buildActivity(BackNavigationActivity.class).setup()) {
                    BackNavigationActivity activity = controller.get();
                    TerminalView view = createViewWithSession();
                    TestClient client = new TestClient();
                    client.mapBackToEscape = mapBack;
                    view.setTerminalViewClient(client);
                    // Real View/Activity KeyEvent dispatch and Back tracking, without rendering or JNI PTY resize.
                    KeyEvent.DispatcherState backState = new KeyEvent.DispatcherState();
                    for (int scan : new int[] {1, 158, 0}) {
                        KeyEvent down = backEvent(0, 0, 0, 29, scan, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
                        KeyEvent up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP);
                        for (KeyEvent event : new KeyEvent[] {down, up}) {
                            boolean handled = preIme && view.onKeyPreIme(event.getKeyCode(), event);
                            if (!handled) handled = view.dispatchKeyEvent(event);
                            if (!handled) event.dispatch(activity, backState, activity);
                        }

                        assertTerminalInput(view, scan == 1 || mapBack ? "\u001b" : "");
                        System.out.println("navigation preIme=" + preIme + ", mapBack=" + mapBack + ", scan=" + scan
                            + ", callbacks=" + activity.backCallbacks);
                        // Existing back-key=escape consumes DOWN, so Activity never starts Back tracking.
                        assertEquals("preIme=" + preIme + ", mapBack=" + mapBack + ", scan=" + scan,
                            mapBack || scan == 1 ? 0 : scan == 158 ? 1 : 2, activity.backCallbacks);
                    }
                }
            }
        }
    }

    public static class BackNavigationActivity extends android.app.Activity {
        int backCallbacks;
        @Override public void onBackPressed() { backCallbacks++; }
    }

    private static KeyEvent backEvent(int action, int repeat, int meta, int device, int scan, int flags, int source) {
        return new KeyEvent(10, 20 + repeat, action, KeyEvent.KEYCODE_BACK, repeat, meta, device, scan, flags, source);
    }

    private static boolean dispatch(TerminalView view, KeyEvent event, boolean preIme) {
        if (preIme) return view.onKeyPreIme(event.getKeyCode(), event);
        return event.getAction() == KeyEvent.ACTION_UP ? view.onKeyUp(event.getKeyCode(), event)
            : view.onKeyDown(event.getKeyCode(), event);
    }

    private static KeyEvent graveEvent(int action, int repeat, int meta, int device, int scan, int flags, int source) {
        return new KeyEvent(10, 20 + repeat, action, KeyEvent.KEYCODE_GRAVE, repeat, meta, device, scan, flags, source);
    }

    private static KeyEvent escapeEvent(int action, int repeat, int meta, int device, int flags, int source) {
        return new KeyEvent(0, 0, action, KeyEvent.KEYCODE_ESCAPE, repeat, meta, device, 1, flags, source);
    }

    private static TerminalView createView(TestClient client) {
        TerminalView view = new TerminalView(RuntimeEnvironment.getApplication(), null);
        view.setTerminalViewClient(client);
        return view;
    }

    private static TerminalView createViewWithSession() throws Exception {
        TestClient client = new TestClient();
        client.enforceCharBasedInput = true;
        TerminalView view = createView(client);
        TerminalSession session = new TerminalSession("", "", new String[0], new String[0], 100, client);
        TerminalEmulator emulator = new TerminalEmulator(session, 80, 24, 8, 16, 100, client);
        // Keep the real encoding and byte queue; only bypass native PTY/process creation.
        // No initializeEmulator(), updateSize(), finishIfRunning(), or JNI calls in this fixture.
        Field emulatorField = TerminalSession.class.getDeclaredField("mEmulator");
        emulatorField.setAccessible(true);
        emulatorField.set(session, emulator);
        Field pidField = TerminalSession.class.getDeclaredField("mShellPid");
        pidField.setAccessible(true);
        pidField.setInt(session, 1); // Enables the in-memory write queue; not an actual process.
        view.mTermSession = session;
        view.mEmulator = emulator;
        return view;
    }

    private static void assertTerminalInput(TerminalView view, String expected) throws Exception {
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), readTerminalInput(view));
    }

    private static byte[] readTerminalInput(TerminalView view) throws Exception {
        Field queueField = TerminalSession.class.getDeclaredField("mTerminalToProcessIOQueue");
        queueField.setAccessible(true);
        Object queue = queueField.get(view.mTermSession);
        Method read = queue.getClass().getDeclaredMethod("read", byte[].class, boolean.class);
        read.setAccessible(true);
        byte[] bytes = new byte[4096];
        int length = (int) read.invoke(queue, bytes, false);
        assertTrue("Terminal input queue must remain open", length >= 0);
        return Arrays.copyOf(bytes, length);
    }

    private static void assertHardwareInput(int keyCode, int metaState, String expected) throws Exception {
        TerminalView view = createViewWithSession();
        assertNotNull(view.onCreateInputConnection(new EditorInfo()));
        KeyEvent event = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0,
            metaState, 1, 0, 0, InputDevice.SOURCE_KEYBOARD);
        assertTrue(view.onKeyDown(keyCode, event));
        assertTerminalInput(view, expected);
    }

    private static final class TestClient implements TerminalViewClient, TerminalSessionClient {
        boolean enforceCharBasedInput;
        boolean terminalViewSelected = true;
        boolean mapBackToEscape;
        boolean consumeKeys;
        boolean consumeCtrlAlt;
        boolean fnKey;
        int lastDownCode;
        int lastUpCode;
        KeyEvent lastDownEvent;
        KeyEvent lastUpEvent;
        int keyDownCount;
        int keyUpCount;

        @Override public void onTextChanged(TerminalSession session) {}
        @Override public void onTitleChanged(TerminalSession session) {}
        @Override public void onSessionFinished(TerminalSession session) {}
        @Override public void onCopyTextToClipboard(TerminalSession session, String text) {}
        @Override public void onPasteTextFromClipboard(TerminalSession session) {}
        @Override public void onBell(TerminalSession session) {}
        @Override public void onColorsChanged(TerminalSession session) {}
        @Override public void onTerminalCursorStateChange(boolean state) {}
        @Override public void setTerminalShellPid(TerminalSession session, int pid) {}
        @Override public Integer getTerminalCursorStyle() { return null; }

        @Override public float onScale(float scale) { return scale; }
        @Override public void onSingleTapUp(MotionEvent e) {}
        @Override public boolean shouldBackButtonBeMappedToEscape() { return mapBackToEscape; }
        @Override public boolean shouldEnforceCharBasedInput() { return enforceCharBasedInput; }
        @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
        @Override public boolean isTerminalViewSelected() { return terminalViewSelected; }
        @Override public void copyModeChanged(boolean copyMode) {}
        @Override public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
            keyDownCount++;
            lastDownCode = keyCode;
            lastDownEvent = e;
            return consumeKeys || (consumeCtrlAlt && e.isCtrlPressed() && e.isAltPressed());
        }
        @Override public boolean onKeyUp(int keyCode, KeyEvent e) {
            keyUpCount++;
            lastUpCode = keyCode;
            lastUpEvent = e;
            return consumeKeys;
        }
        @Override public boolean onLongPress(MotionEvent event) { return false; }
        @Override public boolean readControlKey() { return false; }
        @Override public boolean readAltKey() { return false; }
        @Override public boolean readShiftKey() { return false; }
        @Override public boolean readFnKey() { return fnKey; }
        @Override public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) { return false; }
        @Override public void onEmulatorSet() {}
        @Override public void logError(String tag, String message) {}
        @Override public void logWarn(String tag, String message) {}
        @Override public void logInfo(String tag, String message) {}
        @Override public void logDebug(String tag, String message) {}
        @Override public void logVerbose(String tag, String message) {}
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {}
        @Override public void logStackTrace(String tag, Exception e) {}
    }
}
