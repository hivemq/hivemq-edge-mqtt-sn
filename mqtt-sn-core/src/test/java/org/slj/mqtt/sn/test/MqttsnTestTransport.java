/*
 * Copyright (c) 2021 Simon Johnson <simon622 AT gmail DOT com>
 *
 * Find me on GitHub:
 * https://github.com/simon622
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.slj.mqtt.sn.test;

import org.slj.mqtt.sn.impl.AbstractMqttsnTransport;
import org.slj.mqtt.sn.model.INetworkContext;
import org.slj.mqtt.sn.spi.IMqttsnMessage;
import org.slj.mqtt.sn.spi.MqttsnException;
import org.slj.mqtt.sn.utils.StringTable;

/**
 * A no-op transport for unit tests. It satisfies runtimes that require a registered transport
 * (e.g. to create a network context via {@code getDefaultTransport()}) without binding any
 * socket or port, so the registry-level behaviour under test can be exercised in isolation.
 */
public class MqttsnTestTransport extends AbstractMqttsnTransport {

    @Override
    protected void writeToTransportInternal(INetworkContext context, byte[] data) {
        //-- tests do not exercise the wire
    }

    @Override
    public void broadcast(IMqttsnMessage message) throws MqttsnException {
        //-- tests do not exercise the wire
    }

    @Override
    public StringTable getTransportDetails() {
        return new StringTable();
    }

    @Override
    public String getName() {
        return "mqtt-sn-test-transport";
    }

    @Override
    public int getPort() {
        return 0;
    }

    @Override
    public String getDescription() {
        return "In-memory no-op transport used by unit tests.";
    }
}
