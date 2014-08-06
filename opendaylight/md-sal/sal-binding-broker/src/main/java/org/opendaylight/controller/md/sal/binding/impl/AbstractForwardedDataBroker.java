/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.md.sal.binding.impl;

import com.google.common.base.Objects;
import com.google.common.base.Optional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.opendaylight.controller.md.sal.binding.api.DataChangeListener;
import org.opendaylight.controller.md.sal.common.api.data.AsyncDataBroker.DataChangeScope;
import org.opendaylight.controller.md.sal.common.api.data.AsyncDataChangeEvent;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.dom.api.DOMDataBroker;
import org.opendaylight.controller.md.sal.dom.api.DOMDataChangeListener;
import org.opendaylight.controller.sal.binding.impl.connect.dom.BindingIndependentConnector;
import org.opendaylight.controller.sal.binding.impl.forward.DomForwardedBroker;
import org.opendaylight.controller.sal.core.api.Broker.ProviderSession;
import org.opendaylight.controller.sal.core.api.model.SchemaService;
import org.opendaylight.yangtools.concepts.AbstractListenerRegistration;
import org.opendaylight.yangtools.concepts.Delegator;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.data.impl.codec.BindingIndependentMappingService;
import org.opendaylight.yangtools.yang.data.impl.codec.DeserializationException;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;
import org.opendaylight.yangtools.yang.model.api.SchemaContextListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractForwardedDataBroker implements Delegator<DOMDataBroker>, DomForwardedBroker, SchemaContextListener, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractForwardedDataBroker.class);
    // The Broker to whom we do all forwarding
    private final DOMDataBroker domDataBroker;

    // Mapper to convert from Binding Independent objects to Binding Aware
    // objects
    private final BindingIndependentMappingService mappingService;

    private final BindingToNormalizedNodeCodec codec;
    private BindingIndependentConnector connector;
    private ProviderSession context;
    private final ListenerRegistration<SchemaContextListener> schemaListenerRegistration;

    protected AbstractForwardedDataBroker(final DOMDataBroker domDataBroker,
            final BindingIndependentMappingService mappingService,final SchemaService schemaService) {
        this.domDataBroker = domDataBroker;
        this.mappingService = mappingService;
        this.codec = new BindingToNormalizedNodeCodec(mappingService);
        this.schemaListenerRegistration = schemaService.registerSchemaContextListener(this);
    }

    protected BindingToNormalizedNodeCodec getCodec() {
        return codec;
    }

    protected BindingIndependentMappingService getMappingService() {
        return mappingService;
    }

    @Override
    public DOMDataBroker getDelegate() {
        return domDataBroker;
    }

    @Override
    public void onGlobalContextUpdated(final SchemaContext ctx) {
        codec.onGlobalContextUpdated(ctx);
    }

    public ListenerRegistration<DataChangeListener> registerDataChangeListener(final LogicalDatastoreType store,
            final InstanceIdentifier<?> path, final DataChangeListener listener,
            final DataChangeScope triggeringScope) {
        DOMDataChangeListener domDataChangeListener = new TranslatingDataChangeInvoker(store, path, listener,
                triggeringScope);
        YangInstanceIdentifier domPath = codec.toNormalized(path);
        ListenerRegistration<DOMDataChangeListener> domRegistration = domDataBroker.registerDataChangeListener(store,
                domPath, domDataChangeListener, triggeringScope);
        return new ListenerRegistrationImpl(listener, domRegistration);
    }

    protected Map<InstanceIdentifier<?>, DataObject> toBinding(
            InstanceIdentifier<?> path,
            final Map<YangInstanceIdentifier, ? extends NormalizedNode<?, ?>> normalized) {
        Map<InstanceIdentifier<?>, DataObject> newMap = new HashMap<>();

        for (Map.Entry<YangInstanceIdentifier, ? extends NormalizedNode<?, ?>> entry : sortedEntries(normalized)) {
            try {
                Optional<Entry<InstanceIdentifier<? extends DataObject>, DataObject>> potential = getCodec().toBinding(
                        entry);
                if (potential.isPresent()) {
                    Entry<InstanceIdentifier<? extends DataObject>, DataObject> binding = potential.get();
                    newMap.put(binding.getKey(), binding.getValue());
                } else if (entry.getKey().getLastPathArgument() instanceof YangInstanceIdentifier.AugmentationIdentifier) {
                    DataObject bindingDataObject = getCodec().toBinding(path, entry.getValue());
                    if (bindingDataObject != null) {
                        newMap.put(path, bindingDataObject);
                    }
                }
            } catch (DeserializationException e) {
                LOG.warn("Failed to transform {}, omitting it", entry, e);
            }
        }
        return newMap;
    }

    private static final Comparator<Entry<YangInstanceIdentifier, ?>> MAP_ENTRY_COMPARATOR = new Comparator<Entry<YangInstanceIdentifier, ?>>() {
        @Override
        public int compare(final Entry<YangInstanceIdentifier, ?> left,
                final Entry<YangInstanceIdentifier, ?> right) {
            final Iterator<?> li = left.getKey().getPathArguments().iterator();
            final Iterator<?> ri = right.getKey().getPathArguments().iterator();

            // Iterate until left is exhausted...
            while (li.hasNext()) {
                if (!ri.hasNext()) {
                    // Left is deeper
                    return 1;
                }

                li.next();
                ri.next();
            }

            // Check if right is exhausted
            return ri.hasNext() ? -1 : 0;
        }
    };

    private static <T> Iterable<Entry<YangInstanceIdentifier,T>> sortedEntries(final Map<YangInstanceIdentifier, T> map) {
        if (!map.isEmpty()) {
            ArrayList<Entry<YangInstanceIdentifier, T>> entries = new ArrayList<>(map.entrySet());
            Collections.sort(entries, MAP_ENTRY_COMPARATOR);
            return entries;
        } else {
            return Collections.emptySet();
        }
    }

    protected Set<InstanceIdentifier<?>> toBinding(InstanceIdentifier<?> path,
            final Set<YangInstanceIdentifier> normalized) {
        Set<InstanceIdentifier<?>> hashSet = new HashSet<>();
        for (YangInstanceIdentifier normalizedPath : normalized) {
            try {
                Optional<InstanceIdentifier<? extends DataObject>> potential = getCodec().toBinding(normalizedPath);
                if (potential.isPresent()) {
                    InstanceIdentifier<? extends DataObject> binding = potential.get();
                    hashSet.add(binding);
                } else if (normalizedPath.getLastPathArgument() instanceof YangInstanceIdentifier.AugmentationIdentifier) {
                    hashSet.add(path);
                }
            } catch (DeserializationException e) {
                LOG.warn("Failed to transform {}, omitting it", normalizedPath, e);
            }
        }
        return hashSet;
    }

    protected Optional<DataObject> toBindingData(final InstanceIdentifier<?> path, final NormalizedNode<?, ?> data) {
        if (path.isWildcarded()) {
            return Optional.absent();
        }

        try {
            return Optional.fromNullable(getCodec().toBinding(path, data));
        } catch (DeserializationException e) {
            return Optional.absent();
        }
    }

    private class TranslatingDataChangeInvoker implements DOMDataChangeListener {
        private final DataChangeListener bindingDataChangeListener;
        private final LogicalDatastoreType store;
        private final InstanceIdentifier<?> path;
        private final DataChangeScope triggeringScope;

        public TranslatingDataChangeInvoker(final LogicalDatastoreType store, final InstanceIdentifier<?> path,
                final DataChangeListener bindingDataChangeListener, final DataChangeScope triggeringScope) {
            this.store = store;
            this.path = path;
            this.bindingDataChangeListener = bindingDataChangeListener;
            this.triggeringScope = triggeringScope;
        }

        @Override
        public void onDataChanged(
                final AsyncDataChangeEvent<YangInstanceIdentifier, NormalizedNode<?, ?>> change) {
            bindingDataChangeListener.onDataChanged(new TranslatedDataChangeEvent(change, path));
        }
    }

    private class TranslatedDataChangeEvent implements AsyncDataChangeEvent<InstanceIdentifier<?>, DataObject> {
        private final AsyncDataChangeEvent<YangInstanceIdentifier, NormalizedNode<?, ?>> domEvent;
        private final InstanceIdentifier<?> path;

        private Map<InstanceIdentifier<?>, DataObject> createdCache;
        private Map<InstanceIdentifier<?>, DataObject> updatedCache;
        private Map<InstanceIdentifier<?>, DataObject> originalCache;
        private Set<InstanceIdentifier<?>> removedCache;
        private Optional<DataObject> originalDataCache;
        private Optional<DataObject> updatedDataCache;

        public TranslatedDataChangeEvent(
                final AsyncDataChangeEvent<YangInstanceIdentifier, NormalizedNode<?, ?>> change,
                final InstanceIdentifier<?> path) {
            this.domEvent = change;
            this.path = path;
        }

        @Override
        public Map<InstanceIdentifier<?>, DataObject> getCreatedData() {
            if (createdCache == null) {
                createdCache = Collections.unmodifiableMap(toBinding(path, domEvent.getCreatedData()));
            }
            return createdCache;
        }

        @Override
        public Map<InstanceIdentifier<?>, DataObject> getUpdatedData() {
            if (updatedCache == null) {
                updatedCache = Collections.unmodifiableMap(toBinding(path, domEvent.getUpdatedData()));
            }
            return updatedCache;

        }

        @Override
        public Set<InstanceIdentifier<?>> getRemovedPaths() {
            if (removedCache == null) {
                removedCache = Collections.unmodifiableSet(toBinding(path, domEvent.getRemovedPaths()));
            }
            return removedCache;
        }

        @Override
        public Map<InstanceIdentifier<?>, DataObject> getOriginalData() {
            if (originalCache == null) {
                originalCache = Collections.unmodifiableMap(toBinding(path, domEvent.getOriginalData()));
            }
            return originalCache;

        }

        @Override
        public DataObject getOriginalSubtree() {
            if (originalDataCache == null) {
                if(domEvent.getOriginalSubtree() != null) {
                    originalDataCache = toBindingData(path, domEvent.getOriginalSubtree());
                } else {
                    originalDataCache = Optional.absent();
                }
            }
            return originalDataCache.orNull();
        }

        @Override
        public DataObject getUpdatedSubtree() {
            if (updatedDataCache == null) {
                if(domEvent.getUpdatedSubtree() != null) {
                    updatedDataCache = toBindingData(path, domEvent.getUpdatedSubtree());
                } else {
                    updatedDataCache = Optional.absent();
                }
            }
            return updatedDataCache.orNull();
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(TranslatedDataChangeEvent.class) //
                    .add("created", getCreatedData()) //
                    .add("updated", getUpdatedData()) //
                    .add("removed", getRemovedPaths()) //
                    .add("dom", domEvent) //
                    .toString();
        }
    }

    private static class ListenerRegistrationImpl extends AbstractListenerRegistration<DataChangeListener> {
        private final ListenerRegistration<DOMDataChangeListener> registration;

        public ListenerRegistrationImpl(final DataChangeListener listener,
                final ListenerRegistration<DOMDataChangeListener> registration) {
            super(listener);
            this.registration = registration;
        }

        @Override
        protected void removeRegistration() {
            registration.close();
        }
    }

    @Override
    public BindingIndependentConnector getConnector() {
        return this.connector;
    }

    @Override
    public ProviderSession getDomProviderContext() {
        return this.context;
    }

    @Override
    public void setConnector(final BindingIndependentConnector connector) {
        this.connector = connector;
    }

    @Override
    public void setDomProviderContext(final ProviderSession domProviderContext) {
        this.context = domProviderContext;
    }

    @Override
    public void startForwarding() {
        // NOOP
    }

    @Override
    public void close() throws Exception {
        this.schemaListenerRegistration.close();
    }

}
