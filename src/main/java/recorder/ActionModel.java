package recorder;

/**
 * Represents a single recorded browser action.
 */
public class ActionModel {

    public final ActionType type;

    /**
     * The Playwright locator string for this element,
     * e.g. page.locator("#username") or page.getByRole(AriaRole.BUTTON, ...)
     * For NAVIGATE: the URL string.
     * For COMMENT: the comment text.
     */
    public final String locator;

    /**
     * The value associated with the action:
     * - INPUT: the text to type
     * - SELECT: the option value
     * - NAVIGATE: same as locator (URL)
     * - PRESS: the key name, e.g. "Enter"
     * - CLICK / CHECK / UNCHECK / COMMENT: empty string
     */
    public final String value;

    /** Raw description shown in the CLI during recording. */
    public final String description;

    /** True if this action was performed on a popup window or secondary tab */
    public final boolean isPopup;

    public ActionModel(ActionType type, String locator, String value, String description) {
        this(type, locator, value, description, false);
    }

    public ActionModel(ActionType type, String locator, String value, String description, boolean isPopup) {
        this.type = type;
        this.locator = locator;
        this.value = value;
        this.description = description;
        this.isPopup = isPopup;
    }

    @Override
    public String toString() {
        return "[" + type + "] " + description;
    }
}
