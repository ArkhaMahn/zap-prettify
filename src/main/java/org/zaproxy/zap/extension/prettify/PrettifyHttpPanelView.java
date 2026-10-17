package org.zaproxy.zap.extension.prettify;

import java.awt.BorderLayout;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import org.apache.commons.configuration.FileConfiguration;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.zap.extension.httppanel.Message;
import org.zaproxy.zap.extension.httppanel.view.AbstractStringHttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelView;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelViewModelEvent;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelViewModelListener;
import org.zaproxy.zap.extension.search.SearchMatch;
import org.zaproxy.zap.extension.search.SearchableHttpPanelView;
import org.zaproxy.zap.utils.DisplayUtils;

/**
 * An {@link HttpPanelView} that shows an HTTP message (or one of its parts) as a Burp-style,
 * syntax-highlighted, word-wrapped text area.
 */
public class PrettifyHttpPanelView
        implements HttpPanelView, HttpPanelViewModelListener, SearchableHttpPanelView {

    /** Which part of an HTTP message this view shows. */
    public enum Target {
        REQUEST_HEAD,
        REQUEST_BODY,
        RESPONSE_HEAD,
        RESPONSE_BODY,
        REQUEST_ALL,
        RESPONSE_ALL
    }

    private final String name;
    private final String captionKey;
    private final String fixedStyleKey;
    private final Target target;
    private final AbstractStringHttpPanelViewModel model;
    private final JPanel mainPanel;
    private final PrettifyTextArea textArea;

    private String configurationKey = "";

    public PrettifyHttpPanelView(
            String name,
            String captionKey,
            String fixedStyleKey,
            Target target,
            AbstractStringHttpPanelViewModel model) {
        this.name = name;
        this.captionKey = captionKey;
        this.fixedStyleKey = fixedStyleKey;
        this.target = target;
        this.model = model;

        this.mainPanel = new JPanel(new BorderLayout());
        this.textArea = new PrettifyTextArea(DisplayUtils.isDarkLookAndFeel());
        this.mainPanel.add(new RTextScrollPane(textArea, true), BorderLayout.CENTER);

        this.model.addHttpPanelViewModelListener(this);
        update();
    }

    private void update() {
        Message message = model.getMessage();
        String styleKey;
        if (fixedStyleKey != null) {
            styleKey = fixedStyleKey;
        } else {
            styleKey = ContentType.styleKeyFor(contentTypeOf(message));
        }
        textArea.setSyntaxEditingStyle(styleKey);

        String text = model.getData();
        if (text != null) {
            if (target == Target.REQUEST_ALL || target == Target.RESPONSE_ALL) {
                text = PrettyPrint.formatMessage(ContentType.styleKeyFor(contentTypeOf(message)), text);
            } else if (target == Target.REQUEST_BODY || target == Target.RESPONSE_BODY) {
                text = PrettyPrint.formatBody(styleKey, text);
            }
        }
        textArea.setText(text);
        textArea.setCaretPosition(0);
        textArea.discardAllEdits();
    }

    private String contentTypeOf(Message message) {
        if (!(message instanceof HttpMessage)) {
            return null;
        }
        HttpMessage httpMessage = (HttpMessage) message;
        switch (target) {
            case REQUEST_BODY:
            case REQUEST_ALL:
                return httpMessage.getRequestHeader().getHeader("Content-Type");
            case RESPONSE_BODY:
            case RESPONSE_ALL:
                return httpMessage.getResponseHeader().getHeader("Content-Type");
            default:
                return null;
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getCaptionName() {
        return Constant.messages.getString(captionKey);
    }

    @Override
    public String getTargetViewName() {
        return "HttpPanelTextView";
    }

    @Override
    public int getPosition() {
        return Integer.MIN_VALUE;
    }

    @Override
    public JComponent getPane() {
        return mainPanel;
    }

    @Override
    public void setSelected(boolean selected) {
        if (selected) {
            textArea.requestFocusInWindow();
        }
    }

    @Override
    public void save() {
        // Read-only view.
    }

    @Override
    public HttpPanelViewModel getModel() {
        return model;
    }

    @Override
    public boolean isEnabled(Message message) {
        return true;
    }

    @Override
    public boolean hasChanged() {
        return false;
    }

    @Override
    public boolean isEditable() {
        return false;
    }

    @Override
    public void setEditable(boolean editable) {
        // Read-only view.
    }

    @Override
    public void setParentConfigurationKey(String configurationKey) {
        this.configurationKey = configurationKey + name + ".";
    }

    @Override
    public void loadConfiguration(FileConfiguration fileConfiguration) {
        // No configurable state.
    }

    @Override
    public void saveConfiguration(FileConfiguration fileConfiguration) {
        // No configurable state.
    }

    @Override
    public void dataChanged(HttpPanelViewModelEvent e) {
        update();
    }

    @Override
    public void search(Pattern pattern, List<SearchMatch> matches) {
        Matcher matcher = pattern.matcher(textArea.getText());
        while (matcher.find()) {
            matches.add(new SearchMatch(location(), matcher.start(), matcher.end()));
        }
    }

    @Override
    public void highlight(SearchMatch searchMatch) {
        int start = searchMatch.getStart();
        int end = searchMatch.getEnd();
        int length = textArea.getDocument().getLength();
        if (start < 0 || end > length || start >= end) {
            return;
        }
        try {
            Highlighter highlighter = textArea.getHighlighter();
            highlighter.removeAllHighlights();
            highlighter.addHighlight(
                    start,
                    end,
                    new DefaultHighlighter.DefaultHighlightPainter(DisplayUtils.getHighlightColor()));
            textArea.setCaretPosition(start);
        } catch (Exception ignored) {
            // Invalid offsets - nothing to highlight.
        }
    }

    private SearchMatch.Location location() {
        switch (target) {
            case REQUEST_BODY:
                return SearchMatch.Location.REQUEST_BODY;
            case RESPONSE_HEAD:
                return SearchMatch.Location.RESPONSE_HEAD;
            case RESPONSE_BODY:
                return SearchMatch.Location.RESPONSE_BODY;
            default:
                return SearchMatch.Location.REQUEST_HEAD;
        }
    }
}
