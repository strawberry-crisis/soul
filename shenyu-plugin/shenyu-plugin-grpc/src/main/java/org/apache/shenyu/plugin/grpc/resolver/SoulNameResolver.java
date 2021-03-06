/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shenyu.plugin.grpc.resolver;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import io.grpc.Attributes;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.EquivalentAddressGroup;
import io.grpc.SynchronizationContext;
import io.grpc.internal.SharedResourceHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.shenyu.plugin.grpc.loadbalance.GrpcAttributeUtils;
import org.apache.shenyu.plugin.grpc.cache.ApplicationConfigCache;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * SoulNameResolver.
 *
 * @author zhanglei
 */
@Slf4j
public class SoulNameResolver extends NameResolver implements Consumer<Object> {

    private boolean resolving;

    private Listener2 listener;

    private Executor executor;

    private final String appName;

    private final Attributes attributes;

    private final SynchronizationContext syncContext;

    private final List<SoulServiceInstance> keep = null;

    private List<SoulServiceInstance> instanceList = Lists.newArrayList();

    private final SharedResourceHolder.Resource<Executor> executorResource;

    public SoulNameResolver(final String appName, final Args args, final SharedResourceHolder.Resource<Executor> executorResource) {
        this.appName = appName;
        this.executor = args.getOffloadExecutor();
        this.executorResource = executorResource;
        this.attributes = Attributes.newBuilder().set(GrpcAttributeUtils.appName(), appName).build();
        this.syncContext = Objects.requireNonNull(args.getSynchronizationContext(), "syncContext");
    }

    @Override
    public void start(final Listener2 listener) {
        Preconditions.checkState(this.listener == null, "already started");
        this.executor = SharedResourceHolder.get(this.executorResource);
        this.listener = Preconditions.checkNotNull(listener, "listener");
        ApplicationConfigCache.getInstance().watch(appName, this);
        resolve();
    }

    @Override
    public void accept(final Object o) {
        syncContext.execute(() -> {
            if (this.listener != null) {
                resolve();
            }
        });
    }

    @Override
    public void refresh() {
        Preconditions.checkState(this.listener != null, "not started");
        resolve();
    }

    private void resolve() {
        log.info("Scheduled resolve for {}", this.appName);
        if (this.resolving) {
            return;
        }
        this.resolving = true;
        this.executor.execute(new Resolve(this.listener, this.instanceList));
    }

    @Override
    public String getServiceAuthority() {
        return appName;
    }

    @Override
    public void shutdown() {
        this.listener = null;
        if (this.executor != null) {
            this.executor = SharedResourceHolder.release(this.executorResource, this.executor);
        }
        this.instanceList = Lists.newArrayList();
    }

    private final class Resolve implements Runnable {

        private final Listener2 savedListener;

        private final List<SoulServiceInstance> savedInstanceList;

        Resolve(final Listener2 listener, final List<SoulServiceInstance> instanceList) {
            this.savedListener = Objects.requireNonNull(listener, "listener");
            this.savedInstanceList = Objects.requireNonNull(instanceList, "instanceList");
        }

        @Override
        public void run() {
            final AtomicReference<List<SoulServiceInstance>> resultContainer = new AtomicReference<>();
            try {
                resultContainer.set(resolveInternal());
            } catch (final Exception e) {
                this.savedListener.onError(Status.UNAVAILABLE.withCause(e)
                        .withDescription("Failed to update server list for " + SoulNameResolver.this.appName));
                resultContainer.set(Lists.newArrayList());
            } finally {
                SoulNameResolver.this.syncContext.execute(() -> {
                    SoulNameResolver.this.resolving = false;
                    final List<SoulServiceInstance> newInstanceList = resultContainer.get();
                    if (newInstanceList != keep && SoulNameResolver.this.listener != null) {
                        SoulNameResolver.this.instanceList = newInstanceList;
                    }
                });
            }
        }

        private List<SoulServiceInstance> resolveInternal() {
            final String name = SoulNameResolver.this.appName;
            SoulServiceInstanceLists soulServiceInstanceLists = ApplicationConfigCache.getInstance().get(name);
            List<SoulServiceInstance> newInstanceList = soulServiceInstanceLists.getCopyInstances();
            log.info("Got {} candidate servers for {}", newInstanceList.size(), name);
            if (CollectionUtils.isEmpty(newInstanceList)) {
                log.info("No servers found for {}", name);
                this.savedListener.onError(Status.UNAVAILABLE.withDescription("No servers found for " + name));
                return Lists.newArrayList();
            }
            if (!needsToUpdateConnections(newInstanceList)) {
                log.info("Nothing has changed... skipping update for {}", name);
                return null;
            }
            log.info("Ready to update server list for {}", name);
            final List<EquivalentAddressGroup> targets = newInstanceList.stream()
                    .map(instance -> {
                        log.info("Found gRPC server {}:{} for {}", instance.getHost(), instance.getPort(), name);
                        return SoulResolverHelper.convertToEquivalentAddressGroup(instance);
                    }).collect(Collectors.toList());
            this.savedListener.onResult(ResolutionResult.newBuilder()
                    .setAddresses(targets)
                    .setAttributes(attributes)
                    .build());
            log.info("Done updating server list for {}", name);
            return newInstanceList;
        }

        private boolean needsToUpdateConnections(final List<SoulServiceInstance> newInstanceList) {
            if (this.savedInstanceList.size() != newInstanceList.size()) {
                return true;
            }
            for (final SoulServiceInstance instance : this.savedInstanceList) {
                final String host = instance.getHost();
                final int port = instance.getPort();
                boolean isSame = newInstanceList.stream().anyMatch(newInstance -> host.equals(newInstance.getHost())
                        && port == newInstance.getPort()
                        && isMetadataEquals(instance.getMetadata(), newInstance.getMetadata()));
                if (!isSame) {
                    return true;
                }
            }
            return false;
        }

        private boolean isMetadataEquals(final Map<String, String> metadata, final Map<String, String> newMetadata) {
            final String[] keys = {"weight", "status"};
            for (String key : keys) {
                final String value = metadata.get(key);
                final String newValue = newMetadata.get(key);
                if (!Objects.equals(value, newValue)) {
                    return false;
                }
            }
            return true;
        }
    }
}
