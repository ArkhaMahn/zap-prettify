package org.zaproxy.zap.extension.prettify;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import javax.swing.JMenuItem;
import org.fife.ui.rtextarea.RTextArea;
import org.fife.ui.rtextarea.RecordableTextAction;
import org.parosproxy.paros.extension.ExtensionPopupMenuItem;
import org.parosproxy.paros.view.View;

/**
 * Registers the standard text-editing actions (undo, cut, copy, paste, ...) for the Pretty view in
 * ZAP's main popup menu.
 *
 * <p>The core syntax view adds the same actions so that right-clicking a message offers the usual
 * text operations next to ZAP's message menu. They are enabled only for {@link PrettifyTextArea} and
 * respect whether the view is editable.
 */
final class PrettifyTextActions {

    private static final List<JMenuItem> REGISTERED = new ArrayList<>();
    private static boolean initialized;

    private PrettifyTextActions() {}

    static synchronized void init() {
        if (initialized || View.getSingleton() == null) {
            return;
        }
        Vector<JMenuItem> popupList = View.getSingleton().getPopupList();
        if (popupList == null) {
            return;
        }
        add(popupList, RTextArea.UNDO_ACTION, 19060);
        add(popupList, RTextArea.REDO_ACTION, 19040);
        add(popupList, RTextArea.CUT_ACTION, 17080);
        add(popupList, RTextArea.COPY_ACTION, 17060);
        add(popupList, RTextArea.PASTE_ACTION, 17040);
        add(popupList, RTextArea.DELETE_ACTION, 17020);
        add(popupList, RTextArea.SELECT_ALL_ACTION, 15050);
        initialized = true;
    }

    private static void add(Vector<JMenuItem> popupList, int actionId, int weight) {
        TextAreaMenuItem item = new TextAreaMenuItem(actionId, weight);
        popupList.add(item);
        REGISTERED.add(item);
    }

    static synchronized void remove() {
        if (!initialized || View.getSingleton() == null) {
            return;
        }
        Vector<JMenuItem> popupList = View.getSingleton().getPopupList();
        if (popupList != null) {
            for (JMenuItem item : REGISTERED) {
                popupList.remove(item);
            }
        }
        REGISTERED.clear();
        initialized = false;
    }

    /** A text action that is only enabled for the Pretty text area. */
    private static final class TextAreaMenuItem extends ExtensionPopupMenuItem {

        private static final long serialVersionUID = 1L;

        private final int actionId;

        TextAreaMenuItem(int actionId, int weight) {
            RecordableTextAction action = RTextArea.getAction(actionId);
            if (action == null) {
                throw new IllegalArgumentException("Action not found with id: " + actionId);
            }
            setAction(action);
            this.actionId = actionId;
            setWeight(weight);
        }

        @Override
        public boolean isEnableForComponent(Component invoker) {
            if (!(invoker instanceof PrettifyTextArea)) {
                return false;
            }
            PrettifyTextArea textArea = (PrettifyTextArea) invoker;
            switch (actionId) {
                case RTextArea.CUT_ACTION:
                case RTextArea.PASTE_ACTION:
                case RTextArea.DELETE_ACTION:
                    setEnabled(textArea.isEditable());
                    break;
                case RTextArea.UNDO_ACTION:
                    setEnabled(textArea.isEditable() && textArea.canUndo());
                    break;
                case RTextArea.REDO_ACTION:
                    setEnabled(textArea.isEditable() && textArea.canRedo());
                    break;
                default:
                    setEnabled(true);
                    break;
            }
            return true;
        }

        @Override
        public boolean isSafe() {
            // These are local text operations, so they may be offered while ZAP is in safe mode.
            return true;
        }
    }
}
