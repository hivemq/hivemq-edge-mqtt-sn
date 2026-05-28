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

package org.slj.mqtt.tree;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class MqttTreeWildcardTest {

    private MqttTree<String> tree;

    @Before
    public void setup() {
        tree = new MqttTree<>('/', true);
        tree.withMaxMembersAtLevel(1024 * 1024);
    }

    @Test
    public void testTrailingSingleLevelWildcardMatchesExactlyOneLevel() throws Exception {
        tree.subscribe("sport/tennis/+", "ctx");
        Assert.assertEquals("trailing + should match exactly one following level",
                1, tree.search("sport/tennis/player1").size());
        Assert.assertEquals("trailing + should not match a deeper path",
                0, tree.search("sport/tennis/player1/ranking").size());
        Assert.assertEquals("trailing + should not match a shorter path",
                0, tree.search("sport/tennis").size());
    }

    @Test
    public void testMidPathSingleLevelWildcard() throws Exception {
        tree.subscribe("sport/+/player1", "ctx");
        Assert.assertEquals("mid-path + should match one level in that position",
                1, tree.search("sport/tennis/player1").size());
        Assert.assertEquals("mid-path + should not match a different trailing level",
                0, tree.search("sport/tennis/player2").size());
    }

    @Test
    public void testMultiLevelWildcard() throws Exception {
        tree.subscribe("sport/#", "ctx");
        Assert.assertEquals(1, tree.search("sport/tennis/player1").size());
        Assert.assertEquals(1, tree.search("sport/tennis").size());
    }

    @Test
    public void testExactMatch() throws Exception {
        tree.subscribe("a/b/c", "ctx");
        Assert.assertEquals(1, tree.search("a/b/c").size());
        Assert.assertEquals(0, tree.search("a/b/d").size());
    }
}
