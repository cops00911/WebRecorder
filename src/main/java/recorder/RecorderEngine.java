package recorder;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores recorded actions and applies coalescing logic:
 * consecutive INPUT actions on the same locator are merged
 * (only the last typed value is kept).
 */
public class RecorderEngine {

    private final List<ActionModel> actions = new ArrayList<>();

    public void addAction(ActionModel action) {
        // Coalesce 1: Consecutive CLICKs on the exact same locator
        if (action.type == ActionType.CLICK && !actions.isEmpty()) {
            ActionModel last = actions.get(actions.size() - 1);
            if (last.type == ActionType.CLICK && last.locator.equals(action.locator)) {
                return; // skip duplicate click
            }
        }

        // Coalesce 2: INPUT on same locator as previous CLICK → remove redundant CLICK
        // (Playwright fill() automatically focuses and clears the element)
        if (action.type == ActionType.INPUT && !actions.isEmpty()) {
            ActionModel last = actions.get(actions.size() - 1);
            if (last.type == ActionType.CLICK && last.locator.equals(action.locator)) {
                actions.remove(actions.size() - 1);
            }
        }

        // Coalesce 3: Consecutive INPUT actions on the same locator → replace with latest value
        if (action.type == ActionType.INPUT && !actions.isEmpty()) {
            ActionModel last = actions.get(actions.size() - 1);
            if (last.type == ActionType.INPUT && last.locator.equals(action.locator)) {
                actions.remove(actions.size() - 1);
            }
        }

        // Coalesce 4: Ignore duplicate consecutive NAVIGATE actions
        if (action.type == ActionType.NAVIGATE && !actions.isEmpty()) {
            ActionModel last = actions.get(actions.size() - 1);
            if (last.type == ActionType.NAVIGATE && last.locator.equals(action.locator)) {
                return; // skip exact duplicate
            }
            if (last.type == ActionType.CLICK) {
                return; // Skip NAVIGATE triggered by CLICK
            }
        }

        // ── Hover filtering ─────────────────────────────────────────────
        // Rule 1: CLICK on same locator as previous HOVER → remove the HOVER.
        if (action.type == ActionType.CLICK && !actions.isEmpty()) {
            ActionModel last = actions.get(actions.size() - 1);
            if (last.type == ActionType.HOVER && last.locator.equals(action.locator)) {
                actions.remove(actions.size() - 1);
            }
        }
        // Rule 2: HOVER on same locator as previous CLICK → skip the HOVER.
        if (action.type == ActionType.HOVER && !actions.isEmpty()) {
            ActionModel last = actions.get(actions.size() - 1);
            if (last.type == ActionType.CLICK && last.locator.equals(action.locator)) {
                return; // skip — already clicked it
            }
        }
        actions.add(action);
    }

    /**
     * Auto-checks and sanitizes action sequences before exporting/running:
     * - Removes CLICKs immediately preceding INPUT on the same locator.
     * - Removes premature submit CLICKs if form fields were re-entered before final submit.
     * - Coalesces consecutive duplicate actions.
     */
    public static List<ActionModel> optimizeActions(List<ActionModel> rawList) {
        if (rawList == null || rawList.isEmpty()) return new ArrayList<>();

        List<ActionModel> result = new ArrayList<>();
        for (ActionModel a : rawList) {
            // Apply duplicate CLICK filter
            if (a.type == ActionType.CLICK && !result.isEmpty()) {
                ActionModel last = result.get(result.size() - 1);
                if (last.type == ActionType.CLICK && last.locator.equals(a.locator)) {
                    continue; // skip duplicate click
                }
            }

            // Apply CLICK before INPUT removal
            if (a.type == ActionType.INPUT && !result.isEmpty()) {
                ActionModel last = result.get(result.size() - 1);
                if (last.type == ActionType.CLICK && last.locator.equals(a.locator)) {
                    result.remove(result.size() - 1);
                }
            }

            // Coalesce consecutive INPUT on same locator
            if (a.type == ActionType.INPUT && !result.isEmpty()) {
                ActionModel last = result.get(result.size() - 1);
                if (last.type == ActionType.INPUT && last.locator.equals(a.locator)) {
                    result.set(result.size() - 1, a);
                    continue;
                }
            }

            // Detect and remove premature submit click (only if user retried the exact same field before submit)
            // Do NOT remove if 'prev' was following an input on a different field (e.g. multi-step wizard / Next button)
            if (a.type == ActionType.CLICK && result.size() >= 3) {
                ActionModel last = result.get(result.size() - 1);
                ActionModel prev = result.get(result.size() - 2);
                ActionModel beforePrev = result.get(result.size() - 3);
                if (last.type == ActionType.INPUT && prev.type == ActionType.CLICK && prev.locator.equals(a.locator)) {
                    if (beforePrev.type == ActionType.INPUT && beforePrev.locator.equals(last.locator)) {
                        result.remove(result.size() - 2);
                    }
                }
            }

            result.add(a);
        }

        return result;
    }

    public void undoLast() {
        if (!actions.isEmpty()) {
            ActionModel removed = actions.remove(actions.size() - 1);
            System.out.println("  ↩ Undone: " + removed);
        } else {
            System.out.println("  Nothing to undo.");
        }
    }

    /**
     * Replaces the locator on the most recently added action.
     * Called by WebRecorder after ensureUniqueLocator() finds a better locator.
     */
    public void replaceLastLocator(String newLocator) {
        if (actions.isEmpty()) return;
        ActionModel old = actions.get(actions.size() - 1);
        ActionModel updated = new ActionModel(
            old.type,
            newLocator,
            old.value,
            old.description.replace(old.locator, newLocator),
            old.isPopup
        );
        actions.set(actions.size() - 1, updated);
    }

    public void addComment(String text) {
        actions.add(new ActionModel(ActionType.COMMENT, "", text, "// " + text));
    }

    public List<ActionModel> getActions() {
        return new ArrayList<>(actions);
    }

    public int size() {
        return actions.size();
    }

    public void printSummary() {
        System.out.println("\n  --- Recorded Actions (" + actions.size() + ") ---");
        for (int i = 0; i < actions.size(); i++) {
            System.out.printf("  [%2d] %s%n", i + 1, actions.get(i));
        }
        System.out.println("  ----------------------------");
    }
}
