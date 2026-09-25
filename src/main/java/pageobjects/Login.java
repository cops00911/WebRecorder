package pageobjects;

import com.microsoft.playwright.Page;

public class Login {
    private Page page;

    public Login(Page page) {
        this.page = page;
    }

    // ===== LOCATORS =====
    public static final String LOGIN_WITH_MICROSOFT_BUTTON = "text=Login with Microsoft";
    public static final String LOGINFMT = "[name='loginfmt']";
    public static final String IDSIBUTTON9 = "#idSIButton9";
    public static final String PASSWD = "[name='passwd']";
}
