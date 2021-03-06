/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.impl.protocol.xmpp;

import net.java.sip.communicator.impl.protocol.jabber.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.health.*;
import net.java.sip.communicator.service.protocol.*;

import net.java.sip.communicator.service.protocol.jabber.*;
import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.osgi.framework.*;

import java.util.*;

/**
 * Bundle activator for {@link XmppProtocolProvider}.
 *
 * @author Pawel Domas
 */
public class XmppProtocolActivator
    implements BundleActivator
{
    private ServiceRegistration<?> focusRegistration;

    static BundleContext bundleContext;

    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        XmppProtocolActivator.bundleContext = bundleContext;

        // FIXME: make sure that we're using interoperability layer
        AbstractSmackInteroperabilityLayer.setImplementationClass(
            SmackV3InteroperabilityLayer.class);

        // Constructors called to register extension providers
        new ConferenceIqProvider();
        new ColibriIQProvider();
        HealthCheckIQProvider.registerIQProvider();

        XmppProviderFactory focusFactory
            = new XmppProviderFactory(
                    bundleContext, ProtocolNames.JABBER);
        Hashtable<String, String> hashtable = new Hashtable<String, String>();

        // Register XMPP
        hashtable.put(ProtocolProviderFactory.PROTOCOL,
                      ProtocolNames.JABBER);

        focusRegistration = bundleContext.registerService(
            ProtocolProviderFactory.class.getName(),
            focusFactory,
            hashtable);
    }

    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        if (focusRegistration != null)
            focusRegistration.unregister();
    }
}
