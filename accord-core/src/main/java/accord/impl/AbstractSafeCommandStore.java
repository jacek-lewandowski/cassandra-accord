/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package accord.impl;

import accord.api.VisibleForImplementation;
import accord.impl.CommandsForKey.CommandLoader;
import accord.local.*;
import accord.primitives.*;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

public abstract class AbstractSafeCommandStore<CommandType extends SafeCommand, CommandsForKeyType extends SafeCommandsForKey> implements SafeCommandStore
{
    private static class PendingRegistration<T>
    {
        final T value;
        final Ranges slice;
        final TxnId txnId;

        public PendingRegistration(T value, Ranges slice, TxnId txnId)
        {
            this.value = value;
            this.slice = slice;
            this.txnId = txnId;
        }
    }
    protected final PreLoadContext context;

    private List<PendingRegistration<Seekable>> pendingSeekableRegistrations = null;
    private List<PendingRegistration<Seekables<?, ?>>> pendingSeekablesRegistrations = null;

    public AbstractSafeCommandStore(PreLoadContext context)
    {
        this.context = context;
    }

    protected abstract CommandType getCommandInternal(TxnId txnId);
    protected abstract void addCommandInternal(CommandType command);

    protected abstract CommandsForKeyType getCommandsForKeyInternal(RoutableKey key);
    protected abstract void addCommandsForKeyInternal(CommandsForKeyType cfk);

    protected abstract CommandType getIfLoaded(TxnId txnId);

    private static <K, V> V getIfLoaded(K key, Function<K, V> get, Consumer<V> add, Function<K, V> getIfLoaded)
    {
        V value = get.apply(key);
        if (value != null)
            return value;

        value = getIfLoaded.apply(key);
        if (value == null)
            return null;
        add.accept(value);
        return value;
    }

    @Override
    public CommandType ifPresent(TxnId txnId)
    {
        CommandType command = getCommandInternal(txnId);
        if (command == null)
            throw new IllegalStateException(String.format("%s was not specified in PreLoadContext", txnId));
        if (command.isEmpty())
            return null;
        return command;
    }

    @Override
    public CommandType ifLoaded(TxnId txnId)
    {
        CommandType command = getIfLoaded(txnId, this::getCommandInternal, this::addCommandInternal, this::getIfLoaded);
        if (command == null)
            return null;
        if (command.isEmpty())
            command.notWitnessed();
        return command;
    }

    @Override
    public CommandType command(TxnId txnId)
    {
        CommandType command = getCommandInternal(txnId);
        if (command == null)
            throw new IllegalStateException(String.format("%s was not specified in PreLoadContext", txnId));
        if (command.isEmpty())
            command.notWitnessed();
        return command;
    }

    protected abstract CommandLoader<?> cfkLoader();

    public CommandsForKeyType ifLoaded(RoutableKey key)
    {
        CommandsForKeyType cfk = getIfLoaded(key, this::getCommandsForKeyInternal, this::addCommandsForKeyInternal, this::getIfLoaded);
        if (cfk == null)
            return null;
        if (cfk.isEmpty())
            cfk.initialize(cfkLoader());
        return cfk;
    }

    public CommandsForKeyType commandsForKey(RoutableKey key)
    {
        CommandsForKeyType cfk = getCommandsForKeyInternal(key);
        if (cfk == null)
            throw new IllegalStateException(String.format("%s was not specified in PreLoadContext", key));
        if (cfk.isEmpty())
            cfk.initialize(cfkLoader());
        return cfk;
    }

    protected abstract CommandsForKeyType getIfLoaded(RoutableKey key);

    @VisibleForImplementation
    public CommandsForKeyType maybeCommandsForKey(RoutableKey key)
    {
        CommandsForKeyType cfk = getIfLoaded(key, this::getCommandsForKeyInternal, this::addCommandsForKeyInternal, this::getIfLoaded);
        if (cfk == null || cfk.isEmpty())
            return null;
        return cfk;
    }

    @Override
    public boolean canExecuteWith(PreLoadContext context)
    {
        return context.isSubsetOf(this.context);
    }

    @Override
    public void register(Seekables<?, ?> keysOrRanges, Ranges slice, Command command)
    {
        if (pendingSeekablesRegistrations == null)
            pendingSeekablesRegistrations = new ArrayList<>();
        pendingSeekablesRegistrations.add(new PendingRegistration<>(keysOrRanges, slice, command.txnId()));
    }

    @Override
    public void register(Seekable keyOrRange, Ranges slice, Command command)
    {
        if (pendingSeekableRegistrations == null)
            pendingSeekableRegistrations = new ArrayList<>();
        pendingSeekableRegistrations.add(new PendingRegistration<>(keyOrRange, slice, command.txnId()));
    }

    protected abstract Timestamp maxConflict(Seekables<?, ?> keysOrRanges, Ranges slice);

    @Override
    public Timestamp preaccept(TxnId txnId, Seekables<?, ?> keys)
    {
        Timestamp max = maxConflict(keys, ranges().at(txnId.epoch()));
        long epoch = latestEpoch();
        long now = time().now();
        if (txnId.compareTo(max) > 0 && txnId.epoch() >= epoch && !agent().isExpired(txnId, now))
            return txnId;

        return time().uniqueNow(max);
    }

    public abstract CommonAttributes completeRegistration(Seekables<?, ?> keysOrRanges, Ranges slice, CommandType command, CommonAttributes attrs);

    public abstract CommonAttributes completeRegistration(Seekable keyOrRange, Ranges slice, CommandType command, CommonAttributes attrs);

    private interface RegistrationCompleter<T, CommandType extends SafeCommand>
    {
        CommonAttributes complete(T value, Ranges ranges, CommandType command, CommonAttributes attrs);
    }

    private <T> void completeRegistrations(Map<TxnId, CommonAttributes> updates, List<PendingRegistration<T>> pendingRegistrations, RegistrationCompleter<T, CommandType> completer)
    {
        if (pendingRegistrations == null)
            return;

        for (PendingRegistration<T> pendingRegistration : pendingRegistrations)
        {
            TxnId txnId = pendingRegistration.txnId;
            CommandType safeCommand = command(pendingRegistration.txnId);
            Command command = safeCommand.current();
            CommonAttributes attrs = updates.getOrDefault(txnId, command);
            attrs = completer.complete(pendingRegistration.value, pendingRegistration.slice, safeCommand, attrs);
            if (attrs != command)
                updates.put(txnId, attrs);
        }
    }

    protected abstract void invalidateSafeState();

    public void postExecute()
    {
        if (pendingSeekableRegistrations != null || pendingSeekablesRegistrations != null)
        {
            Map<TxnId, CommonAttributes> attributeUpdates = new HashMap<>();
            completeRegistrations(attributeUpdates, pendingSeekablesRegistrations, this::completeRegistration);
            completeRegistrations(attributeUpdates, pendingSeekableRegistrations, this::completeRegistration);
            attributeUpdates.forEach(((txnId, attributes) -> command(txnId).updateAttributes(attributes)));
        }
    }

    public void complete()
    {
        invalidateSafeState();
    }
}
