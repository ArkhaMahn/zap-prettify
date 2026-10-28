package org.zaproxy.zap.extension.prettify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PrettifyTextAreaTest {

    @Test
    void undoRestoresTheTextBeforeAnEdit() {
        PrettifyTextArea area = new PrettifyTextArea(false);
        area.setEditable(true);
        area.setText("abc");
        area.setCaretPosition(3);

        area.replaceSelection("d");
        assertEquals("abcd", area.getText());
        assertTrue(area.canUndo());

        area.undoLastAction();
        assertEquals("abc", area.getText());
    }

    @Test
    void undoHistoryIsNotCappedAtTheDefaultHundredEdits() {
        PrettifyTextArea area = new PrettifyTextArea(false);
        area.setEditable(true);
        area.setText("0");
        area.setCaretPosition(1);

        for (int i = 1; i <= 150; i++) {
            area.beginAtomicEdit();
            try {
                area.replaceSelection(Integer.toString(i % 10));
            } finally {
                area.endAtomicEdit();
            }
        }
        assertEquals(151, area.getText().length());

        for (int i = 0; i < 150; i++) {
            assertTrue(area.canUndo(), "undo history ran out after " + i + " edits");
            area.undoLastAction();
        }
        assertEquals("0", area.getText());
    }
}
