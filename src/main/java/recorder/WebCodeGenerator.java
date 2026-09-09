package recorder;

import java.util.List;

/**
 * Converts a list of ActionModel objects into Playwright Java code lines.
 */
public class WebCodeGenerator {

    /**
     * Generate a single line (or two lines for INPUT) of Playwright Java code.
     */
    public static String toCode(ActionModel action) {
        switch (action.type) {
            case NAVIGATE:
                return "page.navigate(\"" + esc(action.locator) + "\");";

            case CLICK:
                return action.locator + ".click();";

            case INPUT:
                return action.locator + ".fill(\"" + esc(action.value) + "\");";

            case SELECT:
                return action.locator + ".selectOption(\"" + esc(action.value) + "\");";

            case CHECK:
                return action.locator + ".check();";

            case UNCHECK:
                return action.locator + ".uncheck();";

            case PRESS:
                return action.locator + ".press(\"" + esc(action.value) + "\");";

            case HOVER:
                return action.locator + ".hover();";

            case COMMENT:
                return "// " + action.value;

            default:
                return "// [UNKNOWN ACTION: " + action.type + "]";
        }
    }

    /** Build all code lines joined by newline + indent. */
    public static String buildTestBody(List<ActionModel> actions) {
        StringBuilder sb = new StringBuilder();
        for (ActionModel a : actions) {
            sb.append("        ").append(toCode(a)).append("\n");
        }
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
