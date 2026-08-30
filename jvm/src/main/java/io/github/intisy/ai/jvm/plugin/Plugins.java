package io.github.intisy.ai.jvm.plugin;

import io.github.intisy.ai.api.Api;
import io.github.intisy.ai.engine.CapabilityRecord;
import io.github.intisy.ai.engine.EventBus;
import io.github.intisy.ai.engine.ManifestFacts;
import io.github.intisy.ai.engine.PluginException;
import io.github.intisy.ai.engine.PluginHost;
import io.github.intisy.ai.engine.Scheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The plugin model this host runs, over the engine every other host in this ecosystem runs.
 *
 * @implNote A host asks for a capability id and gets back whatever provides it. Nothing here
 * branches on a plugin id or on a plugin CATEGORY, which is what lets a fourth kind of plugin
 * arrive without a line of host code: it registers under its own id and the lookups already work.
 *
 * <p>Discovery stays separate and stays category-specific, because {@code ServiceLoader} needs the
 * SPI class and no amount of indirection removes that. {@link JarServices} does the discovering;
 * this does the resolving.
 */
public final class Plugins {

    private final PluginHost host;
    private final EventBus bus = new InProcessBus();

    /**
     * A host that recognises one vocabulary of capabilities.
     *
     * @param app the id of the app this host is
     * @param capabilities the capability ids this host resolves, declared by the caller because the
     *                     library that mints an id is not the one that renders it
     */
    public Plugins(String app, List<String> capabilities) {
        this.host = new PluginHost(app, Api.API_VERSION, Collections.<String>emptyList());
        this.host.knownCapabilities(capabilities);
    }

    /**
     * Records that one plugin provides one capability.
     *
     * @param capabilityId the capability it provides
     * @param pluginId what identifies the plugin providing it
     * @param implementation what a host gets back when it resolves that capability
     * @throws PluginException when the activation does not match what was declared for it
     */
    public void register(String capabilityId, String pluginId, Object implementation) {
        ManifestFacts facts = new ManifestFacts(
                pluginId, Api.API_VERSION, Arrays.asList(capabilityId), Collections.<String>emptyList(), null);
        host.sessionFor(facts, bus).provide(capabilityId, implementation);
        PluginException failure = host.verifyActivation(facts);
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Every implementation of one capability, in registration order.
     *
     * @param capabilityId the capability to resolve
     * @param type what each implementation is expected to be
     * @param <T> that type
     * @return the implementations, empty when nothing provides it
     */
    public <T> List<T> resolve(String capabilityId, Class<T> type) {
        List<T> out = new ArrayList<T>();
        for (CapabilityRecord record : host.capability(capabilityId)) {
            Object implementation = record.getImplementation();
            if (type.isInstance(implementation)) {
                out.add(type.cast(implementation));
            }
        }
        return out;
    }

    /**
     * The one implementation registered under a plugin id, for a caller that knows which it wants.
     *
     * @param capabilityId the capability to resolve
     * @param pluginId the plugin whose implementation is wanted
     * @param type what the implementation is expected to be
     * @param <T> that type
     * @return it, or null when that plugin provides no such capability
     */
    public <T> T resolveOne(String capabilityId, String pluginId, Class<T> type) {
        for (CapabilityRecord record : host.capability(capabilityId)) {
            if (record.getPluginId().equals(pluginId) && type.isInstance(record.getImplementation())) {
                return type.cast(record.getImplementation());
            }
        }
        return null;
    }

    /**
     * The plugin ids providing one capability, in registration order.
     *
     * @param capabilityId the capability to resolve
     * @return their ids
     */
    public List<String> providerIds(String capabilityId) {
        List<String> out = new ArrayList<String>();
        for (CapabilityRecord record : host.capability(capabilityId)) {
            out.add(record.getPluginId());
        }
        return out;
    }

    /**
     * The engine's own host, for a caller that needs more of the plugin model than resolution.
     *
     * @return it
     */
    public PluginHost host() {
        return host;
    }

    /**
     * The bus a session subscribes through.
     *
     * @implNote In-process and synchronous: this host has no transport of its own, and the engine
     * only needs somewhere to record a subscription so a stopped plugin's listeners can be released.
     */
    private static final class InProcessBus implements EventBus {

        private final Map<String, List<Listener>> listeners = new LinkedHashMap<String, List<Listener>>();

        @Override
        public void publish(String topic, Object payload) {
            List<Listener> subscribed = listeners.get(topic);
            if (subscribed == null) {
                return;
            }
            for (Listener listener : new ArrayList<Listener>(subscribed)) {
                listener.received(payload);
            }
        }

        @Override
        public Scheduler.Cancellable subscribe(String topic, final Listener listener) {
            List<Listener> subscribed = listeners.get(topic);
            if (subscribed == null) {
                subscribed = new ArrayList<Listener>();
                listeners.put(topic, subscribed);
            }
            subscribed.add(listener);
            final List<Listener> owning = subscribed;
            return new Scheduler.Cancellable() {
                @Override
                public void cancel() {
                    owning.remove(listener);
                }
            };
        }
    }
}
