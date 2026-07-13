package io.github.intisy.ai.shared.routing;

/**
 * A resolved provider/model assignment for a request.
 */
public class Assignment {
    public String provider;
    public String model;
    public String name;
    public boolean derived;

    public Assignment() {
    }

    public Assignment(String provider, String model, String name, boolean derived) {
        this.provider = provider;
        this.model = model;
        this.name = name;
        this.derived = derived;
    }
}
