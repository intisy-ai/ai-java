package io.github.intisy.ai.examples.support;

/**
 * Tiny console-formatting helper so every demo prints a consistent, scannable section header and
 * indented detail lines. Purely cosmetic, it exists only to keep the demo classes focused on the
 * AiJava usage rather than on {@code System.out} formatting.
 */
public final class Section {

    private Section() {
    }

    /**
     * Prints a section heading, so a demo's output reads as steps rather than a wall.
     *
     * @param title the heading
     */
    public static void header(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    /**
     * Prints one line under the current heading.
     *
     * @param line the line
     */
    public static void detail(String line) {
        System.out.println("   " + line);
    }
}
