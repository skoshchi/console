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
package org.jboss.hal.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.common.base.Strings;
import org.jboss.hal.core.expression.Expression;
import org.jboss.hal.dmr.ModelNode;
import org.jboss.hal.dmr.ModelType;
import org.jboss.hal.dmr.Property;
import org.jboss.hal.dmr.model.Composite;
import org.jboss.hal.dmr.model.Operation;
import org.jboss.hal.dmr.model.ResourceAddress;
import org.jboss.hal.meta.Metadata;
import org.jboss.hal.meta.description.ResourceDescription;
import org.jetbrains.annotations.NonNls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.collect.Sets.intersection;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.jboss.hal.dmr.ModelDescriptionConstants.*;

/**
 * @author Harald Pehl
 */
public class OperationFactory {

    @NonNls private static final Logger logger = LoggerFactory.getLogger(OperationFactory.class);

    /**
     * Turns a change-set into a composite operation containing {@linkplain org.jboss.hal.dmr.ModelDescriptionConstants#WRITE_ATTRIBUTE_OPERATION
     * write-attribute}
     * and {@linkplain org.jboss.hal.dmr.ModelDescriptionConstants#UNDEFINE_ATTRIBUTE_OPERATION undefine-attribute}
     * operations.
     * <p>
     * The composite operation will contain {@linkplain org.jboss.hal.dmr.ModelDescriptionConstants#UNDEFINE_ATTRIBUTE_OPERATION
     * undefine-attribute}
     * operations which reflect the alternative attributes as defined in the specified metadata.
     *
     * @param address   the fq address used for the operations
     * @param changeSet the changed values
     * @param metadata  the metadata which should contain the attribute definitions of the change-set
     */
    public Composite fromChangeSet(final ResourceAddress address, final Map<String, Object> changeSet,
            final Metadata metadata) {

        // TODO Is it safe to always use ATTRIBUTES as path when calling ResourceDescription methods?
        Map<String, Operation> operations = new HashMap<>();
        HashMap<String, Object> localChanges = new HashMap<>(changeSet);
        ResourceDescription resourceDescription = metadata.getDescription();

        // look for alternatives
        Set<String> conflicts = new HashSet<>();
        Map<String, List<String>> allAlternatives = localChanges.keySet().stream()
                .collect(toMap(identity(), name -> resourceDescription.findAlternatives(ATTRIBUTES, name)));
        allAlternatives.forEach((attribute, alternatives) -> {

            logger.debug("Alternatives resolution for {} -> [{}]", attribute, String.join(", ", alternatives));
            Set<String> intersection = intersection(new HashSet<>(alternatives), changeSet.keySet()).immutableCopy();
            if (intersection.isEmpty()) {

                // the easy part: no conflicts
                if (!alternatives.isEmpty()) {
                    logger.debug("Add undefine operations for alternatives [{}]", String.join(", ", alternatives));
                    alternatives.forEach(alternative -> {
                        operations.putIfAbsent(alternative, undefineAttribute(address, alternative));
                        List<String> requires = resourceDescription.findRequires(ATTRIBUTES, alternative);
                        if (!requires.isEmpty()) {
                            logger.debug("Add undefine operations for attributes which require {}: [{}]", alternative,
                                    String.join(", ", requires));
                            requires.forEach(r -> operations.putIfAbsent(r, undefineAttribute(address, r)));
                        }
                    });
                }

            } else {
                // possible conflicts: one or more alternatives are also in the change-set
                // just collect for now and resolve later
                conflicts.add(attribute);
                conflicts.addAll(intersection);
                logger.debug("Record conflict {} <-> {[]}", attribute, String.join(", ", intersection));
            }

            alternatives.forEach(localChanges::remove);
        });

        if (!conflicts.isEmpty()) {
            // try to resolve conflicts: only one of the conflicting attributes must have a value other than
            // null, empty or default
            logger.debug("Try to resolve conflicts between alternatives {[]}", String.join(", ", conflicts));
            Map<Boolean, List<String>> resolution = conflicts.stream().collect(groupingBy(conflict -> {
                Object value = changeSet.get(conflict);
                return isNullOrEmpty(value) || resourceDescription.isDefaultValue(ATTRIBUTES, conflict, value);
            }));
            List<String> undefine = resolution.getOrDefault(true, Collections.emptyList());
            List<String> write = resolution.getOrDefault(false, Collections.emptyList());
            if (write.size() > 1) {
                logger.error(
                        "More than one conflicting alternative attribute which is not null, empty or default: {[]}. This should have been caught by a form validation. Adding the write operations anyway to get an appropriate error message from the server.",
                        String.join(", ", write));
            }

            logger.debug("Add undefine operations for {[]}, write operation for {[]}",
                    String.join(", ", undefine), String.join(", ", write));
            undefine.forEach(u -> {
                operations.putIfAbsent(u, undefineAttribute(address, u));
                localChanges.remove(u);
                // process requires of the current undefine attribute
                List<String> requires = resourceDescription.findRequires(ATTRIBUTES, u);
                requires.forEach(ur -> {
                    operations.putIfAbsent(ur, undefineAttribute(address, ur));
                    localChanges.remove(ur);
                });
            });

            write.forEach(w -> {
                operations.putIfAbsent(w, writeAttribute(address, w, changeSet.get(w), resourceDescription));
                localChanges.remove(w);
                List<String> writeAlternatives = resourceDescription.findAlternatives(ATTRIBUTES, w);
                // process alternatives of the current write attribute
                writeAlternatives.forEach(wa -> {
                    operations.putIfAbsent(wa, undefineAttribute(address, wa));
                    localChanges.remove(wa);
                });
            });
        }

        // handle the remaining attributes
        logger.debug("Process remaining attributes {[]}", String.join(", ", localChanges.keySet()));
        localChanges.forEach((name, value) ->
                operations.putIfAbsent(name, writeAttribute(address, name, value, resourceDescription)));
        return new Composite(operations.values().stream().filter(Objects::nonNull).collect(toList()));
    }

