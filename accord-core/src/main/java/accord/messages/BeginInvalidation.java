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

package accord.messages;

import accord.api.RoutingKey;
import accord.local.*;
import accord.local.Node.Id;
import accord.primitives.*;
import accord.topology.Topologies;
import accord.primitives.Ballot;
import accord.primitives.Route;
import accord.primitives.TxnId;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

import static accord.primitives.Route.castToFullRoute;
import static accord.primitives.Route.isFullRoute;
import static accord.utils.Functions.mapReduceNonNull;

public class BeginInvalidation extends AbstractEpochRequest<BeginInvalidation.InvalidateReply> implements Request, PreLoadContext
{
    public final Ballot ballot;
    public final Unseekables<?, ?> someUnseekables;

    public BeginInvalidation(Id to, Topologies topologies, TxnId txnId, Unseekables<?, ?> someUnseekables, Ballot ballot)
    {
        super(txnId);
        this.someUnseekables = someUnseekables.slice(topologies.computeRangesForNode(to));
        this.ballot = ballot;
    }

    public BeginInvalidation(TxnId txnId, Unseekables<?, ?> someUnseekables, Ballot ballot)
    {
        super(txnId);
        this.someUnseekables = someUnseekables;
        this.ballot = ballot;
    }

    @Override
    public void process()
    {
        node.mapReduceConsumeLocal(this, someUnseekables, txnId.epoch(), txnId.epoch(), this);
    }

    @Override
    public InvalidateReply apply(SafeCommandStore instance)
    {
        boolean isOk = Commands.preacceptInvalidate(instance, txnId, ballot);
        SafeCommand safeCommand = instance.command(txnId);
        Command command = safeCommand.current();
        Ballot supersededBy = isOk ? null : command.promised();
        boolean acceptedFastPath = command.executeAt() != null && command.executeAt().equals(command.txnId());
        return new InvalidateReply(supersededBy, command.accepted(), command.status(), acceptedFastPath, command.route(), command.homeKey());
    }

    @Override
    public InvalidateReply reduce(InvalidateReply o1, InvalidateReply o2)
    {
        // since the coordinator treats a node's response as a collective answer for the keys it owns
        // we can safely take any reject from one key as a reject for the whole node
        // unfortunately we must also treat the promise rejection as pan-node, even though we only need
        // a single key to accept a promise globally for the invalidation to be able to succeed
        boolean isOk = o1.isPromised() && o2.isPromised();
        Ballot supersededBy = isOk ? null : Ballot.nonNullOrMax(o1.supersededBy, o2.supersededBy);
        boolean acceptedFastPath = o1.acceptedFastPath && o2.acceptedFastPath;
        Route<?> route =  Route.merge((Route)o1.route, o2.route);
        RoutingKey homeKey = o1.homeKey != null ? o1.homeKey : o2.homeKey != null ? o2.homeKey : null;
        InvalidateReply maxStatus = Status.max(o1, o1.status, o1.accepted, o2, o2.status, o2.accepted);
        return new InvalidateReply(supersededBy, maxStatus.accepted, maxStatus.status, acceptedFastPath, route, homeKey);
    }

    @Override
    public Iterable<TxnId> txnIds()
    {
        return Collections.singleton(txnId);
    }

    @Override
    public Seekables<?, ?> keys()
    {
        return Keys.EMPTY;
    }

    @Override
    public long waitForEpoch()
    {
        return txnId.epoch();
    }

    @Override
    public MessageType type()
    {
        return MessageType.BEGIN_INVALIDATE_REQ;
    }

    @Override
    public String toString()
    {
        return "BeginInvalidate{" +
               "txnId:" + txnId +
               ", ballot:" + ballot +
               '}';
    }

    public static class InvalidateReply implements Reply
    {
        public final Ballot supersededBy;
        public final Ballot accepted;
        public final Status status;
        public final boolean acceptedFastPath;
        public final @Nullable Route<?> route;
        public final @Nullable RoutingKey homeKey;

        public InvalidateReply(Ballot supersededBy, Ballot accepted, Status status, boolean acceptedFastPath, @Nullable Route<?> route, @Nullable RoutingKey homeKey)
        {
            this.supersededBy = supersededBy;
            this.accepted = accepted;
            this.status = status;
            this.acceptedFastPath = acceptedFastPath;
            this.route = route;
            this.homeKey = homeKey;
        }

        public boolean isPromised()
        {
            return supersededBy == null;
        }

        @Override
        public String toString()
        {
            return "Invalidate" + (isPromised() ? "Promised{" : "NotPromised{" + supersededBy + ",") + status + ',' + (route != null ? route: homeKey) + '}';
        }

        @Override
        public MessageType type()
        {
            return MessageType.BEGIN_INVALIDATE_RSP;
        }

        public static FullRoute<?> findRoute(List<InvalidateReply> invalidateOks)
        {
            for (InvalidateReply ok : invalidateOks)
            {
                if (isFullRoute(ok.route))
                    return castToFullRoute(ok.route);
            }
            return null;
        }

        public static Route<?> mergeRoutes(List<InvalidateReply> invalidateOks)
        {
            return mapReduceNonNull(ok -> (Route)ok.route, Route::union, invalidateOks);
        }

        public static InvalidateReply max(List<InvalidateReply> invalidateReplies)
        {
            return Status.max(invalidateReplies, r -> r.status, r -> r.accepted, invalidateReply -> true);
        }

        public static RoutingKey findHomeKey(List<InvalidateReply> invalidateOks)
        {
            for (InvalidateReply ok : invalidateOks)
            {
                if (ok.homeKey != null)
                    return ok.homeKey;
            }
            return null;
        }
    }
}
