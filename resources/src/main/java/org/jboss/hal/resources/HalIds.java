/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
package org.jboss.hal.resources;

import com.google.gwt.i18n.client.Constants;

public interface HalIds extends Constants {

    //@formatter:off
    String endpoints_table();
    String endpoint_form();

    String header_connected_to();
    String header_messages();
    String header_roles();
    String header_username();

    String homepage_access_control();
    String homepage_configuration();
    String homepage_deployments();
    String homepage_patching();
    String homepage_runtime();
    String homepage_runtime_server_group();
    String homepage_runtime_server();
    String homepage_runtime_monitor();

    String root_container();

    String tlc_access_control();
    String tlc_configuration();
    String tlc_deployments();
    String tlc_homepage();
    String tlc_patching();
    String tlc_runtime();
    //@formatter:on
}
