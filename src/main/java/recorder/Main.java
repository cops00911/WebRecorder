package recorder;

import java.util.Scanner;

/**
 * Entry point for the Web Recorder.
 *
 * Run via: mvn exec:java
 * or: java -cp target/... recorder.Main
 */
public class Main {

    public static void main(String[] args) throws Exception {

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║       Web Recorder — Playwright Java Codegen     ║");
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.println();

        Scanner sc = new Scanner(System.in);

        // Resolve project root (directory of this JAR / class output)
        String projectRoot = resolveProjectRoot();

        System.out.print("  Do you want to resume recording from an existing script? (y/N): ");
        String resumeChoice = sc.nextLine().trim().toLowerCase();
        boolean resume = resumeChoice.equals("y") || resumeChoice.equals("yes");

        String url = "https://dev-tapral.techies.work/Central";
        String pageObjectName = "GeneratedWebPage";
        java.util.List<ActionModel> preRecordedActions = new java.util.ArrayList<>();

        if (resume) {
            preRecordedActions = WebRecorder.parseExistingTest(projectRoot);
            if (!preRecordedActions.isEmpty()) {
                for (ActionModel a : preRecordedActions) {
                    if (a.type == ActionType.NAVIGATE) {
                        url = a.locator;
                        break;
                    }
                }
                try {
                    java.io.File testFile = new java.io.File(projectRoot + "/src/test/java/recorder/GeneratedWebTest.java");
                    if (testFile.exists()) {
                        String testContent = new String(java.nio.file.Files.readAllBytes(testFile.toPath()));
                        java.util.regex.Pattern p = java.util.regex.Pattern.compile("import pageobjects\\.(\\w+);");
                        java.util.regex.Matcher m = p.matcher(testContent);
                        if (m.find()) {
                            pageObjectName = m.group(1);
                        }
                    }
                } catch (Exception e) {}
                System.out.println("  [Resume Mode] Found existing script. Page Object: " + pageObjectName);
            } else {
                System.out.println("  [Resume Mode] No existing script found. Starting fresh.");
                resume = false;
            }
        }

        if (!resume) {
            // ── URL ────────────────────────────────────────────────────────────
            System.out.print("  Enter target URL [https://dev-tapral.techies.work/Central]: ");
            url = sc.nextLine().trim();
            if (url.isEmpty()) {
                url = "https://dev-tapral.techies.work/Central";
            }
            if (!url.startsWith("http")) {
                url = "https://" + url;
            }
        }

        // ── Browser ────────────────────────────────────────────────────────
        System.out.print("  Choose browser  [chrome / firefox / webkit]  (default: chrome): ");
        String browser = sc.nextLine().trim().toLowerCase();
        if (!browser.equals("firefox") && !browser.equals("webkit")) {
            browser = "chrome";
        }

        if (!resume) {
            // ── Page Object Name ───────────────────────────────────────────────
            System.out.print("  Enter Page Object name [e.g. PartnerPage] (default: GeneratedWebPage): ");
            pageObjectName = sc.nextLine().trim();
            pageObjectName = cleanClassName(pageObjectName);
        }

        boolean startPaused = false;
        if (!resume) {
            System.out.print("  Start recording immediately? (Y/n): ");
            String pausedChoice = sc.nextLine().trim().toLowerCase();
            startPaused = pausedChoice.equals("n") || pausedChoice.equals("no");
        }

        System.out.println();
        System.out.println("  URL              : " + url);
        System.out.println("  Browser          : " + browser);
        System.out.println("  Page Object Name : " + pageObjectName);
        System.out.println("  Output (Page)    : " + projectRoot + "/src/main/java/pageobjects/" + pageObjectName + ".java");
        System.out.println("  Output (Test)    : " + projectRoot + "/src/test/java/recorder/GeneratedWebTest.java");
        System.out.println();

        WebRecorder recorder = new WebRecorder(url, browser, projectRoot, pageObjectName);
        if (resume) {
            recorder.start(preRecordedActions, false);
        } else {
            recorder.start(new java.util.ArrayList<>(), startPaused);
        }
    }

    private static String cleanClassName(String name) {
        String cleaned = name.replaceAll("[^a-zA-Z0-9_]", "");
        if (cleaned.isEmpty()) {
            return "GeneratedWebPage";
        }
        if (Character.isDigit(cleaned.charAt(0))) {
            cleaned = "Page" + cleaned;
        }
        return Character.toUpperCase(cleaned.charAt(0)) + (cleaned.length() > 1 ? cleaned.substring(1) : "");
    }

    /**
     * Detect the Maven project root by walking up from the current working
     * directory
     * until we find a pom.xml. Falls back to the current directory.
     */
    private static String resolveProjectRoot() {
        java.io.File dir = new java.io.File(System.getProperty("user.dir"));
        while (dir != null) {
            if (new java.io.File(dir, "pom.xml").exists()) {
                return dir.getAbsolutePath();
            }
            dir = dir.getParentFile();
        }
        return System.getProperty("user.dir");
    }
}