    /**
     * Creates a composite operation which resets the attributes of the specified resource. Only attributes which are
     * nillable, w/o alternatives and not read-only will be reset. The composite contains {@linkplain
     * org.jboss.hal.dmr.ModelDescriptionConstants#UNDEFINE_ATTRIBUTE_OPERATION undefine-attribute} operations for each
     * attribute of type {@code EXPRESSION, LIST, OBJECT, PROPERTY} or {@code STRING} and {@linkplain
     * org.jboss.hal.dmr.ModelDescriptionConstants#WRITE_ATTRIBUTE_OPERATION write-attribute} operations for attributes
     * of type {@code BIG_DECIMAL, BIG_INTEGER, BOOLEAN, BYTES, DOUBLE, INT} or {@code LONG} if they have a default
     * value.
     *
     * @param address    the fq address used for the operations
     * @param attributes the attributes to reset
     * @param metadata   the metadata which should contain the attribute definitions of the change-set
     *
     * @return a composite to reset the attributes or an empty composite if no attributes could be reset.
     */
    Composite resetResource(final ResourceAddress address, final Set<String> attributes,
            final Metadata metadata) {
        List<Operation> operations = new ArrayList<>();
        ResourceDescription description = metadata.getDescription();

        attributes.stream()
                .map(attribute -> description.findAttribute(ATTRIBUTES, attribute))
                .filter(Objects::nonNull)
                .forEach(property -> {
                    ModelNode attributeDescription = property.getValue();
                    boolean nillable = attributeDescription.hasDefined(NILLABLE) &&
                            attributeDescription.get(NILLABLE).asBoolean();
                    boolean readOnly = attributeDescription.hasDefined(ACCESS_TYPE) &&
                            READ_ONLY.equals(attributeDescription.get(ACCESS_TYPE).asString());
                    boolean alternatives = attributeDescription.hasDefined(ALTERNATIVES) &&
                            !attributeDescription.get(ALTERNATIVES).asList().isEmpty();

                    if (nillable && !readOnly && !alternatives) {
                        boolean hasDefault = attributeDescription.hasDefined(DEFAULT);
                        ModelType type = attributeDescription.get(TYPE).asType();
                        switch (type) {
                            case BIG_DECIMAL:
                            case BIG_INTEGER:
                            case BOOLEAN:
                            case BYTES:
                            case DOUBLE:
                            case INT:
                            case LONG:
                                if (hasDefault) {
                                    operations.add(new Operation.Builder(WRITE_ATTRIBUTE_OPERATION, address)
                                            .param(NAME, property.getName())
                                            .param(VALUE, attributeDescription.get(DEFAULT))
                                            .build());
                                }
                                break;
                            case EXPRESSION:
                            case LIST:
                            case OBJECT:
                            case PROPERTY:
                            case STRING:
                                operations.add(new Operation.Builder(UNDEFINE_ATTRIBUTE_OPERATION, address)
                                        .param(NAME, property.getName())
                                        .build());
                                break;
                            case TYPE:
                            case UNDEFINED:
                                break;
                        }
                    }
                });
        return new Composite(operations);
    }

    private boolean isNullOrEmpty(Object value) {
        return (value == null
                || (value instanceof String && (Strings.isNullOrEmpty((String) value)))
                || (value instanceof List && ((List) value).isEmpty())
                || (value instanceof Map && ((Map) value).isEmpty()));
    }

    private Operation undefineAttribute(ResourceAddress address, String name) {
        return new Operation.Builder(UNDEFINE_ATTRIBUTE_OPERATION, address).param(NAME, name).build();
    }

    private Operation writeAttribute(ResourceAddress address, String name, Object value,
            ResourceDescription resourceDescription) {
        if (isNullOrEmpty(value) || resourceDescription.isDefaultValue(ATTRIBUTES, name, value)) {
            return undefineAttribute(address, name);

        } else {
            ModelNode valueNode = asValueNode(name, value, resourceDescription);
            if (valueNode != null) {
                return new Operation.Builder(WRITE_ATTRIBUTE_OPERATION, address)
                        .param(NAME, name)
                        .param(VALUE, valueNode)
                        .build();
            } else {
                return null;
            }
        }
    }

