/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl.util;

import com.hazelcast.jet.Processor;
import com.hazelcast.jet.ProcessorSupplier;
import com.hazelcast.jet.function.DistributedFunction;

import javax.annotation.Nonnull;
import java.util.Collection;

import static java.util.stream.Collectors.toList;

/**
 * A {@link ProcessorSupplier} which wraps another {@code ProcessorSupplier}
 * with one that will wrap its processors using {@code wrapperSupplier}.
 */
public final class WrappingProcessorSupplier implements ProcessorSupplier {
    private ProcessorSupplier wrapped;
    private DistributedFunction<Processor, Processor> wrapperSupplier;

    public WrappingProcessorSupplier(ProcessorSupplier wrapped,
                                     DistributedFunction<Processor, Processor> wrapperSupplier
    ) {
        this.wrapped = wrapped;
        this.wrapperSupplier = wrapperSupplier;
    }

    @Nonnull
    @Override
    public Collection<? extends Processor> get(int count) {
        Collection<? extends Processor> processors = wrapped.get(count);
        return processors.stream()
                         .map(wrapperSupplier)
                         .collect(toList());
    }

    @Override
    public void init(@Nonnull Context context) {
        wrapped.init(context);
    }

    @Override
    public void complete(Throwable error) {
        wrapped.complete(error);
    }
}