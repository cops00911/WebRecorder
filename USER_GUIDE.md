# Web Recorder User Guide

This guide explains how to run the Web Recorder tool and execute the generated Playwright tests.

---

## Prerequisites
Ensure you have the following installed on your system:
- **Java JDK 11** or higher
- **Apache Maven**

---

## 1. How to Run the Web Recorder

The Web Recorder is a Java application that runs Playwright's codegen to record browser interactions and automatically save them as Java tests.

To run the recorder, execute the following command from the root of the project:

```bash
mvn exec:java
```

### Configuration Prompts
Upon running the command, you will be prompted in the terminal:
1. **Enter target URL**: The URL you want the browser to navigate to (e.g., `https://dev-tapral.techies.work/Central`).
2. **Choose browser**: Select `chrome` (default), `firefox`, or `webkit`.

After you enter the inputs, the tool opens a browser session and records your interactions.

### Output
The recorded session is automatically saved to the following file:
- `src/test/java/recorder/GeneratedWebTest.java`

---

## 2. How to Run the Recorded Tests

The generated tests are managed with TestNG. To run the recorded tests, run:

```bash
mvn test
```
This command compiles the test code, launches the browser (in headed mode by default, as configured in the setup), and runs all tests in the suite.



mvn exec:java
mvn test

