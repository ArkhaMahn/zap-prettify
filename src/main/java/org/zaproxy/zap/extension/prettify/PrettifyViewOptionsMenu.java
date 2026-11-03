package org.zaproxy.zap.extension.prettify;

import java.awt.Component;
import org.parosproxy.paros.Constant;
import org.zaproxy.zap.extension.ExtensionPopupMenu;
import org.zaproxy.zap.view.messagecontainer.MessageContainer;

/**
 * The "Pretty view" sub-menu of the message popup, holding the Prettify view's own per-view options.
 *
 * <p>The menu is offered only for {@link PrettifyTextArea}s; everywhere else it is hidden, and the
 * view whose menu item was toggled keeps the state through {@link PrettifyTextArea.ContentTypeToggle}
 * (the view persists it with its own editor settings). The single checkable item for now governs
 * whether saving an edited message adds the Content-Type header the body implies; more options live
 * in the style/adapted View menu of the core syntax view, so the extra level of indirection matches
 * the convention the panel's own checkable options follow.
 */
public class PrettifyViewOptionsMenu extends ExtensionPopupMenu {

    private static final long serialVersionUID = 1L;

    private final javax.swing.JCheckBoxMenuItem updateContentType =
            new javax.swing.JCheckBoxMenuItem(
                    Constant.messages.getString("prettify.popup.updatetype"));

    /** The text area whose view the toggle is about (set when the menu becomes visible). */
    private PrettifyTextArea toggling;

    public PrettifyViewOptionsMenu() {
        super(Constant.messages.getString("prettify.popup.menu"));
        updateContentType.addActionListener(
                event -> {
                    PrettifyTextArea.ContentTypeToggle toggle =
                            toggling == null ? null : toggling.contentTypeToggle();
                    if (toggle != null) {
                        toggle.setOn(updateContentType.isSelected());
                    }
                });
        add(updateContentType);
    }

    @Override
    public boolean isEnableForComponent(Component invoker) {
        if (!(invoker instanceof PrettifyTextArea)) {
            return false;
        }
        PrettifyTextArea area = (PrettifyTextArea) invoker;
        PrettifyTextArea.ContentTypeToggle toggle = area.contentTypeToggle();
        if (toggle == null || !toggle.isApplicable()) {
            // wherever the fill itself would never run, the menu is not offered either
            return false;
        }
        this.toggling = area;
        updateContentType.setSelected(toggle.isOn());
        return true;
    }

    @Override
    public boolean isEnableForMessageContainer(MessageContainer<?> container) {
        return container != null && isEnableForComponent(container.getComponent());
    }

    @Override
    public int getWeight() {
        // Next to the text actions of PrettifyTextActions (they weigh in at 15-19 thousand).
        return 19500;
    }

    @Override
    public boolean isSafe() {
        // Local to the editor; it may be offered while ZAP is in safe mode.
        return true;
    }
}
