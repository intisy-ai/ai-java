package io.github.intisy.ai.examples.support;

import io.github.intisy.ai.shared.logic.Notifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A {@link Notifier} that collects user-facing notices in memory instead of writing them to disk.
 * The default JVM notifier for a file-backed store is the {@code JsonlNotifier} (see NotifierDemo);
 * injecting this one instead shows the notifier is fully swappable: a server that surfaces notices
 * through its own channel (a websocket, a message bus, ...) just implements {@link Notifier}.
 */
public final class CollectingNotifier implements Notifier {

    /** One collected notice: the message and the level the engine tagged it with. */
    public static final class Notice {
        public final String message;
        public final String level;

        public Notice(String message, String level) {
            this.message = message;
            this.level = level;
        }
    }

    private final List<Notice> notices = new ArrayList<>();

    @Override
    public void notify(String message, String level) {
        notices.add(new Notice(message, level));
    }

    public List<Notice> notices() {
        return Collections.unmodifiableList(notices);
    }
}
