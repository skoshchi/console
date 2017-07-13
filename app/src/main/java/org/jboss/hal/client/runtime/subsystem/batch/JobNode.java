/*
 * Copyright 2015-2016 Red Hat, Inc, and individual contributors.
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
package org.jboss.hal.client.runtime.subsystem.batch;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.jboss.hal.client.runtime.subsystem.batch.ExecutionNode.BatchStatus;
import org.jboss.hal.dmr.ModelNode;
import org.jboss.hal.dmr.NamedNode;
import org.jboss.hal.dmr.ResourceAddress;
import org.jboss.hal.resources.Names;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static org.jboss.hal.dmr.ModelDescriptionConstants.DEPLOYMENT;
import static org.jboss.hal.dmr.ModelDescriptionConstants.EXECUTION;
import static org.jboss.hal.dmr.ModelDescriptionConstants.INSTANCE_COUNT;
import static org.jboss.hal.dmr.ModelDescriptionConstants.RUNNING_EXECUTIONS;
import static org.jboss.hal.dmr.ModelDescriptionConstants.SUBDEPLOYMENT;
import static org.jboss.hal.dmr.ModelNodeHelper.failSafePropertyList;

class JobNode extends NamedNode {

    private final ResourceAddress address;
    private final String deployment;
    private final List<ExecutionNode> executions;

    JobNode(ResourceAddress address, ModelNode modelNode) {
        super(address.lastValue(), modelNode);
        this.address = address;
        this.executions = failSafePropertyList(modelNode, EXECUTION).stream().map(ExecutionNode::new).collect(toList());

        StringBuilder name = new StringBuilder();
        address.asList().forEach(segment -> {
            if (segment.hasDefined(DEPLOYMENT)) {
                name.append(segment.get(DEPLOYMENT).asString());
            }
            if (segment.hasDefined(SUBDEPLOYMENT)) {
                name.append("#").append(segment.get(SUBDEPLOYMENT).asString());
            }
        });
        this.deployment = name.length() != 0 ? name.toString() : Names.NOT_AVAILABLE;
    }

    ResourceAddress getAddress() {
        return address;
    }

    String getDeployment() {
        return deployment;
    }

    int getInstanceCount() {
        return hasDefined(INSTANCE_COUNT) ? get(INSTANCE_COUNT).asInt() : 0;
    }

    int getRunningExecutions() {
        return hasDefined(RUNNING_EXECUTIONS) ? get(RUNNING_EXECUTIONS).asInt() : 0;
    }

    boolean hasExecutions(BatchStatus status) {
        return executions.stream().anyMatch(e -> e.getBatchStatus() == status);
    }

    boolean hasExecutions(Set<BatchStatus> status) {
        return executions.stream().anyMatch(e -> status.contains(e.getBatchStatus()));
    }

    Map<Integer, SortedSet<ExecutionNode>> byInstanceIdAllExecutions() {
        // first group by instance-id,
        // then sort multiple executions with the same instance id by last updated time
        return executions.stream()
                .collect(collectingAndThen(groupingBy(ExecutionNode::getInstanceId), input -> {
                    // sort executions by last updated time
                    Map<Integer, SortedSet<ExecutionNode>> result = new HashMap<>(input.size());
                    input.forEach((instanceId, executions) -> {
                        SortedSet<ExecutionNode> byLastUpdate = new TreeSet<>(
                                comparing(ExecutionNode::getLastUpdatedTime));
                        byLastUpdate.addAll(executions);
                        result.put(instanceId, byLastUpdate);
                    });
                    return result;
                }));
    }

    Map<Integer, ExecutionNode> byInstanceIdMostRecentExecution() {
        // first group by instance-id,
        // then keep only the execution with the most recent last updated time
        return executions.stream()
                .collect(collectingAndThen(groupingBy(ExecutionNode::getInstanceId), input -> {
                    // sort executions by last updated time
                    Map<Integer, ExecutionNode> result = new HashMap<>(input.size());
                    input.forEach((instanceId, executions) -> {
                        SortedSet<ExecutionNode> byLastUpdate = new TreeSet<>(
                                comparing(ExecutionNode::getLastUpdatedTime));
                        byLastUpdate.addAll(executions);
                        result.put(instanceId, byLastUpdate.last());
                    });
                    return result;
                }));
    }
}
