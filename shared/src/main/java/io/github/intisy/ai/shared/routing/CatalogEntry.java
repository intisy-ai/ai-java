package io.github.intisy.ai.shared.routing;

/**
 * A single catalog entry describing a provider/model combination available for routing.
 */
public class CatalogEntry {
    public String provider;
    public String model;
    public String name;
    public Double score;
    public Integer contextLimit;
    public Integer outputLimit;

    public CatalogEntry() {
    }

    public CatalogEntry(String provider, String model, String name, Double score, Integer contextLimit, Integer outputLimit) {
        this.provider = provider;
        this.model = model;
        this.name = name;
        this.score = score;
        this.contextLimit = contextLimit;
        this.outputLimit = outputLimit;
    }
}
