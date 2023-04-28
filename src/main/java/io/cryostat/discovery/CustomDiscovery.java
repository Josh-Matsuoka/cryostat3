/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.discovery;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

import io.cryostat.targets.JvmIdException;
import io.cryostat.targets.Target;
import io.cryostat.targets.Target.Annotations;
import io.cryostat.targets.TargetConnectionManager;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.eventbus.EventBus;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;

@ApplicationScoped
@Path("")
public class CustomDiscovery {

    public static final Pattern HOST_PORT_PAIR_PATTERN =
            Pattern.compile("^([^:\\s]+)(?::(\\d{1,5}))$");
    private static final String REALM = "Custom Targets";

    @Inject Logger logger;
    @Inject EventBus bus;
    @Inject TargetConnectionManager connectionManager;

    @Transactional
    void onStart(@Observes StartupEvent evt) {
        DiscoveryNode universe = DiscoveryNode.getUniverse();
        if (DiscoveryNode.getRealm(REALM).isEmpty()) {
            DiscoveryPlugin plugin = new DiscoveryPlugin();
            DiscoveryNode node = DiscoveryNode.environment(REALM, DiscoveryNode.REALM);
            plugin.realm = node;
            plugin.builtin = true;
            universe.children.add(node);
            plugin.persist();
            universe.persist();
        }
    }

    @Transactional(rollbackOn = {JvmIdException.class})
    @POST
    @Path("v2/targets")
    @Consumes("application/json")
    @RolesAllowed("write")
    public Response create(Target target, @RestQuery boolean dryrun) {
        try {
            target.connectUrl = sanitizeConnectUrl(target.connectUrl.toString());

            try {
                if (target.isAgent()) {
                    // TODO test connection
                    target.jvmId = target.connectUrl.toString();
                } else {
                    target.jvmId =
                            connectionManager.executeConnectedTask(target, conn -> conn.getJvmId());
                }
            } catch (Exception e) {
                logger.error("Target connection failed", e);
                return Response.status(400).build();
            }

            if (dryrun) {
                return Response.ok().build();
            }

            target.activeRecordings = new ArrayList<>();
            target.labels = Map.of();
            target.annotations = new Annotations();
            target.annotations.cryostat.putAll(Map.of("REALM", REALM));

            DiscoveryNode node = DiscoveryNode.target(target);
            target.discoveryNode = node;
            DiscoveryNode realm = DiscoveryNode.getRealm(REALM).orElseThrow();

            realm.children.add(node);
            target.persist();
            node.persist();
            realm.persist();

            return Response.created(URI.create("v3/targets/" + target.id)).build();
        } catch (Exception e) {
            if (ExceptionUtils.indexOfType(e, ConstraintViolationException.class) >= 0) {
                logger.warn("Invalid target definition", e);
                return Response.status(400).build();
            }
            logger.error("Unknown error", e);
            return Response.serverError().build();
        }
    }

    @Transactional
    @POST
    @Path("v2/targets")
    @Consumes("multipart/form-data")
    @RolesAllowed("write")
    public Response create(
            @RestForm URI connectUrl, @RestForm String alias, @RestQuery boolean dryrun) {
        var target = new Target();
        target.connectUrl = connectUrl;
        target.alias = alias;

        return create(target, dryrun);
    }

    @Transactional
    @DELETE
    @Path("v2/targets/{connectUrl}")
    @RolesAllowed("write")
    public Response delete(@RestPath URI connectUrl) throws URISyntaxException {
        Target target = Target.getTargetByConnectUrl(connectUrl);
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(URI.create(String.format("/api/v3/targets/%d", target.id)))
                .build();
    }

    @Transactional
    @DELETE
    @Path("v3/targets/{id}")
    @RolesAllowed("write")
    public Response delete(@RestPath long id) throws URISyntaxException {
        Target target = Target.find("id", id).singleResult();
        DiscoveryNode realm = DiscoveryNode.getRealm(REALM).orElseThrow();
        realm.children.remove(target.discoveryNode);
        target.delete();
        realm.persist();
        return Response.ok().build();
    }

    private URI sanitizeConnectUrl(String in) throws URISyntaxException, MalformedURLException {
        URI out;

        Matcher m = HOST_PORT_PAIR_PATTERN.matcher(in);
        if (m.find()) {
            String host = m.group(1);
            String port = m.group(2);
            out =
                    URI.create(
                            String.format(
                                    "service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi",
                                    host, Integer.valueOf(port)));
        } else {
            out = new URI(in);
        }

        return out;
    }
}