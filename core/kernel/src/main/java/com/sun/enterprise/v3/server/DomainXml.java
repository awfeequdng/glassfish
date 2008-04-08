package com.sun.enterprise.v3.server;

import com.sun.enterprise.module.bootstrap.Populator;
import com.sun.enterprise.module.bootstrap.StartupContext;
import com.sun.enterprise.module.ModulesRegistry;
import com.sun.hk2.component.ExistingSingletonInhabitant;
import org.glassfish.config.support.GlassFishDocument;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.component.Habitat;
import org.jvnet.hk2.config.ConfigParser;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.net.URLClassLoader;


/**
 * Locates and parses the portion of <tt>domain.xml</tt> that we care.
 *
 * @author Jerome Dochez
 * @author Kohsuke Kawaguchi
 */
@Service
public class DomainXml implements Populator {

    @Inject
    StartupContext context;

    @Inject
    Logger logger;

    @Inject
    Habitat habitat;

    @Inject
    ModulesRegistry registry;

    @Inject
    XMLInputFactory xif;


    private final static String DEFAULT_DOMAINS_DIR_PROPNAME = "AS_DEF_DOMAINS_PATH";
    private final static String INSTANCE_ROOT_PROP_NAME = "com.sun.aas.instanceRoot";
    private File domainRoot;
    
    public void run(ConfigParser parser) {
        if (logger.isLoggable(Level.FINE)) {
            logger.fine("Startup class : " + this.getClass().getName());
        }

        domainRoot = new File(System.getProperty(INSTANCE_ROOT_PROP_NAME));

        ServerEnvironment env = new ServerEnvironment(domainRoot.getPath(), context);
        habitat.add(new ExistingSingletonInhabitant(ServerEnvironment.class, env));
        habitat.addComponent("parent-class-loader",
                new ExistingSingletonInhabitant(URLClassLoader.class, registry.getParentClassLoader()));
        File domainXml = new File(env.getConfigDirPath(), ServerEnvironment.kConfigXMLFileName);
        
        String instanceName = context.getArguments().get("-instancename");
        if(instanceName == null || instanceName.length() <= 0)
            instanceName = "server";
        
        parseDomainXml(parser, domainXml, instanceName);
     }


    /**
     * Parses <tt>domain.xml</tt>
     */
    private void parseDomainXml(ConfigParser parser, final File domainXml, final String serverName) {
        try {
            DomainXmlReader xsr = new DomainXmlReader(domainXml, serverName);
            parser.parse(xsr, new GlassFishDocument(habitat));
            xsr.close();
            if(!xsr.foundConfig)
                throw new RuntimeException("No <config> seen for name="+xsr.configName);
        } catch (XMLStreamException e) {
            throw new RuntimeException("Failed to parse "+domainXml,e);
        }
    }

    /**
     * {@link XMLStreamReader} that skips irrelvant &lt;config> elements that we shouldn't see.
     */
    private class DomainXmlReader extends XMLStreamReaderFilter {
        /**
         * We need to figure out the configuration name from the server name.
         * Once we find that out, it'll be set here.
         */
        private String configName;
        private final File domainXml;
        private final String serverName;

        /**
         * If we find a matching config, set to true. Used for error detection in case
         * we don't see any config for us.
         */
        private boolean foundConfig;

        /**
         * Because {@link XMLStreamReader} doesn't close the underlying stream,
         * we need to do it by ourselves. So much for the "easy to use" API.
         */
        private FileInputStream stream;

        public DomainXmlReader(File domainXml, String serverName) throws XMLStreamException {
            try {
                stream = new FileInputStream(domainXml);
            } catch (FileNotFoundException e) {
                throw new XMLStreamException(e);
            }
            setParent(xif.createXMLStreamReader(domainXml.toURI().toString(), stream));
            this.domainXml = domainXml;
            this.serverName = serverName;
        }

        public void close() throws XMLStreamException {
            super.close();
            try {
                stream.close();
            } catch (IOException e) {
                throw new XMLStreamException(e);
            }
        }

        boolean filterOut() throws XMLStreamException {
            checkConfigRef(getParent());

            if(getLocalName().equals("config")) {
                if(configName==null) {
                    // we've hit <config> element before we've seen <server>,
                    // so we still don't know which config element to look for.
                    // For us to make this work, we need to parse the file twice
                    parse2ndTime();
                    assert configName!=null;
                }

                // if <config name="..."> didn't match what we are looking for, filter it out
                if(configName.equals(getAttributeValue(null, "name"))) {
                    foundConfig = true;
                    return false;
                }
                return true;
            }

            // we'll read everything else
            return false;
        }

        private void parse2ndTime() throws XMLStreamException {
            logger.info("Forced to parse "+ domainXml +" twice because we didn't see <server> before <config>");
            try {
                InputStream stream = new FileInputStream(domainXml);
                XMLStreamReader xsr = xif.createXMLStreamReader(domainXml.toURI().toString(),stream);
                while(configName==null) {
                    switch(xsr.next()) {
                    case START_ELEMENT:
                        checkConfigRef(xsr);
                        break;
                    case END_DOCUMENT:
                        break;
                    }
                }
                xsr.close();
                stream.close();
                if(configName==null)
                    throw new RuntimeException(domainXml +" contains no <server> element that matches "+ serverName);
            } catch (IOException e) {
                throw new XMLStreamException("Failed to parse "+domainXml,e);
            }
        }

        private void checkConfigRef(XMLStreamReader xsr) {
            String ln = xsr.getLocalName();

            if(configName==null && ln.equals("server")) {
                // is this our <server> element?
                if(serverName.equals(xsr.getAttributeValue(null, "name"))) {
                    configName = xsr.getAttributeValue(null,"config-ref");
                    if(configName==null)
                        throw new RuntimeException("<server> element is missing @config-ref at "+formatLocation(xsr));
                }
            }
        }

        /**
         * Convenience method to return a human-readable location of the parser.
         */
        private String formatLocation(XMLStreamReader xsr) {
            return "line "+xsr.getLocation().getLineNumber()+" at "+xsr.getLocation().getSystemId();
        }
        
    }
}
