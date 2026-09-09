package recorder;

/**
 * Types of browser interactions that can be recorded.
 */
public enum ActionType {
    NAVIGATE,   // page.navigate(url)
    CLICK,      // element.click()
    INPUT,      // element.fill(value)
    SELECT,     // element.selectOption(value)
    CHECK,      // element.check()
    UNCHECK,    // element.uncheck()
    PRESS,      // element.press(key)
    HOVER,      // element.hover()
    COMMENT     // // user comment
}
