package io.github.intisy.ai.examples.support;

/**
 * Tiny console-formatting helper so every demo prints a consistent, scannable section header and
 * indented detail lines. Purely cosmetic, it exists only to keep the demo classes focused on the
 * AiJava usage rather than on {@code System.out} formatting.
 */
public final class Section {

    private Section() {
    }

    public static void header(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    public static void detail(String line) {
        System.out.println("   " + line);
    }
}
