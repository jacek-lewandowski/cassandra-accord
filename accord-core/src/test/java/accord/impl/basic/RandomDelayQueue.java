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

package accord.impl.basic;

import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class RandomDelayQueue<T> implements PendingQueue
{
    public static class Factory implements Supplier<PendingQueue>
    {
        final Random seeds;

        public Factory(Random seeds)
        {
            this.seeds = seeds;
        }

        @Override
        public PendingQueue get()
        {
            return new RandomDelayQueue<>(new Random(seeds.nextLong()));
        }
    }

    static class Item implements Comparable<Item>
    {
        final long time;
        final int seq;
        final Pending item;

        Item(long time, int seq, Pending item)
        {
            this.time = time;
            this.seq = seq;
            this.item = item;
        }

        @Override
        public int compareTo(Item that)
        {
            int c = Long.compare(this.time, that.time);
            if (c == 0) c = Integer.compare(this.seq, that.seq);
            return c;
        }

        @Override
        public String toString()
        {
            return "@" + time + "/" + seq + ":" + item;
        }
    }

    final PriorityQueue<Item> queue = new PriorityQueue<>();
    final Random random;
    long now;
    int seq;

    RandomDelayQueue(Random random)
    {
        this.random = random;
    }

    @Override
    public void add(Pending item)
    {
        add(item, random.nextInt(500), TimeUnit.MILLISECONDS);
    }

    @Override
    public void add(Pending item, long delay, TimeUnit units)
    {
        queue.add(new Item(now + units.toMillis(delay), seq++, item));
    }

    @Override
    public Pending poll()
    {
        Item item = queue.poll();
        if (item == null)
            return null;
        now = item.time;
        return item.item;
    }

    @Override
    public int size()
    {
        return queue.size();
    }
}
