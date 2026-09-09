package pageobjects;

import com.microsoft.playwright.Page;

public class RilomaLogin {
    private Page page;

    public RilomaLogin(Page page) {
        this.page = page;
    }

    // ===== LOCATORS =====
    public static final String IDENTIFIER = "[name='identifier']";
    public static final String PASSWORD = "[name='password']";
    public static final String LOGIN_BUTTON = "text=Login";
}
