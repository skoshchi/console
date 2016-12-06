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
package org.jboss.hal.client.configuration.subsystem.undertow;

import org.jboss.hal.core.finder.PreviewAttributes;
import org.jboss.hal.core.finder.PreviewContent;
import org.jboss.hal.dmr.model.NamedNode;

import static java.util.Arrays.asList;
import static org.jboss.hal.dmr.ModelDescriptionConstants.SERVLET_CONTAINER;

/**
 * @author Harald Pehl
 */
class UndertowServerPreview extends PreviewContent<NamedNode> {

    @SuppressWarnings("HardCodedStringLiteral")
    UndertowServerPreview(NamedNode server) {
        super(server.getName());

        previewBuilder()
                .addAll(new PreviewAttributes<>(server, asList("default-host", SERVLET_CONTAINER)).end());
    }
}
