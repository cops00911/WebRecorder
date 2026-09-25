package pageobjects;

import com.microsoft.playwright.Page;

public class Managment {
    private Page page;

    public Managment(Page page) {
        this.page = page;
    }

    // ===== LOCATORS =====
    public static final String USER_MANAGEMENT_BUTTON = "text=User Management";
    public static final String ASSIGN_ROLE_BUTTON = "text=+ Assign Role";
    public static final String DIV_CSS_19BB58M = "div.css-19bb58m";
    public static final String REACT_SELECT_2_INPUT = "#react-select-2-input";
    public static final String RACHIT_MEHTA_BUTTON = "text=Rachit Mehta";
    public static final String REC_RECORDING_ACTIVE_DASHBOARD_USER_MANAGEMENT_LOCATION_MANAGEMENT_VENDOR_M_BUTTON = "text=🔴 REC (Recording Active) Dashboard User Management Location Management Vendor M";
    public static final String ADMIN_TEAM_FINANCE_TEAM_BUTTON = "text=Admin Team Finance Team";
    public static final String CANCEL_BUTTON = "text=Cancel";
    public static final String CONTINUE_BUTTON = "text=Continue";
}
