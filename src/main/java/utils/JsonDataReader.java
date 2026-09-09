package utils;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Utility class to read test data from JSON files for TestNG @DataProvider.
 */
public class JsonDataReader {

    /**
     * Reads a JSON array of objects from a file and converts it into a 2D Object array for TestNG DataProvider.
     *
     * @param filePath Path to the JSON file
     * @return 2D Object array containing row data
     */
    public static Object[][] loadData(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                System.err.println("[JsonDataReader Warning] Test data file not found: " + filePath);
                return new Object[0][0];
            }

            String content = new String(Files.readAllBytes(file.toPath())).trim();
            if (content.isEmpty()) {
                return new Object[0][0];
            }

            if (content.startsWith("[")) {
                content = content.substring(1);
            }
            if (content.endsWith("]")) {
                content = content.substring(0, content.length() - 1);
            }

            List<List<String>> rows = new ArrayList<>();
            String[] objects = content.split("\\}\\s*,\\s*\\{");

            for (String objStr : objects) {
                objStr = objStr.replace("{", "").replace("}", "").trim();
                List<String> rowValues = new ArrayList<>();
                String[] pairs = objStr.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                for (String pair : pairs) {
                    String[] kv = pair.split(":(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                    if (kv.length == 2) {
                        String val = kv[1].trim();
                        if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                            val = val.substring(1, val.length() - 1);
                        }
                        val = val.replace("\\\"", "\"").replace("\\\\", "\\");
                        rowValues.add(val);
                    }
                }
                if (!rowValues.isEmpty()) {
                    rows.add(rowValues);
                }
            }

            if (rows.isEmpty()) {
                return new Object[0][0];
            }

            int rowCount = rows.size();
            int colCount = rows.get(0).size();
            Object[][] data = new Object[rowCount][colCount];

            for (int i = 0; i < rowCount; i++) {
                List<String> row = rows.get(i);
                for (int j = 0; j < Math.min(colCount, row.size()); j++) {
                    data[i][j] = row.get(j);
                }
            }

            return data;
        } catch (Exception e) {
            System.err.println("[JsonDataReader Error] Failed to read test data from " + filePath + ": " + e.getMessage());
            return new Object[0][0];
        }
    }
}
