import * as fs from 'fs';
import * as path from 'path';

/**
 * DataProvider utility mimicking TestNG @DataProvider behavior.
 * Loads test datasets from JSON files in fixtures or custom paths.
 */
export class DataProvider {
  /**
   * Load JSON test data from the fixtures folder or an absolute/relative path.
   * @param relativeOrAbsolutePath File path relative to typescript-tests/fixtures or workspace
   */
  static loadJson<T = any>(filePath: string): T[] {
    let resolvedPath = filePath;
    if (!path.isAbsolute(filePath)) {
      // Check fixtures directory first
      const fixturesPath = path.resolve(__dirname, '../../fixtures', filePath);
      if (fs.existsSync(fixturesPath)) {
        resolvedPath = fixturesPath;
      } else {
        resolvedPath = path.resolve(process.cwd(), filePath);
      }
    }

    if (!fs.existsSync(resolvedPath)) {
      throw new Error(`[DataProvider] Test data file not found at: ${resolvedPath}`);
    }

    const raw = fs.readFileSync(resolvedPath, 'utf8');
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [parsed];
  }
}