    @SuppressWarnings("DuplicateStringLiteralInspection")
    private ModelNode asValueNode(String name, Object value, ResourceDescription resourceDescription) {
        ModelNode valueNode = new ModelNode();

        Property attribute = resourceDescription.findAttribute(ATTRIBUTES, name);
        if (attribute != null) {
            ModelNode attributeDescription = attribute.getValue();
            if (attributeDescription.hasDefined(EXPRESSIONS_ALLOWED) &&
                    attributeDescription.get(EXPRESSIONS_ALLOWED).asBoolean() &&
                    Expression.isExpression(String.valueOf(value))) {
                valueNode.setExpression(String.valueOf(value));
            } else {
                ModelType type = attributeDescription.get(TYPE).asType();
                try {
                    switch (type) {
                        case BIG_DECIMAL:
                            valueNode.set(BigDecimal.valueOf((Double) value).doubleValue());
                            break;
                        case BIG_INTEGER:
                            valueNode.set(BigInteger.valueOf((Long) value).longValue());
                            break;
                        case BOOLEAN:
                            valueNode.set((Boolean) value);
                            break;
                        case BYTES:
                            valueNode.set((byte[]) value);
                            break;
                        case DOUBLE:
                            valueNode.set((Double) value);
                            break;
                        case EXPRESSION:
                            valueNode.setExpression((String) value);
                            break;
                        case INT:
                            valueNode.set(((Long) value).intValue());
                            break;
                        case LIST: {
                            ModelType valueType = attributeDescription.hasDefined(VALUE_TYPE)
                                    ? attributeDescription.get(VALUE_TYPE).asType()
                                    : ModelType.UNDEFINED;
                            if (valueType == ModelType.STRING) {
                                valueNode.clear();
                                List l = (List) value;
                                for (Object o : l) { valueNode.add(String.valueOf(o)); }
                            } else {
                                valueNode = null;
                                logger.error("Unsupported value type {} for attribute {} of type {}", valueType, name,
                                        type);
                            }
                            break;
                        }
                        case LONG:
                            valueNode.set((Long) value);
                            break;
                        case OBJECT:
                            ModelType valueType = attributeDescription.hasDefined(VALUE_TYPE)
                                    ? attributeDescription.get(VALUE_TYPE).asType()
                                    : ModelType.UNDEFINED;
                            if (valueType == ModelType.STRING) {
                                Map map = (Map) value;
                                for (Object k : map.keySet()) {
                                    valueNode.get(String.valueOf(k)).set(String.valueOf(map.get(k)));
                                }
                            } else {
                                valueNode = null;
                                logger.error("Unsupported value type {} for attribute {} of type {}", valueType, name,
                                        type);
                            }
                            break;
                        case STRING:
                            valueNode.set((String) value);
                            break;

                        case PROPERTY:
                        case TYPE:
                        case UNDEFINED:
                            valueNode = null;
                            logger.error("Unsupported type {} for attribute {}", type, name);
                            break;
                    }
                } catch (ClassCastException e) {
                    logger.error("Unable to cast attribute {} as {}", name, type);
                }
            }

        } else {
            Class<?> clazz = value.getClass();
            logger.warn("Unable to get type information for attribute {}. Will use its class instead ({})", name,
                    clazz);
            try {
                if (String.class == clazz) {
                    String stringValue = (String) value;
                    if (Expression.isExpression(stringValue)) {
                        valueNode.setExpression(stringValue);
                    } else {
                        valueNode.set(stringValue);
                    }
                } else if (Boolean.class == clazz) {
                    valueNode.set((Boolean) value);
                } else if (Integer.class == clazz) {
                    valueNode.set((Integer) value);
                } else if (Double.class == clazz) {
                    valueNode.set((Double) value);
                } else if (Long.class == clazz) {
                    valueNode.set((Long) value);
                } else if (Float.class == clazz) {
                    valueNode.set((Float) value);
                } else if (ArrayList.class == clazz) {
                    valueNode.clear();
                    List l = (List) value;
                    for (Object o : l) { valueNode.add(String.valueOf(o)); }
                } else if (HashMap.class == clazz) {
                    valueNode.clear();
                    Map map = (Map) value;
                    for (Object k : map.keySet()) {
                        valueNode.get(String.valueOf(k)).set(String.valueOf(map.get(k)));
                    }
                } else if (ModelNode.class == clazz) {
                    valueNode.set((ModelNode) value);
                } else {
                    valueNode = null;
                    logger.error("Unsupported class {} for attribute {}", clazz, name);
                }
            } catch (ClassCastException e) {
                logger.error("Unable to cast attribute {} as {}", name, clazz);
            }
        }
        return valueNode;
    }
}