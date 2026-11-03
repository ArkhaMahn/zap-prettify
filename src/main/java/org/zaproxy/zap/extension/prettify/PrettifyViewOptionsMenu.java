package org.zaproxy.zap.extension.prettify;

import java.awt.Component;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.JCheckBoxMenuItem;
import org.parosproxy.paros.Constant;
import org.zaproxy.zap.extension.ExtensionPopupMenu;
import org.zaproxy.zap.view.messagecontainer.MessageContainer;

/**
 * The &quot;Pretty view&quot; sub-menu of the message popup, holding the Prettify view's own
 * per-view options: the Content-Type filler, word wrap, pretty formatting and syntax highlighting.
 *
 * <p>The menu is offered only for {@link PrettifyTextArea}s; everywhere else it is hidden. The view
 * keeps the state itself (and persists it with its own editor settings) through
 * {@link PrettifyTextArea.Options}; every show re-derives the check state from the view being
 * invoked, so view instances never share a checkbox, and an entry is only offered where its option
 * applies (for example, the Content-Type filler only exists for editable request views).
 */
public class PrettifyViewOptionsMenu extends ExtensionPopupMenu {

    private static final long serialVersionUID = 1L;

    private final Map<PrettifyTextArea.Option, JCheckBoxMenuItem> items =
            new EnumMap<>(PrettifyTextArea.Option.class);

    /** The text area whose view the menu is about (set again on every show). */
    private PrettifyTextArea toggling;

    public PrettifyViewOptionsMenu() {
        super(Constant.messages.getString("prettify.popup.menu"));
        for (PrettifyTextArea.Option option : PrettifyTextArea.Option.values()) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(label(option));
            item.addActionListener(
                    event -> {
                        PrettifyTextArea.Options options =
                                toggling == null ? null : toggling.options();
                        if (options != null) {
                            options.setOn(option, item.isSelected());
                        }
                    });
            items.put(option, item);
            add(item);
        }
    }

    private static String label(PrettifyTextArea.Option option) {
        switch (option) {
            case UPDATE_CONTENT_TYPE:
                return Constant.messages.getString("prettify.popup.updatetype");
            case WRAP:
                return Constant.messages.getString("prettify.popup.wrap");
            case FORMAT:
                return Constant.messages.getString("prettify.popup.format");
            case SYNTAX_HIGHLIGHT:
                return Constant.messages.getString("prettify.popup.highlight");
        }
        return option.name();
    }

    @Override
    public boolean isEnableForComponent(Component invoker) {
        if (!(invoker instanceof PrettifyTextArea)) {
            return false;
        }
        PrettifyTextArea area = (PrettifyTextArea) invoker;
        PrettifyTextArea.Options options = area.options();
        if (options == null) {
            return false;
        }
        boolean any = false;
        for (Map.Entry<PrettifyTextArea.Option, JCheckBoxMenuItem> entry : items.entrySet()) {
            PrettifyTextArea.Option option = entry.getKey();
            boolean shown = options.isApplicable(option);
            entry.getValue().setVisible(shown);
            if (shown) {
                entry.getValue().setSelected(options.isOn(option));
                any = true;
            }
        }
        this.toggling = area;
        return any;
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
