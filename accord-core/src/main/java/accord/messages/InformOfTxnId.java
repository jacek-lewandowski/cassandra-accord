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
import accord.primitives.TxnId;

import java.util.Collections;

import static accord.api.ProgressLog.ProgressShard.Home;
import static accord.messages.SimpleReply.Nack;
import static accord.messages.SimpleReply.Ok;

public class InformOfTxnId extends AbstractEpochRequest<Reply> implements Request, PreLoadContext
{
    public final RoutingKey homeKey;

    public InformOfTxnId(TxnId txnId, RoutingKey homeKey)
    {
        super(txnId);
        this.homeKey = homeKey;
    }

    @Override
    public void process()
    {
        // TODO (expected, efficiency): do not first load txnId
        node.mapReduceConsumeLocal(this, homeKey, txnId.epoch(), this);
    }

    @Override
    public Reply apply(SafeCommandStore safeStore)
    {
        SafeCommand safeCommand = safeStore.command(txnId);
        if (!safeCommand.current().hasBeen(Status.PreAccepted))
        {
            Commands.updateHomeKey(safeStore, safeCommand, homeKey);
            safeStore.progressLog().unwitnessed(txnId, homeKey, Home);
        }
        return Ok;
    }

    @Override
    public void accept(Reply reply, Throwable failure)
    {
        if (reply == null)
            reply = Nack;

        super.accept(reply, failure);
    }

    @Override
    public String toString()
    {
        return "InformOfTxn{txnId:" + txnId + '}';
    }

    @Override
    public MessageType type()
    {
        return MessageType.INFORM_TXNID_REQ;
    }

    @Override
    public long waitForEpoch()
    {
        return txnId.epoch();
    }

    @Override
    public Iterable<TxnId> txnIds()
    {
        // TODO (expected, efficiency): should be empty list, as can be written without existing state
        //                              (though perhaps might check existing in-memory state in case already present)
        return Collections.singleton(txnId);
    }
}
